package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent tester, L26, against lab/specs/L26.md only. */
class CarRemindersIndepTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min
    private fun add(s: String) = CarReminders.parse(s) as CarReminders.Cmd.Add

    @Test fun at1() {
        for (s in listOf("Jarvis, remind me in the car to get gas.", "When I get in the car, remind me to get gas.", "Remind me to get gas when I'm in the car.",
            "next time I'm in the car remind me to get gas", "remind me once I get into my car to get gas", "Could you please remind me in the car to get gas, sir?")) {
            val c = add(s); assertEquals(s, "get gas", c.text); assertTrue(s, !c.out)
        }
    }

    @Test fun at2() {
        for (s in listOf("remind me when I park to grab the bag", "remind me to grab the bag when I get out of the car", "remind me to grab the bag when I leave the car", "once I park remind me to grab the bag")) {
            val c = add(s); assertEquals(s, "grab the bag", c.text); assertTrue(s, c.out)
        }
    }

    @Test fun at3() {
        for (s in listOf("remind me to put gas in the car", "remind me in the car", "remind me when I get home to grab wipes", "remind me in an hour to call Abbey", "remind me to clean the car tomorrow"))
            assertNull(s, CarReminders.parse(s))
    }

    @Test fun at4and5() {
        val l = mutableListOf<CarReminders.Item>()
        assertEquals("I'll remind you in the car, sir.", CarReminders.handle(l, add("remind me in the car to call my mom"), true, false, t0))
        assertEquals("I'll remind you next time you're in the car, sir.", CarReminders.handle(l, add("remind me in the car to get gas"), true, true, t0 + min))
        assertEquals("I'll remind you when you park, sir.", CarReminders.handle(l, add("remind me when I park to grab the bag"), true, true, t0 + 2 * min))
        val back = CarReminders.load(CarReminders.dump(l))
        assertEquals(" Reminders: call your mom, and get gas.", CarReminders.line(CarReminders.take(back, false, t0 + 3 * min)))
        assertEquals("", CarReminders.line(CarReminders.take(back, false, t0 + 4 * min)))
        assertEquals(" Reminder: grab the bag.", CarReminders.line(CarReminders.take(back, true, t0 + 5 * min)))
        assertEquals("", CarReminders.line(CarReminders.take(back, true, t0 + 6 * min)))
    }

    @Test fun at6() {
        val l = mutableListOf<CarReminders.Item>()
        assertEquals("Save your car's Bluetooth name in Jarvis first, sir.", CarReminders.handle(l, add("remind me in the car to get gas"), false, false, t0))
        assertTrue(l.isEmpty())
    }

    @Test fun at7() {
        val l = mutableListOf<CarReminders.Item>()
        assertEquals("No car reminders, sir.", CarReminders.handle(l, CarReminders.parse("what are my car reminders")!!, true, false, t0))
        CarReminders.handle(l, add("remind me in the car to get gas"), true, false, t0)
        CarReminders.handle(l, add("remind me when I park to grab my bag"), true, false, t0 + min)
        assertEquals("In the car: get gas. When you park: grab your bag.", CarReminders.handle(l, CarReminders.parse("What are my car reminders?")!!, true, false, t0 + 2 * min))
        assertEquals("Cleared 2 car reminders, sir.", CarReminders.handle(l, CarReminders.parse("clear my car reminders")!!, true, false, t0 + 3 * min))
    }

    @Test fun at8() {
        val l = mutableListOf<CarReminders.Item>()
        for (i in 1..6) CarReminders.add(l, "task $i", false, t0 + i * min)
        CarReminders.add(l, "Task 6", false, t0 + 7 * min)
        assertEquals(listOf("task 2", "task 3", "task 4", "task 5", "Task 6"), CarReminders.take(CarReminders.load(CarReminders.dump(l)), false, t0 + 8 * min))
        val old = mutableListOf(CarReminders.Item(false, t0, "old one"), CarReminders.Item(true, t0 + 7 * day, "new one"))
        assertEquals("", CarReminders.line(CarReminders.take(old, false, t0 + 8 * day)))
        val dumped = CarReminders.dump(listOf(CarReminders.Item(false, t0, "a\nb\rc"))) + "\nX\u0001notanumber\u0001x\ngarbage"
        assertEquals(listOf("a b c"), CarReminders.load(dumped).map { it.text })
    }

    @Test fun at9() {
        val l = mutableListOf(CarReminders.Item(false, t0, (1..20).joinToString(" ") { "w$it" }))
        assertEquals(" Reminder: " + (1..15).joinToString(" ") { "w$it" } + ".", CarReminders.line(CarReminders.take(l, false, t0)))
    }
}
