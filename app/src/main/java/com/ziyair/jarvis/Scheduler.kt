package com.ziyair.jarvis

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Timers, reminders and the daily heads-ups. Uses exact alarms so they fire with the screen off. */
object Scheduler {
    private const val DAILY_BASE = 5000
    val MOMENTS = listOf("lunch_soon", "lunch_done", "dinner_soon", "dinner_done", "bed_soon")

    fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))

    fun add(context: Context, at: Long, text: String, loud: Boolean, label: String): Int {
        val prefs = Prefs(context)
        val list = prefs.reminders()
        val id = 10_000 + (System.currentTimeMillis() % 100_000_000).toInt()
        list.put(JSONObject().put("id", id).put("at", at).put("text", text).put("loud", loud).put("label", label))
        prefs.saveReminders(list)
        setAlarm(context, at, id, Intent(context, AlarmReceiver::class.java).putExtra("id", id))
        return id
    }

    /** A silent scheduled action (e.g. end quiet mode in the morning). */
    fun addAction(context: Context, at: Long, action: String, label: String) {
        val prefs = Prefs(context)
        val list = prefs.reminders(); val keep = JSONArray()
        for (i in 0 until list.length()) { val r = list.getJSONObject(i); if (r.optString("action") == action) cancel(context, r.getInt("id")) else keep.put(r) }
        val id = 10_000 + (System.currentTimeMillis() % 100_000_000).toInt()
        prefs.reminders().let { l -> l.put(JSONObject().put("id", id).put("at", at).put("text", "").put("loud", false).put("label", label).put("action", action)); prefs.saveReminders(l) }
        setAlarm(context, at, id, Intent(context, AlarmReceiver::class.java).putExtra("id", id))
    }

    fun cancel(context: Context, id: Int) {
        val prefs = Prefs(context)
        val list = prefs.reminders(); val keep = JSONArray()
        for (i in 0 until list.length()) if (list.getJSONObject(i).getInt("id") != id) keep.put(list.getJSONObject(i))
        prefs.saveReminders(keep)
        context.getSystemService(AlarmManager::class.java).cancel(pi(context, id, Intent(context, AlarmReceiver::class.java).putExtra("id", id)))
    }

    fun describe(context: Context): String {
        val list = Prefs(context).reminders()
        if (list.length() == 0) return "Nothing scheduled."
        val parts = (0 until list.length()).map { list.getJSONObject(it) }.sortedBy { it.getLong("at") }
            .map { "${it.optString("label")} at ${clock(it.getLong("at"))}" }
        return "Scheduled: " + parts.joinToString("; ") + "."
    }

    fun cancelAll(context: Context) {
        val prefs = Prefs(context)
        val list = prefs.reminders()
        val am = context.getSystemService(AlarmManager::class.java)
        for (i in 0 until list.length()) {
            val id = list.getJSONObject(i).getInt("id")
            am.cancel(pi(context, id, Intent(context, AlarmReceiver::class.java).putExtra("id", id)))
        }
        prefs.saveReminders(JSONArray())
    }

    /** Re-arm everything (after a reboot, or when settings change). */
    fun rearm(context: Context) {
        val list = Prefs(context).reminders()
        val keep = JSONArray()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.getLong("at") > System.currentTimeMillis() - 60_000) {
                keep.put(r)
                setAlarm(context, r.getLong("at"), r.getInt("id"), Intent(context, AlarmReceiver::class.java).putExtra("id", r.getInt("id")))
            }
        }
        Prefs(context).saveReminders(keep)
        scheduleDaily(context)
    }

    fun scheduleDaily(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        MOMENTS.forEachIndexed { i, m ->
            val intent = Intent(context, AlarmReceiver::class.java).putExtra("moment", m)
            if (!prefs.talkFirst) { am.cancel(pi(context, DAILY_BASE + i, intent)); return@forEachIndexed }
            val at = nextMoment(prefs, m) ?: return@forEachIndexed
            setAlarm(context, at, DAILY_BASE + i, intent)
        }
    }

    private fun nextMoment(prefs: Prefs, m: String): Long? {
        fun range(r: String) = r.split("-").map { it.trim() }
        val minutes: Int = when (m) {
            "lunch_soon" -> toMin(range(prefs.lunch).getOrNull(0))?.minus(15)
            "lunch_done" -> toMin(range(prefs.lunch).getOrNull(1))
            "dinner_soon" -> toMin(range(prefs.dinner).getOrNull(0))?.minus(15)
            "dinner_done" -> toMin(range(prefs.dinner).getOrNull(1))
            "bed_soon" -> toMin(prefs.bedtime)?.minus(30)
            else -> null
        } ?: return null
        val mm = ((minutes % 1440) + 1440) % 1440
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, mm / 60); c.set(Calendar.MINUTE, mm % 60); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun toMin(t: String?): Int? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(t ?: return null) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun pi(context: Context, code: Int, intent: Intent) =
        PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun setAlarm(context: Context, at: Long, code: Int, intent: Intent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val p = pi(context, code, intent)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun momentLine(context: Context, m: String): String {
        val s = if (Math.random() < .75) "sir" else "boss"
        val roads = if (m.endsWith("_soon") && m != "bed_soon") Weather.roadsNote(context) else ""
        return when (m) {
            "lunch_soon" -> "Lunch rush starts in fifteen minutes, $s.$roads"
            "lunch_done" -> "That's the shift wrapped, $s. How much did you make?"
            "dinner_soon" -> "Dinner rush in fifteen, $s.$roads"
            "dinner_done" -> "Dinner rush is done, $s. How'd tonight go, what did you make?"
            "bed_soon" -> "Half an hour to bedtime, $s."
            else -> ""
        }
    }

    fun notify(context: Context, title: String, text: String, loud: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = if (loud) "timers" else "heads_up"
        if (nm.getNotificationChannel(ch) == null) {
            val c = NotificationChannel(ch, if (loud) "Jarvis timers" else "Jarvis heads-ups",
                if (loud) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)
            if (loud) c.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            nm.createNotificationChannel(c)
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1000 + (System.currentTimeMillis() % 100000).toInt(), Notification.Builder(context, ch)
            .setSmallIcon(R.drawable.ic_jarvis).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val moment = intent.getStringExtra("moment")
        if (moment != null) {
            Scheduler.scheduleDaily(context) // arm tomorrow's
            if (!prefs.talkFirst) return
            say(context, Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("moment", moment)
                .putExtra("listen", moment.endsWith("_done")),
                fallbackTitle = "Jarvis", fallbackText = Scheduler.momentLine(context, moment), loud = false)
            return
        }
        val id = intent.getIntExtra("id", -1)
        val list = prefs.reminders()
        val keep = JSONArray()
        var hit: JSONObject? = null
        for (i in 0 until list.length()) { val r = list.getJSONObject(i); if (r.getInt("id") == id) hit = r else keep.put(r) }
        prefs.saveReminders(keep)
        val r = hit ?: return
        if (r.optString("action") == "quiet_off") {
            try { context.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (_: Exception) {}
            prefs.sp.edit().putLong("quiet_until", 0L).apply()
            return
        }
        val loud = r.optBoolean("loud")
        Scheduler.notify(context, if (loud) "Timer: ${r.optString("label")}" else "Reminder", r.getString("text"), loud)
        say(context, Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", r.getString("text")),
            null, null, loud)
    }

    /** Hand the line to Jarvis if he's running; otherwise show it as a notification. */
    private fun say(context: Context, i: Intent, fallbackTitle: String?, fallbackText: String?, loud: Boolean) {
        val ok = Prefs(context).running && try { context.startService(i); true } catch (_: Exception) { false }
        if (!ok && fallbackText != null) Scheduler.notify(context, fallbackTitle ?: "Jarvis", fallbackText, loud)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Scheduler.rearm(context)
        if (Prefs(context).running) {
            Prefs(context).running = false
            Scheduler.notify(context, "Jarvis is ready, sir", "Your phone restarted. Tap to open Jarvis and start him.", false)
        }
    }
}
