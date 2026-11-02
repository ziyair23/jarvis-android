package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Instant commands: the right things are caught on the phone, and nothing that needs thought is. */
class FastTest {
    private fun p(t: String) = Fast.parse(t, "ziyair")

    @Test fun babyEvents() {
        for (t in listOf("he just ate", "ziyair just ate", "we just fed him", "log a feed", "bottle's done", "he finished his bottle")) assertEquals(t, Fast.Quick.Baby("feed"), p(t))
        for (t in listOf("just changed him", "changed his diaper", "he pooped", "log a diaper", "diaper change")) assertEquals(t, Fast.Quick.Baby("diaper"), p(t))
        for (t in listOf("he's asleep", "he's down", "ziyair is sleeping", "we just put him down", "he fell asleep")) assertEquals(t, Fast.Quick.Baby("sleep"), p(t))
        for (t in listOf("he's up", "he's awake", "he just woke up", "ziyair is awake")) assertEquals(t, Fast.Quick.Baby("wake"), p(t))
    }

    @Test fun moneyOnlyWhenClear() {
        assertEquals(Fast.Quick.Earn(40.0), p("i made 40 bucks"))
        assertEquals(Fast.Quick.Earn(12.0), p("just made 12 on that one"))
        assertEquals(Fast.Quick.Earn(25.0), p("got 25 dollars on that order"))
        assertNull("a day total needs Claude to decide add vs replace", p("i made 112 today"))
        assertNull(p("i made 40 bucks so far"))
        assertNull(p("how much did i make"))
    }

    @Test fun lists() {
        assertEquals(Fast.Quick.ListAdd("wipes"), p("add wipes to the list"))
        assertEquals(Fast.Quick.ListAdd("diapers and formula"), p("put diapers and formula on my shopping list"))
        assertNull(p("add that to the list"))
    }

    @Test fun leavesEverythingElseToJarvis() {
        for (t in listOf("i just ate", "what time does he eat", "call mom", "text dad i'm on my way", "is he asleep", "he's not asleep",
            "how much have i made", "play some music", "add a reminder for 5", "i got a new order")) assertNull(t, p(t))
    }

    @Test fun confirmationsAreOnlySaidWhenTheyWorked() {
        assertEquals("Timer set for 10 minutes, ends at 9:40 PM.", Fast.line("set_timer", "Timer set for 10 minutes, ends at 9:40 PM."))
        assertNull(Fast.line("remind_me", "Couldn't read that time."))
        assertEquals("Alarm set for 6:30.", Fast.line("set_alarm", "Alarm set in the Clock app for 6:30."))
        assertEquals("Feed logged, sir. Next one around 12:40 AM.", Fast.line("log_baby", "Logged feed at 9:40 PM. Next feed heads-up at 12:40 AM."))
        assertEquals("Diaper logged.", Fast.line("log_baby", "Logged diaper at 9:40 PM."))
        assertEquals("Logged. You're at $112 today.", Fast.line("log_earnings", "Logged. Today is now $112. This week: $640..."))
        assertNull(Fast.line("log_earnings", "Couldn't tell which day that was."))
        assertEquals("Added wipes to the shopping list.", Fast.line("list_add", "Added wipes to the shopping list. 3 things on it now."))
        assertNull(Fast.line("call", "No contact found for that name."))
        assertNull("not a quick tool", Fast.line("get_weather", "62 and clear."))
    }

    @Test fun callsStayWithThePhoneAgent() { assertTrue("call" !in Agents.CORE) }
}
