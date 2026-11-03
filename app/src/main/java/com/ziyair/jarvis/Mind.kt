package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Jarvis's own mind. A few times a day, with nobody asking, he looks over everything the team knows and gets ahead of
 * Marcus's real life: what's coming, what clashes, what's running low, what slipped. He does the safe things himself
 * (asks his agents, adds to the list, sets a reminder, plans meals, starts a follow-up), suggests the rest, and at
 * night comes up with one idea of his own. Everything he thinks and does shows in the Mind card on the home screen.
 *
 * Hard limits, enforced here in code, not left to the model: he never sends, calls, pays, buys, posts or deletes.
 */
object Mind {
    /** The only things the mind can do by itself. */
    val SAFE = setOf("ask_agent", "team_note", "list_add", "remind_me", "follow_up_add", "follow_up_done", "follow_ups_list", "calendar_events", "list_read",
        "suggest", "tell_him", "share_idea")
    private val DIRECT = setOf("list_add", "remind_me", "follow_up_add", "follow_up_done", "follow_ups_list", "calendar_events", "list_read")

    @Volatile var thinking = false; private set

    fun enabled(c: Context) = Prefs(c).sp.getBoolean("mind_on", true)
    fun setEnabled(c: Context, on: Boolean) { Prefs(c).sp.edit().putBoolean("mind_on", on).apply(); LogBus.changed() }
    fun on(c: Context) = enabled(c) && Agents.on(c) && Brain(c).config().optBoolean("mind", true) && Prefs(c).claudeKey.isNotBlank()

    /** Which thinking time it is, or null between them. Pure. */
    fun slot(hour: Int): String? = when (hour) { in 6..10 -> "morning"; in 12..15 -> "afternoon"; in 19..22 -> "evening"; else -> null }

    private fun arr(c: Context, k: String): JSONArray = try { JSONArray(Prefs(c).sp.getString(k, "[]")) } catch (_: Exception) { JSONArray() }
    private fun put(c: Context, k: String, a: JSONArray, keep: Int) { val t = JSONArray(); for (i in maxOf(0, a.length() - keep) until a.length()) t.put(a.get(i)); Prefs(c).sp.edit().putString(k, t.toString()).apply() }
    fun thoughts(c: Context) = arr(c, "mind_thoughts")
    fun ideas(c: Context) = arr(c, "mind_ideas")
    fun suggestions(c: Context) = arr(c, "mind_suggest")
    private fun tells(c: Context) = arr(c, "mind_tell")

