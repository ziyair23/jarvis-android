package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Independent tester, L20, against lab/specs/L20.md only. Calls Again the way JarvisService does. */
class AgainIndepTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val weather = "It's 72 and clear in Columbus, sir. Rain after nine."
    @Before fun reset() = Again.forget()

    /** What the service does with a reflex reply: speaks it, then records it with Again.said. */
    private fun turn(said: String, now: Long): String? = Again.ask(said, now)?.also { Again.said(it, now) }

    @Test fun at1and2() {
        Again.said(weather, t0)
        assertEquals(weather, turn("say that again", t0 + min))
        assertEquals("Rain after nine.", turn("say the last bit again", t0 + min))
        assertEquals(weather, turn("say that again", t0 + 2 * min))
        assertEquals(weather, turn("say that again", t0 + 3 * min))
    }

    @Test fun at3() {
        Again.said(weather, t0)
        for (s in listOf("Jarvis, say that again, sir.", "what was that", "come again", "repeat that", "sorry what", "pardon",
            "i didn't catch that", "one more time", "what did you say")) assertEquals(s, weather, Again.ask(s, t0 + min))
        for (s in listOf("what did she say", "play it again", "say hello to Abbey", "repeat my last order")) assertNull(s, Again.ask(s, t0 + min))
    }

    @Test fun at4() {
        assertEquals(Again.NOTHING, Again.ask("say that again", t0))
        Again.said(weather, t0)
        assertEquals(Again.NOTHING, Again.ask("say that again", t0 + 11 * min))
    }

    @Test fun at5() {
        Again.said(CallNotes.markPrivate("Abbey is calling, sir. Your note: she owes you twenty. Answer it?"), t0)
        val r = turn("say that again", t0 + min)!!
        assertEquals("Abbey is calling, sir. Your note: she owes you twenty.", r)
        assertTrue(CallNotes.isPrivate(r))
    }

    // AT 6 as it happens on the phone: "drop it" is a reflex whose reply "Dropped, sir." is spoken and then recorded
    // by JarvisService.handle (Again.said(reply, ...)), after Reflex called Again.forget().
    @Test fun at6ThroughTheService() {
        Again.said(weather, t0)
        Again.forget(); Again.said("Dropped, sir.", t0 + min)
        assertEquals(Again.NOTHING, Again.ask("say that again", t0 + 2 * min))
    }

    @Test fun at7() {
        Again.said(weather, t0)
        Again.said("", t0 + min); Again.said("  ", t0 + min)
        assertEquals("Rain after nine.", turn("the last bit", t0 + min))
        assertEquals(weather, Again.ask("repeat that", t0 + 2 * min))
    }

    // L17 small fix: filler-only notes are not saved
    @Test fun noteThatSirIsNotANote() {
        assertNull(CallNotes.parse("Jarvis, note that, sir."))
        assertNull(CallNotes.parse("note for, please"))
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey", t0)
        assertEquals("Noted for Abbey, sir.", CallNotes.parse("note that she paid, sir.")?.let { CallNotes.handle(b, it, t0 + min) })
        assertEquals("she paid", CallNotes.notesFor(b, "Abbey").single().text)
    }
}
