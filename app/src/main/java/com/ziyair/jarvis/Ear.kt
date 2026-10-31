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
                        if (m == Mode.LISTEN) { vad.reset(); speechAt = 0L }
                        if ((m == Mode.SLEEP || m == Mode.SPEAKING) && last != Mode.SLEEP && last != Mode.SPEAKING) wake.reset()
                        wake.sensitive(m != Mode.SPEAKING)
                        last = m
                    }
                    when (m) {
                        Mode.SLEEP, Mode.SPEAKING -> if (wake.feed(chunk)) {
                            // catch whatever he says straight after "Jarvis", in the same breath
                            if (m == Mode.SLEEP) { mode = Mode.LISTEN; speechStarted = false; vad.reset(); speechAt = 0L; last = Mode.LISTEN }
                            main.post { events.onWake() }
                        }
                        Mode.LISTEN -> {
                            var sum = 0.0
                            for (i in 0 until CHUNK) { val v = chunk[i] / 32768f; f[i] = v; sum += v * v }
                            Orb.level((sqrt(sum / CHUNK).toFloat() * 14f))
                            vad.acceptWaveform(f)
                            if (!speechStarted && vad.isSpeechDetected()) {
                                speechStarted = true; speechAt = System.currentTimeMillis()
                                main.post { events.onSpeechStart() }
                            }
                            if (!vad.empty()) {
                                val seg = vad.front()
                                while (!vad.empty()) vad.pop()
                                mode = Mode.HOLD; last = Mode.HOLD
                                val samples = seg.samples
                                asr.execute { transcribe(samples) }
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

    private fun transcribe(samples: FloatArray) {
        val t0 = System.currentTimeMillis()
        val r = recognizer(context)
        if (r == null) { main.post { events.onEarFailed("speech model unloaded") }; return }
        val text = try {
            val s = r.createStream()
            s.acceptWaveform(samples, RATE)
            r.decode(s)
            val out = r.getResult(s).text
            s.release()
            out
        } catch (e: Throwable) { Problems.log(context, "speech-to-text: ${e.message}"); "" }
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val ms = System.currentTimeMillis() - t0
        val speechMs = samples.size * 1000L / RATE
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
