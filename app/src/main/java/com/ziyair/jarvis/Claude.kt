package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One way to call Claude for everything that works in the background (agents, whole jobs, Jarvis's own mind).
 * If his account turns down an optional setting (thinking, effort), it's dropped, remembered, and the call is tried again,
 * so a newer setting can never break a job. Busy or overloaded: waits a moment and tries once more.
 */
object Claude {
    @Volatile var lastError = ""; private set
    @Volatile var lastCode = 0; private set

    fun post(key: String, body: JSONObject, readMs: Int = 90_000): JSONObject? = try {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = readMs
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        lastCode = c.responseCode
        val ok = lastCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) lastError = text
        if (ok) JSONObject(text) else null
    } catch (e: Exception) { lastCode = 0; lastError = "network: ${e.message}"; null }

    /** Which optional setting the error is about, so it can be left out from now on (pure, unit-tested). */
    fun refused(body: JSONObject, error: String): String? {
        val think = body.optJSONObject("thinking")?.optString("type")
        if (error.contains("signature", true)) return null
        return when {
            body.has("output_config") && Regex("output_config|effort", RegexOption.IGNORE_CASE).containsMatchIn(error) -> "effort_refused"
            think == "adaptive" && error.contains("thinking", true) -> "adaptive_refused"
            think == "disabled" && error.contains("thinking", true) -> "thinking_param_refused"
            else -> null
        }
    }

    /** Thinking on for real work, at a set effort (both dropped by [send] if his account won't take them). */
    fun smart(body: JSONObject, effort: String): JSONObject =
        body.put("thinking", JSONObject().put("type", "adaptive")).put("output_config", JSONObject().put("effort", effort))

    fun send(c: Context, body: JSONObject): JSONObject? {
        val p = Prefs(c).sp
        val key = Prefs(c).claudeKey
        if (key.isBlank()) { lastError = "no key"; return null }
        var busyRetry = false
        repeat(5) {
            if (p.getBoolean("effort_refused", false)) body.remove("output_config")
            if (p.getBoolean("adaptive_refused", false) && body.optJSONObject("thinking")?.optString("type") == "adaptive") body.remove("thinking")
            if (p.getBoolean("thinking_param_refused", false) && body.optJSONObject("thinking")?.optString("type") == "disabled") body.remove("thinking")
            post(key, body)?.let { return it }
            val flag = if (lastCode == 400) refused(body, lastError) else null
            when {
                flag != null -> { p.edit().putBoolean(flag, true).apply(); Trace.log(c, "claude", "$flag: ${lastError.take(140)}") }
                !busyRetry && (lastCode == 429 || lastCode == 529 || lastCode >= 500 || lastCode == 0) -> { busyRetry = true; Thread.sleep(1500) }
                else -> return null
            }
        }
        return null
    }

    class Step(val tool: String, val input: JSONObject, val out: String)

    /**
     * A full working loop: Claude thinks, uses tools (run by [handle]), reads the results, and keeps going until it's
     * done or out of rounds. Returns its final words and every step it took. Null if Claude couldn't be reached at all.
     */
    fun work(c: Context, model: String, system: String, task: String, tools: org.json.JSONArray, effort: String?, maxTokens: Int, maxRounds: Int,
             handle: (String, JSONObject) -> String): Pair<String, List<Step>>? {
        val cfg = Brain(c).config()
        val steps = ArrayList<Step>()
        if (tools.length() > 0) tools.getJSONObject(tools.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral"))
        val sys = org.json.JSONArray().put(JSONObject().put("type", "text").put("text", system).put("cache_control", JSONObject().put("type", "ephemeral")))
        val msgs = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", task))
        for (round in 1..maxRounds) {
            val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", sys).put("messages", msgs)
            if (tools.length() > 0) body.put("tools", tools)
            if (effort != null) smart(body, effort) else body.put("thinking", JSONObject().put("type", "disabled"))
            val resp = send(c, body) ?: return if (steps.isEmpty()) null else "" to steps
            Spend.record(c, cfg, model, resp.optJSONObject("usage"))
            val content = resp.optJSONArray("content") ?: org.json.JSONArray()
            val text = StringBuilder(); val results = org.json.JSONArray()
            for (i in 0 until content.length()) {
                val p = content.getJSONObject(i)
                when (p.optString("type")) {
                    "text" -> text.append(p.optString("text"))
                    "tool_use" -> {
                        val name = p.optString("name"); val input = p.optJSONObject("input") ?: JSONObject()
                        val out = try { handle(name, input) } catch (e: Exception) { "That didn't work: ${e.message}" }
                        steps.add(Step(name, input, out))
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", p.optString("id")).put("content", out))
                    }
                }
            }
            if (resp.optString("stop_reason") == "pause_turn") { msgs.put(JSONObject().put("role", "assistant").put("content", content)); continue }
            if (results.length() == 0) return text.toString().trim() to steps
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            msgs.put(JSONObject().put("role", "user").put("content", results))
        }
        return "" to steps
    }

    /** Spend is near his monthly budget: background thinking steps down to the small model. */
    fun nearBudget(c: Context, share: Double = 0.85): Boolean { val p = Prefs(c); return Spend.thisMonth(p) >= Spend.budget(p) * share }
}
