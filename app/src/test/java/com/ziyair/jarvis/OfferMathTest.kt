package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferMathTest {
    private val sevenPm = 19 * 60

    @Test fun greatOfferWithMinutes() {
        val o = OfferMath.parse("\$8.50 | 2.1 mi | 18 min | Accept", sevenPm)!!
        assertEquals(8.5, o.pay, 0.001); assertEquals(2.1, o.miles, 0.001); assertEquals(18, o.minutes)
        assertEquals(OfferMath.GREAT, OfferMath.overall(o, 2.0, 20.0))
        assertTrue(OfferMath.line(o, 2.0, 20.0).endsWith("Good one, sir, take it."))
    }

    @Test fun shortButSlowIsAPass() {
        val o = OfferMath.parse("\$6.00 | 1.5 mi | Deliver by 7:40 PM | Accept", sevenPm)!!
        assertEquals(40, o.minutes)
        assertEquals(OfferMath.POOR, OfferMath.overall(o, 2.0, 20.0))
        assertTrue(OfferMath.line(o, 2.0, 20.0).endsWith("I'd pass."))
    }

    @Test fun noTimeFallsBackToPerMile() {
        val o = OfferMath.parse("\$7.00 | 3.5 mi | Accept", sevenPm)!!
        assertNull(o.minutes)
        assertEquals(OfferMath.OK, OfferMath.overall(o, 2.0, 20.0))
        val line = OfferMath.line(o, 2.0, 20.0)
        assertTrue(line.endsWith("Worth taking.")); assertFalse(line.contains("an hour"))
    }

    @Test fun deliverByWrapsPastMidnight() {
        assertEquals(30, OfferMath.minutes("Deliver by 12:20 AM", 23 * 60 + 50))
    }

    @Test fun oddTimesAreIgnored() {
        assertNull(OfferMath.minutes("Deliver by 3:00 PM", sevenPm))   // four hours ago
        assertNull(OfferMath.minutes("240 min", sevenPm))
        assertNull(OfferMath.minutes("1 min", sevenPm))
    }

    @Test fun noAcceptNoCallout() {
        assertNull(OfferMath.parse("\$8.50 | 2.1 mi | 18 min", sevenPm))
    }

    @Test fun lineIsShort() {
        val o = OfferMath.parse("\$12.75 | 4.3 mi | Deliver by 7:35 PM | Accept", sevenPm)!!
        assertTrue(OfferMath.line(o, 2.0, 20.0).split(" ").size < 20)
    }

    @Test fun diaryNeedsTenOffers() {
        val few = List(6) { OfferMath.Seen(8, 8.0, 3.0, 20) }
        assertEquals("I've only seen 6 offers so far; give me a few more dashes.", OfferMath.bestHours(few))
    }

    @Test fun diaryNamesBestWindow() {
        val morning = List(6) { OfferMath.Seen(7 + it % 2, 8.4, 3.0, 20) }    // 2.80 a mile
        val evening = List(6) { OfferMath.Seen(17 + it % 2, 4.8, 3.0, 25) }   // 1.60 a mile
        val r = OfferMath.bestHours(morning + evening)
        assertTrue(r, r.startsWith("Your best stretch is 7 AM to 9 AM, about 2.80 dollars a mile"))
        assertTrue(r, r.contains("from 6 offers"))
        assertTrue(r, r.contains("Weakest is 5 PM to 7 PM"))
        assertTrue(r.split(" ").size < 40)
    }

    @Test fun oneLuckyOfferDoesNotWin() {
        val lucky = listOf(OfferMath.Seen(13, 20.0, 1.0, 10))
        val normal = List(10) { OfferMath.Seen(18, 6.0, 3.0, 20) }
        val r = OfferMath.bestHours(lucky + normal)
        assertNotNull(r); assertFalse(r, r.contains("1 PM"))
    }

    @Test fun clockWords() {
        assertEquals("12 AM", OfferMath.clock(0)); assertEquals("12 PM", OfferMath.clock(12)); assertEquals("1 AM", OfferMath.clock(25))
    }
}
