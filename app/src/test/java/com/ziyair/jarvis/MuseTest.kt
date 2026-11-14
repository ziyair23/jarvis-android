package com.ziyair.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Jarvis hands web jobs to Muse only after a yes, and only Muse's WhatsApp chat counts as Muse. */
class MuseTest {
    private val src = "src/main/java/com/ziyair/jarvis/"

    @Test fun recognisesMuse() {
        assertTrue(Muse.isMuse("Muse", "WhatsApp"))
        assertTrue(Muse.isMuse("Muse from Meta", "WhatsApp"))
        assertFalse(Muse.isMuse("Muse", "Messages"))          // a text from someone saved as Muse isn't the agent
        assertFalse(Muse.isMuse("Museum Tickets", "WhatsApp"))
        assertFalse(Muse.isMuse("Mom", "WhatsApp"))
    }

    @Test fun asksFirst() {
        val m = File(src + "Muse.kt").readText()
        assertTrue(m.contains("if (!confirmed) return \"Read the job back"))
        assertTrue("Muse checks before paying", Muse.message("Book a haircut Saturday").contains("ask me first before paying"))
    }

    @Test fun wiredIn() {
        val a = File(src + "Actions.kt").readText()
        assertTrue(a.contains("tool(\"ask_muse\"") && a.contains("\"ask_muse\" ->"))
        assertTrue("ask_muse" in Agents.CORE)
        assertTrue("Muse's answers are read out", File(src + "Messages.kt").readText().contains("Muse.onReply(this"))
    }
}
