package com.ziyair.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Jarvis's HUD sounds, generated in code: no sound files, nothing to download, nothing to license.
 * wake: a quick rising two-note "ready" chirp. listen: a soft up-glide. off: a down-glide. alert: two bright pings.
 * error: a low falling tone.
 */
object Sfx {
    private const val RATE = 24000
    private val cache = HashMap<String, FloatArray>()
    private val main = Handler(Looper.getMainLooper())

    /** One tone gliding from f0 to f1 Hz with a soft attack and a smooth fade, plus a faint overtone for shimmer. */
    private fun tone(f0: Double, f1: Double, secs: Double, amp: Double): FloatArray {
        val n = (RATE * secs).toInt()
        val out = FloatArray(n)
        var ph = 0.0
        val attack = RATE * 0.008
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val f = f0 + (f1 - f0) * x
            ph += 2 * PI * f / RATE
            val env = min(1.0, i / attack) * exp(-3.2 * x) * (1 - x * x * x)
            out[i] = (amp * env * (sin(ph) + 0.18 * sin(2 * ph) + 0.06 * sin(3.01 * ph))).toFloat()
        }
        return out
    }

    private fun gap(secs: Double) = FloatArray((RATE * secs).toInt())

    private fun join(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
        return out
    }

    private fun make(kind: String): FloatArray = when (kind) {
        "wake" -> join(tone(880.0, 990.0, 0.07, 0.30), gap(0.02), tone(1320.0, 1480.0, 0.14, 0.32))
        "listen" -> tone(700.0, 1150.0, 0.13, 0.28)
        "off" -> tone(950.0, 440.0, 0.20, 0.28)
        "alert" -> join(tone(1175.0, 1175.0, 0.09, 0.30), gap(0.05), tone(1568.0, 1568.0, 0.14, 0.30))
        "error" -> tone(330.0, 196.0, 0.30, 0.32)
        else -> tone(1000.0, 1000.0, 0.08, 0.25)
    }

    @Synchronized private fun pcm(kind: String) = cache.getOrPut(kind) { make(kind) }

    /** Duration in ms, so callers can wait for it to finish before opening the mic. */
    fun lengthMs(kind: String) = pcm(kind).size * 1000L / RATE

    fun play(kind: String, usage: Int = AudioAttributes.USAGE_ASSISTANT) {
        try {
            val s = pcm(kind)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(s.size * 4).build()
            t.write(s, 0, s.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            main.postDelayed({ try { t.stop() } catch (_: Exception) {}; t.release() }, lengthMs(kind) + 300)
        } catch (_: Exception) {}
    }
}

/**
 * Jarvis's phrasebook: every short line he says ("Yes, sir?", "On it, sir.", "Paused.") is saved the first time it's
 * spoken, in his current voice, so the next time it plays instantly with no thinking time. Kept per voice and speed.
 */
object Voicebank {
    private const val MAX_CHARS = 70
    private const val MAX_FILES = 400

    fun fits(text: String) = text.length in 1..MAX_CHARS

    private fun file(c: Context, voice: String, text: String, speed: Float): File {
        val key = (text.trim().lowercase() + "|" + speed).hashCode().toUInt().toString(16)
        return File(File(c.filesDir, "voicebank/$voice"), "$key.pcm")
    }

    fun get(c: Context, voice: String, text: String, speed: Float): Pair<FloatArray, Int>? {
        if (!fits(text)) return null
        val f = file(c, voice, text, speed)
        if (!f.exists()) return null
        return try {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val rate = b.int
            val n = b.remaining() / 4
            val s = FloatArray(n)
            b.asFloatBuffer().get(s)
            if (n == 0) null else s to rate
        } catch (_: Exception) { null }
    }

    fun put(c: Context, voice: String, text: String, speed: Float, samples: FloatArray, rate: Int) {
        if (!fits(text) || samples.isEmpty()) return
        try {
            val f = file(c, voice, text, speed)
            f.parentFile?.mkdirs()
            val b = ByteBuffer.allocate(4 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(rate)
            b.asFloatBuffer().put(samples)
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(b.array())
            tmp.renameTo(f)
            // keep it tidy: drop the oldest lines once there are lots
            val all = f.parentFile?.listFiles()?.filter { it.name.endsWith(".pcm") } ?: return
            if (all.size > MAX_FILES) all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES).forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /** Record the lines he uses most ahead of time (background thread). */
    fun prepare(c: Context, lines: Collection<String>, speed: Float) {
        for (l in lines) {
            if (!fits(l)) continue
            try { Piper.synth(c, l, speed) } catch (_: Throwable) {}
        }
    }
}
