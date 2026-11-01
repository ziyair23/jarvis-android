package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tester's checks for lab L11, written from lab/specs/L11.md (not from the code). */
class ProtocolRulesSpecTest {
    private val gym = listOf("phone_control", "play_music", "lights")
    private val refuse = "I can't put texting, calls or screen taps in a protocol, sir."

    // AT1: the spec's own example line
    @Test fun at1_savedLineNamesSteps() {
        assertNull(ProtocolRules.check("gym", gym, emptySet()))
        val line = ProtocolRules.savedLine("gym", listOf(
            ProtocolRules.describe("phone_control", "quiet_on"),
            ProtocolRules.describe("play_music", "workout playlist"),
            ProtocolRules.describe("lights", "off")), false)
        assertEquals("Gym protocol saved: quiet, workout playlist, lights off.", line)
        assertTrue(line.split(" ").size <= 12)
    }

    // AT2: every listed tool, run_protocol included, gets exactly the spec's refusal line
    @Test fun at2_everyForbiddenToolGetsTheSpecLine() {
        for (bad in listOf("send_text", "call", "reply_message", "screen_tap", "screen_type", "screen_action", "answer_call", "decline_call", "run_protocol"))
            assertEquals(bad, refuse, ProtocolRules.check("gym", gym + bad, emptySet()))
    }

    // AT2 also when the forbidden step comes first or alone
    @Test fun at2_forbiddenAloneOrFirst() {
        assertEquals(refuse, ProtocolRules.check("gym", listOf("send_text"), emptySet()))
        assertEquals(refuse, ProtocolRules.check("gym", listOf("call") + gym, emptySet()))
    }

    // Spec "Never" list: nothing that talks to people, touches the screen, logs/deletes data, memory, budgets, protocols
    @Test fun allowedListHasNothingFromTheNeverList() {
        val never = listOf("send_text", "call", "reply_message", "answer_call", "decline_call", "screen_tap", "screen_type", "screen_action",
            "phone_task", "remember", "forget", "log_earnings", "log_expense", "log_baby", "list_add", "list_check", "list_clear",
            "cancel_reminders", "set_budget", "set_goal", "run_protocol", "save_protocol", "delete_protocol", "journal_add", "navigate", "offer_rule")
        for (t in never) assertFalse(t, t in ProtocolRules.ALLOWED)
        assertEquals(setOf("lights", "phone_control", "quiet_mode", "open_app", "play_music", "media_control", "set_timer", "set_alarm",
            "remind_me", "driving_mode", "get_weather", "calendar_events", "status_report", "list_read", "house_speaker"), ProtocolRules.ALLOWED)
    }

    // AT3
    @Test fun at3_looseNames() {
        for (n in listOf("Gym", "gym protocol", "gym mode", "the gym protocol", "GYM Protocol.", "  gym  ")) assertEquals(n, "gym", ProtocolRules.normName(n))
    }

    // AT4
    @Test fun at4_builtInsCantBeOverwritten() {
        for (n in listOf("dash", "baby", "home", "goodnight", "Dash Protocol", "the baby mode", "good night", "Goodnight."))
            assertEquals(n, "That one's built in, sir. Pick another name.", ProtocolRules.check(n, gym, emptySet()))
    }

    // AT5
    @Test fun at5_limits() {
        assertTrue(ProtocolRules.check("gym", emptyList(), emptySet())!!.length < 60)
        assertTrue(ProtocolRules.check("gym", List(9) { "lights" }, emptySet())!!.length < 60)
        val twenty = (1..20).map { "p$it" }.toSet()
        assertTrue(ProtocolRules.check("gym", gym, twenty)!!.contains("Delete one", ignoreCase = true))
        assertNull(ProtocolRules.check("gym", gym, twenty - "p1"))
    }

    // AT6: every spoken form the spec names, plus "hey jarvis" in front
    @Test fun at6_spokenFormsMatch() {
        val names = listOf("gym")
        for (s in listOf("gym protocol", "gym mode", "run gym", "start gym", "Gym protocol.", "jarvis gym protocol", "run the gym protocol", "start gym mode"))
            assertEquals(s, "gym", ProtocolRules.match(s, names))
    }

    // Noisy car: sentences that merely contain his protocol name must not fire it
    @Test fun mishearsDontFire() {
        val names = listOf("gym", "chill")
        for (s in listOf("gym", "i'm heading to the gym", "remind me to go to the gym at five", "text her i'm at the gym",
                "what's the gym protocol", "delete the gym protocol", "make a protocol called gym", "start gym at 6", "run gym tomorrow",
                "chill", "just chill", "go to the gym", "is gym mode on"))
            assertNull(s, ProtocolRules.match(s, names))
    }

    // Built-ins never go down the custom path
    @Test fun builtInsNotMatchedAsCustom() {
        for (s in listOf("dash protocol", "baby mode", "home protocol", "goodnight protocol")) assertNull(s, ProtocolRules.match(s, listOf("gym")))
    }

    // AT7
    @Test fun at7_listIsOneShortLine() {
        val l = ProtocolRules.listLine(listOf("gym", "study"))
        assertTrue(l.contains("dash") && l.contains("goodnight") && l.contains("gym") && l.contains("study"))
        assertFalse(l.contains("\n"))
        assertTrue(ProtocolRules.listLine(emptyList()).contains("dash"))
    }

    // AT9
    @Test fun at9_replacingSaysUpdated() {
        assertNull(ProtocolRules.check("gym", gym, setOf("gym")))
        assertTrue(ProtocolRules.savedLine("gym", listOf("quiet"), true).contains("updated"))
    }
}
