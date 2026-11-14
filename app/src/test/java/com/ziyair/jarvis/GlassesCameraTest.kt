package com.ziyair.jarvis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Glasses camera: on-demand only, camera memory finds the right photo, and every tool is wired in. */
class GlassesCameraTest {
    private val src = "src/main/java/com/ziyair/jarvis/"
    private fun e(note: String) = JSONObject().put("note", note)

    @Test fun findsTheRightMemory() {
        val list = listOf(e("where I parked"), e("the paint color for the bedroom"), e("wifi password on the router"))
        assertEquals("where I parked", Sights.best(list, "where did I park?")!!.optString("note"))
        assertEquals("the paint color for the bedroom", Sights.best(list, "what paint did I save")!!.optString("note"))
        assertEquals("wifi password on the router", Sights.best(list, "what's the wifi password")!!.optString("note"))
        assertEquals("wifi password on the router", Sights.best(list, "show me what I saved")!!.optString("note"))   // nothing matches: the latest
        assertNull(Sights.best(emptyList(), "where did I park"))
        assertTrue("park" in Sights.words("where I parked") && "park" in Sights.words("where did I park"))
    }

    @Test fun cameraNeverLeftOn() {
        val g = File(src + "Glasses.kt").readText()
        assertTrue("each look closes the camera", g.contains("camera.stop()") && g.contains("session.stop()"))
        assertTrue(g.contains("withTimeoutOrNull(25_000)"))
        assertFalse("no recording or live feed kept", g.contains("videoStream.collect"))
    }

    @Test fun wiredIn() {
        val actions = File(src + "Actions.kt").readText()
        for (t in listOf("remember_sight", "recall_sight", "glasses_photo", "connect_glasses")) {
            assertTrue("$t has a schema", actions.contains("tool(\"$t\""))
            assertTrue("$t has a handler", actions.contains("\"$t\" ->"))
            assertTrue("$t is Jarvis's own", t in Agents.CORE)
        }
        val brain = File(src + "Brain.kt").readText()
        assertTrue("looks go through the glasses first", brain.contains("Sights.snap(context)"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("com.meta.wearable.mwdat.APPLICATION_ID") && manifest.contains(".GlassesSetup") && manifest.contains("BLUETOOTH_CONNECT"))
    }
}
