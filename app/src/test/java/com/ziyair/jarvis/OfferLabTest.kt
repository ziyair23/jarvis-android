package com.ziyair.jarvis

import com.ziyair.jarvis.OfferMath.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tester's extra checks for lab ideas L1 and L2 (acceptance tests + real-world card text). */
class OfferLabTest {
    private val sevenPm = 19 * 60
    private fun words(s: String) = s.trim().split(Regex("\\s+")).size

    // ---------- L2 ----------
    @Test fun l2_t1_numbers() {
        val o = OfferMath.parse("\$8.50 | 2.1 mi | 18 min | Accept", sevenPm)!!
        assertEquals(28.33, OfferMath.perHour(o)!!, 0.01)
        val line = OfferMath.line(o, 2.0, 20.0)
        assertTrue(line, line.contains("4.05 dollars a mile, about 28 an hour"))
    }

    @Test fun l2_t2_hourlyBeatsMile() {
        val o = OfferMath.parse("\$6.00 | 1.5 mi | Deliver by 7:40 PM | Accept", sevenPm)!!
        assertEquals(9.0, OfferMath.perHour(o)!!, 0.01)
        assertTrue(OfferMath.line(o, 2.0, 20.0).contains("about 9 an hour"))
    }

    @Test fun l2_t5_staleDeliverByIsUnknown() {
        assertNull(OfferMath.minutes("Deliver by 5:10 PM", sevenPm))   // two hours ago
        assertNull(OfferMath.minutes("Deliver by 6:58 PM", sevenPm))   // just passed
        assertNull(OfferMath.minutes("Deliver by 7:01 PM", sevenPm))   // 1 minute: would be a crazy hourly rate
        assertNull(OfferMath.minutes("Deliver by 11:30 PM", sevenPm))  // 270 min
        assertNull(OfferMath.minutes("2 min", sevenPm))
        assertNull(OfferMath.minutes("121 min", sevenPm))
    }

    @Test fun l2_t7_everyVerdictUnder20Words() {
        val cards = listOf(
            "\$8.50 | 2.1 mi | 18 min | Accept",
            "\$12.75 | 14.3 mi | Deliver by 7:45 PM | Accept",
            "\$7.00 | 3.5 mi | Accept",
            "\$3.50 | 9.9 mi | 118 min | Accept",
            "\$199.99 | 0.5 mi | 3 min | Accept",
            "\$5.25 | 2.9 mi | 15 min | Accept")
        for (c in cards) {
            val l = OfferMath.line(OfferMath.parse(c, sevenPm)!!, 2.0, 20.0)
            assertTrue("$c -> $l", words(l) < 20)
        }
    }

    @Test fun l2_verdictTiers() {
        // ok: 2.10/mi, ~21/hr
        assertEquals(OfferMath.OK, OfferMath.overall(OfferMath.Offer(7.0, 3.3, 20), 2.0, 20.0))
        // borderline: per mile fine, per hour 18 (0.9x)
        assertEquals(OfferMath.BORDERLINE, OfferMath.overall(OfferMath.Offer(9.0, 2.0, 30), 2.0, 20.0))
        // a different hourly minimum (he said "judge offers at 22 an hour")
        assertEquals(OfferMath.BORDERLINE, OfferMath.overall(OfferMath.Offer(10.5, 2.0, 30), 2.0, 22.0))
        assertEquals(OfferMath.OK, OfferMath.overall(OfferMath.Offer(10.5, 2.0, 30), 2.0, 20.0))
    }

    @Test fun l2_t8_onlySpeaks() {
        // OfferMath has no Android access at all; the line is plain text with no action words
        val l = OfferMath.line(OfferMath.Offer(8.5, 2.1, 18), 2.0, 20.0)
        assertFalse(l.contains("Accepted") || l.contains("Declined"))
    }

    /** Android 13+ formats times with a narrow no-break space (U+202F) before AM/PM, and some apps use U+00A0. */
    @Test fun l2_realWorld_nonBreakingSpaces() {
        assertEquals(40, OfferMath.minutes("Deliver by 7:40 PM", sevenPm))
        assertEquals(40, OfferMath.minutes("Deliver by 7:40 PM", sevenPm))
        val o = OfferMath.parse("\$6.00 | 1.5 mi | Deliver by 7:40 PM | Accept", sevenPm)
        assertEquals(40, o?.minutes)
    }

    // ---------- L1 ----------
    @Test fun l1_t2_fewOffers() {
        assertEquals("I've only seen 9 offers so far; give me a few more dashes.",
            OfferMath.bestHours(List(9) { Seen(18, 7.0, 3.0, 20) }))
    }

    @Test fun l1_t2_oneOfferGrammar() {
        val r = OfferMath.bestHours(listOf(Seen(18, 7.0, 3.0, 20)))
        assertFalse(r, r.contains("1 offers"))
    }

    /**
     * His morning shift is 7 to 11. With the 7 o'clock hour a bit better than 8 (normal, real data),
     * the 7–9 AM band averages ~2.70/mi and must be named. It must not name "6 AM to 8 AM": he never dashes at 6.
     */
    @Test fun l1_t3_realisticMorningNamesSevenToNine() {
        val morning = List(8) { Seen(7, 8.4, 3.0, 20) } + List(8) { Seen(8, 7.8, 3.0, 20) }
        val evening = List(10) { Seen(17 + it % 2, 4.8, 3.0, 25) }
        val r = OfferMath.bestHours(morning + evening)
        assertTrue(r, r.startsWith("Your best stretch is 7 AM to 9 AM"))
        assertTrue(r, r.contains("16 offers"))
    }

    @Test fun l1_t3_weakestMustBeHoursHeWorked() {
        val morning = List(12) { Seen(7 + it % 2, 8.4, 3.0, 20) }
        val evening = List(8) { Seen(17, 4.5, 3.0, 25) } + List(8) { Seen(18, 4.8, 3.0, 25) }
        val r = OfferMath.bestHours(morning + evening)
        assertFalse(r, r.contains("4 PM"))
    }

    @Test fun l1_t4_luckyOfferNeverWins() {
        val lucky = List(3) { Seen(13, 20.0, 1.0, 10) }   // 3 lucky ones still under 4
        val normal = List(10) { Seen(18 + it % 2, 6.0, 3.0, 20) }
        val r = OfferMath.bestHours(lucky + normal)
        assertFalse(r, r.contains("PM to 3 PM") || r.contains("12 PM to 2 PM"))
    }

    @Test fun l1_t5_under40WordsWorstCase() {
        val s = List(1490) { Seen(7 + it % 2, 18.43, 7.0, 37) } + List(10) { Seen(22 + it % 2, 4.33, 3.1, 40) }
        val r = OfferMath.bestHours(s)
        assertTrue(r, words(r) < 40)
        assertFalse(r, r.contains("\n") || r.contains("•"))
    }
}
