package com.ziyair.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var toggle: Button
    private lateinit var update: Button
    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        toggle = findViewById(R.id.toggle)
        update = findViewById(R.id.update)

        val claude = findViewById<EditText>(R.id.claudeKey)
        val pico = findViewById<EditText>(R.id.picoKey)
        val her = findViewById<EditText>(R.id.herName)
        val notes = findViewById<EditText>(R.id.notes)
        claude.setText(prefs.claudeKey); pico.setText(prefs.picoKey); her.setText(prefs.herName); notes.setText(prefs.notes)
        val city = findViewById<EditText>(R.id.city); val lunch = findViewById<EditText>(R.id.lunch)
        val dinner = findViewById<EditText>(R.id.dinner); val bed = findViewById<EditText>(R.id.bedtime)
        city.setText(prefs.city); lunch.setText(prefs.lunch); dinner.setText(prefs.dinner); bed.setText(prefs.bedtime)
        val car = findViewById<EditText>(R.id.carName); car.setText(prefs.carName)
        val ttsKey = findViewById<EditText>(R.id.ttsKey); ttsKey.setText(prefs.ttsKey)
        val voiceName = findViewById<EditText>(R.id.voiceName); voiceName.setText(prefs.voiceName)

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.claudeKey = claude.text.toString(); prefs.picoKey = pico.text.toString()
            prefs.herName = her.text.toString(); prefs.notes = notes.text.toString()
            prefs.city = city.text.toString(); prefs.lunch = lunch.text.toString()
            prefs.dinner = dinner.text.toString(); prefs.bedtime = bed.text.toString()
            prefs.carName = car.text.toString()
            prefs.ttsKey = ttsKey.text.toString(); prefs.voiceName = voiceName.text.toString()
            Scheduler.scheduleDaily(this)
            status.text = "Saved, sir."
            findViewById<View>(R.id.setup).visibility = if (ready()) View.GONE else View.VISIBLE
            render()
        }
        findViewById<Button>(R.id.editSetup).setOnClickListener {
            val s = findViewById<View>(R.id.setup); s.visibility = if (s.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        toggle.setOnClickListener { if (prefs.running) stopJarvis() else startJarvis() }
        findViewById<Button>(R.id.talk).setOnClickListener {
            if (!hasMic()) { askPermissions(); return@setOnClickListener }
            startForegroundService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_LISTEN))
        }
        findViewById<Button>(R.id.openDash).setOnClickListener {
            if (!ready()) { status.text = "Add your Claude key first, sir."; return@setOnClickListener }
            startActivity(Intent(this, DashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.allowComms).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 2)
        }
        findViewById<Button>(R.id.allowApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.allowQuiet).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.allowScreen).setOnClickListener {
            if (JarvisAccess.on(this)) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return@setOnClickListener }
            android.app.AlertDialog.Builder(this)
                .setTitle("Let Jarvis control the screen")
                .setMessage("Two quick steps, sir:\n\n1. Tap App info, then the ⋮ menu in the top corner, then \"Allow restricted settings\". (If you don't see it, skip this step.)\n\n2. Come back, tap Accessibility, then Installed apps, then Jarvis screen control, and switch it on.")
                .setPositiveButton("Accessibility") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNeutralButton("App info") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .show()
        }
        findViewById<Button>(R.id.memoryBtn).setOnClickListener { showMemory() }
        findViewById<Button>(R.id.testVoice).setOnClickListener {
            if (prefs.claudeKey.isBlank() && prefs.ttsKey.isBlank()) { status.text = "Save your keys first, sir."; return@setOnClickListener }
            if (!hasMic()) { askPermissions(); return@setOnClickListener }
            startForegroundService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_SAY)
                .putExtra("text", "Good evening, sir. This is how I sound. Shall we get to work?"))
            if (prefs.ttsKey.isNotBlank()) thread { Thread.sleep(6000); runOnUiThread { if (CloudVoice.lastError.isNotBlank()) status.text = CloudVoice.lastError + ". Check the Google voice key." } }
        }
        findViewById<Button>(R.id.allowMsgs).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.readMode).setOnClickListener {
            prefs.readMessages = when (prefs.readMessages) { "driving" -> "always"; "always" -> "off"; else -> "driving" }; render()
        }
        findViewById<Button>(R.id.wakeScreen).setOnClickListener { prefs.wakeScreen = !prefs.wakeScreen; render() }
        findViewById<Button>(R.id.talkFirst).setOnClickListener {
            prefs.talkFirst = !prefs.talkFirst; Scheduler.scheduleDaily(this); render()
        }
        findViewById<Button>(R.id.clear).setOnClickListener { prefs.clearHistory(); render() }
        update.setOnClickListener {
            update.text = "Checking…"
            thread {
                val r = Updater.check(this, force = true)
                runOnUiThread {
                    update.text = if (r != null) "Installing build ${r.build}…" else "Up to date · build ${BuildConfig.VERSION_CODE}"
                }
            }
        }
        update.text = "Check for updates · build ${BuildConfig.VERSION_CODE}"
        findViewById<View>(R.id.setup).visibility = if (ready()) View.GONE else View.VISIBLE
        handleExtras(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleExtras(intent) }

    /** autostart: just updated, bring Jarvis back and step out of the way. update: tapped "Update ready". */
    private fun handleExtras(i: Intent?) {
        if (i?.getBooleanExtra("autostart", false) == true) {
            i.removeExtra("autostart")
            if (ready() && hasMic()) {
                startForegroundService(Intent(this, JarvisService::class.java)); prefs.running = true
                status.text = "Updated to build ${BuildConfig.VERSION_CODE}, sir. Back on."
                window.decorView.postDelayed({ moveTaskToBack(true) }, 600)
            }
        }
        if (i?.getBooleanExtra("update", false) == true) { i.removeExtra("update"); update.performClick() }
    }

    override fun onResume() { super.onResume(); LogBus.listen(refresh); render() }
    override fun onPause() { LogBus.unlisten(refresh); super.onPause() }

    private fun ready() = prefs.claudeKey.isNotBlank()
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(want.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        if (requestCode == 2) return
        if (hasMic() && pendingStart) { pendingStart = false; startJarvis() }
        else if (!hasMic()) status.text = "I need the microphone to hear you, sir. Allow it in the app's settings."
    }

    private var pendingStart = false
    private fun startJarvis() {
        if (!ready()) { status.text = "Add your Claude key first, sir."; findViewById<View>(R.id.setup).visibility = View.VISIBLE; return }
        if (!hasMic()) { pendingStart = true; askPermissions(); return }
        startForegroundService(Intent(this, JarvisService::class.java))
        prefs.running = true
        render()
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) askBattery()
    }

    private fun stopJarvis() {
        startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
        prefs.running = false
        render()
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun showMemory() {
        val facts = Memory.all(this)
        val b = android.app.AlertDialog.Builder(this)
            .setTitle("What Jarvis remembers")
            .setMessage(if (facts.isEmpty()) "Nothing yet. Talk to him, or say \"Hey Jarvis, remember that...\"" else facts.joinToString("\n\n") { "• $it" })
            .setPositiveButton("Close", null)
        if (facts.isNotEmpty()) b.setNegativeButton("Forget everything") { _, _ -> Memory.clear(this); render() }
        b.show()
    }

    private fun render() {
        findViewById<Button>(R.id.talkFirst).text = if (prefs.talkFirst) "Speaks first: on" else "Speaks first: off"
        val listening = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        findViewById<Button>(R.id.allowMsgs).text = if (listening) "Reading messages: allowed" else "Allow reading messages"
        findViewById<Button>(R.id.readMode).text = "Read messages aloud: " + when (prefs.readMessages) { "always" -> "always"; "off" -> "never"; else -> "while driving" }
        findViewById<Button>(R.id.wakeScreen).text = if (prefs.wakeScreen) "Show replies on lock screen: on" else "Show replies on lock screen: off"
        val comms = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        findViewById<Button>(R.id.allowComms).text = if (comms && Cal.allowed(this)) "Calls, texts & calendar: allowed" else "Allow calls, texts & calendar"
        findViewById<Button>(R.id.allowScreen).text = if (JarvisAccess.on(this)) "Screen control: on" else "Allow screen control"
        findViewById<Button>(R.id.allowQuiet).text = if (PhoneControl.quietAllowed(this)) "Quiet mode: allowed" else "Allow quiet mode"
        findViewById<Button>(R.id.allowApps).text = if (Settings.canDrawOverlays(this)) "Opening apps: allowed" else "Allow opening apps"
        toggle.text = if (prefs.running) "Stop listening" else "Start Jarvis"
        if (status.text.isNullOrBlank() || status.text.startsWith("Listening") || status.text.startsWith("Off"))
            status.text = if (prefs.running) "Listening for \"Hey Jarvis\", sir. You can lock your phone." else "Off. Tap Start Jarvis."
        val h = prefs.history()
        val sb = StringBuilder()
        for (i in 0 until h.length()) {
            val m = h.getJSONObject(i)
            sb.append(if (m.getString("role") == "user") "You: " else "Jarvis: ").append(m.getString("content")).append("\n\n")
        }
        log.text = if (sb.isEmpty()) "Say \"Hey Jarvis\" and ask anything. Your conversation shows up here." else sb.toString().trim()
        if (findViewById<View>(R.id.setup).visibility != View.VISIBLE)
            findViewById<ScrollView>(R.id.scroll).post { findViewById<ScrollView>(R.id.scroll).fullScroll(View.FOCUS_DOWN) }
    }
}
