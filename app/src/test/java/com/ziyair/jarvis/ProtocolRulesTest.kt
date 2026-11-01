package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRulesTest {
    private val gym = listOf("phone_control", "play_music", "lights")

    @Test fun savesSafeSteps() = assertNull(ProtocolRules.check("gym", gym, emptySet()))

    @Test fun refusesTextsCallsAndTaps() {
        for (bad in listOf("send_text", "call", "reply_message", "screen_tap", "screen_type", "screen_action", "answer_call", "decline_call"))
            assertEquals(bad, "I can't put texting, calls or screen taps in a protocol, sir.", ProtocolRules.check("gym", gym + bad, emptySet()))
    }

    @Test fun refusesNestedAndUnknownTools() {
        assertTrue(ProtocolRules.check("gym", gym + "run_protocol", emptySet())!!.startsWith("I can only put"))
        assertTrue(ProtocolRules.check("gym", listOf("log_expense"), emptySet())!!.startsWith("I can only put"))
    }

    @Test fun namesAreLoose() {
        for (n in listOf("Gym", "gym protocol", "gym mode", "the gym protocol", "My Gym Routine")) assertEquals("gym", ProtocolRules.normName(n))
    }

    @Test fun builtInsAreProtected() {
        for (n in listOf("dash", "Home protocol", "goodnight", "baby mode"))
            assertEquals("That one's built in, sir. Pick another name.", ProtocolRules.check(n, gym, emptySet()))
    }

    @Test fun stepLimits() {
        assertEquals("That protocol has nothing in it yet, sir.", ProtocolRules.check("gym", emptyList(), emptySet()))
        assertTrue(ProtocolRules.check("gym", List(9) { "lights" }, emptySet())!!.contains("more than 8"))
        assertNull(ProtocolRules.check("gym", List(8) { "lights" }, emptySet()))
    }

    @Test fun protocolLimit() {
        val twenty = (1..20).map { "p$it" }.toSet()
        assertTrue(ProtocolRules.check("gym", gym, twenty)!!.contains("Delete one first"))
        assertNull(ProtocolRules.check("p3", gym, twenty))   // replacing one is fine
    }

    @Test fun instantMatch() {
        val names = listOf("gym", "study time")
        for (s in listOf("gym protocol", "gym mode", "run gym", "start gym", "start the gym protocol", "hey jarvis gym protocol", "study time mode"))
            assertTrue(s, ProtocolRules.match(s, names) != null)
        assertNull(ProtocolRules.match("gym", names))                       // bare word in a sentence: no
        assertNull(ProtocolRules.match("i'm going to the gym later", names))
        assertNull(ProtocolRules.match("dash protocol", names))             // built-ins stay with the old code
        assertNull(ProtocolRules.match("run errands", names))
    }

    @Test fun lines() {
        assertEquals("Gym protocol saved: quiet, workout playlist, lights off.",
            ProtocolRules.savedLine("gym", listOf(ProtocolRules.describe("phone_control", "quiet_on"), ProtocolRules.describe("play_music", "workout playlist"), ProtocolRules.describe("lights", "off")), false))
        assertTrue(ProtocolRules.savedLine("gym", listOf("x"), true).contains("updated"))
        assertEquals("Built in: dash, baby, home and goodnight. Yours: gym, study time.", ProtocolRules.listLine(listOf("gym", "study time")))
    }
}
