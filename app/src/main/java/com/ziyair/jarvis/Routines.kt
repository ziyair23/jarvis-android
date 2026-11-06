package com.ziyair.jarvis

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Things Jarvis now runs on his own, without being asked:
 *  - Shift: the Dasher app going on starts a shift (phone quiet except starred contacts, weather, roads, cheapest gas);
 *    it going off ends it (hours, and his dollars per hour once he says what he made).
 *  - LeaveAlerts: anything on his calendar with a place gets a "time to leave" alert from live traffic.
 *  - Stock: diapers and the other things he rebuys count down as they're used; low ones go on the list.
 *  - Triage: each evening, one short rundown of what in his email and texts needs him.
 *  - Patterns: habits he repeats every week (gas on Thursdays...) become reminders, once he says yes.
 * All of it is skipped quietly when the connection it needs isn't set up.
 */
object Shift {
    const val DASHER = "com.doordash.driverapp"
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ---------------- pure (unit-tested) ----------------
    fun hm(ms: Long): String { val m = (ms / 60_000L).toInt(); return if (m < 60) "$m min" else "${m / 60}h ${String.format(Locale.US, "%02d", m % 60)}m" }
    /** Dollars an hour, once a shift has run long enough to mean something. */
    fun perHour(amount: Double, ms: Long): Double? = if (ms < 20 * 60_000L || amount <= 0) null else amount / (ms / 3_600_000.0)

    // ---------------- state ----------------
    fun startedAt(c: Context) = Prefs(c).sp.getLong("shift_start", 0L)
    fun active(c: Context) = startedAt(c) > 0L
    /** Time on shift today, including the one running now. */
    fun todayMs(c: Context): Long {
        val p = Prefs(c).sp; val today = ymd.format(Date())
        val done = if (p.getString("shift_day", "") == today) p.getLong("shift_day_ms", 0L) else 0L
        return done + (startedAt(c).takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0L)
    }

    fun start(c: Context): String {
        val p = Prefs(c)
        if (active(c)) return "Already on shift since ${Scheduler.clock(startedAt(c))}."
        p.sp.edit().putLong("shift_start", System.currentTimeMillis()).putBoolean("shift_quieted", false).apply()
        try { DashDays.seen(c, "shift") } catch (_: Exception) {}
        DriveMode.on = true
        val out = mutableListOf("Shift started.")
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Brain(c).config().optBoolean("shift_quiet", true) && nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            try {
                nm.notificationPolicy = NotificationManager.Policy(
                    NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
                        NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS,
                    NotificationManager.Policy.PRIORITY_SENDERS_STARRED, NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                p.sp.edit().putBoolean("shift_quieted", true).apply()
                out.add("Phone's quiet except starred contacts.")
            } catch (_: Exception) {}
        }
        try { Weather.now(c)?.let { out.add("Weather: $it" + Weather.roadsNote(c)) } } catch (_: Exception) {}
        if (p.mapsKey.isNotBlank()) try { out.add(Maps.gas(c, null)) } catch (_: Exception) {}
        Timeline.add(c, "shift", "Shift started")
        LogBus.changed()
        return out.joinToString(" ")
    }

