package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jarvis's brain: the Claude API, with the personality loaded from config. */
class Brain(private val context: Context) {
    private val prefs = Prefs(context)

    /** The personality and settings. Downloaded from GitHub when available, otherwise the copy built into the app. */
    fun config(): JSONObject {
        val remote = prefs.remoteConfig
        if (remote.isNotBlank()) try { return JSONObject(remote) } catch (_: Exception) {}
        return JSONObject(context.assets.open("persona.json").bufferedReader().use { it.readText() })
    }

    /** Fetch the latest personality file from the GitHub repo (works when the repo is public). */
    fun refreshConfig() {
        try {
            val c = URL(CONFIG_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode == 200) {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text) // only keep it if it's valid
                prefs.remoteConfig = text
            }
            c.disconnect()
        } catch (_: Exception) { }
    }

    private fun systemPrompt(cfg: JSONObject): String {
        val now = SimpleDateFormat("EEEE, MMMM d, h:mm a", Locale.US).format(Date())
        val her = prefs.herName
        return cfg.optString("system") +
            (if (prefs.notes.isNotBlank()) "\n\nAbout him and his day: " + prefs.notes else "") +
            (if (her.isNotBlank()) "\nHis partner's name: " + her + "." else "") +
            (if (prefs.babyName.isNotBlank()) "\nHis baby son is ${prefs.babyName}. Speech-to-text often mishears that name (as Zaire, Zyair, Ziair, Isaiah, Xavier, Sahir, Zayer, 'the air' and similar): treat any of those as ${prefs.babyName}." else "") +
            "\nHe talks naturally, not in commands, and speech-to-text garbles words. Work out what he means from loose wording and act on it: 'note that we just fed him', 'he ate', 'bottle's done', 'we fed the baby' all mean log_baby feed; 'changed him', 'he pooped' mean a diaper; 'he's down', 'he's out' mean asleep. If the meaning is reasonably clear, do it and confirm in a few words; only ask a short question when it's genuinely ambiguous. Never ask him to rephrase or use specific words." +
            Memory.promptBlock(context) +
            "\nRight now it is " + now + "." +
            todaySoFar() +
            (if (DriveMode.on) "\nHE IS DRIVING: one short sentence per reply, never ask him to look at or touch the phone." else "") +
            (Inbox.lastAnnounced?.let { if (System.currentTimeMillis() - it.at < 600_000) "\nYou just read him a message from ${it.sender} on ${it.app}: \"${it.text}\". If he dictates a reply, read it back and ask to send; reply with reply_message who='last'." else "" } ?: "") +
            "\nYou are speaking out loud through his phone, so never use lists, markdown, emojis or URLs." +
            "\nYou can act on his phone with your tools (timers, reminders, alarms, calls, texts, directions, music, weather). When he tells you something worth keeping for future days, or asks you to remember something, save it with remember. Use them when he asks, then confirm in one short line. Times you pass to tools are 24-hour local time." +
            "\nRun his day hands-free: whenever he mentions money he made, log it with log_earnings without asking; whenever he mentions feeding, changing or the baby sleeping or waking, log it with log_baby. If he asked you something and a tool can do it, do it rather than telling him how." +
            "\nYou can drive his phone: open_app for any app, media_control for play/pause/skip, stop_navigation to cancel directions, and screen_read/screen_tap/screen_type/screen_action to work inside any app step by step (e.g. open YouTube, tap the search icon, type, submit, tap the first video). Keep going until it's done, then confirm in a few words. Never post, send, buy or delete anything through the screen without his clear yes." +
            "\nOne reply per request: when you use one of his phone tools, don't write any words alongside the tool call, wait for the result and then answer once. After a web_search, always say the actual answer (scores, times, facts); never reply with just 'Done'. Tool results that say Logged, Set, Opened, Sent, Done and the like mean it worked: say so plainly and never follow up with a 'correction', a second answer or an apology. Only answer what he just asked; don't add notes about earlier conversation, the dashboard numbers or older messages unless he asks." +
            "\nWhen he asks what something is, to read something, or anything that needs eyes ('what's this', 'read this', 'what does this say'), use look, then answer from the photo in a sentence or two. For anything current or that you're not sure of (scores, games last night, news, prices, store hours, facts), use web_search, then answer in a sentence or two out loud: no links, no source names unless he asks."
    }

    private fun todaySoFar(): String = try {
        val baby = Baby.status(context)
        val earned = Earnings.today(context)
        "\nToday so far (from his Jarvis dashboard): baby: $baby Earned today: ${Earnings.money(earned)}."
    } catch (_: Exception) { "" }

    /** Ask Jarvis something. Returns the spoken reply, or a short in-character error line. */
    fun ask(text: String, followUp: Boolean = false, onAck: (() -> Unit)? = null): String {
        val key = prefs.claudeKey
        if (key.isBlank()) return "I need my Claude key first, sir. Open the Jarvis app and add it."
        val cfg = config()
        val msgs = JSONArray()
        val h = prefs.history()
        val start = maxOf(0, h.length() - cfg.optInt("history_turns", 24))
        val stamp = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
        for (i in start until h.length()) {
            val m = h.getJSONObject(i)
            val at = m.optLong("at", 0)
            var content = m.getString("content")
            // older turns carry their time so he knows they're from before, not right now
            if (m.getString("role") == "user" && System.currentTimeMillis() - at > 2 * 3600_000L) content = "[earlier, ${stamp.format(Date(at))}] $content"
            msgs.put(JSONObject().put("role", m.getString("role")).put("content", content))
        }
        // the API wants alternating turns starting with the user
        val clean = JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val role = m.getString("role")
            if (clean.length() == 0 && role != "user") continue
            val last = if (clean.length() > 0) clean.getJSONObject(clean.length() - 1) else null
            if (last != null && last.getString("role") == role) last.put("content", last.getString("content") + "\n" + m.getString("content"))
            else clean.put(JSONObject().put("role", role).put("content", m.getString("content")))
        }
        val tail = if (clean.length() > 0) clean.getJSONObject(clean.length() - 1) else null
        if (tail != null && tail.getString("role") == "user") tail.put("content", tail.getString("content") + "\n" + text)
        else clean.put(JSONObject().put("role", "user").put("content", text))

        val actions = Actions(context)
        val tools = actions.tools()
        // the tool list is the same every time: let Claude cache it (much cheaper on repeat questions)
        if (tools.length() > 0) tools.getJSONObject(tools.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral"))
        // real questions get the smarter model and room for a proper answer; quick commands stay fast and cheap
        val deep = !followUp && realQuestion(text) && cfg.optBoolean("smart_answers", true)
        val model = if (deep) cfg.optString("smart_model", "claude-sonnet-5-5") else cfg.optString("model", "claude-haiku-5-5")
        val maxTokens = if (deep) cfg.optInt("smart_max_tokens", 700) else cfg.optInt("max_tokens", 400)
        if (cfg.optBoolean("web_search", true)) tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search")
            .put("max_uses", cfg.optInt("web_search_max", 3))
            .put("user_location", JSONObject().put("type", "approximate").put("city", prefs.city.ifBlank { "Spokane" })
                .put("region", "Washington").put("country", "US").put("timezone", java.util.TimeZone.getDefault().id)))
        var rounds = 0
        var earlierText = ""
        while (true) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", systemPrompt(cfg) + (if (deep) "\nThis is a real question: give him a proper spoken answer, three to six natural sentences with the actual details (names, numbers, scores, times), like a knowledgeable friend talking. No lists or headings." else "") + (if (!followUp) "\nThe wake word can misfire on TV or nearby conversation. If what you heard is clearly not a request or question for you (background talk, half a sentence, TV), reply with exactly [ignore]." else "") + if (followUp) "\nThis was picked up WITHOUT the wake word, while you waited a few seconds for his answer to your question. If it's not an answer to you or a request for you (he's talking to someone else, background chatter, TV, a phone call), reply with exactly [ignore] and nothing else." else "")
                .put("tools", tools)
                .put("messages", clean)
            val (code, resp) = try { post(key, body) } catch (e: Exception) { return "I can't reach the network right now, sir." }
            // web search not turned on for his Claude account: carry on without it
            if (code == 400 && resp.contains("web_search", true) && tools.length() > 0 &&
                tools.getJSONObject(tools.length() - 1).optString("name") == "web_search") { tools.remove(tools.length() - 1); continue }
            when {
                code == 401 || code == 403 -> return "My Claude key isn't working, sir. Check it in the Jarvis app."
                code == 402 || resp.contains("credit balance", true) -> return "I'm out of API credit, sir. Top it up in the Claude Console."
                code == 429 || code == 529 -> return "I'm a bit overloaded, sir. Give me a moment and ask again."
                code !in 200..299 -> return "Something went wrong on my end, sir. Error $code."
            }
            val j = JSONObject(resp)
            Spend.record(context, cfg, model, j.optJSONObject("usage"))
            val content = j.getJSONArray("content")
            // a long web search can pause mid-turn: hand it straight back so it finishes
            if (j.optString("stop_reason") == "pause_turn" && rounds < 10) {
                rounds++
                clean.put(JSONObject().put("role", "assistant").put("content", content))
                continue
            }
            val sb = StringBuilder()
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                when (part.optString("type")) {
                    "text" -> sb.append(part.getString("text"))
                    "tool_use" -> {
                        val out: Any = if (part.getString("name") == "look") look() else actions.run(part.getString("name"), part.optJSONObject("input") ?: JSONObject())
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", part.getString("id")).put("content", out))
                    }
                }
            }
            if (results.length() > 0 && rounds < 10) {
                if (rounds == 0 && slow.any { resp.contains("\"name\":\"$it\"") }) onAck?.invoke()
                rounds++
                if (sb.isNotBlank()) earlierText = sb.toString().trim()
                clean.put(JSONObject().put("role", "assistant").put("content", content))
                clean.put(JSONObject().put("role", "user").put("content", results))
                continue
            }
            // searched (or thought) but said nothing: ask for the spoken answer instead of "Done"
            if (sb.isBlank() && earlierText.isBlank() && content.length() > 0 && rounds < 10) {
                rounds++
                clean.put(JSONObject().put("role", "assistant").put("content", content))
                clean.put(JSONObject().put("role", "user").put("content", "(Now tell him the answer out loud, with the actual details.)"))
                continue
            }
            // speak one final answer only; strip any "correction" second-guessing
            val reply = sb.toString().trim().ifBlank { earlierText.ifBlank { "Done, sir." } }
                .replace(Regex("^(correction|actually|apologies|my apologies|sorry)[,.:!]?\\s*(sir[,.]?\\s*)?", RegexOption.IGNORE_CASE), "")
                .replaceFirstChar { it.uppercase() }
            if (reply.contains("[ignore]", true)) return ""
            prefs.addTurn("user", text)
            prefs.addTurn("assistant", reply)
            return reply
        }
    }

    /** Jarvis's eyes: a photo from the back camera, sent to Claude to look at. */
    private fun look(): Any {
        val f = Eyes.capture(context) ?: return Eyes.why
        val data = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Photo just taken with his phone's back camera, pointed at what he's asking about."))
            .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", data)))
    }

    /** A genuine question (scores, how/why, advice), as opposed to a quick command for the phone. */
    private fun realQuestion(t: String): Boolean {
        val s = t.lowercase().trim()
        if (Regex("^(log|set|remind|start|stop|cancel|turn|call|text|send|open|play|pause|skip|resume|navigate|take me|lights?|fed|he (just )?(ate|pooped)|changed|made|note|remember|forget|run|dash|goodnight|good night|status report|answer|decline|volume|flashlight)\\b").containsMatchIn(s)) return false
        if (Regex("^(what('?s| is) the time|what time is it|what day is it)").containsMatchIn(s)) return false
        return s.endsWith("?") || Regex("^(what|who|whose|when|where|why|how|which|is|are|was|were|do|does|did|can|could|should|would|will|tell me|explain|give me|any |did the|who won|what about)\\b").containsMatchIn(s)
    }

    private val slow = listOf("run_protocol", "screen_tap", "screen_type", "screen_read", "stop_navigation", "status_report", "lights", "look", "open_app")

    private fun post(key: String, body: JSONObject): Pair<Int, String> {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000; c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to resp
    }

    companion object {
        const val REPO = "ziyair23/jarvis-android"
        const val CONFIG_URL = "https://raw.githubusercontent.com/$REPO/main/config/persona.json"
    }
}
