package com.ziyair.jarvis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class AdvancedTest {
    private val H = 3600_000L

    @Test fun dropsOnlySettingsHisAccountRefuses() {
        val adaptive = Claude.smart(JSONObject().put("model", "m"), "medium")
        assertEquals("effort_refused", Claude.refused(adaptive, "{\"error\":{\"message\":\"output_config.effort: unknown field\"}}"))
        assertEquals("adaptive_refused", Claude.refused(JSONObject().put("thinking", JSONObject().put("type", "adaptive")), "thinking.type: adaptive not supported"))
        assertEquals("thinking_param_refused", Claude.refused(JSONObject().put("thinking", JSONObject().put("type", "disabled")), "thinking: disabled is not allowed"))
        assertNull("a thinking-signature problem isn't a refused setting", Claude.refused(adaptive, "messages.1.content.0: invalid thinking signature"))
        assertNull(Claude.refused(adaptive, "max_tokens too large"))
    }

    @Test fun followUpsKnowWhenTheyreDue() {
        val now = 1_700_000_000_000L
        assertEquals(now + 24 * H, Loops.dueTime(JSONObject().put("in_hours", 24), now))
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-11-06 18:00")!!.time
        assertEquals(at, Loops.dueTime(JSONObject().put("date", "2026-11-06"), now))
        assertEquals(at - 8 * H, Loops.dueTime(JSONObject().put("date", "2026-11-06").put("time", "10:00"), now))
        assertNull(Loops.dueTime(JSONObject().put("date", "Friday"), now))
    }

    @Test fun repliesCloseTheRightFollowUp() {
        assertTrue(Loops.sameWho("Jesse", "Jesse Ramirez")); assertTrue(Loops.sameWho("jesse", "Jesse"))
        assertTrue(Loops.sameWho("Jesse Ramirez", "Jesse"))
        assertFalse(Loops.sameWho("Jesse", "Jessica")); assertFalse(Loops.sameWho("", "Jesse"))
        val o = JSONObject().put("what", "Jesse about Saturday's shift").put("who", "Jesse")
        assertTrue(Loops.matches(o, "the Saturday shift")); assertFalse(Loops.matches(o, "diapers"))
    }

    @Test fun bringsUpWhatSlippedOncePerDay() {
        val now = 1_700_000_000_000L
        val late = JSONObject().put("status", "open").put("due", now - H).put("nudged", 0L).put("kind", "reply").put("who", "Jesse").put("what", "Saturday's shift").put("then", "draft")
        val nudged = JSONObject(late.toString()).put("nudged", now - 2 * H)
        val early = JSONObject(late.toString()).put("due", now + H)
        val closed = JSONObject(late.toString()).put("status", "closed")
        assertEquals(listOf(late), Loops.overdue(listOf(late, nudged, early, closed), now))
        val line = Loops.nudgeLine(late)
        assertTrue(line, line.contains("Jesse still hasn't got back to you")); assertTrue(line.endsWith("?"))
        assertTrue(Loops.nudgeLine(JSONObject().put("kind", "delivery").put("what", "the diapers")).contains("still nothing on the diapers"))
    }

    @Test fun teamNotesFadeAfterAFewDays() {
        val now = 1_700_000_000_000L
        val a = JSONArray().put(JSONObject().put("at", now - 5 * 24 * H).put("from", "Bills").put("t", "old"))
            .put(JSONObject().put("at", now - H).put("from", "Money").put("t", "Payday is Friday"))
        val l = Team.recentNotes(a, now)
        assertEquals(1, l.size); assertTrue(l[0].endsWith("Money: Payday is Friday"))
    }

    @Test fun mindThinksAtTheRightTimesAndOnlyDoesSafeThings() {
        assertEquals("morning", Mind.slot(7)); assertEquals("afternoon", Mind.slot(13)); assertEquals("evening", Mind.slot(20))
        assertNull(Mind.slot(3)); assertNull(Mind.slot(11)); assertNull(Mind.slot(17))
        for (t in listOf("send_text", "reply_message", "call", "phone_task", "answer_call", "open_app", "draft_text", "forget", "memory_fix", "bill_paid"))
            assertFalse("the mind can't $t", t in Mind.SAFE)
        for (t in listOf("send_text", "reply_message", "call", "phone_task", "forget"))
            assertFalse("a background job can't $t", t in Jobs.DIRECT)
        assertFalse("Phone stays quick", "phone" in Agents.SMART)
        assertTrue(Agents.SMART.containsAll(listOf("planner", "money", "bills", "meals")))
        val now = 1_700_000_000_000L
        val s = JSONArray().put(JSONObject().put("status", "open").put("at", now - H).put("text", "a"))
            .put(JSONObject().put("status", "open").put("at", now - 72 * H).put("text", "old"))
            .put(JSONObject().put("status", "done").put("at", now).put("text", "b"))
        assertEquals(listOf("a"), Mind.openSuggestions(s, now).map { it.optString("text") })
    }

    @Test fun jarvisKnowsTheNewSkills() {
        assertTrue(Agents.CORE.containsAll(listOf("run_job", "follow_up_add", "follow_ups_list", "follow_up_done", "mind_recent")))
        assertFalse("call stays with the Phone agent", "call" in Agents.CORE)
        val actions = File("src/main/java/com/ziyair/jarvis/Actions.kt").readText()
        for (t in listOf("run_job", "follow_up_add", "follow_ups_list", "follow_up_done", "mind_recent")) {
            assertTrue("$t has a tool definition", actions.contains("tool(\"$t\""))
            assertTrue("$t has a handler", actions.contains("\"$t\" ->"))
        }
        val html = File("src/main/assets/dash.html").readText()
        assertTrue(html.contains("id=\"sh-mind\"")); assertTrue(html.contains("J.mindAct")); assertTrue(html.contains("mind-loops"))
        // the agents' request always carries its tools and messages (it used to lose them when thinking was left on)
        val agents = File("src/main/java/com/ziyair/jarvis/Agents.kt").readText()
        assertTrue(agents.contains(".put(\"system\", system).put(\"tools\", tools).put(\"messages\", msgs)"))
    }

    @Test fun lockScreenPlanShowsWhatsLeftOfToday() {
        val now = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 14); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        val items = listOf(DayPlan.Item(now + 3 * H, "Dash until 9"), DayPlan.Item(now - 3 * H, "Dash until 11"), DayPlan.Item(now + 30 * 60_000L, "Feed Ziyair"),
            DayPlan.Item(now + 30 * 60_000L, "feed ziyair"), DayPlan.Item(now + 3 * 24 * H, "Dentist"), DayPlan.Item(now + 4 * H, "Dinner: tacos"))
        assertEquals(listOf("Feed Ziyair", "Dash until 9", "Dinner: tacos"), DayPlan.pick(items, now).map { it.text })
        assertEquals("Cash App due Friday.", DayPlan.headline(JSONObject().put("thought", "Cash App due Friday. Also planned meals.")))
        assertEquals("Short one", DayPlan.headline(JSONObject().put("headline", "Short one").put("thought", "x")))
        assertEquals("Your day, at a glance.", DayPlan.headline(null))
    }

    @Test fun mindSplitsHeadlineAndOnlyRethinksOnRealChanges() {
        val (h, t) = Mind.split("Headline: Cash App due Friday; dinner planned\nNoticed the bill and planned dinners.")
        assertEquals("Cash App due Friday; dinner planned", h); assertEquals("Noticed the bill and planned dinners.", t)
        assertEquals("" to "Just a thought.", Mind.split("Just a thought."))
        assertEquals("Bold", Mind.split("**Headline:** Bold\nRest").first)
        val before = JSONObject().put("bills", "a".hashCode().toString()).put("shopping list", "milk".hashCode().toString())
        assertEquals(listOf<String>(), Mind.changed(before, mapOf("bills" to "a", "shopping list" to "milk")))
        assertEquals(listOf("shopping list"), Mind.changed(before, mapOf("bills" to "a", "shopping list" to "milk, eggs")))
        assertEquals("first look never triggers a think", listOf<String>(), Mind.changed(JSONObject(), mapOf("bills" to "a")))
    }

    @Test fun knowsWhichDaysHeDashes() {
        fun day(y: Int, m: Int, d: Int) = java.util.Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }
        val tue = day(2026, 11, 3)
        // dashed the last three Tuesdays, plus one Monday
        val h = mapOf("2026-10-27" to true, "2026-10-20" to true, "2026-10-13" to true, "2026-11-02" to true)
        assertEquals(DashDays.Today.LIKELY, DashDays.state(h, tue))
        assertEquals(DashDays.Today.NO, DashDays.state(h, day(2026, 11, 4)))
        assertEquals(DashDays.Today.OFF, DashDays.state(h + ("2026-11-03" to false), tue))
        assertEquals(DashDays.Today.ON, DashDays.state(h + ("2026-11-04" to true), day(2026, 11, 4)))
        assertFalse("two of four isn't a habit", DashDays.usual(mapOf("2026-10-27" to true, "2026-10-20" to true), tue))
        assertEquals("Tue", DashDays.usualDays(h, tue))
        assertTrue("dash_day is a quick skill", "dash_day" in Agents.CORE)
    }

    @Test fun scanReadsBusinessTextsOnly() {
        val rows = listOf(Triple("+15095550000", "are you coming sunday", 3L), Triple("+15095551234", "hey whats up", 4L),
            Triple("72975", "Your Walmart order of $84.12 has been delivered", 5L), Triple("72975", "Your Walmart order of $91.40 has been delivered", 6L),
            Triple("TMOBILE", "Your T-Mobile bill of $65 is due Nov 12", 2L), Triple("33663", "Your verification code is 482913", 7L))
        val out = Scan.businessTexts(rows)
        assertTrue(out.toString(), out.any { it.contains("T-Mobile bill") })
        assertEquals("near-identical order texts count once", 1, out.count { it.contains("Walmart order") })
        assertFalse(out.any { it.contains("code") }); assertFalse(out.any { it.contains("whats up") })
    }

    @Test fun burstsOfTextsBecomeOneLine() {
        assertEquals("Sir, 3 new messages: 2 from Mom and 1 from Jesse.", Inbox.bundle(listOf("Mom", "Jesse", "Mom")))
        assertEquals("Sir, 2 new messages: from Mom.", Inbox.bundle(listOf("Mom", "Mom")))
        assertTrue("scan is a quick skill", "scan_phone" in Agents.CORE)
    }

    @Test fun sentencesJoinWithoutDeadAir() {
        val rate = 24000
        // 0.4 s of silence, 1 s of voice, 0.6 s of silence: what the engine hands back for one sentence
        val s = FloatArray(rate * 2) { i -> if (i in (rate * 4 / 10) until (rate * 14 / 10)) 0.3f else 0f }
        val t = SpeechStream.trimTail(SpeechStream.trimHead(s, rate), rate)
        val seconds = t.size.toFloat() / rate
        assertTrue("kept the voice plus a short natural edge: $seconds s", seconds in 1.05f..1.15f)
        assertEquals("fades in", 0f, t[0], 0.0001f); assertEquals("fades out", 0f, t[t.size - 1], 0.05f)
    }

    @Test fun remindersSetOnThePhone() {
        val now = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 3, 17, 22) }   // Tue 5:22 PM
        fun at(t: String) = Fast.reminder(t, now)?.let { (ms, what) -> java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US).format(java.util.Date(ms)) + " " + what }
        assertEquals("Tue 19:00 call mom", at("remind me to call mom at 7"))
        assertEquals("Tue 17:30 grab diapers", at("remind me at 5 30 pm to grab diapers"))
        assertEquals("Tue 17:42 flip the laundry", at("remind me in 20 minutes to flip the laundry"))
        assertEquals("Wed 09:00 call the doctor", at("remind me tomorrow at 9 to call the doctor"))
        assertEquals("Tue 20:00 take out the trash", at("remind me tonight at 8 to take out the trash"))
        assertEquals("both 5s passed: tomorrow evening", "Wed 17:00 pay the phone bill", at("remind me to pay the phone bill at 5"))
        assertNull("no time: Claude asks", at("remind me to call mom"))
        assertNull("repeating: Claude handles", at("remind me every day at 9 to take vitamins"))
    }

    @Test fun knowsWhenHesFinishedTalking() {
        assertTrue(EarlyRead.complete("Set a reminder for 5 PM.")); assertTrue(EarlyRead.complete("yeah"))
        assertTrue(EarlyRead.complete("What's the weather like"))
        assertFalse(EarlyRead.complete("Remind me to")); assertFalse(EarlyRead.complete("Can you text my")); assertFalse(EarlyRead.complete("so um"))
        assertFalse(EarlyRead.complete("I was thinking,")); assertFalse(EarlyRead.complete("hmm"))
    }
}
