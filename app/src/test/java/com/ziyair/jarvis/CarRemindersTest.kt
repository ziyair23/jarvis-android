package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRemindersTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min

    private fun say(l: MutableList<CarReminders.Item>, s: String, now: Long = t0, inCar: Boolean = false, carSet: Boolean = true) =
        CarReminders.parse(s)?.let { CarReminders.handle(l, it, carSet, inCar, now) }

    private fun add(s: String): CarReminders.Cmd.Add = CarReminders.parse(s) as CarReminders.Cmd.Add

    @Test fun getInPhrasings() {
        listOf("Jarvis, remind me in the car to get gas.", "remind me when I get in the car to get gas",
            "When I get in the car, remind me to get gas, sir.", "remind me to get gas when I'm in the car",
            "next time I'm in the car remind me to get gas", "remind me once I get into my car to get gas",
            "Could you remind me in my car about get gas please")
            .forEach { val c = add(it); assertEquals(it, "get gas", c.text); assertEquals(it, false, c.out) }
    }

    @Test fun getOutPhrasings() {
        listOf("remind me when I park to grab the bag", "remind me to grab the bag when I get out of the car",
            "when I leave the car remind me to grab the bag", "remind me to grab the bag once I park")
            .forEach { val c = add(it); assertEquals(it, "grab the bag", c.text); assertEquals(it, true, c.out) }
    }

    @Test fun notCarReminders() {
        listOf("remind me to put gas in the car", "remind me in the car", "remind me when I get home to call Jess",
            "remind me in an hour to call Jess", "what's in the car", "remind me to clean the car tomorrow", "say that again")
            .forEach { assertNull(it, CarReminders.parse(it)) }
    }

    @Test fun repliesAndArrival() {
        val l = mutableListOf<CarReminders.Item>()
        assertEquals("I'll remind you in the car, sir.", say(l, "remind me in the car to call my mom"))
        assertEquals("I'll remind you next time you're in the car, sir.", say(l, "remind me in the car to get gas", t0 + min, inCar = true))
        assertEquals("I'll remind you when you park, sir.", say(l, "remind me when I park to grab the bag", t0 + 2 * min))
        val due = CarReminders.take(l, false, t0 + 3 * min)
        assertEquals(" Reminders: call your mom, and get gas.", CarReminders.line(due))
        assertEquals("", CarReminders.line(CarReminders.take(l, false, t0 + 4 * min)))   // said once only
        assertEquals(" Reminder: grab the bag.", CarReminders.line(CarReminders.take(l, true, t0 + 5 * min)))
        assertTrue(l.isEmpty())
    }

    @Test fun noCarSetNothingSaved() {
        val l = mutableListOf<CarReminders.Item>()
        assertEquals("Save your car's Bluetooth name in Jarvis first, sir.", say(l, "remind me in the car to get gas", carSet = false))
        assertTrue(l.isEmpty())
    }

    @Test fun readAndClear() {
        val l = mutableListOf<CarReminders.Item>()
        assertEquals("No car reminders, sir.", say(l, "what are my car reminders"))
        say(l, "remind me in the car to get gas"); say(l, "remind me when I park to grab my bag", t0 + min)
        assertEquals("In the car: get gas. When you park: grab your bag.", say(l, "any car reminders", t0 + 2 * min))
        assertEquals("Cleared 2 car reminders, sir.", say(l, "clear my car reminders, sir", t0 + 3 * min))
        assertEquals("No car reminders, sir.", say(l, "read my car reminders", t0 + 4 * min))
    }

    @Test fun limitsDuplicatesAndExpiry() {
        val l = mutableListOf<CarReminders.Item>()
        (1..6).forEach { CarReminders.add(l, "thing $it", false, t0 + it * min) }
        CarReminders.add(l, "thing 6", false, t0 + 7 * min)   // same again: not doubled
        assertEquals(listOf("thing 2", "thing 3", "thing 4", "thing 5", "thing 6"), CarReminders.take(l, false, t0 + 8 * min))
        CarReminders.add(l, "old one", false, t0)
        assertEquals(emptyList<String>(), CarReminders.take(l, false, t0 + 8 * day))
    }

    @Test fun storageRoundTrip() {
        val l = mutableListOf<CarReminders.Item>()
        CarReminders.add(l, "pay Jess 20\nback", false, t0)
        CarReminders.add(l, "grab the bag", true, t0 + min)
        val back = CarReminders.load(CarReminders.dump(l) + "\ngarbage line")
        assertEquals(2, back.size)
        assertEquals("pay Jess 20 back", back[0].text); assertEquals(false, back[0].out)
        assertEquals("grab the bag", back[1].text); assertEquals(true, back[1].out)
        assertTrue(CarReminders.load(null).isEmpty())
    }

    @Test fun longReminderCutForTheEar() {
        val words = (1..20).joinToString(" ") { "w$it" }
        assertEquals(" Reminder: ${(1..15).joinToString(" ") { "w$it" }}.", CarReminders.line(listOf(words)))
    }

    @Test fun cutOffPhraseSavesNothing() {
        assertNull(CarReminders.parse("remind me when I get in the car to"))
        assertNull(CarReminders.parse("remind me in the car about"))
    }

    @Test fun carPlusATimeGoesToClaude() {
        listOf("remind me in the car in an hour to call mom", "remind me in the car tomorrow to get gas",
            "remind me in the car in 20 minutes to call mom", "remind me in the car at 5 to get gas", "remind me in the car tonight to get gas",
            "remind me when I park this evening to grab the bag")
            .forEach { assertNull(it, CarReminders.parse(it)) }
    }

    @Test fun parkAtHomeKeepsJustTheTask() {
        val c = add("remind me when I park at home to grab the bag")
        assertEquals("grab the bag", c.text); assertEquals(true, c.out)
    }
}
