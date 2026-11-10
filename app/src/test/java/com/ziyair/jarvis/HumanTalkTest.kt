package com.ziyair.jarvis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Live talking sounds like a person who knows him, and the nightly picture learns how he talks. */
class HumanTalkTest {
    private val src = "src/main/java/com/ziyair/jarvis/"

    @Test fun liveSoundsHuman() {
        val brain = File(src + "Brain.kt").readText()
        assertTrue(brain.contains("sound like a real person"))
        assertTrue(brain.contains("match his energy"))
        assertTrue(brain.contains("How to talk with him"))
        assertTrue("live still uses his memory and picture of him", brain.contains("Dream.promptBlock(context)") && brain.contains("Memory.promptBlock(context)"))
    }

    @Test fun dreamLearnsHowHeTalks() {
        assertTrue(File(src + "Dream.kt").readText().contains("How to talk with him (his humor, his slang"))
    }

    @Test fun liveTurnsAreRemembered() {
        val live = File(src + "Live.kt").readText()
        assertTrue(live.contains("addTurn(\"user\"") && live.contains("addTurn(\"assistant\""))
    }
}
