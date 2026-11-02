package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Jarvis's brain: Jarvis is the one Marcus talks to, and behind him sit specialist agents. Each agent owns a set of
 * skills (tools), keeps its own notebook of what it learns, and does one job well. Jarvis hands a job to an agent in
 * one sentence and gets back what happened.
 *
 * Kept simple on purpose: the hand-off is one call, every hand-off is written to the flight log, and if agents are
 * switched off (remotely or in the Brain tab) Jarvis goes back to using every skill himself.
 */
object Agents {
    class Def(val id: String, val name: String, val blurb: String, val tools: Set<String>, val style: String)

    /** Skills Jarvis keeps himself: the quick, everyday ones where an extra hop would only slow him down. */
    val CORE = setOf("set_timer", "remind_me", "get_weather", "remember", "forget", "learn_lesson",
        "quiet_mode", "set_voice", "status_report", "look", "list_add",
        "list_check", "list_read", "list_clear", "driving_mode", "media_control", "answer_call", "decline_call")

    val PHONE = Def("phone", "Phone", "Runs his phone: calls, texts, DMs, apps, music, directions, the screen.",
        setOf("call", "send_text", "read_messages", "reply_message", "navigate", "stop_navigation", "play_music", "open_app",
            "media_control", "phone_task", "screen_read", "phone_control", "find_phone", "answer_call", "decline_call"),
        "Do the job on his phone with the fewest steps. For anything inside an app, use phone_task with the whole job in one sentence. " +
            "Texts, DMs, replies and calls only go out when the task says he confirmed; otherwise get it ready and say it's waiting for his yes.")

    val MONEY = Def("money", "Money", "Tracks what he makes and spends, the Macan fund, and his Jarvis bill.",
        setOf("log_earnings", "earnings_summary", "set_goal", "log_expense", "expenses_summary", "spending", "set_budget", "offer_rule"),
        "Be exact with numbers. Log what he says, then answer with the figure that matters (today, the week, how far to the goal).")

    val FAMILY = Def("family", "Family and home", "Ziyair's feeds, diapers and sleep, the baby journal, the lights and the house.",
        setOf("log_baby", "baby_status", "set_feed_interval", "journal_add", "journal_read", "remind_at_home", "lights",
            "house_speaker", "set_home_wifi"),
        "Log baby events the moment he mentions them. Speech-to-text often mangles the baby's name: it's always Ziyair.")

    val PLANNER = Def("planner", "Planner", "Owns his time: calendar, reminders, alarms, the morning briefing and his protocols.",
        setOf("calendar_events", "add_event", "remind_me", "list_reminders", "cancel_reminders", "set_alarm", "briefing", "run_protocol", "set_timer"),
        "Think about his whole day before answering: his dashes run 7 to 11 and 5 to 9, and there's a newborn at home. Check the calendar before adding things, " +
            "and say plainly if something clashes. Times you pass to tools are 24-hour local time.")

    val RESEARCH = Def("research", "Research", "Digs into questions that need more than one search: comparisons, how-tos, prices, plans.",
        setOf(), "Search several times from different angles, read what comes back, and answer with the bottom line in two to four plain sentences: " +
            "the answer first, then the one or two facts that matter (numbers, names, dates). No links or source names unless asked. Say so if sources disagree.")

    val PURSUIT = Def("pursuit", "Pursuit", "His business partner for the Pursuit clothing brand: ideas, the shoot list, launch to-dos and orders.",
        setOf("pursuit_add", "pursuit_list", "pursuit_done"),
        "Save ideas the moment he says them, tagged (design, drop, post, collab or the product). When he asks what's left or what ideas he has, read back " +
            "the best few, newest first. Talk like a sharp co-founder: practical, no hype. Never post, message or buy anything.")

    val MEMORY = Def("memory", "Memory", "Keeps everything Jarvis knows about him accurate: what he remembers, corrections, and forgetting on request.",
        setOf("remember", "forget", "memory_list", "memory_fix"),
        "Read memory_list before changing anything. When he asks what you know, give the few things that matter most in two or three sentences, not the whole list. " +
            "When tidying, merge duplicates and fix outdated facts with memory_fix; never invent facts. Forget means delete, completely.")

