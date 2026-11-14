package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** One request, many parts: independent ones run at the same time, screen/camera ones take turns, order is kept. */
class DispatchTest {
    @Test fun lanes() {
        val names = listOf("ask_muse", "money_status", "agent_money", "look", "remind_me")
        val l = Dispatch.lanes(names)
        assertEquals(listOf(0, 3), l[0])                       // Muse and the camera share one lane, in order
        assertEquals(setOf(listOf(1), listOf(2), listOf(4)), l.drop(1).toSet())
    }

    @Test fun runsAtTheSameTime() {
        val names = listOf("agent_money", "agent_life", "get_weather")
        val t0 = System.currentTimeMillis()
        val out = Dispatch.runAll(names, true, { "fail" }) { i -> Thread.sleep(300); "r$i" }
        val ms = System.currentTimeMillis() - t0
        assertEquals(listOf("r0", "r1", "r2"), out)            // results stay in order
        assertTrue("took $ms ms, should be ~300 not ~900", ms < 700)
    }

    @Test fun screenThingsTakeTurns() {
        val order = ConcurrentLinkedQueue<String>()
        Dispatch.runAll(listOf("phone_task", "ask_muse"), true, { "fail" }) { i -> order.add("start$i"); Thread.sleep(100); order.add("end$i"); "ok" }
        assertEquals(listOf("start0", "end0", "start1", "end1"), order.toList())
    }

    @Test fun failureStaysContained() {
        val out = Dispatch.runAll(listOf("a", "b"), true, { e -> "failed: ${e.message}" }) { i -> if (i == 0) throw IllegalStateException("boom") else "ok" }
        assertEquals(listOf("failed: boom", "ok"), out)
    }

    @Test fun wiredIn() {
        val src = "src/main/java/com/ziyair/jarvis/"
        assertTrue(File(src + "Brain.kt").readText().contains("Dispatch.runAll("))
        assertTrue(File(src + "Claude.kt").readText().contains("Dispatch.runAll("))
        assertTrue("background jobs can't hand Muse a job he didn't ask for", File(src + "Jobs.kt").readText().contains("Muse jobs need his yes"))
    }
}
