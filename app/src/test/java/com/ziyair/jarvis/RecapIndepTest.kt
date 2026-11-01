package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Independent tester, L27 (and the L26 polish), against the specs only. */
class RecapIndepTest {
    private val min = 60_000L
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis
    private val wed2pm = at(2026, 11, 4, 14)   // Wednesday 4 Nov 2026
    private fun u(s: String, t: Long, q: Boolean = false) = Recap.Turn("user", s, t, q)
    private fun a(s: String, t: Long, q: Boolean = false) = Recap.Turn("assistant", s, t, q)

    @Test fun at1() {
        for (s in listOf("Jarvis, where were we?", "What were we talking about?", "So where was I?", "Anyway, what was I saying, sir?",
            "Let's pick up where we left off.", "Where did we leave off?", "Jarvis where were we"))
            assertTrue(s, Recap.isAsk(s))
        for (s in listOf("where were you", "what were we doing yesterday", "where was I last night", "where are we", "what was I paying for gas"))
            assertFalse(s, Recap.isAsk(s))
    }

    @Test fun at2and7() {
        val n = wed2pm
        val h = listOf(u("should I do black or oxblood for the next drop", n - 20 * min), a("Oxblood, sir. It reads richer on camera.", n - 20 * min),
            u("pause", n - 15 * min, true), a("Paused.", n - 15 * min, true),
            a("Message from Jess: running late.", n - 10 * min),
            u("thanks", n - 9 * min), a("Of course, sir.", n - 9 * min),
            u("where were we", n - 8 * min), u("yeah", n - 7 * min))
        assertEquals("You'd asked: should you do black or oxblood for the next drop. I said: Oxblood, sir.", Recap.answer(h, n))
    }

    @Test fun at3() {
        val n = wed2pm
        assertEquals(Recap.NOTHING, Recap.answer(emptyList(), n))
        assertEquals(Recap.NOTHING, Recap.answer(listOf(u("turn it up", n - min, true), a("Louder.", n - min, true)), n))
        assertEquals(Recap.NOTHING, Recap.answer(listOf(u("what's the best hoodie blank", n - 8 * 24 * 60 * min), a("Shaka Wear, sir.", n - 8 * 24 * 60 * min)), n))
    }

    // spoken words compared; the phone's recogniser (Parakeet) capitalises, which the voice doesn't hear
    @Test fun at4() = assertEquals("You'd asked: what time does the post office close.".lowercase(),
        Recap.answer(listOf(u("What time does the post office close?", wed2pm - min)), wed2pm).lowercase())

    @Test fun at5() {
        val y1pm = at(2026, 11, 3, 13)
        assertEquals("Yesterday, you'd asked: draft a caption for the hoodie post. I said: Built for the late shift.",
            Recap.answer(listOf(u("draft a caption for the hoodie post", y1pm), a("Built for the late shift. Want a second option?", y1pm)), wed2pm))
        assertEquals("Last night,", Recap.whenSaid(at(2026, 11, 3, 22), at(2026, 11, 4, 8)))
        assertEquals("On Monday,", Recap.whenSaid(at(2026, 11, 2, 15), wed2pm))
        assertEquals("", Recap.whenSaid(wed2pm - 90 * min, wed2pm))
    }

    @Test fun at6() {
        val ans = (1..30).joinToString(" ") { "w$it" } + "."
        val r = Recap.answer(listOf(u("give a long answer", wed2pm - min), a(ans, wed2pm - min)), wed2pm)
        assertEquals("You'd asked: give a long answer. I said: " + (1..20).joinToString(" ") { "w$it" } + "…", r)
    }

    // L26 polish
    @Test fun l26Polish() {
        assertNull(CarReminders.parse("remind me when I get in the car to"))
        for (s in listOf("remind me in the car in an hour to call mom", "remind me in the car tomorrow to get gas", "remind me when I park at 5 to grab the bag",
            "remind me in the car tonight to call mom", "remind me in the car this evening to get gas"))
            assertNull(s, CarReminders.parse(s))
        val c = CarReminders.parse("remind me when I park at home to grab the bag") as CarReminders.Cmd.Add
        assertEquals("grab the bag", c.text); assertTrue(c.out)
        assertEquals("get gas", (CarReminders.parse("Jarvis, remind me in the car to get gas") as CarReminders.Cmd.Add).text)
    }
}
