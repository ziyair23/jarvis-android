package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Ziyair's patterns, learned from the feeds and sleep he logs: how often he's eating now versus last week, his
 * longest stretch at night and where it's heading, and when something changes (feeds bunching up, which often
 * comes with a growth spurt). Patterns and gentle predictions only, never medical advice; worries go to the pediatrician.
 * Worked out on the phone, free.
 */
object BabyInsights {
    private const val H = 3600_000L
    private const val D = 24 * H

    class Stats(val feedsPerDay: Double, val gapHours: Double, val longestNight: Double, val days: Int)

    /** Feeds per day, average gap between feeds and the longest night stretch (gap between feeds starting 9 PM to 6 AM), over [from, to). Pure. */
    fun stats(feeds: List<Long>, from: Long, to: Long): Stats? {
        val f = feeds.filter { it in from until to }.sorted()
        val days = ((to - from) / D).toInt().coerceAtLeast(1)
        if (f.size < 4) return null
        val gaps = f.zipWithNext { a, b -> (b - a).toDouble() / H }.filter { it in 0.5..12.0 }
        val night = f.zipWithNext().filter { (a, _) -> Calendar.getInstance().apply { timeInMillis = a }.get(Calendar.HOUR_OF_DAY).let { it >= 21 || it < 6 } }
            .map { (a, b) -> (b - a).toDouble() / H }.filter { it in 0.5..12.0 }
        return Stats(f.size.toDouble() / days, if (gaps.isEmpty()) 0.0 else gaps.average(), night.maxOrNull() ?: 0.0, days)
    }

    private fun hm(h: Double): String { val m = Math.round(h * 60).toInt(); return if (m < 60) "$m minutes" else "${m / 60}h ${m % 60}m".replace(" 0m", "") }

    /** Plain-spoken insights from this week against last week. Pure. */
    fun insights(feeds: List<Long>, now: Long, name: String): List<String> {
        val thisWeek = stats(feeds, now - 7 * D, now) ?: return listOf("I need about a week of logged feeds to see $name's patterns.")
        val lastWeek = stats(feeds, now - 14 * D, now - 7 * D)
        val recent = stats(feeds, now - 3 * D, now)
        val out = ArrayList<String>()
        out.add("$name's eating about ${"%.1f".format(thisWeek.feedsPerDay)} times a day, roughly every ${hm(thisWeek.gapHours)}" +
            (if (thisWeek.longestNight > 0) ", with his longest night stretch at ${hm(thisWeek.longestNight)}." else "."))
        if (lastWeek != null) {
            val change = (lastWeek.gapHours - thisWeek.gapHours) / lastWeek.gapHours
            if (change > 0.15) out.add("Feeds are about ${Math.round(change * 100)}% closer together than last week. That often comes with a growth spurt and usually settles in a few days.")
            else if (change < -0.15) out.add("He's going ${Math.round(-change * 100)}% longer between feeds than last week.")
            if (thisWeek.longestNight > 0 && lastWeek.longestNight > 0) {
                val gain = thisWeek.longestNight - lastWeek.longestNight
                if (gain >= 0.25) {
                    val toFour = (4.0 - thisWeek.longestNight) / gain
                    out.add("His longest night stretch grew ${hm(gain)} this week." + if (thisWeek.longestNight < 4.0 && toFour in 0.5..8.0) " If it keeps going like this, a 4-hour stretch could come in about ${Math.round(toFour)} week${if (Math.round(toFour) == 1L) "" else "s"}." else "")
                } else if (gain <= -0.5) out.add("His longest night stretch is ${hm(-gain)} shorter than last week; rough patches like that are common and usually pass.")
            }
        }
        if (recent != null && recent.feedsPerDay >= thisWeek.feedsPerDay * 1.25 && out.none { it.contains("growth spurt") })
            out.add("The last three days he's eating more often than his weekly average, possibly a growth spurt starting.")
        return out
    }

    fun feeds(c: Context): List<Long> = Baby.all(Prefs(c)).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("k") == "feed" }.map { it.optLong("at") } }

    fun forJarvis(c: Context): String {
        val name = Prefs(c).babyName.ifBlank { "the baby" }
        return insights(feeds(c), System.currentTimeMillis(), name).joinToString(" ") +
            (try { Appts.checkupNote(c)?.let { " $it" } ?: "" } catch (_: Exception) { "" }) +
            " (Patterns from what's logged, not medical advice: anything worrying goes to the pediatrician.)"
    }

    fun json(c: Context): JSONObject = JSONObject().put("lines", org.json.JSONArray(insights(feeds(c), System.currentTimeMillis(), Prefs(c).babyName.ifBlank { "He" })))
}
