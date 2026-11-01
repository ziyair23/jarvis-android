package com.ziyair.jarvis

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent tester, L8, against lab/specs/L8.md only. */
class HeldIndepTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    @After fun reset() { Held.clear(); Held.onHold = null }

    // AT 1: the exact spec line
    @Test fun at1() = assertEquals("No signal, sir. I'll come back to that when it's back.", Held.add("what time does Costco close", t0))

    // AT 5: newest three, repeat counts as newest, over 45 min dropped silently
    @Test fun at5() {
        Held.add("a", t0); Held.add("b", t0 + 1); Held.add("c", t0 + 2); Held.add("A", t0 + 3); Held.add("d", t0 + 4)
        assertEquals(listOf("c", "A", "d"), Held.takeDue(t0 + 5).map { it.text })
        Held.add("old", t0); Held.add("new", t0 + 30 * min)
        assertEquals(listOf("new"), Held.takeDue(t0 + 46 * min).map { it.text })
        assertEquals(0, Held.count())
    }

    // AT 4: one line, "Signal's back, sir." once, question answered, action only mentioned
    @Test fun at4() {
        val l = Held.combine(listOf("Signal's back, sir. Costco closes at 8:30.", "Signal's back sir, you'd asked me to text Jess. Say it again if you still want it."), emptyList())
        assertEquals(1, Regex("signal'?s back", RegexOption.IGNORE_CASE).findAll(l).count())
        assertTrue(l.startsWith("Signal's back, sir. Costco closes at 8:30."))
        val p = Held.prompt(Held.Item("text Jess I'm running late", t0), t0 + 5 * min)
        assertTrue(p.contains("don't do it"))
    }

    // AT 6: his words, lowercased, ending cleaned
    @Test fun at6() = assertEquals("Signal's back, sir. You'd asked \"what's the score of the seahawks game\". Say it again if you still want it.",
        Held.reminder(listOf(Held.Item("What's the score of the Seahawks game?", t0))))
}
