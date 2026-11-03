package com.ziyair.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Get to know me": once (and again whenever he asks), Jarvis reads back through his phone to learn his life:
 *  - texts from businesses (bills, orders, deliveries, appointments, stores) over the last four months;
 *  - who he talks to most (names and how often only: what friends and family say is never read or sent anywhere);
 *  - his calendar, birthdays saved in contacts, the apps he has;
 *  - his past orders in shopping apps like Walmart and Amazon, read off the screen the way he'd scroll through them;
 *  - what Jarvis already tracks (spending, bills, deliveries, earnings).
 * Then one careful pass with the bigger model turns that into memories, bills, things to price-watch and notes for
 * each agent. Everything stays on the phone except that one pass; everything it saved can be deleted in the app.
 */
object Scan {
    @Volatile var running = false; private set
    @Volatile var step = ""; private set

    fun report(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("scan_report", "{}")) } catch (_: Exception) { JSONObject() }

    private fun has(c: Context, perm: String) = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    private val day = SimpleDateFormat("MMM d, yyyy", Locale.US)

    /** Shopping apps whose order history he can be walked through on screen: package to (name, where the orders are). */
    val SHOPS = linkedMapOf(
        "com.walmart.android" to ("Walmart" to "Account, then Purchase history"),
        "com.amazon.mShop.android.shopping" to ("Amazon" to "the profile tab, then Your orders"),
        "com.target.ui" to ("Target" to "Account, then Orders"))

    /** Starts the scan in the background. [screens] = also walk through shopping apps' order history on screen. */
    fun start(c: Context, screens: Boolean = true): String {
        if (running) return "Already getting to know you: $step"
        if (Prefs(c).claudeKey.isBlank()) return "I need my Claude key first."
        running = true; step = "Starting"; LogBus.changed()
        thread(name = "jarvis-scan") {
            try { run(c, screens) } catch (e: Exception) { Problems.log(c, "scan: ${e.message}"); save(c, JSONObject().put("error", e.message ?: "problem")) }
            finally { running = false; step = ""; LogBus.changed() }
        }
        return "Started. Tell him you're reading back through his phone to get to know him, it takes a few minutes" +
            (if (screens && JarvisAccess.me != null) ", and his screen will flip through his shopping apps' order history for a minute, so leave the phone be" else "") + ". You'll tell him what you learned."
    }

    private fun progress(c: Context, s: String) { step = s; Trace.log(c, "scan", s); LogBus.changed() }

    // ---------------- reading the phone ----------------
    /** Business texts (short codes or named senders), newest first, deduplicated. Pure over the rows. */
    fun businessTexts(rows: List<Triple<String, String, Long>>, max: Int = 250): List<String> {
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for ((addr, body, at) in rows.sortedByDescending { it.third }) {
            if (!LifeWatch.businessSender(addr)) continue
            val b = body.replace(Regex("\\s+"), " ").trim()
            if (b.length < 12 || Regex("(?i)\\b(code|verification|otp|passcode|password)\\b").containsMatchIn(b)) continue
            val k = b.replace(Regex("\\d"), "#").take(60)
            if (!seen.add(k)) continue
            out.add("${SimpleDateFormat("MMM d", Locale.US).format(Date(at))} ($addr): ${b.take(220)}")
            if (out.size >= max) break
        }
        return out
    }

    private fun texts(c: Context): Pair<List<String>, List<String>> {
        if (!has(c, Manifest.permission.READ_SMS)) return emptyList<String>() to emptyList()
        val since = System.currentTimeMillis() - 120L * 86_400_000L
        val rows = ArrayList<Triple<String, String, Long>>()
        val people = HashMap<String, IntArray>()   // address -> [received, sent]
        for ((box, sent) in listOf("inbox" to false, "sent" to true)) try {
            c.contentResolver.query(Uri.parse("content://sms/$box"), arrayOf("address", "body", "date"), "date > ?", arrayOf(since.toString()), "date DESC")?.use { q ->
                while (q.moveToNext() && rows.size < 6000) {
                    val a = q.getString(0) ?: continue; val b = q.getString(1) ?: ""
                    if (!sent && LifeWatch.businessSender(a)) rows.add(Triple(a, b, q.getLong(2)))
                    else if (!LifeWatch.businessSender(a)) people.getOrPut(a.filter { it.isDigit() }.takeLast(10)) { IntArray(2) }[if (sent) 1 else 0]++
                }
            }
        } catch (_: Exception) {}
        val names = people.entries.sortedByDescending { it.value[0] + it.value[1] }.take(15).map { (num, n) -> "${contactName(c, num) ?: "an unsaved number"}: ${n[0] + n[1]} texts" }
        return businessTexts(rows) to names
    }

    private fun contactName(c: Context, num: String): String? = try {
        if (!has(c, Manifest.permission.READ_CONTACTS) || num.length < 7) null
        else c.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)), arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { q -> if (q.moveToFirst()) q.getString(0) else null }
    } catch (_: Exception) { null }

    private fun birthdays(c: Context): List<String> = try {
        if (!has(c, Manifest.permission.READ_CONTACTS)) emptyList() else {
            val out = ArrayList<String>()
            c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { q ->
                while (q.moveToNext() && out.size < 80) out.add("${q.getString(0)}: ${q.getString(1)}")
            }
            out
        }
    } catch (_: Exception) { emptyList() }

    private fun calendar(c: Context): List<String> = try {
        if (!Cal.allowed(c)) emptyList() else {
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, now - 90L * 86_400_000L); android.content.ContentUris.appendId(it, now + 60L * 86_400_000L) }.build()
            val out = ArrayList<String>()
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { q ->
                while (q.moveToNext() && out.size < 160) out.add("${day.format(Date(q.getLong(1)))}: ${q.getString(0).orEmpty().take(60)}")
            }
            out
        }
    } catch (_: Exception) { emptyList() }

    private fun apps(c: Context): List<String> = try {
        val pm = c.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }.distinct().sorted().take(150)
    } catch (_: Exception) { emptyList() }

    /** Walks through a shopping app's order history on screen and reads it, like scrolling through it himself. */
    private fun orders(c: Context, pkg: String, name: String, where: String): String? {
        if (JarvisAccess.me == null || c.packageManager.getLaunchIntentForPackage(pkg) == null) return null
        progress(c, "Reading your $name orders")
        val got = Hands.run(c, "Open the $name app and go to his list of past orders ($where). Stop as soon as the list of past orders is on screen. " +
            "Only look: don't open an order, reorder, buy, cancel or change anything.", false)
        Trace.log(c, "scan", "$name: $got")
        val pages = LinkedHashSet<String>()
        repeat(5) {
            val r = JarvisAccess.read()
            if (!r.contains(name, true) && !r.contains(pkg)) return@repeat
            pages.add(r.substringAfter(": ").take(3000))
            JarvisAccess.press("scroll_down"); Thread.sleep(1200)
        }
        JarvisAccess.press("home")
        return pages.joinToString(" | ").take(9000).ifBlank { null }
    }

    // ---------------- making sense of it ----------------
    private fun run(c: Context, screens: Boolean) {
        val t0 = System.currentTimeMillis()
        progress(c, "Reading your texts")
        val (biz, people) = texts(c)
        progress(c, "Reading your calendar and contacts")
        val cal = calendar(c); val bdays = birthdays(c); val appList = apps(c)
        val shopping = LinkedHashMap<String, String>()
        if (screens) for ((pkg, v) in SHOPS) orders(c, pkg, v.first, v.second)?.let { shopping[v.first] = it }
        progress(c, "Putting it all together")
        val material = StringBuilder()
        material.append("Texts from businesses, last 4 months (${biz.size}):\n").append(biz.joinToString("\n").ifBlank { "(none readable)" })
        material.append("\n\nWho he texts most (names and counts only):\n").append(people.joinToString("\n").ifBlank { "(none)" })
        material.append("\n\nCalendar, past 3 months and next 2:\n").append(cal.joinToString("\n").ifBlank { "(none)" })
        material.append("\n\nBirthdays saved in contacts:\n").append(bdays.joinToString("\n").ifBlank { "(none)" })
        material.append("\n\nApps on his phone:\n").append(appList.joinToString(", "))
        for ((n, t) in shopping) material.append("\n\nHis $n order history, as read off the screen:\n").append(t)
        material.append("\n\nWhat Jarvis already knows:\n").append(Team.brief(c)).append(Memory.promptBlock(c))
        material.append("\n\nPurchases picked up from bank and card alerts:\n").append(try { val a = Spending.all(c); (maxOf(0, a.length() - 120) until a.length()).joinToString("; ") { a.getJSONObject(it).let { o -> "${o.optString("m")} $${o.optDouble("a")}" } } } catch (_: Exception) { "" })

        val actions = Actions(c)
        val tools = JSONArray()
        val all = actions.tools(); for (i in 0 until all.length()) { val t = all.getJSONObject(i); if (t.optString("name") in setOf("remember", "bill_add", "supply_watch")) tools.put(JSONObject(t.toString())) }
        tools.put(Agents.TEAM_NOTE)
        tools.put(JSONObject().put("name", "agent_note").put("description", "Teach one agent something about how he lives, so it does its job better (Supplies: what he buys and where; Meals: what he eats; Bills; Money; Planner; Family; Packages).")
            .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("agent", JSONObject().put("type", "string").put("enum", JSONArray(listOf("supplies", "meals", "bills", "money", "planner", "family", "packages", "appts", "pursuit"))))
                .put("note", JSONObject().put("type", "string"))).put("required", JSONArray().put("agent").put("note"))))
        val learned = ArrayList<String>()
        val system = "You are Jarvis, Marcus's personal assistant, getting to know him from his own phone, with his permission. Read everything below and learn how he actually lives: " +
            "his bills and subscriptions (amounts, due days), where he shops and what he buys regularly (Walmart delivery and so on) and how often, his stores, his routines, the people who matter (by how often they talk; birthdays), his appointments and patterns.\n" +
            "Save what's genuinely useful and durable: remember for facts about his life (one clear fact each, like 'Orders Walmart delivery about once a week, mostly diapers, wipes, milk and eggs'), " +
            "bill_add for real recurring bills with their next due date, supply_watch for things he rebuys often, agent_note to teach the right agent. " +
            "Rules: only what the data actually shows, never guesses; no codes, card or account numbers, passwords, health details or anything private about other people; skip one-offs and marketing. " +
            "Don't save what Jarvis already knows. Around 15 to 30 saves is right.\n" +
            "Finish with a short spoken summary for him (three or four plain sentences): the most interesting things you learned and what you set up. No lists."
        val cfg = Brain(c).config()
        val out = Claude.work(c, cfg.optString("scan_model", "claude-sonnet-5-5"), system, material.toString().take(60000), tools, "medium", 8000, 30) { name, input ->
            when (name) {
                "remember" -> if (Memory.add(c, input.optString("fact"))) "Saved.".also { learned.add(input.optString("fact")) } else "Already known or not allowed."
                "agent_note" -> if (Agents.addNote(c, input.optString("agent"), input.optString("note"))) "Saved.".also { learned.add("${input.optString("agent")}: ${input.optString("note")}") } else "Already noted."
                "team_note" -> Team.note(c, "Jarvis", input.optString("note"))
                "bill_add" -> actions.run(name, input).also { learned.add("Bill: ${input.optString("name")}") }
                "supply_watch" -> actions.run(name, input).also { learned.add("Price-watching: ${input.optString("item")}") }
                else -> "Not available."
            }
        }
        val summary = out?.first?.takeIf { it.isNotBlank() } ?: if (out == null) "I couldn't reach Claude to finish getting to know you. Try again in a bit." else "I learned ${learned.size} things about you; they're in the app."
        save(c, JSONObject().put("at", System.currentTimeMillis()).put("summary", summary).put("learned", JSONArray(learned))
            .put("read", JSONObject().put("texts", biz.size).put("people", people.size).put("calendar", cal.size).put("birthdays", bdays.size).put("shops", JSONArray(shopping.keys)))
            .put("ms", System.currentTimeMillis() - t0))
        Trace.log(c, "scan", "done: ${learned.size} saved, ${biz.size} business texts, ${people.size} people, ${cal.size} events, shops ${shopping.keys}")
        try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", summary)) } catch (_: Exception) {}
    }

    private fun save(c: Context, o: JSONObject) { Prefs(c).sp.edit().putString("scan_report", o.toString()).apply(); LogBus.changed() }
}
