package com.ziyair.jarvis

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.CalendarContract
import android.Manifest
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** DoorDash earnings, logged by voice. Stored only on the phone: [{"d":"2026-10-26","a":112.0}] */
object Earnings {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("earnings", "[]")) } catch (_: Exception) { JSONArray() }
    fun delete(context: Context, d: String) {
        val p = Prefs(context); val a = all(p); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("d") != d) keep.put(a.get(i))
        save(p, keep)
    }
    fun today(context: Context): Double {
        val a = all(Prefs(context)); val t = day.format(Date())
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("d") == t }.sumOf { it.optDouble("a") }
    }
    private fun save(p: Prefs, a: JSONArray) = p.sp.edit().putString("earnings", a.toString()).apply()

    fun goal(p: Prefs): Double = p.sp.getFloat("goal_amount", 0f).toDouble()
    fun goalName(p: Prefs): String = p.sp.getString("goal_name", "the Macan") ?: "the Macan"
    fun setGoal(context: Context, amount: Double, name: String?): String {
        val p = Prefs(context)
        p.sp.edit().putFloat("goal_amount", amount.toFloat()).apply()
        if (!name.isNullOrBlank()) p.sp.edit().putString("goal_name", name.trim()).apply()
        return "Goal set: ${money(amount)} for ${goalName(p)}. " + summary(context)
    }

    /** "today", "yesterday" or YYYY-MM-DD. Two shifts a day add up unless replace is true. */
    fun log(context: Context, amount: Double, whichDay: String?, replace: Boolean): String {
        val p = Prefs(context)
        val d = resolveDay(whichDay) ?: return "Couldn't tell which day that was."
        val a = all(p)
        var total = amount
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (e.optString("d") == d) { if (!replace) total += e.optDouble("a") } else keep.put(e)
        }
        keep.put(JSONObject().put("d", d).put("a", total))
        // keep about two years
        val trimmed = JSONArray(); for (i in maxOf(0, keep.length() - 730) until keep.length()) trimmed.put(keep.get(i))
        save(p, trimmed)
        return "Logged. ${if (d == day.format(Date())) "Today" else d} is now ${money(total)}. " + summary(context)
    }

    fun summary(context: Context): String {
        val p = Prefs(context)
        val a = all(p)
        if (a.length() == 0) return "Nothing logged yet."
        val today = day.format(Date())
        val c = Calendar.getInstance(); c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); val weekStart = day.format(c.time)
        val monthStart = today.substring(0, 8) + "01"
        val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }.let { day.format(it.time) }
        var t = 0.0; var w = 0.0; var m = 0.0; var all = 0.0; var last7 = 0.0; var last7Days = 0
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i); val d = e.optString("d"); val v = e.optDouble("a")
            all += v
            if (d == today) t += v
            if (d >= weekStart) w += v
            if (d >= monthStart) m += v
            if (d >= weekAgo) { last7 += v; last7Days++ }
        }
        val sb = StringBuilder("Today ${money(t)}, this week ${money(w)}, this month ${money(m)}.")
        if (last7Days > 0) sb.append(" Averaging ${money(last7 / last7Days)} a working day lately.")
        val spentAll = Expenses.total(p)
        if (spentAll > 0) sb.append(" " + Expenses.week(context))
        val kept = all - spentAll
        val g = goal(p)
        if (g > 0) {
            val left = (g - kept).coerceAtLeast(0.0)
            sb.append(" Saved toward ${goalName(p)} (after expenses): ${money(kept)} of ${money(g)}, ${money(left)} to go")
            if (left > 0 && last7Days > 0 && last7 > 0) sb.append(", about ${Math.ceil(left / (last7 / last7Days)).toInt()} working days at this pace")
            sb.append(".")
        } else sb.append(" No savings goal set yet; he can say how much the Macan costs.")
        return sb.toString()
    }

    private fun resolveDay(w: String?): String? {
        val s = (w ?: "today").trim().lowercase()
        val c = Calendar.getInstance()
        return when {
            s.isBlank() || s == "today" || s == "tonight" -> day.format(c.time)
            s == "yesterday" -> { c.add(Calendar.DAY_OF_YEAR, -1); day.format(c.time) }
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(s) -> s
            else -> null
        }
    }

    fun money(v: Double) = if (v == Math.floor(v)) "$" + v.toLong() else "$" + String.format(Locale.US, "%.2f", v)
}

