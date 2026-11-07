package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * New powers, all in Jarvis's act-first spirit:
 *  - Council: his agents weigh a big decision from different sides in the background; Marcus only hears the verdict.
 *  - Goals: money autopilot. Every payout he logs is split into buckets (the Meta glasses first, taxes, then the Macan).
 *  - Radar: learns which days pay best and nudges him when a good window is opening.
 *  - Miles: dash miles, the tax write-off they add up to, and car care (oil changes) from the odometer.
 *  - Lessons: "teach me anything" as five-minute daily lessons, offered at the start of a drive.
 *  - Practice: role-plays a hard conversation with him on the fast brain until he says stop.
 *  - Ideas: catches a half-baked idea and offers to turn it into a plan.
 *  - Bill fighter: when a bill goes up, offers to draft the "why did this go up / lower my rate" message (sent only on his yes).
 */
private fun ask(c: Context, system: String, user: String, maxTokens: Int, smart: Boolean = false): String? {
    val cfg = Brain(c).config()
    val model = if (smart) cfg.optString("smart_model", "claude-sonnet-5-5") else cfg.optString("agent_model", "claude-haiku-5-5")
    val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
    val resp = Claude.send(c, body) ?: return null
    try { Spend.record(c, cfg, model, resp.optJSONObject("usage")) } catch (_: Exception) {}
    val a = resp.optJSONArray("content") ?: return null
    return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim().ifBlank { null }
}

object Council {
    private val SEATS = listOf(
        "Money" to "You care about his money: the cost, what it does to his savings goals (the Meta glasses, the Macan), and the cheapest way to get what he wants.",
        "Family" to "You care about his family and his time: his newborn son Ziyair, his girlfriend, sleep, and not burning him out.",
        "Future" to "You care about where he'll be in a year: his goals, the business he's building (Pursuit), and what he'll wish he'd done.")

    /** The whole council, quietly: three short takes in parallel, then one verdict in two or three spoken sentences. Blocking. */
    fun decide(c: Context, question: String): String {
        if (question.isBlank()) return "What's the decision?"
        if (Claude.nearBudget(c, 0.95)) return "I'm at my monthly Claude budget, so the council can't sit right now."
        val about = (Prefs(c).notes.take(600) + Memory.promptBlock(c).take(800))
        val takes = arrayOfNulls<String>(SEATS.size)
        val threads = SEATS.mapIndexed { i, (name, lens) -> thread { takes[i] = try { ask(c, "You sit on Marcus's private council as $name. $lens Give your honest take on his decision in two or three sentences, and say which way you lean. About him: $about", question, 160) } catch (_: Exception) { null }?.let { "$name: $it" } } }
        threads.forEach { it.join() }
        val heard = takes.filterNotNull()
        if (heard.isEmpty()) return "The council couldn't meet just now (no connection to Claude)."
        Timeline.add(c, "thought", "Council on \"${question.take(80)}\": ${heard.joinToString(" | ").take(400)}")
        return ask(c, "You're Jarvis. Your council of agents argued Marcus's decision out quietly; he only wants your verdict. In two or three spoken sentences, starting 'Sir,': " +
            "say what you'd do and the one or two reasons that decided it. If they disagreed, name the trade-off in a few words. No lists.",
            "His decision: $question\n\nWhat the council said:\n" + heard.joinToString("\n"), 220, smart = true) ?: heard.joinToString(" ")
    }
}

object Goals {
    // ---------------- pure (unit-tested) ----------------
    /** Split one payout across goals in order: each takes its share of what's left until it's full. Returns name -> dollars. */
    fun split(amount: Double, goals: List<JSONObject>): Map<String, Double> {
        var left = amount; val out = LinkedHashMap<String, Double>()
        for (g in goals) {
            if (left <= 0.0) break
            val room = g.optDouble("target", 0.0) - g.optDouble("saved", 0.0)
            if (room <= 0.0 && g.optDouble("target", 0.0) > 0.0) continue
            val share = g.optDouble("share", 0.0).coerceIn(0.0, 1.0)
            val take = minOf(left * share, if (g.optDouble("target", 0.0) > 0.0) room else Double.MAX_VALUE)
            if (take > 0.0) { out[g.optString("name")] = Math.round(take * 100) / 100.0; left -= take }
        }
        return out
    }

