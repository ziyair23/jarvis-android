package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Jarvis's manners: what he says when you call him, how he acknowledges, never the same line twice in a row,
 * and aware of the time of day (first call of the morning gets "Good morning, sir.").
 */
object Persona {
    private val last = HashMap<String, String>()

    private val DEF_GREET = listOf("Sir?", "Yes, sir?", "At your service, sir.", "Go ahead, sir.", "Listening, sir.")
    private val DEF_MORNING = listOf("Morning, sir?", "Yes, sir?", "Sir?")
    private val DEF_LATE = listOf("Sir?", "Yes, sir?")
    private val DEF_ACKS = listOf("On it, sir.", "Right away, sir.", "One moment, sir.", "Checking now, sir.", "Give me a second, sir.")
    private val DEF_BYE = listOf("Very good, sir.", "I'll be here, sir.", "Standing by, boss.")
    /** Short lines the phone brain says; recorded ahead so they play instantly. */
    val QUICK = listOf("Paused.", "Resuming.", "Skipping.", "Going back.", "Music off.", "Louder.", "Quieter.", "Muted.",
        "Volume all the way up.", "Flashlight's on.", "Flashlight's off.", "Lights on, sir.", "Lights off, sir.",
        "Done, sir.", "Good morning, sir.", "Good evening, sir.", "Evening, sir?")

    fun list(cfg: JSONObject, key: String, def: List<String>): List<String> {
        val a = cfg.optJSONArray(key) ?: return def
        val out = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        return out.ifEmpty { def }
    }

    /** A random line from the list, never the same as last time. */
    @Synchronized fun pick(cfg: JSONObject, key: String, def: List<String>): String {
        val l = list(cfg, key, def)
        val choices = if (l.size > 1) l.filter { it != last[key] } else l
        return choices.random().also { last[key] = it }
    }

    fun ack(cfg: JSONObject) = pick(cfg, "acks", DEF_ACKS)
    fun goodbye(cfg: JSONObject) = pick(cfg, "goodbyes", DEF_BYE)

    /** What Jarvis says when you call his name. */
    fun wakeLine(c: Context, cfg: JSONObject): String {
        val p = Prefs(c)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // the first time he calls in the morning, and the first time in the evening, a proper greeting
        if (h in 5..11 && p.sp.getString("greeted_morning", "") != today) {
            p.sp.edit().putString("greeted_morning", today).apply()
            return "Good morning, sir."
        }
        if (h in 17..21 && p.sp.getString("greeted_evening", "") != today) {
            p.sp.edit().putString("greeted_evening", today).apply()
            return "Good evening, sir."
        }
        return when (h) {
            in 5..11 -> pick(cfg, "morning_greetings", DEF_MORNING)
            in 22..23, in 0..4 -> pick(cfg, "late_greetings", DEF_LATE)
            else -> pick(cfg, "greetings", DEF_GREET)
        }
    }

    /** Everything worth having ready in his voice before he needs it. */
    fun stockLines(cfg: JSONObject): List<String> =
        (list(cfg, "greetings", DEF_GREET) + list(cfg, "morning_greetings", DEF_MORNING) + list(cfg, "late_greetings", DEF_LATE) +
            list(cfg, "acks", DEF_ACKS) + list(cfg, "goodbyes", DEF_BYE) + QUICK).distinct()
}
