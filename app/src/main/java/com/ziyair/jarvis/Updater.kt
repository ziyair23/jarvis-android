package com.ziyair.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub for a newer build (once every few hours) and offers it as "Update ready, sir." */
object Updater {
    data class Release(val build: Int, val apkUrl: String, val pageUrl: String)

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
            Release(build, apk.ifBlank { j.optString("html_url") }, j.optString("html_url"))
        }
    } catch (_: Exception) { null }

    /** Runs on a background thread. */
    fun check(context: Context, force: Boolean = false): Release? {
        val prefs = Prefs(context)
        if (!force && System.currentTimeMillis() - prefs.lastUpdateCheck < 6 * 3600_000L) return null
        prefs.lastUpdateCheck = System.currentTimeMillis()
        val r = latest() ?: return null
        if (r.build <= BuildConfig.VERSION_CODE) return null
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("updates") == null)
            nm.createNotificationChannel(NotificationChannel("updates", "Jarvis updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 9, Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(9, Notification.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Update ready, sir")
            .setContentText("Jarvis build ${r.build} is ready. Tap to download, then install it.")
            .setContentIntent(open).setAutoCancel(true).build())
        return r
    }
}
