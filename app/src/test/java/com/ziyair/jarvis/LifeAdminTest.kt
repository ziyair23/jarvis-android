package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bills and Appointments: they keep the right texts, never codes or chit-chat, and track checkups on the usual schedule. */
class LifeAdminTest {
    @Test fun keepsBillsAndAppointments() {
        for (t in listOf(
            "T-Mobile: Your bill of \$85.00 is due Nov 14. Pay at t-mo.com",
            "Progressive: your auto insurance payment of \$142.30 will be charged on 11/20.",
            "Netflix: Your subscription renews tomorrow for \$15.49",
            "Reminder: your appointment with Dr. Lee is confirmed for Nov 20 at 2:30 PM.",
            "Spokane Pediatrics: Ziyair's well child visit is scheduled for Dec 3 at 10:15 AM",
            "Your rent payment of \$1,250 is due on the 1st. Balance due \$1,250.")) assertTrue(t, LifeWatch.relevant(t))
    }

    @Test fun skipsCodesAndChat() {
        for (t in listOf(
            "Your verification code is 482913. It expires in 10 minutes.",
            "Chase: Use code 112233 to sign in. Payment security alert.",
            "lol are you coming tonight",
            "Huge sale this weekend only! 40% off everything",
            "Your bill is ready to view")) assertFalse(t, LifeWatch.relevant(t))
    }

    @Test fun businessSendersOnly() {
        assertTrue(LifeWatch.businessSender("72345"))
        assertTrue(LifeWatch.businessSender("T-Mobile"))
        assertFalse("a person's number", LifeWatch.businessSender("+15095551234"))
    }

    @Test fun billNamesMatchLoosely() {
        assertTrue(Bills.same("T-Mobile", "tmobile"))
        assertTrue(Bills.same("Car insurance", "Car Insurance"))
        assertFalse(Bills.same("Phone", "Rent"))
    }

    @Test fun usualCheckups() {
        val day = 86_400_000L
        val born = 1_000L * day
        assertEquals("two-month", Appts.nextCheckup(born, born + 40 * day)?.second)
        assertEquals(born + 61 * day, Appts.nextCheckup(born, born + 40 * day)?.first)
        assertEquals("one-month", Appts.nextCheckup(born, born + 10 * day)?.second)
        assertNull(Appts.nextCheckup(born, born + 1000 * day))
    }

    @Test fun neverPaysOrBooks() {
        val money = setOf("send_text", "reply_message", "call", "phone_task", "answer_call")
        assertTrue((Agents.BILLS.tools intersect money).isEmpty())
        assertTrue((Agents.APPTS.tools intersect money).isEmpty())
    }

    @Test fun onTheDashboardAndInTheBrain() {
        val html = File("src/main/assets/dash.html").readText()
        assertTrue(html.contains("id=\"life-list\""))
        assertTrue(html.contains("bills:[") && html.contains("appts:["))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.READ_SMS"))
    }
}
