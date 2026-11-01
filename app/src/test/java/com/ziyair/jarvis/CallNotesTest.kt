package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNotesTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    private fun say(b: CallNotes.Book, t: String, now: Long): String? = CallNotes.parse(t)?.let { CallNotes.handle(b, it, now) }

    @Test fun noteRightAfterACall() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        assertEquals("Noted for Abbey, sir.", say(b, "note that she owes me twenty", t0 + 10 * min))
        assertEquals("she owes me twenty", CallNotes.notesFor(b, "Abbey").single().text)
    }

    @Test fun ringLineCarriesTheNoteBeforeAnswerIt() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        say(b, "note that she owes me twenty", t0 + min)
        val note = CallNotes.ringNote(b, "Abbey")
        assertEquals("Abbey is calling, sir. Your note: she owes you twenty. Answer it?", CallNotes.ringLine("Abbey is calling, sir. Answer it?", note))
        assertEquals("Abbey is calling, sir. Your note: she owes you twenty.", CallNotes.ringLine("Abbey is calling, sir.", note))
        assertEquals("Jess is calling, sir.", CallNotes.ringLine("Jess is calling, sir.", CallNotes.ringNote(b, "Jess")))
    }

    @Test fun noRecentCallMeansNotHandled() {
        val b = CallNotes.Book()
        assertNull(say(b, "note that the meeting moved", t0))
        CallNotes.called(b, "Abbey", t0)
        assertNull(say(b, "note that the meeting moved", t0 + 61 * min))
        assertTrue(b.notes.isEmpty())
    }

    @Test fun noteForANamedCaller() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey Smith", t0)
        CallNotes.called(b, "Jess", t0 + min)
        assertEquals("Noted for Abbey Smith, sir.", say(b, "note for abbey she wants the black hoodie in large", t0 + 2 * min))
        assertEquals("she wants the black hoodie in large", CallNotes.notesFor(b, "Abbey Smith").single().text)
        assertEquals("Noted for Abbey Smith, sir.", say(b, "note for abbey smith that she paid", t0 + 3 * min))
        assertEquals("she paid", CallNotes.notesFor(b, "Abbey Smith").first().text)
        // a name Jarvis has never seen goes to Claude as usual
        assertNull(say(b, "note about the meeting tomorrow", t0 + 4 * min))
        assertEquals("Say the note after the name, sir.", say(b, "note for jess", t0 + 5 * min))
    }

    @Test fun readNewestThreeFirst() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        for (i in 1..4) say(b, "note that thing $i", t0 + i * min)
        assertEquals("Abbey, sir: thing 4. thing 3. thing 2.", say(b, "what are my notes on abbey", t0 + 10 * min))
        assertEquals("No notes on Jess, sir.", say(b, "read my notes on jess", t0))
    }

    @Test fun clearAll() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        say(b, "note that one", t0 + min); say(b, "note that two", t0 + 2 * min)
        assertEquals("Cleared 2 notes on Abbey, sir.", say(b, "delete my notes on abbey", t0 + 3 * min))
        assertTrue(CallNotes.notesFor(b, "Abbey").isEmpty())
        assertEquals("No notes on Abbey, sir.", say(b, "delete my notes on abbey", t0 + 4 * min))
    }

    @Test fun fivePerPersonAndRoundTrip() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey 🙂", t0)
        for (i in 1..6) say(b, "note that n$i", t0 + i * min)
        val texts = CallNotes.notesFor(b, "Abbey").map { it.text }
        assertEquals(listOf("n6", "n5", "n4", "n3", "n2"), texts)
        CallNotes.add(b, "Abbey", "tab\there\nnew line \u0001 odd", t0 + 9 * min)
        val back = CallNotes.load(CallNotes.dump(b))
        assertEquals("Abbey", back.lastCaller)
        assertEquals(t0, back.lastCallAt)
        assertEquals(CallNotes.notesFor(b, "Abbey").map { it.text }, CallNotes.notesFor(back, "Abbey").map { it.text })
        assertEquals(5, CallNotes.notesFor(back, "Abbey").size)
        assertTrue(CallNotes.load("garbage\nN\u0001x").notes.isEmpty())
    }

    @Test fun spokenSwapsPronounsAndCaps() {
        assertEquals("you owe her your jacket", CallNotes.spoken("I owe her my jacket."))
        assertEquals(15, CallNotes.spoken((1..30).joinToString(" ") { "w$it" }).split(" ").size)
    }

    @Test fun otherReflexesUntouched() {
        for (t in listOf("drop it", "forget it", "forget about that", "pause", "louder", "what time is it", "open my notes", "notes", "note to self buy milk"))
            assertNull(t, CallNotes.parse(t))
    }

    // round 2: noisy car mishears the name
    @Test fun misheardNamesStillFindThePerson() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0); CallNotes.called(b, "Jesse Ruiz", t0 + min)
        assertEquals("Noted for Abbey, sir.", say(b, "note for abby she owes me twenty", t0 + 2 * min))
        assertEquals("she owes me twenty", CallNotes.notesFor(b, "Abbey").single().text)
        assertEquals("Noted for Jesse Ruiz, sir.", say(b, "note for jess bring the samples", t0 + 3 * min))
        assertEquals("Abbey, sir: she owes you twenty.", say(b, "do i have any notes on abby", t0 + 4 * min))
        assertNull(say(b, "note for mark he called", t0 + 5 * min))
    }

    // independent-test fixes: his own words kept, note lines kept off the cloud voice
    @Test fun noteKeptInHisOwnWords() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        assertEquals("Noted for Abbey, sir.", say(b, "Jarvis, note that she owes me $20 now, sir.", t0 + min))
        assertEquals("she owes me $20 now", CallNotes.notesFor(b, "Abbey").single().text)
        assertEquals("Noted for Abbey, sir.", say(b, "Please make a note for Abbey: Black hoodie, size L.", t0 + 2 * min))
        assertEquals("Black hoodie, size L", CallNotes.notesFor(b, "Abbey").first().text)
        assertEquals("Cleared 2 notes on Abbey, sir.", say(b, "Delete my notes on Abbey, sir.", t0 + 3 * min))
    }

    @Test fun noteLinesArePrivate() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        say(b, "note that she owes me twenty", t0 + min)
        val ring = CallNotes.ringLine("Abbey is calling, sir. Answer it?", CallNotes.ringNote(b, "Abbey"))
        assertTrue(CallNotes.isPrivate(ring))
        val read = say(b, "what are my notes on abbey", t0 + 2 * min)!!
        assertTrue(CallNotes.isPrivate(read))
        // lines without a note still use his chosen voice
        assertFalse(CallNotes.isPrivate(CallNotes.ringLine("Jess is calling, sir.", "")))
        assertFalse(CallNotes.isPrivate("Noted for Abbey, sir."))
    }

    @Test fun noteThatSirIsNotANote() {
        val b = CallNotes.Book()
        CallNotes.called(b, "Abbey", t0)
        assertNull(CallNotes.parse("Jarvis, note that, sir."))
        assertNull(CallNotes.parse("note that please"))
        assertNull(CallNotes.parse("note for, sir."))
        assertNull(say(b, "Jarvis, note that, sir.", t0 + min))
        assertTrue(b.notes.isEmpty())
        assertEquals("Noted for Abbey, sir.", say(b, "Jarvis, note that she owes me twenty, sir.", t0 + min))
        assertEquals("she owes me twenty", CallNotes.notesFor(b, "Abbey").single().text)
    }
}
