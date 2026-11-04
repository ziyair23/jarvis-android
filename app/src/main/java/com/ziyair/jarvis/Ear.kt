package com.ziyair.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Jarvis's ears: ONE microphone stream that stays open, with the phone's echo cancelling on.
 * Everything listens to that same stream, so the mic is never handed back and forth:
 *  - the wake word ("Hey Jarvis"), while he's waiting and while he's talking (to be cut off);
 *  - a voice detector (Silero) that knows the moment Marcus starts and stops talking;
 *  - speech-to-text on the phone (NVIDIA Parakeet, ~100 MB, downloaded once on Wi-Fi). No beeps, no Google round trip.
 * If anything here fails, Jarvis falls back to the old Google listening.
 */
class Ear(private val context: Context, private val wake: WakeWord, private val main: Handler, private val events: Events) {
    interface Events {
        fun onWake()
        fun onSpeechStart()
        fun onHeard(text: String, speechMs: Long, decodeMs: Long)
        fun onNoise()
        fun onEarFailed(why: String)
    }

    enum class Mode { SLEEP, LISTEN, SPEAKING, MUTE, HOLD }

    @Volatile var mode = Mode.SLEEP
        private set
    @Volatile private var running = false
    @Volatile private var modeChanged = true
    @Volatile var speechStarted = false; private set
    @Volatile private var loudAt = 0L
    /** Any voice-level sound in the last ms (quicker than full speech detection). */
    fun voiceRecently(ms: Long) = System.currentTimeMillis() - loudAt < ms
    private var worker: Thread? = null
    private val asr = Executors.newSingleThreadExecutor()

    fun set(m: Mode) {
        if (m == mode) return
        mode = m
        modeChanged = true
        if (m == Mode.LISTEN) speechStarted = false
    }

