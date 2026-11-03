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
 * Whole jobs: "handle my week", "get us ready for tomorrow", "sort out the baby stuff before payday". Jarvis plans it,
 * hands each part to the right agent (they can see each other's work), does the safe steps himself, and tells Marcus
 * once at the end what's done and what needs his yes. Runs in the background, so Marcus isn't stuck waiting.
 * Never sends, calls, pays, buys or posts: at most one text is drafted, read back, and only goes out on his yes.
 */
object Jobs {
    /** What the job runner can do directly (everything else goes through an agent). */
    val DIRECT = setOf("list_add", "remind_me", "draft_text", "calendar_events", "follow_up_add", "follow_ups_list", "follow_up_done", "list_read")
    @Volatile var running: String? = null; private set

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("jobs", "[]")) } catch (_: Exception) { JSONArray() }

    /** Starts a job in the background. Returns what Jarvis tells him right now. */
    fun start(c: Context, goal: String): String {
        if (goal.isBlank()) return "What's the job?"
        running?.let { return "Still working on the last job ($it). Tell him you'll get to this one right after." }
        running = goal
        Agents.active["planner"] = System.currentTimeMillis()
        thread {
            val summary = try { run(c, goal) } catch (e: Exception) { Problems.log(c, "job: ${e.message}"); "Sir, that job hit a problem partway: ${e.message}" }
            finally { running = null; Agents.active.remove("planner"); LogBus.changed() }
            // said out loud (and written into the conversation, so "yes, send it" works) by the service
            try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", summary).putExtra("listen", summary.trimEnd().endsWith("?"))) } catch (_: Exception) {}
        }
        return "Started the job in the background. Tell him in a few words you're on it and you'll report back when it's done (about a minute). Don't do any of it yourself."
    }

    fun run(c: Context, goal: String): String {
        val cfg = Brain(c).config()
        val t0 = System.currentTimeMillis()
        val actions = Actions(c)
        val tools = JSONArray()
        tools.put(Agents.askTool(c, null)); tools.put(Agents.TEAM_NOTE)
        val all = actions.tools(); for (i in 0 until all.length()) { val t = all.getJSONObject(i); if (t.optString("name") in DIRECT) tools.put(JSONObject(t.toString())) }
        val now = SimpleDateFormat("EEEE, MMMM d, h:mm a", Locale.US).format(Date())
        val system = "You are Jarvis, Marcus's personal assistant, running a whole job for him in the background: plan it, get it done, report once. " +
            "Work like a top executive assistant: think about what the job really needs across his whole life (money, Ziyair, his calendar and shifts, bills, meals, the list), " +
            "hand each part to the agent who owns it with ask_agent (give them every detail), do the simple steps yourself, and check results before moving on. Don't ask the same agent twice for the same thing.\n" +
            "Limits: you can't send, call, pay, buy, post or delete anything. If a text is part of the job, use draft_text once (only one per job) and read it back at the end. " +
            "Use follow_up_add for anything that needs chasing later. Leave a team_note for anything the agents should keep in mind this week. Never touch his DoorDash offers.\n" +
            "When you're done, reply with what you'd say to him out loud: two to four plain sentences, what you got done (specifics), and the one or two things that need him. " +
            "If you drafted a text, end with it word for word in double quotes and ask if you should send it. No lists, no markdown.\n" +
            Memory.promptBlock(c) + "\n\nWhat the team knows right now:\n" + Team.brief(c) + "\n\nRight now it is $now."
        var drafts = 0
        val model = if (Claude.nearBudget(c)) cfg.optString("agent_model", "claude-haiku-5-5") else cfg.optString("job_model", "claude-sonnet-5-5")
        val out = Claude.work(c, model, system, "The job: $goal", tools, if (model.contains("haiku")) null else cfg.optString("job_effort", "medium"),
            6000, cfg.optInt("job_rounds", 16)) { name, input ->
            when (name) {
                "ask_agent" -> Agents.run(c, input.optString("agent"), input.optString("task"), false, depth = 1)
                "team_note" -> Team.note(c, "Jarvis", input.optString("note"))
                "draft_text" -> if (++drafts > 1) "Only one text per job: mention the others to him instead." else actions.run(name, input)
                in DIRECT -> actions.run(name, input)
                else -> "Not something a background job can do."
            }
        }
        val steps = out?.second.orEmpty()
        val summary = out?.first?.takeIf { it.isNotBlank() }
            ?: if (out == null) "Sir, I couldn't reach Claude to run that job. Try me again in a minute."
            else "Sir, I did part of it: " + steps.filter { it.tool != "team_note" }.takeLast(3).joinToString(" ") { it.out.take(120) }
        val ms = System.currentTimeMillis() - t0
        save(c, goal, steps, summary, ms)
        Trace.log(c, "job", "\"${goal.take(100)}\" -> ${steps.size} steps, ${ms}ms: ${summary.take(200)}")
        return summary
    }

    @Synchronized private fun save(c: Context, goal: String, steps: List<Claude.Step>, summary: String, ms: Long) {
        val a = all(c)
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("goal", goal.take(200)).put("summary", summary.take(600)).put("ms", ms)
            .put("steps", JSONArray().apply { steps.forEach { s -> put(JSONObject().put("t", s.tool).put("a", s.input.optString("agent")).put("o", s.out.take(160))) } }))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 15) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("jobs", keep.toString()).apply()
    }
}
