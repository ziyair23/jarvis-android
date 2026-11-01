package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Independent tester, L17, against lab/specs/L17.md only. */
class CallNotesIndepTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private fun say(b: CallNotes.Book, t: String, now: Long): String? = CallNotes.parse(t)?.let { CallNotes.handle(b, it, now) }

    // AT 1 + 2 + 3, end to end through the notebook
    @Test fun at1to3() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        assertEquals("Noted for Abbey, sir.", say(b, "note that she owes me twenty", t0 + 10 * min))
        val b2 = CallNotes.load(CallNotes.dump(b))
        val n = CallNotes.ringNote(b2, "Abbey")
        assertEquals("Abbey is calling, sir. Your note: she owes you twenty. Answer it?", CallNotes.ringLine("Abbey is calling, sir. Answer it?", n))
        assertEquals("Abbey is calling, sir. Your note: she owes you twenty.", CallNotes.ringLine("Abbey is calling, sir.", n))
    }

    // AT 1 window edge: exactly an hour still counts, a minute over does not, a clock going backwards does not
    @Test fun windowEdges() {
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey", t0)
        assertEquals("Noted for Abbey, sir.", say(b, "note that a", t0 + 60 * min))
        assertNull(say(b, "note that b", t0 + 61 * min))
        assertNull(say(b, "note that c", t0 - min))
    }

    // AT 2: newest note is the one announced
    @Test fun ringUsesNewest() {
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey", t0)
        say(b, "note that old one", t0 + min); say(b, "note that new one", t0 + 2 * min)
        assertEquals(" Your note: new one.", CallNotes.ringNote(b, "Abbey"))
    }

    // AT 5: two-word names by full or first name
    @Test fun at5() {
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey Smith", t0 - 5 * 3600_000L)
        assertEquals("Noted for Abbey Smith, sir.", say(b, "note for abbey she wants the black hoodie in large", t0))
        assertEquals("she wants the black hoodie in large", CallNotes.notesFor(b, "Abbey Smith").single().text)
        assertEquals("Noted for Abbey Smith, sir.", say(b, "note for abbey smith she paid", t0 + min))
        assertEquals(" Your note: she paid.", CallNotes.ringNote(b, "Abbey Smith"))
    }

    // AT 6 + 7
    @Test fun at6and7() {
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey", t0)
        assertEquals("No notes on Abbey, sir.", say(b, "what are my notes on abbey", t0))
        say(b, "note that one", t0 + min); say(b, "note that two", t0 + 2 * min)
        assertEquals("Abbey, sir: two. one.", say(b, "what are my notes on abbey", t0 + 3 * min))
        assertEquals("Cleared 2 notes on Abbey, sir.", say(b, "delete my notes on abbey", t0 + 4 * min))
        assertEquals("", CallNotes.ringNote(b, "Abbey"))
    }

    // AT 8: limits and storage of odd text
    @Test fun at8() {
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey", t0)
        for (i in 1..6) CallNotes.add(b, "Abbey", "n$i", t0 + i * min)
        assertEquals(listOf("n6", "n5", "n4", "n3", "n2"), CallNotes.notesFor(b, "Abbey").map { it.text })
        for (p in 1..55) CallNotes.add(b, "Person $p", "x", t0 + (10 + p) * min)
        assertTrue(b.notes.map { CallNotes.key(it.who) }.distinct().size <= CallNotes.MAX_PEOPLE)
        CallNotes.add(b, "Q", "a\tb\nc\rd \u0001 e \"quote\" é ✓", t0 + 100 * min)
        val back = CallNotes.load(CallNotes.dump(b))
        assertEquals(CallNotes.notesFor(b, "Q").single().text, CallNotes.notesFor(back, "Q").single().text)
        assertEquals(15, CallNotes.spoken((1..20).joinToString(" ") { "w$it" }).split(" ").size)
    }

    // AT 10: other reflex phrasings are not taken
    @Test fun at10() {
        for (t in listOf("drop it", "forget it", "forget about it", "clear the chat", "play music", "turn it up", "volume down", "stop"))
            assertNull(t, CallNotes.parse(t))
    }

    // Spec: a misheard name is found only "if it sounds the same". Ordinary words are not names: "make a note for me to
    // pick up diapers" must go to Claude, not be filed as a call note on Mom (or Mia, May, Mo...).
    // IGNORED so the lab branch CI still builds; FAILS today (see lab/reports/L17-2026-11-01-indep.md). Un-ignore with the fix.
    @Ignore("L17 bug: plain words sound-match short names; un-ignore with the fix")
    @Test fun ordinaryWordsAreNotNames() {
        for ((caller, line) in listOf("Mom" to "make a note for me to pick up diapers", "Mia" to "note for me to grab gas",
            "Tia" to "make a note about the oil change", "Mo" to "note on my mileage")) {
            val b = CallNotes.Book(); CallNotes.called(b, caller, t0 - 3 * 3600_000L)
            assertNull("$caller / $line", say(b, line, t0))
            assertTrue(b.notes.isEmpty())
        }
        // the real misheard names still work
        val b = CallNotes.Book(); CallNotes.called(b, "Abbey", t0); CallNotes.called(b, "Jesse", t0)
        assertEquals("Noted for Abbey, sir.", say(b, "note for abby she owes me twenty", t0 + min))
        assertEquals("Noted for Jesse, sir.", say(b, "note for jess bring samples", t0 + min))
    }
}
