package com.ziyair.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Bills and Appointments agents, working on their own.
 *
 * LifeWatch quietly keeps the texts and notifications that look like a bill or an appointment ("Your payment of $85 is
 * due Nov 14", "Your appointment is confirmed for Nov 20 at 2:30"), never one-time codes or ordinary chats. Once a day
 * (and whenever he asks) it reads what's new with one small Claude call, then Bills and Appts keep the book, set the
 * reminders and put appointments on his calendar. He doesn't have to type anything in.
 */
object LifeWatch {
    // ---------------- pure rules (unit-tested) ----------------
    private val BILL = Regex("(?i)\\b(due|payment|bill|statement|autopay|auto-pay|renews?|renewal|subscription|invoice|past due|minimum payment|will be charged|amount due|balance due)\\b")
    private val APPT = Regex("(?i)\\b(appointment|appt|scheduled for|is scheduled|reminder:? your|confirmed for|check-?up|checkup|well[- ]child|visit on|reservation|booked for|see you on)\\b")
    private val SKIP = Regex("(?i)\\b(code|verification|verify|otp|passcode|password|one[- ]time|security alert|sign[- ]in|log[- ]in attempt|2fa)\\b")
    private val HAS_WHEN = Regex("(?i)(\\$\\s?\\d|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{1,2}\\b|\\b\\d{1,2}/\\d{1,2}\\b|\\b(mon|tues?|wed|thur?s?|fri|sat|sun)[a-z]*day\\b|\\btomorrow\\b|\\b\\d{1,2}(:\\d{2})? ?(am|pm)\\b)")

    /** Worth keeping for Bills or Appointments? Needs a bill/appointment word AND an amount or a date/time, and is never a login code. */
    fun relevant(text: String): Boolean = text.length in 15..600 && !SKIP.containsMatchIn(text) && (BILL.containsMatchIn(text) || APPT.containsMatchIn(text)) && HAS_WHEN.containsMatchIn(text)

    /** A business sender (short code or a name), not a person's phone number. */
    fun businessSender(address: String): Boolean { val d = address.filter { it.isDigit() }; return address.any { it.isLetter() } || d.length in 3..6 }

    // ---------------- capture ----------------
    fun inbox(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("life_inbox", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized fun capture(c: Context, from: String, title: String, text: String, at: Long = System.currentTimeMillis()) {
        val body = (if (title.isNotBlank() && !text.startsWith(title)) "$title: " else "") + text
        if (!relevant(body)) return
        val a = inbox(c)
        for (i in maxOf(0, a.length() - 40) until a.length()) if (a.getJSONObject(i).optString("t") == body) return
        a.put(JSONObject().put("at", at).put("from", from.take(40)).put("t", body.take(600)))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 150) until a.length()) keep.put(a.get(i))
        Prefs(c).sp.edit().putString("life_inbox", keep.toString()).apply()
    }

    /** First run: learn from the last two months of texts from businesses (needs permission to read texts). */
    fun backfill(c: Context) {
        val p = Prefs(c)
        if (p.sp.getBoolean("life_backfilled", false)) return
        if (c.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val since = System.currentTimeMillis() - 60L * 86_400_000L
            c.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), "date > ?", arrayOf(since.toString()), "date ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val addr = cur.getString(0) ?: continue; val body = cur.getString(1) ?: continue
                    if (businessSender(addr)) capture(c, "Texts", "", body, cur.getLong(2))
                }
            }
            p.sp.edit().putBoolean("life_backfilled", true).apply()
            Trace.log(c, "life", "learned from past texts: ${inbox(c).length()} bill or appointment messages")
        } catch (e: Exception) { Problems.log(c, "reading past texts: ${e.message}") }
    }

    // ---------------- the daily sweep ----------------
    @Volatile private var sweeping = false

    fun on(c: Context) = Agents.on(c) && (Agents.enabled(c, "bills") || Agents.enabled(c, "appts")) && Brain(c).config().optBoolean("life_admin", true)

    /** Once a day, the first time the phone is used after 5 AM. Safe to call often (background thread). */
    fun maybeSweep(c: Context) {
        if (!on(c) || sweeping) return
        val cut = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 5); set(Calendar.MINUTE, 0) }.timeInMillis
        val since = if (System.currentTimeMillis() >= cut) cut else cut - 86_400_000L
        if (Prefs(c).sp.getLong("life_swept", 0L) >= since) return
        sweep(c)
    }

    /** Reads what's new and updates bills and appointments. Returns what changed, in a sentence. Blocking. */
    fun sweep(c: Context): String {
        if (sweeping) return "Already checking."
        sweeping = true
        Agents.active["bills"] = System.currentTimeMillis(); Agents.active["appts"] = System.currentTimeMillis()
        val p = Prefs(c)
        try {
            backfill(c)
            Bills.roll(c)
            val last = p.sp.getLong("life_swept", 0L)
            val all = inbox(c)
            val fresh = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optLong("at") > last - 3600_000L }
            p.sp.edit().putLong("life_swept", System.currentTimeMillis()).apply()
            val checkups = Appts.checkupNote(c)
            if (fresh.isEmpty()) { note(c); return "Nothing new in your texts." + (checkups?.let { " $it" } ?: "") }
            if (p.claudeKey.isBlank()) return "No Claude key set."
            val cfg = Brain(c).config()
            val model = cfg.optString("agent_model", "claude-haiku-5-5")
            val fmt = SimpleDateFormat("EEE MMM d, yyyy h:mm a", Locale.US)
            val user = "Today is ${fmt.format(Date())}.\nKnown bills: ${Bills.brief(c)}\nKnown appointments: ${Appts.brief(c)}\n\nNew texts and notifications:\n" +
                fresh.joinToString("\n") { "- [${fmt.format(Date(it.optLong("at")))}] ${it.optString("from")}: ${it.optString("t")}" }
            val system = "You read Marcus's texts and notifications for bills and appointments. Only from companies, offices and services, never from friends or family chats. " +
                "Reply with ONLY a JSON object: {\"bills\":[{\"name\":\"short name like Phone or Car insurance\",\"amount\":85.0 or null,\"due\":\"yyyy-MM-dd\",\"repeat\":\"monthly\" or \"none\",\"paid\":true if it says it was paid}]," +
                "\"appointments\":[{\"what\":\"short, e.g. Dentist or Ziyair's checkup\",\"date\":\"yyyy-MM-dd\",\"time\":\"HH:mm\" or null,\"where\":\"place or null\",\"cancelled\":true if cancelled}]}. " +
                "Work out real dates from the message date. Skip anything already known unless the date or amount changed, skip marketing and sales, and leave a list empty if there's nothing."
            val body = JSONObject().put("model", model).put("max_tokens", 900).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            val resp = post(p.claudeKey, body) ?: return "Couldn't reach Claude to check."
            Spend.record(c, cfg, model, resp.optJSONObject("usage"))
            val txt = resp.optJSONArray("content")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }.orEmpty()
            val json = try { JSONObject(txt.substring(txt.indexOf('{'), txt.lastIndexOf('}') + 1)) } catch (_: Exception) { JSONObject() }
            val changes = ArrayList<String>()
            json.optJSONArray("bills")?.let { a -> for (i in 0 until a.length()) Bills.fromSweep(c, a.getJSONObject(i))?.let { changes.add(it) } }
            json.optJSONArray("appointments")?.let { a -> for (i in 0 until a.length()) Appts.fromSweep(c, a.getJSONObject(i))?.let { changes.add(it) } }
            note(c)
            val out = (if (changes.isEmpty()) "Checked your texts; nothing new." else changes.joinToString(" ")) + (checkups?.let { " $it" } ?: "")
            Trace.log(c, "life", "sweep: $out")
            return out
        } catch (e: Exception) {
            Problems.log(c, "life sweep: ${e.message}"); return "Couldn't finish checking: ${e.message}"
        } finally {
            sweeping = false; Agents.active.remove("bills"); Agents.active.remove("appts"); LogBus.changed()
        }
    }

    /** One line for Jarvis about what's coming up in the next week (saved after each sweep). */
    private fun note(c: Context) {
        val parts = listOfNotNull(Bills.upcoming(c, 7).takeIf { it.isNotBlank() }, Appts.upcoming(c, 7).takeIf { it.isNotBlank() })
        Prefs(c).sp.edit().putString("life_note", parts.joinToString(" ")).apply()
    }

    fun promptBlock(c: Context): String {
        if (!on(c)) return ""
        val n = listOfNotNull(Bills.upcoming(c, 7).takeIf { it.isNotBlank() }, Appts.upcoming(c, 7).takeIf { it.isNotBlank() }).joinToString(" ")
        return if (n.isBlank()) "" else "\n\nComing up in his life (Bills and Appointments keep this; mention it only when it's relevant or he asks): $n"
    }

    private fun post(key: String, body: JSONObject): JSONObject? = try {
        val k = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        k.requestMethod = "POST"; k.doOutput = true; k.connectTimeout = 10000; k.readTimeout = 60000
        k.setRequestProperty("content-type", "application/json"); k.setRequestProperty("x-api-key", key); k.setRequestProperty("anthropic-version", "2023-06-01")
        k.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = k.responseCode in 200..299
        val text = (if (ok) k.inputStream else k.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (ok) JSONObject(text) else null
    } catch (_: Exception) { null }
}

