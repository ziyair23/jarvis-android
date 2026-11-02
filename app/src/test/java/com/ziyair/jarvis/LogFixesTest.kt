package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Straight from his 11-02 flight log: the voice crash, the reply to Dad he never approved, and "Justustice". */
class LogFixesTest {
    private val readBack = "Right, so here's what I've got for Justus: \"Hey, I was wondering what you're doing today, because I've got a free day.\" I took \"can't have\" to mean \"got a free day\". Shall I send it?"

    @Test fun theTextHeApprovedGoesOut() {
        assertTrue(Fast.readBack(listOf(readBack), "Hey, I was wondering what you're doing today, because I've got a free day."))
        assertTrue("tiny punctuation differences are fine", Fast.readBack(listOf(readBack), "Hey I was wondering what you're doing today because I've got a free day"))
        assertTrue(Fast.readBack(listOf("Reply to Toby: “You're good, don't worry about it.” Send it?"), "You're good, don't worry about it."))
    }

    @Test fun nothingHeDidntHearCanGoOut() {
        assertFalse("the reply to Dad he never heard", Fast.readBack(listOf(readBack), "okay, perfect, thank you"))
        assertFalse("a changed message", Fast.readBack(listOf(readBack), "Hey, want to hang out today? I've got a free day."))
        assertFalse("nothing read back", Fast.readBack(listOf("Done, sir."), "on my way"))
        assertFalse(Fast.readBack(emptyList(), "on my way"))
    }

    @Test fun misheardNamesFindTheRightContact() {
        val names = listOf("Justus Miller", "Dad", "Mom", "Toby", "Jesse", "Jessica Lane")
        assertEquals("Justus Miller", Fast.closestName("Justustice", names))
        assertEquals("Toby", Fast.closestName("Tobi", names))
        assertNull("two equally close: ask, don't guess", Fast.closestName("Jess", listOf("Jesse", "Jessa")))
        assertNull(Fast.closestName("Zachary", names))
    }

    @Test fun voiceNoLongerUsesTheCrashingCallback() {
        val src = File("src/main/java/com/ziyair/jarvis/PiperVoice.kt").readText()
        assertFalse(src.contains("generateWithCallback("))
        assertEquals(listOf("Evening, Marcus.", "You're at a hundred and twelve today.", "Want me to queue something up?"),
            Piper.sentences("Evening, Marcus. You're at a hundred and twelve today. Want me to queue something up?"))
        assertEquals(listOf("Ask Dr. Lee at St. Luke's about it."), Piper.sentences("Ask Dr. Lee at St. Luke's about it."))
    }

    @Test fun sendsAreCheckedInCode() {
        val src = File("src/main/java/com/ziyair/jarvis/Actions.kt").readText()
        assertEquals("send_text and reply_message both check", 2, Regex("readBackOk\\(").findAll(src.substringBefore("private fun readBackOk")).count())
    }
}
