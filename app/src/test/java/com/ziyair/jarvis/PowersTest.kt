package com.ziyair.jarvis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PowersTest {
    @Test fun payoutsFillTheBuckets() {
        val glasses = JSONObject().put("name", "Meta glasses").put("target", 380.0).put("saved", 0.0).put("share", 0.5)
        val taxes = JSONObject().put("name", "Taxes").put("target", 0.0).put("saved", 0.0).put("share", 0.15)
        val s = Goals.split(100.0, listOf(glasses, taxes))
        assertEquals(50.0, s["Meta glasses"]!!, 0.001); assertEquals(7.5, s["Taxes"]!!, 0.001)   // 15% of what's left
        glasses.put("saved", 370.0)
        assertEquals(10.0, Goals.split(100.0, listOf(glasses, taxes))["Meta glasses"]!!, 0.001)    // only what fits
        glasses.put("saved", 380.0)
        assertFalse(Goals.split(100.0, listOf(glasses, taxes)).containsKey("Meta glasses"))       // full: skipped
    }

    @Test fun bestDaysAndCar() {
        val w = Radar.byWeekday(listOf("2026-10-31" to 131.0, "2026-10-24" to 125.0, "2026-10-29" to 80.0, "2026-10-22" to 90.0, "2026-10-30" to 100.0))
        assertEquals(128.0, w[java.util.Calendar.SATURDAY]!!, 0.01)
        assertFalse(w.containsKey(java.util.Calendar.FRIDAY))   // one Friday isn't enough
        assertEquals(1500.0, Miles.toOil(53500.0, 50000.0), 0.01)
        assertTrue(BillFighter.wentUp(50.0, 65.0)); assertFalse(BillFighter.wentUp(50.0, 52.0)); assertFalse(BillFighter.wentUp(null, 65.0))
    }

    @Test fun wiredIn() {
        val src = "src/main/java/com/ziyair/jarvis/"
        val actions = File(src + "Actions.kt").readText()
        for (t in listOf("council", "goals_status", "goal_add", "goal_spend", "best_days", "log_miles", "car_status", "miles_status", "learn", "next_lesson", "practice", "practice_stop", "idea")) {
            assertTrue("$t schema", actions.contains("tool(\"$t\"")); assertTrue("$t handler", actions.contains("\"$t\" ->")); assertTrue("$t core", t in Agents.CORE)
        }
        assertTrue(actions.contains("Goals.allocate(context"))
        assertTrue(File(src + "LifeAdmin.kt").readText().contains("BillFighter.check("))
        assertTrue(File(src + "Brain.kt").readText().contains("Practice.promptBlock(context)"))
    }
}
