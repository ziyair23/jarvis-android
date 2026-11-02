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
    /** Slot 50 of the voice file is rewritten on the phone with Jarvis's own blended voice (see blend()). */
    private const val BLEND_SLOT = 50
    private val VOICES = linkedMapOf("jarvis" to (KOKORO to BLEND_SLOT), "alan" to (ALAN to 0), "george" to (KOKORO to 26), "lewis" to (KOKORO to 27), "daniel" to (KOKORO to 24), "fable" to (KOKORO to 25))
    private val SPEAKER = mapOf("daniel" to 24, "fable" to 25, "george" to 26, "lewis" to 27)
    @Volatile private var blendKey = ""
    val NAMES = VOICES.keys.toList()

    @Volatile var state = ""
    @Volatile var downloading = false
    private var engine: OfflineTts? = null
    private var engineFor: Model? = null

    private fun root(c: Context) = File(c.filesDir, "voices")
    private fun dir(c: Context, m: Model) = File(root(c), m.dir)
    private fun ready(c: Context, m: Model) = File(dir(c, m), ".ok").exists()

    fun pick(c: Context): String {
        val p = Prefs(c)
        // a voice change sent from config (e.g. switching him to his own blended voice), applied once
        val def = try { Brain(c).config().optString("voice_default") } catch (_: Exception) { "" }
        if (def in VOICES && p.sp.getString("voice_default_applied", "") != def) p.sp.edit().putString("voice_pick", def).putString("voice_default_applied", def).apply()
        return p.sp.getString("voice_pick", "alan")?.takeIf { it in VOICES } ?: "alan"
    }

    /**
     * Jarvis's own voice: a mix of the British voices, written into one slot of the voice file on the phone.
     * The mix comes from config ("voice_blend": {"george": 0.6, "lewis": 0.4}), so it can be retuned without a new build.
     */
    private fun blend(c: Context, d: File): String {
        val cfg = try { Brain(c).config() } catch (_: Exception) { null }
        val b = cfg?.optJSONObject("voice_blend") ?: org.json.JSONObject().put("george", 0.6).put("lewis", 0.4)
        val key = b.toString()
        val mark = File(d, ".blend")
        if (mark.exists() && mark.readText() == key) return key
        val n = 510 * 256; val bytes = n * 4
        java.io.RandomAccessFile(File(d, "voices.bin"), "rw").use { f ->
            val out = FloatArray(n)
            var total = 0.0
            for (name in b.keys()) {
                val sid = SPEAKER[name] ?: continue
                val w = b.optDouble(name, 0.0); if (w <= 0) continue
                total += w
                val buf = ByteArray(bytes); f.seek(sid.toLong() * bytes); f.readFully(buf)
                val fb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n) out[i] += (fb.get(i) * w).toFloat()
            }
            if (total <= 0) return key
            val ob = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) ob.putFloat((out[i] / total).toFloat())
            f.seek(BLEND_SLOT.toLong() * bytes); f.write(ob.array())
        }
        mark.writeText(key)
        Trace.log(c, "voice", "blended his own voice: $key")
        return key
    }
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
            state.startsWith("failed") -> "Voice: $name didn't finish (${state.removePrefix("failed: ").take(90)}). Tap to carry on"
            else -> "Voice: $name, tap to download (${VOICES[pick(c)]!!.first.mb} MB)"
        }
    }

    /** Downloads his chosen voice (blocking; run on a background thread). */
    fun download(c: Context): Boolean = fetch(c, VOICES[pick(c)]!!.first)

    /**
     * Downloads to a file first and RESUMES where it left off if Wi-Fi drops or the phone dozes (the big voice is
     * 350 MB, so one hiccup used to throw it all away), retrying on its own; then unpacks. Keeps the phone awake
     * meanwhile, checks there's room first, and writes the real reason to the flight log if it fails.
     */
    private fun fetch(c: Context, m: Model): Boolean {
        if (ready(c, m)) return true
        synchronized(this) { if (downloading) return false; downloading = true }
        state = "0%"
        val root = root(c).apply { mkdirs() }
        val part = File(root, m.dir + ".tar.bz2.part")
        val tmp = File(root, "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        val wake = try { pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:voice").apply { acquire(30 * 60_000L) } } catch (_: Exception) { null }
        @Suppress("DEPRECATION")
        val wifi = try { (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis:voice").apply { acquire() } } catch (_: Exception) { null }
        try {
            // room for the download plus the unpacked voice
            val needMb = m.mb * 23 / 10 + 50 - part.length() / 1_000_000
            val freeMb = root.usableSpace / 1_000_000
            if (freeMb < needMb) throw Exception("not enough storage: needs about $needMb MB free, the phone has $freeMb MB")
            Trace.log(c, "voice", "downloading ${m.dir} (${m.mb} MB), ${part.length() / 1_000_000} MB already here, $freeMb MB free")
            var total = -1L
            var tries = 0
            while (true) {
                try { total = download(m.url, part, total); break }
                catch (e: Exception) {
                    tries++
                    Trace.log(c, "voice", "download hiccup $tries at ${part.length() / 1_000_000} MB: ${e.message}")
                    if (tries >= 8) throw Exception("download kept dropping (${e.message}); tap to carry on from ${part.length() / 1_000_000} MB")
                    state = "${if (total > 0) part.length() * 100 / total else 0}%, reconnecting"
                    Thread.sleep(minOf(30_000L, 2_000L shl minOf(tries, 4)))
                }
            }
            state = "unpacking"
            Trace.log(c, "voice", "downloaded ${part.length() / 1_000_000} MB, unpacking")
            val size = part.length()
            val counting = object : java.io.FilterInputStream(BufferedInputStream(part.inputStream(), 1 shl 16)) {
                var n = 0L
                override fun read(): Int = super.read().also { if (it >= 0) n++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { n += it; state = "unpacking ${n * 100 / size}%" } }
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
            val got = File(tmp, m.dir)
            if (!File(got, "tokens.txt").exists() || !File(got, if (m.kokoro) "model.onnx" else "en_GB-alan-medium.onnx").exists()) {
                part.delete()   // a bad copy: start fresh next time
                throw Exception("voice files missing after unpacking")
            }
            dir(c, m).deleteRecursively()
            if (!got.renameTo(dir(c, m))) throw Exception("couldn't save voice")
            File(dir(c, m), ".ok").writeText("ok")
            part.delete()
            if (m.kokoro) File(root(c), OLD_KOKORO).deleteRecursively()   // the old compressed voices aren't needed any more
            state = "done"
            Trace.log(c, "voice", "${m.dir} ready")
            return true
        } catch (e: Throwable) {
            state = "failed: ${e.message}"
            Trace.log(c, "voice", "download failed: ${e.message}")
            Problems.log(c, "voice download: ${e.message}")
            return false
        } finally {
            tmp.deleteRecursively()
            try { wake?.release() } catch (_: Exception) {}
            try { wifi?.release() } catch (_: Exception) {}
            downloading = false
        }
    }

    /** Fetches [url] into [part], carrying on from however much is already there. Returns the full size. */
    private fun download(url: String, part: File, knownTotal: Long): Long {
        // GitHub hands out a short-lived link to the real file: ask for a fresh one each time
        var u = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 120000; conn.instanceFollowRedirects = false
            val have = part.length()
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399) { val loc = conn.getHeaderField("Location") ?: throw Exception("HTTP $code"); conn.disconnect(); u = URL(u, loc); if (++hops > 5) throw Exception("too many redirects"); continue }
            break
        }
        val code = conn.responseCode
        if (code == 416 && knownTotal > 0 && part.length() >= knownTotal) { conn.disconnect(); return knownTotal }   // already complete
        if (code == 416) { conn.disconnect(); part.delete(); throw Exception("restarting the download") }
        if (code != 200 && code != 206) throw Exception("HTTP $code")
        val append = code == 206
        val total = if (append) conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
            else conn.contentLengthLong
        val base = if (append) part.length() else 0L
        java.io.FileOutputStream(part, append).use { out ->
            conn.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16); var n = base
                while (true) {
                    val r = inp.read(buf); if (r < 0) break
                    out.write(buf, 0, r); n += r
                    if (total > 0) state = "${n * 100 / total}%"
                }
            }
        }
        conn.disconnect()
        if (total > 0 && part.length() < total) throw Exception("connection closed early")
        return total
    }

    @Synchronized private fun engine(c: Context, m: Model): OfflineTts? {
        // a new blend means the voice file changed: reload the engine
        if (m.kokoro && ready(c, m)) try { val k = blend(c, dir(c, m)); if (k != blendKey) { blendKey = k; if (engineFor === m) { try { engine?.release() } catch (_: Throwable) {}; engine = null; engineFor = null } } } catch (e: Exception) { Problems.log(c, "voice blend: ${e.message}") }
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
        val vid = m.dir + "_" + sid + (if (sid == BLEND_SLOT) "_" + blendKey.hashCode().toUInt().toString(16) else "")
        if (sid == BLEND_SLOT) engine(c, m)   // make sure the blend is current before using the phrasebook
        Voicebank.get(c, vid, text, speed)?.let { return it }
        return try {
            engine(c, m)?.generate(text, sid, speed)?.let { it.samples to it.sampleRate }?.takeIf { it.first.isNotEmpty() }
                ?.also { Voicebank.put(c, vid, text, speed, it.first, it.second) }
        } catch (e: Throwable) { Problems.log(c, "voice: ${e.message}"); null }
    }

    /**
     * Speaks a whole stretch of text in one go, so it flows like one person talking (no reset between sentences),
     * handing over the audio piece by piece as it's made so playback never waits for the end. False = no voice.
     */
    @Synchronized fun synthFlow(c: Context, text0: String, speed: Float, keepGoing: () -> Boolean, out: (FloatArray, Int) -> Unit): Boolean {
        val (m, sid) = current(c) ?: return false
        val text = speakable(text0).ifBlank { return true }
        if (Voicebank.fits(text)) { val a = synth(c, text, speed) ?: return false; out(a.first, a.second); return true }
        // one sentence at a time, each played the moment it's ready, joined with the voice's own natural spacing.
        // (Not the engine's streaming callback: the app's build tools reshape that callback and the engine then
        // crashes the whole app looking for it. That was the crash in builds 80 to 116.)
        return try {
            for (part in sentences(text)) {
                if (!keepGoing()) break
                val a = synth(c, part, speed) ?: return false
                out(a.first, a.second)
            }
            true
        } catch (e: Throwable) { Problems.log(c, "voice: ${e.message}"); false }
    }

    /** Splits text into sentences the way the voice engine does (keeping "Mr." and "St." together). Pure, unit-tested. */
    fun sentences(text: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        val words = text.trim().split(Regex("\\s+"))
        for ((i, w) in words.withIndex()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
            val end = Regex("[.!?][\"')\u201d]*$").containsMatchIn(w) && !Regex("(?i)^(mr|mrs|ms|dr|st|vs|jr|sr|e\\.g|i\\.e)\\.$").matches(w)
            if (end && i < words.size - 1) { out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    /** Only if it's already in the phrasebook (never makes him wait). */
    fun cached(c: Context, text0: String, speed: Float): Pair<FloatArray, Int>? {
        val (m, sid) = current(c) ?: return null
        val text = speakable(text0).ifBlank { return null }
        return Voicebank.get(c, m.dir + "_" + sid + (if (sid == BLEND_SLOT) "_" + blendKey.hashCode().toUInt().toString(16) else ""), text, speed)
    }

    /** Load the voice ahead of time, and run it once, so the first words of a reply come out without a pause. */
    @Volatile private var primedFor: Any? = null
    @Synchronized fun warm(c: Context) {
        try {
            val (m, sid) = current(c) ?: return
            val e = engine(c, m) ?: return
            if (primedFor !== e) { e.generate("Hm.", sid, 1f); primedFor = e }
        } catch (_: Throwable) {}
    }
}
