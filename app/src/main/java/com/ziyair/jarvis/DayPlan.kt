package com.ziyair.jarvis

import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * His day at a glance, for the lock screen: what's on Jarvis's mind (one line) and what's coming up today, in order.
 * Built on the phone from what Jarvis already knows (calendar, reminders, Ziyair's next feed, his shifts, bills,
 * appointments, dinner, deliveries, follow-ups), so it's always current and costs nothing. Rebuilt every ten minutes
 * and whenever the screen comes on.
 */
object DayPlan {
    class Item(val at: Long, val text: String)

    private val clock = SimpleDateFormat("h:mm a", Locale.US)

    /** What's left of today (and the small hours), in time order, without duplicates. Pure, unit-tested. */
    fun pick(items: List<Item>, now: Long, max: Int = 6): List<Item> {
        val end = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0) }.timeInMillis
        val seen = HashSet<String>()
        return items.filter { it.at in (now - 20 * 60_000L)..end }.sortedBy { it.at }
            .filter { seen.add(it.text.lowercase()) }.take(max)
    }

    /** "On his mind" in one short line: his headline, or the first sentence of his latest thought. Pure. */
    fun headline(thought: JSONObject?): String {
        thought ?: return "Your day, at a glance."
        thought.optString("headline").takeIf { it.isNotBlank() }?.let { return it.take(90) }
        val t = thought.optString("thought").trim()
        val first = Regex("^.+?[.!?](\\s|$)").find(t)?.value?.trim() ?: t
        return if (first.length <= 90) first else first.take(87).trimEnd() + "…"
    }

    private fun at(base: Calendar, hm: String): Long? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(hm) ?: return null
        return (base.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt()); set(Calendar.SECOND, 0) }.timeInMillis
    }

    fun items(c: Context): List<Item> {
        val p = Prefs(c); val now = System.currentTimeMillis()
        val today = Calendar.getInstance()
        val out = ArrayList<Item>()
        fun safe(f: () -> Unit) { try { f() } catch (_: Exception) {} }
        // calendar
        safe {
            if (Cal.allowed(c)) {
                val start = (today.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, start + 27 * 3600_000L) }.build()
                c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { q ->
                    while (q.moveToNext() && out.size < 20) if (q.getInt(2) != 1) out.add(Item(q.getLong(1), q.getString(0).orEmpty().take(40)))
                }
            }
        }
        // reminders and timers
        safe { val r = p.reminders(); for (i in 0 until r.length()) { val o = r.getJSONObject(i); out.add(Item(o.optLong("at"), o.optString("label").ifBlank { "Reminder" }.take(40))) } }
        // Ziyair's next feed
        safe {
            val b = Baby.all(p)
            val last = (b.length() - 1 downTo 0).map { b.getJSONObject(it) }.firstOrNull { it.optString("k") == "feed" }
            if (last != null) { val next = last.optLong("at") + (Baby.feedHours(p) * 3600_000L).toLong(); if (next > now - 3 * 3600_000L) out.add(Item(maxOf(next, now), "Feed ${p.babyName.ifBlank { "the baby" }}")) }
        }
        // his shifts, only on days he's dashing
        if (try { DashDays.dashing(c) } catch (_: Exception) { false }) safe {
            val sh = Brain(c).config().optJSONArray("shifts") ?: JSONArray("[[\"07:00\",\"11:00\"],[\"17:00\",\"21:00\"]]")
            for (i in 0 until sh.length()) { val s = sh.getJSONArray(i); at(today, s.getString(0))?.let { t -> out.add(Item(t, "Dash until ${clock.format(Date(at(today, s.getString(1)) ?: t)).removeSuffix(":00 PM").removeSuffix(":00 AM")}")) } }
        }
        // bills due today or tomorrow morning
        safe { val a = Bills.all(c); for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if (!o.optBoolean("paid") && o.optLong("due") in (now - 12 * 3600_000L)..(now + 24 * 3600_000L))
                out.add(Item(maxOf(o.optLong("due"), now), "${o.optString("name")}${if (o.has("amount")) " $" + String.format(Locale.US, "%.2f", o.optDouble("amount")).removeSuffix(".00") else ""} due")) } }
        // appointments
        safe { val a = Appts.all(c); for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Item(o.optLong("at"), o.optString("what").take(40))) } }
        // dinner
        safe { Meals.plan(c).optJSONObject("meals")?.optJSONObject(SimpleDateFormat("EEEE", Locale.US).format(Date()))?.let { m -> at(today, "18:00")?.let { out.add(Item(it, "Dinner: ${m.optString("name").take(34)}")) } } }
        // deliveries coming today
        safe { val a = Packages.all(c); for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if (o.optString("status") == "out" || (o.optString("eta") == "today" && o.optString("status") != "delivered")) out.add(Item(maxOf(now, o.optLong("at")), "Arriving: ${o.optString("item").take(34)}")) } }
        // follow-ups due today
        safe { Loops.open(Loops.all(c)).forEach { o -> out.add(Item(maxOf(now, o.optLong("due")), "Follow up: ${o.optString("what").take(36)}")) } }
        return out
    }

    /** Rebuilds the lock-screen card and saves it (the wallpaper and the notification read it). */
    fun save(c: Context): JSONObject {
        val th = Mind.thoughts(c)
        val last = if (th.length() > 0) th.getJSONObject(th.length() - 1) else null
        val now = System.currentTimeMillis()
        val list = pick(items(c), now)
        val shop = try { Lists.all(Prefs(c)).optJSONArray("list")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("done") }.map { it.optString("t") } } } catch (_: Exception) { null }.orEmpty()
        val mood = Mood.now(c)
        val card = JSONObject().put("at", now).put("list", JSONArray(shop.takeLast(12))).put("mood", mood.first.toDouble()).put("moodWhy", JSONArray(mood.second)).put("headline", if (Mind.thinking) "Thinking things over…" else headline(last))
            .put("thoughtAt", last?.optLong("at") ?: 0L)
            .put("items", JSONArray().apply { list.forEach { put(JSONObject().put("t", clock.format(Date(it.at))).put("x", it.text)) } })
        Prefs(c).sp.edit().putString("lock_card", card.toString()).apply()
        return card
    }

    fun card(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("lock_card", "{}")) } catch (_: Exception) { JSONObject() }
}

