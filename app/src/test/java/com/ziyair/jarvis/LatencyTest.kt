package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The speed-ups: he starts talking at the first natural pause, early reads are only used when safe, and the line stays open. */
class LatencyTest {
    private fun speak(early: Boolean, vararg parts: String): List<String> {
        val out = ArrayList<String>()
        val s = Brain.Sentences({ out.add(it) }, early)
        parts.forEach { s.feed(it) }
        s.flush()
        return out
    }

    @Test fun startsAtTheFirstClause() {
        val out = speak(true, "Right, so you're at a hundred ", "and twelve today, which puts you ", "eight off your target. Ziyair's next feed is around eleven forty.")
        assertEquals("Right, so you're at a hundred and twelve today,", out[0])
        assertEquals("you're at a hundred and twelve today, which puts you eight off your target. Ziyair's next feed is around eleven forty.".substringAfter("today, "), out.drop(1).joinToString(" "))
    }

    @Test fun shortRepliesStillGoAsAWhole() {
        assertEquals(listOf("Done, sir."), speak(true, "Done, sir."))
        assertEquals(listOf("Lights are off."), speak(true, "Lights ", "are off."))
    }

    @Test fun sentenceEndBeforeAnyCommaStillWins() {
        val out = speak(true, "It's sixty-two degrees. Later, it drops to fifty, so bring a jacket.")
        assertEquals("It's sixty-two degrees.", out[0])
    }

    @Test fun canBeSwitchedOff() {
        val out = speak(false, "Right, so you're at a hundred and twelve today, which puts you eight off. Next.")
        assertTrue(out[0].endsWith("eight off."))
    }

    @Test fun earlyReadOnlyWhenNothingCameAfter() {
        val r = 16000
        assertTrue(EarlyRead.usable(r * 2, r * 2, r))
        assertTrue(EarlyRead.usable(r * 2 + r / 5, r * 2, r))           // a little padding is fine
        assertFalse(EarlyRead.usable(r * 3, r * 2, r))                   // he kept talking: read again
        assertFalse(EarlyRead.usable(r, 0, r))
        assertTrue(EarlyRead.voiced(0.08f, 0.01f))
        assertFalse(EarlyRead.voiced(0.01f, 0.01f))
        assertFalse(EarlyRead.voiced(0.05f, 0.03f))                      // noisy room: needs to stand out
    }

    @Test fun lineToClaudeStaysOpenAndPromptIsCached() {
        val brain = File("src/main/java/com/ziyair/jarvis/Brain.kt").readText()
        val streamFn = brain.substringAfter("private fun postStream").substringBefore("private fun post(")
        assertFalse("streaming reply must not hang up the connection on success", streamFn.substringAfter("bufferedReader().use").contains("c.disconnect()\n        val content"))
        assertTrue(brain.contains("stablePrompt(cfg)).put(\"cache_control\""))
        assertTrue(brain.contains("fun warmLine()"))
    }
}
