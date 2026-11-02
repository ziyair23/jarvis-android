package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Packages: follows his deliveries from the notifications and texts stores and carriers already send (Amazon, UPS,
 * FedEx, USPS, Walmart, Target...), with no Claude call at all. Tells him when something's delivered, and knows
 * what's arriving today. Ignores his DoorDash work apps.
 */
object Packages {
    data class Parsed(val status: String, val item: String, val eta: String?, val tracking: String?)

    private val WORK = Regex("(?i)dasher|driver|doordash|uber ?eats|grubhub|instacart shopper|spark driver|amazon flex")
    private val SHOP = Regex("(?i)amazon|ups|fedex|usps|informed delivery|walmart|target|shein|temu|ebay|etsy|best buy|costco|ontrac|lasership|dhl|nike|apple|package|order|shipment")
    private val TRACK = Regex("\\b(1Z[0-9A-Z]{16}|9[0-9]{21,25}|[0-9]{12,15})\\b")

    /** What a delivery notification says, or null if it isn't one. Pure, so it's unit-tested. */
    fun parse(app: String, title: String, text: String): Parsed? {
        val all = "$title $text"
        if (WORK.containsMatchIn(app) || WORK.containsMatchIn(all)) return null
        if (!SHOP.containsMatchIn(app) && !SHOP.containsMatchIn(all)) return null
        val low = all.lowercase()
        val status = when {
            Regex("\\b(was|been|has been|is) delivered\\b|^delivered\\b|\\bdelivered[:!.]|\\bdelivered to\\b|\\bdelivered today\\b").containsMatchIn(low) && !Regex("will be delivered|to be delivered|delivered by").containsMatchIn(low) -> "delivered"
            "out for delivery" in low -> "out"
            Regex("arriving (today|tomorrow|\\w+day)|expected (delivery|by|today|tomorrow)|will arrive|arrives (today|tomorrow|\\w+day)|delivery (date|estimate)").containsMatchIn(low) -> "coming"
            Regex("\\bshipped\\b|on (its|the) way|in transit").containsMatchIn(low) -> "shipped"
            else -> return null
        }
        val eta = Regex("(?i)\\b(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(all)?.value?.lowercase()
            ?.takeIf { status != "delivered" }
        fun strip(x: String) = x.replace(Regex("(?i)^(delivered|arriving (today|tomorrow|\\w+day)|out for delivery|shipped|your (package|order|shipment)( from [A-Za-z ]+)?( has| is| was)?( been)?( shipped| delivered| arriving| out for delivery)?)[:!.,-]*\\s*"), "")
            .replace(TRACK, "").replace(Regex("\\s+"), " ").trim().trimEnd('.', '!')
        // the item's name: from the title ("Delivered: Pampers Size 1"), unless the title is only a status or the store's name
        val fromTitle = strip(title)
        val generic = Regex("(?i)^(ups|fedex|usps|amazon|target|walmart|update|ups update|fedex update|order update|delivery update|your order|your package|a package)?$")
        var item = if (fromTitle.length >= 4 && !generic.matches(fromTitle)) fromTitle else strip(text)
        if (item.length < 3) item = "a package"
        return Parsed(status, item.take(60), eta, TRACK.find(all)?.value)
    }

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("packages", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { val keep = JSONArray(); for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i)); Prefs(c).sp.edit().putString("packages", keep.toString()).apply() }
    private fun words(s: String) = s.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()

    fun on(c: Context) = Agents.on(c) && Agents.enabled(c, "packages")

