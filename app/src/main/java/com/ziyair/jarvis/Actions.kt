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
        put(tool("play_music", "Play a specific song, artist, album or playlist (in YouTube Music or Spotify, whichever he uses). For resuming or skipping use media_control instead.",
            props("query" to str("What to play, song and artist, e.g. 'Fear NF'"), "song" to str("Just the song title, e.g. 'Fear'")), listOf("query")))
        put(tool("get_weather", "Weather right now plus today's and tomorrow's forecast, where he lives or for a place he names.", props("place" to str("Optional city, if not where he lives")), listOf()))
        put(tool("open_app", "Open any app on his phone by name: camera, YouTube, TikTok, Instagram, Snapchat, DoorDash Dasher, Maps, Settings, anything installed.", props("name" to str("App name as he says it")), listOf("name")))
        put(tool("media_control", "Control whatever is playing (music, YouTube, podcasts): play/resume, pause, toggle, next (skip), previous, stop. Use this for 'skip', 'next song', 'pause', 'resume', 'turn it back on'.",
            props("action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("play", "pause", "toggle", "next", "previous", "stop")))), listOf("action")))
        put(tool("stop_navigation", "Cancel/end Google Maps directions.", props(), listOf()))
        put(tool("screen_read", "See what's on his screen right now: the app and the visible buttons and text ([tap] marks tappable things). Use before tapping if unsure.", props(), listOf()))
        put(tool("screen_tap", "Tap a button, link, tab or item on screen by its visible text or label. Returns what's on screen afterwards, so you can keep going step by step.",
            props("target" to str("The text or label of what to tap"), "long_press" to bool("Long-press instead")), listOf("target")))
        put(tool("screen_type", "Type text into the text box on screen (search bars, message boxes).", props("text" to str("What to type"), "submit" to bool("Press enter/search after")), listOf("text")))
        put(tool("screen_action", "Phone buttons and gestures: back, home, recent_apps, notifications, quick_settings, scroll_down, scroll_up (also next video on TikTok/Reels/Shorts), lock_screen, screenshot.",
            props("action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("back", "home", "recent_apps", "notifications", "quick_settings", "scroll_down", "scroll_up", "lock_screen", "screenshot")))), listOf("action")))
        put(tool("read_messages", "Read his recent incoming messages (texts, WhatsApp and similar), newest first.", props(), listOf()))
        put(tool("reply_message", "Reply to someone who messaged him, in the same app. Use who='last' for the message just read out. Read the reply back and get a yes first; only call with confirmed=true after he says yes. In driving mode, if he dictated the reply right after being asked, a quick 'sending: ... ' confirmation question is still required.",
            props("who" to str("Sender name, or 'last'"), "message" to str("The reply text"), "confirmed" to bool("He said yes to sending this exact reply")), listOf("who", "message", "confirmed")))
        put(tool("remember", "Save something to long-term memory so you know it in future conversations (a fact about him, a person, a plan, a preference, where he parked, a code).",
            props("fact" to str("One short sentence in third person, e.g. 'His girlfriend's name is Bri.'")), listOf("fact")))
        put(tool("forget", "Delete remembered facts about something he wants forgotten.", props("about" to str("Words that identify what to forget")), listOf("about")))
        put(tool("log_earnings", "Log money he made DoorDashing. Amounts for the same day add up (two shifts) unless replace is true (he's correcting the day's total). Reply with the result's totals in one or two short sentences.",
            props("amount" to num("Dollars made"), "day" to str("'today', 'yesterday' or YYYY-MM-DD"), "replace" to bool("true if this is a correction of that day's total")), listOf("amount")))
        put(tool("earnings_summary", "His earnings so far today, this week, this month, his daily average and progress toward his savings goal (the Macan).", props(), listOf()))
        put(tool("set_goal", "Set his savings goal amount (e.g. what the Porsche Macan costs).", props("amount" to num("Goal in dollars"), "name" to str("What it's for, e.g. 'the Macan'")), listOf("amount")))
        put(tool("log_baby", "Log something about the baby: a feed, a diaper, sleep or waking. Feeds also set a heads-up for the next feed.",
            props("kind" to str("feed, diaper, sleep or wake"), "minutes_ago" to num("How long ago it happened, 0 if just now"), "note" to str("Optional detail, e.g. '4 oz' or 'poop'")), listOf("kind")))
        put(tool("baby_status", "When the baby last ate, feeds and diapers today, and whether he's asleep.", props(), listOf()))
        put(tool("set_feed_interval", "Change how many hours between feeds for the next-feed heads-up (default 3).", props("hours" to num("Hours between feeds")), listOf("hours")))
        put(tool("phone_control", "Control the phone: quiet_on/quiet_off (Do Not Disturb, e.g. 'the baby's asleep'), flashlight_on/flashlight_off, volume (level 0-10).",
            props("action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("quiet_on", "quiet_off", "flashlight_on", "flashlight_off", "volume"))), "level" to num("Volume 0-10")), listOf("action")))
        put(tool("calendar_events", "What's on his calendar.", props("day" to str("'today', 'tomorrow' or YYYY-MM-DD"), "days" to num("How many days to cover, default 1 (7 for 'this week')")), listOf()))
        put(tool("add_event", "Add an event to his calendar. Confirm the details in your reply.",
            props("title" to str("What it is"), "date" to str("'today', 'tomorrow' or YYYY-MM-DD"), "time" to str("24-hour HH:MM, leave out for all-day"), "minutes" to num("Length in minutes, default 60")), listOf("title", "date")))
        put(tool("house_speaker", "Jarvis's voice through his Google Nest Hub / Cast speakers at home. mode: 'announcements' (greetings, heads-ups, reminders), 'everything' (all replies), or 'off'. Optional speaker name. With no arguments, lists speakers found.",
            props("mode" to str("announcements, everything or off"), "speaker" to str("Speaker or group name")), listOf()))
        put(tool("quiet_mode", "Quiet mode: on = Jarvis goes completely silent (no voice, no updates) and saves everything; off = end it and get the list of what he missed to recap.",
            props("on" to bool("true to go quiet, false to end it"), "minutes" to num("Optional: end automatically after this many minutes")), listOf("on")))
        put(tool("set_voice", "Change Jarvis's voice: alan, george or lewis (all British men; George and Lewis are smoother).", props("name" to str("alan, george or lewis")), listOf("name")))
        put(tool("set_home_wifi", "Save the Wi-Fi he's on right now as his home network (for 'welcome home'). Use when he says 'this is home', 'this is my home Wi-Fi'.", props(), listOf()))
        put(tool("spending", "How much Jarvis has cost on his Claude account this month, against his budget.", props(), listOf()))
        put(tool("set_budget", "Set his monthly Claude budget in dollars (he gets a warning near it).", props("dollars" to num("Monthly budget")), listOf("dollars")))
        put(tool("status_report", "Status report: battery, weather and roads, earnings today, the baby, next shift, next reminder. Use for 'status report', 'how are we looking', 'catch me up'.", props(), listOf()))
        put(tool("run_protocol", "Run one of his named protocols: 'dash' (heading out to DoorDash: driving mode, opens Dasher, starts music, weather), 'baby' (night feed: quiet phone, dim warm lights, low volume, baby status), 'home' (done for the night: driving off, lights on, earnings and baby recap), 'goodnight' (lights off, phone quiet until before his next shift with starred contacts still ringing, next shift time).",
            props("name" to str("dash, baby, home or goodnight")), listOf("name")))
        put(tool("lights", "Control his Govee lights: on/off, brightness 1-100, color (red, orange, yellow, green, blue, purple, pink, white, warm, cool). which = a light's name or 'all'.",
            props("which" to str("Light name or 'all'"), "on" to bool("true on, false off"), "brightness" to num("1-100"), "color" to str("Color name")), listOf()))
        put(tool("find_phone", "He can't find his phone and asked where you are: ring loudly (even on silent) and flash the light.", props(), listOf()))
        put(tool("look", "Take a photo with the back camera and look at it (he points the phone at something). Use for 'what's this', 'read this', 'what does this say', 'what's wrong with this'.", props(), listOf()))
        put(tool("remind_at_home", "Remind him of something when he gets home (e.g. 'grab the wipes from the car').", props("text" to str("What to remind him")), listOf("text")))
        put(tool("journal_add", "Save a moment to the baby journal (e.g. 'Ziyair smiled today', 'first bath'). Dated automatically.", props("text" to str("The moment, short, in his words")), listOf("text")))
        put(tool("journal_read", "Read back baby journal moments, optionally about something.", props("about" to str("Optional word to search")), listOf()))
        put(tool("answer_call", "Answer the phone call that's ringing right now.", props(), listOf()))
        put(tool("decline_call", "Decline the phone call that's ringing right now.", props(), listOf()))
        put(tool("offer_rule", "Change DoorDash order judging: minimum dollars per mile, or turn it on/off.", props("per_mile" to num("Minimum $ per mile"), "on" to bool("Judge offers or not")), listOf()))
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
                Inbox.findByName(partnerAlias(input.getString("who")))?.takeIf { it.reply != null }?.let { m ->
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
                val q = input.getString("query").trim()
                val pm = context.packageManager
                fun has(p: String) = pm.getLaunchIntentForPackage(p) != null
                // use the music app he's already using; otherwise YouTube Music, then Spotify
                val current = Apps.player(context)?.packageName?.takeIf { it == YT_MUSIC || it == SPOTIFY }
                val pkg = current ?: listOf(YT_MUSIC, SPOTIFY).firstOrNull { has(it) } ?: return "No music app found (YouTube Music or Spotify)."
                val name = if (pkg == SPOTIFY) "Spotify" else "YouTube Music"
                if (q.isBlank() || q.lowercase() in setOf("music", "something", "my music", "anything")) return Apps.media(context, "play")
                Music.play(context, pkg, name, q, input.optString("song").ifBlank { null })
            }
            "get_weather" -> Weather.report(context, input.optString("place")) ?: "Couldn't get the weather right now."
            "open_app" -> Apps.open(context, input.getString("name"))
            "media_control" -> Apps.media(context, input.getString("action"))
            "stop_navigation" -> JarvisAccess.stopNavigation(context)
            "screen_read" -> JarvisAccess.read()
            "screen_tap" -> JarvisAccess.tap(input.getString("target"), input.optBoolean("long_press"))
            "screen_type" -> JarvisAccess.type(input.getString("text"), input.optBoolean("submit"))
            "screen_action" -> JarvisAccess.press(input.getString("action"))
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
            "log_earnings" -> Earnings.log(context, input.getDouble("amount"), input.optString("day", "today"), input.optBoolean("replace"))
            "earnings_summary" -> Earnings.summary(context)
            "set_goal" -> Earnings.setGoal(context, input.getDouble("amount"), input.optString("name"))
            "log_baby" -> Baby.log(context, input.getString("kind"), input.optDouble("minutes_ago", 0.0), input.optString("note"))
            "baby_status" -> Baby.status(context)
            "set_feed_interval" -> Baby.setFeedHours(context, input.getDouble("hours").coerceIn(0.5, 12.0))
            "phone_control" -> PhoneControl.run(context, input.getString("action"), if (input.has("level")) input.optInt("level") else null)
            "calendar_events" -> Cal.events(context, input.optString("day", "today"), input.optInt("days", 1))
            "add_event" -> Cal.add(context, input.getString("title"), input.optString("date", "today"), input.optString("time").ifBlank { null }, input.optInt("minutes", 60))
            "remind_at_home" -> HomeReminders.add(context, input.getString("text"))
            "journal_add" -> Journal.add(context, input.getString("text"))
            "journal_read" -> Journal.read(context, input.optString("about"))
            "answer_call" -> CallControl.answer(context)
            "decline_call" -> CallControl.decline(context)
            "offer_rule" -> OfferJudge.setRule(context, if (input.has("per_mile")) input.optDouble("per_mile") else null, if (input.has("on")) input.optBoolean("on") else null)
            "house_speaker" -> {
                val p = prefs
                input.optString("mode").takeIf { it in setOf("announcements", "everything", "off") }?.let { Cast.setMode(p, it) }
                input.optString("speaker").takeIf { it.isNotBlank() }?.let { p.sp.edit().putString("cast_speaker", it).apply() }
                val list = Cast.speakers().joinToString { it.name }
                "House speaker: ${Cast.mode(p)}. " + (if (list.isBlank()) "No Cast speakers found on this Wi-Fi right now." else "Found: $list. Using ${Cast.target(p)?.name}.")
            }
            "quiet_mode" -> if (input.optBoolean("on")) Stealth.start(context, if (input.has("minutes")) input.optInt("minutes") else null) else Stealth.end(context)
            "set_voice" -> {
                val v = input.getString("name").lowercase().trim()
                if (v !in Piper.NAMES) "Voices available: Alan, George, Lewis." else {
                    Piper.setPick(context, v)
                    if (Piper.pickReady(context)) "Switched to ${v.replaceFirstChar { it.uppercase() }}." else {
                        Thread { Piper.download(context) }.start()
                        "Downloading the ${v.replaceFirstChar { it.uppercase() }} voice now (about 100 MB, once); I'll switch over as soon as it's ready."
                    }
                }
            }
            "set_home_wifi" -> HomeNet.setHere(context)
            "spending" -> Spend.report(context)
            "set_budget" -> Spend.setBudget(context, input.getDouble("dollars"))
            "status_report" -> Status.report(context)
            "run_protocol" -> Protocols.run(context, input.getString("name"))
            "lights" -> Lights.set(context, input.optString("which", "all"), if (input.has("on")) input.optBoolean("on") else null,
                if (input.has("brightness")) input.optInt("brightness") else null, input.optString("color").ifBlank { null })
            "find_phone" -> FindPhone.ring(context)
            "driving_mode" -> { DriveMode.on = input.optBoolean("on"); if (DriveMode.on) "Driving mode on." else "Driving mode off." }
            else -> "Unknown action."
        }
    } catch (e: Exception) { Problems.log(context, "$name: ${e.message}"); "That didn't work: ${e.message}" } }

    private fun canOpenApps() = Settings.canDrawOverlays(context)
    private fun listenerOn() = (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "").contains(context.packageName)
    private fun ago(t: Long): String { val m = ((System.currentTimeMillis() - t) / 60000).toInt(); return if (m < 1) "just now" else if (m < 60) "$m min ago" else "${m / 60} h ago" }
    private fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Contact lookup by name (partial match), or a raw number. "her"/"my girlfriend" maps to the saved partner name. */
    private fun partnerAlias(whoIn: String): String {
        val who = whoIn.trim()
        return if (Regex("^(her|my girl(friend)?|girlfriend|my partner|partner|wife|bae|my baby mama|baby mama)$", RegexOption.IGNORE_CASE).matches(who) && prefs.herName.isNotBlank()) prefs.herName else who
    }

    private fun findNumber(whoIn: String): Pair<String, String>? {
        val who = partnerAlias(whoIn)
        if (who.replace(Regex("[^0-9+]"), "").length >= 7) return who to who
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val found = ArrayList<Pair<String, String>>()
        context.contentResolver.query(uri, cols, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null)?.use { c ->
            while (c.moveToNext()) found.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
        }
        val w = who.lowercase()
        return found.firstOrNull { it.first.lowercase() == w }
            ?: found.firstOrNull { it.first.lowercase().startsWith("$w ") || it.first.lowercase().split(" ").contains(w) }
            ?: found.firstOrNull()
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
        const val SPOTIFY = "com.spotify.music"
        const val NEED_OPEN_APPS = "He needs to let Jarvis open apps: open the Jarvis app and tap Allow opening apps."
    }
}

/** Free weather from Open-Meteo (no key). City comes from the Jarvis app setup. */
object Weather {
    private const val HOME = "Spokane"

    private fun geocode(place: String): Pair<String, String>? {
        val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(place.substringBefore(",").trim(), "UTF-8")) ?: return null
        val r = JSONObject(g).optJSONArray("results")?.optJSONObject(0) ?: return null
        return r.getDouble("latitude").toString() to r.getDouble("longitude").toString()
    }

    private fun coords(context: Context, place: String?): Pair<String, String>? {
        if (!place.isNullOrBlank()) return geocode(place)
        val prefs = Prefs(context)
        val city = prefs.city.ifBlank { HOME }
        if (prefs.cityLat.isBlank() || prefs.cityFor != city) {
            val (la, lo) = geocode(city) ?: return null
            prefs.cityLat = la; prefs.cityLon = lo; prefs.cityFor = city
        }
        return prefs.cityLat to prefs.cityLon
    }

    private fun forecast(ll: Pair<String, String>): JSONObject? = get("https://api.open-meteo.com/v1/forecast?latitude=${ll.first}&longitude=${ll.second}" +
        "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=2" +
        "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto")?.let { JSONObject(it) }

    fun now(context: Context): String? = try {
        val c = forecast(coords(context, null) ?: throw Exception())!!.getJSONObject("current")
        "${Math.round(c.optDouble("temperature_2m"))}°F, feels like ${Math.round(c.optDouble("apparent_temperature"))}, ${describe(c.optInt("weather_code"))}, wind ${Math.round(c.optDouble("wind_speed_10m"))} mph."
    } catch (_: Exception) { null }

    fun report(context: Context, place: String?): String? = try {
        val j = forecast(coords(context, place) ?: throw Exception())!!
        val c = j.getJSONObject("current"); val d = j.getJSONObject("daily")
        fun day(i: Int) = "high ${Math.round(d.getJSONArray("temperature_2m_max").optDouble(i))}, low ${Math.round(d.getJSONArray("temperature_2m_min").optDouble(i))}, " +
            "${describe(d.getJSONArray("weather_code").optInt(i))}, ${d.getJSONArray("precipitation_probability_max").optInt(i)}% chance of rain or snow"
        (if (!place.isNullOrBlank()) "$place: " else "") +
            "Now ${Math.round(c.optDouble("temperature_2m"))}°F, feels like ${Math.round(c.optDouble("apparent_temperature"))}, ${describe(c.optInt("weather_code"))}, wind ${Math.round(c.optDouble("wind_speed_10m"))} mph. " +
            "Today: ${day(0)}. Tomorrow: ${day(1)}."
    } catch (_: Exception) { null }

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
