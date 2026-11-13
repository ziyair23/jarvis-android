package com.ziyair.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The glasses take over the mic only when they're glasses, and the phone mic comes back when they're gone. */
class GlassesMicTest {
    private val re = Regex("ray-?ban|oakley|meta", RegexOption.IGNORE_CASE)
    private val src = "src/main/java/com/ziyair/jarvis/"

    @Test fun onlyGlasses() {
        assertTrue(GlassesMic.isGlasses("Ray-Ban Meta 0A3F", re))
        assertTrue(GlassesMic.isGlasses("RayBan Meta Gen 2", re))
        assertTrue(GlassesMic.isGlasses("Oakley Meta HSTN", re))
        assertFalse(GlassesMic.isGlasses("Galaxy Buds2 Pro", re))
        assertFalse(GlassesMic.isGlasses("Toyota Camry", re))
        assertFalse(GlassesMic.isGlasses("", re))
        assertFalse(GlassesMic.isGlasses(null, re))
    }

    @Test fun wiredIn() {
        assertTrue(File(src + "Ear.kt").readText().contains("GlassesMic.use(context, rec)"))
        assertTrue(File(src + "WakeWord.kt").readText().contains("GlassesMic.use(context, rec)"))
        assertTrue(File(src + "JarvisService.kt").readText().contains("GlassesMic.tick(this)"))
        assertTrue("live hands the link back", File(src + "Live.kt").readText().contains("GlassesMic.forget()"))
        val g = File(src + "GlassesMic.kt").readText()
        assertTrue("music keeps its quality", g.contains("isMusicActive"))
        assertTrue("phone mic when the glasses are off", g.contains("unlink(c, \"glasses off\")"))
    }
}
