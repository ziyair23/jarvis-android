package com.ziyair.jarvis

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jarvis's own voice: Piper "Alan" (British male), running entirely on the phone. Free, no account, works offline.
 * The voice (about 67 MB) downloads once, then lives in the app's private storage.
 */
object Piper {
    private const val NAME = "vits-piper-en_GB-alan-medium"
    private const val MODEL = "en_GB-alan-medium.onnx"
    private const val URL_ = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$NAME.tar.bz2"

    @Volatile var state = ""
    @Volatile var downloading = false
    private var engine: OfflineTts? = null

    private fun root(c: Context) = File(c.filesDir, "voices")
    private fun dir(c: Context) = File(root(c), NAME)
    fun installed(c: Context) = File(dir(c), ".ok").exists()

    fun label(c: Context): String = when {
        downloading -> "Jarvis voice: downloading $state"
        installed(c) -> "Jarvis voice: Alan, ready"
        state.startsWith("failed") -> "Jarvis voice: download failed, tap to retry"
        else -> "Download Jarvis voice (67 MB, once)"
    }

    /** Blocking; run on a background thread. */
    fun download(c: Context): Boolean {
        if (installed(c)) return true
        synchronized(this) { if (downloading) return false; downloading = true }
        state = "0%"
        val tmp = File(root(c), "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val conn = URL(URL_).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 67_220_121L
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
            val got = File(tmp, NAME)
            if (!File(got, MODEL).exists()) throw Exception("voice files missing")
            dir(c).deleteRecursively()
            if (!got.renameTo(dir(c))) throw Exception("couldn't save voice")
            File(dir(c), ".ok").writeText("ok")
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

    @Synchronized private fun engine(c: Context): OfflineTts? {
        engine?.let { return it }
        if (!installed(c)) return null
        return try {
            val d = dir(c)
            val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = File(d, MODEL).path, tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path),
                numThreads = 2))
            OfflineTts(null, cfg).also { engine = it }
        } catch (e: Throwable) {
            state = "failed: voice engine ${e.message}"
            null
        }
    }

    /** Float samples and sample rate, or null (then Jarvis uses the phone's voice). Blocking. */
    @Synchronized fun synth(c: Context, text: String, speed: Float): Pair<FloatArray, Int>? = try {
        val e = engine(c)
        e?.generate(text, 0, speed)?.let { it.samples to it.sampleRate }?.takeIf { it.first.isNotEmpty() }
    } catch (_: Throwable) { null }

    /** Load the voice ahead of time so the first reply isn't slow. */
    fun warm(c: Context) { try { engine(c) } catch (_: Throwable) {} }
}