    fun end(c: Context): String {
        val p = Prefs(c); val st = startedAt(c)
        if (st == 0L) return "No shift running."
        val ms = System.currentTimeMillis() - st
        val today = ymd.format(Date())
        val before = if (p.sp.getString("shift_day", "") == today) p.sp.getLong("shift_day_ms", 0L) else 0L
        p.sp.edit().putLong("shift_start", 0L).putString("shift_day", today).putLong("shift_day_ms", before + ms).putLong("shift_last_ms", ms).apply()
        DriveMode.on = false
        if (p.sp.getBoolean("shift_quieted", false)) {
            try { c.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (_: Exception) {}
            p.sp.edit().putBoolean("shift_quieted", false).apply()
        }
        Timeline.add(c, "shift", "Shift ended after ${hm(ms)}")
        LogBus.changed()
        val made = Earnings.today(c)
        val rate = perHour(made, before + ms)
        return "Shift done after ${hm(ms)}." + if (made > 0 && rate != null) " Today's logged at ${Earnings.money(made)}, about ${Earnings.money(Math.round(rate).toDouble())} an hour." else
            " Ask him what he made, log it, then tell him his dollars per hour (${hm(before + ms)} on shift today)."
    }

    fun status(c: Context): String {
        val ms = todayMs(c); val made = Earnings.today(c)
        if (ms == 0L) return "No shift today yet." + if (made > 0) " Logged ${Earnings.money(made)}." else ""
        val rate = perHour(made, ms)
        return (if (active(c)) "On shift since ${Scheduler.clock(startedAt(c))}, " else "Off shift, ") + "${hm(ms)} today" +
            (if (made > 0) ", ${Earnings.money(made)} logged" + (rate?.let { ", about ${Earnings.money(Math.round(it).toDouble())} an hour" } ?: "") else ", nothing logged yet") + "."
    }

    // ---------------- the Dasher app going on and off ----------------
    @Volatile private var lastOn = 0L
    fun dasherOn(c: Context) {
        lastOn = System.currentTimeMillis()
        val p = Prefs(c).sp
        if (active(c) || !Brain(c).config().optBoolean("shift_auto", true)) return
        if (System.currentTimeMillis() - p.getLong("shift_manual_end", 0L) < 2 * 3600_000L) return   // he ended it himself: don't restart off the same notification
        thread { try { say(c, "Sir, you're dashing. " + start(c).removePrefix("Shift started. "), false) } catch (e: Exception) { Problems.log(c, "shift start: ${e.message}") } }
    }
    fun dasherOff(c: Context) {
        val off = System.currentTimeMillis()
        if (!active(c)) return
        // the Dasher app redraws its notification now and then: only a real stop (3 minutes gone) ends the shift
        thread { try { Thread.sleep(180_000L); if (lastOn < off && active(c)) { val l = end(c); say(c, "Sir, " + l.replaceFirstChar { it.lowercase() }.substringBefore(" Ask him") + if (l.contains("Ask him")) " What did you make?" else "", l.contains("Ask him")) } } catch (_: Exception) {} }
    }
    fun endedByHim(c: Context) { Prefs(c).sp.edit().putLong("shift_manual_end", System.currentTimeMillis()).apply() }

    fun say(c: Context, text: String, listen: Boolean) {
        if (!Prefs(c).running) { Mind.tellLater(c, text); return }
        try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", text).putExtra("listen", listen)) } catch (_: Exception) { Mind.tellLater(c, text) }
    }
}

object LeaveAlerts {
    /** When to walk out: the drive with traffic plus a few minutes to get going. Pure. */
    fun leaveAt(begin: Long, driveSeconds: Long, bufferMin: Int = 10) = begin - driveSeconds * 1000L - bufferMin * 60_000L
    /** A real place to drive to, not a video call or a phone number. Pure. */
    fun drivable(where: String) = where.isNotBlank() && !Regex("(?i)(https?://|zoom|meet\\.google|teams|webex|facetime|^\\+?[0-9 ()-]{7,}$|phone call|online|virtual)").containsMatchIn(where)

    private class Ev(val id: Long, val title: String, val begin: Long, val where: String)

