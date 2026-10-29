package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lists by voice: "add diapers and wipes to the list", "what's on my list", "got the wipes".
 * The main list is "list" (shopping); he can have others ("to-do", "baby").
 * Stored as {"list":[{"t":"diapers","done":false,"at":ms}], "to-do":[...]}.
 */
object Lists {
    fun all(p: Prefs): JSONObject = try { JSONObject(p.sp.getString("lists", "{}")) } catch (_: Exception) { JSONObject() }
    private fun save(p: Prefs, o: JSONObject) = p.sp.edit().putString("lists", o.toString()).apply()

    private fun key(name: String?): String {
        val n = name?.trim()?.lowercase()?.removePrefix("my ")?.removePrefix("the ")?.removeSuffix(" list").orEmpty()
        return when (n) { "", "list", "shopping", "grocery", "groceries", "store" -> "list"; "todo", "to do", "to-do", "tasks" -> "to-do"; else -> n }
    }
    private fun label(k: String) = if (k == "list") "the list" else "the $k list"

    private fun split(items: String) = items.split(Regex("[,\\n;]")).map { it.trim().trim('.') }.filter { it.isNotBlank() }

    fun add(context: Context, items: String, list: String?): String {
        val p = Prefs(context); val o = all(p); val k = key(list)
        val a = o.optJSONArray(k) ?: JSONArray()
        val added = mutableListOf<String>()
        for (it in split(items)) {
            val exists = (0 until a.length()).any { i -> a.getJSONObject(i).optString("t").equals(it, true) && !a.getJSONObject(i).optBoolean("done") }
            if (!exists) { a.put(JSONObject().put("t", it).put("done", false).put("at", System.currentTimeMillis())); added.add(it) }
        }
        o.put(k, a); save(p, o)
        return if (added.isEmpty()) "Already on ${label(k)}." else "Added ${added.joinToString(", ")} to ${label(k)}. ${count(a)} things on it now."
    }

    /** Ticks items off (got them / done). */
    fun check(context: Context, items: String, list: String?): String {
        val p = Prefs(context); val o = all(p); val k = key(list)
        val a = o.optJSONArray(k) ?: return "Nothing on ${label(k)}."
        val hit = mutableListOf<String>()
        val wanted = split(items).map { it.lowercase() }
        val everything = wanted.any { it in setOf("everything", "all", "all of it", "the whole list") }
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i); val t = e.optString("t").lowercase()
            if (!e.optBoolean("done") && (everything || wanted.any { w -> t.contains(w) || w.contains(t) })) { e.put("done", true); hit.add(e.optString("t")) }
        }
        o.put(k, a); save(p, o)
        return if (hit.isEmpty()) "Didn't find that on ${label(k)}." else "Checked off ${hit.joinToString(", ")}. ${count(a)} left."
    }

    fun read(context: Context, list: String?): String {
        val o = all(Prefs(context)); val k = key(list)
        val a = o.optJSONArray(k)
        val open = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("done") }.map { it.optString("t") }
        val others = o.keys().asSequence().filter { it != k && count(o.optJSONArray(it)) > 0 }.toList()
        return (if (open.isEmpty()) "${label(k).replaceFirstChar { it.uppercase() }} is empty." else "On ${label(k)}: ${open.joinToString(", ")}.") +
            (if (others.isNotEmpty()) " Other lists: ${others.joinToString(", ")}." else "")
    }

    /** Clears ticked items (or everything). */
    fun clear(context: Context, list: String?, everything: Boolean): String {
        val p = Prefs(context); val o = all(p); val k = key(list)
        val a = o.optJSONArray(k) ?: return "Nothing on ${label(k)}."
        val keep = JSONArray(); if (!everything) for (i in 0 until a.length()) if (!a.getJSONObject(i).optBoolean("done")) keep.put(a.get(i))
        o.put(k, keep); save(p, o)
        return if (everything) "Cleared ${label(k)}." else "Cleared the ticked-off items."
    }

    fun toggle(context: Context, list: String, at: Long) {
        val p = Prefs(context); val o = all(p); val a = o.optJSONArray(list) ?: return
        for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optLong("at") == at) e.put("done", !e.optBoolean("done")) }
        o.put(list, a); save(p, o)
    }

    private fun count(a: JSONArray?) = if (a == null) 0 else (0 until a.length()).count { !a.getJSONObject(it).optBoolean("done") }
}

/**
 * What dashing costs him (gas, car wash, food on shift...), so Jarvis can say what he actually kept, and the Macan
 * goal counts real money. Stored as [{"d":"2026-10-28","a":40.0,"w":"gas","at":ms}].
 */
object Expenses {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("expenses", "[]")) } catch (_: Exception) { JSONArray() }

    fun log(context: Context, amount: Double, what: String?, whichDay: String?): String {
        val p = Prefs(context); val a = all(p)
        val d = when (whichDay?.trim()?.lowercase()) { "yesterday" -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.let { day.format(it.time) }
            null, "", "today", "tonight" -> day.format(Date()); else -> whichDay.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: day.format(Date()) }
        val w = what?.trim()?.lowercase().orEmpty().ifBlank { "other" }
        a.put(JSONObject().put("d", d).put("a", amount).put("w", w).put("at", System.currentTimeMillis()))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 2000) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("expenses", keep.toString()).apply()
        return "Logged ${Earnings.money(amount)} for $w. " + week(context)
    }

    fun delete(context: Context, at: Long) {
        val p = Prefs(context); val a = all(p); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") != at) keep.put(a.get(i))
        p.sp.edit().putString("expenses", keep.toString()).apply()
    }

    fun total(p: Prefs, from: String = "0000"): Double {
        val a = all(p); var t = 0.0
        for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optString("d") >= from) t += e.optDouble("a") }
        return t
    }

    fun weekStart(): String { val c = Calendar.getInstance(); c.firstDayOfWeek = Calendar.MONDAY; c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); return day.format(c.time) }

    /** "This week: made $540, spent $85 (gas $70, food $15), kept $455." */
    fun week(context: Context): String {
        val p = Prefs(context); val ws = weekStart()
        val e = Earnings.all(p); var made = 0.0
        for (i in 0 until e.length()) { val x = e.getJSONObject(i); if (x.optString("d") >= ws) made += x.optDouble("a") }
        val a = all(p); val by = LinkedHashMap<String, Double>()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("d") >= ws) by[x.optString("w")] = (by[x.optString("w")] ?: 0.0) + x.optDouble("a") }
        val spent = by.values.sum()
        val detail = if (by.isEmpty()) "" else " (" + by.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${Earnings.money(it.value)}" } + ")"
        return "This week: made ${Earnings.money(made)}, spent ${Earnings.money(spent)}$detail, kept ${Earnings.money(made - spent)}."
    }
}
