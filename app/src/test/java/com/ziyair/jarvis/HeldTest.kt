package com.ziyair.jarvis

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HeldTest {
    private val t0 = 1_000_000_000L
    @After fun reset() = Held.clear()

    @Test fun holdsAndSaysSo() {
        assertEquals("No signal, sir. I'll come back to that when it's back.", Held.add("what's the score of the Seahawks game", t0))
        assertEquals(1, Held.count())
    }

    @Test fun keepsNewestThreeWithoutDuplicates() {
        Held.add("one", t0); Held.add("two", t0 + 1); Held.add("ONE", t0 + 2); Held.add("three", t0 + 3); Held.add("four", t0 + 4)
        val due = Held.takeDue(t0 + 5)
        assertEquals(listOf("ONE", "three", "four"), due.map { it.text })   // a repeat counts as the newest
        assertEquals(0, Held.count())
    }

    @Test fun oldOnesExpire() {
        Held.add("old", t0); Held.add("new", t0 + 40 * 60_000L)
        assertEquals(listOf("new"), Held.takeDue(t0 + 46 * 60_000L).map { it.text })
    }

    @Test fun promptNeverAsksForLateActions() {
        val p = Held.prompt(Held.Item("turn the lights off", t0), t0 + 12 * 60_000L)
        assertTrue(p.contains("12 minutes ago")); assertTrue(p.contains("don't do it"))
        assertTrue(Held.prompt(Held.Item("x", t0), t0 + 10_000L).contains("A minute ago"))
    }

    @Test fun reminderFallback() {
        assertEquals("Signal's back, sir. You'd asked \"text her i'm late\" and \"what's the weather\". Say it again if you still want it.",
            Held.reminder(listOf(Held.Item("Text her I'm late.", t0), Held.Item("What's the weather?", t0))))
        assertEquals("", Held.reminder(emptyList()))
    }

    @Test fun blankIsNotHeld() { Held.add("   ", t0); assertFalse(Held.count() > 0) }

    @Test fun joinSaysSignalBackOnce() {
        assertEquals("Signal's back, sir. Seahawks won 24 to 17. You'd asked me to turn the lights off; say it again if you still want it.",
            Held.join(listOf("Signal's back, sir. Seahawks won 24 to 17.", "Signal's back, sir. You'd asked me to turn the lights off; say it again if you still want it.")))
        assertEquals("", Held.join(listOf("Signal's back, sir.")))
    }
}
