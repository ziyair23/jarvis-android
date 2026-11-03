package com.ziyair.jarvis

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Starts before anything else in Jarvis. Its one job: when Jarvis crashes or gets shut down, write down exactly why,
 * so it can be fixed from the flight log instead of guessed.
 *  - A crash in Jarvis's own code: the error and where it happened, saved the moment it happens.
 *  - Everything else Android records (froze and was closed, ran out of memory, a crash inside the voice or speech
 *    engines): read back from Android's own records the next time Jarvis starts.
 */
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Thread { try { Crash.reportPrevious(this) } catch (_: Throwable) {} }.start()
    }
}

object Crash {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")
    @Volatile private var installed = false

    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                val text = "thread ${t.name}: ${sw.toString().take(3000)}"
                file(app).writeText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + text)
                Trace.log(app, "crash", text.take(400))
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    /** On start: what took Jarvis down last time, into the flight log (and the problems list in settings). */
    fun reportPrevious(c: Context) {
        val p = Prefs(c)
        file(c).takeIf { it.exists() }?.let { f ->
            val t = f.readText(); f.delete()
            Trace.log(c, "crash", "last crash (Jarvis's code): ${t.take(1500)}")
            Problems.log(c, "Jarvis crashed: " + t.substringAfter(": ").lineSequence().firstOrNull().orEmpty().take(160))
        }
        if (Build.VERSION.SDK_INT < 30) return
        val am = c.getSystemService(ActivityManager::class.java) ?: return
        val seen = p.sp.getLong("exit_seen", 0L)
        val reasons = try { am.getHistoricalProcessExitReasons(c.packageName, 0, 8) } catch (_: Exception) { return }
        var newest = seen
        for (r in reasons.sortedBy { it.timestamp }) {
            if (r.timestamp <= seen) continue
            newest = maxOf(newest, r.timestamp)
            val why = when (r.reason) {
                ApplicationExitInfo.REASON_ANR -> "froze and Android closed it (not responding)"
                ApplicationExitInfo.REASON_CRASH -> "crashed"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed inside the voice or speech engine"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "was closed because the phone ran low on memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "was closed for using too much battery or CPU"
                ApplicationExitInfo.REASON_SIGNALED -> "was killed by the system"
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> null   // he closed it
                ApplicationExitInfo.REASON_PACKAGE_UPDATED -> null   // an update
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "was restarted because a permission changed"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "stopped because something it relies on stopped"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                ApplicationExitInfo.REASON_EXIT_SELF -> "shut itself down"
                else -> "was stopped by the phone (reason ${r.reason}, often battery saving)"
            } ?: continue
            val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(r.timestamp))
            // for a freeze or engine crash Android keeps the details: the first lines say where it was stuck
            val detail = try {
                if (r.reason == ApplicationExitInfo.REASON_ANR || r.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                    r.traceInputStream?.bufferedReader()?.use { br -> br.lineSequence().filter { it.contains("com.ziyair") || it.contains("main\"") || it.contains("at ") }.take(25).joinToString(" | ") }.orEmpty()
                else ""
            } catch (_: Exception) { "" }
            val mem = if (r.pss > 0) " (memory ${r.pss / 1024} MB)" else ""
            Trace.log(c, "crash", "$at Jarvis $why$mem: ${r.description.orEmpty().take(200)} ${detail.take(1500)}")
            Problems.log(c, "Jarvis $why ($at)")
        }
        p.sp.edit().putLong("exit_seen", newest).apply()
    }
}
