package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How Jarvis looks things up: search, then open the reliable pages and read them (like a careful person would),
 * never Yelp-style directories, menu-price sites or SEO lists. Shared by Jarvis, the Research agent and Supplies.
 *
 * Uses Claude's current web search and web fetch tools. If his account or model ever rejects them, it drops back to the
 * basic search tool on its own (and remembers that).
 */
object Web {
    /** Sites he doesn't trust for answers: review aggregators, directories, scraped menu and price pages. */
    val BLOCKED = listOf("yelp.com", "tripadvisor.com", "yellowpages.com", "superpages.com", "mapquest.com", "manta.com",
        "chamberofcommerce.com", "birdeye.com", "foursquare.com", "restaurantji.com", "menupix.com", "allmenus.com",
        "zmenu.com", "sirved.com", "menuwithprice.com", "fastfoodmenuprices.com", "menu-price.net", "restaurantguru.com",
        "wanderlog.com", "topratedlocal.com", "cylex.us.com", "hotfrog.com", "brownbook.net", "find-open.com")

    /** How to look things up, for every prompt that searches. */
    const val GUIDE = "Look things up the way a careful person would: search, then open the most reliable pages with web_fetch and read them before you answer. " +
        "Trust, in this order: the business's own website or app pages (menus, hours, prices, deals), the store's own product pages (Walmart, Target, Fred Meyer, Costco, Safeway, Amazon), " +
        "Google's business listing details (hours, address, phone), official and government sites, and established news outlets. " +
        "Don't rely on review aggregators, directory sites, menu-price sites or 'best of' list articles. " +
        "For prices, deals and recommendations, check the actual page, and say where it came from in a few words ('on Fred Meyer's site'); no links. " +
        "If sources disagree, or a price may be out of date, say so in one short line."

    private fun legacy(c: Context) = Prefs(c).sp.getBoolean("web_legacy", false)

    /** The search and fetch tools for one request. [fetches] = 0 leaves page reading out. */
    fun tools(c: Context, searches: Int, fetches: Int, pageTokens: Int = 6000): JSONArray {
        val p = Prefs(c)
        val loc = JSONObject().put("type", "approximate").put("city", p.city.ifBlank { "Spokane" }).put("region", "Washington").put("country", "US")
            .put("timezone", java.util.TimeZone.getDefault().id)
        val blocked = JSONArray(BLOCKED)
        val out = JSONArray()
        if (legacy(c)) {
            out.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", searches).put("blocked_domains", blocked).put("user_location", loc))
            return out
        }
        out.put(JSONObject().put("type", "web_search_20260318").put("name", "web_search").put("max_uses", searches).put("blocked_domains", blocked)
            .put("user_location", loc).put("allowed_callers", JSONArray().put("direct")))
        if (fetches > 0) out.put(JSONObject().put("type", "web_fetch_20260318").put("name", "web_fetch").put("max_uses", fetches).put("blocked_domains", blocked)
            .put("max_content_tokens", pageTokens).put("allowed_callers", JSONArray().put("direct"))
            // only pages found by searching (or that he names): never a link hidden in a text or notification Jarvis read out
            .put("url_sources", JSONObject().put("client_tool_results", JSONObject().put("type", "none"))))
        return out
    }

    /** Claude turned the new tools down: use the basic search from now on. True if there was something to drop back from. */
    fun fallBack(c: Context, error: String): Boolean {
        if (legacy(c)) return false
        if (!Regex("web_fetch|web_search|allowed_callers|url_sources|blocked_domains|max_content_tokens|response_inclusion", RegexOption.IGNORE_CASE).containsMatchIn(error)) return false
        Prefs(c).sp.edit().putBoolean("web_legacy", true).apply()
        Trace.log(c, "web", "new web tools not accepted, using basic search: ${error.take(160)}")
        return true
    }

    /** Is this tool one of the web tools? */
    fun isWeb(t: JSONObject) = t.optString("name") == "web_search" || t.optString("name") == "web_fetch"
}
