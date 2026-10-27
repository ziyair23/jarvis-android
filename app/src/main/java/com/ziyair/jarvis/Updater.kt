package com.ziyair.jarvis

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates itself from GitHub releases. When a newer build is out and he's using the phone (unlocked, Jarvis not
 * mid-conversation), it downloads and installs it. The very first time Android asks "Update Jarvis?" once;
 * after that Jarvis is the app's installer and updates go through on their own. Then Jarvis restarts himself.
 */
object Updater {
    data class Release(val build: Int, val apkUrl: String, val pageUrl: String)

    @Volatile private var installing = false

    fun latest(): Release? = try {
        val c = URL("https://api.github.com/repos/${Brain.REPO}/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) { c.disconnect(); null } else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            val build = j.optString("tag_name").removePrefix("v").toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets")
            var apk = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            Release(build, apk, j.optString("html_url"))
        }
    } catch (_: Exception) { null }

    private fun userPresent(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    /** Runs on a background thread. Checks at most once an hour unless forced. */
    fun check(context: Context, force: Boolean = false, busy: Boolean = false): Release? {
        val prefs = Prefs(context)
        if (!force && System.currentTimeMillis() - prefs.lastUpdateCheck < 3600_000L) return null
        prefs.lastUpdateCheck = System.currentTimeMillis()
        val r = latest() ?: return null
        if (r.build <= BuildConfig.VERSION_CODE || r.apkUrl.isBlank()) return null
        if (!busy && (force || userPresent(context)) && install(context, r)) return r
        // couldn't install right now: try again soon, and leave a tap-to-update notification
        prefs.lastUpdateCheck = 0L
        notifyReady(context, r)
        return r
    }

    private fun install(context: Context, r: Release): Boolean {
        if (installing) return true
        installing = true
        try {
            val f = File(context.cacheDir, "jarvis-update.apk")
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 60000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) { c.disconnect(); installing = false; return false }
            c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            c.disconnect()

            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                f.inputStream().use { input -> s.openWrite("jarvis.apk", 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) } }
                Prefs(context).sp.edit().putBoolean("restart_after_update", Prefs(context).running).apply()
                val status = PendingIntent.getBroadcast(context, 77, Intent(context, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                s.commit(status.intentSender)
            }
            return true
        } catch (_: Exception) {
            installing = false
            return false
        }
    }

    fun done() { installing = false }

    fun notifyReady(context: Context, r: Release) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("updates") == null)
            nm.createNotificationChannel(NotificationChannel("updates", "Jarvis updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java).putExtra("update", true), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(9, Notification.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Update ready, sir")
            .setContentText("Jarvis build ${r.build} installs next time you unlock your phone, or tap to do it now.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Result of an install. Android asks him once the first time; after that it's silent. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    if (!Settings.canDrawOverlays(context)) throw IllegalStateException()
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    Updater.done()
                    Scheduler.notify(context, "Update ready, sir", "Open Jarvis and tap Check for updates to finish installing.", false)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.done()
            else -> {
                Updater.done()
                Prefs(context).sp.edit().putBoolean("restart_after_update", false).apply()
            }
        }
    }
}

/** After an update the old Jarvis is gone: bring him straight back if he was running. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        Scheduler.rearm(context)
        if (!prefs.sp.getBoolean("restart_after_update", false)) return
        prefs.sp.edit().putBoolean("restart_after_update", false).apply()
        // Android only lets the mic start from the foreground, so open Jarvis for a moment; it starts him and steps back
        try {
            context.startActivity(Intent(context, MainActivity::class.java).putExtra("autostart", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Exception) {
            prefs.running = false
            Scheduler.notify(context, "Jarvis updated, sir", "Tap to start him again.", false)
        }
    }
}
