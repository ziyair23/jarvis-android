package com.ziyair.jarvis

import java.util.Locale

/**
 * No-signal mode (lab idea L8). When he asks Jarvis something and there's no signal, the request is held here
 * instead of failing. When signal comes back, held questions get answered; held actions are never done late,
 * Jarvis only reminds him what he asked. Plain Kotlin (no Android), so it can be unit tested.
 */
object Held {
    class Item(val text: String, val at: Long)

    const val MAX = 3
    const val EXPIRE_MS = 45 * 60_000L

    private val items = mutableListOf<Item>()

    const val HELD_LINE = "No signal, sir. I'll come back to that when it's back."

    /** Hold a request. Duplicates are dropped and only the newest three are kept. Returns the spoken line. */
    @Synchronized fun add(text: String, now: Long): String {
        val t = text.trim()
        if (t.isEmpty()) return HELD_LINE
        items.removeAll { it.text.equals(t, ignoreCase = true) }
        items.add(Item(t, now))
        while (items.size > MAX) items.removeAt(0)
        return HELD_LINE
    }

    /** Everything still worth coming back to (oldest first), and empties the list. Anything older than 45 minutes is dropped. */
    @Synchronized fun takeDue(now: Long): List<Item> {
        val due = items.filter { now - it.at <= EXPIRE_MS }
        items.clear()
        return due
    }

    @Synchronized fun count() = items.size
    @Synchronized fun clear() = items.clear()

    /** What Claude is asked when signal is back. Tools are switched off for these, so nothing can be done late. */
    fun prompt(item: Item, now: Long): String {
        val mins = ((now - item.at) / 60_000L).coerceAtLeast(1)
        return "[Signal just came back. ${if (mins == 1L) "A minute" else "$mins minutes"} ago, with no signal, he said: \"${item.text}\". " +
            "Open with \"Signal's back, sir.\" If it was a question, answer it in one or two short sentences. " +
            "If it asked you to do something (a text, a call, lights, music, a timer, a reminder, anything on the phone), " +
            "don't do it and don't pretend you did: in one short line say what he asked and that he can say it again if he still wants it.]"
    }

    /** One spoken line from several answers: "Signal's back, sir." only once, at the start. */
    fun join(replies: List<String>): String {
        val lead = Regex("^\\s*signal'?s back,? sir[.,!]?\\s*", RegexOption.IGNORE_CASE)
        val body = replies.map { it.replace(lead, "").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return if (body.isEmpty()) "" else "Signal's back, sir. $body"
    }

    /** Fallback if Claude can't be reached even now: just remind him, in his words. */
    fun reminder(items: List<Item>): String {
        if (items.isEmpty()) return ""
        val said = items.joinToString(" and ") { "\"" + it.text.trim().trimEnd('.', '?', '!').lowercase(Locale.US) + "\"" }
        return "Signal's back, sir. You'd asked $said. Say it again if you still want it."
    }
}