    private fun upcoming(c: Context, hours: Int): List<Ev> {
        if (!Cal.allowed(c)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, now); android.content.ContentUris.appendId(it, now + hours * 3600_000L) }.build()
        val out = ArrayList<Ev>()
        c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN)?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(4) == 1) continue
                val where = cur.getString(3).orEmpty().trim()
                if (drivable(where)) out.add(Ev(cur.getLong(0), cur.getString(1).orEmpty().ifBlank { "your appointment" }, cur.getLong(2), where))
            }
        }
        return out
    }

    /** Every ten minutes: for each event in the next few hours, work out when to leave and set (or move) the alert. */
    fun tick(c: Context) {
        val p = Prefs(c)
        if (p.mapsKey.isBlank() || !Brain(c).config().optBoolean("leave_alerts", true)) return
        val now = System.currentTimeMillis()
        val state = try { JSONObject(p.sp.getString("leave_state", "{}")) } catch (_: Exception) { JSONObject() }
        for (e in upcoming(c, 4)) {
            val key = "${e.id}_${e.begin}"
            val s = state.optJSONObject(key) ?: JSONObject()
            if (now - s.optLong("checked", 0L) < 25 * 60_000L) continue
            val r = Maps.route(c, e.where) ?: continue
            val at = leaveAt(e.begin, r.seconds)
            s.put("checked", now)
            val old = s.optLong("at", 0L)
            if (at > now && Math.abs(at - old) > 4 * 60_000L) {
                if (s.has("rem")) Scheduler.cancel(c, s.optInt("rem"))
                val text = "Sir, time to leave for ${e.title}: ${Maps.mins(r.seconds)} with traffic to get there by ${Scheduler.clock(e.begin)}."
                s.put("rem", Scheduler.add(c, at, text, loud = false, label = "Leave for ${e.title}")).put("at", at)
                Trace.log(c, "leave", "${e.title}: leave at ${Scheduler.clock(at)} (${Maps.mins(r.seconds)} drive)")
            } else if (at <= now && !s.optBoolean("told") && e.begin > now) {
                s.put("told", true)
                Shift.say(c, "Sir, you should head out now for ${e.title}: it's ${Maps.mins(r.seconds)} with traffic and it starts at ${Scheduler.clock(e.begin)}.", false)
            }
            state.put(key, s)
        }
        // forget events that are over
        val keep = JSONObject(); state.keys().forEach { k -> val b = k.substringAfter('_').toLongOrNull() ?: 0L; if (b > now - 3600_000L) keep.put(k, state.get(k)) }
        p.sp.edit().putString("leave_state", keep.toString()).apply()
    }
}

object Stock {
    // ---------------- pure (unit-tested) ----------------
    fun daysLeft(left: Double, perDay: Double): Double? = if (perDay <= 0.0) null else left / perDay
    /** Diapers a day, from the last few days of logged changes (needs at least a day of logs). */
    fun diapersPerDay(changes: List<Long>, now: Long): Double? {
        val recent = changes.filter { now - it < 3 * 86_400_000L }
        if (recent.size < 4) return null
        val span = maxOf(1.0, (now - recent.min()) / 86_400_000.0)
        return recent.size / span
    }

