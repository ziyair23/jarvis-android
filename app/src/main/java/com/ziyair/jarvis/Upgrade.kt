package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Skills Jarvis built for himself: a name, the phrases that trigger it, and a few steps using his existing safe tools
 * ("heading out" = driving mode, music, weather). Run instantly on the phone when he says one of the phrases.
 * Never sends, calls, pays or deletes anything.
 */
object Skills {
    /** What a skill step may use: everyday, undoable things only. */
    val SAFE_STEPS = setOf("set_timer", "remind_me", "set_alarm", "list_add", "list_read", "lights", "play_music", "media_control", "driving_mode",
        "quiet_mode", "run_protocol", "open_app", "navigate", "get_weather", "baby_status", "earnings_summary", "briefing", "calendar_events",
        "dash_day", "follow_up_add", "follow_ups_list", "meal_tonight", "money_week", "bills_list", "appts_list", "packages_list", "status_report")

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("skills", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { Prefs(c).sp.edit().putString("skills", a.toString()).apply(); LogBus.changed() }

    /** Only steps that are allowed; null if nothing usable is left. Pure. */
    fun clean(steps: JSONArray): JSONArray? {
        val out = JSONArray()
        for (i in 0 until steps.length()) { val s = steps.optJSONObject(i) ?: continue; if (s.optString("tool") in SAFE_STEPS) out.put(JSONObject().put("tool", s.optString("tool")).put("input", s.optJSONObject("input") ?: JSONObject())) }
        return if (out.length() == 0) null else out
    }

    @Synchronized fun add(c: Context, skill: JSONObject): String {
        val steps = clean(skill.optJSONArray("steps") ?: JSONArray()) ?: return "No usable steps."
        val phrases = JSONArray(); skill.optJSONArray("phrases")?.let { for (i in 0 until it.length()) Reflex.norm(it.optString(i)).takeIf { p -> p.split(" ").size in 1..8 }?.let { p -> phrases.put(p) } }
        if (phrases.length() == 0) return "No trigger phrases."
        val a = all(c); val keep = JSONArray()
        for (i in 0 until a.length()) if (!a.getJSONObject(i).optString("name").equals(skill.optString("name"), true)) keep.put(a.get(i))
        keep.put(JSONObject().put("name", skill.optString("name").take(40)).put("phrases", phrases).put("steps", steps).put("line", skill.optString("line").take(120))
            .put("at", System.currentTimeMillis()).put("uses", 0))
        save(c, keep)
        return "Learned the ${skill.optString("name")} skill."
    }

    @Synchronized fun remove(c: Context, name: String) { val a = all(c); val keep = JSONArray(); for (i in 0 until a.length()) if (!a.getJSONObject(i).optString("name").equals(name, true)) keep.put(a.get(i)); save(c, keep) }

    /** The skill these exact words trigger, if any. Pure over the list. */
    fun match(a: JSONArray, t: String): JSONObject? {
        val n = Reflex.norm(t)
        for (i in 0 until a.length()) { val s = a.getJSONObject(i); val p = s.optJSONArray("phrases") ?: continue
            for (j in 0 until p.length()) if (p.optString(j) == n) return s }
        return null
    }

    fun run(c: Context, s: JSONObject): String {
        val actions = Actions(c); val outs = ArrayList<String>()
        val steps = s.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) { val st = steps.getJSONObject(i)
            if (st.optString("tool") !in SAFE_STEPS) continue
            outs.add(try { actions.run(st.optString("tool"), st.optJSONObject("input") ?: JSONObject()) } catch (e: Exception) { "That step didn't work." }) }
        // count it, for the weekly review
        try { val a = all(c); for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("name") == s.optString("name")) o.put("uses", o.optInt("uses") + 1) }; Prefs(c).sp.edit().putString("skills", a.toString()).apply() } catch (_: Exception) {}
        Trace.log(c, "skill", "${s.optString("name")}: ${outs.joinToString(" | ").take(200)}")
        Timeline.add(c, "skill", "Ran ${s.optString("name")}")
        return s.optString("line").ifBlank { outs.lastOrNull { it.isNotBlank() } ?: "Done." }
    }

    fun byName(c: Context, name: String): JSONObject? { val a = all(c); return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").equals(name.trim(), true) } }

    fun promptBlock(c: Context): String {
        val a = all(c); if (a.length() == 0) return ""
        return "\nSkills you built for yourself (run_skill by name when he asks for one in other words): " +
            (0 until a.length()).joinToString("; ") { a.getJSONObject(it).let { s -> "${s.optString("name")} (\"${s.optJSONArray("phrases")?.optString(0)}\")" } } + "."
    }
}