/** Baby log by voice: feeds, diapers, sleep. Sets a "next feed" heads-up after every feed. */
object Baby {
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("baby_log", "[]")) } catch (_: Exception) { JSONArray() }
    fun delete(context: Context, at: Long) {
        val p = Prefs(context); val a = all(p); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") != at) keep.put(a.get(i))
        p.sp.edit().putString("baby_log", keep.toString()).apply()
    }

    fun feedHours(p: Prefs): Double = p.sp.getFloat("feed_hours", 3f).toDouble()

    fun log(context: Context, kindIn: String, minutesAgo: Double, note: String): String {
        val p = Prefs(context)
        val kind = kindIn.lowercase().let { when {
            it.startsWith("feed") || it.contains("bottle") || it.contains("ate") || it.contains("nurs") -> "feed"
            it.contains("diaper") || it.contains("poop") || it.contains("pee") -> "diaper"
            it.contains("wake") || it.contains("woke") -> "wake"
            it.contains("sleep") || it.contains("nap") -> "sleep"
            else -> it
        } }
        val at = System.currentTimeMillis() - (minutesAgo.coerceAtLeast(0.0) * 60_000).toLong()
        val a = all(p)
        a.put(JSONObject().put("k", kind).put("at", at).put("n", note))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 400) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("baby_log", keep.toString()).apply()
        var extra = ""
        if (kind == "feed") {
            // replace the previous next-feed heads-up with a fresh one
            val old = p.sp.getInt("feed_reminder", 0)
            if (old != 0) Scheduler.cancel(context, old)
            val next = at + (feedHours(p) * 3600_000).toLong()
            if (next > System.currentTimeMillis()) {
                val id = Scheduler.add(context, next, "Sir, it's been ${hoursWords(feedHours(p))} since his last feed.", loud = false, label = "next feed")
                p.sp.edit().putInt("feed_reminder", id).apply()
                extra = " Next feed heads-up at ${Scheduler.clock(next)}."
            }
        }
        return "Logged $kind at ${Scheduler.clock(at)}.$extra"
    }

    fun setFeedHours(context: Context, h: Double): String {
        Prefs(context).sp.edit().putFloat("feed_hours", h.toFloat()).apply()
        return "Feeds every ${hoursWords(h)} from now on."
    }

    fun status(context: Context): String {
        val p = Prefs(context)
        val a = all(p)
        if (a.length() == 0) return "Nothing logged for the baby yet."
        val now = System.currentTimeMillis()
        val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        fun last(k: String): JSONObject? { for (i in a.length() - 1 downTo 0) { val e = a.getJSONObject(i); if (e.optString("k") == k) return e }; return null }
        fun count(k: String) = (0 until a.length()).count { a.getJSONObject(it).let { e -> e.optString("k") == k && e.optLong("at") >= dayStart } }
        val sb = StringBuilder()
        last("feed")?.let { sb.append("Last feed ${ago(now - it.optLong("at"))} ago at ${Scheduler.clock(it.optLong("at"))}. ") }
        val f = count("feed"); val dp = count("diaper")
        sb.append("Today: $f feed${if (f == 1) "" else "s"}, $dp diaper${if (dp == 1) "" else "s"}.")
        val s = last("sleep"); val w = last("wake")
        if (s != null && (w == null || s.optLong("at") > w.optLong("at"))) sb.append(" Asleep for ${ago(now - s.optLong("at"))}.")
        else if (w != null) sb.append(" Awake since ${Scheduler.clock(w.optLong("at"))}.")
        return sb.toString()
    }

    private fun hoursWords(h: Double) = if (h == Math.floor(h)) "${h.toInt()} hour${if (h == 1.0) "" else "s"}" else "${h} hours"
    private fun ago(ms: Long): String { val m = (ms / 60000).toInt(); return if (m < 60) "$m minutes" else "${m / 60} hours ${m % 60} minutes" }
}

/** Hands-free phone controls: quiet mode, flashlight, volume. */
object PhoneControl {
    fun quietAllowed(context: Context) = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    /** Goodnight quiet hours: the only time Jarvis keeps his voice down and uses notifications instead. */
    fun nightQuiet(context: Context): Boolean {
        val until = Prefs(context).sp.getLong("quiet_until", 0L)
        if (until == 0L) return false
        if (System.currentTimeMillis() > until || !quietOn(context)) { Prefs(context).sp.edit().putLong("quiet_until", 0L).apply(); return false }
        return true
    }

