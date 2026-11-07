package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** From his log of Nov 6: songs with "sorry" on the end, a $0 gas expense, a silent empty answer, Jarvis left off after Android stopped him. */
class LogFixes2Test {
    @Test fun songRequestsLoseTheFiller() {
        assertEquals("far by nf", SongQuery.clean("far by nf sorry"))
        assertEquals("Far NF", SongQuery.clean("Far NF, please"))
        assertEquals("lose yourself", SongQuery.clean("um lose yourself for me Jarvis"))
        assertEquals("sorry", SongQuery.clean("sorry"))
        assertEquals("Now and Then", SongQuery.clean("Now and Then"))
    }

    @Test fun fixesAreInPlace() {
        val src = "src/main/java/com/ziyair/jarvis/"
        val actions = File(src + "Actions.kt").readText()
        assertTrue(actions.contains("Not logged yet: no amount"))
        val svc = File(src + "JarvisService.kt").readText()
        assertTrue(svc.contains("speak(\"Say that again, sir?\")") && svc.contains("revive_at"))
        assertTrue(File(src + "Messages.kt").readText().contains("lastMissedLog"))
        assertTrue(File(src + "Mind.kt").readText().contains("never tell him to call a doctor over a logging gap"))
        assertTrue(File(src + "Control.kt").readText().contains("youtube/v3/search"))
    }
}

class BreathingRoomTest {
    @Test fun midThoughtPausesWait() {
        org.junit.Assert.assertFalse(EarlyRead.complete("I need"))
        org.junit.Assert.assertFalse(EarlyRead.complete("Can you play"))
        org.junit.Assert.assertFalse(EarlyRead.complete("I'm gonna"))
        org.junit.Assert.assertTrue(EarlyRead.complete("What's the weather like"))
        val ear = java.io.File("src/main/java/com/ziyair/jarvis/Ear.kt").readText()
        assertTrue(ear.contains("maxOf(650, cfg.optInt(\"ear_fast_end_ms\", 700))") && ear.contains("giving him a moment"))
    }
}

class TalkModeTest {
    @Test fun conversationStaysOnTheFastBrain() {
        // talk mode: real questions and opinions go fast and free; doing things and his data still go to Claude
        assertTrue(FastChat.route("what do you think about electric cars versus gas cars", true, chatFirst = true))
        assertTrue(FastChat.route("why do people get nervous before big decisions", true, chatFirst = true))
        org.junit.Assert.assertFalse(FastChat.route("how much did I make today", true, chatFirst = true))
        org.junit.Assert.assertFalse(FastChat.route("set a reminder for 5", true, chatFirst = true))
        org.junit.Assert.assertFalse(FastChat.route("why do people get nervous before big decisions", true))   // old behaviour unchanged when off
    }
}

class CloudStreamTest {
    @Test fun rawAudioBecomesFloats() {
        val raw = byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte())   // 0, max, min
        val f = Pcm.floats(raw)
        assertEquals(3, f.size); assertEquals(0f, f[0], 0.0001f); assertEquals(1f, f[1], 0.001f); assertEquals(-1f, f[2], 0.0001f)
        val wav = ByteArray(44) { 0 }.also { it[0] = 'R'.code.toByte(); it[1] = 'I'.code.toByte(); it[2] = 'F'.code.toByte(); it[3] = 'F'.code.toByte() } + raw
        assertEquals(3, Pcm.floats(wav).size)   // the WAV header is skipped
        val svc = java.io.File("src/main/java/com/ziyair/jarvis/JarvisService.kt").readText()
        assertTrue(svc.contains("(Piper.installed(this) || prefs.cloudVoice) && Cast.mode(prefs)") && svc.contains("cloudPcm()"))
    }
}
