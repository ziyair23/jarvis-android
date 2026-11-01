package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pursuit's business book, kept on the phone: ideas (tagged design, drop, post, collab), the shoot list, launch to-dos
 * (his or his partner's) and orders. Read and written by the Pursuit agent. Never sends anything to anyone.
 */
object Pursuit {
    val KINDS = listOf("idea", "shot", "task", "order")
    private val fmt = SimpleDateFormat("MMM d", Locale.US)

    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("pursuit", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(p: Prefs, a: JSONArray) {
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 400) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("pursuit", keep.toString()).apply()
    }

    fun add(c: Context, kind0: String, text: String, tag: String?, who: String?): String {
        val kind = kind0.lowercase().trim().removeSuffix("s").let { if (it in KINDS) it else "idea" }
        val t = text.trim().removeSuffix(".")
        if (t.length < 2) return "Nothing to save."
        val p = Prefs(c); val a = all(p)
        a.put(JSONObject().put("k", kind).put("t", t).put("tag", tag?.trim()?.lowercase().orEmpty()).put("who", who?.trim().orEmpty())
            .put("at", System.currentTimeMillis()).put("done", false))
        save(p, a)
        val open = (0 until a.length()).count { a.getJSONObject(it).optString("k") == kind && !a.getJSONObject(it).optBoolean("done") }
        return "Saved to Pursuit ${label(kind)}${if (!tag.isNullOrBlank()) " ($tag)" else ""}. $open open ${label(kind)}."
    }

    fun list(c: Context, kind0: String?, query: String?): String {
        val kind = kind0?.lowercase()?.trim()?.removeSuffix("s")?.takeIf { it in KINDS }
        val q = query?.lowercase()?.split(Regex("\\W+"))?.filter { it.length > 2 }.orEmpty()
        val a = all(Prefs(c))
        val items = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("done") && (kind == null || it.optString("k") == kind) }
            .filter { q.isEmpty() || q.any { w -> (it.optString("t") + " " + it.optString("tag") + " " + it.optString("who")).lowercase().contains(w) } }
            .sortedByDescending { it.optLong("at") }
        if (items.isEmpty()) return "Nothing open in Pursuit ${kind?.let { label(it) } ?: "yet"}${if (q.isNotEmpty()) " about ${query}" else ""}."
        return "${items.size} open: " + items.take(8).joinToString("; ") { e ->
            "${e.optString("t")}${e.optString("tag").takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""}${e.optString("who").takeIf { it.isNotBlank() }?.let { " (for $it)" } ?: ""}, ${fmt.format(Date(e.optLong("at")))}"
        } + if (items.size > 8) "; and ${items.size - 8} more." else "."
    }

    fun done(c: Context, text: String): String {
        val w = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (w.isEmpty()) return "Which one?"
        val p = Prefs(c); val a = all(p); var hit: String? = null
        for (i in a.length() - 1 downTo 0) {
            val e = a.getJSONObject(i)
            if (!e.optBoolean("done") && w.all { e.optString("t").lowercase().contains(it) }) { e.put("done", true); hit = e.optString("t"); break }
        }
        if (hit == null) return "Couldn't find an open Pursuit item matching that."
        save(p, a); return "Ticked off: $hit."
    }

    private fun label(k: String) = when (k) { "shot" -> "shoot list"; "task" -> "launch to-dos"; "order" -> "orders"; else -> "ideas" }
}
