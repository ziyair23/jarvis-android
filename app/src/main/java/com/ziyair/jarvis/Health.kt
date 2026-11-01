package com.ziyair.jarvis

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis's health check: everything on the phone that has to be set up right for him to work, with a one-tap fix.
 * Most "he's not listening / not doing it" problems are one of these switched off.
 */
object Health {
    class Item(val key: String, val name: String, val ok: Boolean, val why: String)

    fun items(c: Context): List<Item> {
        val p = Prefs(c)
        val pm = c.getSystemService(PowerManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val listener = (Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: "").contains(c.packageName)
        return listOf(
            Item("running", "Jarvis is running", p.running, "Turn him on so he listens for his name."),
            Item("mic", "Microphone", c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, "He can't hear you without it."),
            Item("battery", "Not battery-restricted", pm.isIgnoringBatteryOptimizations(c.packageName), "Android puts him to sleep in the background, so he stops hearing his name."),
            Item("key", "Claude key", p.claudeKey.isNotBlank(), "His brain needs it to answer anything."),
            Item("overlay", "Display over other apps", Settings.canDrawOverlays(c), "Needed to open apps, play songs and show the orb."),
            Item("screen", "Screen control", JarvisAccess.on(c), "Needed to press Play on songs and work inside apps."),
            Item("listener", "Read notifications", listener, "Needed to announce callers and read messages, and to control music."),
            Item("dnd", "Do Not Disturb access", nm.isNotificationPolicyAccessGranted, "Needed for quiet mode and goodnight."),
            Item("ears", "Phone ears downloaded", Ear.ready(c), "One-time 104 MB download on Wi-Fi; Google listening covers until then."),
            Item("voice", "Voice downloaded", Piper.pickReady(c), "His chosen voice downloads on Wi-Fi."),
        )
    }

    fun json(c: Context): JSONArray = JSONArray().apply {
        for (i in items(c)) put(JSONObject().put("k", i.key).put("n", i.name).put("ok", i.ok).put("why", i.why))
    }

    fun summary(c: Context): String {
        val bad = items(c).filter { !it.ok }
        return if (bad.isEmpty()) "all good" else "NOT SET: " + bad.joinToString(", ") { it.name }
    }

    /** Opens the screen that fixes this item. */
    fun fix(c: Context, key: String) {
        val pkg = Uri.parse("package:${c.packageName}")
        val i = when (key) {
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "screen" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "mic" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            "ears" -> { Thread { Ear.download(c) }.start(); null }
            "voice" -> { Thread { Piper.download(c) }.start(); null }
            else -> Intent(c, MainActivity::class.java)
        } ?: return
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    }
}
