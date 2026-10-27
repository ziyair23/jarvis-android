package com.ziyair.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Runs in the background with a small notification so Android lets it keep the mic.
 * Loop: listen for "Hey Jarvis" (openWakeWord, on-device) -> chime -> hear the request (Android speech)
 * -> ask Claude -> speak the answer -> listen a few seconds for a follow-up -> back to waiting for "Jarvis".
 */
class JarvisService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var brain: Brain
    private var wakeWord: WakeWord? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var busy = false
    private var followUps = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        brain = Brain(this)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (_: Exception) { null }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                pickVoice()
            }
        }
        thread { brain.refreshConfig(); Updater.check(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_LISTEN -> { goForeground(); if (!busy) onWake() ; return START_STICKY }
        }
        goForeground()
        prefs.running = true
        startWakeWord()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getService(this, 2, Intent(this, JarvisService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Hey Jarvis\" any time, sir.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun startWakeWord() {
        main.post {
            if (busy) return@post
            try {
                if (wakeWord == null) {
                    val th = brain.config().optDouble("wake_threshold", 0.5).toFloat()
                    wakeWord = WakeWord(applicationContext, th) { main.post { onWake() } }
                }
                wakeWord?.start()
            } catch (e: Exception) {
                wakeWord = null
                note("I couldn't start listening for my name: ${e.message}")
            }
        }
    }

    private fun stopWakeWord() {
        try { wakeWord?.stop() } catch (_: Exception) {}
    }

    /** Heard "Jarvis" (or the Talk button): chime, then listen for the request. */
    private fun onWake() {
        if (busy) return
        busy = true
        followUps = 0
        acquire()
        stopWakeWord()
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {}
        main.postDelayed({ listen(firstTurn = true) }, 350)
    }

    private fun listen(firstTurn: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Speech recognition isn't available on this phone, sir.") { finishTurn() }; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val secs = brain.config().optInt("follow_up_seconds", 7)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, if (firstTurn) 2000L else secs * 1000L)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isBlank()) noSpeech(firstTurn) else handle(text)
            }
            override fun onError(error: Int) { noSpeech(firstTurn) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { recognizer?.startListening(intent) } catch (e: Exception) { noSpeech(firstTurn) }
    }

    private fun noSpeech(firstTurn: Boolean) {
        if (firstTurn) {
            // he said "Jarvis" then paused: answer like the films and give him one more go
            if (followUps == 0) {
                followUps = 1
                speak(pick("greetings", "Sir?")) { listen(firstTurn = false) }
            } else finishTurn()
        } else finishTurn()
    }

    private fun handle(text: String) {
        val lower = text.lowercase(Locale.US)
        if (Regex("^(stop|never ?mind|that'?s all|thanks?,? (that'?s|thats) (it|all)|go to sleep|nothing)\\b").containsMatchIn(lower)) {
            speak(pick("goodbyes", "Very good, sir.")) { finishTurn() }
            return
        }
        try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 80) } catch (_: Exception) {}
        thread {
            val reply = brain.ask(text)
            main.post {
                LogBus.changed()
                speak(reply) { listen(firstTurn = false) }
            }
        }
    }

    private fun finishTurn() {
        recognizer?.destroy(); recognizer = null
        busy = false
        release()
        LogBus.changed()
        startWakeWord()
    }

    private fun speak(text: String, then: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { main.postDelayed(then, 300); return }
        val cfg = brain.config()
        t.setSpeechRate(cfg.optDouble("voice_rate", 0.95).toFloat())
        t.setPitch(cfg.optDouble("voice_pitch", 0.88).toFloat())
        val id = "j" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) main.post(then) }
            @Deprecated("old api") override fun onError(utteranceId: String?) { if (utteranceId == id) main.post(then) }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** A British male voice when the phone has one; otherwise the phone's UK English voice. */
    private fun pickVoice() {
        val t = tts ?: return
        try {
            val voices = t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired } ?: emptyList()
            val male = Regex("rjs|gbd|gbb|male|daniel|arthur|george|oliver", RegexOption.IGNORE_CASE)
            val best = voices.firstOrNull { it.locale.country == "GB" && male.containsMatchIn(it.name) && !it.name.contains("female", true) }
                ?: voices.firstOrNull { it.locale.country == "GB" }
            if (best != null) t.voice = best else t.language = Locale.UK
        } catch (_: Exception) { t.language = Locale.UK }
    }

    private fun pick(key: String, fallback: String): String {
        val arr = brain.config().optJSONArray(key) ?: return fallback
        return if (arr.length() == 0) fallback else arr.optString((0 until arr.length()).random(), fallback)
    }

    private fun note(msg: String) {
        prefs.addTurn("assistant", msg)
        LogBus.changed()
    }

    private fun acquire() {
        if (wakeLock == null) wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:turn")
        try { wakeLock?.acquire(60_000L) } catch (_: Exception) {}
    }

    private fun release() { try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {} }

    private fun stopEverything() {
        prefs.running = false
        try { wakeWord?.close() } catch (_: Exception) {}
        wakeWord = null
        recognizer?.destroy(); recognizer = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogBus.changed()
    }

    override fun onDestroy() {
        try { wakeWord?.close() } catch (_: Exception) {}
        recognizer?.destroy()
        tts?.shutdown()
        tone?.release()
        release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "jarvis"
        const val NOTIF_ID = 7
        const val ACTION_STOP = "com.ziyair.jarvis.STOP"
        const val ACTION_LISTEN = "com.ziyair.jarvis.LISTEN"
    }
}

/** Tiny signal so the screen refreshes the conversation when the service adds to it. */
object LogBus {
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners.add(fn) }
    fun unlisten(fn: () -> Unit) { listeners.remove(fn) }
    fun changed() { listeners.toList().forEach { it() } }
}