/** His bills: name, amount, due date, monthly or one-off; reminders two days before and on the morning it's due. */
object Bills {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val say = SimpleDateFormat("EEE MMM d", Locale.US)

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("bills", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = Prefs(c).sp.edit().putString("bills", a.toString()).apply()
    private fun key(n: String) = n.lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun money(v: Double) = "$" + (if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v))

    /** Same bill? Matches by name, ignoring case and spacing ("T-Mobile" = "tmobile"). Pure. */
    fun same(a: String, b: String) = key(a).isNotEmpty() && (key(a) == key(b) || key(a).startsWith(key(b)) || key(b).startsWith(key(a)))

    @Synchronized fun upsert(c: Context, name0: String, amount: Double?, due: Long, repeat: String): String {
        val name = name0.trim().replaceFirstChar { it.uppercase() }.take(40)
        if (name.length < 2) return "Needs a name."
        val a = all(c); var hit: JSONObject? = null
        for (i in 0 until a.length()) if (same(a.getJSONObject(i).optString("name"), name)) hit = a.getJSONObject(i)
        val b = hit ?: JSONObject().put("name", name).also { a.put(it) }
        if (amount != null && amount > 0) b.put("amount", amount)
        b.put("due", due).put("repeat", if (repeat == "monthly") "monthly" else "none").put("paid", false)
        schedule(c, b)
        save(c, a); LogBus.changed()
        return "${b.optString("name")}${if (b.has("amount")) " " + money(b.optDouble("amount")) else ""} due ${say.format(Date(due))}."
    }

