package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecapTest {
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val now = at(4, 14)   // Wednesday 2 PM
    private fun u(s: String, t: Long, q: Boolean = false) = Recap.Turn("user", s, t, q)
    private fun a(s: String, t: Long, q: Boolean = false) = Recap.Turn("assistant", s, t, q)

    @Test fun phrasings() {
        listOf("Jarvis, where were we?", "where was I", "what were we talking about", "So, what were we saying, sir?",
            "remind me what we were talking about", "let's pick up where we left off", "anyway jarvis where were we",
            "what was I saying", "where did we leave off").forEach { assertTrue(it, Recap.isAsk(it)) }
        listOf("where were you", "what were we doing yesterday", "where was I last night", "where are we", "what was I paying for gas")
            .forEach { assertFalse(it, Recap.isAsk(it)) }
    }

    @Test fun lastRealTopicSkippingQuickCommandsAndMessages() {
        val h = listOf(
            u("should I do black or oxblood for the next drop", now - 20 * 60_000),
            a("Oxblood, sir. It matches the logo and nobody else is doing it. Black sells, but it blends in.", now - 20 * 60_000),
            u("pause", now - 15 * 60_000, true), a("Paused.", now - 15 * 60_000, true),
            a("Message from Jess: running late.", now - 10 * 60_000),
            u("thanks", now - 9 * 60_000), a("Of course, sir.", now - 9 * 60_000))
        assertEquals("You'd asked: should you do black or oxblood for the next drop. I said: Oxblood, sir.", Recap.answer(h, now))
    }

    @Test fun nothingWhenEmptyOnlyQuickOrTooOld() {
        assertEquals(Recap.NOTHING, Recap.answer(emptyList(), now))
        assertEquals(Recap.NOTHING, Recap.answer(listOf(u("turn it up", now, true), a("Louder.", now, true)), now))
        assertEquals(Recap.NOTHING, Recap.answer(listOf(u("what's a good hoodie weight", now - 8 * 24 * 3600_000L), a("About 400 gsm, sir.", now - 8 * 24 * 3600_000L)), now))
    }

    @Test fun noAnswerYet() {
        assertEquals("You'd asked: what time does the post office close.", Recap.answer(listOf(u("what time does the post office close?", now - 60_000)), now))
    }

    @Test fun whenItWas() {
        assertEquals("", Recap.whenSaid(now - 90 * 60_000, now))
        assertEquals("Earlier today,", Recap.whenSaid(at(4, 9), now))
        assertEquals("Yesterday,", Recap.whenSaid(at(3, 13), now))
        assertEquals("Last night,", Recap.whenSaid(at(3, 22), at(4, 8)))
        assertEquals("On Monday,", Recap.whenSaid(at(2, 13), now))
        val h = listOf(u("draft a caption for the hoodie post", at(3, 13)), a("Built for the late shift. Pursuit. Want it shorter, sir?", at(3, 13)))
        assertEquals("Yesterday, you'd asked: draft a caption for the hoodie post. I said: Built for the late shift.", Recap.answer(h, now))
    }

    @Test fun longAnswerCutForTheEar() {
        val long = (1..30).joinToString(" ") { "w$it" } + "."
        assertEquals((1..20).joinToString(" ") { "w$it" } + "…", Recap.firstSentence(long))
        assertEquals("Yes.", Recap.firstSentence("Yes"))
    }

    @Test fun earlierRecapAsksAndFillerAreNotTopics() {
        val h = listOf(u("how many hoodies did I sell this week", now - 30 * 60_000), a("Twelve, sir.", now - 30 * 60_000),
            u("where were we", now - 5 * 60_000), a("You'd asked: …", now - 5 * 60_000), u("yeah", now - 4 * 60_000), a("Right, sir.", now - 4 * 60_000))
        assertEquals("You'd asked: how many hoodies did you sell this week. I said: Twelve, sir.", Recap.answer(h, now))
    }
}