    fun all(c: Context): JSONArray {
        val raw = Prefs(c).sp.getString("goals2", null)
        if (raw != null) return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        // first time: the Meta glasses he's saving for, money set aside for taxes, and the rest heads to the Macan
        val a = JSONArray()
            .put(JSONObject().put("name", "Meta glasses").put("target", 380.0).put("saved", 0.0).put("share", 0.5))
            .put(JSONObject().put("name", "Taxes").put("target", 0.0).put("saved", 0.0).put("share", 0.15))
        save(c, a); return a
    }
    private fun save(c: Context, a: JSONArray) = Prefs(c).sp.edit().putString("goals2", a.toString()).apply()

    /** After he logs a payout: fill the buckets, and say so in one line. */
    @Synchronized fun allocate(c: Context, amount: Double): String {
        if (amount <= 0) return ""
        val a = all(c); val list = (0 until a.length()).map { a.getJSONObject(it) }
        val parts = split(amount, list)
        if (parts.isEmpty()) return ""
        for (g in list) parts[g.optString("name")]?.let { g.put("saved", Math.round((g.optDouble("saved") + it) * 100) / 100.0) }
        save(c, a); LogBus.changed()
        val glasses = list.firstOrNull { it.optString("name").contains("glasses", true) }
        val line = parts.entries.joinToString(", ") { "${Earnings.money(it.value)} to ${it.key.lowercase()}" }
        val g = glasses?.let { if (it.optDouble("saved") >= it.optDouble("target")) " The glasses fund is full, sir: ${Earnings.money(it.optDouble("target"))}." else " Glasses: ${Earnings.money(it.optDouble("saved"))} of ${Earnings.money(it.optDouble("target"))}." }.orEmpty()
        return "Set aside $line.$g"
    }

    fun add(c: Context, name: String, target: Double, share: Double): String {
        if (name.isBlank()) return "Needs a name."
        val a = all(c); val l = (0 until a.length()).map { a.getJSONObject(it) }
        val hit = l.firstOrNull { it.optString("name").equals(name.trim(), true) }
        val g = hit ?: JSONObject().put("name", name.trim().take(30)).put("saved", 0.0).also { a.put(it) }
        g.put("target", maxOf(0.0, target)).put("share", share.coerceIn(0.0, 1.0))
        save(c, a); LogBus.changed()
        return "${g.optString("name")}: ${if (target > 0) "goal ${Earnings.money(target)}, " else ""}${Math.round(share * 100)}% of each payout."
    }

    fun spend(c: Context, name: String, amount: Double): String {
        val a = all(c); val g = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").contains(name.trim(), true) } ?: return "No bucket called $name."
        g.put("saved", maxOf(0.0, g.optDouble("saved") - amount)); save(c, a); LogBus.changed()
        return "Took ${Earnings.money(amount)} out of ${g.optString("name")}; ${Earnings.money(g.optDouble("saved"))} left."
    }

    fun status(c: Context): String {
        val a = all(c)
        return (0 until a.length()).map { a.getJSONObject(it) }.joinToString("; ") { g ->
            val t = g.optDouble("target"); "${g.optString("name")} ${Earnings.money(g.optDouble("saved"))}" + if (t > 0) " of ${Earnings.money(t)}" else ""
        } + ". The rest of each payout goes to ${Earnings.goalName(Prefs(c))}."
    }
}

object Radar {
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DAYS = listOf("", "Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")

    /** Average earnings by weekday from (yyyy-MM-dd, amount) days he worked. Pure. */
    fun byWeekday(days: List<Pair<String, Double>>): Map<Int, Double> {
        val cal = Calendar.getInstance(); val sums = HashMap<Int, MutableList<Double>>()
        for ((d, a) in days) { if (a <= 0) continue; cal.time = try { ymd.parse(d) } catch (_: Exception) { null } ?: continue; sums.getOrPut(cal.get(Calendar.DAY_OF_WEEK)) { ArrayList() }.add(a) }
        return sums.filter { it.value.size >= 2 }.mapValues { it.value.average() }
    }

    private fun days(c: Context): List<Pair<String, Double>> {
        val m = HashMap<String, Double>(); val a = Earnings.all(Prefs(c))
        for (i in 0 until a.length()) a.getJSONObject(i).let { m[it.optString("d")] = (m[it.optString("d")] ?: 0.0) + it.optDouble("a") }
        val since = System.currentTimeMillis() - 56 * 86_400_000L
        return m.filter { (k, _) -> (try { ymd.parse(k)?.time } catch (_: Exception) { null } ?: 0L) > since }.map { it.key to it.value }
    }

    fun line(c: Context): String {
        val w = byWeekday(days(c)); if (w.isEmpty()) return "I need a couple of weeks of logged earnings to see which days pay best."
        val best = w.entries.sortedByDescending { it.value }.take(2)
        return "Your best days lately: " + best.joinToString(" and ") { "${DAYS[it.key]} (about ${Earnings.money(Math.round(it.value).toDouble())})" } + "."
    }