    @Synchronized fun capture(c: Context, app: String, title: String, text: String) {
        if (!on(c)) return
        val p = parse(app, title, text) ?: return
        val a = all(c); var hit: JSONObject? = null
        for (i in a.length() - 1 downTo 0) { val o = a.getJSONObject(i)
            if (o.optString("status") == "delivered" && System.currentTimeMillis() - o.optLong("at") > 2 * 86_400_000L) continue
            val sameTrack = p.tracking != null && o.optString("track") == p.tracking
            val sameItem = p.item != "a package" && (words(o.optString("item")) intersect words(p.item)).size >= 2
            if (sameTrack || sameItem || (p.item == "a package" && o.optString("app") == app && o.optString("status") != "delivered")) { hit = o; break } }
        val o = hit ?: JSONObject().put("first", System.currentTimeMillis()).also { a.put(it) }
        val was = o.optString("status")
        if (was == "delivered" && p.status != "delivered") return
        o.put("app", app).put("status", p.status).put("at", System.currentTimeMillis())
        if (p.item != "a package" || !o.has("item")) o.put("item", p.item)
        p.eta?.let { o.put("eta", it) }; p.tracking?.let { o.put("track", it) }
        save(c, a); LogBus.changed()
        Trace.log(c, "packages", "${o.optString("item")}: ${p.status}")
        // say it when something lands (once), unless he's in quiet mode
        if (p.status == "delivered" && was != "delivered" && Prefs(c).running && !Stealth.on(Prefs(c)) && Brain(c).config().optBoolean("packages_announce", true)) {
            Agents.flash("packages")
            val line = "Sir, your ${if (o.optString("item") == "a package") "${shortApp(app)} package" else o.optString("item")} was just delivered."
            try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", line)) } catch (_: Exception) {}
        }
    }

    private fun shortApp(app: String) = app.split(" ").first()

    fun list(c: Context): String {
        val a = all(c); val l = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("status") != "delivered" || System.currentTimeMillis() - it.optLong("at") < 86_400_000L }.sortedByDescending { it.optLong("at") }
        if (l.isEmpty()) return "No packages on the way."
        return l.take(8).joinToString("; ") { o -> "${o.optString("item")} (${o.optString("app")}): " + when (o.optString("status")) {
            "delivered" -> "delivered ${SimpleDateFormat("h:mm a", Locale.US).format(Date(o.optLong("at")))}"
            "out" -> "out for delivery"
            else -> "on the way" + (o.optString("eta").takeIf { it.isNotBlank() }?.let { ", arriving $it" } ?: "") } } + "."
    }

    @Synchronized fun done(c: Context, what: String): String {
        val w = words(what); val a = all(c); val keep = JSONArray(); var hit: String? = null
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (hit == null && (w.isEmpty() || (words(o.optString("item")) intersect w).isNotEmpty())) hit = o.optString("item") else keep.put(o) }
        save(c, keep); return hit?.let { "Stopped following $it." } ?: "No package matching that."
    }

    /** What's coming today, for Jarvis and the briefing. */
    fun today(c: Context): String {
        val a = all(c); val l = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("status") == "out" || (it.optString("status") != "delivered" && it.optString("eta") == "today" && System.currentTimeMillis() - it.optLong("at") < 86_400_000L) }
        return if (l.isEmpty()) "" else "Packages arriving today: " + l.joinToString("; ") { it.optString("item") } + "."
    }
}

/**
 * Supplies: watches prices on the things he keeps rebuying (diapers, wipes, formula... picked up from his shopping list
 * automatically, or ones he names). Once a week it checks current prices at the big stores near Spokane with a quick web
 * search, remembers what's normal, and tells him when something is a real deal. Never buys anything.
 */
object Supplies {
    private val BABY = Regex("(?i)\\b(diapers?|wipes|formula|bottles?|nipples|pacifiers?|binkies|diaper cream|butt paste|desitin|baby wash|baby shampoo|baby lotion|pampers|huggies|similac|enfamil|onesies?|swaddles?|burp cloths?)\\b")

    /** Items on his shopping list worth watching. Pure. */
    fun worthWatching(item: String) = BABY.containsMatchIn(item)

