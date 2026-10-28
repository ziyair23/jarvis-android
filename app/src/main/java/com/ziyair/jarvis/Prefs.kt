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

    var city: String
        get() = sp.getString("city", "") ?: ""
        set(v) = sp.edit().putString("city", v.trim()).apply()
    var cityLat: String
        get() = sp.getString("city_lat", "") ?: ""
        set(v) = sp.edit().putString("city_lat", v).apply()
    var cityLon: String
        get() = sp.getString("city_lon", "") ?: ""
        set(v) = sp.edit().putString("city_lon", v).apply()
    var cityFor: String
        get() = sp.getString("city_for", "") ?: ""
        set(v) = sp.edit().putString("city_for", v).apply()

    /** Daily routine for heads-ups, as "HH:MM-HH:MM" ranges and a bedtime. */
    var lunch: String
        get() = sp.getString("lunch", "11:00-14:00") ?: "11:00-14:00"
        set(v) = sp.edit().putString("lunch", v.trim()).apply()
    var dinner: String
        get() = sp.getString("dinner", "17:00-21:00") ?: "17:00-21:00"
        set(v) = sp.edit().putString("dinner", v.trim()).apply()
    var bedtime: String
        get() = sp.getString("bedtime", "00:00") ?: "00:00"
        set(v) = sp.edit().putString("bedtime", v.trim()).apply()
    var talkFirst: Boolean
        get() = sp.getBoolean("talk_first", true)
        set(v) = sp.edit().putBoolean("talk_first", v).apply()

    /** Driving: the car's Bluetooth name (part of it is enough), and when to read messages aloud: "driving", "always" or "off". */
    var carName: String
        get() = sp.getString("car_name", "") ?: ""
        set(v) = sp.edit().putString("car_name", v.trim()).apply()
    var readMessages: String
        get() = sp.getString("read_messages", "driving") ?: "driving"
        set(v) = sp.edit().putString("read_messages", v).apply()
    /** Govee lights developer key (free, from the Govee Home app). */
    var goveeKey: String
        get() = sp.getString("govee_key", "") ?: ""
        set(v) = sp.edit().putString("govee_key", v.trim()).apply()
    var babyName: String
        get() = sp.getString("baby_name", "Ziyair") ?: "Ziyair"
        set(v) = sp.edit().putString("baby_name", v.trim()).apply()
    var announceCalls: Boolean
        get() = sp.getBoolean("announce_calls", true)
        set(v) = sp.edit().putBoolean("announce_calls", v).apply()
    var wakeScreen: Boolean
        get() = sp.getBoolean("wake_screen", true)
        set(v) = sp.edit().putBoolean("wake_screen", v).apply()

    /** Realistic voice: Google Cloud Text-to-Speech API key and voice name (blank = default Chirp 3 HD voice). */
    var ttsKey: String
        get() = sp.getString("tts_key", "") ?: ""
        set(v) = sp.edit().putString("tts_key", v.trim()).apply()
    var voiceName: String
        get() = sp.getString("voice_name", "") ?: ""
        set(v) = sp.edit().putString("voice_name", v.trim()).apply()

    /** Long-term memory: [{"t":"fact","at":ms}] */
    fun memory(): JSONArray = try { JSONArray(sp.getString("memory", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveMemory(a: JSONArray) = sp.edit().putString("memory", a.toString()).apply()

    /** Pending timers and reminders: [{"id":n,"at":ms,"text":"...","loud":bool,"label":"..."}] */
    fun reminders(): JSONArray = try { JSONArray(sp.getString("reminders", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveReminders(a: JSONArray) = sp.edit().putString("reminders", a.toString()).apply()

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
