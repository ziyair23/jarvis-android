package com.ziyair.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Live talking: a real two-way voice conversation with Gemini Live. It hears him and speaks back directly (no
 * speech-to-text, no stitched voice), so it sounds natural and he can cut in any time, like a phone call.
 * Same personality, memory and phone tools as the rest of Jarvis. Uses the phone's call-style echo cancelling only
 * while a conversation is on, and always puts the phone's audio back to normal afterwards.
 */
class Live(
    private val context: Context,
    private val main: Handler,
    private val isStop: (String) -> Boolean,
    private val onEnd: (reason: String, nothingSaid: Boolean) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var ended = false
    private var ws: WebSocket? = null
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val audioQ = LinkedBlockingQueue<ShortArray>()
    private val am = context.getSystemService(AudioManager::class.java)
    private val cfg = Brain(context).config()

    @Volatile private var spoke = false            // any audio from him back yet
    @Volatile private var lastAudioAt = 0L
    @Volatile private var lastUserLoudAt = 0L
    @Volatile private var lastHeardAt = 0L
    @Volatile private var turnDoneAt = 0L
    @Volatile private var endAfterTurn = false
    @Volatile private var toolsRunning = 0
    @Volatile private var micSamples = 0L
    @Volatile private var outSamples = 0L
    private var orbState = -1
    private val startedAt = System.currentTimeMillis()
    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()
    val tools = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    private var model = "gemini-3.8-live"

    fun start(first: String?, modelName: String) {
        model = modelName
        running = true
        val key = Prefs(context).geminiKey
        Trace.log(context, "live", "connecting ($model)")
        callAudio(true)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup().toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text, first) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8(), first) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!ended) { Trace.log(context, "live", "server closed: $code ${reason.take(160)}"); if (code != 1000) Problems.log(context, "live talking closed: $code ${reason.take(120)}") }
                stop(if (code == 1000) "closed" else "error $code ${reason.take(120)}")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = "${response?.code ?: ""} ${t.message ?: ""}".trim()
                if (!ended) Problems.log(context, "live talking failed: $why")
                stop("failed: $why")
            }
        })
        // the watchdog for this conversation: ends it when he's done
        main.postDelayed(object : Runnable {
            override fun run() {
                if (ended) return
                val now = System.currentTimeMillis()
                val idleMs = cfg.optInt("live_idle_seconds", 12) * 1000L
                val quietSince = maxOf(lastHeardAt, turnDoneAt, lastAudioAt, startedAt)
                val playing = audioQ.isNotEmpty() || now - lastAudioAt < 700
                val talkingNow = now - lastUserLoudAt < 1500
                val st = if (playing) Orb.SPEAK else if (toolsRunning > 0) Orb.THINK else Orb.LISTEN
                if (st != orbState) { orbState = st; Orb.set(context, st) }
                when {
                    endAfterTurn && turnDoneAt > 0 && !playing && toolsRunning == 0 -> { stop("music"); return }
                    !playing && !talkingNow && toolsRunning == 0 && now - quietSince > idleMs -> { stop("idle"); return }
                    now - startedAt > cfg.optInt("live_max_minutes", 10) * 60_000L -> { stop("max length"); return }
                    !spoke && tools.isEmpty() && toolsRunning == 0 && now - startedAt > 15_000 -> { stop("no answer"); return }
                }
                main.postDelayed(this, 300)
            }
        }, 300)
    }

    private fun setup(): JSONObject {
        val voice = cfg.optString("live_voice", "Algenib")
        val sys = Brain(context).livePrompt(cfg)
        val decls = JSONArray()
        val claudeTools = Agents.jarvisTools(context, Actions(context).tools())
        val skip = setOf("look")
        for (i in 0 until claudeTools.length()) {
            val t = claudeTools.getJSONObject(i)
            val name = t.optString("name")
            if (name.isBlank() || name in skip || !t.has("input_schema")) continue
            val d = JSONObject().put("name", name).put("description", t.optString("description"))
            val schema = t.optJSONObject("input_schema")
            if (schema != null && (schema.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", toGemini(schema))
            decls.put(d)
        }
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", decls))
        if (cfg.optBoolean("web_search", true)) tools.put(JSONObject().put("googleSearch", JSONObject()))
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("tools", tools)
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("silenceDurationMs", cfg.optInt("live_silence_ms", 800))
                .put("prefixPaddingMs", 200)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject())))
    }

    /** Claude's JSON-schema tool parameters, in the shape Gemini wants (type names in capitals). */
    private fun toGemini(s: JSONObject): JSONObject {
        val out = JSONObject()
        s.optString("type").takeIf { it.isNotBlank() }?.let { out.put("type", it.uppercase()) }
        s.optString("description").takeIf { it.isNotBlank() }?.let { out.put("description", it) }
        s.optJSONArray("enum")?.let { out.put("enum", it) }
        s.optJSONObject("properties")?.let { p ->
            val o = JSONObject()
            for (k in p.keys()) o.put(k, toGemini(p.getJSONObject(k)))
            out.put("properties", o)
        }
        s.optJSONObject("items")?.let { out.put("items", toGemini(it)) }
        s.optJSONArray("required")?.let { if (it.length() > 0) out.put("required", it) }
        return out
    }

    private fun handle(text: String, first: String?) {
        if (ended) return
        val j = try { JSONObject(text) } catch (_: Exception) { return }
        if (j.has("setupComplete")) {
            Trace.log(context, "live", "connected")
            startMic(); startPlayer()
            if (!first.isNullOrBlank()) send(JSONObject().put("realtimeInput", JSONObject().put("text", first)))
            if (!first.isNullOrBlank()) { userBuf.append(first); lastHeardAt = System.currentTimeMillis() }
        }
        j.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                    if (data.isBlank()) continue
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    val pcm = ShortArray(bytes.size / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    if (!spoke) Trace.log(context, "live", "first sound back")
                    spoke = true
                    outSamples += pcm.size
                    audioQ.put(pcm)
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
                userBuf.append(t); lastHeardAt = System.currentTimeMillis()
                val said = userBuf.toString().trim()
                // the stop word, once he's finished saying it ("stop the music" must not end the chat at "stop")
                if (isStop(said)) main.postDelayed({ if (userBuf.toString().trim() == said) { Trace.log(context, "stop", "\"$said\""); stop("stop word") } }, 800)
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { modelBuf.append(it) }
            if (sc.optBoolean("interrupted")) {
                Trace.log(context, "live", "he cut in")
                audioQ.clear()
                try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
                track?.let { t -> Orb.voiceStart(OUT_RATE) { t.playbackHeadPosition.toLong() } }
            }
            if (sc.optBoolean("turnComplete")) {
                turnDoneAt = System.currentTimeMillis()
                val u = userBuf.toString().trim(); val m = modelBuf.toString().trim()
                if (u.isNotBlank()) Prefs(context).addTurn("user", u)
                if (m.isNotBlank()) Prefs(context).addTurn("assistant", m)
                if (u.isNotBlank() || m.isNotBlank()) Trace.log(context, "live", "turn: \"${u.take(100)}\" -> \"${m.take(140)}\"")
                userBuf.setLength(0); modelBuf.setLength(0)
                LogBus.changed()
            }
        }
        j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val name = c.optString("name"); val id = c.optString("id")
                val args = c.optJSONObject("args") ?: JSONObject()
                tools.add(name)
                Trace.log(context, "tool", "$name $args")
                if (name == "play_music" || (name == "media_control" && args.optString("action") in setOf("play", "next", "previous"))) endAfterTurn = true
                toolsRunning++
                val started = System.currentTimeMillis()
                thread {
                    val out = try { Actions(context).run(name, args) } catch (e: Exception) { "That didn't work: ${e.message}" } finally { toolsRunning-- }
                    Trace.log(context, "tool", "$name -> ${out.take(160)}")
                    if (Agents.usedSince("play_music", started)) endAfterTurn = true
                    send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                        JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", out))))))
                }
            }
        }
        if (j.has("goAway")) { Trace.log(context, "live", "server asked to wrap up"); main.post { stop("time") } }
    }

    private fun send(o: JSONObject) { if (!ended) try { ws?.send(o.toString()) } catch (_: Exception) {} }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        thread(name = "jarvis-live-mic") {
            val rate = IN_RATE
            val frame = rate / 10   // 100 ms
            val r = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 2 * 4))
            } catch (e: Exception) { main.post { stop("mic: ${e.message}") }; return@thread }
            rec = r
            val fx = ArrayList<android.media.audiofx.AudioEffect>()
            try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            val buf = ShortArray(frame)
            val bytes = ByteBuffer.allocate(frame * 2).order(ByteOrder.LITTLE_ENDIAN)
            try {
                r.startRecording()
                while (running) {
                    var got = 0
                    while (got < frame && running) { val n = r.read(buf, got, frame - got); if (n <= 0) break; got += n }
                    if (!running || got <= 0) continue
                    var sum = 0.0
                    bytes.clear()
                    for (i in 0 until got) { bytes.putShort(buf[i]); val f = buf[i] / 32768.0; sum += f * f }
                    val rms = sqrt(sum / got).toFloat()
                    if (rms > 0.02f) lastUserLoudAt = System.currentTimeMillis()
                    if (audioQ.isEmpty()) Orb.level(rms * 14f)
                    micSamples += got
                    val b64 = Base64.encodeToString(bytes.array(), 0, got * 2, Base64.NO_WRAP)
                    send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=$rate"))))
                }
            } catch (_: Exception) {
            } finally {
                try { r.stop() } catch (_: Exception) {}
                fx.forEach { try { it.release() } catch (_: Exception) {} }
                r.release()
            }
        }
    }

    private fun startPlayer() {
        thread(name = "jarvis-live-voice") {
            val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(min, OUT_RATE / 2)).build()
            } catch (e: Exception) { main.post { stop("speaker: ${e.message}") }; return@thread }
            track = t
            t.play()
            Orb.voiceStart(OUT_RATE) { t.playbackHeadPosition.toLong() }
            try {
                while (running) {
                    val pcm = audioQ.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    Orb.voiceAdd(FloatArray(pcm.size) { pcm[it] / 32768f })
                    t.write(pcm, 0, pcm.size)
                    lastAudioAt = System.currentTimeMillis()
                }
            } catch (_: Exception) {
            } finally {
                try { t.stop() } catch (_: Exception) {}
                t.release()
                Orb.voiceEnd()
            }
        }
    }

    /** Call-style audio (proper echo cancelling, so he can talk over Jarvis) only while live; always undone. */
    private fun callAudio(on: Boolean) {
        try {
            if (on) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val devs = am.availableCommunicationDevices
                    // headset or car first, otherwise the loudspeaker (never the earpiece)
                    val pick = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                        ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    pick?.let { am.setCommunicationDevice(it) }
                } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = true }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
                am.mode = AudioManager.MODE_NORMAL
                GlassesMic.forget()
            }
        } catch (e: Exception) { Trace.log(context, "live", "audio mode: ${e.message}") }
    }

    /** End the conversation (any thread). Safe to call more than once. */
    fun stop(reason: String) {
        if (ended) return
        ended = true
        running = false
        // cost from how long each side actually talked (25 audio tokens a second each way, plus a little for context)
        val inTok = (micSamples / IN_RATE.toDouble() * 25 * 1.3).toLong()
        val outTok = (outSamples / OUT_RATE.toDouble() * 25 * 1.1).toLong()
        Spend.recordLive(context, cfg, inTok, outTok)
        Trace.log(context, "live", "ended: $reason (${(System.currentTimeMillis() - startedAt) / 1000}s, tools ${tools.joinToString(",").ifBlank { "none" }})")
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        audioQ.clear()
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        val u = userBuf.toString().trim(); val m = modelBuf.toString().trim()
        if (u.isNotBlank()) Prefs(context).addTurn("user", u)
        if (m.isNotBlank()) Prefs(context).addTurn("assistant", m)
        main.postDelayed({
            callAudio(false)
            try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
            onEnd(reason, !spoke)
        }, 250)
    }

    companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000

        /** Live talking is set up and within his monthly budget. */
        fun ready(context: Context): Boolean {
            val p = Prefs(context)
            val cfg = Brain(context).config()
            if (!cfg.optBoolean("live", true) || p.geminiKey.isBlank()) return false
            if (Spend.liveThisMonth(p) >= cfg.optDouble("live_budget", 15.0)) return false
            return true
        }

        /** Undo call-style audio if anything was left behind (e.g. a crash mid-conversation). */
        fun restoreAudio(context: Context) {
            try {
                val am = context.getSystemService(AudioManager::class.java)
                if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
                    am.mode = AudioManager.MODE_NORMAL
                }
            } catch (_: Exception) {}
        }
    }
}
