package com.ziyair.jarvis

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Jarvis's reflexes: the quick everyday commands handled right on the phone, with no trip to Claude.
 * Instant, and they cost nothing. Only exact, simple phrasings are caught here; anything with more to it
 * ("pause the music and text her I'm late") goes to Claude as usual.
 */
object Reflex {
    class Hit(val reply: String, val endConvo: Boolean = false)

    private val NUM = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "twenty five" to 25, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90)

    private fun norm(t0: String): String {
        var t = t0.lowercase(Locale.US).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^((hey|ok|okay|yo) )?(jarvis )?"), "")
        t = t.replace(Regex("^(can you|could you|would you|will you|please|go ahead and|just) "), "")
        t = t.replace(Regex("^(please) "), "")
        t = t.replace(Regex(" (please|for me|sir|jarvis|real quick|now)$"), "")
        t = t.replace(Regex(" (please|for me|sir|jarvis)$"), "")
        return t.trim()
    }

    private fun m(t: String, re: String) = Regex("^($re)$").matches(t)

    fun handle(c: Context, text: String): Hit? {
        if (!Brain(c).config().optBoolean("reflexes", true)) return null
        val t = norm(text)
        if (t.isEmpty() || t.split(" ").size > 9) return null
        val hit = try { match(c, t) } catch (e: Exception) { Problems.log(c, "reflex: ${e.message}"); null } ?: return null
        // Claude still sees it in the conversation, so "and turn it up a bit" afterwards makes sense to him
        if (hit.reply == "Dropped, sir.") return hit
        val p = Prefs(c)
        p.addTurn("user", text)
        p.addTurn("assistant", hit.reply)
        return hit
    }

    private fun match(c: Context, t: String): Hit? {
        val now = Date()
        when {
            // "drop it": wipe the running conversation so he stops circling back to something
            m(t, "drop it|let it go|change the subject|new topic|stop (talking|bringing) (about |up )?(it|that|this)( up)?|stop talking about (it|that|this)|clear (the |our )?(chat|conversation)|start fresh|forget (about )?(it|that)") -> {
                Prefs(c).clearHistory()
                Inbox.lastAnnounced = null
                Memory.purgeSensitive(c)
                return Hit("Dropped, sir.", true)
            }
            m(t, "what time is it|what's the time|what is the time|time|what time is it right now|got the time") ->
                return Hit("It's ${SimpleDateFormat("h:mm", Locale.US).format(now)}, sir.")
            m(t, "what's the date|what is the date|what's today's date|what is today's date|what's the date today|what day is it|what day is today|what's today") -> {
                val cal = Calendar.getInstance()
                return Hit("It's ${SimpleDateFormat("EEEE, MMMM", Locale.US).format(now)} ${ordinal(cal.get(Calendar.DAY_OF_MONTH))}.")
            }

            // music
            m(t, "pause|pause it|pause (the |my )?(music|song|video|this)|hold (the )?music") -> return Hit(media(c, "pause", "Paused."))
            m(t, "resume|unpause|keep playing|resume (the |my )?(music|song)|play (the |my )?music|play it|press play") -> return Hit(media(c, "play", "Resuming."), true)
            m(t, "skip|next|skip (it|this|this one|this song|the song|song|track)|next (song|track|one)|play the next song") -> return Hit(media(c, "next", "Skipping."), true)
            m(t, "previous|previous (song|track)|go back|last song|play the last song|play (that|the) last song again|back one") -> return Hit(media(c, "previous", "Going back."), true)
            m(t, "stop (the |my )?(music|song)|stop playing|turn (the |my )?music off|turn off (the |my )?music") -> return Hit(media(c, "pause", "Music off."))

            // volume
            m(t, "(volume|turn it|turn (the )?(volume|music)) up|turn up (the )?(volume|music)|louder|make it louder|crank it( up)?|a bit louder|little louder") ->
                return Hit(volume(c, +2, "Louder."), true)
            m(t, "(volume|turn it|turn (the )?(volume|music)) down|turn down (the )?(volume|music)|quieter|make it quieter|softer|a bit quieter|little quieter") ->
                return Hit(volume(c, -2, "Quieter."), true)
            m(t, "(max|full) volume|volume (all the way up|to max|max)|turn it all the way up") -> return Hit(PhoneControl.run(c, "volume", 10).let { "Volume all the way up." })
            m(t, "mute|mute it|mute (the )?(volume|music|sound)") -> return Hit(PhoneControl.run(c, "volume", 0).let { "Muted." })
            Regex("^(set )?(the )?(volume|music) (to |at )?(\\d{1,3})( percent)?$").find(t) != null -> {
                val n = Regex("(\\d{1,3})").find(t)!!.value.toInt()
                val lvl = if (t.contains("percent") || n > 10) Math.round(n / 10f) else n
                PhoneControl.run(c, "volume", lvl.coerceIn(0, 10))
                return Hit("Volume at $lvl.", true)
            }

            // flashlight
            m(t, "(turn )?(the )?(flashlight|torch|flash) on|turn on (the |my )?(flashlight|torch|flash)|flashlight|lumos") ->
                return Hit(PhoneControl.run(c, "flashlight_on", null).let { if (it.startsWith("Flashlight")) "Flashlight's on." else it })
            m(t, "(turn )?(the )?(flashlight|torch|flash) off|turn off (the |my )?(flashlight|torch|flash)|kill the (flashlight|light)") ->
                return Hit(PhoneControl.run(c, "flashlight_off", null).let { if (it.startsWith("Flashlight")) "Flashlight's off." else it })

            // battery
            m(t, "(what's |what is |check )?(my |the )?(battery|battery level|battery life|phone battery)|how('s| is) (my |the )?battery|how much battery( do i have)?( left)?") -> {
                val (lvl, charging) = Status.battery(c)
                if (lvl < 0) return null
                return Hit("Battery's at $lvl percent" + (if (charging) " and charging" else "") + ", sir.")
            }

            // lights (only when his Govee lights are set up)
            Lights.available(c) && m(t, "(turn )?(the |my )?lights (on|off)|turn (on|off) (the |my )?lights|lights (on|off)|(kill|cut) the lights") -> {
                val on = !(t.contains(" off") || t.startsWith("kill") || t.startsWith("cut"))
                val r = Lights.set(c, "all", on, null, null)
                val failed = Regex("couldn't|can't|failed|wrong|no lights|not", RegexOption.IGNORE_CASE).containsMatchIn(r)
                return Hit(if (failed) r else if (on) "Lights on, sir." else "Lights off, sir.")
            }
        }
        timer(c, t)?.let { return it }
        return null
    }

    private fun media(c: Context, action: String, ok: String): String {
        val r = Apps.media(c, action)
        return if (r.startsWith("Unknown")) r else ok
    }

    private fun volume(c: Context, steps: Int, ok: String): String {
        val am = c.getSystemService(AudioManager::class.java)
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
        return ok
    }

    /** "set a timer for 10 minutes", "5 minute timer", "timer 30 seconds", "timer for an hour and a half". */
    private fun timer(c: Context, t: String): Hit? {
        if (!t.contains("timer")) return null
        val re = Regex("^(set |start |make )?(a |an )?(timer (for )?)?(.+?) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)( timer)?$")
        val mm = re.find(t) ?: return null
        if (!t.startsWith("timer") && !mm.groupValues[3].isNotBlank() && !t.endsWith("timer")) return null
        var qty = mm.groupValues[5].trim().removePrefix("for ").trim()
        var extra = 0.0
        if (qty.endsWith(" and a half")) { qty = qty.removeSuffix(" and a half"); extra = 0.5 }
        val base = when {
            qty == "half an" || qty == "half a" -> 0.5
            qty.toDoubleOrNull() != null -> qty.toDouble()
            NUM.containsKey(qty) -> NUM[qty]!!.toDouble()
            else -> return null
        } + extra
        val unit = mm.groupValues[6]
        val minutes = when { unit.startsWith("s") -> base / 60.0; unit.startsWith("h") -> base * 60.0; else -> base }
        if (minutes <= 0 || minutes > 24 * 60) return null
        val r = Actions(c).run("set_timer", JSONObject().put("minutes", minutes))
        if (!r.startsWith("Timer set")) return null
        val len = r.removePrefix("Timer set for ").substringBefore(",")
        return Hit("$len, sir. Timer's running.")
    }

    private fun ordinal(d: Int) = d.toString() + when { d in 11..13 -> "th"; d % 10 == 1 -> "st"; d % 10 == 2 -> "nd"; d % 10 == 3 -> "rd"; else -> "th" }
}
