package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** DoorDash offers: read pay and miles off the screen, give a straight verdict, never tap anything. */
class OfferTest {
    @Test fun readsTypicalOffers() {
        assertEquals(9.5 to 3.2, OfferJudge.read("\$9.50 | Guaranteed (incl. tips) | 3.2 mi | Deliver by 6:45 PM | Accept | Decline"))
        assertEquals(12.0 to 7.4, OfferJudge.read("\$12.00 | Total | 7.4 miles | Chipotle | Accept"))
        assertEquals(6.75 to 2.0, OfferJudge.read("\$6.75 | 2 mi | 18 min | Accept"))
    }

    @Test fun smarterVerdicts() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 10, 6, 18, 20, 0) }
        assertEquals(25.0, OfferJudge.minutes("\$9.50 | 3.2 mi | Deliver by 6:45 PM | Accept", cal)!!, 0.5)
        assertEquals(18.0, OfferJudge.minutes("\$6.75 | 2 mi | 18 min", cal)!!, 0.01)
        assertTrue(OfferJudge.verdict2(3.0, 0.8, 2.0, 10.0, 25.0, 4.0).contains("minimum"))            // tiny payout: pass
        assertTrue(OfferJudge.verdict2(12.0, 4.0, 2.0, 15.0, 25.0, 4.0).contains("Quick money"))       // $48 an hour
        assertTrue(OfferJudge.verdict2(7.0, 3.0, 2.0, 45.0, 25.0, 4.0).contains("Too slow"))          // $9 an hour
        assertTrue(OfferJudge.verdict2(10.0, 3.0, 2.0, null, 25.0, 4.0).contains("take it"))          // no time shown: per mile
        val accepted = List(20) { 1.5 + it * 0.1 }
        assertEquals(2.0, OfferJudge.learnedFloor(accepted)!!, 0.001)
        org.junit.Assert.assertNull(OfferJudge.learnedFloor(accepted.take(10)))
    }

    @Test fun minutesArentMiles() { assertNull(OfferJudge.read("\$8.00 | 15 min | Accept")) }

    @Test fun verdicts() {
        assertTrue(OfferJudge.verdict(10.0, 3.0, 2.0).contains("take it"))
        assertTrue(OfferJudge.verdict(4.0, 2.0, 2.0).contains("Worth taking"))
        assertTrue(OfferJudge.verdict(3.5, 2.0, 2.0).contains("Borderline"))
        assertTrue(OfferJudge.verdict(5.0, 6.0, 2.0).contains("I'd pass"))
    }

    @Test fun onlyAdvisesNeverTaps() {
        val src = File("src/main/java/com/ziyair/jarvis/Moments.kt").readText()
        val judge = src.substringAfter("object OfferJudge").substringBefore("\n}\n")
        assertTrue("never performs a tap on the offer", !judge.contains("performAction") && !judge.contains("tap("))
        assertTrue("judge_offer" in Agents.CORE)
    }

    @Test fun lightWhileDashing() {
        val src = File("src/main/java/com/ziyair/jarvis/Control.kt").readText()
        assertTrue("reads the screen at most about once a second", src.contains("now - lastLook < 900"))
        assertTrue("only reads everything when an offer is showing", src.contains("findAccessibilityNodeInfosByText(\"Accept\")"))
    }

    @Test fun crashesAreRecorded() {
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:name=\".JarvisApp\""))
        assertTrue(File("src/main/java/com/ziyair/jarvis/JarvisApp.kt").readText().contains("getHistoricalProcessExitReasons"))
    }
}
