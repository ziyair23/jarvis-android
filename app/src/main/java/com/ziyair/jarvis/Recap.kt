package com.ziyair.jarvis

import java.util.Calendar
import java.util.Locale

/**
 * "Where were we?" (lab idea L27). After a break he picks the conversation back up: Jarvis says, in one or two
 * short lines, what he last asked and what Jarvis said, straight from the saved history: on the phone, instant,
 * free. Quick phone commands (music, volume, lights …) and read-out messages are skipped, so it finds the real
 * topic. Plain Kotlin (no Android) so it can be unit tested; the phone side passes the history in.
 */
object Recap {
    const val NOTHING = "Nothing on the go, sir."
    const val MAX_AGE_MS = 7 * 24 * 3600_000L
    const val ANSWER_WORDS = 20

    const val KEEP_REAL = 40
    const val KEEP_QUICK = 10

    /**
     * Which saved turns to keep (indexes, oldest first): the newest [KEEP_REAL] real turns and the newest [KEEP_QUICK]
     * quick ones, so an evening of skips and volume changes can't push the real conversation out.
     */
    fun keep(quick: List<Boolean>): List<Int> {
        var q = KEEP_QUICK; var r = KEEP_REAL
        return quick.indices.reversed().filter { if (quick[it]) q-- > 0 else r-- > 0 }.reversed()
    }

    /** One saved turn. [quick] = a phone reflex (pause, louder, the time …), not a real topic. */
    class Turn(val role: String, val content: String, val at: Long, val quick: Boolean = false)

    private val ASK = Regex("^(where were we|where was i|where did we leave (it|off)|what were we (talking about|saying|on)|" +
        "what was i (saying|asking|talking about)|remind me what we were (talking about|saying|on)|" +
        "(let's |lets )?(pick up|carry on|go back to) where we left (it|off)|back to what we were (saying|talking about))$")

    private fun norm(t0: String): String {
        var t = t0.lowercase(Locale.US).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^((hey|ok|okay|yo|so|right|anyway|sorry) )*(jarvis )?((so|right|anyway|okay|ok) )*"), "")
        t = t.replace(Regex("( (please|sir|jarvis|again|then))+$"), "")
        return t.trim()
    }

    fun isAsk(said: String) = ASK.matches(norm(said))

    /** Small talk that isn't a topic on its own ("yes", "thanks", "okay do it"). */
    private val FILLER = Regex("^(yes|yeah|yep|no|nope|ok|okay|sure|thanks|thank you|cheers|cool|nice|do it|go on|go ahead|alright|right)( (sir|jarvis|then|please|mate))*$")

    private fun isTopic(t: Turn): Boolean {
        if (t.role != "user" || t.quick || t.content.isBlank()) return false
        if (isAsk(t.content)) return false
        val n = t.content.lowercase(Locale.US).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return n.isNotEmpty() && !FILLER.matches(n) && n.split(" ").size >= 2
    }

    /** What Jarvis says to "where were we?". [turns] oldest first, as saved. */
    fun answer(turns: List<Turn>, now: Long): String {
        val i = turns.indices.lastOrNull { isTopic(turns[it]) } ?: return NOTHING
        val q = turns[i]
        if (now - q.at > MAX_AGE_MS || q.at > now + 60_000) return NOTHING
        val reply = turns.drop(i + 1).firstOrNull { it.role == "assistant" && !it.quick && it.content.isNotBlank() && !it.content.startsWith("Message from ") }
        val asked = CallNotes.spoken(q.content).trimEnd('?', '.', '!', ',')
            .let { s -> if (s.startsWith("I ") || s.startsWith("I'")) s else s.replaceFirstChar { it.lowercase() } }
        val said = reply?.let { firstSentence(it.content) }.orEmpty()
        val head = whenSaid(q.at, now)
        return (if (head.isEmpty()) "You'd asked: $asked." else "$head you'd asked: $asked.") +
            (if (said.isEmpty()) "" else " I said: $said")
    }

    /** First sentence of an answer, at most [ANSWER_WORDS] words, ending in a full stop or its own mark. */
    fun firstSentence(s: String): String {
        val first = s.trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull { it.isNotBlank() } ?: return ""
        val words = first.split(Regex("\\s+"))
        val cut = words.take(ANSWER_WORDS).joinToString(" ")
        return if (words.size > ANSWER_WORDS) cut.trimEnd(',', ';', ':') + "…" else if (cut.last() in ".!?") cut else "$cut."
    }

    /** "" within two hours, else "Earlier today," / "Last night," / "Yesterday," / "On Tuesday,". */
    fun whenSaid(at: Long, now: Long): String {
        if (now - at < 2 * 3600_000L) return ""
        val a = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val days = dayNumber(n) - dayNumber(a)
        return when {
            days <= 0L && a.get(Calendar.HOUR_OF_DAY) < 5 && n.get(Calendar.HOUR_OF_DAY) < 12 -> "Last night,"
            days <= 0L -> "Earlier today,"
            days == 1L && a.get(Calendar.HOUR_OF_DAY) >= 18 && n.get(Calendar.HOUR_OF_DAY) < 12 -> "Last night,"
            days == 1L -> "Yesterday,"
            else -> "On " + a.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) + ","
        }
    }

    private fun dayNumber(c: Calendar): Long {
        val d = c.clone() as Calendar
        d.set(Calendar.HOUR_OF_DAY, 12); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0)
        return (d.timeInMillis + d.get(Calendar.ZONE_OFFSET) + d.get(Calendar.DST_OFFSET)) / (24 * 3600_000L)
    }
}
