package com.ziyair.jarvis

import android.app.Notification
import android.content.Context
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

    fun minPerMile(p: Prefs) = p.sp.getFloat("min_per_mile", 2f).toDouble()
    fun on(p: Prefs) = p.sp.getBoolean("judge_offers", true)

    fun setRule(context: Context, perMile: Double?, on: Boolean?): String {
        val p = Prefs(context)
        perMile?.let { p.sp.edit().putFloat("min_per_mile", it.toFloat()).apply() }
        on?.let { p.sp.edit().putBoolean("judge_offers", it).apply() }
        return if (!on(p)) "Order judging is off." else "Judging offers at ${Earnings.money(minPerMile(p))} a mile."
    }

    /** Called with all the text on the Dasher screen. */
    fun check(context: Context, texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 700) return
        lastCheck = now
        val p = Prefs(context)
        if (!on(p) || !p.running) return
        val all = texts.joinToString(" | ")
        if (!Regex("\\baccept\\b", RegexOption.IGNORE_CASE).containsMatchIn(all)) return
        val pay = Regex("\\$\\s?(\\d{1,3}(?:\\.\\d{2})?)").findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it in 1.0..200.0 }.maxOrNull() ?: return
        val miles = Regex("(\\d{1,3}(?:\\.\\d)?)\\s?mi\\b", RegexOption.IGNORE_CASE).findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it > 0 }.maxOrNull() ?: return
        val sig = "$pay|$miles"
        if (sig == lastSig && now - lastAt < 120_000) return
        lastSig = sig; lastAt = now
        val rate = pay / miles
        val min = minPerMile(p)
        val payWords = if (pay == Math.floor(pay)) "${pay.toInt()} dollars" else String.format(Locale.US, "%.2f dollars", pay)
        val milesWords = if (miles == Math.floor(miles)) "${miles.toInt()} miles" else "$miles miles"
        val rateWords = String.format(Locale.US, "%.2f", rate).let { if (rate < 1) "${Math.round(rate * 100)} cents" else "$it dollars" }
        val verdict = when {
            rate >= min * 1.25 -> "That's $rateWords a mile. Good one, sir, take it."
            rate >= min -> "$rateWords a mile. Worth taking."
            rate >= min * 0.85 -> "$rateWords a mile. Borderline, your call."
            else -> "Only $rateWords a mile. I'd pass."
        }
        try {
            context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", "$payWords for $milesWords. $verdict"))
        } catch (_: Exception) {}
    }
}
