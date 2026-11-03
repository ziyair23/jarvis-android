package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * He doesn't dash every day. Jarvis works out which days he does, so shifts, targets and "next shift" only show up
 * on days he's actually dashing:
 *  - he says so ("not dashing today", "day off tomorrow", "I'm dashing tonight"), or taps it in the app;
 *  - he's dashing: the Dasher app is sending notifications or showing offers, he ran the dash protocol, or logged earnings;
 *  - otherwise, his usual pattern: a weekday he's dashed on at least 3 of the last 4 weeks counts as "probably".
 */
object DashDays {
    enum class Today { ON, OFF, LIKELY, NO }

    private val ymd get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun key(cal: Calendar): String = ymd.format(cal.time)
    private fun all(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("dash_days", "{}")) } catch (_: Exception) { JSONObject() }

    /** Records a day as dashing ("on") or off. What he says or does on the day always wins over the pattern. */
    @Synchronized fun mark(c: Context, day: Calendar, on: Boolean, source: String) {
        val a = all(c); val k = key(day)
        val was = a.optJSONObject(k)
        // something seen automatically doesn't undo what he said, except actually dashing (he changed his mind)
        if (was != null && was.optBoolean("on") == on) return
        a.put(k, JSONObject().put("on", on).put("src", source).put("at", System.currentTimeMillis()))
        val keep = JSONObject(); val cutoff = (Calendar.getInstance().clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -70) }.let { key(it) }
        for (kk in a.keys()) if (kk >= cutoff) keep.put(kk, a.get(kk))
        Prefs(c).sp.edit().putString("dash_days", keep.toString()).apply()
        Trace.log(c, "dash", "$k ${if (on) "dashing" else "off"} ($source)")
        LogBus.changed()
    }

    /** Seen dashing right now (Dasher notification, an offer, the dash protocol, earnings logged). Cheap to call often. */
    fun seen(c: Context, source: String) {
        val now = Calendar.getInstance()
        if (all(c).optJSONObject(key(now))?.optBoolean("on") == true) return
        mark(c, now, true, source)
    }

    /** Days he dashed: marked days plus days with earnings logged. */
    fun history(c: Context): Map<String, Boolean> {
        val m = HashMap<String, Boolean>()
        try { val e = Earnings.all(Prefs(c)); for (i in 0 until e.length()) { val o = e.getJSONObject(i); if (o.optDouble("a") > 0) m[o.optString("d")] = true } } catch (_: Exception) {}
        val a = all(c); for (k in a.keys()) m[k] = a.getJSONObject(k).optBoolean("on")
        return m
    }

    /** Does he usually dash on this weekday? At least 3 of the last 4 same weekdays. Pure, unit-tested. */
    fun usual(days: Map<String, Boolean>, day: Calendar): Boolean {
        var n = 0
        for (w in 1..4) { val d = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7 * w) }; if (days[key(d)] == true) n++ }
        return n >= 3
    }

    /** What a given day looks like. Pure over the data. */
    fun state(days: Map<String, Boolean>, day: Calendar): Today = when (days[key(day)]) {
        true -> Today.ON
        false -> Today.OFF
        else -> if (usual(days, day)) Today.LIKELY else Today.NO
    }

    fun on(c: Context, day: Calendar = Calendar.getInstance()): Today = state(history(c), day)
    fun dashing(c: Context, day: Calendar = Calendar.getInstance()) = on(c, day).let { it == Today.ON || it == Today.LIKELY }

    /** The weekdays he usually dashes, e.g. "Mon, Tue, Fri". Pure. */
    fun usualDays(days: Map<String, Boolean>, today: Calendar): String =
        (0..6).map { (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, it) } }.filter { usual(days, it) }
            .sortedBy { (it.get(Calendar.DAY_OF_WEEK) + 5) % 7 }.joinToString(", ") { SimpleDateFormat("EEE", Locale.US).format(it.time) }

    /** For the team brief: today, tomorrow and his usual days. */
    fun line(c: Context): String {
        val h = history(c); val t = Calendar.getInstance(); val tm = (t.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun say(s: Today) = when (s) { Today.ON -> "dashing"; Today.OFF -> "day off (he said)"; Today.LIKELY -> "probably dashing (his usual)"; Today.NO -> "probably not dashing" }
        val usual = usualDays(h, t)
        return "Today ${say(state(h, t))}; tomorrow ${say(state(h, tm))}. " + (if (usual.isNotBlank()) "He usually dashes on $usual." else "No regular dash days yet.") +
            " He does NOT dash every day: only plan around shifts, targets or offers on days he's dashing."
    }

    /** "dash_day" from voice or the app: on/off, today or tomorrow. */
    fun set(c: Context, on: Boolean, tomorrow: Boolean): String {
        val d = Calendar.getInstance().apply { if (tomorrow) add(Calendar.DAY_OF_YEAR, 1) }
        mark(c, d, on, "he said")
        return if (on) "Got it, ${if (tomorrow) "tomorrow" else "today"}'s a dash day." else "Got it, no dashing ${if (tomorrow) "tomorrow" else "today"}. I'll leave shifts and targets out."
    }
}
