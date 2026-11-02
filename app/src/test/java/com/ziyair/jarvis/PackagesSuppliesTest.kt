package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackagesSuppliesTest {
    @Test fun readsDeliveries() {
        val d = Packages.parse("Amazon Shopping", "Delivered: Pampers Swaddlers Size 1", "Your package was delivered to the front door.")
        assertEquals("delivered", d?.status); assertTrue(d!!.item.contains("Pampers"))
        val o = Packages.parse("Amazon Shopping", "Out for delivery", "Huggies Natural Care Wipes 12 pack")
        assertEquals("out", o?.status); assertTrue(o!!.item.contains("Huggies"))
        val c = Packages.parse("UPS", "UPS Update", "Your package is arriving tomorrow by 7:00 PM. Tracking 1Z999AA10123456784")
        assertEquals("coming", c?.status); assertEquals("tomorrow", c?.eta); assertEquals("1Z999AA10123456784", c?.tracking)
        assertEquals("shipped", Packages.parse("Target", "Your order has shipped", "Baby bottles 4 pack is on its way")?.status)
    }

    @Test fun ignoresHisWorkAndOtherStuff() {
        assertNull(Packages.parse("Dasher", "Order delivered", "Great job! You delivered the order to Sam."))
        assertNull(Packages.parse("DoorDash - Dasher", "New order", "Pick up from Chipotle"))
        assertNull(Packages.parse("Messages", "Mom", "did you get the package i sent"))
        assertNull(Packages.parse("Amazon Shopping", "Deal of the day", "Save 30% on headphones"))
        assertFalse("'will be delivered' isn't delivered", Packages.parse("FedEx", "FedEx", "Your package will be delivered Thursday")?.status == "delivered")
    }

    @Test fun watchesBabySuppliesAutomatically() {
        for (t in listOf("diapers", "wipes", "Similac formula", "pacifiers", "Pampers size 1")) assertTrue(t, Supplies.worthWatching(t))
        for (t in listOf("milk", "eggs", "gas", "chips")) assertFalse(t, Supplies.worthWatching(t))
    }

    @Test fun onlyRealDeals() {
        assertTrue(Supplies.isDeal(0.16, 0.18))
        assertFalse(Supplies.isDeal(0.175, 0.18))
        assertFalse("no history yet", Supplies.isDeal(0.10, null))
    }

    @Test fun neverBuysOrSends() {
        val risky = setOf("send_text", "reply_message", "call", "phone_task", "answer_call", "open_app")
        assertTrue((Agents.PACKAGES.tools intersect risky).isEmpty())
        assertTrue((Agents.SUPPLIES.tools intersect risky).isEmpty())
        assertTrue(File("src/main/assets/dash.html").readText().contains("id=\"pk-list\""))
    }
}
