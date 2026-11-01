package com.ziyair.jarvis

import java.util.Locale

/**
 * The maths behind judging DoorDash offers and the offer diary. Plain Kotlin (no Android), so it can be unit tested.
 * Lab ideas L1 (offer diary + best hours) and L2 (per-hour judging).
 */
object OfferMath {
    class Offer(val pay: Double, val miles: Double, val minutes: Int?)

    /** One saved offer: when it was seen (hour of day 0–23), and what it paid. */
    class Seen(val hour: Int, val pay: Double, val miles: Double, val minutes: Int?)

    const val GREAT = 3; const val OK = 2; const val BORDERLINE = 1; const val POOR = 0

    /** Reads an offer card. [all] is the screen text joined with " | ", [nowMinutes] is minutes since midnight. */
    fun parse(all: String, nowMinutes: Int): Offer? {
        if (!Regex("\\baccept\\b", RegexOption.IGNORE_CASE).containsMatchIn(all)) return null
        val pay = Regex("\\$\\s?(\\d{1,3}(?:\\.\\d{2})?)").findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in 1.0..200.0 }.maxOrNull() ?: return null
        val miles = Regex("(\\d{1,3}(?:\\.\\d)?)\\s?mi\\b", RegexOption.IGNORE_CASE).findAll(all)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it > 0 }.maxOrNull() ?: return null
        return Offer(pay, miles, minutes(all, nowMinutes))
    }

    /** How long the offer takes: an explicit "32 min" wins, else "Deliver by 7:42 PM" counted from now. Null if unknown or odd. */
    fun minutes(all: String, nowMinutes: Int): Int? {
        Regex("(\\d{1,3})\\s?(?:min|mins|minutes)\\b", RegexOption.IGNORE_CASE).find(all)?.let {
            val m = it.groupValues[1].toInt()
            return if (m in 3..120) m else null
        }
        val by = Regex("deliver(?:y)?\\s+by\\s+(\\d{1,2}):(\\d{2})\\s*([ap])\\.?\\s?m\\.?", RegexOption.IGNORE_CASE).find(all) ?: return null
        var h = by.groupValues[1].toInt() % 12
        if (by.groupValues[3].equals("p", true)) h += 12
        val target = h * 60 + by.groupValues[2].toInt()
        var diff = target - nowMinutes
        if (diff < -60) diff += 24 * 60          // past midnight
        return if (diff in 3..120) diff else null
    }

    fun tier(value: Double, min: Double): Int = when {
        min <= 0 -> GREAT
        value >= min * 1.25 -> GREAT
        value >= min -> OK
        value >= min * 0.85 -> BORDERLINE
        else -> POOR
    }

    fun perHour(o: Offer): Double? = o.minutes?.let { o.pay / it * 60.0 }

    /** The worse of the per-mile and per-hour tiers (per mile only when the time isn't known). */
    fun overall(o: Offer, minPerMile: Double, minPerHour: Double): Int {
        val mile = tier(o.pay / o.miles, minPerMile)
        val hour = perHour(o)?.let { tier(it, minPerHour) } ?: return mile
        return minOf(mile, hour)
    }

    fun verdictWords(t: Int) = when (t) {
        GREAT -> "Good one, sir, take it."
        OK -> "Worth taking."
        BORDERLINE -> "Borderline, your call."
        else -> "I'd pass."
    }

    /** The spoken call-out, e.g. "8.50 dollars for 2.1 miles. 4.05 dollars a mile, about 28 an hour. Good one, sir, take it." */
    fun line(o: Offer, minPerMile: Double, minPerHour: Double): String {
        val payWords = if (o.pay == Math.floor(o.pay)) "${o.pay.toInt()} dollars" else String.format(Locale.US, "%.2f dollars", o.pay)
        val milesWords = if (o.miles == Math.floor(o.miles)) "${o.miles.toInt()} miles" else "${o.miles} miles"
        val rate = o.pay / o.miles
        val rateWords = if (rate < 1) "${Math.round(rate * 100)} cents" else String.format(Locale.US, "%.2f dollars", rate)
        val hourWords = perHour(o)?.let { ", about ${Math.round(it)} an hour" } ?: ""
        return "$payWords for $milesWords. $rateWords a mile$hourWords. ${verdictWords(overall(o, minPerMile, minPerHour))}"
    }

    /** "When are my best hours?" from the diary. Short and written to be spoken. */
    fun bestHours(seen: List<Seen>): String {
        if (seen.size < 10) return "I've only seen ${seen.size} offers so far; give me a few more dashes."
        class Band(val start: Int, val list: List<Seen>) {
            val perMile = list.sumOf { it.pay } / list.sumOf { it.miles }.coerceAtLeast(0.1)
            val timed = list.filter { it.minutes != null }
            val perHour = if (timed.size >= 3) timed.sumOf { it.pay } / timed.sumOf { it.minutes!! } * 60.0 else null
        }
        // two-hour windows starting on every hour; a window needs at least 4 offers to count
        val bands = (0 until 24).map { s -> Band(s, seen.filter { it.hour == s || it.hour == (s + 1) % 24 }) }.filter { it.list.size >= 4 }
        if (bands.isEmpty()) return "I've seen ${seen.size} offers, but they're too spread out to call a best time yet."
        val best = bands.maxByOrNull { it.perMile }!!
        val sb = StringBuilder("Your best stretch is ${clock(best.start)} to ${clock(best.start + 2)}, about ${money(best.perMile)} a mile")
        best.perHour?.let { sb.append(" and ${Math.round(it)} an hour") }
        sb.append(", from ${best.list.size} offers.")
        val worst = bands.filter { it.list.intersect(best.list.toSet()).isEmpty() }.minByOrNull { it.perMile }
        if (worst != null && worst.perMile < best.perMile * 0.75) sb.append(" Weakest is ${clock(worst.start)} to ${clock(worst.start + 2)}, ${money(worst.perMile)} a mile.")
        return sb.toString()
    }

    fun clock(h0: Int): String {
        val h = ((h0 % 24) + 24) % 24
        val twelve = if (h % 12 == 0) 12 else h % 12
        return "$twelve ${if (h < 12) "AM" else "PM"}"
    }

    private fun money(v: Double) = String.format(Locale.US, "%.2f dollars", v)
}
