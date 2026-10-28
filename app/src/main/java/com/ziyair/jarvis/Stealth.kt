package com.ziyair.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quiet mode ("I'm at work", "I'm in a meeting"): Jarvis says nothing and sends no updates. Everything he would
 * have told him is saved, and when quiet mode ends he gives one short catch-up of what was missed.
 */
object Stealth {
    private const val NOTE_ID = 4242

    fun on(p: Prefs): Boolean {
        if (!p.sp.getBoolean("stealth", false)) return false
        val until = p.sp.getLong("stealth_until", 0L)
        if (until in 1 until System.currentTimeMillis()) return false   // time's up; the service ends it properly
        return true
    }
    fun expired(p: Prefs) = p.sp.getBoolean("stealth", false) && p.sp.getLong("stealth_until", 0L).let { it in 1 until System.currentTimeMillis() }
    fun since(p: Prefs) = p.sp.getLong("stealth_since", 0L)

    fun start(context: Context, minutes: Int?): String {
        val p = Prefs(context)
        val until = if (minutes != null && minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        p.sp.edit().putBoolean("stealth", true).putLong("stealth_since", System.currentTimeMillis()).putLong("stealth_until", until)
            .putString("stealth_missed", "[]").apply()
        if (until > 0) Scheduler.addAction(context, until, "stealth_off", "end quiet mode")
        showNote(context)
        return "Quiet mode on" + (if (until > 0) " until ${Scheduler.clock(until)}" else " until he says he's out") +
            ". Not a sound from Jarvis; everything gets saved for a catch-up afterwards."
    }

    /** Ends quiet mode and returns what was missed, as notes for Jarvis to sum up. */
    fun end(context: Context): String {
        val p = Prefs(context)
        val missed = missed(p)
        val since = since(p)
        p.sp.edit().putBoolean("stealth", false).putLong("stealth_until", 0L).putString("stealth_missed", "[]").apply()
        context.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
        val f = SimpleDateFormat("h:mm a", Locale.US)
        val list = (0 until missed.length()).map { missed.getJSONObject(it) }.map { "${f.format(Date(it.optLong("at")))}: ${it.optString("t")}" }
        val msgs = Inbox.recent().filter { it.at >= since }.map { "${f.format(Date(it.at))}: message from ${it.sender} on ${it.app}: ${it.text}" }
        val all = (list + msgs).distinct()
        return if (all.isEmpty()) "Quiet mode off. Nothing happened while he was away."
            else "Quiet mode off. While he was away (since ${f.format(Date(since))}): " + all.joinToString(" | ")
    }

    fun miss(context: Context, text: String) {
        val p = Prefs(context); val a = missed(p)
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("t", text))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("stealth_missed", keep.toString()).apply()
        showNote(context)
    }

    private fun missed(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("stealth_missed", "[]")) } catch (_: Exception) { JSONArray() }

    /** One silent, ongoing notification: "Quiet mode · 3 things saved · tap to end". */
    private fun showNote(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("quiet") == null)
            nm.createNotificationChannel(NotificationChannel("quiet", "Jarvis quiet mode", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val n = missed(Prefs(context)).length()
        val end = PendingIntent.getService(context, 43, Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_QUIET_END), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTE_ID, Notification.Builder(context, "quiet")
            .setSmallIcon(R.drawable.ic_jarvis).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle("Jarvis quiet mode")
            .setContentText(if (n == 0) "Staying silent. Tap to end and catch up." else "$n thing${if (n == 1) "" else "s"} saved. Tap to end and catch up.")
            .setContentIntent(end).build())
    }

    /** A silent notification for replies he asks for while quiet. */
    fun silentNote(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("quiet") == null)
            nm.createNotificationChannel(NotificationChannel("quiet", "Jarvis quiet mode", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        nm.notify(4243, Notification.Builder(context, "quiet").setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }
}
