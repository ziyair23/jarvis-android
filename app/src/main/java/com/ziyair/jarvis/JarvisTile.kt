package com.ziyair.jarvis

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A "Jarvis" button in the quick settings pull-down: tap it to talk without saying his name. */
class JarvisTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = if (Prefs(this@JarvisTile).running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_LISTEN)
        try {
            if (Prefs(this).running) startService(i) else startForegroundService(i)
        } catch (_: Exception) {
            // Android wouldn't let him start from here: open the app instead
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(open)
        }
    }
}
