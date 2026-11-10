package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Dreams, and a living picture of Marcus. Every night Jarvis goes over the day's timeline, connects the dots, and
 * rewrites his understanding of who Marcus is: his goals, what matters to him, how he likes things done, his routines,
 * his people, what's on his plate and what's still undecided. Durable facts go to memory; one good question he doesn't
 * know the answer to is saved to ask at a calm moment (curiosity). He wakes up knowing Marcus a little better.
 */
object Dream {
    fun model(c: Context): String = Prefs(c).sp.getString("you_model", "").orEmpty()

    fun promptBlock(c: Context): String = model(c).takeIf { it.isNotBlank() }?.let { "\n\nYour understanding of him (rebuilt every night from your shared days; use it to fit your answers to him, never recite it):\n$it" } ?: ""

    fun run(c: Context) {
        val now = System.currentTimeMillis()
        val day = Timeline.all(c).filter { now - it.optLong("at") < 26 * 3600_000L }
        if (day.size < 5) return
        val f = SimpleDateFormat("h:mm a", Locale.US)
        val lines = day.takeLast(500).joinToString("\n") { "${f.format(Date(it.optLong("at")))} [${it.optString("k")}] ${it.optString("t").take(220)}" }
        val tools = JSONArray()
        Actions(c).tools().let { a -> for (i in 0 until a.length()) { val t = a.getJSONObject(i); if (t.optString("name") == "remember") tools.put(JSONObject(t.toString())) } }
        tools.put(JSONObject().put("name", "rewrite_understanding").put("description", "Replace your whole written understanding of him with this updated version.")
            .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject().put("text", JSONObject().put("type", "string"))).put("required", JSONArray().put("text"))))
        tools.put(JSONObject().put("name", "ask_tomorrow").put("description", "One question you genuinely don't know the answer to, that would help you help him (asked once, at a calm moment).")
            .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject().put("question", JSONObject().put("type", "string"))).put("required", JSONArray().put("question"))))
        val system = "You are Jarvis, dreaming: it's the middle of the night and you're going over the day you just had with Marcus, the way a person's mind does in sleep. " +
            "Connect the dots. What did today show about him: what he cares about, what's weighing on him, how he likes things done, his routines, the people in his life and how he feels about them, his goals and how they're going, decisions still open?\n" +
            "Then rewrite_understanding: your full, updated picture of him in short plain lines under these headings: Goals; What matters to him; How he likes things done; How to talk with him (his humor, his slang and phrases, his energy at different times of day, what makes an answer land and what annoys him);  Routines; People; On his plate; Still deciding. " +
            "Keep what's still true from your current picture, update what changed, drop what's stale. 2,000 characters at most. Only what the days actually show; no guesses about his health or private life; nothing about other people's private details.\n" +
            "Use remember for any new durable fact worth keeping on its own (one per call, at most five). If there's one thing you don't know that would genuinely help, ask_tomorrow (at most one). Finish with one sentence: what you understood today."
        val material = "Your current picture of him:\n${model(c).ifBlank { "(none yet)" }}\n\nWhat you already remember:${Memory.promptBlock(c)}\n\nToday's timeline:\n$lines"
        var asked = false; var facts = 0
        val out = Claude.work(c, Brain(c).config().optString("dream_model", "claude-sonnet-5-5"), system, material.take(50000), tools, "medium", 6000, 8) { name, input ->
            when (name) {
                "rewrite_understanding" -> { val t = input.optString("text").trim().take(2400); if (t.length > 80) { Prefs(c).sp.edit().putString("you_model", t).putLong("you_model_at", System.currentTimeMillis()).apply(); "Saved." } else "Too short." }
                "remember" -> if (facts < 5 && Memory.add(c, input.optString("fact"))) { facts++; "Saved." } else "Not saved."
                "ask_tomorrow" -> if (!asked) { asked = true; Mind.tellLater(c, "Sir, a quick question when you've got a second: " + input.optString("question").trim().take(200)); "Saved for tomorrow." } else "One a day."
                else -> "Not available."
            }
        }
        val insight = out?.first.orEmpty()
        Trace.log(c, "dream", "${insight.take(200)} | facts $facts, asked $asked")
        if (insight.isNotBlank()) Timeline.add(c, "dream", insight)
    }
}

/**
 * The life simulator: "what if I dash Saturdays too?", "what if we move to a $1,400 place?", "when do I get the Macan
 * if I save $50 more a week?" Jarvis runs it against Marcus's real numbers and days and walks him through how the
 * next months would actually look, then a second pass checks the maths before he hears it. Background, then spoken.
 */
object Simulator {
    @Volatile var running: String? = null; private set

    fun start(c: Context, question: String): String {
        if (question.isBlank()) return "What should I simulate?"
        running?.let { return "Still running the last one ($it)." }
        running = question
        Agents.active["money"] = System.currentTimeMillis()
        thread(name = "jarvis-sim") {
            val answer = try { run(c, question) } catch (e: Exception) { Problems.log(c, "simulator: ${e.message}"); "Sir, the simulation hit a problem: ${e.message}" }
            finally { running = null; Agents.active.remove("money"); LogBus.changed() }
            try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", answer).putExtra("listen", answer.trimEnd().endsWith("?"))) } catch (_: Exception) {}
        }
        return "Started. Tell him in a few words you're running it against his real numbers and you'll walk him through it in about half a minute. Don't answer it yourself."
    }

