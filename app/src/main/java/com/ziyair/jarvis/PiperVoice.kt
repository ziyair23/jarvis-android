package com.ziyair.jarvis

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jarvis's own voice, running entirely on the phone (free, no account, works offline). Three British men to pick from:
 *  - Alan (Piper): light and quick, 67 MB.
 *  - George and Lewis (Kokoro): smoother and more natural, one 103 MB download covers both.
 * (Named Piper for history; it now handles both voice engines.)
 */
object Piper {
    private class Model(val dir: String, val url: String, val mb: Int, val kokoro: Boolean)
    private val ALAN = Model("vits-piper-en_GB-alan-medium", "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2", 67, false)
    /** Full-quality Kokoro v1.0 (not the compressed copy, which sounded warbly), with British pronunciation. */
    private val KOKORO = Model("kokoro-multi-lang-v1_0", "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2", 350, true)
    private const val OLD_KOKORO = "kokoro-int8-en-v0_19"

    /** Voice name -> (model, speaker id). Kokoro v1.0 British men: 24 daniel, 25 fable, 26 george, 27 lewis. */
    private val VOICES = linkedMapOf("alan" to (ALAN to 0), "george" to (KOKORO to 26), "lewis" to (KOKORO to 27), "daniel" to (KOKORO to 24), "fable" to (KOKORO to 25))
    val NAMES = VOICES.keys.toList()

    @Volatile var state = ""
    @Volatile var downloading = false
    private var engine: OfflineTts? = null
    private var engineFor: Model? = null

    private fun root(c: Context) = File(c.filesDir, "voices")
    private fun dir(c: Context, m: Model) = File(root(c), m.dir)
    private fun ready(c: Context, m: Model) = File(dir(c, m), ".ok").exists()

    fun pick(c: Context) = Prefs(c).sp.getString("voice_pick", "alan")?.takeIf { it in VOICES } ?: "alan"
    fun setPick(c: Context, v: String) = Prefs(c).sp.edit().putString("voice_pick", v).apply()
    fun pickReady(c: Context) = ready(c, VOICES[pick(c)]!!.first)

    /** The voice actually used: his pick if downloaded, otherwise Alan, otherwise none (phone voice). */
    private fun current(c: Context): Pair<Model, Int>? {
        val chosen = VOICES[pick(c)]!!
        if (ready(c, chosen.first)) return chosen
        return if (ready(c, ALAN)) ALAN to 0 else null
    }
    fun installed(c: Context) = current(c) != null

    fun label(c: Context): String {
        val name = pick(c).replaceFirstChar { it.uppercase() }
        return when {
            downloading -> "Voice: $name, downloading $state"
            pickReady(c) -> "Voice: $name (tap to change)"
            state.startsWith("failed") -> "Voice: $name, download failed, tap to retry"
            else -> "Voice: $name, tap to download (${VOICES[pick(c)]!!.first.mb} MB)"
        }
    }

    /** Downloads his chosen voice (blocking; run on a background thread). */
    fun download(c: Context): Boolean = fetch(c, VOICES[pick(c)]!!.first)

    private fun fetch(c: Context, m: Model): Boolean {
        if (ready(c, m)) return true
        synchronized(this) { if (downloading) return false; downloading = true }
        state = "0%"
        val tmp = File(root(c), "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val conn = URL(m.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.mb * 1_000_000L)
            val counting = object : java.io.FilterInputStream(BufferedInputStream(conn.inputStream, 1 shl 16)) {
                var n = 0L
                override fun read(): Int = super.read().also { if (it >= 0) { n++; if (n % 65536 == 0L) state = "${n * 100 / total}%" } }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { n += it; state = "${n * 100 / total}%" } }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                val base = tmp.canonicalPath
                while (true) {
                    val e = tar.nextEntry ?: break
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(base)) continue
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
            conn.disconnect()
            val got = File(tmp, m.dir)
            if (!File(got, "tokens.txt").exists()) throw Exception("voice files missing")
            dir(c, m).deleteRecursively()
            if (!got.renameTo(dir(c, m))) throw Exception("couldn't save voice")
            File(dir(c, m), ".ok").writeText("ok")
            if (m.kokoro) File(root(c), OLD_KOKORO).deleteRecursively()   // the old compressed voices aren't needed any more
            state = "done"
            return true
        } catch (e: Exception) {
            state = "failed: ${e.message}"
            return false
        } finally {
            tmp.deleteRecursively()
            downloading = false
        }
    }

    @Synchronized private fun engine(c: Context, m: Model): OfflineTts? {
        if (engineFor === m) engine?.let { return it }
        try { engine?.release() } catch (_: Throwable) {}
        engine = null; engineFor = null
        return try {
            val d = dir(c, m)
            val cfg = if (m.kokoro) OfflineTtsConfig(model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(model = File(d, "model.onnx").path, voices = File(d, "voices.bin").path,
                    tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path,
                    lexicon = File(d, "lexicon-gb-en.txt").path),
                numThreads = 4))
            else OfflineTtsConfig(model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = File(d, "en_GB-alan-medium.onnx").path, tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path),
                numThreads = 2))
            OfflineTts(null, cfg).also { engine = it; engineFor = m }
        } catch (e: Throwable) {
            state = "failed: voice engine ${e.message}"
            Problems.log(c, "voice engine: ${e.message}")
            null
        }
    }

    /** Float samples and sample rate, or null (then Jarvis uses the phone's voice). Blocking. */
    /** Clean text up so it's read naturally: no symbols, markdown, links or run-together sentences. */
    fun speakable(t0: String): String {
        var t = t0
        t = t.replace(Regex("https?://\\S+"), "")
        t = t.replace(Regex("[*_#`>~|]+"), " ")
        t = t.replace(Regex("(?m)^\\s*[-\u2022]\\s+"), "")
        t = t.replace(Regex("\u00b0\\s?F\\b"), " degrees").replace("\u00b0", " degrees")
        t = t.replace("%", " percent").replace("&", " and ").replace(Regex("\\bvs\\.?\\b"), "versus")
        t = t.replace(Regex("([.!?])([A-Z])"), "$1 $2")
        t = t.replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}]"), "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Short lines come straight from his phrasebook when they've been said before (instant); new ones get saved. */
    @Synchronized fun synth(c: Context, text0: String, speed: Float): Pair<FloatArray, Int>? {
        val (m, sid) = current(c) ?: return null
        val text = speakable(text0).ifBlank { return null }
        val vid = m.dir + "_" + sid
        Voicebank.get(c, vid, text, speed)?.let { return it }
        return try {
            engine(c, m)?.generate(text, sid, speed)?.let { it.samples to it.sampleRate }?.takeIf { it.first.isNotEmpty() }
                ?.also { Voicebank.put(c, vid, text, speed, it.first, it.second) }
        } catch (e: Throwable) { Problems.log(c, "voice: ${e.message}"); null }
    }

    /** Only if it's already in the phrasebook (never makes him wait). */
    fun cached(c: Context, text0: String, speed: Float): Pair<FloatArray, Int>? {
        val (m, sid) = current(c) ?: return null
        val text = speakable(text0).ifBlank { return null }
        return Voicebank.get(c, m.dir + "_" + sid, text, speed)
    }

    /** Load the voice ahead of time so the first reply isn't slow. */
    fun warm(c: Context) { try { current(c)?.let { engine(c, it.first) } } catch (_: Throwable) {} }
}