    /** Open suggestions from the last two days (pure over the data). */
    fun openSuggestions(a: JSONArray, now: Long): List<JSONObject> =
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "open" && now - it.optLong("at") < 48 * 3600_000L }

    /** On unlock and after a conversation: think if it's a thinking time he hasn't had yet today. */
    fun maybeThink(c: Context) {
        if (!on(c) || thinking) return
        val cal = Calendar.getInstance()
        val s = slot(cal.get(Calendar.HOUR_OF_DAY)) ?: return
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time) + ":" + s
        val p = Prefs(c)
        if (p.sp.getString("mind_last_slot", "") == key || Claude.nearBudget(c, 0.8)) return
        p.sp.edit().putString("mind_last_slot", key).apply()
        thread { think(c, s) }
    }

    /** "Think now" from the dashboard (at most every ten minutes). */
    fun thinkNow(c: Context): Boolean {
        if (!on(c) || thinking) return false
        val p = Prefs(c)
        if (System.currentTimeMillis() - p.sp.getLong("mind_now_at", 0L) < 10 * 60_000L) return false
        p.sp.edit().putLong("mind_now_at", System.currentTimeMillis()).apply()
        thread { think(c, slot(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) ?: "afternoon") }
        return true
    }

    private fun context(c: Context, s: String): String {
        val now = System.currentTimeMillis()
        val f = SimpleDateFormat("EEE h:mm a", Locale.US)
        val h = Prefs(c).history()
        val convo = (maxOf(0, h.length() - 10) until h.length()).map { h.getJSONObject(it) }.filter { now - it.optLong("at") < 36 * 3600_000L }
            .joinToString("\n") { "${f.format(Date(it.optLong("at")))} ${if (it.optString("role") == "user") "Him" else "You"}: ${it.optString("content").take(220)}" }
        val th = thoughts(c); val did = (maxOf(0, th.length() - 6) until th.length()).map { th.getJSONObject(it) }
            .joinToString("\n") { "${f.format(Date(it.optLong("at")))}: ${it.optString("thought")} Did: ${it.optJSONArray("did")?.let { d -> (0 until d.length()).joinToString("; ") { d.getString(it) } }.orEmpty()}" }
        val id = ideas(c); val ideaList = (maxOf(0, id.length() - 10) until id.length()).joinToString("\n") { "- " + id.getJSONObject(it).optString("t") }
        val sug = openSuggestions(suggestions(c), now).joinToString("\n") { "- " + it.optString("text") }
        return "What the team knows right now:\n" + Team.brief(c) + Memory.promptBlock(c) +
            (if (convo.isNotBlank()) "\n\nYour recent conversation with him:\n$convo" else "") +
            (if (did.isNotBlank()) "\n\nWhat you've thought and done on your own lately (don't repeat any of it):\n$did" else "") +
            (if (sug.isNotBlank()) "\n\nSuggestions still waiting for his yes:\n$sug" else "") +
            (if (ideaList.isNotBlank()) "\n\nIdeas you've already had (don't repeat):\n$ideaList" else "") +
            "\n\nRight now it is " + SimpleDateFormat("EEEE, MMMM d, h:mm a", Locale.US).format(Date()) + " ($s)."
    }

    // ---------------- every ten minutes: look again, rethink only if something changed ----------------
    /** A fingerprint of each part of his life that matters to the plan (no times-ago, so it only moves when something real changes). */
    fun parts(c: Context): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        fun add(k: String, f: () -> String?) { try { m[k] = f().orEmpty() } catch (_: Exception) {} }
        add("bills") { Bills.brief(c) }
        add("appointments") { Appts.brief(c) }
        add("calendar") { if (Cal.allowed(c)) Cal.events(c, null, 2) else "" }
        add("reminders") { Scheduler.describe(c) }
        add("shopping list") { Lists.read(c, "list") }
        add("meal plan") { Meals.week(c) }
        add("deliveries") { val a = Packages.all(c); (0 until a.length()).joinToString { a.getJSONObject(it).let { o -> o.optString("item") + ":" + o.optString("status") } } }
        add("follow-ups") { Loops.open(Loops.all(c)).joinToString { it.optString("id") } }
        add("earnings") { Earnings.today(c).toString() }
        add("big purchases") { val a = Spending.all(c); (0 until a.length()).count { a.getJSONObject(it).optDouble("a") >= 40 }.toString() }
        add("team notes") { Team.notes(c).length().toString() }
        return m
    }

    /** Which parts changed between two fingerprints. Pure. */
    fun changed(before: JSONObject, now: Map<String, String>): List<String> =
        if (before.length() == 0) emptyList() else now.filter { (k, v) -> before.has(k) && before.optString(k) != v.hashCode().toString() }.keys.toList()

    /**
     * Runs every ten minutes (and when he picks up the phone): refreshes the lock-screen plan, and if anything in his
     * life changed since he last thought, thinks again about just that (capped per day so it stays inside his budget).
     */
    fun tick(c: Context) {
        try { DayPlan.save(c) } catch (_: Exception) {}
        if (!on(c) || thinking) return
        val p = Prefs(c)
        val now = System.currentTimeMillis()
        val cur = parts(c)
        val before = try { JSONObject(p.sp.getString("mind_parts", "{}")) } catch (_: Exception) { JSONObject() }
        val diff = changed(before, cur)
        val firstTime = before.length() == 0
        p.sp.edit().putString("mind_parts", JSONObject().apply { cur.forEach { (k, v) -> put(k, v.hashCode().toString()) } }.toString()).apply()
        if (firstTime || diff.isEmpty()) return   // nothing new: keep the same thoughts
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val count = if (p.sp.getString("mind_upd_day", "") == day) p.sp.getInt("mind_upd_n", 0) else 0
        if (h !in 6..22 || count >= Brain(c).config().optInt("mind_updates_max", 18) || Claude.nearBudget(c, 0.8)) return
        if (now - p.sp.getLong("mind_think_at", 0L) < 9 * 60_000L) return
        p.sp.edit().putString("mind_upd_day", day).putInt("mind_upd_n", count + 1).apply()
        Trace.log(c, "mind", "changed: ${diff.joinToString()}")
        think(c, slot(h) ?: "afternoon", diff)
    }

    fun think(c: Context, s: String, changes: List<String>? = null) {
        if (thinking) return
        thinking = true; LogBus.changed()
        val t0 = System.currentTimeMillis()
        Prefs(c).sp.edit().putLong("mind_think_at", t0).apply()
        try { DayPlan.save(c) } catch (_: Exception) {}
        val did = ArrayList<String>(); var reminders = 0; var adds = 0; var nowTells = 0
        try {
            val cfg = Brain(c).config()
            val actions = Actions(c)
            val tools = JSONArray().put(Agents.askTool(c, null)).put(Agents.TEAM_NOTE)
            val all = actions.tools(); for (i in 0 until all.length()) { val t = all.getJSONObject(i); if (t.optString("name") in DIRECT) tools.put(JSONObject(t.toString())) }
            fun tool(n: String, d: String, props: JSONObject, req: List<String>) = tools.put(JSONObject().put("name", n).put("description", d)
                .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
            fun str(d: String) = JSONObject().put("type", "string").put("description", d)
            tool("suggest", "Something worth doing that needs his yes (a text to send, a call, a purchase, a decision). For a text, give who and the exact message.",
                JSONObject().put("text", str("The suggestion, one short sentence")).put("who", str("Who a text would go to, if it's a text")).put("message", str("The exact text, if it's a text")), listOf("text"))
            tool("tell_him", "Something he should hear from you. now=true only if it matters today and can't wait for the next time he talks to you.",
                JSONObject().put("text", str("One short spoken sentence, starting 'Sir,'")).put("now", JSONObject().put("type", "boolean")), listOf("text"))
            tool("share_idea", "One idea of your own for his life, grounded in his real numbers and days.", JSONObject().put("text", str("The idea, two sentences at most")), listOf("text"))

            val evening = s == "evening" && changes == null
            val system = "You are Jarvis's own mind, thinking on your own in the $s while Marcus gets on with his day. Nobody asked you anything. " +
                "Look at everything below and get ahead of his real life in the next day or two: what's coming, what clashes, what's running low, what slipped, what he'd want handled before he thinks of it. " +
                "Think like the best chief of staff alive who also genuinely cares about him, his girlfriend and Ziyair.\n" +
                "Do the safe things yourself: ask your agents (Meals to plan dinners, Bills to check what's due, Supplies to check prices, Planner for his day...), add to the list, set a reminder, start a follow-up, leave a team note. " +
                "You cannot send, call, pay, buy, post or delete anything: for those use suggest (with the exact words for a text) and he decides.\n" +
                "Rules: act only on real data below, never invent. Don't redo anything you or he already did. At most two reminders, and only for things that matter. Never touch his DoorDash offers. " +
                "If nothing is worth doing, do nothing. Use tell_him for what he should hear (now=true only if it matters today).\n" +
                (if (evening) "It's evening: also look at tomorrow (anything to get ready tonight?), and come up with ONE genuinely useful, specific idea of your own for his life (money toward his goal, Ziyair, Pursuit, his time, the house), grounded in the numbers below, new compared to your past ideas. Save it with share_idea.\n" else "") +
                (if (changes != null) "This is a quick update: since you last thought, this changed: ${changes.joinToString()}. Look at what changed and how it affects his day; act only if it needs something new. Your earlier thoughts still stand otherwise.\n" else "") +
                "Finish with two parts. First line: 'Headline: ' then what's on your mind for his day in ten words or fewer (it goes on his lock screen, like 'Cash App due Friday; dinner's planned; Ziyair feeds at 2:30'). " +
                "Then one or two plain sentences for his Thoughts feed: what you noticed and what you did. If nothing needed you, say what you checked in one sentence."
            val model = if (changes != null) cfg.optString("mind_update_model", "claude-haiku-5-5") else cfg.optString("mind_model", "claude-sonnet-5-5")
            val effort = if (model.contains("haiku")) null else cfg.optString("mind_effort", "medium")
            val out = Claude.work(c, model, system, context(c, s), tools, effort, if (effort == null) 2500 else 6000, if (changes != null) 6 else cfg.optInt("mind_rounds", 12)) { name, input ->
                if (name !in SAFE) return@work "Not allowed: you can't do that on your own. Use suggest."
                when (name) {
                    "ask_agent" -> Agents.run(c, input.optString("agent"), input.optString("task"), false, depth = 1).also { did.add("asked ${input.optString("agent")}: ${input.optString("task").take(80)}") }
                    "team_note" -> Team.note(c, "Jarvis", input.optString("note"))
                    "remind_me" -> if (++reminders > 2) "That's enough reminders for now." else actions.run(name, input).also { did.add("reminder: ${input.optString("text").take(80)} at ${input.optString("time")}") }
                    "list_add" -> if (++adds > 3) "Enough list changes for now." else actions.run(name, input).also { did.add("added to the list: ${input.optString("items").take(80)}") }
                    "follow_up_add" -> actions.run(name, input).also { did.add("following up: ${input.optString("what").take(80)}") }
                    "suggest" -> suggest(c, input).also { did.add("suggested: ${input.optString("text").take(80)}") }
                    "tell_him" -> { val now = input.optBoolean("now") && ++nowTells <= 1; tell(c, input.optString("text"), now); if (now) "Said." else "He'll hear it next time he talks to you." }
                    "share_idea" -> idea(c, input.optString("text"))
                    else -> actions.run(name, input)
                }
            }
            val raw = out?.first?.takeIf { it.isNotBlank() } ?: if (out == null) return else "Checked in on everything."
            val (headline, thought) = split(raw)
            val th = thoughts(c)
            // a quick update that found nothing new and did nothing: keep the same thoughts
            if (changes != null && did.isEmpty() && th.length() > 0 && Regex("(?i)nothing (new|needs|changed)|no change").containsMatchIn(thought)) {
                Trace.log(c, "mind", "update (${changes.joinToString()}): no change"); return
            }
            th.put(JSONObject().put("at", System.currentTimeMillis()).put("slot", if (changes != null) "update" else s).put("headline", headline).put("thought", thought.take(400)).put("did", JSONArray(did)).put("ms", System.currentTimeMillis() - t0)
                .put("why", changes?.joinToString() ?: ""))
            put(c, "mind_thoughts", th, 30)
            Trace.log(c, "mind", "$s: ${thought.take(200)} | did ${did.size}")
        } catch (e: Exception) {
            Problems.log(c, "mind: ${e.message}")
        } finally { thinking = false; try { DayPlan.save(c) } catch (_: Exception) {}; LogBus.changed() }
    }

    /** "Headline: ..." on the first line, the thought after it. Pure. */
    fun split(raw: String): Pair<String, String> {
        val t = raw.trim()
        val m = Regex("(?im)^\\**headline\\**:\\s*(.+)$").find(t) ?: return "" to t
        val head = m.groupValues[1].trim().trim('*', '"', '\'').trim().take(90)
        val rest = t.removeRange(m.range).trim().ifBlank { head }
        return head to rest
    }

    @Synchronized private fun suggest(c: Context, i: JSONObject): String {
        val a = suggestions(c)
        val text = i.optString("text").trim().take(200)
        if (openSuggestions(a, System.currentTimeMillis()).any { it.optString("text").equals(text, true) }) return "Already suggested."
        a.put(JSONObject().put("id", System.currentTimeMillis()).put("at", System.currentTimeMillis()).put("text", text)
            .put("who", i.optString("who").trim()).put("msg", i.optString("message").trim().take(500)).put("status", "open"))
        put(c, "mind_suggest", a, 30)
        return "Saved for his yes."
    }

    @Synchronized private fun tell(c: Context, text: String, now: Boolean) {
        val t = text.trim().take(240); if (t.isBlank()) return
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (now && h in 8..21 && Prefs(c).running) {
            try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", t)) } catch (_: Exception) {}
            return
        }
        val a = tells(c); a.put(JSONObject().put("at", System.currentTimeMillis()).put("t", t).put("said", false)); put(c, "mind_tell", a, 12)
    }

    @Synchronized private fun idea(c: Context, text: String): String {
        val a = ideas(c); a.put(JSONObject().put("at", System.currentTimeMillis()).put("t", text.trim().take(300))); put(c, "mind_ideas", a, 30)
        tell(c, "Sir, an idea I had: " + text.trim().take(220), false)
        return "Saved."
    }

    /** For Jarvis's next reply: what his mind wants him to hear (once). */
    fun promptBlock(c: Context): String {
        if (!on(c)) return ""
        val now = System.currentTimeMillis()
        val a = tells(c); val l = (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("said") && now - it.optLong("at") < 24 * 3600_000L }
        if (l.isEmpty()) return ""
        return "\nYour own mind noticed or did this since he last talked to you. Once his question is answered, mention it in one short sentence at the end of this reply (just the most useful one if there are several): " +
            l.takeLast(3).joinToString(" | ") { it.optString("t") }
    }

    @Synchronized fun said(c: Context) {
        val a = tells(c); var any = false
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (!o.optBoolean("said")) { o.put("said", true); any = true } }
        if (any) put(c, "mind_tell", a, 12)
    }

    /** What he's been thinking about lately, for "what have you been up to?" */
    fun recent(c: Context): String {
        val th = thoughts(c); val now = System.currentTimeMillis()
        val l = (0 until th.length()).map { th.getJSONObject(it) }.filter { now - it.optLong("at") < 2 * 86_400_000L }.takeLast(4)
        val sug = openSuggestions(suggestions(c), now)
        val id = ideas(c)
        if (l.isEmpty() && sug.isEmpty()) return "Nothing yet: I think on my own in the morning, afternoon and evening."
        return l.joinToString(" ") { it.optString("thought") } +
            (if (sug.isNotEmpty()) " Waiting for your yes: " + sug.joinToString("; ") { it.optString("text") } + "." else "") +
            (if (id.length() > 0) " Latest idea: " + id.getJSONObject(id.length() - 1).optString("t") else "")
    }

    /** "Do it" on a suggestion from the home screen. A text opens in his messages app, filled in, for him to send himself. */
    @Synchronized fun act(c: Context, id: Long, doIt: Boolean) {
        val a = suggestions(c)
        val s = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("id") == id } ?: return
        s.put("status", if (doIt) "done" else "dismissed"); put(c, "mind_suggest", a, 30); LogBus.changed()
        if (!doIt) return
        if (s.optString("msg").isNotBlank()) {
            val num = s.optString("who").takeIf { it.isNotBlank() }?.let { Actions(c).findNumber(it)?.second }
            try { c.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (num?.let { Uri.encode(it) } ?: ""))).putExtra("sms_body", s.optString("msg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        } else Jobs.start(c, s.optString("text"))
    }

    fun json(c: Context): JSONObject {
        val now = System.currentTimeMillis()
        val th = thoughts(c); val id = ideas(c)
        return JSONObject().put("on", enabled(c)).put("thinking", thinking).put("job", Jobs.running ?: "")
            .put("thoughts", JSONArray().apply { for (i in maxOf(0, th.length() - 8) until th.length()) put(th.get(i)) })
            .put("ideas", JSONArray().apply { for (i in maxOf(0, id.length() - 5) until id.length()) put(id.get(i)) })
            .put("suggest", JSONArray(openSuggestions(suggestions(c), now)))
            .put("jobs", Jobs.all(c).let { j -> JSONArray().apply { for (i in maxOf(0, j.length() - 5) until j.length()) put(j.get(i)) } })
            .put("loops", JSONArray(Loops.open(Loops.all(c))))
    }
}
