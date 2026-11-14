package com.ziyair.jarvis

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The dispatcher: when one request has several independent parts ("have Muse book a haircut, check my balance, and
 * remind me to call Mom"), every part starts at the same time instead of one after another: agents, Muse, lookups.
 * Anything that needs the screen or a camera (only one thing can drive them at once) takes turns in a single lane;
 * everything else runs side by side. Results come back in the original order.
 */
object Dispatch {
    /** Tools that drive the screen, the camera or the speaker: one at a time, in order. */
    val EXCLUSIVE = setOf("phone_task", "screen_tap", "screen_type", "screen_action", "screen_read", "play_music", "open_app",
        "look", "remember_sight", "glasses_photo", "connect_glasses", "ask_muse", "agent_phone", "navigate", "media_control",
        "run_protocol", "find_phone", "answer_call", "decline_call", "send_text", "reply_message", "make_call")

    fun exclusive(name: String) = name in EXCLUSIVE

    /** How a batch is split: lane 0 holds the exclusive tools in order; every other tool gets its own lane. Pure. */
    fun lanes(names: List<String>): List<List<Int>> {
        val ex = names.indices.filter { exclusive(names[it]) }
        val rest = names.indices.filter { !exclusive(names[it]) }.map { listOf(it) }
        return (if (ex.isNotEmpty()) listOf(ex) else emptyList()) + rest
    }

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "jarvis-dispatch").apply { isDaemon = true } }

    /** Runs every call, in parallel where it's safe. [run] gets the call's index. Never throws. */
    fun <T> runAll(names: List<String>, parallel: Boolean, fail: (Exception) -> T, run: (Int) -> T): List<T> {
        if (!parallel || names.size < 2) return names.indices.map { i -> try { run(i) } catch (e: Exception) { fail(e) } }
        val out = arrayOfNulls<Any?>(names.size)
        val futures = ArrayList<Future<*>>()
        for (lane in lanes(names)) futures.add(pool.submit {
            for (i in lane) out[i] = try { run(i) } catch (e: Exception) { fail(e) }
        })
        for (f in futures) try { f.get(4, TimeUnit.MINUTES) } catch (_: Exception) {}
        @Suppress("UNCHECKED_CAST")
        return names.indices.map { i -> (out[i] ?: fail(Exception("timed out"))) as T }
    }
}
