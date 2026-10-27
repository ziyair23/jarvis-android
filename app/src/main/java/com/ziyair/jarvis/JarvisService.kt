package com.ziyair.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
    private var carWatchOn = false
    private var listenUntil = 0L
    private var retries = 0
    private var chatted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        brain = Brain(this)
        restoreSound()
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
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (!goForeground()) {
            Scheduler.notify(this, "Jarvis stopped", "Android paused him in the background. Tap to open Jarvis and start him again.", false)
            prefs.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_LISTEN -> { if (!busy) onWake(manual = true); return START_STICKY }
            ACTION_SAY -> {
                val moment = intent.getStringExtra("moment")
                val text = intent.getStringExtra("text")
                val thenListen = intent.getBooleanExtra("listen", false)
                thread {
                    val line = moment?.let { Scheduler.momentLine(this, it) } ?: text.orEmpty()
                    if (line.isNotBlank()) main.post { sayFirst(line, thenListen) }
                }
                return START_STICKY
            }
        }
        prefs.running = true
        Scheduler.rearm(this)
        if (!carWatchOn) { carWatchOn = true; getSystemService(AudioManager::class.java).registerAudioDeviceCallback(carWatch, main) }
        startWakeWord()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try { showForeground(); true } catch (_: Exception) { false }

    private fun showForeground() {
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
    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    private fun onWake(manual: Boolean = false) {
        if (busy) return
        if (!manual && inCall()) { startWakeWord(); return }
        busy = true
        followUps = 0
        chatted = false
        acquire()
        stopWakeWord()
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {}
        openWindow(brain.config().optInt("wake_seconds", 5))
        main.postDelayed({ listen(firstTurn = true) }, 350)
    }

    /** How long he keeps listening without hearing anything before going back to waiting for "Hey Jarvis". */
    private fun openWindow(seconds: Int) { listenUntil = System.currentTimeMillis() + seconds * 1000L }
    private fun convoSeconds() = brain.config().optInt("conversation_seconds", 3)

    /** Mutes the phone's own "mic on / mic off" beeps while Jarvis listens; always undone before he speaks. */
    private var muted = false
    private val quietStreams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private fun quiet(on: Boolean) {
        if (on == muted) return
        muted = on
        val am = getSystemService(AudioManager::class.java)
        if (on) {
            val did = quietStreams.filter { st -> !am.isStreamMute(st) && try { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); true } catch (_: Exception) { false } }
            prefs.sp.edit().putString("muted_streams", did.joinToString(",")).apply()
        } else restoreSound()
    }
    private fun restoreSound() {
        val am = getSystemService(AudioManager::class.java)
        val list = prefs.sp.getString("muted_streams", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
        for (st in list) try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        prefs.sp.edit().putString("muted_streams", "").apply()
        muted = false
    }

    private fun listen(firstTurn: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Speech recognition isn't available on this phone, sir.") { finishTurn() }; return }
        try { recognizer?.destroy() } catch (_: Exception) {}
        quiet(true)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                retries = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isBlank()) noSpeech(firstTurn) else handle(text)
            }
            override fun onError(error: Int) {
                // the recognizer wasn't ready yet: try again shortly instead of giving up
                if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && retries < 4) {
                    retries++
                    main.postDelayed({ listen(firstTurn) }, 400L * retries)
                    return
                }
                retries = 0
                noSpeech(firstTurn)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { openWindow(convoSeconds()) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) { openWindow(convoSeconds()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { recognizer?.startListening(intent) } catch (e: Exception) { noSpeech(firstTurn) }
    }

    /** Heard nothing this round. Keep the conversation open until the window runs out. */
    private fun noSpeech(firstTurn: Boolean) {
        if (System.currentTimeMillis() < listenUntil) {
            main.postDelayed({ listen(firstTurn) }, 300)
            return
        }
        if (firstTurn && followUps == 0) {
            // he said "Jarvis" then paused: answer like the films and give him one more go
            followUps = 1
            speak(pick("greetings", "Sir?")) { openWindow(convoSeconds()); afterSpeaking { listen(firstTurn = false) } }
        } else finishTurn()
    }

    /** A short pause after Jarvis talks so the mic doesn't hear the end of his own voice. */
    private fun afterSpeaking(fn: () -> Unit) { main.postDelayed(fn, 350) }

    private fun handle(text: String) {
        val lower = text.lowercase(Locale.US)
        if (Regex("^(stop|never ?mind|that'?s all|that'?s it|thanks?,? (that'?s|thats) (it|all)|go to sleep|nothing|no thanks?|i'?m good)\\b").containsMatchIn(lower)) {
            speak(pick("goodbyes", "Very good, sir.")) { finishTurn() }
            return
        }
        quiet(false)
        acquire()
        try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 80) } catch (_: Exception) {}
        chatted = true
        thread {
            val reply = brain.ask(text)
            main.post {
                LogBus.changed()
                speak(reply) { openWindow(convoSeconds()); afterSpeaking { listen(firstTurn = false) } }
            }
        }
    }

    /** Jarvis speaks up on his own (heads-ups, timers, reminders), then goes back to listening. */
    private fun sayFirst(line: String, thenListen: Boolean = false) {
        note(line)
        if (inCall() || PhoneControl.quietOn(this)) { Scheduler.notify(this, "Jarvis", line, false); if (!prefs.running && !busy) stopEverything(); return }
        if (busy) return
        busy = true
        followUps = 1
        acquire()
        stopWakeWord()
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) } catch (_: Exception) {}
        main.postDelayed({ speak(line) { if (thenListen) { openWindow(convoSeconds()); afterSpeaking { listen(firstTurn = false) } } else finishTurn() } }, 300)
    }

    /** Driving mode follows the car's Bluetooth (matched by name, no extra permission needed). */
    private val carWatch = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { checkCar() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { checkCar() }
    }

    private fun checkCar() {
        val car = prefs.carName
        if (car.isBlank()) return
        val am = getSystemService(AudioManager::class.java)
        val inCar = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                it.productName?.toString()?.contains(car, ignoreCase = true) == true
        }
        if (inCar != DriveMode.on) {
            DriveMode.on = inCar
            sayFirst(if (inCar) "Driving mode, sir. I'll keep it short and read your messages out." else "Driving mode off, sir.")
        }
    }

    private fun finishTurn() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        main.postDelayed({ if (recognizer == null) quiet(false) }, 400)
        if (chatted) { chatted = false; thread { Memory.learn(this) } }
        busy = false
        release()
        LogBus.changed()
        if (prefs.running) startWakeWord() else stopEverything()
    }

    private var player: android.media.MediaPlayer? = null

    /** Speak with the realistic Google voice when a key is set, otherwise the phone's own voice. */
    private fun speak(text: String, then: () -> Unit) {
        quiet(false)
        LockView.showIfLocked(this, text)
        val key = prefs.ttsKey
        if (key.isBlank()) { phoneSpeak(text, then); return }
        thread {
            val f = CloudVoice.synth(this, key, text, brain.config())
            main.post { if (f == null || !playFile(f, then)) phoneSpeak(text, then) }
        }
    }

    private fun playFile(f: java.io.File, then: () -> Unit): Boolean = try {
        player?.release()
        val mp = android.media.MediaPlayer()
        mp.setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setDataSource(f.absolutePath)
        mp.setOnCompletionListener { it.release(); if (player === it) player = null; main.post(then) }
        mp.setOnErrorListener { p, _, _ -> p.release(); if (player === p) player = null; main.post(then); true }
        mp.prepare()
        mp.start()
        player = mp
        true
    } catch (_: Exception) { false }

    private fun phoneSpeak(text: String, then: () -> Unit) {
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
            val voices = t.voices?.filter { it.locale.language == "en" } ?: emptyList()
            val male = Regex("rjs|gbd|gbb|male|daniel|arthur|george|oliver", RegexOption.IGNORE_CASE)
            fun gbMale(v: android.speech.tts.Voice) = v.locale.country == "GB" && male.containsMatchIn(v.name) && !v.name.contains("female", true)
            val best = voices.filter { gbMale(it) }.maxByOrNull { (if (it.isNetworkConnectionRequired) 100 else 0) + it.quality }
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
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:turn").also { it.setReferenceCounted(false) }
        try { wakeLock?.acquire(60_000L) } catch (_: Exception) {}
    }

    private fun release() { try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {} }

    private fun stopEverything() {
        prefs.running = false
        busy = false
        try { tts?.stop() } catch (_: Exception) {}
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        restoreSound()
        try { wakeWord?.close() } catch (_: Exception) {}
        wakeWord = null
        recognizer?.destroy(); recognizer = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogBus.changed()
    }

    override fun onDestroy() {
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(carWatch) } catch (_: Exception) {}
        DriveMode.on = false
        quiet(false)
        try { wakeWord?.close() } catch (_: Exception) {}
        recognizer?.destroy()
        tts?.shutdown()
        player?.release()
        tone?.release()
        release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "jarvis"
        const val NOTIF_ID = 7
        const val ACTION_STOP = "com.ziyair.jarvis.STOP"
        const val ACTION_LISTEN = "com.ziyair.jarvis.LISTEN"
        const val ACTION_SAY = "com.ziyair.jarvis.SAY"
    }
}

/** Tiny signal so the screen refreshes the conversation when the service adds to it. */
object LogBus {
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners.add(fn) }
    fun unlisten(fn: () -> Unit) { listeners.remove(fn) }
    fun changed() { listeners.toList().forEach { it() } }
}
