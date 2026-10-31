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
        focus = { on -> val l = java.util.concurrent.CountDownLatch(1); main.post { holdFocus(on); l.countDown() }; l.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        restoreSound()
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (_: Exception) { null }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                pickVoice()
            }
        }
        thread { brain.refreshConfig(); Updater.check(this, busy = busy) }
        Cast.discover(this)
        // undo any call mode left over from older builds
        try { val am = getSystemService(AudioManager::class.java); if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        // watchdog: if his ears ever stop (mic grabbed by another app, an error), quietly start them again
        main.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (prefs.running && !busy && !earOn() && wakeWord?.alive() != true && !inCall()) { Problems.log(this@JarvisService, "ears restarted"); startWakeWord() }
                    // phone ears ready but not in use (after a failure or the download just finishing): switch over when idle
                    if (prefs.running && !busy && !earOn() && Ear.ready(this@JarvisService) && !inCall()) startWakeWord()
                    if (Stealth.expired(prefs)) startService(Intent(this@JarvisService, JarvisService::class.java).setAction(ACTION_QUIET_END))
                } catch (_: Exception) {}
                main.postDelayed(this, 30_000)
            }
        }, 30_000)
        // Jarvis's own voice: fetch it once on Wi-Fi, then keep it warm
        thread {
            val metered = try { getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered } catch (_: Exception) { true }
            if (!Piper.installed(this) && !metered) Piper.download(this)
            // his chosen voice (e.g. full-quality George) downloads on Wi-Fi; Alan covers until it's ready
            if (Piper.installed(this) && !Piper.pickReady(this) && !metered) Piper.download(this)
            Piper.warm(this)
            prepareLines()
            if (!Ear.ready(this) && !metered) Ear.download(this)
            if (Ear.ready(this)) Ear.recognizer(this)   // load it now so the first request isn't slow
        }
        // whenever he unlocks the phone, see if there's a new build to slip in (checks GitHub at most hourly)
        registerReceiver(unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
        registerReceiver(batteryWatch, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try { getSystemService(android.net.ConnectivityManager::class.java).registerNetworkCallback(
            android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifiWatch) } catch (_: Exception) {}
    }

    /** "Battery's at 15 percent, sir." Once at 15 and once at 5, reset when he plugs in. */
    private var warnedAt = 101
    private val batteryWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100))
            if (i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0) { warnedAt = 101; return }
            if (!prefs.running || lvl < 0) return
            val mark = when { lvl <= 5 -> 5; lvl <= 15 -> 15; else -> return }
            if (mark >= warnedAt) return
            warnedAt = mark
            sayFirst(if (mark == 5) "Battery's at $lvl percent, sir. I won't last much longer." else "Battery's at $lvl percent, sir. Might want to plug in.")
        }
    }

    /** Home Wi-Fi after being out for a while: "Welcome home, sir," and the lights come on. */
    private val wifiWatch = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onLost(network: android.net.Network) {
            // only leaving HOME counts (other Wi-Fi along the way doesn't)
            if (prefs.sp.getBoolean("at_home", false) && prefs.sp.getString("home_net_seen", "") == network.toString())
                prefs.sp.edit().putBoolean("at_home", false).putLong("left_home_at", System.currentTimeMillis()).apply()
        }
        override fun onAvailable(network: android.net.Network) {
            // give the network a moment to get its address before checking it
            main.postDelayed({ thread { arrived(network) } }, 2500)
        }
    }

    private fun arrived(network: android.net.Network) {
        val home = HomeNet.saved(prefs).isNotBlank()
        val isHome = if (home) HomeNet.isHome(this, network) else true   // not set up yet: any Wi-Fi after a while away
        if (!isHome) return
        prefs.sp.edit().putString("home_net_seen", network.toString()).apply()
        val wasHome = prefs.sp.getBoolean("at_home", false)
        val left = prefs.sp.getLong("left_home_at", 0L)
        prefs.sp.edit().putBoolean("at_home", true).apply()
        if (wasHome || left == 0L || System.currentTimeMillis() - left < 10 * 60_000L || !prefs.running) return
        prefs.sp.edit().putLong("left_home_at", 0L).apply()
        val (line, ask) = HomeNet.welcome(this)
        main.post { sayFirst(line, thenListen = ask) }
    }

    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (!busy) thread { Updater.check(this@JarvisService, busy = busy) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (intent?.action == ACTION_QUIET_END) {
            if (goForeground()) thread {
                val notes = Stealth.end(this)
                val line = brain.recap(notes)
                main.post { sayFirst(line) }
            }
            return START_STICKY
        }
        if (!goForeground()) {
            Scheduler.notify(this, "Jarvis stopped", "Android paused him in the background. Tap to open Jarvis and start him again.", false)
            prefs.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_LISTEN -> { if (!busy) onWake(manual = true); return START_STICKY }
            ACTION_HUSH -> {
                main.post {
                    if (!busy) { Orb.set(this, Orb.HIDDEN); return@post }
                    turnId++
                    try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
                    recognizer = null
                    inConvo = false; pending.clear()
                    hush(); quiet(false)
                    Sfx.play("off")
                    finishTurn()
                }
                return START_STICKY
            }
            ACTION_SAY -> {
                val moment = intent.getStringExtra("moment")
                val text = intent.getStringExtra("text")
                val thenListen = intent.getBooleanExtra("listen", false)
                val ringing = intent.getBooleanExtra("ringing", false)
                thread {
                    val line = moment?.let { Scheduler.momentLine(this, it) } ?: text.orEmpty()
                    if (line.isNotBlank()) main.post { sayFirst(line, thenListen, ringing) }
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, DashActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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

    private fun startWakeWord(whileSpeaking: Boolean = false) {
        main.post {
            if (busy && !whileSpeaking) return@post
            if (whileSpeaking && (!speaking || !brain.config().optBoolean("interrupt_by_name", true))) return@post
            try {
                if (wakeWord == null) {
                    val th = brain.config().optDouble("wake_threshold", 0.5).toFloat()
                    wakeWord = WakeWord(applicationContext, th) { main.post { if (speaking) interrupt() else onWake() } }
                }
                wakeWord?.musicFactor = brain.config().optDouble("music_wake_factor", 0.7).toFloat()
                // the one-ear stream: the wake word just listens to it, no separate mic
                if (ensureEar()) { ear?.set(if (whileSpeaking) Ear.Mode.SPEAKING else Ear.Mode.SLEEP); return@post }
                wakeWord?.sensitive(!whileSpeaking)
                wakeWord?.start()
            } catch (e: Exception) {
                wakeWord = null
                note("I couldn't start listening for my name: ${e.message}")
            }
        }
    }

    private fun stopWakeWord() {
        ear?.let { if (it.alive()) { it.set(Ear.Mode.MUTE); return } }
        try { wakeWord?.stop() } catch (_: Exception) {}
    }

    // ---------------- the one ear ----------------
    private var ear: Ear? = null
    private var earBrokenAt = 0L
    private var earListening = false
    private var earFirst = false
    private fun earOn() = ear?.alive() == true

    /** Starts the one-ear stream if it's set up (model downloaded, not switched off remotely, not broken recently). */
    private fun ensureEar(): Boolean {
        if (earOn()) return true
        if (!prefs.running || inCall()) return false
        if (System.currentTimeMillis() - earBrokenAt < 10 * 60_000L) return false
        if (brain.config().optString("ears", "phone") != "phone" || !Ear.ready(this)) return false
        if (!Ear.loaded()) { Ear.loadAsync(this); return false }   // switches over once it's loaded
        val w = wakeWord ?: return false
        try { w.stop() } catch (_: Exception) {}
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        val e = Ear(applicationContext, w, main, earEvents)
        ear = e
        if (!e.start()) { ear = null; return false }
        Trace.log(this, "ears", "one-ear stream on")
        return true
    }

    private val earEvents = object : Ear.Events {
        override fun onWake() {
            if (speaking) { interrupt(); return }
            if (busy) return
            this@JarvisService.onWake(fromEar = true)
        }
        override fun onSpeechStart() {
            if (!earListening) return
            openWindow(30)   // he's talking: wait for him to finish, however long
            Trace.log(this@JarvisService, "speech", "he started talking")
        }
        override fun onHeard(text: String, speechMs: Long, decodeMs: Long) {
            Trace.log(this@JarvisService, "heard", "\"$text\" (${speechMs / 1000.0}s of speech, ${decodeMs}ms to read)")
            if (!earListening) return
            earListening = false
            listenSession++
            handle(text, followUp = !earFirst)
        }
        override fun onNoise() {
            if (!earListening) return
            Trace.log(this@JarvisService, "noise", "sound but no words")
            openWindow(convoSeconds().coerceAtMost(5))
            ear?.set(Ear.Mode.LISTEN)
        }
        override fun onEarFailed(why: String) {
            Problems.log(this@JarvisService, "ears failed, using Google listening for now: $why")
            earBrokenAt = System.currentTimeMillis()
            try { ear?.stop() } catch (_: Exception) {}
            ear = null
            val wasListening = earListening
            earListening = false
            if (wasListening && busy) listen(earFirst) else if (!busy) startWakeWord()
        }
    }

    /** Listening for his request on the one-ear stream: no beeps, no mic handoff; it ends when he stops talking. */
    private fun earListen(firstTurn: Boolean) {
        val e = ear ?: return listen(firstTurn)
        earFirst = firstTurn
        earListening = true
        val session = ++listenSession
        Orb.set(this, Orb.LISTEN)
        e.set(Ear.Mode.LISTEN)
        val tick = object : Runnable {
            override fun run() {
                if (!earListening || session != listenSession) return
                if (!earOn()) return
                if (System.currentTimeMillis() > listenUntil && !e.speechStarted) {
                    earListening = false
                    Trace.log(this@JarvisService, "silence", "nothing said, going back to sleep")
                    finishTurn()
                    return
                }
                main.postDelayed(this, 200)
            }
        }
        main.postDelayed(tick, 200)
    }

    /** Heard "Jarvis" (or the Talk button): chime, then listen for the request. */
    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        // a real phone call or ringing only; "communication" mode can be left behind by other apps and made him deaf
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    private fun onWake(manual: Boolean = false, fromEar: Boolean = false) {
        if (busy) return
        if (!manual && inCall()) { startWakeWord(); return }
        Trace.turn(this, if (manual) "talk button" else if (fromEar) "heard his name (one ear)" else "heard his name")
        busy = true
        followUps = 0
        chatted = false
        inConvo = true
        ignoredInRow = 0
        acquire()
        if (!(fromEar && earOn())) stopWakeWord()   // the one ear is already listening for his request: leave it be
        holdFocus(true)
        val cfg = brain.config()
        val wakeSecs = cfg.optInt("wake_seconds", 5)
        openWindow(wakeSecs)
        val stealth = Stealth.on(prefs)
        val speed = cfg.optDouble("piper_speed", 1.0).toFloat()
        // "Yes, sir?" in his own voice, straight from the phrasebook so there's no wait; the chirp if it isn't ready yet
        val line = if (!stealth && cfg.optString("wake_reply", "voice") == "voice" && Piper.installed(this) && prefs.ttsKey.isBlank()) Persona.wakeLine(this, cfg) else null
        val clip = line?.let { Piper.cached(this, it, speed) }
        val go = { openWindow(wakeSecs); listen(firstTurn = true) }
        // one ear: he may have said it all in one breath ("Jarvis, what time is it"); if he's already talking, skip the greeting
        if (fromEar && earOn()) {
            val e = ear!!
            // the ear is already catching what he says next
            earFirst = true
            earListening = true
            listenSession++
            main.postDelayed({
                if (!busy || thinking || !earListening) return@postDelayed
                if (e.speechStarted) { Trace.log(this, "wake", "already talking, no greeting"); openWindow(wakeSecs); earListen(true); return@postDelayed }
                earListening = false
                e.set(Ear.Mode.MUTE)
                if (clip != null && playPcm(clip.first, clip.second) { main.postDelayed({ go() }, 120) }) return@postDelayed
                if (line != null) prepareLines()
                if (!stealth) Sfx.play("wake")
                main.postDelayed({ go() }, Sfx.lengthMs("wake") + 120)
            }, brain.config().optLong("same_breath_ms", 350L))
            Orb.set(this, Orb.LISTEN)
            return
        }
        if (clip != null && playPcm(clip.first, clip.second) { main.postDelayed({ go() }, 120) }) return
        if (line != null) prepareLines()
        Orb.set(this, Orb.LISTEN)
        if (!stealth) Sfx.play("wake")
        main.postDelayed({ go() }, Sfx.lengthMs("wake") + 120)
    }

    /** Records his everyday lines in the current voice, once, in the background. */
    @Volatile private var preparing = false
    private fun prepareLines() {
        if (preparing || !Piper.installed(this) || prefs.ttsKey.isNotBlank()) return
        preparing = true
        thread {
            try { val cfg = brain.config(); Voicebank.prepare(this, Persona.stockLines(cfg), cfg.optDouble("piper_speed", 1.0).toFloat()) }
            finally { preparing = false }
        }
    }

    /** Each request gets a number; if he cancels (tap the orb, "stop", a call), a late answer is thrown away. */
    private var turnId = 0

    /** How long he keeps listening without hearing anything before going back to waiting for "Hey Jarvis". */
    private fun openWindow(seconds: Int) { listenUntil = System.currentTimeMillis() + seconds * 1000L }
    private fun convoSeconds() = brain.config().optInt("conversation_seconds", 8)

    /** In a conversation: after each answer he keeps listening (no wake word, no chime) until he's done. */
    private var inConvo = false
    private var ignoredInRow = 0
    private var speechTok = 0
    private var barge: BargeIn? = null

    /** Stop whatever Jarvis is saying right now. */
    private fun hush() {
        speechTok++
        barge?.stop(); barge = null
        stream?.cancel(); stream = null
        try { track?.stop() } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { tts?.stop() } catch (_: Exception) {}
        acking = false
    }

    /** While he talks: if Marcus starts speaking over him, Jarvis stops and listens. */
    private fun listenForInterruptions() {
        if (!inConvo || !brain.config().optBoolean("barge_in", true) || Stealth.on(prefs)) return
        barge?.stop()
        barge = BargeIn(this, main) {
            if (!busy) return@BargeIn
            hush()
            thinking = false
            openWindow(convoSeconds())
            listen(firstTurn = false)
        }.also { it.start() }
    }

    /** Next turn of the conversation, or back to waiting for "Hey Jarvis". */
    private fun nextTurn(keepGoing: Boolean) {
        barge?.stop(); barge = null
        thinking = false
        if (keepGoing && prefs.running) { openWindow(convoSeconds()); afterSpeaking { listen(firstTurn = false) } }
        else finishTurn()
    }

    /** Mutes the phone's own "mic on / mic off" beeps while Jarvis listens; always undone before he speaks. */
    private var muted = false
    private val quietStreams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private fun quiet(on: Boolean) {
        if (on == muted) return
        muted = on
        val am = getSystemService(AudioManager::class.java)
        if (on) {
            val streams = if (CallControl.ringingNow()) quietStreams + AudioManager.STREAM_RING else quietStreams
            val did = streams.filter { st -> !am.isStreamMute(st) && try { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); true } catch (_: Exception) { false } }
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

    private var lastPartial = ""
    private var listenSession = 0
    /** True from the moment he's asked something until Jarvis has finished answering: nothing else gets in. */
    @Volatile private var thinking = false

    private fun googleRecognizer(): android.content.ComponentName? {
        val list = packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
        return list.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?.let { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            ?: list.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
                ?.let { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }

    private fun listen(firstTurn: Boolean) {
        if (earOn() || (prefs.running && ensureEar())) { earListen(firstTurn); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Speech recognition isn't available on this phone, sir.") { finishTurn() }; return }
        try { recognizer?.destroy() } catch (_: Exception) {}
        stopWakeWord()
        quiet(true)
        Orb.set(this, Orb.LISTEN)
        recognizer = googleRecognizer()?.let { SpeechRecognizer.createSpeechRecognizer(this, it) } ?: SpeechRecognizer.createSpeechRecognizer(this)
        lastPartial = ""
        val session = ++listenSession
        var done = false
        fun live() = session == listenSession && !done && !thinking
        fun take(t: String) { done = true; try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}; recognizer = null; handle(t, followUp = !firstTurn) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .also { if (Build.VERSION.SDK_INT >= 33) it.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                arrayListOf("Jarvis", prefs.babyName, prefs.herName, "DoorDash", "Dasher", "Macan").filter { s -> s.isNotBlank() } as ArrayList<String>) }
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, brain.config().optLong("end_silence_ms", 1500L))
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, brain.config().optLong("end_silence_ms", 1500L))
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (!live()) return
                retries = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartial }
                Trace.log(this@JarvisService, "heard", "\"$text\" (google)")
                if (text.isBlank()) { done = true; noSpeech(firstTurn) } else take(text)
            }
            override fun onError(error: Int) {
                if (!live()) return
                Trace.log(this@JarvisService, "google", "recognizer error $error")
                done = true
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Problems.log(this@JarvisService, "speech recognizer error $error")
                // the recognizer wasn't ready yet: try again shortly instead of giving up
                if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && retries < 4 && lastPartial.isBlank()) {
                    retries++
                    main.postDelayed({ listen(firstTurn) }, 400L * retries)
                    return
                }
                retries = 0
                if (lastPartial.isNotBlank() && error != SpeechRecognizer.ERROR_CLIENT) { done = false; take(lastPartial); return }
                noSpeech(firstTurn)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { openWindow(convoSeconds()) }
            override fun onRmsChanged(rmsdB: Float) { Orb.level((rmsdB + 2f) / 12f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                openWindow(convoSeconds())
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (p.isBlank() || p == lastPartial) return
                lastPartial = p
                val mine = recognizer
                if (isStop(p)) {
                    main.postDelayed({ if (live() && recognizer === mine && lastPartial == p) { done = true; stopListening(p) } },
                        brain.config().optLong("stop_word_ms", 700L))
                    return
                }
                val wait = brain.config().optLong("done_talking_ms", 1300L)
                main.postDelayed({
                    // same words as a moment ago and he's said something real: he's finished, answer now
                    if (live() && recognizer === mine && lastPartial == p && p.split(" ").size >= 2) try { mine?.stopListening() } catch (_: Exception) {}
                }, wait)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { recognizer?.startListening(intent) } catch (e: Exception) { noSpeech(firstTurn) }
    }

    /**
     * The stop word. A short phrase with "stop" (or "that's all", "I'm done", "go to sleep", "stop listening",
     * "bye", "later") ends the conversation on the spot: Jarvis goes quiet and back to waiting for "Hey Jarvis".
     */
    private fun isStop(t: String): Boolean {
        val s = t.lowercase(Locale.US).replace(Regex("[^a-z' ]"), " ").replace(Regex("\\b(hey|ok|okay|alright|jarvis|please|sir|yo)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty() || s.split(" ").size > 4) return false
        return Regex("^(stop( it| listening| talking| now)?|that'?s (all|it|enough)|i'?m done|we'?re done|done|go to sleep|sleep|bye|later|shut up|be quiet|quiet|enough|cancel)$").matches(s)
    }

    private fun stopListening(text: String) {
        inConvo = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        hush()
        quiet(false)
        turnId++
        Sfx.play("off")   // a short "off" sound, no talking
        main.postDelayed({ finishTurn() }, 150)
    }

    /** He said "Jarvis" while Jarvis was talking: stop talking and listen to him. */
    private fun interrupt() {
        if (!speaking) return
        Trace.log(this, "interrupt", "he said Jarvis while I was talking")
        speaking = false
        stopWakeWord()
        hush()
        thinking = false
        inConvo = true
        turnId++
        Sfx.play("listen")
        openWindow(5)
        main.postDelayed({ listen(firstTurn = false) }, 250)
    }
    @Volatile private var speaking = false

    /** Heard nothing this round. Keep the conversation open until the window runs out. */
    private fun noSpeech(firstTurn: Boolean) {
        if (System.currentTimeMillis() < listenUntil) {
            main.postDelayed({ listen(firstTurn) }, 300)
            return
        }
        finishTurn()
    }

    /** A short pause after Jarvis talks so the mic doesn't hear the end of his own voice. */
    private fun afterSpeaking(fn: () -> Unit) { main.postDelayed(fn, 350) }

    private fun handle(text: String, followUp: Boolean = false) {
        if (isStop(text)) { Trace.log(this, "stop", "\"$text\""); stopListening(text); return }
        val lower = text.lowercase(Locale.US)
        if (Regex("^(never ?mind|thanks?( you)?,? (jarvis|that'?s (it|all))|thank you,? jarvis|nothing|no thanks?|i'?m good|that'?ll be all|goodbye|see you)\\b").containsMatchIn(lower)
            || Regex("^(thanks|thank you|cheers)[.!]?$").matches(lower)) {
            inConvo = false
            if (Stealth.on(prefs)) finishTurn() else speak(Persona.goodbye(brain.config())) { finishTurn() }
            return
        }
        if (thinking) return
        thinking = true
        val turn = ++turnId
        quiet(false)
        acquire()
        Orb.set(this, Orb.THINK)
        thinkingSound(turn)

        chatted = true
        // with Jarvis's own voice he starts talking as soon as the first sentence is ready
        val wasQuiet = Stealth.on(prefs)
        val live = if (!wasQuiet && Piper.installed(this) && prefs.ttsKey.isBlank() && Cast.mode(prefs) != "everything")
            SpeechStream(this, brain.config().optDouble("piper_speed", 1.0).toFloat(), main, brain.config().optInt("sentence_gap_ms", 160)) { acking }.also { stream = it } else null
        thread {
            // quick everyday commands are done right here on the phone: instant, and free
            val t0 = System.currentTimeMillis()
            val quick = Reflex.handle(this, text)
            if (quick != null) { brain.lastTools.clear(); Trace.log(this, "reflex", "handled on the phone: ${quick.reply}") }
            else Trace.log(this, "ask", if (followUp) "follow-up to Claude" else "to Claude")
            val reply = quick?.reply ?: brain.ask(text, followUp, live?.let { s -> { line: String -> main.post { quiet(false); Orb.set(this@JarvisService, Orb.SPEAK) }; s.add(line) } }) { main.post { ack() } }
            Trace.log(this, "reply", "${System.currentTimeMillis() - t0}ms, tools ${brain.lastTools.joinToString(",").ifBlank { "none" }}: \"${reply.take(160)}\"")
            main.post {
                if (turn != turnId) { live?.cancel(); Trace.log(this, "dropped", "cancelled while thinking"); return@post }   // he cancelled while it was thinking
                if (getSystemService(AudioManager::class.java).mode == AudioManager.MODE_IN_CALL) { live?.cancel(); inConvo = false; finishTurn(); return@post }
                // heard chatter that wasn't for him: drop it silently; twice in a row and the conversation's over
                if (reply.isBlank()) { live?.cancel(); ignoredInRow++; nextTurn(ignoredInRow < 2); return@post }
                ignoredInRow = 0
                if (quick?.endConvo == true) inConvo = false
                // he asked for music: let it play instead of listening over it
                if (brain.lastTools.any { it == "play_music" } || (brain.lastTools.contains("media_control") && !reply.contains("paus", true))) inConvo = false
                // in quiet mode his answers arrive as a silent notification instead of out loud
                if (wasQuiet && Stealth.on(prefs)) { live?.cancel(); Stealth.silentNote(this, reply); LogBus.changed(); finishTurn(); return@post }
                LogBus.changed()
                // keep the conversation going (he can talk over Jarvis to cut in); a question from Jarvis always waits for an answer
                val keep = inConvo || reply.trimEnd().endsWith("?")
                val tok = ++speechTok
                val next = { if (tok == speechTok) { speaking = false; stopWakeWord(); nextTurn(keep) } }
                speaking = true
                startWakeWord(whileSpeaking = true)
                if (live != null && live.started) { LockView.showIfLocked(this, reply); live.finish { stream = null; next() } }
                else { live?.cancel(); speak(reply) { next() } }
            }
        }
    }

    /** Jarvis speaks up on his own (heads-ups, timers, reminders), then goes back to listening. */
    private fun sayFirst(line: String, thenListen: Boolean = false, ringing: Boolean = false) {
        if (Stealth.on(prefs)) { Stealth.miss(this, line.replace(" Answer it?", "").replace(" Decline it?", "").replace(" Want to reply, sir?", "")); if (!prefs.running && !busy) stopEverything(); return }
        if (ringing) {
            if (PhoneControl.nightQuiet(this)) { Problems.log(this, "call not announced (goodnight quiet hours): $line"); return }
            // a call beats whatever he was doing: drop it and announce the caller
            if (busy) {
                try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null; hush(); thinking = false; speaking = false; pending.clear(); turnId++
            }
            busy = true; acquire(); stopWakeWord(); followUps = 1
            // his voice goes out on the alarm channel (Android silences assistant audio while it rings), ringtone hushed for a moment
            overRing = true
            val hushed = ringHush(true)
            speak(line) {
                overRing = false
                if (hushed) ringHush(false)
                if (thenListen && CallControl.ringingNow()) { openWindow(5); afterSpeaking { listen(firstTurn = false) } } else finishTurn()
            }
            return
        }
        // say it out loud; only a live phone call or goodnight quiet hours turn it into a notification
        val onCall = getSystemService(AudioManager::class.java).mode == AudioManager.MODE_IN_CALL
        if (onCall || PhoneControl.nightQuiet(this)) { note(line); Scheduler.notify(this, "Jarvis", line, false); if (!prefs.running && !busy) stopEverything(); return }
        // mid-conversation: hold it and say it right after
        if (busy) { if (pending.size < 5) pending.add(line to thenListen); return }
        note(line)
        busy = true
        followUps = 1
        acquire()
        stopWakeWord()
        holdFocus(true)
        Sfx.play("alert")
        val say: (String, () -> Unit) -> Unit = if (Cast.mode(prefs) != "off") { t, f -> speakHouse(t, f) } else { t, f -> speak(t, f) }
        main.postDelayed({ say(line) { if (thenListen) { openWindow(convoSeconds()); afterSpeaking { listen(firstTurn = false) } } else finishTurn() } }, 300)
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
            if (!inCar) { sayFirst("Driving mode off, sir."); return }
            thread {
                val w = Weather.now(this)
                val roads = if (w == null) "" else Weather.roadsNote(this).ifBlank { " Roads look clear." }
                val bat = Status.battery(this).first
                val line = "Welcome back, sir.$roads" + (if (bat in 0..20) " Battery's at $bat percent, you'll want to plug in." else "")
                main.post { sayFirst(line) }
            }
        }
    }

    /** Pauses (or turns down) music and videos while he's talking to Jarvis, then lets them carry on. */
    private var focus: android.media.AudioFocusRequest? = null
    private fun holdFocus(on: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (on && focus == null) {
                focus = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { }.build()
                am.requestAudioFocus(focus!!)
            } else if (!on) focus?.let { am.abandonAudioFocusRequest(it); focus = null }
        } catch (_: Exception) {}
    }

    private val pending = ArrayList<Pair<String, Boolean>>()

    private fun finishTurn() {
        Orb.set(this, Orb.HIDDEN)
        earListening = false
        Trace.log(this, "done", "back to waiting for his name")
        speaking = false
        thinking = false
        inConvo = false
        barge?.stop(); barge = null
        if (pending.isNotEmpty() && prefs.running) {
            val (line, listenAfter) = pending.removeAt(0)
            busy = false
            main.postDelayed({ sayFirst(line, listenAfter) }, 400)
            return
        }
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        main.postDelayed({ if (!busy) holdFocus(false) }, 500)
        main.postDelayed({ if (recognizer == null) quiet(false) }, 400)
        if (chatted) { chatted = false; thread { Memory.learn(this) } }
        busy = false
        release()
        LogBus.changed()
        if (prefs.running) startWakeWord() else stopEverything()
    }

    private var player: android.media.MediaPlayer? = null
    private var acking = false
    private var stream: SpeechStream? = null
    private var casting = false
    private val ACKS = listOf("On it, sir.", "Right away, sir.", "One moment, sir.", "On it.")
    /** A soft pulse while he's working on a longer answer, instead of dead air. Stops the moment he speaks. */
    private fun thinkingSound(turn: Int) {
        if (!brain.config().optBoolean("thinking_sound", false) || Stealth.on(prefs)) return
        val r = object : Runnable {
            override fun run() {
                if (turn != turnId || !thinking || speaking || acking || stream?.started == true) return
                Sfx.play("think")
                main.postDelayed(this, 1400)
            }
        }
        main.postDelayed(r, brain.config().optLong("thinking_after_ms", 1100L))
    }

    private fun ack() {
        if (stream?.started == true || Stealth.on(prefs)) return
        speak(Persona.ack(brain.config())) { acking = false }
        acking = true
        main.postDelayed({ acking = false }, 6000)
    }

    /** Speak with the realistic Google voice when a key is set, otherwise the phone's own voice. */
    /** At home with a Nest Hub: say it through the speaker (blocks until done), else on the phone. */
    private fun speakHouse(text: String, then: () -> Unit) {
        if (Cast.target(prefs) == null || !Piper.installed(this) || prefs.ttsKey.isNotBlank()) { speak(text, then); return }
        if (acking) { main.postDelayed({ speakHouse(text, then) }, 150); return }
        quiet(false)
        LockView.showIfLocked(this, text)
        thread {
            val a = Piper.synth(this, text, brain.config().optDouble("piper_speed", 1.0).toFloat())
            val ok = a != null && Cast.play(this, Cast.wav(this, a.first, a.second), "audio/wav", a.first.size * 1000L / a.second + 3000)
            main.post { if (ok) then() else if (a != null && playPcm(a.first, a.second, then)) Unit else phoneSpeak(text, then) }
        }
    }

    private fun speak(text: String, then: () -> Unit) {
        if (acking) { main.postDelayed({ speak(text, then) }, 150); return }
        if (Cast.mode(prefs) == "everything" && Cast.target(prefs) != null && !casting) { casting = true; speakHouse(text) { casting = false; then() }; return }
        quiet(false)
        LockView.showIfLocked(this, text)
        val key = prefs.ttsKey
        if (key.isBlank()) {
            if (!Piper.installed(this)) { phoneSpeak(text, then); return }
            thread {
                val a = Piper.synth(this, text, brain.config().optDouble("piper_speed", 1.0).toFloat())
                main.post { if (a == null || !playPcm(a.first, a.second, then)) phoneSpeak(text, then) }
            }
            return
        }
        thread {
            val f = CloudVoice.synth(this, key, text, brain.config())
            main.post { if (f == null || !playFile(f, then)) phoneSpeak(text, then) }
        }
    }

    private var track: android.media.AudioTrack? = null
    /** True while announcing a ringing call. */
    @Volatile private var overRing = false
    private fun usage() = if (overRing) android.media.AudioAttributes.USAGE_ALARM else android.media.AudioAttributes.USAGE_ASSISTANT
    private fun ringHush(on: Boolean): Boolean = try {
        val am = getSystemService(AudioManager::class.java)
        if (on && am.isStreamMute(AudioManager.STREAM_RING)) false
        else { am.adjustStreamVolume(AudioManager.STREAM_RING, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0); true }
    } catch (_: Exception) { false }

    /** Plays Piper's audio; calls then() once when it finishes. */
    private fun playPcm(samples: FloatArray, rate: Int, then: () -> Unit): Boolean = try {
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(usage())
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4).build()
        t.write(samples, 0, samples.size, android.media.AudioTrack.WRITE_BLOCKING)
        Orb.voiceStart(rate) { t.playbackHeadPosition.toLong() }
        Orb.voiceAdd(samples)
        var fired = false
        val finish = { if (!fired) { fired = true; main.post(then) } }
        t.notificationMarkerPosition = maxOf(1, samples.size - 1)
        t.setPlaybackPositionUpdateListener(object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(tr: android.media.AudioTrack) { finish() }
            override fun onPeriodicNotification(tr: android.media.AudioTrack) {}
        }, main)
        main.postDelayed({ finish() }, samples.size * 1000L / rate + 700)
        t.play()
        Orb.set(this, Orb.SPEAK)
        track = t
        true
    } catch (_: Exception) { false }

    private fun playFile(f: java.io.File, then: () -> Unit): Boolean = try {
        player?.release()
        val mp = android.media.MediaPlayer()
        mp.setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(usage())
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
        try { t.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(usage()).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()) } catch (_: Exception) {}
        t.setSpeechRate(cfg.optDouble("voice_rate", 0.95).toFloat())
        t.setPitch(cfg.optDouble("voice_pitch", 0.88).toFloat())
        val id = "j" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) main.post(then) }
            @Deprecated("old api") override fun onError(utteranceId: String?) { if (utteranceId == id) main.post(then) }
        })
        Orb.set(this, Orb.SPEAK)
        t.speak(Piper.speakable(text), TextToSpeech.QUEUE_FLUSH, null, id)
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
        Orb.set(this, Orb.HIDDEN)
        thinking = false
        prefs.running = false
        busy = false
        try { tts?.stop() } catch (_: Exception) {}
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        stream?.cancel(); stream = null
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
        restoreSound()
        holdFocus(false)
        earListening = false
        try { ear?.stop() } catch (_: Exception) {}
        ear = null
        try { wakeWord?.close() } catch (_: Exception) {}
        wakeWord = null
        recognizer?.destroy(); recognizer = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogBus.changed()
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlocked) } catch (_: Exception) {}
        try { unregisterReceiver(batteryWatch) } catch (_: Exception) {}
        try { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(wifiWatch) } catch (_: Exception) {}
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(carWatch) } catch (_: Exception) {}
        DriveMode.on = false
        quiet(false)
        try { ear?.stop() } catch (_: Exception) {}
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
        const val ACTION_QUIET_END = "com.ziyair.jarvis.QUIET_END"
        const val ACTION_HUSH = "com.ziyair.jarvis.HUSH"
        /** Lets the music player take the speaker back (true = Jarvis holds it, false = let go). */
        @Volatile var focus: ((Boolean) -> Unit)? = null
    }
}

/** Tiny signal so the screen refreshes the conversation when the service adds to it. */
object LogBus {
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners.add(fn) }
    fun unlisten(fn: () -> Unit) { listeners.remove(fn) }
    fun changed() { listeners.toList().forEach { it() } }
}