/**
 * The brain's mood: how his day actually feels, from what Jarvis already knows. Calm and deep when things are on
 * track; warmer and quicker when a bill is due, something slipped, the night was rough or the day is packed.
 */
object Mood {
    /** 0 = calm, 1 = pressing, and the reasons. Pure, unit-tested. */
    fun score(billsSoon: Int, overdue: Int, nightFeeds: Int, eventsToday: Int, behindGoal: Boolean): Pair<Float, List<String>> {
        var m = 0f; val why = ArrayList<String>()
        if (billsSoon > 0) { m += minOf(0.5f, 0.25f * billsSoon); why.add("bill due soon") }
        if (overdue > 0) { m += minOf(0.3f, 0.15f * overdue); why.add("something slipped") }
        if (nightFeeds >= 2) { m += 0.2f; why.add("rough night") }
        if (eventsToday >= 4) { m += 0.15f; why.add("packed day") }
        if (behindGoal) { m += 0.1f; why.add("behind today") }
        return m.coerceIn(0f, 1f) to why
    }

    fun word(m: Float) = when { m < 0.2f -> "steady"; m < 0.5f -> "busy"; else -> "pressing" }

    fun now(c: Context): Pair<Float, List<String>> = try {
        val now = System.currentTimeMillis()
        val bills = Bills.all(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.count { !it.optBoolean("paid") && it.optLong("due") in (now - 86_400_000L)..(now + 2 * 86_400_000L) } }
        val overdue = Loops.open(Loops.all(c)).count { it.optLong("due") < now }
        val midnight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val feeds = Baby.all(Prefs(c)).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.count { it.optString("k") == "feed" && it.optLong("at") in midnight..(midnight + 5 * 3600_000L) } }
        val events = DayPlan.pick(DayPlan.items(c), now, 20).size
        val p = Prefs(c)
        val behind = DashDays.dashing(c) && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 20 && Earnings.today(c) < p.sp.getFloat("daily_target", 120f) * 0.7
        score(bills, overdue, feeds, events, behind)
    } catch (_: Exception) { 0f to emptyList() }
}
