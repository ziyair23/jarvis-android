package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Total recall: everything that happens in his life that Jarvis sees, kept as one timeline on the phone
 * (what he said and what Jarvis said, feeds and diapers, money in and out, bills, appointments, deliveries,
 * messages that came in, reminders, jobs, Jarvis's own thoughts). "When did Ziyair last have a fever?",
 * "what did Jesse say about Saturday?", "how many times did we feed him yesterday?" are answered from it.
 * Searching happens on the phone; only the few matching moments go to Claude to answer from.
 */
object Timeline {
    private const val MAX_BYTES = 24L * 1024 * 1024
    private fun file(c: Context) = File(c.applicationContext.filesDir, "timeline.jsonl")
    private val lock = Any()

    fun add(c: Context, kind: String, text: String, at: Long = System.currentTimeMillis()) {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isBlank()) return
        try {
            synchronized(lock) {
                val f = file(c)
                f.appendText(JSONObject().put("at", at).put("k", kind).put("t", t.take(600)).toString() + "\n")
                if (f.length() > MAX_BYTES) { val lines = f.readLines(); f.writeText(lines.drop(lines.size / 4).joinToString("\n") + "\n") }
            }
        } catch (_: Exception) {}
    }

    fun all(c: Context): List<JSONObject> = try {
        synchronized(lock) { file(c).takeIf { it.exists() }?.readLines().orEmpty() }.mapNotNull { l -> try { JSONObject(l) } catch (_: Exception) { null } }
    } catch (_: Exception) { emptyList() }

    fun count(c: Context): Int = try { synchronized(lock) { file(c).takeIf { it.exists() }?.useLines { it.count() } ?: 0 } } catch (_: Exception) { 0 }

    // ---------------- search ----------------
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "with", "about", "did", "do", "does", "was", "were", "is", "are",
        "when", "what", "who", "how", "many", "much", "last", "time", "times", "i", "me", "my", "he", "him", "his", "we", "our", "you", "it", "that", "this", "say", "said", "ever")
    private val SYN = mapOf(
        "fever" to listOf("temperature", "temp", "sick", "hot"), "sick" to listOf("fever", "ill", "cold", "cough"),
        "feed" to listOf("fed", "bottle", "ate", "feeding"), "diaper" to listOf("changed", "poop", "pee"), "sleep" to listOf("asleep", "nap", "slept"),
        "money" to listOf("earned", "made", "spent", "paid"), "text" to listOf("message", "msg", "sms"), "message" to listOf("text"),
        "doctor" to listOf("pediatrician", "appointment", "checkup", "clinic"), "bill" to listOf("due", "payment", "pay"),
        "package" to listOf("delivered", "delivery", "order"), "gas" to listOf("fuel", "shell", "chevron"))

    /** Lowercase word stems (very simple: feeds/feeding/fed -> feed). Pure. */
    fun stem(w: String): String = w.lowercase().let { x -> when {
        x.length > 5 && x.endsWith("ing") -> x.dropLast(3)
        x.length > 4 && x.endsWith("ed") -> x.dropLast(2)
        x.length > 3 && x.endsWith("es") -> x.dropLast(2)
        x.length > 3 && x.endsWith("s") && !x.endsWith("ss") -> x.dropLast(1)
        else -> x } }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }

    /** The moments that best match [query] between [from] and [to] (millis), best and newest first. Pure. */
    fun search(events: List<JSONObject>, query: String, from: Long = 0L, to: Long = Long.MAX_VALUE, kinds: Set<String> = emptySet(), max: Int = 30): List<JSONObject> {
        val q = words(query).filter { it !in STOP }
        val terms = q.map { stem(it) }.toMutableSet()
        q.forEach { w -> SYN[stem(w)]?.forEach { terms.add(stem(it)) }; SYN[w]?.forEach { terms.add(stem(it)) } }
        val inRange = events.filter { it.optLong("at") in from..to && (kinds.isEmpty() || it.optString("k") in kinds) }
        if (terms.isEmpty()) return inRange.takeLast(max).reversed()
        return inRange.map { e ->
            val ws = words(e.optString("t") + " " + e.optString("k")).map { stem(it) }.toSet()
            e to terms.count { t -> ws.contains(t) || (t.length >= 4 && ws.any { it.startsWith(t) }) }
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<JSONObject, Int>> { it.second }.thenByDescending { it.first.optLong("at") })
            .take(max).map { it.first }
    }

    private val fmt get() = SimpleDateFormat("EEE MMM d yyyy, h:mm a", Locale.US)

    /** The recall tool: what Jarvis reads to answer a question about the past. */
    fun recall(c: Context, input: JSONObject): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val from = input.optString("from").let { try { day.parse(it)?.time } catch (_: Exception) { null } } ?: 0L
        val to = input.optString("to").let { try { day.parse(it)?.time?.plus(86_400_000L - 1) } catch (_: Exception) { null } } ?: Long.MAX_VALUE
        val kinds = input.optString("kinds").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val hits = search(all(c), input.optString("query"), from, to, kinds, input.optInt("max", 30).coerceIn(5, 60))
        if (hits.isEmpty()) return "Nothing in his timeline matches that${if (from > 0 || to < Long.MAX_VALUE) " in those dates" else ""}. Try other words, or say you don't have a record of it."
        return "From his timeline (newest and best matches first):\n" + hits.joinToString("\n") { "${fmt.format(Date(it.optLong("at")))} [${it.optString("k")}] ${it.optString("t").take(300)}" }
    }

    /** First run: fills the timeline from everything Jarvis already kept, so recall works from day one. */
    fun backfill(c: Context) {
        val p = Prefs(c)
        if (p.sp.getBoolean("timeline_backfilled", false)) return
        p.sp.edit().putBoolean("timeline_backfilled", true).apply()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rows = ArrayList<Triple<Long, String, String>>()
        fun arr(a: JSONArray, f: (JSONObject) -> Triple<Long, String, String>?) { for (i in 0 until a.length()) try { f(a.getJSONObject(i))?.let { rows.add(it) } } catch (_: Exception) {} }
        try {
            arr(p.history()) { o -> Triple(o.optLong("at"), if (o.optString("role") == "user") "him" else "jarvis", o.optString("content")) }
            arr(Baby.all(p)) { o -> Triple(o.optLong("at"), "baby", "${p.babyName.ifBlank { "Baby" }}: ${o.optString("k")}${o.optString("n").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}") }
            arr(Earnings.all(p)) { o -> day.parse(o.optString("d"))?.time?.let { Triple(it + 21 * 3600_000L, "earned", "Earned ${Earnings.money(o.optDouble("a"))} dashing") } }
            arr(Expenses.all(p)) { o -> Triple(o.optLong("at"), "expense", "Spent ${Earnings.money(o.optDouble("a"))} on ${o.optString("w")}") }
            arr(Spending.all(c)) { o -> Triple(o.optLong("at"), "purchase", "${if (o.optString("c") == "income") "Money in" else "Paid"} ${Earnings.money(o.optDouble("a"))} ${o.optString("m")} (${o.optString("c")})") }
            arr(Journal.all(p)) { o -> Triple(o.optLong("at"), "journal", o.optString("t")) }
            arr(Packages.all(c)) { o -> Triple(o.optLong("at"), "package", "${o.optString("item")}: ${o.optString("status")}") }
            arr(Appts.all(c)) { o -> Triple(o.optLong("at"), "appointment", o.optString("what")) }
            arr(Bills.all(c)) { o -> Triple(o.optLong("due"), "bill", "${o.optString("name")} ${if (o.has("amount")) Earnings.money(o.optDouble("amount")) else ""} due") }
        } catch (_: Exception) {}
        rows.filter { it.first > 0 }.sortedBy { it.first }.forEach { add(c, it.second, it.third, it.first) }
        Trace.log(c, "recall", "timeline started with ${rows.size} past moments")
    }
}
