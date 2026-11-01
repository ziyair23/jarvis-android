package com.ziyair.jarvis

import java.util.Calendar
import java.util.Locale

/**
 * YouTube pulse (lab idea L13). "How's my channel doing?" → subscribers (and how many since he last asked),
 * plus how his newest video is doing next to his recent ones. Read-only; uses the free YouTube Data API with a key
 * he types into the app (never in the repo), 3 quota units per ask out of 10,000 a day. Plain Kotlin (no Android)
 * so it can be unit tested; the phone side fetches and parses the API replies into [Stats] and [Video].
 */
object YouTubePulse {
    const val KEEP_DAYS = 60
    private const val DAY = 24 * 3600_000L
    private const val SEP = '\u0001'

    class Stats(val subs: Long, val views: Long, val videos: Long, val at: Long, val subsHidden: Boolean = false)
    class Video(val title: String, val publishedAt: Long, val views: Long)

    // ---------- what he says ----------

    // "subs" and "subscribers" only with "my" (his subscribers); "the channel" only with doing/looking/going (not the TV)
    private val ASK = Regex("^(how's|how is|hows|how are) (my (youtube|youtube channel|channel|subs|subscribers|videos)|youtube|youtube channel)( doing| looking| going)?$|" +
        "^(how's|how is|hows) the channel (doing|looking|going)$|" +
        "^(how's|how is|hows) (my )?(latest|newest|last|new) (video|upload)( doing| looking| going)?$|" +
        "^(how many )?(subs|subscribers) (do i have|have i got)( on youtube)?$|" +
        "^(youtube|channel) (stats|update|numbers|check)$|^(check|give me) (my )?(youtube|channel)( stats| numbers)?$")

    fun norm(t0: String): String {
        var t = t0.lowercase(Locale.US).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^((hey|ok|okay|yo|so) )?(jarvis )?((can you|could you|please|just) )*"), "")
        t = t.replace(Regex("( (please|sir|jarvis|today|right now|at the moment))+$"), "")
        t = t.replace(Regex(" on youtube doing$"), " doing")
        return t.trim()
    }

    fun isAsk(said: String) = ASK.matches(norm(said))

    /** "@handle", a channel URL, or a "UC…" id → the API query part ("forHandle=…" or "id=…"), or null. */
    fun channelQuery(input: String): String? {
        val s = input.trim().substringBefore('?').trimEnd('/')
        if (s.isEmpty()) return null
        // a link copied from a tab (/videos, /featured) or a video link must never name someone else's channel
        val segs = s.split('/').filter { it.isNotEmpty() }
        segs.firstNotNullOfOrNull { Regex("^UC[A-Za-z0-9_-]{22}$").find(it)?.value }?.let { return "id=$it" }
        val h = (segs.firstOrNull { it.startsWith("@") } ?: segs.singleOrNull() ?: return null).removePrefix("@")
        if (!Regex("^[A-Za-z0-9._-]{3,30}$").matches(h)) return null
        return "forHandle=@$h"
    }

    // ---------- snapshots (one per day, on the phone) ----------

    fun dump(l: List<Stats>) = l.joinToString("\n") { "${it.at}$SEP${it.subs}$SEP${it.views}$SEP${it.videos}$SEP${if (it.subsHidden) 1 else 0}" }

    fun load(s: String?): MutableList<Stats> {
        val out = mutableListOf<Stats>()
        if (s.isNullOrBlank()) return out
        for (line in s.split('\n')) {
            val f = line.split(SEP)
            try { out.add(Stats(f[1].toLong(), f[2].toLong(), f[3].toLong(), f[0].toLong(), f.getOrNull(4) == "1")) } catch (_: Exception) { }
        }
        return out
    }