/**
 * Jarvis upgrades himself. Once a week (or when Marcus asks), he looks back at what went wrong or what he couldn't
 * do, and proposes a few upgrades: a new skill, a lesson for how he behaves, or a bigger change that needs building.
 * Nothing changes until Marcus approves it.
 */
object Upgrade {
    @Volatile var running = false; private set

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("upgrades", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { val keep = JSONArray(); for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i)); Prefs(c).sp.edit().putString("upgrades", keep.toString()).apply(); LogBus.changed() }
    fun waiting(c: Context) = all(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "waiting" } }

    /** Weekly, Sunday evening or later, once. */
    fun maybeReview(c: Context) {
        if (running || !Mind.on(c)) return
        val p = Prefs(c)
        // every three days in the evening, he looks back for ways to upgrade himself (small ones he applies, big ones wait for a yes)
        if (System.currentTimeMillis() - p.sp.getLong("upgrade_at", 0L) < Brain(c).config().optLong("upgrade_every_days", 3L) * 86_400_000L) return
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < 18 || Claude.nearBudget(c, 0.8)) return
        start(c)
    }

    fun start(c: Context): String {
        if (running) return "Already looking for upgrades."
        val p = Prefs(c)
        if (System.currentTimeMillis() - p.sp.getLong("upgrade_now_at", 0L) < 3 * 3600_000L && p.sp.getLong("upgrade_at", 0L) > 0) return "I looked for upgrades a little while ago; they're in His mind, waiting for your OK."
        p.sp.edit().putLong("upgrade_now_at", System.currentTimeMillis()).putLong("upgrade_at", System.currentTimeMillis()).apply()
        running = true; LogBus.changed()
        thread(name = "jarvis-upgrade") { try { review(c) } catch (e: Exception) { Problems.log(c, "upgrade: ${e.message}") } finally { running = false; LogBus.changed() } }
        return "Started. Tell him you're looking back over the week for ways to upgrade yourself, and the ideas will be waiting for his OK in His mind."
    }

    private fun review(c: Context) {
        val now = System.currentTimeMillis()
        val week = Timeline.all(c).filter { now - it.optLong("at") < 7 * 86_400_000L }
        val convo = week.filter { it.optString("k") == "him" || it.optString("k") == "jarvis" }.takeLast(260)
            .joinToString("\n") { "${SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(it.optLong("at")))} ${if (it.optString("k") == "him") "Him" else "You"}: ${it.optString("t").take(220)}" }
        val log = try { Trace.text(c).lines().filter { Regex("problem|blocked|ignored|couldn't|can't|failed|handed to Claude|fell back|interrupt|gave up|Not sent", RegexOption.IGNORE_CASE).containsMatchIn(it) }.takeLast(120).joinToString("\n") } catch (_: Exception) { "" }
        val skills = Skills.all(c).toString().take(3000)
        val lessons = try { Coach.lessons(c).toString().take(3000) } catch (_: Exception) { "" }
        val toolsList = Actions(c).tools().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("name") in Skills.SAFE_STEPS }
            .joinToString("\n") { "${it.optString("name")}: ${it.optString("description").take(140)} input=${it.optJSONObject("input_schema")?.optJSONObject("properties")?.keys()?.asSequence()?.joinToString()}" } }
        val tools = JSONArray().put(JSONObject().put("name", "propose").put("description", "Propose one upgrade for Marcus to approve.")
            .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("skill", "lesson", "build"))))
                .put("title", JSONObject().put("type", "string").put("description", "Short name, like 'Heading out' or 'Shorter answers while driving'"))
                .put("why", JSONObject().put("type", "string").put("description", "One plain sentence: what you noticed this week and what it fixes"))
                .put("lesson", JSONObject().put("type", "string").put("description", "For a lesson: the rule, one sentence"))
                .put("phrases", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "For a skill: 2 to 4 short ways he'd say it"))
                .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")).put("description", "For a skill: [{tool, input}] using only the allowed tools"))
                .put("line", JSONObject().put("type", "string").put("description", "For a skill: what Jarvis says when it's done")))
                .put("required", JSONArray(listOf("kind", "title", "why")))))
        var n = 0
        val auto = ArrayList<String>()
        val system = "You are Jarvis, looking back over your week with Marcus to upgrade yourself. Find what went wrong, what he had to repeat or correct, what you couldn't do, " +
            "and what he does over and over that could be one phrase. Propose at most three upgrades with propose, the most valuable first:\n" +
            "- skill: a new phrase that runs several of your existing tools in one go (only these tools: ${Skills.SAFE_STEPS.joinToString()}). Real inputs, 24-hour times.\n" +
            "- lesson: a rule for how you talk or behave that fixes something that actually happened.\n" +
            "- build: something that needs new code (describe it plainly for the developer).\n" +
            "Only propose what this week's data supports. Never anything that sends, calls, pays, buys, posts or deletes. Nothing already covered by an existing skill or lesson. If nothing is worth it, propose nothing.\n" +
            "Finish with one sentence summing up what you proposed."
        val cfg = Brain(c).config()
        // replays: how his suggestions and plans actually turned out, so he advises better
        val outcomes = try {
            val sug = Mind.suggestions(c).let { s -> (0 until s.length()).map { s.getJSONObject(it) }.filter { now - it.optLong("at") < 14 * 86_400_000L } }
            val loops = Loops.all(c).let { s -> (0 until s.length()).map { s.getJSONObject(it) }.filter { now - it.optLong("at") < 14 * 86_400_000L } }
            "Suggestions you made: " + sug.joinToString("; ") { "${it.optString("text").take(80)} -> ${it.optString("status")}" } +
                "\nFollow-ups: " + loops.joinToString("; ") { "${it.optString("what").take(60)} -> ${it.optString("status")}${it.optString("why").takeIf { w -> w.isNotBlank() }?.let { w -> " ($w)" } ?: ""}" } +
                "\nSkills used: " + Skills.all(c).let { s -> (0 until s.length()).joinToString("; ") { s.getJSONObject(it).let { k -> "${k.optString("name")} ${k.optInt("uses")}x" } } } +
                "\nYour speed last night: " + SelfCare.report(c).optJSONObject("speed")
        } catch (_: Exception) { "" }
        val material = "How your advice and plans turned out (replay these: what did he take, ignore or undo, and why?):\n$outcomes\n\nTools a skill can use:\n$toolsList\n\nSkills you already have:\n$skills\n\nLessons you already follow:\n$lessons\n\nThis week's problems from your flight log:\n$log\n\nThis week's conversations:\n$convo"
        val out = Claude.work(c, cfg.optString("upgrade_model", "claude-sonnet-5-5"), system, material.take(60000), tools, "medium", 6000, 6) { name, input ->
            if (name != "propose" || n >= 3) return@work "That's enough for this week."
            n++
            val a = all(c)
            val id = System.currentTimeMillis() + n
            a.put(JSONObject(input.toString()).put("id", id).put("at", System.currentTimeMillis()).put("status", "waiting"))
            save(c, a)
            // small, safe upgrades (a new skill, a lesson) he adds himself and mentions after; anything needing code waits for Marcus
            if (input.optString("kind") != "build" && cfg.optBoolean("auto_small_upgrades", true)) { auto.add(input.optString("title")); decide(c, id.toString(), true).also { markAuto(c, id) } } else "Saved for his OK."
        }
        Trace.log(c, "upgrade", "proposed $n (applied ${auto.size} myself): ${out?.first?.take(160)}")
        val waitingNow = n - auto.size
        if (n > 0) try { Mind.tellLater(c, "Sir, I looked back over the week and upgraded myself" +
            (if (auto.isNotEmpty()) ": ${auto.joinToString(" and ")}" else "") +
            (if (waitingNow > 0) ". $waitingNow bigger one${if (waitingNow == 1) "" else "s"} need${if (waitingNow == 1) "s" else ""} your OK in His mind." else ".")) } catch (_: Exception) {}
    }

    /** A fix or idea found on its own (overnight checks). False if the same one is already waiting. */
    @Synchronized fun addProposal(c: Context, kind: String, title: String, why: String): Boolean {
        val a = all(c)
        if ((0 until a.length()).any { a.getJSONObject(it).let { o -> o.optString("status") == "waiting" && o.optString("title").equals(title, true) } }) return false
        a.put(JSONObject().put("id", System.currentTimeMillis()).put("at", System.currentTimeMillis()).put("kind", kind).put("title", title.take(80)).put("why", why.take(300)).put("status", "waiting"))
        save(c, a); return true
    }

    /** He approved or turned down an upgrade (by id, or by words from its title). */
    @Synchronized fun decide(c: Context, which: String, approve: Boolean): String {
        val a = all(c)
        val u = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "waiting" }
            .firstOrNull { it.optLong("id").toString() == which.trim() || (which.isNotBlank() && it.optString("title").contains(which.trim(), true)) }
            ?: waiting(c).singleOrNull()?.let { w -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.optLong("id") == w.optLong("id") } }
            ?: return "No upgrade like that is waiting."
        if (!approve) { u.put("status", "declined"); save(c, a); return "Dropped the ${u.optString("title")} upgrade." }
        val r = when (u.optString("kind")) {
            "skill" -> Skills.add(c, JSONObject().put("name", u.optString("title")).put("phrases", u.optJSONArray("phrases") ?: JSONArray()).put("steps", u.optJSONArray("steps") ?: JSONArray()).put("line", u.optString("line")))
            "lesson" -> Coach.addLesson(c, u.optString("lesson").ifBlank { u.optString("why") }, "upgrade")
            else -> if (Builder.codeAllowed(c)) {
                // the Builder writes the fix and makes a TEST version; it only installs after he says yes
                val task = "${u.optString("title")}: ${u.optString("why")}. Find the cause in the code, make the smallest safe fix, and start a test build."
                thread(name = "jarvis-selffix") { val r = try { Agents.run(c, "builder", task, false) } catch (e: Exception) { "The fix hit a problem: ${e.message}" }
                    Mind.tellLater(c, "Sir, about the ${u.optString("title")} fix: $r") }
                "On it: I'm writing the fix and building a test version. I'll ask before installing it."
            } else "Saved for building: tell Claude in chat \"build the ${u.optString("title")} upgrade\", or add a GitHub token in setup and I'll build fixes myself."
        }
        u.put("status", if (u.optString("kind") == "build") "to build" else "approved").put("result", r); save(c, a)
        Timeline.add(c, "upgrade", "Approved: ${u.optString("title")}")
        return r
    }

    private fun markAuto(c: Context, id: Long) { val a = all(c); for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optLong("id") == id) o.put("auto", true) }; save(c, a) }

    fun list(c: Context): String {
        val w = waiting(c)
        val sk = Skills.all(c)
        return (if (w.isEmpty()) "No upgrades waiting." else "Waiting for your OK: " + w.joinToString("; ") { "${it.optString("title")} (${it.optString("why")})" } + ".") +
            (if (sk.length() > 0) " Skills I've built: " + (0 until sk.length()).joinToString(", ") { sk.getJSONObject(it).optString("name") } + "." else "")
    }
}