    /** A real deal: at least 10% under the best price seen before. Pure. */
    fun isDeal(now: Double, bestBefore: Double?) = bestBefore != null && bestBefore > 0 && now > 0 && now <= bestBefore * 0.9

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("supplies", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = Prefs(c).sp.edit().putString("supplies", a.toString()).apply()
    private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\b(the|some|a|an|more)\\b"), "").replace(Regex("\\s+"), " ").trim()

    fun on(c: Context) = Agents.on(c) && Agents.enabled(c, "supplies")

    @Synchronized fun watch(c: Context, item0: String, auto: Boolean = false): String {
        val item = item0.trim().take(60); if (item.length < 3) return "What should I watch?"
        val a = all(c)
        for (i in 0 until a.length()) if (key(a.getJSONObject(i).optString("item")) == key(item)) return "Already watching $item."
        if (a.length() >= 12) return "I'm already watching twelve things; drop one first."
        a.put(JSONObject().put("item", item).put("added", System.currentTimeMillis()).put("auto", auto)); save(c, a); LogBus.changed()
        return "Watching prices on $item. I'll check weekly and tell you when it's a good deal."
    }

    /** Called when things go on his shopping list: baby supplies get watched automatically. */
    fun noticeListAdd(c: Context, items: String) {
        if (!on(c)) return
        items.split(Regex(",| and ")).map { it.trim() }.filter { worthWatching(it) }.forEach { watch(c, it, auto = true) }
    }

    @Synchronized fun unwatch(c: Context, item: String): String {
        val a = all(c); val keep = JSONArray(); var hit: String? = null
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (hit == null && (key(o.optString("item")).contains(key(item)) || key(item).contains(key(o.optString("item"))))) hit = o.optString("item") else keep.put(o) }
        save(c, keep); return hit?.let { "Stopped watching $it." } ?: "I'm not watching that."
    }

    fun list(c: Context): String {
        val a = all(c); if (a.length() == 0) return "Not watching anything yet. Baby supplies you add to the shopping list get watched automatically."
        return (0 until a.length()).joinToString("; ") { val o = a.getJSONObject(it)
            o.optString("item") + (o.optJSONObject("last")?.let { l -> ": ${money(l.optDouble("price"))} at ${l.optString("store")}${l.optString("size").takeIf { s -> s.isNotBlank() }?.let { s -> " ($s)" } ?: ""}" } ?: ": not checked yet") } + "." +
            (Prefs(c).sp.getString("supplies_deal", "")?.takeIf { it.isNotBlank() }?.let { " Latest deal: $it" } ?: "")
    }

    private fun money(v: Double) = "$" + String.format(Locale.US, "%.2f", v)

    @Volatile private var checking = false