    /** About 3:30 on one of his best days, if he's not out yet: one nudge. */
    fun tick(c: Context) {
        val cal = Calendar.getInstance(); val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        if (h < 15.5 || h > 16.5 || Shift.active(c)) return
        val today = ymd.format(Date()); val p = Prefs(c).sp
        if (p.getString("radar_day", "") == today) return
        val w = byWeekday(days(c)); if (w.size < 3) return
        val avg = w.values.average(); val mine = w[cal.get(Calendar.DAY_OF_WEEK)] ?: return
        if (mine < avg * 1.15) return
        p.edit().putString("radar_day", today).apply()
        Shift.say(c, "Sir, ${DAYS[cal.get(Calendar.DAY_OF_WEEK)].removeSuffix("s")}s have been paying you about ${Earnings.money(Math.round(mine).toDouble())}, your best night. Good window from 5 if you want it.", false)
    }
}

object Miles {
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun rate(c: Context) = Brain(c).config().optDouble("mileage_rate", 0.70)   // the IRS standard rate per business mile; he can change it
    /** Miles until the next oil change from where the odometer is and when it was last done. Pure. */
    fun toOil(odometer: Double, lastOil: Double, every: Double = 5000.0) = (lastOil + every) - odometer

    fun log(c: Context, miles: Double): String {
        if (miles <= 0 || miles > 1000) return "How many miles was it?"
        val p = Prefs(c).sp; val year = Calendar.getInstance().get(Calendar.YEAR)
        val ytd = (if (p.getInt("miles_year", 0) == year) p.getFloat("miles_ytd", 0f) else 0f) + miles.toFloat()
        val odo = p.getFloat("odometer", 0f).let { if (it > 0f) it + miles.toFloat() else 0f }
        p.edit().putInt("miles_year", year).putFloat("miles_ytd", ytd).putFloat("odometer", odo).putString("miles_last_day", ymd.format(Date())).apply()
        Timeline.add(c, "expense", "Drove ${miles.toInt()} dash miles")
        val off = ytd * rate(c)
        val oil = if (odo > 0f) toOil(odo.toDouble(), p.getFloat("last_oil", odo).toDouble()) else null
        if (oil != null && oil < 300 && !p.getBoolean("oil_warned", false)) { p.edit().putBoolean("oil_warned", true).apply(); Mind.tellLater(c, "Sir, your oil change is due in about ${maxOf(0, oil.toInt())} miles.") }
        return "Logged ${miles.toInt()} miles. This year: ${ytd.toInt()} dash miles, about ${Earnings.money(Math.round(off).toDouble())} off your taxable income at ${Math.round(rate(c) * 100)} cents a mile." +
            (oil?.let { if (it < 600) " Oil change due in about ${maxOf(0, it.toInt())} miles." else "" } ?: "")
    }

    fun car(c: Context, odometer: Double?, lastOilAt: Double?): String {
        val p = Prefs(c).sp; val e = p.edit()
        if (odometer != null && odometer > 0) e.putFloat("odometer", odometer.toFloat())
        if (lastOilAt != null && lastOilAt > 0) e.putFloat("last_oil", lastOilAt.toFloat()).putBoolean("oil_warned", false)
        e.apply()
        val odo = p.getFloat("odometer", 0f).toDouble(); if (odo <= 0) return "Tell me the odometer reading and I'll keep track."
        val last = p.getFloat("last_oil", 0f).toDouble()
        return "Car: ${odo.toInt()} miles." + if (last > 0) " Next oil change in about ${maxOf(0, toOil(odo, last).toInt())} miles." else " When was the last oil change (odometer reading)?"
    }

    fun status(c: Context): String {
        val p = Prefs(c).sp; val year = Calendar.getInstance().get(Calendar.YEAR)
        val ytd = if (p.getInt("miles_year", 0) == year) p.getFloat("miles_ytd", 0f) else 0f
        return "This year: ${ytd.toInt()} dash miles, worth about ${Earnings.money(Math.round(ytd * rate(c)).toDouble())} as a mileage deduction." + if (ytd == 0f) " Tell me your miles after each shift (the Dasher app shows them)." else ""
    }
}