    private fun schedule(c: Context, b: JSONObject) {
        b.optJSONArray("rem")?.let { r -> for (i in 0 until r.length()) Scheduler.cancel(c, r.optInt(i)) }
        val ids = JSONArray()
        val due = b.optLong("due"); val amt = if (b.has("amount")) " (${money(b.optDouble("amount"))})" else ""
        val name = b.optString("name")
        fun at(daysBefore: Int, hour: Int) = Calendar.getInstance().apply { timeInMillis = due; add(Calendar.DAY_OF_YEAR, -daysBefore); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 30) }.timeInMillis
        val t1 = at(2, 18); val t2 = at(0, 9)
        if (t1 > System.currentTimeMillis()) ids.put(Scheduler.add(c, t1, "Sir, your $name bill$amt is due ${say.format(Date(due))}.", loud = false, label = "$name bill"))
        if (t2 > System.currentTimeMillis()) ids.put(Scheduler.add(c, t2, "Sir, your $name bill$amt is due today.", loud = false, label = "$name bill"))
        b.put("rem", ids)
    }

    fun fromSweep(c: Context, o: JSONObject): String? {
        val name = o.optString("name").trim(); if (name.isBlank()) return null
        val due = try { day.parse(o.optString("due"))?.time } catch (_: Exception) { null } ?: return null
        if (o.optBoolean("paid")) { paid(c, name); return "Marked $name paid." }
        val amt = o.optDouble("amount", Double.NaN).takeIf { !it.isNaN() }
        val known = (0 until all(c).length()).map { all(c).getJSONObject(it) }.firstOrNull { same(it.optString("name"), name) }
        if (known != null && known.optLong("due") == due && (amt == null || known.optDouble("amount") == amt)) return null
        return "Bill: " + upsert(c, name, amt, due, o.optString("repeat"))
    }

    @Synchronized fun paid(c: Context, name: String): String {
        val a = all(c)
        for (i in 0 until a.length()) {
            val b = a.getJSONObject(i)
            if (!same(b.optString("name"), name)) continue
            b.optJSONArray("rem")?.let { r -> for (j in 0 until r.length()) Scheduler.cancel(c, r.optInt(j)) }
            if (b.optString("repeat") == "monthly") {
                val next = Calendar.getInstance().apply { timeInMillis = b.optLong("due"); add(Calendar.MONTH, 1) }.timeInMillis
                b.put("due", next).put("paid", false); schedule(c, b); save(c, a)
                return "Marked ${b.optString("name")} paid. Next one is due ${say.format(Date(next))}."
            }
            b.put("paid", true).put("rem", JSONArray()); save(c, a)
            return "Marked ${b.optString("name")} paid."
        }
        return "No bill called $name."
    }

    @Synchronized fun remove(c: Context, name: String): String {
        val a = all(c); val keep = JSONArray(); var hit: String? = null
        for (i in 0 until a.length()) { val b = a.getJSONObject(i)
            if (hit == null && same(b.optString("name"), name)) { hit = b.optString("name"); b.optJSONArray("rem")?.let { r -> for (j in 0 until r.length()) Scheduler.cancel(c, r.optInt(j)) } } else keep.put(b) }
        save(c, keep); return hit?.let { "Stopped tracking $it." } ?: "No bill called $name."
    }

    /** Monthly bills whose date has passed move on to next month (most are on autopay). */
    @Synchronized fun roll(c: Context) {
        val a = all(c); var changed = false
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        for (i in 0 until a.length()) { val b = a.getJSONObject(i)
            if (b.optString("repeat") == "monthly" && b.optLong("due") < today) {
                var d = b.optLong("due"); while (d < today) d = Calendar.getInstance().apply { timeInMillis = d; add(Calendar.MONTH, 1) }.timeInMillis
                b.put("due", d).put("paid", false); schedule(c, b); changed = true } }
        if (changed) save(c, a)
    }

    fun upcoming(c: Context, days: Int): String {
        val until = System.currentTimeMillis() + days * 86_400_000L
        val a = all(c); val l = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("paid") && it.optLong("due") in (System.currentTimeMillis() - 86_400_000L)..until }.sortedBy { it.optLong("due") }
        return if (l.isEmpty()) "" else "Bills due: " + l.joinToString("; ") { "${it.optString("name")}${if (it.has("amount")) " " + money(it.optDouble("amount")) else ""} ${say.format(Date(it.optLong("due")))}" } + "."
    }

    fun brief(c: Context): String { val a = all(c); return if (a.length() == 0) "none" else (0 until a.length()).joinToString("; ") { val b = a.getJSONObject(it); "${b.optString("name")} ${if (b.has("amount")) money(b.optDouble("amount")) else ""} due ${day.format(Date(b.optLong("due")))} ${b.optString("repeat")}" } }

    fun list(c: Context): String {
        val a = all(c); if (a.length() == 0) return "No bills found yet. I learn them from your bill texts and notifications, or you can tell me one."
        val l = (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("paid") }.sortedBy { it.optLong("due") }
        val total = l.filter { it.optLong("due") < System.currentTimeMillis() + 30 * 86_400_000L }.sumOf { it.optDouble("amount", 0.0) }
        return l.joinToString("; ") { "${it.optString("name")}${if (it.has("amount")) " " + money(it.optDouble("amount")) else ""} due ${say.format(Date(it.optLong("due")))}${if (it.optString("repeat") == "monthly") " (monthly)" else ""}" } +
            ". About ${money(total)} due in the next 30 days."
    }

    fun json(c: Context): JSONArray = all(c)
}

