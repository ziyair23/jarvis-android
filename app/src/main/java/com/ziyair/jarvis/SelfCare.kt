package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Jarvis looking after himself, overnight while Marcus sleeps:
 *  - night tests: his voice, his ears, his brain (Claude and the fast brain), reminders, permissions, his memory;
 *    fixes what he can on the spot (re-arms reminders, clears stuck settings) and reports the rest in the morning;
 *  - self-fixing: crashes and repeated problems become fixes. Small ones he applies himself; anything that needs new
 *    code is written up for the Builder, which only installs after Marcus says yes;
 *  - watches his own speed and hearing, and tunes himself within safe limits (how long he waits before answering,
 *    how sensitive talking-over-him is);
 *  - tidies his memory once a week;
 * and in the morning, rehearses the day (clashes in the plan) and runs the money early-warning.
 */
object SelfCare {
    @Volatile var running = false; private set
    private val day get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun report(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("night_report", "{}")) } catch (_: Exception) { JSONObject() }

    /** From the ten-minute tick: once a night, between 1 and 5 AM. */
    fun maybeNight(c: Context) {
        if (running || !Brain(c).config().optBoolean("self_care", true)) return
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h !in 1..4) return
        val p = Prefs(c); val today = day.format(Date())
        if (p.sp.getString("night_done", "") == today) return
        p.sp.edit().putString("night_done", today).apply()
        running = true
        thread(name = "jarvis-night") {
            try { night(c) } catch (e: Exception) { Problems.log(c, "night check: ${e.message}") }
            try { if (Mind.on(c) && !Claude.nearBudget(c, 0.85)) Dream.run(c) } catch (e: Exception) { Problems.log(c, "dream: ${e.message}") }
            running = false; LogBus.changed()
        }
    }

    /** "Run your checks now" (voice or the app). */
    fun now(c: Context): String {
        if (running) return "Already checking myself."
        running = true
        thread(name = "jarvis-check") { try { night(c) } catch (e: Exception) { Problems.log(c, "self check: ${e.message}") } finally { running = false; LogBus.changed() } }
        return "Started. Tell him you're running your own checks and will report what you find and fix in a minute."
    }

    class Test(val name: String, val ok: Boolean, val detail: String, val fixed: Boolean = false)

    private fun night(c: Context) {
        val t0 = System.currentTimeMillis()
        val tests = ArrayList<Test>()
        fun test(name: String, f: () -> Test) { tests.add(try { f() } catch (e: Exception) { Test(name, false, e.message ?: "error") }) }
        val p = Prefs(c)
        test("voice") { if (!Piper.installed(c)) Test("voice", true, "phone voice not used") else Piper.synth(c, "Testing.", 1f)?.let { Test("voice", true, "speaks") } ?: Test("voice", false, "his voice didn't make any sound") }
        test("ears") { Test("ears", Ear.ready(c), if (Ear.ready(c)) "speech model ready" else "speech model missing") }
        test("brain") {
            val r = Claude.send(c, JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 5).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Say ok"))))
            Test("brain", r != null, if (r != null) "Claude answers" else "Claude didn't answer: ${Claude.lastCode} ${Claude.lastError.take(80)}")
        }
        test("fast brain") {
            if (p.groqKey.isBlank()) Test("fast brain", true, "no Groq key")
            else { val bad = p.sp.getString("fast_chat_bad", "").orEmpty(); val allBad = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b").all { bad.contains(it) }
                if (allBad) { p.sp.edit().remove("fast_chat_bad").apply(); Test("fast brain", false, "every fast model was marked unavailable; cleared so he tries again", fixed = true) } else Test("fast brain", true, "ready") }
        }
        test("reminders") { Scheduler.rearm(c); Test("reminders", true, "${p.reminders().length()} armed", fixed = false) }
        test("memory") { Test("memory", Timeline.count(c) > 0, "${Timeline.count(c)} moments remembered") }
        test("music practice") {
            if (c.packageManager.getLaunchIntentForPackage(Actions.YT_MUSIC) == null) Test("music practice", true, "no YouTube Music")
            else Apps.youtubeId("Ordinary Alex Warren")?.let { Test("music practice", true, "can find songs") } ?: Test("music practice", false, "couldn't look up a song (no signal, or YouTube changed)")
        }
        try { for (h in Health.items(c)) if (!h.ok && h.key != "live") tests.add(Test(h.name, false, h.why)) } catch (_: Exception) {}

        // self-fixing: what went wrong today becomes a fix
        val problems = Problems.all(p).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { System.currentTimeMillis() - it.optLong("at") < 26 * 3600_000L }
        val crashes = try { Trace.text(c, 60_000).lines().filter { it.contains(" crash ") } } catch (_: Exception) { emptyList() }
        val grouped = problems.groupBy { it.optString("t").replace(Regex("\\d+"), "#").take(70) }.filter { it.value.size >= 2 || it.key.contains("crash", true) }
        var proposed = 0
        for ((k, v) in grouped.entries.take(3)) {
            if (Upgrade.addProposal(c, "build", "Fix: ${k.take(60)}", "This happened ${v.size} time${if (v.size == 1) "" else "s"} today: ${v.last().optString("t").take(160)}")) proposed++
        }
        if (crashes.isNotEmpty() && Upgrade.addProposal(c, "build", "Fix a crash", "Jarvis crashed ${crashes.size} time${if (crashes.size == 1) "" else "s"}: ${crashes.last().takeLast(200)}")) proposed++

        // watching his own speed and hearing, and tuning himself
        val speed = try { speed(Trace.text(c, 90_000).lines()) } catch (_: Exception) { JSONObject() }
        val tuned = ArrayList<String>()
        try {
            val cfg = Brain(c).config()
            if (speed.optInt("falseBarge") >= 3) {
                val v = (cfg.optDouble("barge_ratio", 3.0) + 0.5).coerceAtMost(6.0)
                Builder.run(c, "change_setting", JSONObject().put("key", "barge_ratio").put("value", v).put("why", "stopped talking for no reason ${speed.optInt("falseBarge")} times"), false); tuned.add("less jumpy about being talked over")
            }
            if (speed.optInt("cutOffs") >= 3) {
                val v = (cfg.optDouble("ear_fast_end_ms", 450.0) + 50).coerceAtMost(900.0)
                Builder.run(c, "change_setting", JSONObject().put("key", "ear_fast_end_ms").put("value", v).put("why", "cut him off ${speed.optInt("cutOffs")} times"), false); tuned.add("waits a touch longer before answering")
            }
        } catch (_: Exception) {}

        // weekly: tidy his memory
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && !Claude.nearBudget(c, 0.85)) try {
            Agents.run(c, "memory", "Tidy what you know about him: read memory_list, merge duplicates and fix anything outdated or contradicted with memory_fix. Never invent anything.", false)
        } catch (_: Exception) {}

        val failed = tests.filter { !it.ok }
        val rep = JSONObject().put("at", System.currentTimeMillis()).put("ms", System.currentTimeMillis() - t0)
            .put("tests", JSONArray().apply { tests.forEach { put(JSONObject().put("n", it.name).put("ok", it.ok).put("d", it.detail).put("fixed", it.fixed)) } })
            .put("proposed", proposed).put("tuned", JSONArray(tuned)).put("speed", speed)
        p.sp.edit().putString("night_report", rep.toString()).apply()
        Trace.log(c, "selfcare", "${tests.size - failed.size}/${tests.size} ok; fixes proposed $proposed; tuned ${tuned.joinToString()}; speed ${speed}")
        Timeline.add(c, "selfcare", "Checked myself: ${tests.size - failed.size} of ${tests.size} fine" + (if (failed.isNotEmpty()) "; problems: " + failed.joinToString { it.name } else "") + (if (tuned.isNotEmpty()) "; tuned: " + tuned.joinToString() else ""))
        if (failed.any { !it.fixed } || proposed > 0 || tuned.isNotEmpty()) {
            val parts = ArrayList<String>()
            failed.filter { !it.fixed }.takeIf { it.isNotEmpty() }?.let { parts.add("these need a look: " + it.joinToString("; ") { t -> "${t.name} (${t.detail})" }) }
            if (proposed > 0) parts.add("I wrote up $proposed fix${if (proposed == 1) "" else "es"} for myself, waiting for your OK in His mind")
            if (tuned.isNotEmpty()) parts.add("I tuned myself: " + tuned.joinToString(" and "))
            Mind.tellLater(c, "Sir, I checked myself overnight. " + parts.joinToString("; ").replaceFirstChar { it.uppercase() } + ".")
        }
        LogBus.changed()
    }

    /**
     * From the flight log: how fast he answered (him finishing to Jarvis's first word), how often he stopped for no
     * reason when he thought he was being talked over, and how often he cut Marcus off. Pure, unit-tested.
     */
    fun speed(lines: List<String>): JSONObject {
        val re = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d)\\s+(?:\\+[\\d.]+\\s+)?(\\S+)\\s+(.*)$")
        val f = SimpleDateFormat("MM-dd HH:mm:ss.S", Locale.US)
        class L(val at: Long, val k: String, val t: String)
        val ls = lines.mapNotNull { re.find(it)?.let { m -> try { L(f.parse(m.groupValues[1])!!.time, m.groupValues[2], m.groupValues[3]) } catch (_: Exception) { null } } }
        val waits = ArrayList<Long>(); var falseBarge = 0; var cutOffs = 0
        for ((i, l) in ls.withIndex()) {
            if (l.k == "heard") ls.drop(i + 1).take(12).firstOrNull { it.k == "voice" && it.t.startsWith("first word") }?.let { if (it.at - l.at in 0..30_000) waits.add(it.at - l.at) }
            if (l.k == "interrupt" && l.t.contains("talked over")) { val next = ls.drop(i + 1).take(4); if (next.none { it.k == "heard" && it.at - l.at < 8000 }) falseBarge++ }
            if (l.k == "ear" && l.t.startsWith("finished early")) { val next = ls.drop(i + 1).take(6); if (next.any { it.k == "speech" && it.at - l.at in 0..2500 }) cutOffs++ }
        }
        val sorted = waits.sorted()
        return JSONObject().put("turns", waits.size).put("medianMs", if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]).put("falseBarge", falseBarge).put("cutOffs", cutOffs)
    }

    // ---------------- morning: rehearse the day, and the money early-warning ----------------
    fun maybeMorning(c: Context) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h !in 5..11 || !Mind.on(c)) return
        val p = Prefs(c); val today = day.format(Date())
        if (p.sp.getString("morning_done", "") == today) return
        p.sp.edit().putString("morning_done", today).apply()
        try {
            clashes(DayPlan.pick(DayPlan.items(c), System.currentTimeMillis(), 20)).firstOrNull()?.let { Mind.tellLater(c, "Sir, heads up for today: $it") }
            if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY || p.sp.getString("warn_week", "") == "") Forecast.warning(c)?.let { w ->
                p.sp.edit().putString("warn_week", today).apply(); Mind.tellLater(c, w) }
        } catch (e: Exception) { Problems.log(c, "morning rehearsal: ${e.message}") }
    }

    /** Things in today's plan that run into each other (within 20 minutes, one of them fixed in time). Pure. */
    fun clashes(items: List<DayPlan.Item>): List<String> {
        val out = ArrayList<String>()
        val clock = SimpleDateFormat("h:mm a", Locale.US)
        val fixed = { i: DayPlan.Item -> !i.text.startsWith("Feed") && !i.text.startsWith("Follow up") && !i.text.startsWith("Arriving") && !i.text.startsWith("List") }
        for ((a, b) in items.sortedBy { it.at }.zipWithNext()) {
            if (b.at - a.at > 20 * 60_000L || a.text == b.text) continue
            if (!fixed(a) && !fixed(b)) continue
            val feed = listOf(a, b).firstOrNull { it.text.startsWith("Feed") }
            val other = listOf(a, b).first { it !== feed }
            out.add(if (feed != null) "${feed.text.lowercase().replaceFirstChar { it.uppercase() }} lands right on ${other.text.lowercase()} at ${clock.format(Date(other.at))}; maybe feed him a little early."
                else "${a.text} at ${clock.format(Date(a.at))} and ${b.text.lowercase()} at ${clock.format(Date(b.at))} are back to back.")
        }
        return out
    }
}

