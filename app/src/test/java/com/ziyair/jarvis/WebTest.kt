package com.ziyair.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Looking things up: real pages from reliable sources, never Yelp-style sites, everywhere he searches. */
class WebTest {
    @Test fun blocksTheSitesHeDoesntTrust() {
        for (d in listOf("yelp.com", "tripadvisor.com", "yellowpages.com", "menuwithprice.com", "restaurantji.com")) assertTrue(d, d in Web.BLOCKED)
        for (d in listOf("google.com", "walmart.com", "target.com", "fredmeyer.com", "costco.com")) assertFalse(d, d in Web.BLOCKED)
        assertTrue("bare domains only", Web.BLOCKED.all { !it.contains("://") && !it.startsWith("www.") })
    }

    @Test fun guideSaysReadTheRealPages() {
        assertTrue(Web.GUIDE.contains("web_fetch"))
        assertTrue(Web.GUIDE.contains("own website"))
        assertTrue(Web.GUIDE.contains("Don't rely on review aggregators"))
    }

    @Test fun usedByJarvisResearchAndSupplies() {
        val src = { f: String -> File("src/main/java/com/ziyair/jarvis/$f").readText() }
        assertTrue(src("Brain.kt").contains("Web.tools(") && src("Brain.kt").contains("Web.GUIDE"))
        assertTrue(src("Agents.kt").contains("Web.tools(") && src("Agents.kt").contains("Web.GUIDE"))
        assertTrue(src("PackagesSupplies.kt").contains("Web.tools("))
        assertFalse("no one builds the old search tool by hand any more", listOf("Brain.kt", "Agents.kt", "PackagesSupplies.kt").any { src(it).contains("web_search_20250305") })
    }

    @Test fun pageReadingCantFollowLinksFromHisMessages() {
        // a link inside a text or notification Jarvis read must never be opened (keeps his data from leaking)
        assertTrue(File("src/main/java/com/ziyair/jarvis/Web.kt").readText().contains("\"client_tool_results\", JSONObject().put(\"type\", \"none\")"))
    }
}
