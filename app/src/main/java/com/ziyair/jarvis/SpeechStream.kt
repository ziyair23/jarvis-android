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
 * Speaks a reply as it streams in, fluently: the first sentence is voiced on its own so he starts talking straight
 * away, then everything that follows is voiced as ONE flowing piece (the voice engine carries his tone from sentence
 * to sentence, the way a person talks) and played as it's made. No glued-together sentences, no stop-start pauses.
 * Set "voice_fluent": false in config to go back to sentence by sentence.
 */
class SpeechStream(
    private val context: Context,
    private val speed: Float,
    private val main: Handler,
    private val gapMs: Int = 160,
    private val waitWhile: () -> Boolean,
) {
    private class Chunk(val pcm: FloatArray, val rate: Int, val newPiece: Boolean)

    private val texts = LinkedBlockingQueue<String>()
    private val audio = LinkedBlockingQueue<Chunk>()
    @Volatile var started = false; private set
    @Volatile private var cancelled = false
    @Volatile private var onDone: (() -> Unit)? = null
    @Volatile private var track: AudioTrack? = null
    private val fluent = try { Brain(context).config().optBoolean("voice_fluent", true) } catch (_: Exception) { true }
    private val gatherMs = try { Brain(context).config().optLong("voice_gather_ms", 120L) } catch (_: Exception) { 120L }

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
            var first = true
            var ended = false
            while (!cancelled && !ended) {
                val t = texts.poll(60, TimeUnit.SECONDS) ?: break
                if (t === END) break
                var piece = t
                if (fluent && !first) {
                    // gather what's arrived (and whatever lands in the next moment) into one stretch of speech
                    val sb = StringBuilder(t)
                    val until = System.currentTimeMillis() + gatherMs
                    while (!cancelled) {
                        val left = until - System.currentTimeMillis()
                        val n = (if (left > 0) texts.poll(left, TimeUnit.MILLISECONDS) else texts.poll()) ?: break
                        if (n === END) { ended = true; break }
                        sb.append(' ').append(n)
                        if (sb.length > 600) break
                    }
                    piece = sb.toString()
                }
                var newPiece = true
                val ok = Piper.synthFlow(context, piece, speed, { !cancelled }) { s, r -> audio.put(Chunk(s, r, newPiece)); newPiece = false }
                if (!ok) break
                first = false
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
                    // ONE continuous audio stream for the whole reply: no restart (and no click) anywhere
                    if (t == null) { val tr = open(a.rate); t = tr; track = tr; Orb.voiceStart(a.rate) { tr.playbackHeadPosition.toLong() }; tr.play() }
                    // every sentence comes out of the voice engine wrapped in its own silence (a beat before, a longer one
                    // after), and back to back that was the pause he heard between sentences. Trim both ends and join
                    // them with one short, natural breath instead.
                    if (!first) written += pause(t, a.rate)
                    val pcm = trimTail(trimHead(a.pcm, a.rate), a.rate)
                    Orb.voiceAdd(pcm)
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
            // let the last words finish playing
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

    private fun pause(t: AudioTrack, rate: Int): Int {
        val n = rate * gapMs / 1000
        val z = FloatArray(n)
        Orb.voiceAdd(z)
        t.write(z, 0, n, AudioTrack.WRITE_BLOCKING)
        return n
    }

    companion object {
        /** Cut the dead air after the last sound (keeping a natural tail), with a soft fade-out so nothing clicks. Pure. */
        fun trimTail(s: FloatArray, rate: Int): FloatArray {
            var b = s.size - 1; while (b > 0 && kotlin.math.abs(s[b]) < 0.012f) b--
            b = minOf(s.size - 1, b + rate * 6 / 100)       // keep 60 ms after the last sound (word endings fade, not cut)
            if (b <= 0 || b >= s.size - 1) return s
            val out = s.copyOfRange(0, b + 1)
            val fade = minOf(out.size / 4, rate / 60)
            for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
            return out
        }

        /** Cut the dead air before the first sound, with a soft fade-in so nothing clicks. */
        fun trimHead(s: FloatArray, rate: Int): FloatArray {
            var a = 0; while (a < s.size && kotlin.math.abs(s[a]) < 0.012f) a++
            a = maxOf(0, a - rate * 3 / 100)              // keep 30 ms before the first sound (soft "s" and "f" starts)
            if (a >= s.size - 1) return s
            val out = s.copyOfRange(a, s.size)
            val fade = minOf(out.size / 4, rate / 200)
            for (i in 0 until fade) out[i] *= i.toFloat() / fade
            return out
        }

        private val END = String(charArrayOf('\u0000'))
        private val DONE = Chunk(FloatArray(0), 0, false)
    }
}
