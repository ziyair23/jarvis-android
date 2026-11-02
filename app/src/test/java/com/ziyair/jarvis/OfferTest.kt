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
