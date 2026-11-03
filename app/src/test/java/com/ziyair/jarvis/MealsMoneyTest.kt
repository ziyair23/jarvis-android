package com.ziyair.jarvis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class MealsMoneyTest {
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun readsPurchaseNotifications() {
        val card = Spending.parse("Chase. You made a \$23.45 transaction with WINCO FOODS #12 on your credit card ending in 1234.")
        assertNotNull(card); assertEquals(23.45, card!!.amount, 0.001); assertTrue(card.spend)
        assertEquals("groceries", Spending.category(card.merchant))
        val sub = Spending.parse("Purchase of \$12.99 at Netflix.com")
        assertEquals("subscriptions", Spending.category(sub!!.merchant))
        val sent = Spending.parse("Cash App. You sent \$20 to Jasmine")
        assertTrue(sent!!.spend); assertEquals(20.0, sent.amount, 0.001)
        val got = Spending.parse("Cash App. Mike sent you \$50.00")
        assertFalse("money in isn't spending", got!!.spend)
        assertEquals(1234.5, Spending.parse("You spent \$1,234.50 at Best Buy")!!.amount, 0.001)
    }

    @Test fun ignoresCodesAlertsAndReminders() {
        assertNull(Spending.parse("Your verification code is 123456"))
        assertNull(Spending.parse("Your Chase payment of \$50.00 is due on Nov 5"))
        assertNull(Spending.parse("Your available balance is \$312.40"))
        assertNull(Spending.parse("Mom: can you grab milk on the way home"))
        assertNull(Spending.parse("Your card was declined for \$40.00 at Shell"))
    }

    @Test fun sortsPlaces() {
        assertEquals("gas", Spending.category("SHELL OIL 5741"))
        assertEquals("food", Spending.category("Dutch Bros Coffee"))
        assertEquals("bills", Spending.category("T-Mobile"))
        assertEquals("transfers", Spending.category("Venmo"))
        assertEquals("other", Spending.category("Jasmine"))
    }

    @Test fun summarisesAndSpotsRepeats() {
        val t0 = at(2026, 10, 1)
        val txs = listOf(
            JSONObject().put("at", t0).put("a", 11.99).put("m", "Spotify").put("c", "subscriptions"),
            JSONObject().put("at", t0 + 30 * 86_400_000L).put("a", 11.99).put("m", "Spotify").put("c", "subscriptions"),
            JSONObject().put("at", t0 + 2 * 86_400_000L).put("a", 40.0).put("m", "Shell").put("c", "gas"),
            JSONObject().put("at", t0 + 20 * 86_400_000L).put("a", 35.0).put("m", "Shell").put("c", "gas"),
            JSONObject().put("at", t0 + 3 * 86_400_000L).put("a", 100.0).put("m", "Mike").put("c", "income"))
        val s = Spending.summary(txs, t0, t0 + 7 * 86_400_000L)
        assertTrue(s, s.startsWith("Spent \$51.99")); assertTrue(s, s.contains("gas \$40.00")); assertTrue(s, s.contains("Money in: \$100"))
        val r = Spending.repeats(txs)
        assertTrue(r.toString(), r.any { it.startsWith("Spotify") }); assertFalse(r.toString(), r.any { it.startsWith("Shell") })
        assertEquals("No purchases picked up.", Spending.summary(txs, t0 + 60 * 86_400_000L, t0 + 70 * 86_400_000L))
    }

    @Test fun mealWeeksStartMonday() {
        assertEquals("2026-11-02", Meals.weekOf(at(2026, 11, 4)))
        assertEquals("2026-11-02", Meals.weekOf(at(2026, 11, 2)))
        assertEquals("2026-11-02", Meals.weekOf(at(2026, 11, 8)))
    }

    @Test fun findsCheapestStore() {
        fun item(store: String) = JSONObject().put("name", "x").put("last", JSONObject().put("store", store).put("price", 5.0))
        val a = JSONArray().put(item("WinCo Foods")).put(item("Walmart")).put(item("WinCo Foods")).put(JSONObject().put("name", "y"))
        val s = Meals.cheapestStore(a)!!
        assertTrue(s, s.startsWith("WinCo Foods had the lowest price on 2 of the 3"))
        assertNull(Meals.cheapestStore(JSONArray()))
    }

    @Test fun newAgentsCanOnlyLookAndPlan() {
        val meals = Agents.ALL.first { it.id == "meals" }
        for (t in listOf("send_text", "reply_message", "call", "answer_call")) {
            assertFalse("meals can't $t", meals.tools.contains(t)); assertFalse("money can't $t", Agents.MONEY.tools.contains(t))
        }
        assertTrue(Agents.MONEY.tools.containsAll(listOf("money_week", "subscriptions_list")))
        val html = File("src/main/assets/dash.html").readText()
        assertTrue(html.contains("meal-list")); assertTrue(html.contains("sp-cats"))
    }
}
