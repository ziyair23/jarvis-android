package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Inbox agent: reads his Gmail (read only, it can never send, delete or change anything) for bills, receipts
 * and deliveries. Bills and appointments go to the Bills/Appointments agents' inbox, deliveries to Packages, and
 * receipts are kept here. Checks about every 30 minutes when he uses the phone, and whenever he asks.
 */
object Gmail {
    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val RECEIPT = Regex("(?i)\\b(receipt|order confirm|order #|order no|your order|thanks for your order|thank you for your order|payment received|we received your payment|invoice paid|purchase confirmation|you paid)\\b")
    private val MONEY = Regex("\\$\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)")
    private val TOTAL = Regex("(?i)\\b(?:order total|grand total|total charged|amount paid|total)\\b[^$]{0,20}\\$\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)")

    data class Receipt(val store: String, val amount: Double?)

    // ---------------- pure rules (unit-tested) ----------------
    /** "Amazon.com <auto-confirm@amazon.com>" -> "Amazon.com"; "bills@avista.com" -> "avista". */
    fun senderName(from: String): String {
        val name = from.substringBefore('<').trim().trim('"')
        if (name.isNotBlank() && !name.contains('@')) return name.take(40)
        val mail = from.substringAfter('<').substringBefore('>').trim()
        return mail.substringAfter('@').substringBeforeLast('.').substringAfterLast('.').ifBlank { mail }.take(40)
    }

    /** A receipt or order confirmation, with the total when it shows one. Null if it isn't one. */
    fun receipt(from: String, subject: String, snippet: String): Receipt? {
        val all = "$subject $snippet"
        if (!RECEIPT.containsMatchIn(all) || LifeWatch.workApp(from)) return null
        if (Regex("(?i)\\b(shipped|out for delivery|delivered|has shipped)\\b").containsMatchIn(subject)) return null
        val amt = (TOTAL.find(all) ?: MONEY.find(all))?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        return Receipt(senderName(from), amt?.takeIf { it > 0 && it < 20000 })
    }

    // ---------------- calls ----------------
    fun on(c: Context) = GoogleAuth.connected(c, GoogleAuth.GOOGLE)

    fun profile(c: Context): String? {
        val (code, body) = GoogleAuth.api(c, GoogleAuth.GOOGLE, "GET", "$API/profile")
        return if (code == 200) JSONObject(body).optString("emailAddress") else null
    }

    private class Msg(val id: String, val from: String, val subject: String, val snippet: String, val at: Long)

    private fun fetch(c: Context, query: String, max: Int): List<Msg>? {
        val (code, body) = GoogleAuth.api(c, GoogleAuth.GOOGLE, "GET", "$API/messages?maxResults=$max&q=" + URLEncoder.encode(query, "UTF-8"))
        if (code != 200) return null
        val ids = JSONObject(body).optJSONArray("messages") ?: return emptyList()
        return (0 until ids.length()).mapNotNull { i -> message(c, ids.getJSONObject(i).optString("id")) }
    }

