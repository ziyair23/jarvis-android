package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the whole team knows, in one place: every agent (and Jarvis's own mind, and whole jobs) reads the same picture
 * of his life, so Bills knows about payday, Meals knows about his shifts and money, Appointments knows Ziyair's day.
 * Built on the phone from what Jarvis already keeps (no Claude call), plus short notes agents leave each other.
 */
object Team {
    private const val KEEP_MS = 4 * 86_400_000L

    fun notes(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("team_notes", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized fun note(c: Context, from: String, text: String): String {
        val t = text.trim().take(200)
        if (t.length < 4 || Memory.sensitive(t)) return "Not saved."
        val now = System.currentTimeMillis()
        val a = notes(c); val keep = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if (now - o.optLong("at") < KEEP_MS && !o.optString("t").equals(t, true)) keep.put(o) }
        keep.put(JSONObject().put("at", now).put("from", from).put("t", t))
        val trimmed = JSONArray(); for (i in maxOf(0, keep.length() - 30) until keep.length()) trimmed.put(keep.get(i))
        Prefs(c).sp.edit().putString("team_notes", trimmed.toString()).apply()
        return "Noted for the team."
    }

    /** Notes from the last few days, newest last (pure over the data). */
    fun recentNotes(a: JSONArray, now: Long): List<String> {
        val f = SimpleDateFormat("EEE h:mm a", Locale.US)
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { now - it.optLong("at") < KEEP_MS }
            .map { "${f.format(Date(it.optLong("at")))} ${it.optString("from")}: ${it.optString("t")}" }
    }

    /** Everything the team knows right now, compact. Each part is optional: one failing never breaks the rest. */
    fun brief(c: Context): String {
        val p = Prefs(c)
        val parts = ArrayList<String>()
        fun add(label: String, f: () -> String?) { try { f()?.trim()?.takeIf { it.isNotBlank() && it != "none" }?.let { parts.add("$label: ${it.take(500)}") } } catch (_: Exception) {} }
        add("About him") { p.notes.take(400) }
        add("Dashing") { DashDays.line(c) }
        add("Ziyair") { Baby.status(c) }
        add("Earned today") { Earnings.money(Earnings.today(c)) }
        add("Earnings") { Earnings.summary(c) }
        add("Goal") { Earnings.goal(p).takeIf { it > 0 }?.let { "${Earnings.money(it)} for ${Earnings.goalName(p)}" } }
        add("Spending") { Spending.week(c).takeIf { !it.contains("No purchases") } }
        add("Expenses") { Expenses.week(c) }
        add("Bills, next 2 weeks") { Bills.upcoming(c, 14) }
        add("Appointments, next 2 weeks") { Appts.upcoming(c, 14) }
        add("Calendar, today and tomorrow") { if (Cal.allowed(c)) Cal.events(c, null, 2) else null }
        add("Reminders set") { Scheduler.describe(c).takeIf { !it.startsWith("Nothing") } }
        add("Shopping list") { Lists.read(c, "list") }
        add("Meal plan") { Meals.week(c).takeIf { !it.startsWith("No meal") } }
        add("Deliveries") { Packages.today(c) }
        add("Price watch") { Supplies.deal(c) }
        add("Open follow-ups") { Loops.brief(c) }
        add("Notes between agents") { recentNotes(notes(c), System.currentTimeMillis()).takeLast(8).joinToString("; ").ifBlank { null } }
        return parts.joinToString("\n").take(5000)
    }
}

/**
 * Follow-through: things that need chasing over days. A reply he's waiting on, a delivery that should have come,
 * something due by a date. Jarvis keeps them open, closes them himself when the reply or package arrives, and
 * brings up the ones that slipped (once a day, never more).
 */
object Loops {
    val TOOLS = setOf("follow_up_add", "follow_ups_list", "follow_up_done")
    private const val DAY = 86_400_000L

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("loops", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { Prefs(c).sp.edit().putString("loops", a.toString()).apply(); LogBus.changed() }
    fun open(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "open" }

    /** When it's due: in_hours, or date (yyyy-MM-dd) with an optional time (HH:mm, else 6 PM). Pure. */
    fun dueTime(input: JSONObject, now: Long): Long? {
        if (input.has("in_hours")) return now + (input.optDouble("in_hours", 24.0).coerceIn(0.25, 24.0 * 30) * 3600_000L).toLong()
        val d = input.optString("date").takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: return null
        val t = input.optString("time").takeIf { Regex("\\d{1,2}:\\d{2}").matches(it) } ?: "18:00"
        return try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$d $t")?.time } catch (_: Exception) { null }
    }

    @Synchronized fun add(c: Context, input: JSONObject): String {
        val what = input.optString("what").trim().take(160)
        if (what.length < 3) return "What should I follow up on?"
        val now = System.currentTimeMillis()
        val kind = input.optString("kind").takeIf { it in setOf("reply", "delivery", "date", "task") } ?: "task"
        val due = dueTime(input, now) ?: (now + if (kind == "reply") DAY else 2 * DAY)
        val a = all(c)
        open(a).firstOrNull { it.optString("what").equals(what, true) }?.let { it.put("due", due); save(c, a); return "Already following that; moved it to ${say(due)}." }
        a.put(JSONObject().put("id", now).put("at", now).put("what", what).put("kind", kind).put("who", input.optString("who").trim())
            .put("due", due).put("then", input.optString("then").ifBlank { "remind" }).put("status", "open").put("nudged", 0L))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 60) until a.length()) keep.put(a.get(i))
        save(c, keep)
        return "Following up: $what, by ${say(due)}."
    }

    private fun say(t: Long) = SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(t))

    fun list(c: Context): String {
        val l = open(all(c)).sortedBy { it.optLong("due") }
        return if (l.isEmpty()) "Nothing open to follow up on." else "Open: " + l.joinToString("; ") { "${it.optString("what")} (by ${say(it.optLong("due"))})" } + "."
    }

    fun brief(c: Context): String? = open(all(c)).sortedBy { it.optLong("due") }.takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { "${it.optString("what")} by ${say(it.optLong("due"))}${if (it.optLong("due") < System.currentTimeMillis()) " (overdue)" else ""}" }

    /** Words that pick out which loop he means (pure). */
    fun matches(o: JSONObject, q: String): Boolean {
        val words = q.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length >= 3 && it !in setOf("the", "and", "for", "about", "from", "that", "with") }
        if (words.isEmpty()) return false
        val hay = (o.optString("what") + " " + o.optString("who")).lowercase()
        return words.count { hay.contains(it) } * 2 >= words.size
    }

    @Synchronized fun done(c: Context, q: String, why: String = "done"): String {
        val a = all(c); val hit = open(a).firstOrNull { it.optLong("id").toString() == q.trim() || matches(it, q) } ?: return "No open follow-up like that."
        hit.put("status", "closed").put("closed", System.currentTimeMillis()).put("why", why); save(c, a)
        return "Closed: ${hit.optString("what")}."
    }

    /** Does this message sender match who he's waiting on? "Jesse" matches "Jesse Ramirez", "jesse r", not "Jessica". Pure. */
    fun sameWho(who: String, sender: String): Boolean {
        val w = who.lowercase().trim(); val s = sender.lowercase().trim()
        if (w.length < 2 || s.isBlank()) return false
        return s == w || s.split(Regex("\\s+")).contains(w) || w.split(Regex("\\s+")).contains(s) || s.startsWith("$w ")
    }

    /** A message came in: whoever he was waiting on has answered. */
    @Synchronized fun onMessage(c: Context, sender: String) {
        val a = all(c); var changed = false
        for (o in open(a)) if (o.optString("kind") == "reply" && sameWho(o.optString("who"), sender)) {
            o.put("status", "closed").put("closed", System.currentTimeMillis()).put("why", "$sender replied"); changed = true
            Trace.log(c, "loops", "closed: ${o.optString("what")} ($sender replied)")
        }
        if (changed) save(c, a)
    }

    /** A package landed: close what was waiting on it. */
    @Synchronized fun onDelivered(c: Context, item: String) {
        val a = all(c); var changed = false
        for (o in open(a)) if (o.optString("kind") == "delivery" && (matches(o, item) || item == "a package")) {
            o.put("status", "closed").put("closed", System.currentTimeMillis()).put("why", "delivered"); changed = true
        }
        if (changed) save(c, a)
    }

    /** Open, past due, and not brought up in the last 20 hours. Pure. */
    fun overdue(l: List<JSONObject>, now: Long): List<JSONObject> =
        l.filter { it.optString("status") == "open" && it.optLong("due") < now && now - it.optLong("nudged") > 20 * 3600_000L }

    /** The line Jarvis says about one that slipped. Pure. */
    fun nudgeLine(o: JSONObject): String {
        val who = o.optString("who"); val what = o.optString("what").trimEnd('.')
        return when (o.optString("kind")) {
            "reply" -> "Sir, ${who.ifBlank { "they" }} still hasn't got back to you: $what." + if (o.optString("then") == "draft") " Want me to send a nudge?" else ""
            "delivery" -> "Sir, still nothing on $what. It should have come by now."
            else -> "Sir, a follow-up: $what." + if (o.optString("then") == "draft" && who.isNotBlank()) " Want me to message $who?" else ""
        }
    }

    /** Daytime check: closes stale ones (two weeks), returns one line to say about the most overdue, or null. */
    @Synchronized fun sweep(c: Context): String? {
        val now = System.currentTimeMillis()
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val a = all(c); var changed = false
        for (o in open(a)) if (now - o.optLong("at") > 14 * DAY) { o.put("status", "closed").put("why", "expired"); changed = true }
        var line: String? = null
        if (h in 8..21) overdue(open(a), now).minByOrNull { it.optLong("due") }?.let { o -> o.put("nudged", now); changed = true; line = nudgeLine(o) }
        if (changed) save(c, a)
        return line
    }
}