    fun alive() = running && worker?.isAlive == true

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (alive()) return true
        val cfg = Brain(context).config()
        val vad = try {
            Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = cfg.optDouble("ear_vad_threshold", 0.5).toFloat(),
                minSilenceDuration = cfg.optInt("ear_silence_ms", 750) / 1000f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = cfg.optInt("ear_max_speech_s", 25).toFloat()), sampleRate = RATE, numThreads = 1))
        } catch (e: Throwable) { events.onEarFailed("voice detector: ${e.message}"); return false }
        if (recognizer(context) == null) { vad.release(); events.onEarFailed("speech model didn't load"); return false }
        running = true
        worker = Thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2 * 2))   // 2 s of room so a slow moment never drops audio
            } catch (e: Exception) { running = false; main.post { events.onEarFailed("mic: ${e.message}") }; vad.release(); return@Thread }
            val fx = ArrayList<android.media.audiofx.AudioEffect>()
            try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            val chunk = ShortArray(CHUNK)
            val f = FloatArray(CHUNK)
            var errors = 0
            var last = Mode.MUTE
            var speechAt = 0L
            // early read: the moment he pauses, start reading what he said; if he was done, the words are ready when the pause is confirmed
            val earlyMs = cfg.optInt("ear_early_ms", 260)
            val fastEndMs = cfg.optInt("ear_fast_end_ms", 450)
            var endedEarly = false
            var utt = FloatArray(RATE * 8); var uttN = 0
            var quietMs = 0; var floor = 0.01f
            val pre = FloatArray(CHUNK * 8); var preN = 0   // the last ~0.6 s before he started, so the first word is never clipped
            var spec: java.util.concurrent.Future<String>? = null; var specN = 0
            fun resetUtt() { uttN = 0; quietMs = 0; spec = null; specN = 0; preN = 0 }
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("mic busy")
                while (running) {
                    var got = 0
                    while (got < CHUNK && running) {
                        val n = rec.read(chunk, got, CHUNK - got)
                        if (n <= 0) { errors++; if (errors > 25) throw IllegalStateException("mic stopped giving audio ($n)"); Thread.sleep(40); break }
                        errors = 0
                        got += n
                    }
                    if (!running || got < CHUNK) continue
                    val m = mode
                    if (modeChanged || m != last) {
                        modeChanged = false
                        if (m == Mode.LISTEN) { vad.reset(); speechAt = 0L; resetUtt() }
                        if ((m == Mode.SLEEP || m == Mode.SPEAKING) && last != Mode.SLEEP && last != Mode.SPEAKING) { wake.reset(); wake.gate.reset() }
                        wake.sensitive(m != Mode.SPEAKING)
                        last = m
                    }
                    when (m) {
                        Mode.SLEEP, Mode.SPEAKING -> if ((if (m == Mode.SLEEP) wake.gate.feed(chunk) else wake.feed(chunk))) {
                            // catch whatever he says straight after "Jarvis", in the same breath
                            if (m == Mode.SLEEP) { mode = Mode.LISTEN; speechStarted = false; vad.reset(); speechAt = 0L; last = Mode.LISTEN; resetUtt() }
                            main.post { events.onWake() }
                        }
                        Mode.LISTEN -> {
                            var sum = 0.0
                            for (i in 0 until CHUNK) { val v = chunk[i] / 32768f; f[i] = v; sum += v * v }
                            val rms = sqrt(sum / CHUNK).toFloat()
                            Orb.level(rms * 14f)
                            if (rms > 0.02f) loudAt = System.currentTimeMillis()
                            vad.acceptWaveform(f)
                            val wasStarted = speechStarted
                            if (!speechStarted && vad.isSpeechDetected()) {
                                speechStarted = true; speechAt = System.currentTimeMillis()
                                main.post { events.onSpeechStart() }
                            }
                            if (!speechStarted) {
                                floor = floor * 0.95f + rms * 0.05f
                                if (preN == pre.size) System.arraycopy(pre, CHUNK, pre, 0, pre.size - CHUNK).also { preN -= CHUNK }
                                System.arraycopy(f, 0, pre, preN, CHUNK); preN += CHUNK
                            } else {
                                if (!wasStarted && preN > 0) { System.arraycopy(pre, 0, utt, 0, preN); uttN = preN; preN = 0 }
                                if (uttN + CHUNK > utt.size) utt = utt.copyOf(utt.size * 2)
                                System.arraycopy(f, 0, utt, uttN, CHUNK); uttN += CHUNK
                                if (EarlyRead.voiced(rms, floor)) { quietMs = 0; spec = null }   // he's still going: drop any early read
                                else {
                                    quietMs += CHUNK * 1000 / RATE
                                    if (spec == null && quietMs >= earlyMs && uttN > RATE * 3 / 10) {
                                        val snap = utt.copyOf(uttN); specN = uttN
                                        spec = asr.submit<String> { decode(snap) }
                                    }
                                    // he's clearly finished (a complete thought, not "remind me to... um"): answer now instead of
                                    // waiting out the full second of silence
                                    val sp = spec
                                    if (sp != null && sp.isDone && quietMs >= fastEndMs) {
                                        val text = try { sp.get() } catch (_: Exception) { "" }
                                        if (EarlyRead.complete(text)) {
                                            mode = Mode.HOLD; last = Mode.HOLD; vad.reset()
                                            val n = specN; resetUtt(); endedEarly = true
                                            deliver(text, n * 1000L / RATE, 0, true)
                                            main.post { Trace.log(context, "ear", "finished early (complete sentence after ${fastEndMs}ms)") }
                                        }
                                    }
                                }
                            }
                            if (endedEarly) { endedEarly = false } else if (!vad.empty()) {
                                val seg = vad.front()
                                while (!vad.empty()) vad.pop()
                                mode = Mode.HOLD; last = Mode.HOLD
                                val samples = seg.samples
                                val early = spec
                                if (early != null && EarlyRead.usable(samples.size, specN, RATE)) {
                                    val t0 = System.currentTimeMillis()
                                    asr.execute { val text = try { early.get() } catch (_: Exception) { "" }
                                        if (text.isBlank()) transcribe(samples) else deliver(text, samples.size * 1000L / RATE, System.currentTimeMillis() - t0, true) }
                                } else asr.execute { transcribe(samples) }
                                resetUtt()
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                if (running) main.post { events.onEarFailed(e.message ?: "mic error") }
            } finally {
                running = false
                try { rec.stop() } catch (_: Exception) {}
                fx.forEach { try { it.release() } catch (_: Exception) {} }
                rec.release()
                vad.release()
            }
        }.also { it.name = "jarvis-ear"; it.start() }
        return true
    }

    /** Speech to text for one stretch of audio (on the decoder thread). */
    private fun decode(samples: FloatArray): String {
        val r = recognizer(context) ?: return ""
        return try {
            val s = r.createStream()
            s.acceptWaveform(samples, RATE)
            r.decode(s)
            val out = r.getResult(s).text
            s.release()
            out
        } catch (e: Throwable) { Problems.log(context, "speech-to-text: ${e.message}"); "" }
    }

    private fun transcribe(samples: FloatArray) {
        val t0 = System.currentTimeMillis()
        if (recognizer(context) == null) { main.post { events.onEarFailed("speech model unloaded") }; return }
        val text = decode(samples)
        deliver(text, samples.size * 1000L / RATE, System.currentTimeMillis() - t0, false)
    }

    private fun deliver(text: String, speechMs: Long, ms: Long, early: Boolean) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (early) Trace.log(context, "ear", "read early: words were ready ${ms}ms after he finished")
        // just a cough, a breath or "um": keep listening
        val junk = clean.isBlank() || Regex("^(uh+|um+|hmm+|mm+|ah+|oh|eh|huh)[.?!]*$", RegexOption.IGNORE_CASE).matches(clean) || clean.count { it.isLetterOrDigit() } < 2
        main.post { if (junk) events.onNoise() else events.onHeard(clean, speechMs, ms) }
    }

    fun stop() {
        running = false
        val w = worker
        if (w != null && w != Thread.currentThread()) try { w.join(1000) } catch (_: Exception) {}
        worker = null
    }

    companion object {
        const val RATE = 16000
        const val CHUNK = 1280
        private const val DIR = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
        private const val URL_ = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$DIR.tar.bz2"
        private const val MB = 104

        @Volatile var state = ""
        @Volatile var downloading = false
        private var rec: OfflineRecognizer? = null

        private fun dir(c: Context) = File(File(c.filesDir, "ears"), DIR)
        fun ready(c: Context) = File(dir(c), ".ok").exists()

        fun status(c: Context): String = when {
            downloading -> "downloading $state"
            ready(c) -> if (Brain(c).config().optString("ears", "phone") == "phone") "on the phone (Parakeet)" else "Google (switched remotely)"
            state.startsWith("failed") -> "Google for now, download $state"
            else -> "Google for now (phone ears download on Wi-Fi, $MB MB)"
        }

        fun loaded() = rec != null
        @Volatile private var loading = false
        /** Load the speech model off the main thread (it's ~130 MB; loading it on the main thread would freeze the screen). */
        fun loadAsync(c: Context) {
            if (rec != null || loading || !ready(c)) return
            loading = true
            Thread { try { recognizer(c) } finally { loading = false } }.start()
        }

        @Synchronized fun recognizer(c: Context): OfflineRecognizer? {
            rec?.let { return it }
            if (!ready(c)) return null
            return try {
                val d = dir(c)
                OfflineRecognizer(null, OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = File(d, "model.int8.onnx").path),
                        tokens = File(d, "tokens.txt").path, numThreads = 2, debug = false, provider = "cpu"))).also { rec = it }
            } catch (e: Throwable) { Problems.log(c, "speech model: ${e.message}"); null }
        }

        /** Download the speech model (blocking; background thread). */
        fun download(c: Context): Boolean {
            if (ready(c)) return true
            synchronized(this) { if (downloading) return false; downloading = true }
            state = "0%"
            val root = File(c.filesDir, "ears")
            val tmp = File(root, "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
            try {
                val conn = URL(URL_).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (MB * 1_000_000L)
                val counting = object : java.io.FilterInputStream(BufferedInputStream(conn.inputStream, 1 shl 16)) {
                    var n = 0L
                    override fun read(): Int = super.read().also { if (it >= 0) n++ }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { n += it; state = "${n * 100 / total}%" } }
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                    val base = tmp.canonicalPath
                    while (true) {
                        val e = tar.nextEntry ?: break
                        if (e.name.contains("test_wavs")) continue
                        val out = File(tmp, e.name)
                        if (!out.canonicalPath.startsWith(base)) continue
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
                conn.disconnect()
                val got = File(tmp, DIR)
                if (!File(got, "model.int8.onnx").exists() || !File(got, "tokens.txt").exists()) throw Exception("model files missing")
                dir(c).deleteRecursively()
                if (!got.renameTo(dir(c))) throw Exception("couldn't save the model")
                File(dir(c), ".ok").writeText("ok")
                state = "done"
                Trace.log(c, "ears", "phone speech model downloaded")
                return true
            } catch (e: Exception) {
                state = "failed: ${e.message}"
                Trace.log(c, "ears", "download failed: ${e.message}")
                return false
            } finally {
                tmp.deleteRecursively()
                downloading = false
            }
        }
    }
}

