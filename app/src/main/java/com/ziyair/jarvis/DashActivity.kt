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
        web.setBackgroundColor(0xFF0F0F10.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = android.webkit.WebChromeClient()
        web.addJavascriptInterface(Bridge(), "J")
        setContentView(web)
        web.loadUrl("file:///android_asset/dash.html")
    }

    override fun onResume() { super.onResume(); Orb.inApp = true; if (::web.isInitialized) { web.onResume(); web.resumeTimers(); LogBus.listen(refresh); refresh() } }
    // the page's timers and animation stop while the app isn't on screen (battery)
    override fun onPause() { Orb.inApp = false; LogBus.unlisten(refresh); if (::web.isInitialized) { web.onPause(); web.pauseTimers() }; super.onPause() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!::web.isInitialized) { super.onBackPressed(); return }
        // an open sheet closes first; otherwise leave the app as usual
        web.evaluateJavascript("(window.backPressed ? window.backPressed() : false)") { v -> if (v != "true") finish() }
    }

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
                .put("lists", Lists.all(p)).put("expenses", Expenses.all(p))
                .put("problems", Problems.all(p))
                .put("ears", Ear.status(ctx))
                .put("health", Health.json(ctx))
                .put("name", Regex("(?i)name is ([A-Za-z]+)").find(p.notes)?.groupValues?.get(1) ?: "")
                .put("babyName", p.babyName)
                .put("target", p.sp.getFloat("daily_target", 120f).toDouble())
                .put("spend", Spend.thisMonth(p)).put("budget", Spend.budget(p))
                .put("reminders", p.reminders())
                .put("bills", Bills.json(ctx))
                .put("appts", Appts.json(ctx))
                .put("packages", Packages.all(ctx))
                .put("mealPlan", Meals.plan(ctx))
                .put("dashToday", try { DashDays.on(ctx).name } catch (_: Exception) { "NO" })
                .put("dayPlan", try { DayPlan.card(ctx).takeIf { System.currentTimeMillis() - it.optLong("at") < 60_000L } ?: DayPlan.save(ctx) } catch (_: Exception) { org.json.JSONObject() })
                .put("mind", try { Mind.json(ctx) } catch (e: Exception) { Trace.log(ctx, "dash", "mind failed: ${e.message}"); org.json.JSONObject() })
                .put("spendTx", Spending.all(ctx).let { a -> org.json.JSONArray().apply { for (i in maxOf(0, a.length() - 300) until a.length()) put(a.get(i)) } })
                .put("supplies", Supplies.all(ctx))
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
        /** "state,level" for the big orb on the dashboard (0 idle, 1 listening, 2 thinking, 3 speaking). */
        @JavascriptInterface fun orb(): String = Orb.now().let { "${it.first},${it.second}" }
        @JavascriptInterface fun hush() = runOnUiThread { startService(Intent(ctx, JarvisService::class.java).setAction(JarvisService.ACTION_HUSH)) }
        @JavascriptInterface fun agents(): String = try { Agents.json(ctx).toString() } catch (e: Exception) { Trace.log(ctx, "dash", "agents list failed: ${e.message}"); "[]" }
        @JavascriptInterface fun dashDay(on: Boolean) { DashDays.set(ctx, on, false); DayPlan.save(ctx); LogBus.changed() }
        @JavascriptInterface fun mindAct(id: String, doIt: Boolean) { Mind.act(ctx, id.toLongOrNull() ?: 0L, doIt) }
        @JavascriptInterface fun mindToggle(on: Boolean) { Mind.setEnabled(ctx, on) }
        @JavascriptInterface fun mindNow(): Boolean = Mind.thinkNow(ctx).also { LogBus.changed() }
        @JavascriptInterface fun loopDone(id: String) { Loops.done(ctx, id); LogBus.changed() }
        @JavascriptInterface fun jsError(msg: String) { Trace.log(ctx, "dash", "screen error: ${msg.take(200)}") }
        @JavascriptInterface fun agentToggle(id: String, on: Boolean) { Agents.setEnabled(ctx, id, on); LogBus.changed() }
        @JavascriptInterface fun agentTeach(id: String, text: String) { if (id == "jarvis") Memory.add(ctx, text) else if (id == "coach") Coach.addLesson(ctx, text, "marcus") else Agents.addNote(ctx, id, text); LogBus.changed() }
        @JavascriptInterface fun agentForget(id: String, at: String) { if (id == "coach") Coach.removeAt(ctx, at.toLongOrNull() ?: 0L) else Agents.removeNote(ctx, id, at.toLongOrNull() ?: 0L); LogBus.changed() }
        @JavascriptInterface fun fix(key: String) = runOnUiThread { Health.fix(ctx, key) }
        @JavascriptInterface fun shareLog() = runOnUiThread { Trace.share(ctx) }
        @JavascriptInterface fun quietStart() { Stealth.start(ctx, null); LogBus.changed() }
        @JavascriptInterface fun listAdd(items: String) { if (items.isNotBlank()) Lists.add(ctx, items, "list"); LogBus.changed() }
        @JavascriptInterface fun listToggle(list: String, at: String) { Lists.toggle(ctx, list, at.toLong()); LogBus.changed() }
        @JavascriptInterface fun listClearDone() { Lists.clear(ctx, "list", false); LogBus.changed() }
        @JavascriptInterface fun expenseAdd(amount: String, what: String) { amount.toDoubleOrNull()?.let { Expenses.log(ctx, it, what, "today") }; LogBus.changed() }
        @JavascriptInterface fun expenseDelete(at: String) { Expenses.delete(ctx, at.toLong()); LogBus.changed() }
        @JavascriptInterface fun clearProblems() { Problems.clear(ctx); LogBus.changed() }
        @JavascriptInterface fun openSetup() = runOnUiThread { startActivity(Intent(ctx, MainActivity::class.java)) }
    }
}