    private fun dayOf(at: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Saves today's numbers (replacing an earlier one from today) and drops anything older than [KEEP_DAYS]. */
    fun record(l: MutableList<Stats>, s: Stats) {
        l.removeAll { dayOf(it.at) == dayOf(s.at) || s.at - it.at > KEEP_DAYS * DAY }
        l.add(s)
        l.sortBy { it.at }
    }

    /** The snapshot to compare with: the newest from an earlier day, at most a week back if one exists. */
    fun before(l: List<Stats>, now: Long): Stats? {
        val earlier = l.filter { dayOf(it.at) < dayOf(now) && !it.subsHidden }
        return earlier.filter { now - it.at <= 8 * DAY }.minByOrNull { it.at } ?: earlier.maxByOrNull { it.at }
    }

    // ---------- the spoken line ----------

    fun num(n: Long): String = String.format(Locale.US, "%,d", n)

    private fun since(at: Long, now: Long): String {
        val days = ((now - at + DAY / 2) / DAY).toInt()
        return when {
            days <= 1 -> "since yesterday"
            days in 6..8 -> "this week"
            days < 6 -> "since " + Calendar.getInstance().apply { timeInMillis = at }.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)
            else -> "in $days days"
        }
    }

    private fun age(publishedAt: Long, now: Long): String {
        val h = (now - publishedAt) / 3600_000L
        return when {
            h < 1 -> "in under an hour"
            h < 24 -> "in $h hour${if (h == 1L) "" else "s"}"
            else -> { val d = h / 24; "in $d day${if (d == 1L) "" else "s"}" }
        }
    }

    /** A title for the ear: no hashtags, emoji or symbols, at most 8 words. */
    fun title(t: String): String {
        val w = t.replace(Regex("#\\S+"), " ").replace(Regex("[^\\p{L}\\p{N}' &-]"), " ").replace(Regex("\\s+"), " ").trim().split(" ").filter { it.isNotEmpty() }
        return if (w.size > 8) w.take(8).joinToString(" ") + "…" else w.joinToString(" ")
    }

    /**
     * What Jarvis says. [videos] newest first (up to 6). Example:
     * "1,240 subscribers, 18 more this week, sir. Newest, Hoodie drop teaser, 900 views in 2 days. Your last five averaged 1,100."
     */
    fun line(now: Stats, prev: Stats?, videos: List<Video>, nowMs: Long): String {
        val parts = mutableListOf<String>()
        if (now.subsHidden) parts.add("Subscriber count is hidden on the channel, sir.")
        else {
            val diff = prev?.let { now.subs - it.subs }
            val change = when {
                prev == null -> ""
                diff == 0L -> ", no change ${since(prev.at, nowMs)}"
                diff!! > 0 -> ", ${num(diff)} more ${since(prev.at, nowMs)}"
                else -> ", ${num(-diff)} fewer ${since(prev.at, nowMs)}"
            }
            parts.add("${num(now.subs)} subscriber${if (now.subs == 1L) "" else "s"}$change, sir.")
        }
        val newest = videos.firstOrNull()
        if (newest == null) parts.add("No videos up yet.")
        else {
            val t = title(newest.title)
            parts.add("Newest${if (t.isEmpty()) "" else ", $t,"} ${num(newest.views)} view${if (newest.views == 1L) "" else "s"} ${age(newest.publishedAt, nowMs)}.")
            val rest = videos.drop(1).take(5)
            if (rest.isNotEmpty()) {
                val avg = rest.sumOf { it.views } / rest.size
                val label = when (rest.size) { 1 -> "The one before had"; else -> "Your last ${words(rest.size)} averaged" }
                parts.add("$label ${num(avg)}.")
            }
        }
        return parts.joinToString(" ")
    }

    private fun words(n: Int) = listOf("zero", "one", "two", "three", "four", "five")[n]

    // ---------- errors, for the ear ----------

    const val NO_KEY = "I need a YouTube key and your channel handle in Jarvis settings first, sir."
    const val BAD_CHANNEL = "I can't find that channel, sir. Check the handle in Jarvis settings."
    const val NOT_ALLOWED = "YouTube won't take the key, sir. Turn on the YouTube Data API for it in Google Cloud."
    const val NO_SIGNAL = "Can't reach YouTube right now, sir."
    const val QUOTA = "YouTube's daily limit is used up, sir. Try again tomorrow."
}