/** His appointments (and Ziyair's): on the calendar, a reminder the evening before and one ninety minutes before. */
object Appts {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val say = SimpleDateFormat("EEE MMM d", Locale.US)
    private val sayT = SimpleDateFormat("EEE MMM d 'at' h:mm a", Locale.US)

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("appts", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = Prefs(c).sp.edit().putString("appts", a.toString()).apply()
    private fun sameDay(a: Long, b: Long) = day.format(Date(a)) == day.format(Date(b))
    private fun words(s: String) = s.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()

    @Synchronized fun upsert(c: Context, what0: String, at: Long, hasTime: Boolean, where: String?): String {
        val what = what0.trim().take(60); if (what.length < 2) return "Needs a name."
        val a = all(c); var hit: JSONObject? = null
        for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if ((words(o.optString("what")) intersect words(what)).isNotEmpty() && (sameDay(o.optLong("at"), at) || o.optLong("at") > System.currentTimeMillis())) hit = o }
        val o = hit ?: JSONObject().also { a.put(it) }
        o.put("what", what).put("at", at).put("time", hasTime).put("where", where.orEmpty())
        // reminders
        o.optJSONArray("rem")?.let { r -> for (i in 0 until r.length()) Scheduler.cancel(c, r.optInt(i)) }
        val ids = JSONArray()
        val eve = Calendar.getInstance().apply { timeInMillis = at; add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 30) }.timeInMillis
        val whereTxt = where?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
        if (eve > System.currentTimeMillis()) ids.put(Scheduler.add(c, eve, "Sir, tomorrow: $what${if (hasTime) " at " + SimpleDateFormat("h:mm a", Locale.US).format(Date(at)) else ""}$whereTxt.", loud = false, label = what))
        if (hasTime && at - 90 * 60_000L > System.currentTimeMillis()) ids.put(Scheduler.add(c, at - 90 * 60_000L, "Sir, $what is in an hour and a half$whereTxt. Want directions?", loud = false, label = what))
        o.put("rem", ids)
        // on his calendar too
        if (!o.optBoolean("cal") && Cal.allowed(c)) try {
            Cal.add(c, what + whereTxt, day.format(Date(at)), if (hasTime) SimpleDateFormat("HH:mm", Locale.US).format(Date(at)) else null, 60); o.put("cal", true)
        } catch (_: Exception) {}
        save(c, a); LogBus.changed()
        return "$what ${if (hasTime) sayT.format(Date(at)) else say.format(Date(at))}$whereTxt."
    }

    fun fromSweep(c: Context, o: JSONObject): String? {
        val what = o.optString("what").trim(); if (what.isBlank()) return null
        val d = o.optString("date"); val t = o.optString("time").takeIf { Regex("\\d{1,2}:\\d{2}").matches(it) }
        val at = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$d ${t ?: "09:00"}")?.time } catch (_: Exception) { null } ?: return null
        if (o.optBoolean("cancelled")) { remove(c, what); return "Cancelled: $what." }
        if (at < System.currentTimeMillis() - 3600_000L) return null
        val known = (0 until all(c).length()).map { all(c).getJSONObject(it) }.firstOrNull { (words(it.optString("what")) intersect words(what)).isNotEmpty() && it.optLong("at") == at }
        if (known != null) return null
        return "Appointment: " + upsert(c, what, at, t != null, o.optString("where").takeIf { it != "null" })
    }

    @Synchronized fun remove(c: Context, what: String): String {
        val w = words(what); val a = all(c); val keep = JSONArray(); var hit: String? = null
        for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if (hit == null && w.isNotEmpty() && (words(o.optString("what")) intersect w).isNotEmpty() && o.optLong("at") > System.currentTimeMillis() - 86_400_000L) {
                hit = o.optString("what"); o.optJSONArray("rem")?.let { r -> for (j in 0 until r.length()) Scheduler.cancel(c, r.optInt(j)) } } else keep.put(o) }
        save(c, keep); return hit?.let { "Removed $it (and its reminders). If it's on your calendar, that stays until you delete it." } ?: "No appointment matching that."
    }

    fun upcoming(c: Context, days: Int): String {
        val until = System.currentTimeMillis() + days * 86_400_000L
        val a = all(c); val l = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("at") in System.currentTimeMillis()..until }.sortedBy { it.optLong("at") }
        return if (l.isEmpty()) "" else "Appointments: " + l.joinToString("; ") { "${it.optString("what")} ${if (it.optBoolean("time")) sayT.format(Date(it.optLong("at"))) else say.format(Date(it.optLong("at")))}" } + "."
    }

    fun brief(c: Context): String { val a = all(c); return if (a.length() == 0) "none" else (0 until a.length()).joinToString("; ") { val o = a.getJSONObject(it); "${o.optString("what")} ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(o.optLong("at")))}" } }

    fun list(c: Context): String {
        val l = (0 until all(c).length()).map { all(c).getJSONObject(it) }.filter { it.optLong("at") > System.currentTimeMillis() - 3600_000L }.sortedBy { it.optLong("at") }
        val base = if (l.isEmpty()) "No appointments coming up." else l.take(8).joinToString("; ") { "${it.optString("what")} ${if (it.optBoolean("time")) sayT.format(Date(it.optLong("at"))) else say.format(Date(it.optLong("at")))}${it.optString("where").takeIf { w -> w.isNotBlank() }?.let { w -> " at $w" } ?: ""}" } + "."
        return base + (checkupNote(c)?.let { " $it" } ?: "")
    }

    // ---------------- Ziyair's checkups ----------------
    /** The usual well-child visit ages, in days (his pediatrician sets the real dates). Pure. */
    val CHECKUPS = listOf(4 to "first-week", 30 to "one-month", 61 to "two-month", 122 to "four-month", 183 to "six-month", 274 to "nine-month",
        365 to "one-year", 456 to "fifteen-month", 548 to "eighteen-month", 730 to "two-year")

    /** The next usual checkup after [today] for a baby born on [born] (both midnight millis), as (date, name). Pure. */
    fun nextCheckup(born: Long, today: Long): Pair<Long, String>? =
        CHECKUPS.map { (d, n) -> born + d * 86_400_000L to n }.firstOrNull { it.first >= today - 3 * 86_400_000L }

    fun setBirthday(c: Context, date: String): String {
        val d = try { day.parse(date.trim()) } catch (_: Exception) { null } ?: return "I need the date as year-month-day."
        if (d.time > System.currentTimeMillis()) return "That date's in the future."
        Prefs(c).sp.edit().putLong("baby_born", d.time).apply()
        return "Got his birthday. " + (checkupNote(c) ?: "")
    }

    /** "Ziyair's two-month checkup is usually around Dec 20; book it if it isn't." Only once it's within six weeks and not already booked. */
    fun checkupNote(c: Context): String? {
        val born = Prefs(c).sp.getLong("baby_born", 0L).takeIf { it > 0 } ?: return null
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val (at, name) = nextCheckup(born, today) ?: return null
        if (at - today > 42 * 86_400_000L) return null
        val booked = (0 until all(c).length()).map { all(c).getJSONObject(it) }.any { Regex("(?i)check ?-?up|well|pediatric|doctor").containsMatchIn(it.optString("what")) && abs(it.optLong("at") - at) < 21 * 86_400_000L }
        if (booked) return null
        val baby = Prefs(c).babyName.ifBlank { "the baby" }
        return "$baby's $name checkup is usually around ${say.format(Date(at))}; book it with the pediatrician if it isn't already."
    }

    private fun abs(v: Long) = if (v < 0) -v else v

    fun json(c: Context): JSONArray = all(c)
}
