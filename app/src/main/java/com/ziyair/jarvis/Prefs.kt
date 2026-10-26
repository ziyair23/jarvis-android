package com.ziyair.jarvis

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Everything Jarvis keeps lives only on this phone, in the app's private storage. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = sp.getString("claude_key", "") ?: ""
        set(v) = sp.edit().putString("claude_key", v.trim()).apply()

    var picoKey: String
        get() = sp.getString("pico_key", "") ?: ""
        set(v) = sp.edit().putString("pico_key", v.trim()).apply()

    var herName: String
        get() = sp.getString("her_name", "") ?: ""
        set(v) = sp.edit().putString("her_name", v.trim()).apply()

    var notes: String
        get() = sp.getString("notes", DEFAULT_NOTES) ?: DEFAULT_NOTES
        set(v) = sp.edit().putString("notes", v.trim()).apply()

    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    /** Latest persona config downloaded from GitHub (JSON text), if any. */
    var remoteConfig: String
        get() = sp.getString("remote_config", "") ?: ""
        set(v) = sp.edit().putString("remote_config", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** Conversation history: [{"role":"user"|"assistant","content":"..."}], newest last. */
    fun history(): JSONArray = try { JSONArray(sp.getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    fun addTurn(role: String, content: String) {
        val h = history()
        h.put(JSONObject().put("role", role).put("content", content).put("at", System.currentTimeMillis()))
        val trimmed = JSONArray()
        val start = maxOf(0, h.length() - 40)
        for (i in start until h.length()) trimmed.put(h.get(i))
        sp.edit().putString("history", trimmed.toString()).apply()
    }

    fun clearHistory() = sp.edit().putString("history", "[]").apply()

    companion object {
        const val DEFAULT_NOTES = ""
    }
}