object Lessons {
    fun start(c: Context, topic: String): String {
        if (topic.isBlank()) return "What do you want to learn?"
        Prefs(c).sp.edit().putString("lesson_topic", topic.trim().take(80)).putInt("lesson_n", 0).putString("lesson_notes", "").apply()
        return "Got it: ${topic.trim()}, five minutes a day. I'll offer a lesson when you start driving, or just say 'next lesson'."
    }
    /** The next five-minute lesson, spoken. Builds on what's been covered. Blocking. */
    fun next(c: Context): String {
        val p = Prefs(c).sp; val topic = p.getString("lesson_topic", "").orEmpty()
        if (topic.isBlank()) return "No course going. Say 'teach me' and what you want to learn."
        val n = p.getInt("lesson_n", 0) + 1; val covered = p.getString("lesson_notes", "").orEmpty()
        val text = ask(c, "You're Jarvis teaching Marcus $topic, five minutes a day, out loud while he drives. This is lesson $n. Already covered: ${covered.ifBlank { "nothing yet" }}. " +
            "Teach the next small step in plain spoken English: about 150 words, one idea, one real example, then one quick question for him to answer out loud. No lists or symbols.", "Lesson $n", 400) ?: return "I couldn't reach Claude for the lesson just now."
        p.edit().putInt("lesson_n", n).putString("lesson_notes", (covered + " | " + text.take(90)).takeLast(700)).apply()
        Timeline.add(c, "journal", "Lesson $n in $topic")
        return text
    }
    fun offerLine(c: Context): String? = Prefs(c).sp.getString("lesson_topic", "")?.takeIf { it.isNotBlank() }?.let { "Want today's five minutes of $it on the drive?" }
}

object Practice {
    fun start(c: Context, scenario: String): String {
        if (scenario.isBlank()) return "Which conversation do you want to practise?"
        Prefs(c).sp.edit().putString("roleplay", scenario.trim().take(200)).putLong("roleplay_until", System.currentTimeMillis() + 25 * 60_000L).apply()
        return "Practice is on. Play the other person in \"$scenario\" from your very next reply, staying in character until he says stop, then tell him in two sentences what went well and one thing to sharpen."
    }
    fun stop(c: Context): String { Prefs(c).sp.edit().remove("roleplay").apply(); return "Practice over." }
    /** For the fast brain while practice is on. */
    fun promptBlock(c: Context): String {
        val p = Prefs(c).sp; val s = p.getString("roleplay", "").orEmpty()
        if (s.isBlank() || System.currentTimeMillis() > p.getLong("roleplay_until", 0L)) return ""
        return "\nPRACTICE MODE: he's rehearsing \"$s\". Play the other person realistically (push back like they would). Stay in character. When he says stop or that he's done, step out and give two sentences of feedback."
    }
    fun on(c: Context) = promptBlock(c).isNotBlank()
}

object Ideas {
    fun catchIt(c: Context, text: String): String {
        if (text.isBlank()) return "What's the idea?"
        Timeline.add(c, "thought", "Idea: ${text.take(200)}")
        Mind.offer(c, "Turn this idea of his into a short, real plan (first three steps, rough cost, and what to do this week): ${text.take(200)}")
        return "Caught it. Say yes in His mind when you want it turned into a plan."
    }
}

object BillFighter {
    /** A bill that went up by more than 10% (and at least $3): worth asking why. Pure. */
    fun wentUp(old: Double?, new: Double?) = old != null && new != null && old > 0 && new > old * 1.10 && new - old >= 3.0
    fun check(c: Context, name: String, old: Double?, new: Double?) {
        if (!wentUp(old, new)) return
        Mind.offer(c, "Draft a polite message to $name asking why the bill went from ${Earnings.money(old!!)} to ${Earnings.money(new!!)} and asking for the old rate or a cheaper plan. Show it to him; it only goes out on his yes.")
        Mind.tellLater(c, "Sir, $name went up from ${Earnings.money(old)} to ${Earnings.money(new)}. I can draft a message to get it lowered; it's waiting in His mind.")
    }
}

object NightWatch {
    /** First time he talks to Jarvis after 6 AM: what came in overnight, in a line (or all clear). */
    fun maybe(c: Context) {
        val cal = Calendar.getInstance(); val h = cal.get(Calendar.HOUR_OF_DAY)
        if (h < 6 || h >= 11 || !Brain(c).config().optBoolean("night_watch", true)) return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()); val p = Prefs(c).sp
        if (p.getString("nightwatch_day", "") == day || Claude.nearBudget(c, 0.85)) return
        p.edit().putString("nightwatch_day", day).apply()
        thread { try { Triage.run(c)?.let { Mind.tellLater(c, "Overnight: " + it.removePrefix("Sir, ")) } ?: Mind.tellLater(c, "All clear overnight, sir.") } catch (_: Exception) {} }
    }
}
