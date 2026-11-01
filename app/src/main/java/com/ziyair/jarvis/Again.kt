package com.ziyair.jarvis

import java.util.Locale

/**
 * "Say that again" (lab idea L20). Remembers the last line Jarvis spoke out loud, so "what was that?" replays it
 * instantly on the phone: no Claude, free, works with no signal. Plain Kotlin (no Android) so it can be unit tested.
 */
object Again {
    const val KEEP_MS = 10 * 60_000L
    const val NOTHING = "Nothing to repeat, sir."
    const val DROPPED = "Dropped, sir."

    private var last = ""
    private var at = 0L
    private var lastReplay = ""

    /** Questions that belonged to the moment (a ringing call, a message): never replayed, so a replay can't act on them. */
    private val PROMPTS = Regex("\\s*(Answer it\\?|Decline it\\?|Want to reply, sir\\?)\\s*$")

    /** Jarvis just said [line] out loud. */
    @Synchronized fun said(line: String, now: Long) {
        val l = line.trim()
        if (l == lastReplay) { lastReplay = ""; return }
        if (l.isEmpty() || l == NOTHING || l == DROPPED) return
        last = l; at = now
    }

    @Synchronized fun forget() { last = ""; at = 0L; lastReplay = "" }

    private val WHOLE = Regex("^(say (that|it) again|say that one more time|(can you )?repeat (that|it)( again)?|repeat|come again|what was that|what('s| was) that again|" +
        "what did you (just )?say|sorry what|pardon|pardon me|i didn't catch (that|it)|didn't catch (that|it)|i missed (that|it)|one more time)$")
    private val TAIL = Regex("^(say the last (bit|part)( again)?|what was the last (bit|part)|repeat the last (bit|part)|the last (bit|part)( again)?)$")

    private fun norm(t0: String): String {
        var t = t0.lowercase(Locale.US).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^((hey|ok|okay|yo) )?(jarvis )?"), "")
        t = t.replace(Regex("^((can you|could you|would you|will you|please|just) )+"), "")
        t = t.replace(Regex("( (please|sir|jarvis|for me))+$"), "")
        return t.trim()
    }

    /** What Jarvis says back if [said] asks him to repeat himself, else null. */
    @Synchronized fun ask(said: String, now: Long): String? {
        val t = norm(said)
        val tailOnly = TAIL.matches(t)
        if (!tailOnly && !WHOLE.matches(t)) return null
        if (last.isEmpty() || now - at > KEEP_MS || now < at) return NOTHING
        val private = CallNotes.isPrivate(last)
        var out = last.replace(PROMPTS, "").trim().ifEmpty { last }
        if (tailOnly) out = out.split(Regex("(?<=[.!?])\\s+")).lastOrNull { it.isNotBlank() } ?: out
        lastReplay = out
        return if (private) CallNotes.markPrivate(out) else out
    }
}
