package com.ziyair.jarvis

import android.app.Notification
import android.content.Context
import java.util.Calendar
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Baby journal: little moments ("he smiled today") kept with dates to look back on. */
object Journal {
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("journal", "[]")) } catch (_: Exception) { JSONArray() }

    fun add(context: Context, text: String): String {
        val p = Prefs(context); val a = all(p)
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("t", text.trim()))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 1000) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("journal", keep.toString()).apply()
        return "Saved to the baby journal."
    }

    fun delete(context: Context, at: Long) {
        val p = Prefs(context); val a = all(p); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") != at) keep.put(a.get(i))
        p.sp.edit().putString("journal", keep.toString()).apply()
    }

    fun read(context: Context, search: String?): String {
        val a = all(Prefs(context))
        val f = SimpleDateFormat("EEE MMM d", Locale.US)
        val w = search?.trim()?.lowercase().orEmpty()
        val items = (0 until a.length()).map { a.getJSONObject(it) }.filter { w.isBlank() || it.optString("t").lowercase().contains(w) }.takeLast(12)
        return if (items.isEmpty()) "Nothing in the journal${if (w.isNotBlank()) " about $search" else ""} yet."
            else items.joinToString("; ") { f.format(Date(it.optLong("at"))) + ": " + it.optString("t") }
    }
}

/** "Remind me when I get home": said out loud with the welcome-home greeting. */
object HomeReminders {
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("home_reminders", "[]")) } catch (_: Exception) { JSONArray() }
    fun add(context: Context, text: String): String {
        val p = Prefs(context); val a = all(p); a.put(text.trim())
        p.sp.edit().putString("home_reminders", a.toString()).apply()
        return "I'll remind him when he gets home (when the phone joins home Wi-Fi)."
    }
    /** Returns and clears them. */
    fun take(context: Context): List<String> {
        val p = Prefs(context); val a = all(p)
        p.sp.edit().putString("home_reminders", "[]").apply()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
}

/** The phone's ringing-call notification, so Jarvis can answer or decline by voice. */
object CallControl {
    @Volatile private var answer: Notification.Action? = null
    @Volatile private var decline: Notification.Action? = null
    @Volatile private var at = 0L

    fun ringing(answer: Notification.Action?, decline: Notification.Action?) { this.answer = answer; this.decline = decline; at = System.currentTimeMillis() }
    fun ringingNow() = System.currentTimeMillis() - at < 45_000 && (answer != null || decline != null)
    fun ended() { answer = null; decline = null; at = 0L }

    fun answer(context: Context): String = fire(context, answer, "Answered.")
    fun decline(context: Context): String = fire(context, decline, "Declined.")

    private fun fire(context: Context, a: Notification.Action?, ok: String): String {
        if (!ringingNow() || a == null) return "Nothing's ringing right now."
        return try { a.actionIntent.send(); ended(); ok } catch (_: Exception) { "The phone app didn't respond." }
    }
}

/**
 * DoorDash order judge: when a Dasher offer is on screen, Jarvis reads the pay and miles and gives a verdict
 * against his rule (default $2 a mile). He still taps Accept or Decline himself.
 */
object OfferJudge {
    const val DASHER = "com.doordash.driverapp"
    private var lastSig = ""; private var lastAt = 0L; private var lastCheck = 0L
    private const val KEEP = 1500

    fun minPerMile(p: Prefs) = p.sp.getFloat("min_per_mile", 2f).toDouble()
    fun minPerHour(p: Prefs) = p.sp.getFloat("min_per_hour", 20f).toDouble()
    fun on(p: Prefs) = p.sp.getBoolean("judge_offers", true)

    fun setRule(context: Context, perMile: Double?, on: Boolean?, perHour: Double? = null): String {
        val p = Prefs(context)
        perMile?.let { p.sp.edit().putFloat("min_per_mile", it.toFloat()).apply() }
        perHour?.let { p.sp.edit().putFloat("min_per_hour", it.toFloat()).apply() }
        on?.let { p.sp.edit().putBoolean("judge_offers", it).apply() }
        return if (!on(p)) "Order judging is off."
        else "Judging offers at ${Earnings.money(minPerMile(p))} a mile and ${Earnings.money(minPerHour(p))} an hour."
    }

    /** Called with all the text on the Dasher screen. */
    fun check(context: Context, texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 700) return
        lastCheck = now
        val p = Prefs(context)
        if (!on(p) || !p.running || Stealth.on(p)) return
        val cal = Calendar.getInstance()
        val o = OfferMath.parse(texts.joinToString(" | "), cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) ?: return
        val sig = "${o.pay}|${o.miles}"
        if (sig == lastSig && now - lastAt < 120_000) return
        lastSig = sig; lastAt = now
        try { remember(p, cal.get(Calendar.HOUR_OF_DAY), o) } catch (e: Exception) { Problems.log(context, "offer diary: ${e.message}") }
        try {
            context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY)
                .putExtra("text", OfferMath.line(o, minPerMile(p), minPerHour(p))))
        } catch (_: Exception) {}
    }

    // ---- offer diary (lab L1): every judged offer, kept only on the phone ----
    fun diary(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("offer_log", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized private fun remember(p: Prefs, hour: Int, o: OfferMath.Offer) {
        val a = diary(p)
        val e = JSONObject().put("at", System.currentTimeMillis()).put("h", hour).put("pay", o.pay).put("mi", o.miles)
        o.minutes?.let { e.put("min", it) }
        a.put(e)
        val keep = JSONArray(); for (i in maxOf(0, a.length() - KEEP) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("offer_log", keep.toString()).apply()
    }

    fun insights(context: Context, action: String?): String {
        val p = Prefs(context)
        if (action == "clear") { p.sp.edit().putString("offer_log", "[]").apply(); return "Offer diary cleared." }
        val a = diary(p)
        val seen = (0 until a.length()).map { a.getJSONObject(it) }.map {
            OfferMath.Seen(it.optInt("h"), it.optDouble("pay"), it.optDouble("mi"), if (it.has("min")) it.optInt("min") else null)
        }
        return OfferMath.bestHours(seen)
    }
}