/**
 * Early warnings for money: looks four weeks ahead at what usually comes in (his recent dashing), what usually goes
 * out (his recent spending) and the bills that are due, and flags a week that looks tight before it happens.
 */
object Forecast {
    /** Weeks ahead as (week start, coming in, going out, bills). Pure. */
    fun weeks(weeklyIn: Double, weeklyOut: Double, bills: List<Pair<Long, Double>>, start: Long, n: Int = 4): List<DoubleArray> =
        (0 until n).map { w -> val from = start + w * 7 * 86_400_000L; val due = bills.filter { it.first >= from && it.first < from + 7 * 86_400_000L }.sumOf { it.second }
            doubleArrayOf(from.toDouble(), weeklyIn, weeklyOut, due) }

    /** The first week where bills plus usual spending clearly beat what usually comes in. Pure. */
    fun tight(weeks: List<DoubleArray>): DoubleArray? = weeks.firstOrNull { it[3] > 0 && it[1] - it[2] - it[3] < -20 }

    fun warning(c: Context): String? {
        val p = Prefs(c); val now = System.currentTimeMillis(); val d = 86_400_000L
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val earn = Earnings.all(p).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { (ymd.parse(it.optString("d"))?.time ?: 0L) > now - 28 * d }.sumOf { it.optDouble("a") } / 4.0
        val spendTx = Spending.all(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { now - it.optLong("at") < 28 * d && it.optString("c") !in setOf("income", "bills", "transfers") }.sumOf { it.optDouble("a") } / 4.0
        val exp = Expenses.all(p).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { now - it.optLong("at") < 28 * d }.sumOf { it.optDouble("a") } / 4.0
        if (earn <= 0) return null
        val bills = Bills.all(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { !it.optBoolean("paid") && it.has("amount") }.map { it.optLong("due") to it.optDouble("amount") }
        val w = tight(weeks(earn, spendTx + exp, bills, now)) ?: return null
        val wk = SimpleDateFormat("MMMM d", Locale.US).format(Date(w[0].toLong()))
        return "Sir, an early warning: the week of $wk looks tight. About ${Earnings.money(Math.round(w[3]).toDouble())} in bills, and after your usual spending you'd normally have about ${Earnings.money(Math.round(w[1] - w[2]).toDouble())} left from dashing. Worth planning for now."
    }
}
