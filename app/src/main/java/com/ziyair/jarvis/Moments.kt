package com.ziyair.jarvis

import android.app.Notification
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Baby journal: little moments ("he smiled today") kept with dates to look back on. */
object Journal {
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("journal", "[]")) } catch (_: Exception) { JSONArray() }

    fun add(context: Context, text: String): String {
        val p = Prefs(context); val a = all(p)
        Timeline.add(context, "journal", text)
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("t", text.trim()))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 1000) until a.length()) keep.put(a.get(i))
        p.sp.edit().putString("journal", keep.toString()).apply()
        return "Saved to the baby journal."
    }

    fun delete(context: Context, at: Long) {
        val p = Prefs(context); val a = all(p); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("at") != at) keep.put(a.get(i))
        p.sp.edit().putString("journal", keep.toString()).apply()
    }

    fun read(context: Context, search: String?): String {
        val a = all(Prefs(context))
        val f = SimpleDateFormat("EEE MMM d", Locale.US)
        val w = search?.trim()?.lowercase().orEmpty()
        val items = (0 until a.length()).map { a.getJSONObject(it) }.filter { w.isBlank() || it.optString("t").lowercase().contains(w) }.takeLast(12)
        return if (items.isEmpty()) "Nothing in the journal${if (w.isNotBlank()) " about $search" else ""} yet."
            else items.joinToString("; ") { f.format(Date(it.optLong("at"))) + ": " + it.optString("t") }
    }
}

/** "Remind me when I get home": said out loud with the welcome-home greeting. */
object HomeReminders {
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("home_reminders", "[]")) } catch (_: Exception) { JSONArray() }
    fun add(context: Context, text: String): String {
        val p = Prefs(context); val a = all(p); a.put(text.trim())
        p.sp.edit().putString("home_reminders", a.toString()).apply()
        return "I'll remind him when he gets home (when the phone joins home Wi-Fi)."
    }
    /** Returns and clears them. */
    fun take(context: Context): List<String> {
        val p = Prefs(context); val a = all(p)
        p.sp.edit().putString("home_reminders", "[]").apply()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
}

/** The phone's ringing-call notification, so Jarvis can answer or decline by voice. */
object CallControl {
    @Volatile private var answer: Notification.Action? = null
    @Volatile private var decline: Notification.Action? = null
    @Volatile private var at = 0L

    fun ringing(answer: Notification.Action?, decline: Notification.Action?) { this.answer = answer; this.decline = decline; at = System.currentTimeMillis() }
    fun ringingNow() = System.currentTimeMillis() - at < 45_000 && (answer != null || decline != null)
    fun ended() { answer = null; decline = null; at = 0L }

    fun answer(context: Context): String = fire(context, answer, "Answered.")
    fun decline(context: Context): String = fire(context, decline, "Declined.")

    private fun fire(context: Context, a: Notification.Action?, ok: String): String {
        if (!ringingNow() || a == null) return "Nothing's ringing right now."
        return try { a.actionIntent.send(); ended(); ok } catch (_: Exception) { "The phone app didn't respond." }
    }
}

/**
 * DoorDash order judge: when a Dasher offer is on screen, Jarvis reads the pay and miles and gives a verdict
 * against his rule (default $2 a mile). He still taps Accept or Decline himself.
 */
object OfferJudge {
    const val DASHER = "com.doordash.driverapp"
    private var lastSig = ""; private var lastAt = 0L; private var lastCheck = 0L; private var lastMiss = 0L

    fun minPerMile(p: Prefs) = p.sp.getFloat("min_per_mile", 2f).toDouble()
    fun on(p: Prefs) = p.sp.getBoolean("judge_offers", true)

    fun setRule(context: Context, perMile: Double?, on: Boolean?): String {
        val p = Prefs(context)
        perMile?.let { p.sp.edit().putFloat("min_per_mile", it.toFloat()).apply() }
        on?.let { p.sp.edit().putBoolean("judge_offers", it).apply() }
        return if (!on(p)) "Order judging is off." else "Judging offers at ${Earnings.money(minPerMile(p))} a mile."
    }

