package com.ziyair.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Speaks a reply sentence by sentence as it streams in: one thread turns sentences into audio (Piper),
 * another plays them back to back, so he starts talking after the first sentence and the next one is
 * ready by the time he finishes the last.
 */
class SpeechStream(
    private val context: Context,
    private val speed: Float,
    private val main: Handler,
    private val waitWhile: () -> Boolean,
) {
    private val texts = LinkedBlockingQueue<String>()
    private val audio = LinkedBlockingQueue<Pair<FloatArray, Int>>()
    @Volatile var started = false; private set
    @Volatile private var cancelled = false
    @Volatile private var onDone: (() -> Unit)? = null
    @Volatile private var track: AudioTrack? = null

    fun add(sentence: String) {
        if (cancelled) return
        if (!started) { started = true; start() }
        texts.put(sentence)
    }

    /** Called once the full reply has arrived; done() runs after the last sentence has been spoken. */
    fun finish(done: () -> Unit) {
        onDone = done
        if (!started) { main.post(done); return }
        texts.put(END)
    }

    fun cancel() {
        cancelled = true
        texts.put(END)
        try { track?.stop() } catch (_: Exception) {}
    }

    private fun start() {
        thread(name = "jarvis-voice-synth") {
            while (!cancelled) {
                val t = texts.poll(60, TimeUnit.SECONDS) ?: break
                if (t === END) break
                Piper.synth(context, t, speed)?.let { audio.put(it) }
            }
            audio.put(DONE)
        }
        thread(name = "jarvis-voice-play") {
            while (!cancelled) {
                val a = audio.poll(60, TimeUnit.SECONDS) ?: break
                if (a === DONE) break
                while (waitWhile() && !cancelled) Thread.sleep(40)
                play(a.first, a.second)
            }
            try { track?.release() } catch (_: Exception) {}
            track = null
            if (!cancelled) onDone?.let { main.post(it) }
        }
    }

    /** Blocks until this sentence has finished playing. */
    private fun play(samples: FloatArray, rate: Int) {
        try {
            try { track?.release() } catch (_: Exception) {}
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4).build()
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track = t
            t.play()
            val until = System.currentTimeMillis() + samples.size * 1000L / rate + 1500
            while (!cancelled && t.playbackHeadPosition < samples.size - 1 && System.currentTimeMillis() < until) Thread.sleep(20)
            try { t.stop() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    companion object {
        private val END = String(charArrayOf('\u0000'))
        private val DONE = FloatArray(0) to 0
    }
}
