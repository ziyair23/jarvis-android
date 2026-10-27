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

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.claudeKey = claude.text.toString(); prefs.picoKey = pico.text.toString()
            prefs.herName = her.text.toString(); prefs.notes = notes.text.toString()
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
        findViewById<Button>(R.id.battery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.clear).setOnClickListener { prefs.clearHistory(); render() }
        update.setOnClickListener {
            update.text = "Checking…"
            thread {
                val r = Updater.check(this, force = true)
                runOnUiThread {
                    if (r != null) { update.text = "Get build ${r.build}"; startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl))) }
                    else update.text = "Up to date · build ${BuildConfig.VERSION_CODE}"
                }
            }
        }
        update.text = "Check for updates · build ${BuildConfig.VERSION_CODE}"
        findViewById<View>(R.id.setup).visibility = if (ready()) View.GONE else View.VISIBLE
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

    private fun render() {
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