    val BUILDER = Def("builder", "Builder", "Works on Jarvis himself: changes how he talks and behaves, and builds new versions for Marcus to approve.",
        Builder.TOOL_NAMES, Builder.STYLE)

    val COACH = Def("coach", "Coach", "Makes Jarvis better every day: learns from his mistakes and Marcus's corrections, reviews each day, and tunes him.",
        Coach.TOOL_NAMES, "When he asks how you're improving or what you've learned, read list_lessons and answer in two or three plain sentences. " +
            "If he says a lesson is wrong, drop it. If he asks for a review now, run review_today.")

    val ALL = listOf(PHONE, MONEY, FAMILY, PLANNER, RESEARCH, PURSUIT, MEMORY, BUILDER, COACH)
    fun def(id: String) = ALL.firstOrNull { it.id == id }

    // ---------------- switches, notebooks, stats ----------------
    fun on(c: Context): Boolean = Brain(c).config().optBoolean("agents", true)
    fun enabled(c: Context, id: String) = Prefs(c).sp.getBoolean("agent_on_$id", true)
    fun setEnabled(c: Context, id: String, v: Boolean) = Prefs(c).sp.edit().putBoolean("agent_on_$id", v).apply()

    fun notes(c: Context, id: String): JSONArray = try { JSONArray(Prefs(c).sp.getString("agent_notes_$id", "[]")) } catch (_: Exception) { JSONArray() }
    @Synchronized fun addNote(c: Context, id: String, text: String): Boolean {
        val t = text.trim().removeSuffix(".")
        if (t.length < 3 || Memory.sensitive(t)) return false
        val a = notes(c, id)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("t").equals(t, true)) return false
        a.put(JSONObject().put("t", t).put("at", System.currentTimeMillis()))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("agent_notes_$id", keep.toString()).apply()
        return true
    }
    @Synchronized fun removeNote(c: Context, id: String, at: Long) {
        val a = notes(c, id); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") != at) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("agent_notes_$id", keep.toString()).apply()
    }

    fun runs(c: Context, id: String): JSONArray = try { JSONArray(Prefs(c).sp.getString("agent_runs_$id", "[]")) } catch (_: Exception) { JSONArray() }
    @Synchronized private fun logRun(c: Context, id: String, task: String, result: String, ms: Long, tools: List<String>) {
        val a = runs(c, id)
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("task", task.take(160)).put("result", result.take(200)).put("ms", ms).put("tools", JSONArray(tools)))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 12) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("agent_runs_$id", keep.toString()).putInt("agent_count_$id", Prefs(c).sp.getInt("agent_count_$id", 0) + 1).apply()
    }

    /** Which agents are working right now (the Brain view lights their threads). */
    val active = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Inner skills used recently (so Jarvis knows "play_music" ran even when an agent did it). */
    private val inner = CopyOnWriteArrayList<Pair<Long, String>>()
    fun usedRecently(name: String, ms: Long) = inner.any { it.second == name && System.currentTimeMillis() - it.first < ms }
    fun usedSince(name: String, since: Long) = inner.any { it.second == name && it.first >= since }
    fun drainInner(): List<String> { val l = inner.map { it.second }; inner.clear(); return l }

    // ---------------- what Jarvis sees ----------------
    /** Jarvis's own tool list when agents are on: his core skills, plus one hand-off tool per agent. */
    fun jarvisTools(c: Context, all: JSONArray): JSONArray {
        if (!on(c)) return all
        val out = JSONArray()
        val handedOff = HashSet<String>()
        for (d in ALL) if (enabled(c, d.id) && (d.id != "builder" || Builder.allowed(c))) handedOff.addAll(d.tools)
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i); val n = t.optString("name")
            if (n in CORE || n !in handedOff) out.put(t)
        }
        for (d in ALL) {
            if (!enabled(c, d.id) || (d.id == "builder" && !Builder.allowed(c))) continue
            out.put(JSONObject().put("name", "agent_${d.id}")
                .put("description", "Hand a job to the ${d.name} agent: ${d.blurb} Describe the whole job in one sentence with every detail he gave (names, amounts, the exact message). " +
                    (if (d.id == "phone" || d.id == "builder") "Set confirmed=true only after he clearly said yes to sending, posting, calling or installing." else ""))
                .put("input_schema", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("task", JSONObject().put("type", "string").put("description", "The job, in one sentence"))
                        .put("confirmed", JSONObject().put("type", "boolean").put("description", "He said yes to the send/post/call/install")))
                    .put("required", JSONArray().put("task"))))
        }
        return out
    }

    // ---------------- running an agent ----------------
    /** Skills that send, call or act on his behalf: their confirmed flag always equals Marcus's actual yes. */
    val CONFIRMABLE = setOf("send_text", "reply_message", "phone_task")

    /** Skills that need a look at the result before the agent is done (everything else returns straight away). */
    private val LOOK_AGAIN = setOf("screen_read", "read_messages", "earnings_summary", "expenses_summary", "baby_status", "journal_read",
        "calendar_events", "list_reminders", "briefing", "pursuit_list", "memory_list", "list_lessons", "review_today",
        "read_file", "list_files", "build_status", "read_build_errors")

    /** Runs one job on an agent (blocking, background thread). Returns what happened, for Jarvis to say. */
    fun run(c: Context, id: String, task: String, confirmed: Boolean): String {
        val d = def(id) ?: return "No agent called $id."
        if (!enabled(c, id)) return "The ${d.name} agent is switched off in the Brain tab."
        val key = Prefs(c).claudeKey
        if (key.isBlank()) return "No Claude key set."
        // the research agent uses the bigger model: it waits once he's at his monthly budget
        if (id == "research" && Spend.thisMonth(Prefs(c)) >= Spend.budget(Prefs(c))) return "He's at his Claude budget for the month, so deep research is paused; I can still do a quick search."
        val t0 = System.currentTimeMillis()
        active[id] = t0
        Trace.log(c, "agent", "${d.name} <- \"${task.take(140)}\"${if (confirmed) " (confirmed)" else ""}")
        val used = ArrayList<String>()
        var result = ""
        try {
            val cfg = Brain(c).config()
            val model = when (id) {
                "builder" -> cfg.optString("builder_model", "claude-sonnet-5-5")
                "research" -> cfg.optString("research_model", "claude-sonnet-5-5")
                else -> cfg.optString("agent_model", "claude-haiku-5-5")
            }
            val actions = Actions(c)
            val tools = JSONArray()
            if (id == "builder") { val b = Builder.tools(); for (i in 0 until b.length()) tools.put(b.get(i)) }
            else if (id == "coach") { val b = Coach.tools(); for (i in 0 until b.length()) tools.put(b.get(i)) }
            else { val all = actions.tools(); for (i in 0 until all.length()) { val t = all.getJSONObject(i); if (t.optString("name") in d.tools) tools.put(t) } }
            // the research agent searches the web itself (Claude's search tool, a few searches per job)
            if (id == "research") tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", cfg.optInt("research_max_searches", 4))
                .put("user_location", JSONObject().put("type", "approximate").put("city", Prefs(c).city.ifBlank { "Spokane" }).put("region", "Washington").put("country", "US")))
            tools.put(JSONObject().put("name", "note_to_self").put("description", "Save one short thing you've learned about how he likes this done (a habit, a preference, a correction), so you do it better next time. Not for one-off details.")
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject().put("note", JSONObject().put("type", "string"))).put("required", JSONArray().put("note"))))
            tools.getJSONObject(tools.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral"))
            val nb = notes(c, id)
            val system = "You are the ${d.name} agent inside Jarvis, Marcus's personal assistant. ${d.blurb} ${d.style}\n" +
                "Jarvis hands you one job; do it with your tools, then reply with ONE short plain sentence saying exactly what happened (facts and numbers, no pleasantries). Jarvis will say it to Marcus.\n" +
                "If you can't do it, say what's missing in one sentence. Never invent results.\n" +
                (if (confirmed) "Marcus has confirmed this job.\n" else "Marcus has NOT confirmed sending, posting, calling, buying or installing anything: don't.\n") +
                (if (nb.length() > 0) "What you've learned about him:\n" + (0 until nb.length()).joinToString("\n") { "- " + nb.getJSONObject(it).optString("t") } + "\n" else "") +
                "Right now it is " + java.text.SimpleDateFormat("EEEE h:mm a", java.util.Locale.US).format(java.util.Date()) + "."
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", task))
            for (round in 1..(if (id == "builder") 14 else 6)) {
                val body = JSONObject().put("model", model).put("max_tokens", when (id) { "builder" -> 4000; "research" -> 900; else -> 500 }).put("system", system)
                    .put("tools", tools).put("messages", msgs)
                val resp = post(key, body) ?: run { result = "The ${d.name} agent couldn't reach Claude."; return result }
                Spend.record(c, cfg, model, resp.optJSONObject("usage"))
                val content = resp.optJSONArray("content") ?: JSONArray()
                val text = StringBuilder(); val results = JSONArray(); val outs = ArrayList<String>()
                var lookAgain = false
                for (i in 0 until content.length()) {
                    val p = content.getJSONObject(i)
                    when (p.optString("type")) {
                        "text" -> text.append(p.optString("text"))
                        "tool_use" -> {
                            val name = p.optString("name"); val input = p.optJSONObject("input") ?: JSONObject()
                            // the confirm rule is enforced here, not left to the model
                            if (name in CONFIRMABLE) input.put("confirmed", confirmed)
                            val out = when {
                                name == "note_to_self" -> if (addNote(c, id, input.optString("note"))) "Noted." else "Already noted."
                                id == "builder" -> Builder.run(c, name, input, confirmed)
                                id == "coach" -> Coach.runTool(c, name, input)
                                name in d.tools -> actions.run(name, input)
                                else -> "That skill belongs to another agent."
                            }
                            if (name != "note_to_self") { used.add(name); inner.add(System.currentTimeMillis() to name); outs.add(out) }
                            if (name in LOOK_AGAIN) lookAgain = true
                            Trace.log(c, "agent", "${d.name}: $name -> ${out.take(140)}")
                            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", p.optString("id")).put("content", out))
                        }
                    }
                }
                // a long web search can pause mid-way: hand it back so it finishes
                if (resp.optString("stop_reason") == "pause_turn") { msgs.put(JSONObject().put("role", "assistant").put("content", content)); continue }
                if (results.length() == 0) { result = text.toString().trim().ifBlank { "Done." }; break }
                // one quick step done and nothing to read back: hand the result straight to Jarvis (saves a whole round)
                if (!lookAgain && id != "builder" && outs.isNotEmpty()) { result = outs.joinToString(" "); break }
                msgs.put(JSONObject().put("role", "assistant").put("content", content))
                msgs.put(JSONObject().put("role", "user").put("content", results))
                if (round == (if (id == "builder") 14 else 6)) result = outs.joinToString(" ").ifBlank { "Ran out of steps." }
            }
        } catch (e: Exception) {
            Problems.log(c, "${d.name} agent: ${e.message}")
            result = "The ${d.name} agent hit a problem: ${e.message}"
        } finally {
            active.remove(id)
            val ms = System.currentTimeMillis() - t0
            logRun(c, id, task, result, ms, used)
            Trace.log(c, "agent", "${d.name} -> \"${result.take(160)}\" (${ms}ms)")
            LogBus.changed()
        }
        return result
    }

    private fun post(key: String, body: JSONObject): JSONObject? = try {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = 60000
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (ok) JSONObject(text) else null
    } catch (_: Exception) { null }

    /** Everything the Brain tab shows. */
    fun json(c: Context): JSONArray = JSONArray().apply {
        put(JSONObject().put("id", "jarvis").put("name", "Jarvis").put("blurb", "The one you talk to. Handles quick things himself and hands bigger jobs to his agents.")
            .put("on", true).put("active", false).put("skills", JSONArray(CORE.sorted())).put("count", 0).put("runs", JSONArray()).put("notes", JSONArray()))
        for (d in ALL) put(JSONObject().put("id", d.id).put("name", d.name).put("blurb", d.blurb)
            .put("on", enabled(c, d.id) && on(c) && (d.id != "builder" || Builder.allowed(c)))
            .put("needs", if (d.id == "builder" && !Builder.allowed(c)) "Add a GitHub token in setup to let him build new versions. Small changes work without it." else "")
            .put("active", active.containsKey(d.id))
            .put("skills", JSONArray(d.tools.sorted())).put("count", Prefs(c).sp.getInt("agent_count_${d.id}", 0))
            .put("runs", runs(c, d.id)).put("notes", if (d.id == "coach") Coach.lessons(c) else notes(c, d.id)))
    }
}
