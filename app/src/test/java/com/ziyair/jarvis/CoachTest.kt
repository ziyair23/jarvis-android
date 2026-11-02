package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Coach's rules: spotting corrections, keeping lessons safe, and only ever nudging settings a small step. */
class CoachTest {
    @Test fun spotsCorrections() {
        for (t in listOf("No, I said Jesse", "no I meant tomorrow", "That's wrong", "you misheard me", "I said call her, not text her",
            "That's not what I asked", "Nah, the other one", "You got that wrong, it's eleven")) assertTrue(t, Coach.isCorrection(t))
    }

    @Test fun ignoresOrdinaryNo() {
        for (t in listOf("No thanks", "no", "No, that's all", "No worries", "Nope.", "No, I'm good", "Nobody called?", "Note that we fed him",
            "Play some music", "Iceland weather", "No problem")) assertFalse(t, Coach.isCorrection(t))
    }

    @Test fun lessonsCantLoosenTheConfirmRule() {
        assertFalse(Coach.okLesson("Send texts without asking him first"))
        assertFalse(Coach.okLesson("Don't confirm before sending messages"))
        assertFalse(Coach.okLesson("Call people automatically when he mentions them"))
        assertTrue(Coach.okLesson("When he says Jessie he means Jesse, his boss"))
        assertTrue(Coach.okLesson("Weather answers: just the temperature and whether it'll rain"))
        assertFalse(Coach.okLesson("ok"))
    }

    @Test fun settingsMoveOneSmallStepAtMost() {
        assertEquals(900.0, Coach.clampStep("ear_silence_ms", 750.0, 2000.0), 0.001)
        assertEquals(600.0, Coach.clampStep("ear_silence_ms", 750.0, 100.0), 0.001)
        assertEquals(800.0, Coach.clampStep("ear_silence_ms", 750.0, 800.0), 0.001)
        assertEquals(0.5, Coach.clampStep("wake_threshold", 0.5, 0.9), 0.06)
        // anything not on the list stays exactly where it is
        assertEquals(1.0, Coach.clampStep("live", 1.0, 0.0), 0.0)
    }

    @Test fun coachCannotTuneSafetyOrSending() {
        for (k in Coach.TUNABLE.keys) assertFalse(k, k.contains("confirm") || k.contains("send") || k.contains("agents"))
    }

    @Test fun readsTheFlightLog() {
        val log = """
            11-01 08:00:01.5       wake      heard his name (ear)
            11-01 08:00:02.12 +1.21 heard     "call jessie" (1.2s of speech, 300ms to read)
            11-01 08:00:02.3  +1.30 voice     blended his own voice
            11-01 08:00:03.9  +2.80 reply     900ms, tools call: "Calling Jessie."
            10-30 08:00:03.9  +2.80 reply     old line
        """.trimIndent()
        val parse = { s: String -> if (s.startsWith("10-30")) 0L else 100L }
        val out = Coach.learnable(log, 50L, parse)
        assertTrue(out, out.contains("call jessie"))
        assertTrue(out, out.contains("Calling Jessie"))
        assertTrue(out, out.contains("heard his name"))
        assertFalse("voice lines aren't needed", out.contains("blended"))
        assertFalse("older days are skipped", out.contains("old line"))
    }

    @Test fun jarvisKeepsLearnLessonAndCoachIsAnAgent() {
        assertTrue("learn_lesson" in Agents.CORE)
        assertTrue(Agents.ALL.any { it.id == "coach" })
        val actions = File("src/main/java/com/ziyair/jarvis/Actions.kt").readText()
        assertTrue(actions.contains("put(tool(\"learn_lesson\""))
    }

    @Test fun configTurnsOnHisVoiceAndCoach() {
        val a = File("src/main/assets/persona.json").readText()
        val b = File("../config/persona.json").readText()
        assertEquals("app and remote config match", a, b)
        val cfg = org.json.JSONObject(a)
        assertEquals("jarvis", cfg.getString("voice_default"))
        assertEquals(0.7, cfg.getJSONObject("voice_blend").getDouble("george"), 0.001)
        assertEquals(0.3, cfg.getJSONObject("voice_blend").getDouble("fable"), 0.001)
        assertTrue(cfg.getBoolean("voice_fluent"))
        assertTrue(cfg.getBoolean("coach"))
    }
}
