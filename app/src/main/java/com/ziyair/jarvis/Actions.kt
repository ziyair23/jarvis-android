package com.ziyair.jarvis

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/** Things Jarvis can actually do on the phone. Each returns a short result for Claude to turn into a reply. */
class Actions(private val context: Context) {
    private val prefs = Prefs(context)

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("set_timer", "Start a countdown timer. When it ends Jarvis speaks up and the phone rings. Use for things like a feed in 3 hours or pizza in 12 minutes.",
            props("minutes" to num("Length in minutes (decimals ok)"), "label" to str("What it's for, short")), listOf("minutes")))
        put(tool("remind_me", "Remind him of something at a time today or tomorrow. Jarvis says it out loud at that time.",
            props("time" to str("24-hour local time HH:MM"), "text" to str("What to remind him about"), "tomorrow" to bool("true if it's for tomorrow")), listOf("time", "text")))
        put(tool("set_alarm", "Set a real wake-up alarm in the phone's Clock app.",
            props("time" to str("24-hour local time HH:MM"), "label" to str("Optional label")), listOf("time")))
        put(tool("list_reminders", "List the timers and reminders Jarvis has scheduled.", props(), listOf()))
        put(tool("cancel_reminders", "Cancel all of Jarvis's pending timers and reminders.", props(), listOf()))
        put(tool("call", "Phone call someone from his contacts (or a number).",
            props("who" to str("Contact name as he'd say it, or a phone number")), listOf("who")))
        put(tool("send_text", "Send a text message. FIRST read the message back and ask him to confirm. Only call with confirmed=true after he clearly says yes.",
            props("who" to str("Contact name or phone number"), "message" to str("The exact text to send"), "confirmed" to bool("He said yes to sending this exact message")), listOf("who", "message", "confirmed")))
        put(tool("navigate", "Start Google Maps driving directions to a place.",
            props("destination" to str("Place name or address")), listOf("destination")))
        put(tool("play_music", "Play a song, artist, album or playlist on YouTube Music.",
            props("query" to str("What to play")), listOf("query")))
        put(tool("get_weather", "Current weather where he lives.", props(), listOf()))
        put(tool("read_messages", "Read his recent incoming messages (texts, WhatsApp and similar), newest first.", props(), listOf()))
        put(tool("reply_message", "Reply to someone who messaged him, in the same app. Use who='last' for the message just read out. Read the reply back and get a yes first; only call with confirmed=true after he says yes. In driving mode, if he dictated the reply right after being asked, a quick 'sending: ... ' confirmation question is still required.",
            props("who" to str("Sender name, or 'last'"), "message" to str("The reply text"), "confirmed" to bool("He said yes to sending this exact reply")), listOf("who", "message", "confirmed")))
        put(tool("remember", "Save something to long-term memory so you know it in future conversations (a fact about him, a person, a plan, a preference, where he parked, a code).",
            props("fact" to str("One short sentence in third person, e.g. 'His girlfriend's name is Bri.'")), listOf("fact")))
        put(tool("forget", "Delete remembered facts about something he wants forgotten.", props("about" to str("Words that identify what to forget")), listOf("about")))
        put(tool("driving_mode", "Turn driving mode on or off (short answers, messages read aloud).", props("on" to bool("true for on")), listOf("on")))
    }

    fun run(name: String, input: JSONObject): String { return try {
        when (name) {
            "set_timer" -> {
                val mins = input.optDouble("minutes", 0.0)
                if (mins <= 0) "Need a length in minutes." else {
                    val label = input.optString("label").ifBlank { "Timer" }
                    val at = System.currentTimeMillis() + (mins * 60_000).toLong()
                    Scheduler.add(context, at, "Sir, your ${label.lowercase()} timer is up.", loud = true, label = label)
                    "Timer set for ${fmtMins(mins)}, ends at ${Scheduler.clock(at)}."
                }
            }
            "remind_me" -> {
                val at = timeToday(input.getString("time"), input.optBoolean("tomorrow")) ?: return "Couldn't read that time."
                val text = input.getString("text")
                Scheduler.add(context, at, "Sir, a reminder: $text", loud = false, label = text)
                "Reminder set for ${Scheduler.clock(at)}${if (input.optBoolean("tomorrow")) " tomorrow" else ""}."
            }
            "set_alarm" -> {
                val (h, m) = hm(input.getString("time")) ?: return "Couldn't read that time."
                if (!canOpenApps()) return NEED_OPEN_APPS
                context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label").ifBlank { "Jarvis" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Alarm set in the Clock app for ${String.format("%d:%02d", h, m)}."
            }
            "list_reminders" -> Scheduler.describe(context)
            "cancel_reminders" -> { Scheduler.cancelAll(context); "All timers and reminders cancelled." }
            "call" -> {
                if (!has(Manifest.permission.CALL_PHONE)) return "He needs to allow calls: open the Jarvis app and tap Allow calls and texts."
                if (!canOpenApps()) return NEED_OPEN_APPS
                val (name, number) = findNumber(input.getString("who")) ?: return "No contact found for that name."
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Calling $name."
            }
            "send_text" -> {
                if (!input.optBoolean("confirmed")) return "Not sent. Read the message back and ask him to confirm first."
                val msg = input.getString("message")
                // if they messaged recently, reply right in that chat (no tap needed)
                Inbox.find(input.getString("who"))?.takeIf { it.reply != null && input.getString("who").length > 1 }?.let { m ->
                    if (Inbox.reply(context, m, msg)) return "Sent to ${m.sender} on ${m.app}."
                }
                if (!canOpenApps()) return NEED_OPEN_APPS
                val (name, number) = findNumber(input.getString("who")) ?: return "No contact found for that name."
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Text to $name is ready in Messages; he just needs to tap send."
            }
            "navigate" -> {
                if (!canOpenApps()) return NEED_OPEN_APPS
                val q = Uri.encode(input.getString("destination"))
                val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(maps) } catch (_: Exception) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Directions started. Unlock the phone to see the map if it's locked."
            }
            "play_music" -> {
                if (!canOpenApps()) return NEED_OPEN_APPS
                val q = input.getString("query")
                val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(YT_MUSIC)
                    .putExtra(SearchManager.QUERY, q).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(play) } catch (_: Exception) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Playing $q on YouTube Music."
            }
            "get_weather" -> Weather.now(context) ?: "Weather isn't available. He may need to set his city in the Jarvis app."
            "read_messages" -> {
                val r = Inbox.recent().take(6)
                if (r.isEmpty()) (if (!listenerOn()) "Can't see messages yet: he needs to tap Allow reading messages in the Jarvis app." else "No new messages in the last few hours.")
                else r.joinToString(" | ") { "${it.sender} on ${it.app}, ${ago(it.at)}: ${it.text}" }
            }
            "reply_message" -> {
                if (!input.optBoolean("confirmed")) return "Not sent. Read the reply back and ask him to confirm first."
                val m = Inbox.find(input.optString("who")) ?: return "I can't find a recent message from them to reply to. Use send_text instead if it's a text."
                if (m.reply == null) return "That app doesn't allow quick replies from notifications."
                if (Inbox.reply(context, m, input.getString("message"))) "Replied to ${m.sender} on ${m.app}." else "The reply didn't go through."
            }
            "remember" -> if (Memory.add(context, input.getString("fact"))) "Saved to memory." else "Already remembered."
            "forget" -> { val n = Memory.forget(context, input.getString("about")); if (n > 0) "Forgot $n thing${if (n > 1) "s" else ""}." else "Nothing matched to forget." }
            "driving_mode" -> { DriveMode.on = input.optBoolean("on"); if (DriveMode.on) "Driving mode on." else "Driving mode off." }
            else -> "Unknown action."
        }
    } catch (e: Exception) { "That didn't work: ${e.message}" } }

    private fun canOpenApps() = Settings.canDrawOverlays(context)
    private fun listenerOn() = (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "").contains(context.packageName)
    private fun ago(t: Long): String { val m = ((System.currentTimeMillis() - t) / 60000).toInt(); return if (m < 1) "just now" else if (m < 60) "$m min ago" else "${m / 60} h ago" }
    private fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Contact lookup by name (partial match), or a raw number. "her"/"my girlfriend" maps to the saved partner name. */
    private fun findNumber(whoIn: String): Pair<String, String>? {
        var who = whoIn.trim()
        if (Regex("^(her|my girl(friend)?|girlfriend|my partner|partner|wife|bae)$", RegexOption.IGNORE_CASE).matches(who) && prefs.herName.isNotBlank()) who = prefs.herName
        if (who.replace(Regex("[^0-9+]"), "").length >= 7) return who to who
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(uri, cols, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null
    }

    private fun hm(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h to mi else null
    }

    private fun timeToday(t: String, tomorrow: Boolean): Long? {
        val (h, m) = hm(t) ?: return null
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (tomorrow || c.timeInMillis < System.currentTimeMillis() - 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun fmtMins(m: Double): String {
        val total = Math.round(m * 60).toInt()
        val h = total / 3600; val mi = (total % 3600) / 60; val s = total % 60
        return listOfNotNull(if (h > 0) "$h hour${if (h > 1) "s" else ""}" else null, if (mi > 0) "$mi minute${if (mi > 1) "s" else ""}" else null,
            if (s > 0 && h == 0) "$s seconds" else null).joinToString(" ").ifBlank { "a moment" }
    }

    private fun tool(name: String, desc: String, properties: JSONObject, required: List<String>) = JSONObject()
        .put("name", name).put("description", desc)
        .put("input_schema", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)))
    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun num(d: String) = JSONObject().put("type", "number").put("description", d)
    private fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)

    companion object {
        const val YT_MUSIC = "com.google.android.apps.youtube.music"
        const val NEED_OPEN_APPS = "He needs to let Jarvis open apps: open the Jarvis app and tap Allow opening apps."
    }
}

