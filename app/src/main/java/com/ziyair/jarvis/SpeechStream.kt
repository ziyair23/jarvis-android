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
    private val gapMs: Int = 160,
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
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
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
            var written = 0L
            var t: AudioTrack? = null
            var first = true
            while (!cancelled) {
                val a = audio.poll(60, TimeUnit.SECONDS) ?: break
                if (a === DONE) break
                while (waitWhile() && !cancelled) Thread.sleep(40)
                if (cancelled) break
                try {
                    // ONE continuous audio stream for the whole reply: no restart (and no click) between sentences
                    if (t == null) { t = open(a.second); track = t; t.play() }
                    val pcm = trim(a.first, a.second)
                    if (!first) written += pause(t, a.second)          // a natural breath between sentences
                    first = false
                    var off = 0
                    while (off < pcm.size && !cancelled) {
                        val n = t.write(pcm, off, minOf(4096, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                    }
                    written += pcm.size
                } catch (_: Exception) {}
            }
            // let the last sentence finish playing
            t?.let { tr ->
                val until = System.currentTimeMillis() + 15_000
                while (!cancelled && tr.playbackHeadPosition < written - 1 && System.currentTimeMillis() < until) Thread.sleep(20)
                try { tr.stop() } catch (_: Exception) {}
                try { tr.release() } catch (_: Exception) {}
            }
            track = null
            if (!cancelled) onDone?.let { main.post(it) }
        }
    }

    private fun open(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 2)).build()   // half a second of buffer
    }

    /** Cut the dead air the voice engine leaves at the start and end of each sentence, with a soft fade so nothing clicks. */
    private fun trim(s: FloatArray, rate: Int): FloatArray {
        val th = 0.012f
        var a = 0; while (a < s.size && kotlin.math.abs(s[a]) < th) a++
        var b = s.size - 1; while (b > a && kotlin.math.abs(s[b]) < th) b--
        a = maxOf(0, a - rate / 100)                  // keep 10 ms before the first sound
        b = minOf(s.size - 1, b + rate / 25)           // and 40 ms after the last
        if (b <= a) return s
        val out = s.copyOfRange(a, b + 1)
        val fade = minOf(out.size / 4, rate / 200)     // 5 ms fades
        for (i in 0 until fade) { val g = i.toFloat() / fade; out[i] *= g; out[out.size - 1 - i] *= g }
        return out
    }

    private fun pause(t: AudioTrack, rate: Int): Int {
        val n = rate * gapMs / 1000
        val z = FloatArray(n)
        t.write(z, 0, n, AudioTrack.WRITE_BLOCKING)
        return n
    }

    companion object {
        private val END = String(charArrayOf('\u0000'))
        private val DONE = FloatArray(0) to 0
    }
}
