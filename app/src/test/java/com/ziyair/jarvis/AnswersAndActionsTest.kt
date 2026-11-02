package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** From his 11-02 log: real questions got one-liners, and "can you log..." was treated as a deep question (slow). */
class AnswersAndActionsTest {
    @Test fun realQuestionsGetARealAnswer() {
        for (t in listOf(
            "So what if I switched you out for a live model instead of like you answering like how you are now? Would that be better",
            "I need you to help me create my grocery list. Me and my girlfriend are trying to eat healthy. What else should I add",
            "Can you look up what the best grocery places are in Spokane?",
            "Do you think I should dash tonight or rest",
            "okay so what's better for gas mileage, highway or city",
            "Explain how DoorDash ratings work")) assertTrue(t, Brain.isRealQuestion(t))
    }

    @Test fun commandsStayQuick() {
        for (t in listOf(
            "Can you a log that we just fed and changed",
            "Can you, uh, can you put down that he just ate, please",
            "can you open instagram", "Call Justus", "text Toby I'm on my way", "turn off the lights",
            "set a timer for ten minutes", "what time is it", "play some Drake")) assertFalse(t, Brain.isRealQuestion(t))
    }

    @Test fun everydayActionsNeedNoThinking() {
        assertEquals(Fast.Direct.Open("instagram"), Fast.direct("open instagram"))
        assertEquals(Fast.Direct.Open("camera"), Fast.direct("open up the camera"))
        assertEquals(Fast.Direct.Call("mom"), Fast.direct("call my mom"))
        assertEquals(Fast.Direct.Call("justus"), Fast.direct("call justus"))
        assertEquals(Fast.Direct.Nav("costco"), Fast.direct("take me to costco"))
        assertEquals(Fast.Direct.Nav("home"), Fast.direct("take me home"))
        assertEquals(Fast.Direct.Play("drake"), Fast.direct("play drake"))
        assertEquals(Fast.Direct.Play("lofi beats on"), Fast.direct("play lofi beats on").let { it })
        assertEquals(Fast.Direct.Yes, Fast.direct("yes send it"))
    }

    @Test fun vagueOnesGoToJarvis() {
        for (t in listOf("open it", "open the door", "call me back", "call an uber", "play it again", "play something",
            "play a youtube video", "take me", "yes i think so but")) assertNull(t, Fast.direct(t))
    }
}
