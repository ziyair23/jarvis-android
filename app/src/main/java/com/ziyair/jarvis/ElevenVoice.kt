package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Jarvis's voice from ElevenLabs. The key and voice ID are typed into setup on the phone.
 * Uses the Flash model (fastest, and half the credits). The free plan is about 10,000 credits a month, so when
 * it runs out (or there's no signal) Jarvis quietly falls back to the Google voice or his own on-phone voice
 * until the next day.
 */
object ElevenVoice {
    /** "George": British, warm, mid-aged storyteller. Closest stock voice to the Wit sound he likes. */
    const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    @Volatile var lastError = ""

    fun usable(p: Prefs): Boolean = p.elevenKey.isNotBlank() && p.sp.getLong("eleven_off_until", 0L) < System.currentTimeMillis()

    /** Out of credits, bad key, or rate limited: step aside until tomorrow morning (or a fresh key is saved). */
    fun benchUntilTomorrow(p: Prefs) {
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 5); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); if (!after(now)) add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        p.sp.edit().putLong("eleven_off_until", t).apply()
    }
    fun unbench(p: Prefs) = p.sp.edit().remove("eleven_off_until").apply()

    /** True when ElevenLabs says the account itself is the problem (quota, bad key), not the network. Pure. */
    fun accountProblem(code: Int, body: String): Boolean =
        code == 401 || code == 402 || code == 403 || Regex("(?i)quota_exceeded|insufficient|credits|invalid_api_key").containsMatchIn(body)

    /** One sentence as raw audio (24 kHz), for speaking sentence by sentence while the rest of the answer is still coming. */
    fun pcm(context: Context, text: String): Pair<FloatArray, Int>? {
        val p = Prefs(context)
        if (!usable(p)) return null
        return try {
            val voice = p.elevenVoice.ifBlank { DEFAULT_VOICE }
            val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
                .put("voice_settings", JSONObject().put("stability", 0.45).put("similarity_boost", 0.8).put("style", 0.15).put("use_speaker_boost", true))
            val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=pcm_24000&optimize_streaming_latency=3").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 12000
            c.setRequestProperty("xi-api-key", p.elevenKey); c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(); c.disconnect()
                lastError = "ElevenLabs error $code"
                if (accountProblem(code, err)) { benchUntilTomorrow(p); Problems.log(context, "ElevenLabs $code: ${err.take(160)}; using the backup voice until tomorrow") }
                return null
            }
            val bytes = c.inputStream.use { it.readBytes() }; c.disconnect()
            lastError = ""; if (bytes.size > 400) Pcm.floats(bytes) to 24000 else null
        } catch (e: Exception) { lastError = "ElevenLabs unreachable"; null }
    }

    fun synth(context: Context, text: String): File? {
        val p = Prefs(context)
        if (!usable(p)) return null
        return try {
            val voice = p.elevenVoice.ifBlank { DEFAULT_VOICE }
            val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
                .put("voice_settings", JSONObject().put("stability", 0.45).put("similarity_boost", 0.8).put("style", 0.15).put("use_speaker_boost", true))
            val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 6000; c.readTimeout = 15000
            c.setRequestProperty("xi-api-key", p.elevenKey); c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("accept", "audio/mpeg")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(); c.disconnect()
                lastError = "ElevenLabs error $code"
                if (accountProblem(code, err)) { benchUntilTomorrow(p); Problems.log(context, "ElevenLabs $code: ${err.take(160)}; using the backup voice until tomorrow") }
                return null
            }
            val f = File(context.cacheDir, "jarvis-eleven.mp3")
            c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }; c.disconnect()
            lastError = ""; if (f.length() > 200) f else null
        } catch (e: Exception) { lastError = "ElevenLabs unreachable"; null }
    }
}

/** Little-endian 16-bit mono PCM to floats in -1..1 (skipping a WAV header if there is one). Pure. */
object Pcm {
    fun floats(b: ByteArray): FloatArray {
        val start = if (b.size > 44 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()) 44 else 0
        val n = (b.size - start) / 2
        return FloatArray(n) { i -> ((b[start + 2 * i].toInt() and 0xff) or (b[start + 2 * i + 1].toInt() shl 8)).toShort() / 32768f }
    }
}

/** Opens the connection to his cloud voice ahead of time (TLS takes a few hundred ms), so the first sentence comes back faster. */
object Voices {
    fun warm(context: Context) {
        val p = Prefs(context)
        val url = if (ElevenVoice.usable(p)) "https://api.elevenlabs.io/v1/models" else if (p.ttsKey.isNotBlank()) "https://texttospeech.googleapis.com/" else return
        try { (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 3000; if (url.contains("elevenlabs")) setRequestProperty("xi-api-key", p.elevenKey) }.let { it.responseCode; it.inputStream.use { s -> s.readBytes() } } } catch (_: Exception) {}
    }
}
