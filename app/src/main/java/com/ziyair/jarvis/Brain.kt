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
            Memory.promptBlock(context) +
            "\nRight now it is " + now + "." +
            (if (DriveMode.on) "\nHE IS DRIVING: one short sentence per reply, never ask him to look at or touch the phone." else "") +
            (Inbox.lastAnnounced?.let { if (System.currentTimeMillis() - it.at < 600_000) "\nYou just read him a message from ${it.sender} on ${it.app}: \"${it.text}\". If he dictates a reply, read it back and ask to send; reply with reply_message who='last'." else "" } ?: "") +
            "\nYou are speaking out loud through his phone, so never use lists, markdown, emojis or URLs." +
            "\nYou can act on his phone with your tools (timers, reminders, alarms, calls, texts, directions, music, weather). When he tells you something worth keeping for future days, or asks you to remember something, save it with remember. Use them when he asks, then confirm in one short line. Times you pass to tools are 24-hour local time." +
            "\nRun his day hands-free: whenever he mentions money he made, log it with log_earnings without asking; whenever he mentions feeding, changing or the baby sleeping or waking, log it with log_baby. If he asked you something and a tool can do it, do it rather than telling him how."
    }

    /** Ask Jarvis something. Returns the spoken reply, or a short in-character error line. */
    fun ask(text: String): String {
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
        var rounds = 0
        while (true) {
            val body = JSONObject()
                .put("model", cfg.optString("model", "claude-haiku-5-5"))
                .put("max_tokens", cfg.optInt("max_tokens", 300))
                .put("system", systemPrompt(cfg))
                .put("tools", tools)
                .put("messages", clean)
            val (code, resp) = try { post(key, body) } catch (e: Exception) { return "I can't reach the network right now, sir." }
            when {
                code == 401 || code == 403 -> return "My Claude key isn't working, sir. Check it in the Jarvis app."
                code == 402 || resp.contains("credit balance", true) -> return "I'm out of API credit, sir. Top it up in the Claude Console."
                code == 429 || code == 529 -> return "I'm a bit overloaded, sir. Give me a moment and ask again."
                code !in 200..299 -> return "Something went wrong on my end, sir. Error $code."
            }
            val j = JSONObject(resp)
            val content = j.getJSONArray("content")
            val sb = StringBuilder()
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                when (part.optString("type")) {
                    "text" -> sb.append(part.getString("text"))
                    "tool_use" -> {
                        val out = actions.run(part.getString("name"), part.optJSONObject("input") ?: JSONObject())
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", part.getString("id")).put("content", out))
                    }
                }
            }
            if (results.length() > 0 && rounds < 4) {
                rounds++
                clean.put(JSONObject().put("role", "assistant").put("content", content))
                clean.put(JSONObject().put("role", "user").put("content", results))
                continue
            }
            val reply = sb.toString().trim().ifBlank { "Done, sir." }
            prefs.addTurn("user", text)
            prefs.addTurn("assistant", reply)
            return reply
        }
    }

    private fun post(key: String, body: JSONObject): Pair<Int, String> {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000; c.readTimeout = 30000
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
