package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The lock-screen brain: built right, every agent has a place in it, and it's registered with Android. */
class BrainWallpaperTest {
    private val m = BrainModel.get()

    @Test fun brainHasItsParts() {
        assertEquals(1500 + 260 + 90 + 160, m.kind.size)
        assertEquals(m.kind.size * 3, m.p.size)
        assertTrue("fibres: ${m.e.size / 2}", m.e.size / 2 > 1500)
        assertTrue(m.p.all { !it.isNaN() && kotlin.math.abs(it) < 2f })
    }

    @Test fun signalsCanHopNeuronToNeuron() {
        val n = m.kind.size
        assertEquals(n + 1, m.adjStart.size)
        assertEquals(m.e.size, m.adj.size)   // every fibre is listed at both of its ends
        for (i in 0 until n) for (j in m.adjStart[i] until m.adjStart[i + 1]) {
            val e = m.adj[j]; assertTrue(m.e[e * 2] == i || m.e[e * 2 + 1] == i)
        }
        assertTrue("most neurons connect onward", (0 until n).count { m.adjStart[it + 1] > m.adjStart[it] } > n * 0.8)
    }

    @Test fun everyAgentHasAPlaceThatCanLightUp() {
        for (d in Agents.ALL) {
            assertTrue("${d.id} has no place in the brain", BrainModel.POS.containsKey(d.id))
            assertTrue("${d.id} has no fibres near it to spark", (m.near[d.id]?.size ?: 0) > 5)
        }
    }

    @Test fun sameSpotsAsTheBrainTab() {
        val html = File("src/main/assets/dash.html").readText()
        for (id in BrainModel.POS.keys) assertTrue("$id missing from the Brain tab", html.contains("$id:["))
    }

    @Test fun registeredAsAWallpaper() {
        val man = File("src/main/AndroidManifest.xml").readText()
        assertTrue(man.contains(".BrainWallpaper") && man.contains("android.permission.BIND_WALLPAPER") && man.contains("@xml/brain_wallpaper"))
        assertTrue(File("src/main/res/xml/brain_wallpaper.xml").exists())
    }
}