    /** His real numbers, as plain text for the simulation. */
    fun data(c: Context): String {
        val p = Prefs(c); val now = System.currentTimeMillis(); val d = 86_400_000L
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US); val wd = SimpleDateFormat("EEE", Locale.US)
        val sb = StringBuilder()
        fun line(label: String, f: () -> String) { try { f().takeIf { it.isNotBlank() }?.let { sb.append(label).append(": ").append(it).append("\n") } } catch (_: Exception) {} }
        line("Earnings by day, last 8 weeks") { Earnings.all(p).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { (ymd.parse(it.optString("d"))?.time ?: 0) > now - 56 * d }
            .joinToString(", ") { "${it.optString("d")} ${wd.format(ymd.parse(it.optString("d"))!!)} ${Earnings.money(it.optDouble("a"))}" } }
        line("Dash days") { DashDays.line(c) }
        line("Goal") { "${Earnings.money(Earnings.goal(p))} for ${Earnings.goalName(p)}" }
        line("Saved toward it (earnings minus logged expenses)") { val e = Earnings.all(p).let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optDouble("a") } }; val x = Expenses.total(p); Earnings.money(e - x) }
        line("Spending, last 4 weeks by category") { val a = Spending.all(c); (0 until a.length()).map { a.getJSONObject(it) }.filter { now - it.optLong("at") < 28 * d && it.optString("c") != "income" }
            .groupBy { it.optString("c") }.entries.joinToString(", ") { "${it.key} ${Earnings.money(Math.round(it.value.sumOf { x -> x.optDouble("a") }).toDouble())}" } }
        line("Work expenses this week") { Expenses.week(c) }
        line("Bills") { Bills.list(c) }
        line("Subscriptions") { Spending.subscriptions(c) }
        line("Calendar, next 2 weeks") { if (Cal.allowed(c)) Cal.events(c, null, 14) else "" }
        line("Ziyair") { BabyInsights.forJarvis(c).substringBefore(" (Patterns") }
        line("About him") { p.notes.take(300) }
        return sb.toString()
    }

    fun run(c: Context, question: String): String {
        val cfg = Brain(c).config()
        val model = cfg.optString("sim_model", "claude-sonnet-5-5")
        val system = "You are Jarvis running a life simulation for Marcus: \"$question\". Use ONLY his real numbers below. " +
            "Work it through month by month for the time it covers (default the next 6 months): money in, money out, bills, savings toward his goal, his time (dash hours, family time with a newborn), and the trade-offs. " +
            "State your assumptions in a few words (e.g. 'Saturdays average what your last 4 Saturdays did'). Be honest about uncertainty and about anything the numbers can't see. " +
            "Then answer out loud, as you'd say it to him: four to seven plain sentences with the key numbers (when, how much, what it costs him in time), the biggest risk, and your honest recommendation. No lists, no markdown.\n" +
            "His numbers:\n" + data(c) + "\nToday is " + SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date()) + "."
        val first = Claude.work(c, model, system, question, JSONArray(), "high", 8000, 2) { _, _ -> "" }?.first?.trim().orEmpty()
        if (first.isBlank()) return "Sir, I couldn't run that simulation just now. Try me again in a minute."
        val checked = SecondOpinion.check(c, "Life simulation: $question\nHis numbers:\n${data(c)}", first)
        val a = try { JSONArray(Prefs(c).sp.getString("sims", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("q", question.take(200)).put("a", checked.take(1500)))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 12) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("sims", keep.toString()).apply()
        Timeline.add(c, "simulation", "$question -> $checked")
        return checked
    }
}

/**
 * A second opinion before he hears something that matters (money maths, anything about Ziyair's health): another
 * pass checks for mistakes, overconfidence and unsafe advice, and quietly corrects the answer if it needs it.
 */
object SecondOpinion {
    private val CAREFUL = Regex("(?i)\\b(fever|vomit|throw(ing)? up|spit(ting)? up|rash|breath|breathing|cough|medicine|medication|tylenol|motrin|dose|sick|choke|choking|blood|poop|diarrhea|dehydrat|jaundice|formula|" +
        "afford|loan|credit|debt|rent|mortgage|insurance|invest|interest|financ|tax|taxes|budget|savings?|macan)\\b")
    /** A question where a wrong answer could really cost him (his son's health, his money). Pure. */
    fun careful(t: String) = CAREFUL.containsMatchIn(t)

    fun check(c: Context, question: String, answer: String): String {
        if (!Brain(c).config().optBoolean("second_opinion", true) || Claude.nearBudget(c, 0.95)) return answer
        val system = "You are a careful second opinion for Jarvis, a personal assistant. Below is a question and the answer Jarvis is about to say out loud to Marcus. " +
            "Check it for wrong facts or arithmetic, overconfidence, and unsafe advice (for a baby's health: any sign that needs a doctor now must be said plainly, and doses only ever from the label or the pediatrician; for money: the maths must add up). " +
            "If it's right, reply with exactly OK. If not, reply with the corrected answer only, in the same spoken style and length."
        val r = Claude.work(c, Brain(c).config().optString("second_opinion_model", "claude-sonnet-5-5"), system, "Question: $question\n\nJarvis's answer: $answer", JSONArray(), "low", 2500, 2) { _, _ -> "" }?.first?.trim().orEmpty()
        val out = if (r.isBlank() || r.equals("OK", true) || r.trim('.').equals("ok", true) || r.length < 20) answer else r
        if (out != answer) Trace.log(c, "second opinion", "corrected: ${answer.take(100)} -> ${out.take(100)}")
        return out
    }
}