    /** Pay and miles off an offer screen's text, or null. Pure, so it's unit-tested. */
    fun read(all: String): Pair<Double, Double>? {
        val pay = Regex("\\$\\s?(\\d{1,3}(?:\\.\\d{2})?)").findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it in 1.0..200.0 }.maxOrNull() ?: return null
        val miles = Regex("(\\d{1,3}(?:\\.\\d{1,2})?)\\s?(mi|miles?)\\b", RegexOption.IGNORE_CASE).findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it > 0 && it < 100 }.maxOrNull() ?: return null
        return pay to miles
    }

    /** The spoken verdict for an offer. Pure, so it's unit-tested. */
    fun verdict(pay: Double, miles: Double, min: Double): String {
        val rate = pay / miles
        val payWords = if (pay == Math.floor(pay)) "${pay.toInt()} dollars" else String.format(Locale.US, "%.2f dollars", pay)
        val milesWords = if (miles == Math.floor(miles)) "${miles.toInt()} miles" else "$miles miles"
        val rateWords = String.format(Locale.US, "%.2f", rate).let { if (rate < 1) "${Math.round(rate * 100)} cents" else "$it dollars" }
        val verdict = when {
            rate >= min * 1.25 -> "That's $rateWords a mile. Good one, sir, take it."
            rate >= min -> "$rateWords a mile. Worth taking."
            rate >= min * 0.85 -> "$rateWords a mile. Borderline, your call."
            else -> "Only $rateWords a mile. I'd pass."
        }
        return "$payWords for $milesWords. $verdict"
    }

    /** Is this Dasher screen an order offer? Buttons when they're readable, otherwise pay plus miles plus an offer word. Pure. */
    fun looksLikeOffer(all: String): Boolean {
        if (Regex("(?i)\\b(accept|decline|reject)\\b").containsMatchIn(all) && Regex("\\$\\s?\\d").containsMatchIn(all)) return true
        return read(all) != null && Regex("(?i)(guaranteed|incl\\.? tips|deliver by|pickup|pick up|new order|order from|est\\.?|total)").containsMatchIn(all)
    }

    /** No text to read (or it couldn't be read): look at the screen itself and read the offer off the picture. Blocking. */
    fun fromScreenshot(context: Context): Triple<Double, Double, Double?>? {
        val shot = Hands.screenshot() ?: return null
        val cfg = Brain(context).config(); val model = cfg.optString("agent_model", "claude-haiku-5-5")
        val img = JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg")
            .put("data", android.util.Base64.encodeToString(shot.jpeg, android.util.Base64.NO_WRAP)))
        val ask = JSONObject().put("type", "text").put("text", "Is there a DoorDash Dasher order offer on this phone screen? Reply with JSON only: " +
            "{\"offer\": true or false, \"pay\": dollars, \"miles\": miles, \"minutes\": minutes to finish or null}. Pay is the big dollar amount of the offer; miles is the total distance.")
        val body = JSONObject().put("model", model).put("max_tokens", 120)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(img).put(ask))))
        val resp = Claude.send(context, body) ?: return null
        try { Spend.record(context, cfg, model, resp.optJSONObject("usage")) } catch (_: Exception) {}
        val text = resp.optJSONArray("content")?.let { a -> (0 until a.length()).joinToString(" ") { a.getJSONObject(it).optString("text") } }.orEmpty()
        val j = try { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) } catch (_: Exception) { return null }
        if (!j.optBoolean("offer")) return null
        val pay = j.optDouble("pay", Double.NaN); val miles = j.optDouble("miles", Double.NaN)
        if (pay.isNaN() || miles.isNaN() || pay <= 0 || miles <= 0) return null
        Trace.log(context, "offer", "read off the screenshot: ${Earnings.money(pay)}, $miles mi")
        return Triple(pay, miles, j.optDouble("minutes", Double.NaN).takeIf { !it.isNaN() && it > 0 })
    }

    @Volatile private var lastShotTry = 0L

    /** A new Dasher screen popped up while he's dashing and its text didn't read as an offer: look at the picture (capped). */
    fun maybeLook(context: Context, texts: List<String>) {
        val now = System.currentTimeMillis(); val p = Prefs(context)
        if (!on(p) || !p.running || now - lastShotTry < 20_000) return
        if (!(try { Shift.active(context) || DashDays.dashing(context) } catch (_: Exception) { false })) return
        val all = texts.joinToString(" | ")
        if (now - lastMiss > 120_000) { lastMiss = now; Trace.log(context, "offer", "new Dasher screen: ${all.take(240)}") }
        if (!Regex("\\$\\s?\\d").containsMatchIn(all) && texts.size > 6) return   // a readable screen with no money on it: not an offer
        if (Regex("\\$\\s?\\d").containsMatchIn(all) && read(all) == null && texts.size > 6 && !looksLikeOffer(all) && !Regex("(?i)(mi\\b|miles|min\\b)").containsMatchIn(all)) return
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val n = if (p.sp.getString("offer_shot_day", "") == day) p.sp.getInt("offer_shot_n", 0) else 0
        if (n >= Brain(context).config().optInt("offer_shots_per_day", 60)) return
        lastShotTry = now; p.sp.edit().putString("offer_shot_day", day).putInt("offer_shot_n", n + 1).apply()
        kotlin.concurrent.thread {
            val (pay, miles, mins) = fromScreenshot(context) ?: return@thread
            val sig = "$pay|$miles"; if (sig == lastSig && System.currentTimeMillis() - lastAt < 120_000) return@thread
            lastSig = sig; lastAt = System.currentTimeMillis()
            val cfg = Brain(context).config()
            val line = verdict2(pay, miles, minPerMile(p), mins, cfg.optDouble("offer_hourly", 25.0), cfg.optDouble("offer_min_pay", 4.0))
            pending = JSONObject().put("pay", pay).put("miles", miles).put("mins", mins ?: 0.0).put("at", System.currentTimeMillis())
            Trace.log(context, "offer", line)
            try { context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", line)) } catch (_: Exception) {}
        }
    }

    /** Minutes the offer will take, from "18 min" or "Deliver by 6:45 PM", or null. Pure. */
    fun minutes(all: String, now: java.util.Calendar): Double? {
        Regex("(\\d{1,3})\\s?min", RegexOption.IGNORE_CASE).find(all)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 3.0..180.0 }?.let { return it }
        val m = Regex("(?i)deliver by\\s*(\\d{1,2}):(\\d{2})\\s*(am|pm)").find(all) ?: return null
        var h = m.groupValues[1].toInt() % 12; if (m.groupValues[3].lowercase() == "pm") h += 12
        val at = (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m.groupValues[2].toInt()); set(java.util.Calendar.SECOND, 0); if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1) }
        return ((at.timeInMillis - now.timeInMillis) / 60_000.0).takeIf { it in 3.0..180.0 }
    }

    /** The smarter verdict: per mile, per hour when the time is shown, and never below his minimum pay. Pure. */
    fun verdict2(pay: Double, miles: Double, min: Double, mins: Double?, hourly: Double, minPay: Double): String {
        val base = verdict(pay, miles, min)
        val perHour = mins?.let { pay / (it / 60.0) }
        val hourWords = perHour?.let { " About ${Math.round(it)} an hour." }.orEmpty()
        return when {
            pay < minPay -> base.substringBefore(". ") + ". Under your ${Math.round(minPay)} dollar minimum. I'd pass."
            perHour != null && perHour < hourly * 0.6 && pay / miles < min * 1.25 -> base.substringBefore(". ") + ".$hourWords Too slow for the money. I'd pass."
            perHour != null && perHour >= hourly * 1.4 && pay / miles >= min * 0.85 -> base.substringBefore(". ") + ".$hourWords Quick money, sir, take it."
            else -> base + hourWords
        }
    }

    /** His own floor, from what he's actually accepted: the rate a quarter of his accepted offers sit below. Pure. */
    fun learnedFloor(acceptedRates: List<Double>): Double? = if (acceptedRates.size < 15) null else acceptedRates.sorted()[acceptedRates.size / 4]

    @Volatile private var pending: JSONObject? = null
    /** He tapped Accept or Decline on the offer Jarvis just judged: remember it, and after enough, judge by his real floor. */
    fun decided(context: Context, accepted: Boolean) {
        val o = pending ?: return; pending = null
        if (System.currentTimeMillis() - o.optLong("at") > 120_000L) return
        val p = Prefs(context)
        val h = try { org.json.JSONArray(p.sp.getString("offer_history", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        h.put(o.put("ok", accepted))
        val keep = org.json.JSONArray(); for (i in maxOf(0, h.length() - 300) until h.length()) keep.put(h.get(i))
        p.sp.edit().putString("offer_history", keep.toString()).apply()
        Trace.log(context, "offer", if (accepted) "he took it" else "he passed")
        if (!Brain(context).config().optBoolean("offer_learn", true)) return
        val rates = (0 until keep.length()).map { keep.getJSONObject(it) }.filter { it.optBoolean("ok") && it.optDouble("miles") > 0 }.map { it.optDouble("pay") / it.optDouble("miles") }
        val floor = learnedFloor(rates) ?: return
        val cur = minPerMile(p)
        if (Math.abs(floor - cur) / cur > 0.12) {
            val next = Math.round(((cur + floor) / 2) * 20) / 20.0   // move halfway, to the nearest 5 cents
            p.sp.edit().putFloat("min_per_mile", next.toFloat()).apply()
            Mind.tellLater(context, "Sir, from the offers you actually take, I'm judging at ${Earnings.money(next)} a mile now instead of ${Earnings.money(cur)}. Say the word if you want it different.")
        }
    }

    /** Called with all the text on the Dasher screen when an offer is showing: says the verdict once per offer. */
    fun check(context: Context, texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 700) return
        lastCheck = now
        val p = Prefs(context)
        if (!on(p) || !p.running || Stealth.on(p)) return
        val all = texts.joinToString(" | ")
        val (pay, miles) = read(all) ?: run {
            // an offer is up but the pay or miles couldn't be read: log what was on screen (once a minute) so it can be fixed,
            // and read it off a screenshot instead (at most every 20 seconds)
            if (now - lastMiss > 60_000) { lastMiss = now; Trace.log(context, "offer", "couldn't read pay or miles from: ${all.take(300)}") }
            if (now - lastShotTry > 20_000) { lastShotTry = now
                kotlin.concurrent.thread { fromScreenshot(context)?.let { (pay, miles, mins) ->
                    val cfg = Brain(context).config()
                    val line = verdict2(pay, miles, minPerMile(p), mins, cfg.optDouble("offer_hourly", 25.0), cfg.optDouble("offer_min_pay", 4.0))
                    pending = JSONObject().put("pay", pay).put("miles", miles).put("mins", mins ?: 0.0).put("at", System.currentTimeMillis())
                    Trace.log(context, "offer", line)
                    try { context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", line)) } catch (_: Exception) {}
                } } }
            return
        }
        val sig = "$pay|$miles"
        if (sig == lastSig && now - lastAt < 120_000) return
        lastSig = sig; lastAt = now
        val cfg = Brain(context).config()
        val mins = minutes(all, java.util.Calendar.getInstance())
        val line = verdict2(pay, miles, minPerMile(p), mins, cfg.optDouble("offer_hourly", 25.0), cfg.optDouble("offer_min_pay", 4.0))
        pending = JSONObject().put("pay", pay).put("miles", miles).put("mins", mins ?: 0.0).put("at", now)
        Trace.log(context, "offer", line)
        try { context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", line)) } catch (_: Exception) {}
    }

    /** "Is this a good order?": judges whatever offer is on screen right now. */
    fun now(context: Context): String {
        val a = JarvisAccess.me ?: return JarvisAccess.NEED
        val cfg = Brain(context).config(); val p = Prefs(context)
        val texts = a.offerTexts()
        val parsed = texts?.let { read(it.joinToString(" | ")) }
        if (parsed != null) return verdict2(parsed.first, parsed.second, minPerMile(p), minutes(texts.joinToString(" | "), java.util.Calendar.getInstance()), cfg.optDouble("offer_hourly", 25.0), cfg.optDouble("offer_min_pay", 4.0))
        // couldn't read it as text: look at the screen itself
        a.dasherTexts()?.let { Trace.log(context, "offer", "asked to judge; Dasher screen said: ${it.joinToString(" | ").take(300)}") }
        val shot = fromScreenshot(context) ?: return "I don't see a DoorDash offer on screen right now."
        return verdict2(shot.first, shot.second, minPerMile(p), shot.third, cfg.optDouble("offer_hourly", 25.0), cfg.optDouble("offer_min_pay", 4.0))
    }
}

/**
 * Payouts on autopilot: when the Dasher app shows the end-of-dash summary (or sends an "you earned" notification),
 * Jarvis reads the total and the miles off it and logs them himself, once per dash. He only reads; he never taps.
 */
object DashPayout {
    private val KEY = Regex("(?i)\\b(dash summary|dash complete|dash ended|dash is over|end of dash|you earned|great dash)\\b")   // only the end of a dash, never the running total
    private val NEAR = Regex("(?i)(earned|earnings|total|you made)[^$]{0,40}\\$\\s?(\\d{1,4}(?:,\\d{3})*(?:\\.\\d{2})?)")
    private val MONEY = Regex("\\$\\s?(\\d{1,4}(?:\\.\\d{2})?)")
    private val MILES = Regex("(?i)(\\d{1,3}(?:\\.\\d{1,2})?)\\s?(mi|miles)\\b")

    /** (total, miles) from an end-of-dash screen or notification, or null if it isn't one. Pure. */
    fun read(all: String): Pair<Double, Double?>? {
        if (!KEY.containsMatchIn(all)) return null
        if (Regex("(?i)\\b(accept|decline)\\b").containsMatchIn(all)) return null   // an offer, not a summary
        val total = NEAR.find(all)?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()
            ?: MONEY.findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it in 5.0..800.0 }.maxOrNull() ?: return null
        if (total < 2.0 || total > 1500.0) return null
        val miles = MILES.findAll(all).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it in 1.0..400.0 }.maxOrNull()
        return total to miles
    }

    /** How much of this total is new: the same dash showing again (or its notification) adds nothing, and a summary
     *  that grew within a few hours (a late tip) adds only the difference. Pure. */
    fun newPart(total: Double, now: Long, logged: List<Pair<Double, Long>>): Double {
        val recent = logged.filter { now - it.second < 3 * 3600_000L }.maxByOrNull { it.second } ?: return total
        return if (total > recent.first + 0.01) Math.round((total - recent.first) * 100) / 100.0 else 0.0
    }

    @Volatile private var lastLook = 0L
    fun seen(context: Context, texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastLook < 2000) return
        lastLook = now
        if (!Brain(context).config().optBoolean("auto_payouts", true)) return
        val (total, miles) = read(texts.joinToString(" | ")) ?: return
        val p = Prefs(context)
        val logged = try { JSONArray(p.sp.getString("payouts_seen", "[]")) } catch (_: Exception) { JSONArray() }
        val list = (0 until logged.length()).map { logged.getJSONObject(it).let { o -> o.optDouble("a") to o.optLong("at") } }
        val add = newPart(total, now, list)
        if (add <= 0.0) return
        logged.put(JSONObject().put("a", total).put("at", now))
        val keep = JSONArray(); for (i in maxOf(0, logged.length() - 40) until logged.length()) keep.put(logged.get(i))
        p.sp.edit().putString("payouts_seen", keep.toString()).apply()
        val out = StringBuilder(if (add < total) "Your dash total went up by ${Earnings.money(add)}, logged." else "Logged ${Earnings.money(total)} from the Dasher app.")
        Earnings.log(context, add, "today", false)
        try { Goals.allocate(context, add).takeIf { it.isNotBlank() }?.let { out.append(' ').append(it) } } catch (_: Exception) {}
        if (miles != null && add >= total) try { Miles.log(context, miles); out.append(" Plus ${miles.toInt()} miles.") } catch (_: Exception) {}
        Trace.log(context, "payout", out.toString())
        Shift.say(context, "Sir, " + out.toString().replaceFirstChar { it.lowercase() }, false)
    }
}
