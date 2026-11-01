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

/** Pursuit idea vault on the phone (lab L12): load, change and save in one go. */
object IdeaStore {
    private fun <T> edit(c: Context, f: (MutableList<IdeaVault.Idea>) -> T): T = synchronized(IdeaVault) {
        val p = Prefs(c)
        val l = IdeaVault.load(p.sp.getString("pursuit_ideas", ""))
        val r = f(l)
        p.sp.edit().putString("pursuit_ideas", IdeaVault.dump(l)).apply()
        r
    }

    /** What Jarvis says if [said] is an idea-vault command, else null. */
    fun handle(c: Context, said: String): String? {
        val cmd = IdeaVault.parse(said) ?: return null
        return edit(c) { IdeaVault.handle(it, cmd, System.currentTimeMillis()) }
    }

    /** For Claude's tools. */
    fun save(c: Context, text: String, kind: String?): String {
        if (text.isBlank()) return "Nothing to save."
        val k = kind?.trim()?.lowercase()?.takeIf { it in IdeaVault.KINDS || it == "idea" }
        return edit(c) { IdeaVault.handle(it, IdeaVault.Cmd.Add(text.trim(), k), System.currentTimeMillis()) }
    }

    fun search(c: Context, about: String?, kind: String?): String {
        val f = SimpleDateFormat("MMM d", Locale.US)
        return edit(c) { IdeaVault.forClaude(it, about, kind?.trim()?.lowercase()) { at -> f.format(Date(at)) } }
    }
}

/** Car reminders on the phone (lab L26): load, change and save in one go. */
object CarRemindersStore {
    private fun <T> edit(c: Context, f: (MutableList<CarReminders.Item>) -> T): T = synchronized(CarReminders) {
        val p = Prefs(c)
        val l = CarReminders.load(p.sp.getString("car_reminders", ""))
        val r = f(l)
        p.sp.edit().putString("car_reminders", CarReminders.dump(l)).apply()
        r
    }

    /** What Jarvis says if [said] is a car-reminder command, else null. */
    fun handle(c: Context, said: String): String? {
        val cmd = CarReminders.parse(said) ?: return null
        return edit(c) { CarReminders.handle(it, cmd, Prefs(c).carName.isNotBlank(), DriveMode.on, System.currentTimeMillis()) }
    }

    /** Anything waiting for when he gets in ([out] false) or out ([out] true). */
    fun has(c: Context, out: Boolean): Boolean = edit(c) { l -> l.any { it.out == out } }

    /** For Claude's remind_in_car tool. */
    fun add(c: Context, text: String, out: Boolean): String =
        edit(c) { CarReminders.handle(it, CarReminders.Cmd.Add(text.trim(), out), Prefs(c).carName.isNotBlank(), DriveMode.on, System.currentTimeMillis()) }

    /** " Reminder: …" for what's due as he gets in ([out] false) or out ([out] true); "" when nothing. */
    fun due(c: Context, out: Boolean): String = edit(c) { CarReminders.line(CarReminders.take(it, out, System.currentTimeMillis())) }
}

/** Call notes on the phone (lab L17): load, change and save the notebook in one go. Never leaves the phone. */
object CallNotesStore {
    private fun <T> edit(c: Context, f: (CallNotes.Book) -> T): T = synchronized(CallNotes) {
        val p = Prefs(c)
        val b = CallNotes.load(p.sp.getString("call_notes", ""))
        val r = f(b)
        p.sp.edit().putString("call_notes", CallNotes.dump(b)).apply()
        r
    }

    fun called(c: Context, who: String) { edit(c) { CallNotes.called(it, who, System.currentTimeMillis()) } }
    fun ringNote(c: Context, who: String): String = edit(c) { CallNotes.ringNote(it, who) }
    fun handle(c: Context, t: String): String? {
        val cmd = CallNotes.parse(t) ?: return null
        return edit(c) { CallNotes.handle(it, cmd, System.currentTimeMillis()) }
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
        if (!on(p) || !p.running || Stealth.on(p)) return
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