/** Free weather from Open-Meteo (no key). City comes from the Jarvis app setup. */
object Weather {
    fun now(context: Context): String? {
        val prefs = Prefs(context)
        if (prefs.city.isBlank()) return null
        try {
            if (prefs.cityLat.isBlank() || prefs.cityFor != prefs.city) {
                val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(prefs.city.substringBefore(","), "UTF-8")) ?: return null
                val r = JSONObject(g).optJSONArray("results")?.optJSONObject(0) ?: return null
                prefs.cityLat = r.getDouble("latitude").toString(); prefs.cityLon = r.getDouble("longitude").toString(); prefs.cityFor = prefs.city
            }
            val f = get("https://api.open-meteo.com/v1/forecast?latitude=${prefs.cityLat}&longitude=${prefs.cityLon}" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto") ?: return null
            val c = JSONObject(f).getJSONObject("current")
            val code = c.optInt("weather_code")
            return "${Math.round(c.optDouble("temperature_2m"))}°F, feels like ${Math.round(c.optDouble("apparent_temperature"))}, ${describe(code)}, wind ${Math.round(c.optDouble("wind_speed_10m"))} mph."
        } catch (_: Exception) { return null }
    }

    fun roadsNote(context: Context): String {
        val w = now(context) ?: return ""
        return when {
            Regex("snow|ice|freezing", RegexOption.IGNORE_CASE).containsMatchIn(w) -> " Roads could be slick, take it easy."
            Regex("rain|drizzle|shower|storm", RegexOption.IGNORE_CASE).containsMatchIn(w) -> " Roads are wet out there."
            else -> ""
        }
    }

    private fun describe(c: Int) = when (c) {
        0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"; 61, 63, 65 -> "rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"; 80, 81, 82 -> "rain showers"; 85, 86 -> "snow showers"; 95, 96, 99 -> "thunderstorms"
        else -> "mixed weather"
    }

    private fun get(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 7000
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }
}