    private fun message(c: Context, id: String): Msg? {
        val (code, body) = GoogleAuth.api(c, GoogleAuth.GOOGLE, "GET", "$API/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
        if (code != 200) return null
        val j = JSONObject(body)
        val h = j.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        fun head(n: String) = (0 until h.length()).map { h.getJSONObject(it) }.firstOrNull { it.optString("name").equals(n, true) }?.optString("value").orEmpty()
        val snip = android.text.Html.fromHtml(j.optString("snippet"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        return Msg(id, head("From"), head("Subject"), snip, j.optString("internalDate").toLongOrNull() ?: System.currentTimeMillis())
    }

    private fun seen(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("gmail_seen", "[]")) } catch (_: Exception) { JSONArray() }
    fun receipts(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString("gmail_receipts", "[]")) } catch (_: Exception) { JSONArray() }

    @Volatile private var busy = false

    fun maybeSweep(c: Context) {
        if (!on(c) || busy) return
        if (System.currentTimeMillis() - Prefs(c).sp.getLong("gmail_swept", 0L) < 30 * 60_000L) return
        sweep(c)
    }

    /** Reads new mail from the last few days and hands it to the right agents. Returns a sentence. Blocking. */
    fun sweep(c: Context): String {
        if (!on(c)) return "Gmail isn't connected. Tap Connect Google in setup."
        if (busy) return "Already reading your email."
        busy = true; Agents.active["inbox"] = System.currentTimeMillis()
        try {
            val p = Prefs(c)
            val msgs = fetch(c, "newer_than:3d -category:social -category:promotions -in:sent -in:chats", 25)
                ?: return "Couldn't reach Gmail${GoogleAuth.lastError.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}."
            p.sp.edit().putLong("gmail_swept", System.currentTimeMillis()).apply()
            val seen = seen(c); val seenSet = (0 until seen.length()).map { seen.optString(it) }.toHashSet()
            var bills = 0; var pkgs = 0; val rec = receipts(c); val newRec = ArrayList<String>()
            for (m in msgs.sortedBy { it.at }) {
                if (m.id in seenSet) continue
                seen.put(m.id); seenSet.add(m.id)
                val app = "Gmail " + senderName(m.from)
                val before = LifeWatch.inbox(c).length()
                LifeWatch.capture(c, app, m.subject, m.snippet, m.at)
                if (LifeWatch.inbox(c).length() > before) bills++
                val pBefore = Packages.all(c).toString()
                Packages.capture(c, app, m.subject, m.snippet)
                if (Packages.all(c).toString() != pBefore) pkgs++
                receipt(m.from, m.subject, m.snippet)?.let { r ->
                    rec.put(JSONObject().put("at", m.at).put("store", r.store).put("amount", r.amount ?: JSONObject.NULL).put("subject", m.subject.take(80)))
                    newRec.add(r.store + (r.amount?.let { " ${money(it)}" } ?: ""))
                    Timeline.add(c, "receipt", "Receipt from ${r.store}" + (r.amount?.let { ", ${money(it)}" } ?: ""), m.at)
                }
            }
            val keepSeen = JSONArray(); for (i in maxOf(0, seen.length() - 300) until seen.length()) keepSeen.put(seen.get(i))
            val keepRec = JSONArray(); for (i in maxOf(0, rec.length() - 200) until rec.length()) keepRec.put(rec.get(i))
            p.sp.edit().putString("gmail_seen", keepSeen.toString()).putString("gmail_receipts", keepRec.toString()).apply()
            val parts = listOfNotNull(
                bills.takeIf { it > 0 }?.let { "$it bill or appointment email${if (it > 1) "s" else ""} for the Bills agent" },
                pkgs.takeIf { it > 0 }?.let { "$it delivery update${if (it > 1) "s" else ""}" },
                newRec.takeIf { it.isNotEmpty() }?.let { "receipts: ${it.take(4).joinToString(", ")}" })
            val out = if (parts.isEmpty()) "Nothing new in your email about bills, receipts or deliveries." else "From your email: " + parts.joinToString("; ") + "."
            Trace.log(c, "gmail", out)
            LogBus.changed()
            return out
        } catch (e: Exception) { Problems.log(c, "gmail sweep: ${e.message}"); return "Couldn't finish reading email: ${e.message}"
        } finally { busy = false; Agents.active.remove("inbox") }
    }

    /** Receipts from the last [days] days, newest first. */
    fun receiptsSummary(c: Context, days: Int = 7): String {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val r = receipts(c); val list = (0 until r.length()).map { r.getJSONObject(it) }.filter { it.optLong("at") >= since }.sortedByDescending { it.optLong("at") }
        if (list.isEmpty()) return if (on(c)) "No receipts in your email in the last $days days." else "Gmail isn't connected."
        val total = list.sumOf { it.optDouble("amount", 0.0).takeIf { a -> !a.isNaN() } ?: 0.0 }
        val d = SimpleDateFormat("EEE", Locale.US)
        return "${list.size} receipts in $days days, about ${money(total)} in all: " +
            list.take(8).joinToString("; ") { "${d.format(Date(it.optLong("at")))} ${it.optString("store")}" + (if (it.isNull("amount")) "" else " ${money(it.optDouble("amount"))}") } + "."
    }

    /** Looks something up in his mail (read only). */
    fun search(c: Context, query: String): String {
        if (!on(c)) return "Gmail isn't connected. Tap Connect Google in setup."
        val msgs = fetch(c, query, 5) ?: return "Couldn't reach Gmail."
        if (msgs.isEmpty()) return "No emails matching \"$query\"."
        val d = SimpleDateFormat("MMM d", Locale.US)
        return msgs.joinToString("\n") { "- ${d.format(Date(it.at))}, ${senderName(it.from)}: ${it.subject.take(90)}. ${it.snippet.take(160)}" }
    }

    private fun money(v: Double) = "$" + String.format(Locale.US, "%.2f", v)
}
