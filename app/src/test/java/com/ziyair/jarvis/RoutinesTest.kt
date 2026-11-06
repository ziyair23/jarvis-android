package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/** Shifts, leave-on-time, supplies counting down, and weekly habits: the pure parts. */
class RoutinesTest {
    private val H = 3_600_000L; private val D = 24 * H

    @Test fun shiftMath() {
        assertEquals("45 min", Shift.hm(45 * 60_000L))
        assertEquals("4h 05m", Shift.hm(4 * H + 5 * 60_000L))
        assertEquals(30.0, Shift.perHour(120.0, 4 * H)!!, 0.01)
        assertNull(Shift.perHour(50.0, 10 * 60_000L))   // too short to mean anything
        assertNull(Shift.perHour(0.0, 4 * H))
    }

    @Test fun leaveOnTime() {
        val begin = 10 * H
        assertEquals(begin - 20 * 60_000L - 10 * 60_000L, LeaveAlerts.leaveAt(begin, 20 * 60))
        assertTrue(LeaveAlerts.drivable("Spokane Pediatrics, 123 N Division St"))
        assertFalse(LeaveAlerts.drivable("https://zoom.us/j/123"))
        assertFalse(LeaveAlerts.drivable("Google Meet"))
        assertFalse(LeaveAlerts.drivable("(509) 555-1234"))
        assertFalse(LeaveAlerts.drivable(""))
    }

    @Test fun suppliesCountDown() {
        val now = 10 * D
        assertNull(Stock.diapersPerDay(listOf(now - H, now - 2 * H), now))            // not enough logged yet
        val changes = (1..18).map { now - it * 4 * H }.filter { now - it < 3 * D }       // one every 4 hours = 6 a day
        assertEquals(6.0, Stock.diapersPerDay(changes, now)!!, 0.6)
        assertEquals(5.0, Stock.daysLeft(30.0, 6.0)!!, 0.01)
        assertNull(Stock.daysLeft(30.0, 0.0))
    }

    @Test fun weeklyHabits() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 6, 12, 0) }   // a Friday
        val now = cal.timeInMillis
        fun thursday(weeksAgo: Int) = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1 - 7 * weeksAgo) }.timeInMillis
        assertEquals(Calendar.THURSDAY, Patterns.weekly(listOf(thursday(0), thursday(1), thursday(3)), now))
        assertNull(Patterns.weekly(listOf(thursday(0), thursday(2)), now))                  // twice isn't a habit
        assertEquals("Wednesday", Patterns.dayName(Patterns.dayBefore(Calendar.THURSDAY)))
        assertEquals(Calendar.SATURDAY, Patterns.dayBefore(Calendar.SUNDAY))
    }

    @Test fun wiredIn() {
        val src = "src/main/java/com/ziyair/jarvis/"
        val actions = File(src + "Actions.kt").readText()
        for (t in listOf("start_shift", "end_shift", "shift_status", "stock_set", "stock_status", "evening_rundown", "habits_list")) {
            assertTrue("$t has a schema", actions.contains("tool(\"$t\""))
            assertTrue("$t has a handler", actions.contains("\"$t\" ->"))
            assertTrue("$t is one of Jarvis's own skills", t in Agents.CORE)
        }
        assertTrue(File(src + "Messages.kt").readText().contains("Shift.dasherOff(this)"))
        assertTrue(File(src + "JarvisService.kt").readText().contains("Routines.tick(this)"))
        assertTrue(File(src + "Iron.kt").readText().contains("Shift.start(context)"))
    }
}
