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
        thread { brain.refreshConfig(); Updater.check(this, busy = busy) }
        Cast.discover(this)
        // undo any call mode left over from older builds
        try { val am = getSystemService(AudioManager::class.java); if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        // watchdog: if his ears ever stop (mic grabbed by another app, an error), quietly start them again
        main.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (prefs.running && !busy && wakeWord?.alive() != true && !inCall()) { Problems.log(this@JarvisService, "wake word restarted"); startWakeWord() }
                    if (Stealth.expired(prefs)) startService(Intent(this@JarvisService, JarvisService::class.java).setAction(ACTION_QUIET_END))
                } catch (_: Exception) {}
                main.postDelayed(this, 30_000)
            }
        }, 30_000)
        // Jarvis's own voice: fetch it once on Wi-Fi, then keep it warm
        thread {
            val metered = try { getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered } catch (_: Exception) { true }
            if (!Piper.installed(this) && !metered) Piper.download(this)
            Piper.warm(this)
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
        // a real phone call or ringing only; "communication" mode can be left behind by other apps and made him deaf
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    private fun onWake(manual: Boolean = false) {
        if (busy) return
        if (!manual && inCall()) { startWakeWord(); return }
        busy = true
        followUps = 0
        chatted = false
        inConvo = true
        ignoredInRow = 0
        acquire()
        stopWakeWord()
        holdFocus(true)
        if (!Stealth.on(prefs)) try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {}
        openWindow(brain.config().optInt("wake_seconds", 5))
        main.postDelayed({ listen(firstTurn = true) }, 200)
    }

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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Speech recognition isn't available on this phone, sir.") { finishTurn() }; return }
        try { recognizer?.destroy() } catch (_: Exception) {}
        quiet(true)
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
                if (text.isBlank()) { done = true; noSpeech(firstTurn) } else take(text)
            }
            override fun onError(error: Int) {
                if (!live()) return
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
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                openWindow(convoSeconds())
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (p.isBlank() || p == lastPartial) return
                lastPartial = p
                val mine = recognizer
                val wait = brain.config().optLong("done_talking_ms", 900L)
                main.postDelayed({
                    // same words as a moment ago and he's said something real: he's finished, answer now
                    if (live() && recognizer === mine && lastPartial == p && p.split(" ").size >= 2) try { mine?.stopListening() } catch (_: Exception) {}
                }, wait)
            }
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
        finishTurn()
    }

    /** A short pause after Jarvis talks so the mic doesn't hear the end of his own voice. */
    private fun afterSpeaking(fn: () -> Unit) { main.postDelayed(fn, 350) }

    private fun handle(text: String, followUp: Boolean = false) {
        val lower = text.lowercase(Locale.US)
        if (Regex("^(stop|never ?mind|that'?s (all|it)|thanks?( you)?,? (jarvis|that'?s (it|all))|thank you,? jarvis|go to sleep|nothing|no thanks?|i'?m good|i'?m done|we'?re done|that'?ll be all|bye|goodbye|later|see you)\\b").containsMatchIn(lower)
            || Regex("^(thanks|thank you|cheers)[.!]?$").matches(lower)) {
            inConvo = false
            if (Stealth.on(prefs)) finishTurn() else speak(pick("goodbyes", "Very good, sir.")) { finishTurn() }
            return
        }
        if (thinking) return
        thinking = true
        quiet(false)
        acquire()

        chatted = true
        // with Jarvis's own voice he starts talking as soon as the first sentence is ready
        val wasQuiet = Stealth.on(prefs)
        val live = if (!wasQuiet && Piper.installed(this) && prefs.ttsKey.isBlank() && Cast.mode(prefs) != "everything")
            SpeechStream(this, brain.config().optDouble("piper_speed", 1.0).toFloat(), main) { acking }.also { stream = it } else null
        thread {
            val reply = brain.ask(text, followUp, live?.let { s -> { line: String -> main.post { quiet(false) }; s.add(line) } }) { main.post { ack() } }
            main.post {
                if (getSystemService(AudioManager::class.java).mode == AudioManager.MODE_IN_CALL) { live?.cancel(); inConvo = false; finishTurn(); return@post }
                // heard chatter that wasn't for him: drop it silently; twice in a row and the conversation's over
                if (reply.isBlank()) { live?.cancel(); ignoredInRow++; nextTurn(ignoredInRow < 2); return@post }
                ignoredInRow = 0
                // he asked for music: let it play instead of listening over it
                if (brain.lastTools.any { it == "play_music" } || (brain.lastTools.contains("media_control") && !reply.contains("paus", true))) inConvo = false
                // in quiet mode his answers arrive as a silent notification instead of out loud
                if (wasQuiet && Stealth.on(prefs)) { live?.cancel(); Stealth.silentNote(this, reply); LogBus.changed(); finishTurn(); return@post }
                LogBus.changed()
                // keep the conversation going (he can talk over Jarvis to cut in); a question from Jarvis always waits for an answer
                val keep = inConvo || reply.trimEnd().endsWith("?")
                val tok = ++speechTok
                val next = { if (tok == speechTok) nextTurn(keep) }
                listenForInterruptions()
                if (live != null && live.started) { LockView.showIfLocked(this, reply); live.finish { stream = null; next() } }
                else { live?.cancel(); speak(reply) { next() } }
            }
        }
    }

    /** Jarvis speaks up on his own (heads-ups, timers, reminders), then goes back to listening. */
    private fun sayFirst(line: String, thenListen: Boolean = false, ringing: Boolean = false) {
        if (Stealth.on(prefs)) { Stealth.miss(this, line.replace(" Answer it?", "").replace(" Decline it?", "").replace(" Want to reply, sir?", "")); if (!prefs.running && !busy) stopEverything(); return }
        if (ringing) {
            if (busy || PhoneControl.nightQuiet(this)) return
            busy = true; acquire(); stopWakeWord(); followUps = 1
            speak(line) { if (thenListen && CallControl.ringingNow()) { openWindow(5); afterSpeaking { listen(firstTurn = false) } } else finishTurn() }
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
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) } catch (_: Exception) {}
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
    private fun ack() {
        if (stream?.started == true || Stealth.on(prefs)) return
        speak(ACKS.random()) { acking = false }
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

    /** Plays Piper's audio; calls then() once when it finishes. */
    private fun playPcm(samples: FloatArray, rate: Int, then: () -> Unit): Boolean = try {
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4).build()
        t.write(samples, 0, samples.size, android.media.AudioTrack.WRITE_BLOCKING)
        var fired = false
        val finish = { if (!fired) { fired = true; main.post(then) } }
        t.notificationMarkerPosition = maxOf(1, samples.size - 1)
        t.setPlaybackPositionUpdateListener(object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(tr: android.media.AudioTrack) { finish() }
            override fun onPeriodicNotification(tr: android.media.AudioTrack) {}
        }, main)
        main.postDelayed({ finish() }, samples.size * 1000L / rate + 700)
        t.play()
        track = t
        true
    } catch (_: Exception) { false }

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
    }
}

/** Tiny signal so the screen refreshes the conversation when the service adds to it. */
object LogBus {
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners.add(fn) }
    fun unlisten(fn: () -> Unit) { listeners.remove(fn) }
    fun changed() { listeners.toList().forEach { it() } }
}
