package com.ziyair.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** From his 11-02 log: Jarvis answered people talking nearby, for minutes, without hearing his name. */
class FollowUpTest {
    @Test fun aQuickReplyToJarvisStillWorks() {
        assertNull(FollowUp.reject("Yeah let's do it.", 4_000, 0))
        assertNull(FollowUp.reject("Instagram.", 6_000, 1))
        assertNull(FollowUp.reject("Thank you.", 3_000, 2))
    }

    @Test fun peopleTalkingNearbyIsIgnored() {
        assertNotNull("long after he spoke", FollowUp.reject("U Rlying, okay, Ben", 40 * 60_000L, 0))
        assertNotNull("long chatter", FollowUp.reject("gay gay, That's just what Yeah we can try and prevent it. I want to be a grandma. He get another dog. We're having another. Yeah. You already made that very clear. Yeah. If I want do or not, you basically just told me.", 5_000, 0))
        assertNotNull("endless back and forth", FollowUp.reject("dude I would like", 3_000, 4))
        assertNull("a long request that names him is fine", FollowUp.reject("Jarvis I need you to help me plan the week, we have the doctor on Tuesday and I want to dash Wednesday and Thursday and then rest on the weekend if we can", 4_000, 0))
    }

    @Test fun afterAskingWhatWasSaidHeStopsListening() {
        assertTrue(FollowUp.clarifying("I'm not quite following you there, sir. Could you say what you'd like me to do?"))
        assertTrue(FollowUp.clarifying("I'm sorry, sir, that came through as fragments and I can't tell what you're asking."))
        assertTrue(FollowUp.clarifying("Go on, sir, I'm listening. What would you like?"))
        assertFalse(FollowUp.clarifying("Want me to queue up something for the drive?"))
    }
}