    private fun all(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("stock", "{}")) } catch (_: Exception) { JSONObject() }
    private fun save(c: Context, o: JSONObject) = Prefs(c).sp.edit().putString("stock", o.toString()).apply()
    private fun norm(item: String) = item.trim().lowercase().removeSuffix("s").ifBlank { item }
    private fun isDiaper(item: String) = norm(item).contains("diaper")

    private fun changes(c: Context): List<Long> = Baby.all(Prefs(c)).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("k") == "diaper" }.map { it.optLong("at") } }

    /** What's left right now, counting what's been used since he told us. */
    private fun left(c: Context, item: String, o: JSONObject): Double {
        val since = o.optLong("at"); val count = o.optDouble("count", 0.0)
        val used = if (isDiaper(item)) changes(c).count { it > since }.toDouble() else o.optDouble("perDay", 0.0) * ((System.currentTimeMillis() - since) / 86_400_000.0)
        return maxOf(0.0, count - used)
    }
    private fun rate(c: Context, item: String, o: JSONObject): Double? =
        if (isDiaper(item)) diapersPerDay(changes(c), System.currentTimeMillis()) else o.optDouble("perDay", 0.0).takeIf { it > 0 }

    fun set(c: Context, item0: String, count: Double, perDay: Double?): String {
        val item = norm(item0); if (item.isBlank() || count < 0) return "Needs the item and how many."
        val a = all(c)
        a.put(item, JSONObject().put("count", count).put("at", System.currentTimeMillis()).put("perDay", perDay ?: a.optJSONObject(item)?.optDouble("perDay", 0.0) ?: 0.0).put("warned", false))
        save(c, a); LogBus.changed()
        val o = a.getJSONObject(item); val r = rate(c, item, o)
        return "Got it: ${count.toInt()} ${item}s." + (r?.let { daysLeft(count, it) }?.let { " At the usual pace that's about ${Math.round(it)} days." }
            ?: if (isDiaper(item)) " I'll count down as you log diaper changes." else " Tell me roughly how many a day you go through and I'll warn you before they run out.")
    }

    fun status(c: Context): String {
        val a = all(c); if (a.length() == 0) return "Nothing counted yet. Tell me, for example, 'we have 120 diapers'."
        return a.keys().asSequence().map { k -> val o = a.getJSONObject(k); val l = left(c, k, o); val d = rate(c, k, o)?.let { daysLeft(l, it) }
            "${l.toInt()} ${k}s" + (d?.let { " (about ${Math.round(it)} days)" } ?: "") }.joinToString(", ") + "."
    }

    /** Once in a while: anything with three days or less left goes on the list, and he hears about it once. */
    fun tick(c: Context) {
        val a = all(c); if (a.length() == 0) return
        var changed = false
        a.keys().forEach { k ->
            val o = a.getJSONObject(k); val l = left(c, k, o); val d = rate(c, k, o)?.let { daysLeft(l, it) } ?: return@forEach
            if (d <= 3.0 && !o.optBoolean("warned")) {
                o.put("warned", true); changed = true
                try { Lists.add(c, k + "s", "list") } catch (_: Exception) {}
                Mind.tellLater(c, "Sir, ${k}s are running low: about ${maxOf(1, Math.round(d).toInt())} days left. I put them on the list.")
                Timeline.add(c, "list", "${k}s running low, added to the list")
            }
        }
        if (changed) save(c, a)
    }
}

object Triage {
    /** Once each evening, between 7:30 and 10. */
    fun maybe(c: Context) {
        val cal = Calendar.getInstance(); val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        if (h < 19.5 || h >= 22.0 || !Brain(c).config().optBoolean("evening_triage", true)) return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()); val p = Prefs(c).sp
        if (p.getString("triage_day", "") == day || Claude.nearBudget(c, 0.85)) return
        p.edit().putString("triage_day", day).apply()
        run(c)?.let { Mind.tellLater(c, it) }
    }

    /** What needs him tonight, in two or three sentences. Null if there's nothing to go through. */
    fun run(c: Context): String? {
        val lines = ArrayList<String>()
        if (Gmail.on(c)) try { Gmail.unread(c, 15).forEach { lines.add("Email from ${Gmail.senderName(it.first)}: ${it.second} — ${it.third.take(140)}") } } catch (_: Exception) {}
        Inbox.recent().take(12).forEach { lines.add("Text from ${it.sender} (${it.app}): ${it.text.take(140)}") }
        if (lines.isEmpty()) return null
        val cfg = Brain(c).config(); val model = cfg.optString("agent_model", "claude-haiku-5-5")
        val body = JSONObject().put("model", model).put("max_tokens", 260).put("system",
            "You're Jarvis, going through Marcus's unread email and today's texts with him in the evening. In two or three short spoken sentences: " +
                "who is waiting on a reply from him (names, one line each), anything about money or bills, and say the rest can wait. Skip ads and newsletters. " +
                "If someone clearly needs a reply, end with 'Want me to draft replies?'. Plain words, no lists, start with 'Sir,'.")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", lines.joinToString("\n"))))
        val resp = Claude.send(c, body) ?: return null
        try { Spend.record(c, cfg, model, resp.optJSONObject("usage")) } catch (_: Exception) {}
        val text = (0 until (resp.optJSONArray("content")?.length() ?: 0)).map { resp.getJSONArray("content").getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
        if (text.isBlank()) return null
        Timeline.add(c, "thought", "Evening rundown: $text")
        Trace.log(c, "triage", text.take(160))
        return text
    }
}

