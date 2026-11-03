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
}