    fun quietOn(context: Context) = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }

    fun run(context: Context, action: String, level: Int?): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)
        return when (action) {
            "quiet_on", "quiet_off" -> {
                if (!nm.isNotificationPolicyAccessGranted) return "He needs to allow quiet mode: open the Jarvis app and tap Allow quiet mode."
                nm.setInterruptionFilter(if (action == "quiet_on") NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                if (action == "quiet_off") Prefs(context).sp.edit().putLong("quiet_until", 0L).apply()
                if (action == "quiet_on") "Do Not Disturb on." else "Do Not Disturb off."
            }
            "flashlight_on", "flashlight_off" -> {
                val cm = context.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return "No flashlight on this phone."
                cm.setTorchMode(id, action == "flashlight_on")
                if (action == "flashlight_on") "Flashlight on." else "Flashlight off."
            }
            "volume" -> {
                val l = (level ?: 5).coerceIn(0, 10)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * l / 10f), 0)
                "Media volume set to $l out of 10."
            }
            else -> "Unknown phone control."
        }
    }
}

/** Reads and adds events in the phone's calendar (Google Calendar syncs it). */
object Cal {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun dayStart(which: String?): Calendar? {
        val s = (which ?: "today").trim().lowercase()
        val c = Calendar.getInstance()
        when {
            s.isBlank() || s == "today" -> {}
            s == "tomorrow" -> c.add(Calendar.DAY_OF_YEAR, 1)
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(s) -> c.time = dayFmt.parse(s) ?: return null
            else -> return null
        }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c
    }

    fun events(context: Context, which: String?, days: Int): String {
        if (!allowed(context)) return NEED
        val start = dayStart(which) ?: return "Couldn't read that day."
        val end = start.timeInMillis + days.coerceIn(1, 14) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start.timeInMillis); android.content.ContentUris.appendId(it, end) }.build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
        val out = ArrayList<String>()
        val dayName = SimpleDateFormat("EEE MMM d", Locale.US)
        context.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 15) {
                val begin = c.getLong(1)
                val allDay = c.getInt(2) == 1
                val loc = c.getString(3).orEmpty()
                out.add((if (days > 1) dayName.format(Date(begin)) + " " else "") + (if (allDay) "all day" else Scheduler.clock(begin)) + ": " + c.getString(0).orEmpty() + (if (loc.isNotBlank()) " at $loc" else ""))
            }
        }
        return if (out.isEmpty()) "Nothing on the calendar." else out.joinToString("; ")
    }

    fun add(context: Context, title: String, date: String?, time: String?, minutes: Int): String {
        if (!allowed(context)) return NEED
        val start = dayStart(date) ?: return "Couldn't read that date."
        val allDay = time.isNullOrBlank()
        if (!allDay) {
            val m = Regex("(\\d{1,2}):(\\d{2})").find(time!!) ?: return "Couldn't read that time."
            start.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); start.set(Calendar.MINUTE, m.groupValues[2].toInt())
        }
        val calId = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), null, null, null)?.use { c ->
            var best = -1L
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (c.getInt(1) == 1) { best = c.getLong(0); break }
                if (best < 0) best = c.getLong(0)
            }
            best
        } ?: -1L
        if (calId < 0) return "No calendar on the phone to add to."
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, title)
        if (allDay) {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)) }
            v.put(CalendarContract.Events.DTSTART, utc.timeInMillis); v.put(CalendarContract.Events.DTEND, utc.timeInMillis + 86_400_000L)
            v.put(CalendarContract.Events.ALL_DAY, 1); v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            v.put(CalendarContract.Events.DTSTART, start.timeInMillis)
            v.put(CalendarContract.Events.DTEND, start.timeInMillis + minutes.coerceIn(5, 1440) * 60_000L)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "The calendar didn't take it."
        return "Added \"$title\" " + (if (allDay) "on ${SimpleDateFormat("EEEE MMM d", Locale.US).format(start.time)}" else "at ${Scheduler.clock(start.timeInMillis)} ${SimpleDateFormat("EEEE MMM d", Locale.US).format(start.time)}") + "."
    }

    private const val NEED = "He needs to allow the calendar: open the Jarvis app and tap Allow calls, texts, calendar & camera."
}