object Patterns {
    private val DAYS = listOf("", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** The weekday he does something on in at least 3 of the last 4 weeks, or null. Pure. */
    fun weekly(times: List<Long>, now: Long): Int? {
        val cal = Calendar.getInstance()
        val weeksByDay = HashMap<Int, MutableSet<Int>>()
        for (t in times) {
            val ago = ((now - t) / (7 * 86_400_000L)).toInt(); if (ago < 0 || ago > 3) continue
            cal.timeInMillis = t
            weeksByDay.getOrPut(cal.get(Calendar.DAY_OF_WEEK)) { HashSet() }.add(ago)
        }
        return weeksByDay.filter { it.value.size >= 3 }.maxByOrNull { it.value.size }?.key
    }
    fun dayName(d: Int) = DAYS.getOrElse(d) { "" }
    fun dayBefore(d: Int) = if (d == 1) 7 else d - 1

    private fun gasTimes(c: Context): List<Long> {
        val out = ArrayList<Long>()
        Spending.all(c).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optString("c") == "gas") out.add(it.optLong("at")) } }
        Expenses.all(Prefs(c)).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optString("w").contains("gas")) out.add(it.optLong("at")) } }
        return out
    }
    private fun groceryTimes(c: Context): List<Long> = Spending.all(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("c") == "groceries" }.map { it.optLong("at") } }

    /** Habits he repeats every week, in words. */
    fun found(c: Context): List<Pair<String, String>> {
        val now = System.currentTimeMillis(); val out = ArrayList<Pair<String, String>>()
        weekly(gasTimes(c), now)?.let { d -> out.add("gas" to "He usually gets gas on ${dayName(d)}s. Set a repeating reminder every ${dayName(dayBefore(d))} at 8 PM: 'Gas tomorrow, sir.'") }
        weekly(groceryTimes(c), now)?.let { d -> out.add("groceries" to "He usually shops for groceries on ${dayName(d)}s. Every ${dayName(dayBefore(d))} at 7 PM, read him the shopping list and ask if anything's missing.") }
        return out
    }

    fun list(c: Context): String = found(c).let { f -> if (f.isEmpty()) "No weekly habits clear enough yet; I need about three weeks of the same thing." else f.joinToString(" ") { it.second.substringBefore(" Set").substringBefore(" Every") } }

    /** Once a week: offer each new habit as a reminder, for his yes. */
    fun tick(c: Context) {
        val p = Prefs(c).sp
        if (System.currentTimeMillis() - p.getLong("patterns_at", 0L) < 7 * 86_400_000L) return
        p.edit().putLong("patterns_at", System.currentTimeMillis()).apply()
        val offered = (p.getString("patterns_offered", "") ?: "").split(',').toSet()
        val now = found(c).filter { it.first !in offered }
        now.forEach { Mind.offer(c, it.second) }
        if (now.isNotEmpty()) p.edit().putString("patterns_offered", (offered + now.map { it.first }).filter { it.isNotBlank() }.joinToString(",")).apply()
    }
}

/** Everything above, on Jarvis's ten-minute tick. */
object Routines {
    fun tick(c: Context) {
        try { LeaveAlerts.tick(c) } catch (e: Exception) { Problems.log(c, "leave alerts: ${e.message}") }
        try { Stock.tick(c) } catch (_: Exception) {}
        try { Triage.maybe(c) } catch (e: Exception) { Problems.log(c, "evening rundown: ${e.message}") }
        try { Patterns.tick(c) } catch (_: Exception) {}
    }
}
