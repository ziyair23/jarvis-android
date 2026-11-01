package com.ziyair.jarvis

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Tester checks for lab L8 (spec acceptance tests 1, 3, 4, 5, 6, 7). */
class HeldSpecTest {
    private val t0 = 2_000_000_000L
    private val min = 60_000L
    @After fun reset() = Held.clear()

    // 1/2: the exact held line
    @Test fun heldLineIsTheSpecLine() =
        assertEquals("No signal, sir. I'll come back to that when it's back.", Held.add("what time do the Seahawks play", t0))

    // 5: newest three only, a repeat counts as newest (even with different case/spaces)
    @Test fun newestThreeAndRepeatMovesToNewest() {
        Held.add("a", t0); Held.add("b", t0 + 1); Held.add("c", t0 + 2); Held.add("  A ", t0 + 3)
        assertEquals(listOf("b", "c", "A"), Held.takeDue(t0 + 4).map { it.text })
        Held.add("1", t0); Held.add("2", t0); Held.add("3", t0); Held.add("4", t0); Held.add("5", t0)
        assertEquals(listOf("3", "4", "5"), Held.takeDue(t0).map { it.text })
    }

    // 5: over 45 minutes is dropped silently; exactly 45 is kept
    @Test fun expiryBoundaryAndSilentDrop() {
        Held.add("kept", t0)
        assertEquals(listOf("kept"), Held.takeDue(t0 + 45 * min).map { it.text })
        Held.add("old", t0)
        assertTrue(Held.takeDue(t0 + 45 * min + 1).isEmpty())
        assertEquals(0, Held.count())
        assertEquals("", Held.reminder(emptyList()))   // nothing said for expired items
    }

    // 3: the catch-up prompt for an action never asks Claude to do it
    @Test fun actionPromptSaysDoNotDoIt() {
        val p = Held.prompt(Held.Item("text Jess I'm running late", t0), t0 + 5 * min)
        assertTrue(p.contains("text Jess I'm running late"))
        assertTrue(p.contains("don't do it and don't pretend you did"))
        assertTrue(p.contains("Open with \"Signal's back, sir.\""))
    }

    // 4: one line, "Signal's back, sir." exactly once, whatever Claude wrote
    @Test fun joinOnceWithVariants() {
        val line = Held.join(listOf("Signal's back, sir. It's 54 and cloudy.", "signals back sir, you'd asked me to call Mom; say it again if you still want it.", "  "))
        assertEquals(1, Regex("signal'?s back", RegexOption.IGNORE_CASE).findAll(line).count())
        assertTrue(line.startsWith("Signal's back, sir. It's 54 and cloudy."))
        assertTrue(line.contains("call Mom"))
    }

    // 6: reminder in his own words, all held items, one line
    @Test fun reminderHisWordsThreeItems() {
        val r = Held.reminder(listOf(Held.Item("Call Mom", t0), Held.Item("what's the weather", t0), Held.Item("turn the lights on", t0)))
        assertEquals("Signal's back, sir. You'd asked \"call mom\" and \"what's the weather\" and \"turn the lights on\". Say it again if you still want it.", r)
    }

    // whitespace-only never held
    @Test fun blankNeverHeld() { Held.add("", t0); Held.add("\n ", t0); assertFalse(Held.count() > 0) }

    // thread safety: many adds from several threads never exceed 3
    @Test fun concurrentAddsStayCapped() {
        val ts = (1..8).map { n -> Thread { repeat(200) { Held.add("q$n-$it", t0 + it) } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        assertEquals(3, Held.count())
    }

    // 8 / build: every caller of Brain.ask must still compile. The service passes onAck as a trailing lambda,
    // so onAck must stay the LAST parameter of Brain.ask (a trailing lambda binds to the last parameter).
    @Test fun brainAskKeepsOnAckLast() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java/com/ziyair/jarvis/Brain.kt").exists() }
        val brain = File(root, "app/src/main/java/com/ziyair/jarvis/Brain.kt").readText()
        val sig = Regex("fun ask\\(([^\\n]*)\\): String").find(brain)!!.groupValues[1]
        val service = File(root, "app/src/main/java/com/ziyair/jarvis/JarvisService.kt").readText()
        val trailing = Regex("brain\\.ask\\([^\\n]*\\)\\s*\\{").containsMatchIn(service)
        if (trailing) assertTrue("Brain.ask is called with a trailing lambda but its last parameter is not onAck: $sig",
            sig.trim().substringAfterLast(",").trim().startsWith("onAck"))
    }

    // round 2: per-request catch-up line (AT 4 + 6 together)
    @Test fun combineAnswersThenReminder() {
        val missed = listOf(Held.Item("Text Jess I'm late", t0))
        assertEquals("Signal's back, sir. It's 54 and cloudy. You'd asked \"text jess i'm late\". Say it again if you still want it.",
            Held.combine(listOf("Signal's back, sir. It's 54 and cloudy."), missed))
        assertEquals("Signal's back, sir. You'd asked \"text jess i'm late\". Say it again if you still want it.", Held.combine(emptyList(), missed))
        assertEquals("Signal's back, sir. It's 54 and cloudy.", Held.combine(listOf("It's 54 and cloudy."), emptyList()))
        assertEquals("", Held.combine(emptyList(), emptyList()))
        // an answer that was only the lead-in counts as nothing; the reminder still gets said, "Signal's back" once
        val l = Held.combine(listOf("Signal's back, sir."), missed)
        assertEquals(1, Regex("signal'?s back", RegexOption.IGNORE_CASE).findAll(l).count())
        assertTrue(l.contains("text jess i'm late"))
    }

    // round 2: holding tells the service, every time (not just the first dead spot)
    @Test fun onHoldFiresOnEveryHold() {
        var n = 0
        Held.onHold = { n++ }
        try { Held.add("a", t0); Held.add("b", t0); Held.add(" ", t0) } finally { Held.onHold = null }
        assertEquals(2, n)
    }

    // round 3, AT 8: a slow but working Claude answer (Sonnet + web search on the non-streaming path) must never be
    // turned into "No signal, sir." A first read timeout may only be held when the network really is unreachable.
    @Test fun slowAnswerOnWorkingNetworkIsNotHeld() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java/com/ziyair/jarvis/Brain.kt").exists() }
        val brain = File(root, "app/src/main/java/com/ziyair/jarvis/Brain.kt").readText()
        val holdOnTimeout = Regex("if \\(e is java\\.net\\.SocketTimeoutException[^{]*\\{[^}]*Held\\.add").find(brain)
        if (holdOnTimeout != null) assertTrue("a read timeout is held as 'No signal' without checking the network: ${holdOnTimeout.value}",
            holdOnTimeout.value.contains("reachable()") || holdOnTimeout.value.contains("isValidated()"))
        val post = brain.substringAfter("private fun post(").substringBefore("}")
        val read = Regex("readTimeout = (\\d+)").find(post)!!.groupValues[1].toInt()
        assertTrue("post() readTimeout $read ms is shorter than before L8 (30000)", read >= 30000)
    }
}
