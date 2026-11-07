package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A fast brain for casual back-and-forth ("how's it going", banter, a quick follow-up): Groq runs open models very
 * quickly (a free key from console.groq.com), so Jarvis starts answering in about a quarter of a second instead of two.
 * Anything that needs doing (reminders, texts, music, his data, the web) or a real question still goes to Claude:
 * the fast brain hands it back the moment it sees that, before saying a word.
 */
object FastChat {
    /** Words that mean he wants something done or looked up: those always go to Claude. */
    private val NEEDS_CLAUDE = Regex("\\b(remind|reminder|timer|alarm|text|message|call|send|reply|play|pause|skip|open|launch|log|add|list|bill|bills|appointment|schedule|calendar|" +
        "weather|earn|earned|made|spent|spend|money|budget|macan|goal|feed|fed|diaper|asleep|awake|package|deliver|delivery|price|deal|buy|order|light|lights|music|song|" +
        "navigate|directions|remember|forget|note|search|look up|google|news|score|game|what time|how much|how many|dash|dashing|offer|plan|job|handle|meal|dinner|grocer|" +
        "quiet|silent|volume|screen|photo|picture|camera|email|follow up|scan|update|build|fix|agent)\\b")

    /** Casual talk the fast brain can take: short, no action words, not a real question for Claude. Pure, unit-tested. */
    fun route(t: String, deep: Boolean, chatFirst: Boolean = false): Boolean {
        // talk mode: real conversation (opinions, ideas, explaining things) stays on the fast free brain too; it hands
        // anything needing his data, the web or an action back to Claude before saying a word
        if (deep && !smallTalk(t) && !chatFirst) return false
        val n = Reflex.norm(t)
        if (deep && smallTalk(t)) return true
        if (n.isBlank() || n.split(" ").size > (if (chatFirst) 45 else 25)) return false
        return !NEEDS_CLAUDE.containsMatchIn(n)
    }

    /** Small talk ("how's it going", "what's up"): the fast brain takes it even though it's phrased as a question. Pure. */
    fun smallTalk(t: String) = Regex("^(how'?s it going|how are you( doing)?|how you doing|what'?s up|whats good|what'?s good|how was your day|how'?s your day( going)?|how have you been|good (morning|evening|night|afternoon)|hey|hi|hello|yo|sup|you good|you there|are you there)( jarvis)?$")
        .matches(Reflex.norm(t))

    /** The fast brain claimed it DID something (it can't do anything): never let that be said. Pure, unit-tested. */
    fun claimsAction(r: String) = Regex("(?i)\\b(timer|reminder|alarm)\\b.*\\b(set|on|started|cancel+ed|stopped|off)\\b|\\b(set|cancel+ed|started|added|logged|sent|texted|called|playing|opened|scheduled|saved|turned (on|off))\\b.*\\b(for you|sir|now|it|that|the)\\b|^(done|all set|got it,? (it'?s|i'?ve))").containsMatchIn(r)

    /** The fast brain decided Claude should take it. Pure. */
    fun handsOff(start: String) = start.trimStart().startsWith("[handoff", true) || start.trimStart().startsWith("[hand off", true)

    fun on(c: Context): Boolean {
        val ok = Prefs(c).groqKey.isNotBlank() && Brain(c).config().optBoolean("fast_chat", true)
        if (!ok && System.currentTimeMillis() - warnedAt > 6 * 3600_000L) { warnedAt = System.currentTimeMillis(); Trace.log(c, "fastchat", if (Prefs(c).groqKey.isBlank()) "off: no Groq key saved in setup" else "off in settings") }
        return ok
    }
    @Volatile private var warnedAt = 0L

    /**
     * Streams a reply in Jarvis's voice. Returns the full reply, or null to hand it to Claude (he asked for something,
     * or Groq is unreachable). Nothing reaches [onText] when it hands off.
     */
    fun ask(c: Context, system: String, history: JSONArray, onText: (String) -> Unit): String? {
        val p = Prefs(c); val cfg = Brain(c).config()
        val models = cfg.optJSONArray("fast_chat_models")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b")
        val skip = p.sp.getString("fast_chat_bad", "").orEmpty().split(",").toSet()
        for (model in models.filter { it !in skip }) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            for (i in 0 until history.length()) msgs.put(history.get(i))
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put("max_tokens", cfg.optInt("fast_chat_max_tokens", 300)).put("temperature", 0.7)
            if (model.contains("gpt-oss")) body.put("reasoning_effort", "low").put("include_reasoning", false)
            val t0 = System.currentTimeMillis()
            val r = stream(p.groqKey, body, onText)
            when {
                r.first == 200 -> { Trace.log(c, "fastchat", "$model ${System.currentTimeMillis() - t0}ms${if (r.second == null) " (handed to Claude)" else ""}"); return r.second }
                r.first == 404 || (r.first == 400 && Regex("model|decommission|reasoning|include_reasoning", RegexOption.IGNORE_CASE).containsMatchIn(r.third)) -> {
                    Trace.log(c, "fastchat", "$model not available: ${r.third.take(120)}")
                    p.sp.edit().putString("fast_chat_bad", (skip + model).filter { it.isNotBlank() }.joinToString(",")).apply()
                }
                else -> { Trace.log(c, "fastchat", "Groq ${r.first}: ${r.third.take(120)}; Claude takes it"); return null }
            }
        }
        return null
    }

    /** (status, reply or null for a hand-off, error text). Holds the first few characters back until it knows it isn't handing off. */
    private fun stream(key: String, body: JSONObject, onText: (String) -> Unit): Triple<Int, String?, String> = try {
        val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 5000; conn.readTimeout = 15000
        conn.setRequestProperty("content-type", "application/json"); conn.setRequestProperty("authorization", "Bearer $key")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) Triple(code, null, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
        else {
            val all = StringBuilder(); var decided = false; var handoff = false
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val d = line.substring(5).trim(); if (d == "[DONE]") break
                    val delta = try { JSONObject(d).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty() } catch (_: Exception) { "" }
                    if (delta.isEmpty() || delta == "null") continue
                    all.append(delta)
                    if (!decided) {
                        val head = all.toString().trimStart()
                        if (head.length < 10 && "[handoff".startsWith(head.lowercase().take(8)) && head.startsWith("[")) continue
                        decided = true; handoff = handsOff(head)
                        if (handoff) break
                        onText(all.toString())
                    } else onText(delta)
                }
            }
            if (!decided && all.isNotBlank()) { handoff = handsOff(all.toString()); if (!handoff) onText(all.toString()) }
            Triple(200, if (handoff || all.isBlank()) null else all.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim(), "")
        }
    } catch (e: Exception) { Triple(0, null, e.message ?: "network") }
}
