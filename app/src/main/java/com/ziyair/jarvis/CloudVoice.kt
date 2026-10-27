package com.ziyair.jarvis

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Realistic voice from Google Cloud Text-to-Speech (Chirp 3 HD voices).
 * Google gives 1 million characters a month free for these voices; Jarvis's replies are short, so normal use stays free.
 */
object CloudVoice {
    /** Returns an MP3 file with the spoken text, or null if anything goes wrong (Jarvis then uses the phone's voice). */
    fun synth(context: Context, key: String, text: String, cfg: JSONObject): File? = try {
        val voice = Prefs(context).voiceName.ifBlank { cfg.optString("cloud_voice", "en-GB-Chirp3-HD-Algenib") }
        val lang = voice.split("-").take(2).joinToString("-")
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", lang).put("name", voice))
            .put("audioConfig", JSONObject().put("audioEncoding", "MP3").put("speakingRate", cfg.optDouble("cloud_rate", 1.0)))
        val c = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=" + key.trim()).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) { lastError = "Google voice error $code"; null } else {
            val audio = Base64.decode(JSONObject(resp).getString("audioContent"), Base64.DEFAULT)
            val f = File(context.cacheDir, "jarvis-voice.mp3")
            f.writeBytes(audio); lastError = ""; f
        }
    } catch (e: Exception) { lastError = "Google voice unreachable"; null }

    @Volatile var lastError = ""
}