/** The early-read rules, kept pure so they're unit-tested. */
object EarlyRead {
    private val TRAIL = setOf("and", "or", "but", "so", "to", "the", "a", "an", "for", "with", "my", "at", "about", "of", "in", "on", "is", "if",
        "because", "cause", "um", "uh", "uhm", "hmm", "then", "can", "could", "would", "should", "will", "i", "we", "you", "he", "she", "they",
        "it's", "i'm", "what", "when", "where", "how", "why", "which", "who", "from", "into", "by", "was", "are", "be", "just", "maybe", "also", "her", "his", "your", "our", "their", "this", "some", "any")
    /** Does this sound like he's finished, rather than pausing mid-thought? Pure, unit-tested. */
    fun complete(text: String): Boolean {
        val t = text.trim().lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || text.trim().endsWith("...") || text.trim().endsWith(",")) return false
        val last = t.substringAfterLast(' ')
        if (last in TRAIL) return false
        return t.split(' ').size >= 2 || Regex("^(yes|yeah|yep|no|nope|nah|sure|okay|ok|thanks|stop|done|perfect|cool|right|correct)$").matches(t)
    }
    /** Is this bit of audio him talking (well above the room's background)? */
    fun voiced(rms: Float, floor: Float) = rms > maxOf(0.012f, floor * 2.5f)
    /** Use the early read only if the confirmed stretch isn't meaningfully longer than what was read (no words after the pause). */
    fun usable(segSamples: Int, readSamples: Int, rate: Int) = readSamples > 0 && segSamples <= readSamples + rate * 35 / 100
}
