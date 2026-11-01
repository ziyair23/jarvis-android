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

/** Checks the agent split and the Builder's guard rails, on every build. */
class AgentsTest {
    private val actionsSrc = File("src/main/java/com/ziyair/jarvis/Actions.kt").readText()
    private val toolNames = Regex("put\\(tool\\(\"([a-z_]+)\"").findAll(actionsSrc).map { it.groupValues[1] }.toSet()

    @Test fun readsTheRealToolList() { assertTrue("found ${toolNames.size} tools", toolNames.size > 30) }

    @Test fun everySkillHasAHome() {
        val owned = Agents.CORE + Agents.PHONE.tools + Agents.MONEY.tools + Agents.FAMILY.tools
        val orphans = toolNames - owned
        assertTrue("skills nobody owns: $orphans", orphans.isEmpty())
    }

    @Test fun agentsOnlyClaimRealSkills() {
        for (d in listOf(Agents.PHONE, Agents.MONEY, Agents.FAMILY)) {
            val fake = d.tools - toolNames
            assertTrue("${d.id} claims skills that don't exist: $fake", fake.isEmpty())
        }
        assertTrue((Agents.CORE - toolNames).isEmpty())
    }

    @Test fun sendingSkillsLiveWithThePhoneAgent() {
        for (s in listOf("send_text", "reply_message", "call", "phone_task")) assertTrue(s in Agents.PHONE.tools)
        for (s in listOf("send_text", "reply_message", "call", "phone_task")) assertFalse("$s must not be a core skill", s in Agents.CORE)
        for (s in listOf("send_text", "reply_message", "phone_task")) assertTrue("$s must follow his real yes", s in Agents.CONFIRMABLE)
    }

    @Test fun sendingToolsStillDemandConfirmation() {
        // the confirmed flag must stay in the tool definitions the agents use
        for (s in listOf("send_text", "reply_message", "phone_task")) {
            val block = actionsSrc.substringAfter("put(tool(\"$s\"").substringBefore("put(tool(")
            assertTrue("$s lost its confirmed flag", block.contains("confirmed"))
        }
        assertTrue(actionsSrc.contains("if (!input.optBoolean(\"confirmed\")) return \"Not sent."))
    }

    @Test fun builderMergeAddsInstructionsAndKeepsTheRest() {
        val base = JSONObject().put("system", "Be Jarvis.").put("wake_seconds", 5).put("model", "m")
        val o = JSONObject().put("wake_seconds", 8).put("system_add", JSONArray().put("Call me Marcus."))
        val m = Builder.merge(base, o)
        assertEquals(8, m.getInt("wake_seconds"))
        assertEquals("m", m.getString("model"))
        assertTrue(m.getString("system").startsWith("Be Jarvis."))
        assertTrue(m.getString("system").contains("Call me Marcus."))
        assertEquals(5, base.getInt("wake_seconds"))   // the original isn't touched
    }

    @Test fun builderLimits() {
        assertNull(Builder.validate("wake_seconds", 8).second)
        assertNotNull(Builder.validate("wake_seconds", 99).second)
        assertNotNull(Builder.validate("piper_speed", "fast").second)
        assertEquals(true, Builder.validate("thinking_sound", "on").first)
        assertNotNull(Builder.validate("model", "claude-opus").second)          // can't swap models (cost)
        assertNotNull(Builder.validate("claude_key", "x").second)               // nor touch keys
        assertNull(Builder.validate("greetings", "[\"Sir?\",\"Yes, Marcus?\"]").second)
        assertNotNull(Builder.validate("greetings", "[]").second)
    }

    @Test fun builderCannotWeakenTheConfirmRule() {
        assertTrue(Builder.weakensSafety("Send texts without asking me first"))
        assertTrue(Builder.weakensSafety("Don't confirm before sending"))
        assertTrue(Builder.weakensSafety("auto-reply to Abbey"))
        assertTrue(Builder.weakensSafety("just send it"))
        assertFalse(Builder.weakensSafety("Call me Marcus, not sir"))
        assertFalse(Builder.weakensSafety("Keep answers under two sentences"))
    }

    @Test fun builderCannotTouchKeysOrThePipeline() {
        assertTrue(Builder.offLimits(".github/workflows/build.yml"))
        assertTrue(Builder.offLimits("app/jarvis.keystore"))
        assertTrue(Builder.offLimits("sdk/home-sdk.tgz.enc"))
        assertTrue(Builder.offLimits("app/src/test/java/com/ziyair/jarvis/AgentsTest.kt"))   // can't weaken its own tests
        assertFalse(Builder.offLimits("app/src/main/java/com/ziyair/jarvis/Reflex.kt"))
        assertFalse(Builder.offLimits("config/persona.json"))
    }
}
