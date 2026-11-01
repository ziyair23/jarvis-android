package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tester round 2 for lab L11: real tool replies and real speech-to-text spellings. */
class ProtocolRulesRound2Test {
    private val gym = listOf("phone_control", "play_music", "lights")

    // Real failure replies from Actions.run / Apps.open / Lights.set for allowed steps must count as failed,
    // otherwise he hears "Gym protocol: open Spotify." when nothing opened.
    @Test fun realFailureRepliesAreCaught() {
        for (bad in listOf(
            "No app called Spotfy on the phone.",            // Apps.open
            "No light called bedroom. He has: Desk, Strip.",  // Lights.set
            "Need a length in minutes.",                      // set_timer
            "Couldn't read that time.",                       // remind_me / set_alarm
            "He needs to let Jarvis open apps: open the Jarvis app and tap Allow opening apps.",
            "That didn't work: No value for time",            // Actions.run catch
            "Unknown music control."))
            assertTrue(bad, ProtocolRules.failed(bad))
    }

    // Real success replies must not be reported as failures.
    @Test fun realSuccessRepliesPass() {
        for (ok in listOf("Do Not Disturb on.", "Do Not Disturb off.", "Media volume set to 3 out of 10.", "Flashlight on.",
            "Done: Bedroom, Living room.", "Opened Spotify.", "Opened Instagram. He'll need to unlock to see it.",
            "Playing Workout Mix on YouTube Music.", "Skipped to the next track on Spotify.", "Timer set for 45 minutes, ends at 6:45.",
            "Reminder set for 6:00 PM.", "Alarm set in the Clock app for 6:00.", "Driving mode on.",
            "Quiet mode on until he says he's out. Not a sound from Jarvis; everything gets saved for a catch-up afterwards."))
            assertTrue(ok, !ProtocolRules.failed(ok))
    }

    // On-device speech often splits words: "work out" for a saved "workout". It must run his protocol,
    // not fall through to the built-in "dash" (which matches the substring "work").
    @Test fun spacingVariantsStillMatch() {
        assertEquals("workout", ProtocolRules.match("work out protocol", listOf("workout")))
        assertEquals("workout", ProtocolRules.match("start work out", listOf("workout")))
        assertEquals("study time", ProtocolRules.match("studytime mode", listOf("study time")))
    }

    // Only the built-in protocols' own names are taken; ordinary names that merely contain a word like "night" are his to use.
    @Test fun naturalNamesAreAllowed() {
        for (n in listOf("movie night", "date night", "game night", "bedroom", "home gym", "workout", "homework"))
            assertNull(n, ProtocolRules.check(n, gym, emptySet()))
        for (n in listOf("dash", "baby", "home", "goodnight", "good night", "bedtime", "bed", "sleep", "night feed", "Dash protocol"))
            assertEquals(n, "That one's built in, sir. Pick another name.", ProtocolRules.check(n, gym, emptySet()))
    }

    @Test fun runLineShapes() {
        assertEquals("Gym protocol: quiet, workout playlist, lights off.", ProtocolRules.runLine("gym", listOf("quiet", "workout playlist", "lights off"), emptyList(), emptyList()))
        val l = ProtocolRules.runLine("gym", listOf("quiet"), listOf("lights off", "workout playlist"), emptyList())
        assertTrue(l, l.contains("didn't work") && !l.contains("\n"))
        assertTrue(ProtocolRules.runLine("gym", emptyList(), emptyList(), listOf("52°F, clear.")).startsWith("Gym protocol."))
    }
}
