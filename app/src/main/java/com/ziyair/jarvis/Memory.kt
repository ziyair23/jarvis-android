package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Jarvis's long-term memory: short facts about him that carry across days. Stored only on the phone. */
object Memory {
    private const val MAX = 120

    fun all(context: Context): List<String> {
        val a = Prefs(context).memory()
        return (0 until a.length()).map { a.getJSONObject(it).optString("t") }.filter { it.isNotBlank() }
    }

    @Synchronized fun add(context: Context, fact: String): Boolean {
        val f = fact.trim().removeSuffix(".")
        if (f.length < 3) return false
        val prefs = Prefs(context)
        val a = prefs.memory()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("t").equals(f, true)) return false
        a.put(JSONObject().put("t", f).put("at", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - MAX) until a.length()) keep.put(a.get(i))
        prefs.saveMemory(keep)
        return true
    }

    /** Remove facts that mention the given words. Returns how many were removed. */
    @Synchronized fun forget(context: Context, about: String): Int {
        val words = about.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return 0
        val prefs = Prefs(context)
        val a = prefs.memory()
        val keep = JSONArray()
        var n = 0
        for (i in 0 until a.length()) {
            val t = a.getJSONObject(i).optString("t").lowercase()
            if (words.all { t.contains(it) }) n++ else keep.put(a.get(i))
        }
        prefs.saveMemory(keep)
        return n
    }

    fun clear(context: Context) = Prefs(context).saveMemory(JSONArray())

    fun promptBlock(context: Context): String {
        val facts = all(context)
        if (facts.isEmpty()) return ""
        return "\n\nWhat you remember about him from earlier conversations (use it naturally, don't recite it):\n- " + facts.joinToString("\n- ")
    }

    /**
     * After a conversation, quietly pick out anything worth remembering long-term (people, plans, preferences, places,
     * routines, things he asked you to keep in mind). One small, cheap call to the same Claude model.
     */
    fun learn(context: Context) {
        val prefs = Prefs(context)
        val key = prefs.claudeKey
        if (key.isBlank()) return
        val h = prefs.history()
        val recent = StringBuilder()
        for (i in maxOf(0, h.length() - 10) until h.length()) {
            val m = h.getJSONObject(i)
            recent.append(if (m.getString("role") == "user") "Him: " else "Jarvis: ").append(m.getString("content")).append("\n")
        }
        if (recent.isBlank()) return
        val known = all(context)
        val prompt = "You maintain the long-term memory of a personal voice assistant.\n" +
            "Already remembered:\n" + (if (known.isEmpty()) "(nothing yet)" else known.joinToString("\n") { "- $it" }) +
            "\n\nLatest conversation:\n" + recent +
            "\nReturn ONLY JSON like {\"add\":[\"...\"],\"remove\":[\"...\"]}. " +
            "\"add\": new durable facts about him worth remembering for future days, each one short sentence in third person " +
            "(people in his life and their names, plans and dates, preferences, places, routines, ongoing projects, things he asked to remember). " +
            "Skip small talk, one-off questions, weather, and anything already remembered. " +
            "\"remove\": exact text of remembered facts that this conversation shows are wrong or out of date. Use empty lists if nothing changes."
        val body = JSONObject()
            .put("model", Brain(context).config().optString("memory_model", "claude-haiku-5-5"))
            .put("max_tokens", 300)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        try {
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) { c.disconnect(); return }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            Spend.record(context, Brain(context).config(), body.optString("model"), JSONObject(resp).optJSONObject("usage"))
            val content = JSONObject(resp).getJSONArray("content")
            var text = ""
            for (i in 0 until content.length()) if (content.getJSONObject(i).optString("type") == "text") text += content.getJSONObject(i).getString("text")
            val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            json.optJSONArray("remove")?.let { r ->
                val a = prefs.memory(); val keep = JSONArray()
                val drop = (0 until r.length()).map { r.optString(it).trim().lowercase() }.toSet()
                for (i in 0 until a.length()) if (a.getJSONObject(i).optString("t").trim().lowercase() !in drop) keep.put(a.get(i))
                prefs.saveMemory(keep)
            }
            json.optJSONArray("add")?.let { ad -> for (i in 0 until minOf(ad.length(), 6)) add(context, ad.optString(i)) }
            LogBus.changed()
        } catch (_: Exception) { }
    }
}
