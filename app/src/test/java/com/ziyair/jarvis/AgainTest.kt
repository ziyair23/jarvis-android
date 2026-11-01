package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgainTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val weather = "It's 72 and clear in Columbus, sir. Rain after nine."

    @Before fun reset() = Again.forget()

    @Test fun replaysTheLastLine() {
        Again.said(weather, t0)
        assertEquals(weather, Again.ask("say that again", t0 + min))
    }

    @Test fun lastBitThenWholeLine() {
        Again.said(weather, t0)
        val bit = Again.ask("say the last bit again", t0 + min)
        assertEquals("Rain after nine.", bit)
        Again.said(bit!!, t0 + min)   // the service records whatever it speaks, the replay included
        assertEquals(weather, Again.ask("say that again", t0 + 2 * min))
        assertEquals("Rain after nine.", Again.ask("what was the last part", t0 + 2 * min))
    }

    @Test fun oneSentenceLastBitIsTheWholeLine() {
        Again.said("Paused.", t0)
        assertEquals("Paused.", Again.ask("the last bit", t0 + min))
    }

    @Test fun phrasings() {
        Again.said(weather, t0)
        listOf("Jarvis, say that again, sir.", "what was that", "Come again?", "repeat that", "Sorry, what?", "pardon",
            "I didn't catch that.", "one more time", "what did you say", "could you repeat that please", "Hey Jarvis, what was that?")
            .forEach { assertEquals(it, weather, Again.ask(it, t0 + min)) }
        listOf("what did she say", "play it again", "say hello to Abbey", "repeat my last order", "what was that song", "note that, sir")
            .forEach { assertNull(it, Again.ask(it, t0 + min)) }
    }

    @Test fun nothingAfterTenMinutesOrNothingSaid() {
        assertEquals(Again.NOTHING, Again.ask("say that again", t0))
        Again.said(weather, t0)
        assertEquals(weather, Again.ask("say that again", t0 + 10 * min))
        assertEquals(Again.NOTHING, Again.ask("say that again", t0 + 11 * min))
    }

    @Test fun ringPromptsDroppedAndPrivacyKept() {
        val ring = CallNotes.markPrivate("Abbey is calling, sir. Your note: she owes you twenty. Answer it?")
        Again.said(ring, t0)
        val r = Again.ask("what was that", t0 + min)
        assertEquals("Abbey is calling, sir. Your note: she owes you twenty.", r)
        assertTrue(CallNotes.isPrivate(r!!))
        Again.said("Message from Jess: running late. Want to reply, sir?", t0)
        assertEquals("Message from Jess: running late.", Again.ask("say that again", t0 + min))
        assertFalse(CallNotes.isPrivate("Message from Jess: running late."))
    }

    @Test fun dropItForgets() {
        Again.said(weather, t0)
        Again.forget()
        assertEquals(Again.NOTHING, Again.ask("say that again", t0 + min))
    }

    @Test fun blanksAndNothingLineNotRecorded() {
        Again.said(weather, t0)
        Again.said("   ", t0 + min)
        Again.said(Again.NOTHING, t0 + min)
        assertEquals(weather, Again.ask("repeat that", t0 + 2 * min))
    }
}
