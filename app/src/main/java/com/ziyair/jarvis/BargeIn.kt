package com.ziyair.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Lets him cut Jarvis off mid-sentence, like a real conversation. While Jarvis talks, it listens through the
 * phone's call-style mic (which cancels out Jarvis's own voice), learns how loud the leftover echo is, and if it
 * hears someone clearly louder than that for about a quarter of a second, it stops Jarvis so he can listen.
 */
class BargeIn(private val main: Handler, private val onBarge: () -> Unit) {
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread(name = "jarvis-barge-in") {
            val rate = 16000
            val frame = 320 // 20 ms
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 8))
            } catch (_: Exception) { running = false; return@thread }
            val fx = ArrayList<android.media.audiofx.AudioEffect>()
            try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            val buf = ShortArray(frame)
            try {
                rec.startRecording()
                var n = 0
                var echo = 0.0
                var loud = 0
                while (running) {
                    val got = rec.read(buf, 0, frame)
                    if (got <= 0) { Thread.sleep(20); continue }
                    var sum = 0.0
                    for (i in 0 until got) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / got)
                    n++
                    if (n <= 20) continue                         // settle (first 0.4 s)
                    if (n <= 50) { echo = maxOf(echo, rms); continue }  // learn the echo level (next 0.6 s)
                    val threshold = maxOf(echo * 2.8, 1400.0)
                    if (rms > threshold) loud++ else { loud = 0; if (rms < threshold * 0.6) echo = echo * 0.995 + rms * 0.005 }
                    if (loud >= 12) { running = false; main.post(onBarge); break }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                fx.forEach { try { it.release() } catch (_: Exception) {} }
                rec.release()
            }
        }
    }

    fun stop() { running = false }
}