    /** Once a week, the first time the phone's used after 8 AM on or after his check day. Background thread. */
    fun maybeCheck(c: Context) {
        if (!on(c) || checking || all(c).length() == 0) return
        if (System.currentTimeMillis() - Prefs(c).sp.getLong("supplies_checked", 0L) < 6.5 * 86_400_000L) return
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 8) return
        check(c)
    }

    /** Looks up current prices for everything on the watch list. Returns what it found. Blocking. */
    fun check(c: Context): String {
        if (checking) return "Already checking prices."
        val p = Prefs(c)
        if (p.claudeKey.isBlank()) return "No Claude key set."
        if (Spend.thisMonth(p) >= Spend.budget(p)) return "You're at your Claude budget for the month, so price checks wait until next month."
        val a = all(c); if (a.length() == 0) return "Nothing to check yet."
        checking = true; Agents.active["supplies"] = System.currentTimeMillis()
        try {
            p.sp.edit().putLong("supplies_checked", System.currentTimeMillis()).apply()
            val cfg = Brain(c).config()
            val items = (0 until a.length()).map { a.getJSONObject(it).optString("item") }
            val system = "You check prices for Marcus in Spokane, WA. For each item, search for the best current price at big stores he can use (Walmart, Target, Amazon, Costco, Fred Meyer, Walgreens). " +
                "Compare the common family size (e.g. diapers in the size and pack you find most), and work out a unit price (per diaper, per wipe, per ounce). " +
                "Reply with ONLY JSON: {\"items\":[{\"item\":\"exactly as given\",\"store\":\"...\",\"price\":34.97,\"size\":\"e.g. 198 count\",\"unit\":0.18}]}. Skip an item if you can't find a real current price."
            val tools = JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", minOf(8, items.size * 2))
                .put("user_location", JSONObject().put("type", "approximate").put("city", p.city.ifBlank { "Spokane" }).put("region", "Washington").put("country", "US")))
            var model = cfg.optString("supplies_model", cfg.optString("agent_model", "claude-haiku-5-5"))
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "Items: " + items.joinToString("; ")))
            var text = ""
            for (round in 1..4) {
                val body = JSONObject().put("model", model).put("max_tokens", 1500).put("system", system).put("tools", tools).put("messages", msgs)
                val (code, resp) = post(p.claudeKey, body)
                if (code == 400 && round == 1 && model != cfg.optString("research_model", "claude-sonnet-5-5")) { model = cfg.optString("research_model", "claude-sonnet-5-5"); continue }
                val j = resp ?: return "Couldn't reach Claude to check prices."
                Spend.record(c, cfg, model, j.optJSONObject("usage"))
                val content = j.optJSONArray("content") ?: JSONArray()
                text = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                if (j.optString("stop_reason") == "pause_turn") { msgs.put(JSONObject().put("role", "assistant").put("content", content)); continue }
                break
            }
            val json = try { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) } catch (_: Exception) { return "Couldn't read the prices this time." }
            val found = json.optJSONArray("items") ?: JSONArray()
            val deals = ArrayList<String>(); var seen = 0
            for (i in 0 until found.length()) {
                val f = found.getJSONObject(i)
                val price = f.optDouble("price", 0.0); if (price <= 0) continue
                val unit = f.optDouble("unit", 0.0).takeIf { it > 0 }
                val o = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { key(it.optString("item")) == key(f.optString("item")) } ?: continue
                seen++
                val measure = unit ?: price
                val best = o.optDouble("best", Double.NaN).takeIf { !it.isNaN() }
                if (isDeal(measure, best)) deals.add("${o.optString("item")} is ${money(price)} at ${f.optString("store")}${f.optString("size").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}, about ${Math.round((1 - measure / best!!) * 100)}% under the usual")
                if (best == null || measure < best) o.put("best", measure)
                o.put("last", JSONObject().put("price", price).put("store", f.optString("store")).put("size", f.optString("size")).put("unit", unit ?: JSONObject.NULL).put("at", System.currentTimeMillis()))
            }
            save(c, a)
            val out = if (deals.isNotEmpty()) "Deal: " + deals.joinToString("; ") + "." else if (seen > 0) "Checked prices on $seen things; nothing's on a real deal this week." else "Couldn't find current prices this time."
            p.sp.edit().putString("supplies_deal", if (deals.isNotEmpty()) out.removePrefix("Deal: ") else "").putLong("supplies_deal_at", if (deals.isNotEmpty()) System.currentTimeMillis() else 0L).apply()
            Trace.log(c, "supplies", out)
            return out
        } catch (e: Exception) { Problems.log(c, "supplies: ${e.message}"); return "Price check hit a problem: ${e.message}" }
        finally { checking = false; Agents.active.remove("supplies"); LogBus.changed() }
    }

    /** A fresh deal (this week), for Jarvis and the briefing. */
    fun deal(c: Context): String {
        val p = Prefs(c); val d = p.sp.getString("supplies_deal", "").orEmpty()
        return if (d.isBlank() || System.currentTimeMillis() - p.sp.getLong("supplies_deal_at", 0L) > 4 * 86_400_000L) "" else "Deal on supplies: $d"
    }

    private fun post(key: String, body: JSONObject): Pair<Int, JSONObject?> = try {
        val k = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        k.requestMethod = "POST"; k.doOutput = true; k.connectTimeout = 10000; k.readTimeout = 120000
        k.setRequestProperty("content-type", "application/json"); k.setRequestProperty("x-api-key", key); k.setRequestProperty("anthropic-version", "2023-06-01")
        k.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = k.responseCode
        val text = (if (code in 200..299) k.inputStream else k.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to (if (code in 200..299) JSONObject(text) else null)
    } catch (_: Exception) { 0 to null }
}
