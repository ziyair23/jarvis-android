package com.ziyair.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis's dashboard: everything he keeps track of (baby, DoorDash, the Macan, memory, reminders, conversation),
 * in one place. Opens first when the app starts; the gear goes to setup.
 */
class DashActivity : Activity() {
    private lateinit var web: WebView
    private val refresh: () -> Unit = { runOnUiThread { web.evaluateJavascript("window.load && load()", null) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs(this).claudeKey.isBlank()) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        web = WebView(this)
        web.setBackgroundColor(0xFF14110E.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = android.webkit.WebChromeClient()
        web.addJavascriptInterface(Bridge(), "J")
        setContentView(web)
        web.loadUrl("file:///android_asset/dash.html")
    }

    override fun onResume() { super.onResume(); if (::web.isInitialized) { LogBus.listen(refresh); refresh() } }
    override fun onPause() { LogBus.unlisten(refresh); super.onPause() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }

    inner class Bridge {
        private val ctx get() = this@DashActivity
        private val prefs get() = Prefs(ctx)

        @JavascriptInterface fun data(): String {
            val p = prefs
            return JSONObject()
                .put("running", p.running)
                .put("baby", Baby.all(p))
                .put("babyStatus", Baby.status(ctx))
                .put("feedHours", Baby.feedHours(p))
                .put("earnings", Earnings.all(p))
                .put("earnSummary", Earnings.summary(ctx))
                .put("goal", Earnings.goal(p))
                .put("goalName", Earnings.goalName(p))
                .put("memory", JSONArray(Memory.all(ctx)))
                .put("journal", Journal.all(p))
                .put("stealth", Stealth.on(p))
                .put("spend", Spend.thisMonth(p)).put("budget", Spend.budget(p))
                .put("reminders", p.reminders())
                .put("history", p.history())
                .toString()
        }

        @JavascriptInterface fun logBaby(kind: String, note: String) { Baby.log(ctx, kind, 0.0, note); LogBus.changed() }
        @JavascriptInterface fun addJournal(text: String) { if (text.isNotBlank()) Journal.add(ctx, text); LogBus.changed() }
        @JavascriptInterface fun deleteJournal(at: String) { Journal.delete(ctx, at.toLong()); LogBus.changed() }
        @JavascriptInterface fun deleteBaby(at: String) { Baby.delete(ctx, at.toLong()); LogBus.changed() }
        @JavascriptInterface fun logEarn(amount: String, day: String, replace: Boolean) {
            amount.toDoubleOrNull()?.let { Earnings.log(ctx, it, day, replace) }; LogBus.changed()
        }
        @JavascriptInterface fun deleteEarn(day: String) { Earnings.delete(ctx, day); LogBus.changed() }
        @JavascriptInterface fun setGoal(amount: String, name: String) { amount.toDoubleOrNull()?.let { Earnings.setGoal(ctx, it, name) }; LogBus.changed() }
        @JavascriptInterface fun forget(fact: String) {
            val p = prefs; val a = p.memory(); val keep = JSONArray()
            for (i in 0 until a.length()) if (a.getJSONObject(i).optString("t") != fact) keep.put(a.get(i))
            p.saveMemory(keep); LogBus.changed()
        }
        @JavascriptInterface fun remember(fact: String) { Memory.add(ctx, fact); LogBus.changed() }
        @JavascriptInterface fun cancelReminder(id: Int) { Scheduler.cancel(ctx, id); LogBus.changed() }
        @JavascriptInterface fun talk() = runOnUiThread {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { openSetup(); return@runOnUiThread }
            startForegroundService(Intent(ctx, JarvisService::class.java).setAction(JarvisService.ACTION_LISTEN))
        }
        @JavascriptInterface fun quietEnd() = runOnUiThread { startService(Intent(ctx, JarvisService::class.java).setAction(JarvisService.ACTION_QUIET_END)) }
        @JavascriptInterface fun quietStart() { Stealth.start(ctx, null); LogBus.changed() }
        @JavascriptInterface fun openSetup() = runOnUiThread { startActivity(Intent(ctx, MainActivity::class.java)) }
    }
}
