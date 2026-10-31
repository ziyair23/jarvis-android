package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The flight recorder: one line for everything Jarvis does, with timings, so glitches can be seen instead of guessed.
 *   18:22:05.1  +0.00 wake      heard his name (ear)
 *   18:22:06.3  +1.21 heard     "set a timer for ten minutes" (0.38s to transcribe)
 *   18:22:06.3  +1.22 reflex    handled on the phone
 * Kept on the phone only (last ~400 KB). He sends it himself with "Send log".
 */
object Trace {
    private const val MAX = 400_000L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.S", Locale.US)
    @Volatile private var turnAt = 0L

    private fun file(c: Context) = File(c.filesDir, "flight.log")

    /** Marks the start of a turn; later lines show seconds since then. */
    fun turn(c: Context, what: String) { turnAt = System.currentTimeMillis(); log(c, "wake", what) }

    @Synchronized fun log(c: Context, tag: String, msg: String) {
        try {
            val now = System.currentTimeMillis()
            val rel = if (turnAt > 0 && now - turnAt < 600_000) String.format(Locale.US, "+%.2f", (now - turnAt) / 1000.0) else "     "
            val f = file(c)
            f.appendText("${fmt.format(Date(now))} $rel ${tag.padEnd(9)} ${msg.replace('\n', ' ').take(400)}\n")
            if (f.length() > MAX) {
                val keep = f.readText().takeLast((MAX * 0.6).toInt())
                f.writeText(keep.substringAfter('\n'))
            }
        } catch (_: Exception) {}
    }

    fun text(c: Context, maxChars: Int = 90_000): String = try { file(c).readText().takeLast(maxChars) } catch (_: Exception) { "" }

    fun clear(c: Context) { try { file(c).delete() } catch (_: Exception) {} }

    /** What's set up on his phone, at the top of a shared log. */
    fun header(c: Context): String {
        val p = Prefs(c)
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode } catch (_: Exception) { -1 }
        val cfg = try { Brain(c).config() } catch (_: Exception) { null }
        return "Jarvis flight log\nbuild $ver, android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
            "ears: ${Ear.status(c)} | voice: ${Piper.pick(c)}${if (Piper.pickReady(c)) "" else " (not downloaded)"} | quiet mode: ${Stealth.on(p)}\n" +
            "settings: wake ${cfg?.optDouble("wake_threshold")}, ear silence ${cfg?.optInt("ear_silence_ms", 750)} ms, convo ${cfg?.optInt("conversation_seconds")} s\n\n"
    }

    /** Opens the share sheet so he can send the log (to Claude, a text, email...). */
    fun share(c: Context) {
        val body = header(c) + text(c)
        try {
            c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Jarvis flight log")
                .putExtra(Intent.EXTRA_TEXT, body), "Send Jarvis's log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
