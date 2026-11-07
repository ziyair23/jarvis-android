package com.ziyair.jarvis

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeps Jarvis alive. Samsung and Android sometimes kill his service and never restart it (that's how 46 calls went
 * unannounced one evening). Every 15 minutes a heartbeat checks he's running; if he should be and isn't, a see-through
 * screen opens for an instant (allowed by "display over other apps", even over the lock screen), starts him, and closes.
 */
class Revive : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        try { startForegroundService(Intent(this, JarvisService::class.java)); Trace.log(this, "revive", "started him again") }
        catch (e: Exception) { Problems.log(this, "couldn't restart Jarvis: ${e.message}") }
        finish(); overridePendingTransition(0, 0)
    }
}

object Heartbeat {
    const val ACTION = "com.ziyair.jarvis.HEARTBEAT"
    private const val EVERY = 15 * 60_000L

    /** Should we bring him back right now? Only if he's meant to be on, isn't, and the screen is on to do it. Pure. */
    fun shouldRevive(wantOn: Boolean, alive: Boolean, screenOn: Boolean, overlay: Boolean, msSinceLast: Long) =
        wantOn && !alive && screenOn && overlay && msSinceLast > 60_000L

    fun arm(c: Context) {
        val pi = PendingIntent.getBroadcast(c, 4242, Intent(c, AlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + EVERY, pi) } catch (_: Exception) {}
    }

    fun beat(c: Context) {
        arm(c)
        val p = Prefs(c)
        val last = p.sp.getLong("revive_at", 0L); val now = System.currentTimeMillis()
        if (!shouldRevive(p.running, JarvisService.alive, c.getSystemService(PowerManager::class.java).isInteractive, Settings.canDrawOverlays(c), now - last)) return
        p.sp.edit().putLong("revive_at", now).apply()
        Trace.log(c, "revive", "he wasn't running: bringing him back")
        try { c.startActivity(Intent(c, Revive::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)) } catch (_: Exception) {}
    }
}
