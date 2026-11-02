package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Coach: makes Jarvis a little better every day.
 *  - In the moment: when Marcus corrects him, Jarvis owns it in a few words, puts it right and saves the lesson.
 *  - Every night: Coach reads the day's flight log (what he misheard, what Marcus had to repeat, what failed, replies
 *    that ran long) and writes lessons Jarvis follows from then on.
 *  - Small fixes: Coach can nudge a handful of safe settings (how long he waits for Marcus to finish, reply length...),
 *    a small step at a time, each one undoable. Anything bigger becomes a suggestion for Marcus to say yes to.
 *  - Morning: one short line on what got better.
 * Coach can never touch the rules about confirming texts, calls, posts or purchases.
 */
object Coach {
    private const val MAX_LESSONS = 25

    // ---------------- pure rules (unit-tested) ----------------
    private val CORRECTION = Regex("(?i)^(no+[,.!]?\\s|nah[,.]?\\s|not that|that'?s (not|wrong)|wrong|you (mis)?heard|you got (it|that) wrong|i said\\b|i meant\\b|i didn'?t (say|ask)|that'?s not what i|not what i (said|asked|meant)|you misunderstood|listen[,.]?\\s|i told you)")
    private val NOT_CORRECTION = Regex("(?i)^(no+[,.!]?\\s*(thanks?|thank you|that'?s (it|all|fine|good)|i'?m good|worries|problem|need|never ?mind)\\b|no+[.!]?$)")
    /** Did he just correct Jarvis? ("no, I said Jesse", "that's wrong", "you misheard me") */
    fun isCorrection(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4 || NOT_CORRECTION.containsMatchIn(t)) return false
        return CORRECTION.containsMatchIn(t)
    }

    /** The only settings Coach may tune, and the furthest it can move one in a single night. */
    val TUNABLE = mapOf(
        "ear_silence_ms" to 150.0, "done_talking_ms" to 200.0, "wake_threshold" to 0.05, "conversation_seconds" to 3.0,
        "max_tokens" to 60.0, "smart_max_tokens" to 100.0, "still_working_ms" to 1500.0, "music_wake_factor" to 0.05, "piper_speed" to 0.05)

    /** Keeps a nightly change small: at most one step from where it is now. Pure, so it's unit-tested. */
    fun clampStep(key: String, current: Double, wanted: Double): Double {
        val step = TUNABLE[key] ?: return current
        return wanted.coerceIn(current - step, current + step)
    }

    /** A lesson Coach is allowed to keep: short, not private, never loosening the confirm-before-sending rule. */
    fun okLesson(t: String): Boolean = t.length in 8..220 && !Memory.sensitive(t) && !Builder.weakensSafety(t) &&
        !Regex("(?i)\\b(send|post|call|buy|install)\\b.{0,30}\\b(without|automatically|straight away)\\b").containsMatchIn(t)

    // ---------------- lessons ----------------
    fun lessons(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("coach_lessons", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized fun addLesson(c: Context, text0: String, from: String): String {
        val t = text0.trim().removeSuffix(".").replaceFirstChar { it.uppercase() }
        if (!okLesson(t)) return "Not saved: lessons are short, never private, and can't change the rule about asking before sending."
        val a = lessons(c)
        val words = t.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        for (i in 0 until a.length()) {
            val old = a.getJSONObject(i).optString("t").lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
            if (old.isNotEmpty() && words.isNotEmpty() && (old intersect words).size >= minOf(old.size, words.size) * 0.8) {
                a.getJSONObject(i).put("t", t).put("at", System.currentTimeMillis()); save(c, a); return "Updated that lesson."
            }
        }
        a.put(JSONObject().put("t", t).put("at", System.currentTimeMillis()).put("from", from))
        save(c, a)
        Trace.log(c, "coach", "lesson ($from): $t")
        LogBus.changed()
        return "Lesson saved."
    }

    @Synchronized fun dropLesson(c: Context, text: String): String {
        val w = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (w.isEmpty()) return "Which lesson?"
        val a = lessons(c); val keep = JSONArray(); var hit: String? = null
        for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (hit == null && w.all { e.optString("t").lowercase().contains(it) }) hit = e.optString("t") else keep.put(e) }
        if (hit == null) return "No lesson matches that."
        save(c, keep); LogBus.changed(); return "Dropped: $hit."
    }

    @Synchronized fun removeAt(c: Context, at: Long) {
        val a = lessons(c); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") != at) keep.put(a.get(i))
        save(c, keep)
    }

    private fun save(c: Context, a: JSONArray) {
        val keep = JSONArray(); for (i in maxOf(0, a.length() - MAX_LESSONS) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("coach_lessons", keep.toString()).apply()
    }

    /** Remembers a correction so the nightly review can learn from it even if Jarvis didn't save a lesson himself. */
    @Synchronized fun noteCorrection(c: Context, jarvisSaid: String, heSaid: String) {
        val p = Prefs(c)
        val a = try { JSONArray(p.sp.getString("coach_corrections", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("jarvis", jarvisSaid.take(200)).put("marcus", heSaid.take(200)))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 30) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("coach_corrections", keep.toString()).apply()
        Trace.log(c, "coach", "correction noted: \"${heSaid.take(120)}\"")
    }

    // ---------------- what Jarvis sees ----------------
    fun on(c: Context) = Agents.on(c) && Agents.enabled(c, "coach") && Brain(c).config().optBoolean("coach", true)

    fun promptBlock(c: Context): String {
        if (!on(c)) return ""
        val a = lessons(c)
        val sb = StringBuilder()
        if (a.length() > 0) sb.append("\n\nLessons from your own mistakes (Coach reviews your days; follow these):\n- ")
            .append((0 until a.length()).joinToString("\n- ") { a.getJSONObject(it).optString("t") })
        sb.append("\nWhen he corrects you ('no, I said...', 'that's wrong', 'you misheard'), own it in a few words ('My mistake, sir.'), put it right straight away, " +
            "and call learn_lesson with what to do differently next time. No long apologies.")
        morningLine(c)?.let { sb.append("\nCoach's note for this morning: \"$it\" Mention it once, in one short sentence, at the very end of your first reply today. Never again after that.") }
        return sb.toString()
    }

    /** The morning line, while it's still waiting to be said (mornings only). */
    fun morningLine(c: Context): String? {
        val p = Prefs(c)
        val l = p.sp.getString("coach_morning", "").orEmpty()
        if (l.isBlank() || p.sp.getBoolean("coach_morning_said", true)) return null
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (h in 4..13) l else null
    }
    fun morningSaid(c: Context) { if (morningLine(c) != null) Prefs(c).sp.edit().putBoolean("coach_morning_said", true).apply() }

    // ---------------- the nightly review ----------------
    @Volatile private var reviewing = false

    /** Runs the review once a day, the first time the phone is used after 3 AM. Safe to call often (background thread). */
    fun maybeReview(c: Context) {
        if (!on(c) || reviewing) return
        val p = Prefs(c)
        val cut = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val since = if (System.currentTimeMillis() >= cut) cut else cut - 86_400_000L
        if (p.sp.getLong("coach_last_review", 0L) >= since) return
        if (p.claudeKey.isBlank()) return
        review(c)
    }

    /** Reads the last day and learns from it. Returns a one-line summary. Blocking. */
    fun review(c: Context): String {
        if (reviewing) return "Coach is already reviewing."
        reviewing = true
        Agents.active["coach"] = System.currentTimeMillis()
        val p = Prefs(c)
        val changes = ArrayList<String>()
        var summary = "Nothing to learn from yet."
        try {
            val last = p.sp.getLong("coach_last_review", 0L).takeIf { it > 0 } ?: (System.currentTimeMillis() - 86_400_000L)
            val day = dayLog(c, maxOf(last, System.currentTimeMillis() - 36 * 3600_000L))
            val corrections = try { JSONArray(p.sp.getString("coach_corrections", "[]")) } catch (_: Exception) { JSONArray() }
            p.sp.edit().putLong("coach_last_review", System.currentTimeMillis()).apply()
            if (day.lines().count { it.contains(" heard ") } < 3 && corrections.length() == 0) { summary = "Quiet day, nothing to review."; return summary }

            val cfg = Brain(c).config()
            val model = cfg.optString("coach_model", cfg.optString("agent_model", "claude-haiku-5-5"))
            val settings = TUNABLE.keys.joinToString(", ") { "$it=${cfg.opt(it) ?: "default"}" }
            val les = lessons(c)
            val system = "You are Coach, inside Jarvis (Marcus's voice assistant on his Android phone). Every night you read Jarvis's flight log for the day and make him better.\n" +
                "Look for: things he misheard (garbled 'heard' lines, Marcus repeating himself, 'no I said...'), times Marcus had to ask twice, tools that failed, " +
                "slow replies, replies that rambled or were too short, cut-offs (Jarvis talking over him or stopping him mid-sentence), and anything Marcus was annoyed by.\n" +
                "Then act, with your tools:\n" +
                "- save_lesson: a short, specific instruction Jarvis will follow from now on (e.g. 'When he says Jessie he means Jesse, his boss', 'Weather answers: temperature and rain only'). " +
                "At most 4 new lessons a night. Only lessons backed by what you saw. Update or drop lessons that are wrong or no longer needed.\n" +
                "- tune_setting: only when the log clearly shows a pattern (e.g. several cut-offs means ear_silence_ms up a step). One small step; it can be undone.\n" +
                "- suggest: anything bigger, for Marcus to decide (a new skill, a code fix). Never change how texts, calls, posts or purchases are confirmed.\n" +
                "- morning_line: finish with ONE short spoken line for the morning on what got better, in Jarvis's voice (e.g. 'I've got better at hearing names, sir; I'd been mishearing Jesse.'). " +
                "If nothing meaningful changed, don't call it.\n" +
                "Never save private details (health, money amounts, what people said in messages). Be brief.\n" +
                "Current lessons:\n" + (if (les.length() == 0) "(none)" else (0 until les.length()).joinToString("\n") { "- " + les.getJSONObject(it).optString("t") }) +
                "\nTunable settings now: $settings."
            val user = "Corrections he made today:\n" + (if (corrections.length() == 0) "(none)" else (0 until corrections.length()).joinToString("\n") {
                val o = corrections.getJSONObject(it); "- Jarvis: \"${o.optString("jarvis")}\" -> Marcus: \"${o.optString("marcus")}\"" }) +
                "\n\nToday's flight log:\n$day"
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
            val tools = tools()
            for (round in 1..4) {
                val body = JSONObject().put("model", model).put("max_tokens", 900).put("system", system).put("tools", tools).put("messages", msgs)
                val resp = post(p.claudeKey, body) ?: break
                Spend.record(c, cfg, model, resp.optJSONObject("usage"))
                val content = resp.optJSONArray("content") ?: JSONArray()
                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.optString("type") != "tool_use") continue
                    val out = runTool(c, b.optString("name"), b.optJSONObject("input") ?: JSONObject(), changes)
                    results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", out))
                }
                if (results.length() == 0) break
                msgs.put(JSONObject().put("role", "assistant").put("content", content))
                msgs.put(JSONObject().put("role", "user").put("content", results))
            }
            p.sp.edit().putString("coach_corrections", "[]").apply()
            summary = if (changes.isEmpty()) "Reviewed the day; nothing needed changing." else changes.joinToString("; ")
            return summary
        } catch (e: Exception) {
            Problems.log(c, "coach review: ${e.message}")
            summary = "Coach couldn't finish the review: ${e.message}"
            return summary
        } finally {
            reviewing = false
            Agents.active.remove("coach")
            logReview(c, summary)
            Trace.log(c, "coach", "review: $summary")
            LogBus.changed()
        }
    }

    /** The day's log, trimmed to what matters for learning (what he said, what Jarvis did, what went wrong). */
    private fun dayLog(c: Context, since: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return learnable(Trace.text(c, 200_000), since) { stamp ->
            try { fmt.parse("$year-$stamp")?.time?.let { if (it > System.currentTimeMillis() + 86_400_000L) null else it } } catch (_: Exception) { null }
        }
    }

    private val LINE = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\.\\d+\\s+(?:\\+\\d+\\.\\d+\\s+)?(\\S+)\\s")
    private val KEEP_TAGS = setOf("wake", "heard", "reply", "reflex", "agent", "dropped", "error", "problem", "live", "ask", "coach", "cutoff", "music", "call", "tool")

    /** The lines worth learning from, newer than [since]. Pure (time parsing passed in), so it's unit-tested. */
    fun learnable(log: String, since: Long, parse: (String) -> Long?): String {
        val out = StringBuilder()
        for (line in log.lines()) {
            val m = LINE.find(line) ?: continue
            val at = parse(m.groupValues[1])
            if (at != null && at < since) continue
            if (m.groupValues[2] !in KEEP_TAGS) continue
            out.append(line.take(300)).append('\n')
        }
        return out.toString().takeLast(40_000)
    }

    private fun logReview(c: Context, summary: String) {
        val p = Prefs(c)
        val a = try { JSONArray(p.sp.getString("coach_reviews", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("summary", summary.take(300)))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 14) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("coach_reviews", keep.toString()).apply()
    }
    fun reviews(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("coach_reviews", "[]")) } catch (_: Exception) { JSONArray() }
    fun suggestions(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("coach_suggestions", "[]")) } catch (_: Exception) { JSONArray() }

    // ---------------- Coach's tools ----------------
    val TOOL_NAMES = setOf("save_lesson", "drop_lesson", "list_lessons", "tune_setting", "suggest", "morning_line", "review_today")

    fun tools(): JSONArray {
        fun t(n: String, d: String, p: JSONObject = JSONObject(), r: List<String> = listOf()) = JSONObject().put("name", n).put("description", d)
            .put("input_schema", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(r)))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        return JSONArray()
            .put(t("save_lesson", "Save (or update) one short lesson Jarvis follows from now on.", JSONObject().put("lesson", s("The lesson, one sentence")), listOf("lesson")))
            .put(t("drop_lesson", "Drop a lesson that's wrong or no longer needed (by part of its text).", JSONObject().put("text", s("Part of the lesson")), listOf("text")))
            .put(t("list_lessons", "The lessons Jarvis follows now, recent reviews and open suggestions."))
            .put(t("tune_setting", "Nudge one safe setting by a small step (undoable). Keys: ${TUNABLE.keys.joinToString()}.",
                JSONObject().put("key", s("Setting")).put("value", JSONObject().put("type", "number")).put("why", s("What you saw in the log")), listOf("key", "value", "why")))
            .put(t("suggest", "Save a bigger improvement for Marcus to decide on.", JSONObject().put("idea", s("The idea, one sentence")), listOf("idea")))
            .put(t("morning_line", "The one short line Jarvis says tomorrow morning about what got better.", JSONObject().put("line", s("One sentence")), listOf("line")))
            .put(t("review_today", "Review today's log right now and learn from it (about ten seconds)."))
    }

    fun runTool(c: Context, name: String, input: JSONObject, changes: MutableList<String>? = null): String = try {
        when (name) {
            "save_lesson" -> addLesson(c, input.optString("lesson"), "coach").also { if (it != "Lesson saved." && it != "Updated that lesson.") Unit else changes?.add("lesson: " + input.optString("lesson").take(80)) }
            "drop_lesson" -> dropLesson(c, input.optString("text")).also { if (it.startsWith("Dropped")) changes?.add(it) }
            "list_lessons" -> {
                val a = lessons(c); val r = reviews(c); val s = suggestions(c)
                "Lessons: " + (if (a.length() == 0) "none yet" else (0 until a.length()).joinToString(" | ") { a.getJSONObject(it).optString("t") }) +
                    ". Last review: " + (if (r.length() == 0) "not yet" else r.getJSONObject(r.length() - 1).optString("summary")) +
                    ". Suggestions waiting: " + (if (s.length() == 0) "none" else (0 until s.length()).joinToString(" | ") { s.getJSONObject(it).optString("t") }) + "."
            }
            "tune_setting" -> {
                val key = input.optString("key").trim()
                if (key !in TUNABLE) "Coach can only tune: ${TUNABLE.keys.joinToString()}."
                else {
                    val cur = Brain(c).config().optDouble(key, Double.NaN)
                    val want = input.optDouble("value", Double.NaN)
                    if (want.isNaN()) "Needs a number."
                    else {
                        val v = if (cur.isNaN()) want else clampStep(key, cur, want)
                        val r = Builder.run(c, "change_setting", JSONObject().put("key", key).put("value", v).put("why", "Coach: " + input.optString("why")), false)
                        if (r.startsWith("Changed")) changes?.add("$key to ${if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()}")
                        r
                    }
                }
            }
            "suggest" -> {
                val idea = input.optString("idea").trim()
                if (idea.length < 8 || Memory.sensitive(idea)) "Not saved." else {
                    val p = Prefs(c); val a = suggestions(c)
                    a.put(JSONObject().put("t", idea.take(240)).put("at", System.currentTimeMillis()))
                    val keep = JSONArray(); for (i in maxOf(0, a.length() - 10) until a.length()) keep.put(a.get(i))
                    p.sp.edit().putString("coach_suggestions", keep.toString()).apply()
                    changes?.add("suggestion: " + idea.take(80)); "Saved for Marcus."
                }
            }
            "morning_line" -> {
                val l = input.optString("line").trim()
                if (l.length !in 8..200) "Keep it to one short sentence." else {
                    Prefs(c).sp.edit().putString("coach_morning", l).putBoolean("coach_morning_said", false).apply(); "Set for the morning."
                }
            }
            "review_today" -> review(c)
            else -> "Coach doesn't have that skill."
        }
    } catch (e: Exception) { "Coach hit a problem: ${e.message}" }

    private fun post(key: String, body: JSONObject): JSONObject? = try {
        val k = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        k.requestMethod = "POST"; k.doOutput = true
        k.connectTimeout = 10000; k.readTimeout = 90000
        k.setRequestProperty("content-type", "application/json")
        k.setRequestProperty("x-api-key", key)
        k.setRequestProperty("anthropic-version", "2023-06-01")
        k.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = k.responseCode in 200..299
        val text = (if (ok) k.inputStream else k.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        k.disconnect()
        if (ok) JSONObject(text) else null
    } catch (_: Exception) { null }
}
