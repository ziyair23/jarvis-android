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
        put(tool("phone_task", "Do something on his phone screen, by looking at it like a person would: anything inside an app (send a Snapchat or Instagram DM, search YouTube and play a video, change a setting, fill a form, order the usual). Describe the whole task in one sentence. For anything that sends, posts, calls or buys, read it back to him first and only set confirmed=true after he says yes.",
            props("task" to str("The full task, e.g. 'In Instagram, DM abbey: running 10 min late'"), "confirmed" to bool("He said yes to sending/posting/buying")), listOf("task")))
        put(tool("screen_read", "Quickly list what's on his screen right now (app, buttons, text). For doing things, use phone_task.", props(), listOf()))
        put(tool("read_messages", "Read his recent incoming messages (texts, WhatsApp and similar), newest first.", props(), listOf()))
        put(tool("reply_message", "Reply to someone who messaged him, in the same app. Use who='last' for the message just read out. Read the reply back and get a yes first; only call with confirmed=true after he says yes. In driving mode, if he dictated the reply right after being asked, a quick 'sending: ... ' confirmation question is still required.",
            props("who" to str("Sender name, or 'last'"), "message" to str("The reply text"), "confirmed" to bool("He said yes to sending this exact reply")), listOf("who", "message", "confirmed")))
        put(tool("remember", "Save something to long-term memory so you know it in future conversations (a fact about him, a person, a plan, a preference, where he parked, a code).",
            props("fact" to str("One short sentence in third person, e.g. 'His girlfriend's name is Bri.'")), listOf("fact")))
        put(tool("forget", "Delete remembered facts about something he wants forgotten.", props("about" to str("Words that identify what to forget")), listOf("about")))
        put(tool("memory_list", "Everything Jarvis remembers about him, numbered, oldest first, with when each was saved.", props(), listOf()))
        put(tool("learn_lesson", "Save a lesson from a mistake he just corrected, so you never repeat it (e.g. 'When he says Jessie he means Jesse, his boss'). One short, specific sentence.",
            props("lesson" to str("What to do differently next time")), listOf("lesson")))
        put(tool("memory_fix", "Replace one remembered fact with a corrected or merged version (by its number from memory_list), or drop it with an empty new_fact.",
            props("number" to num("Fact number from memory_list"), "new_fact" to str("The corrected fact, or empty to drop it")), listOf("number")))
        put(tool("log_earnings", "Log money he made DoorDashing. Amounts for the same day add up (two shifts) unless replace is true (he's correcting the day's total). Reply with the result's totals in one or two short sentences.",
            props("amount" to num("Dollars made"), "day" to str("'today', 'yesterday' or YYYY-MM-DD"), "replace" to bool("true if this is a correction of that day's total")), listOf("amount")))
        put(tool("earnings_summary", "His earnings so far today, this week, this month, his daily average and progress toward his savings goal (the Macan).", props(), listOf()))
        put(tool("set_goal", "Set his savings goal amount (e.g. what the Porsche Macan costs).", props("amount" to num("Goal in dollars"), "name" to str("What it's for, e.g. 'the Macan'")), listOf("amount")))
        put(tool("log_baby", "Log something about the baby: a feed, a diaper, sleep, waking, or anything else (tummy time, a bath, medicine, pumping, a doctor visit). Feeds also set a heads-up for the next feed.",
            props("kind" to str("feed, diaper, sleep, wake, or a short name for anything else, e.g. 'tummy time', 'bath', 'medicine'"), "minutes_ago" to num("How long ago it happened, 0 if just now"), "note" to str("Details, e.g. '4 oz', 'poop', '5 minutes'")), listOf("kind")))
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
        put(tool("set_voice", "Change Jarvis's voice: alan, george, lewis, daniel or fable (all British men; George, Lewis, Daniel and Fable are the smoother, full-quality ones).", props("name" to str("alan, george, lewis, daniel or fable")), listOf("name")))
        put(tool("set_home_wifi", "Save the Wi-Fi he's on right now as his home network (for 'welcome home'). Use when he says 'this is home', 'this is my home Wi-Fi'.", props(), listOf()))
        put(tool("list_add", "Add things to one of his lists. Main list (shopping/groceries/baby stuff) is 'list'; others like 'to-do' are fine.", props("items" to str("Comma-separated items, e.g. 'diapers, wipes, milk'"), "list" to str("Which list; leave out for the main list")), listOf("items")))
        put(tool("list_check", "Tick things off a list (he got them / did them). items='everything' ticks all.", props("items" to str("Comma-separated items"), "list" to str("Which list; leave out for the main list")), listOf("items")))
        put(tool("list_read", "Read out what's on a list.", props("list" to str("Which list; leave out for the main list")), listOf()))
        put(tool("list_clear", "Clear a list: ticked-off items only, or everything.", props("list" to str("Which list"), "everything" to bool("true to wipe the whole list")), listOf()))
        put(tool("log_expense", "Log money he spent for dashing/work: gas, car wash, food on shift, phone, parking, repairs. Don't log earnings here.", props("amount" to num("Dollars spent"), "what" to str("What it was for, one or two words, e.g. gas"), "day" to str("'today', 'yesterday' or YYYY-MM-DD")), listOf("amount")))
        put(tool("expenses_summary", "What he made, spent (by category) and actually kept this week.", props(), listOf()))
        put(tool("spending", "How much Jarvis has cost on his Claude account this month, against his budget.", props(), listOf()))
        put(tool("set_budget", "Set his monthly Claude budget in dollars (he gets a warning near it).", props("dollars" to num("Monthly budget")), listOf("dollars")))
        put(tool("briefing", "His morning briefing: weather and roads, next shift, calendar, the baby overnight, money and the Macan goal, lists, reminders. Use for 'brief me', 'morning briefing', 'what's my day look like', 'what's the plan today'. Only when he asks.", props(), listOf()))
        put(tool("bills_list", "His bills: what's due and when, and about how much is due in the next 30 days.", props(), listOf()))
        put(tool("bill_add", "Track a bill he tells you about (reminders two days before and on the day).",
            props("name" to str("Short name, e.g. Phone, Rent, Car insurance"), "amount" to num("Amount in dollars, if known"), "due" to str("Next due date yyyy-MM-dd"), "monthly" to bool("Repeats every month")), listOf("name", "due")))
        put(tool("bill_paid", "Mark a bill paid (a monthly one moves on to next month).", props("name" to str("The bill")), listOf("name")))
        put(tool("bill_remove", "Stop tracking a bill.", props("name" to str("The bill")), listOf("name")))
        put(tool("life_sweep", "Check his recent texts and notifications for new bills and appointments right now (about five seconds).", props(), listOf()))
        put(tool("appts_list", "His (and Ziyair's) upcoming appointments, plus a heads-up if a checkup is due to be booked.", props(), listOf()))
        put(tool("appt_add", "Add an appointment: puts it on his calendar, reminds him the evening before and ninety minutes before.",
            props("what" to str("Short, e.g. Dentist, Ziyair's checkup"), "date" to str("yyyy-MM-dd"), "time" to str("24-hour HH:MM, if known"), "where" to str("Place, if known")), listOf("what", "date")))
        put(tool("appt_remove", "Remove an appointment and its reminders.", props("what" to str("Part of its name")), listOf("what")))
        put(tool("set_baby_birthday", "Save the baby's birthday so his usual checkups can be tracked.", props("date" to str("yyyy-MM-dd")), listOf("date")))
        put(tool("packages_list", "His deliveries: what's on the way, out for delivery, or just delivered.", props(), listOf()))
        put(tool("package_done", "Stop following a package (by part of its name).", props("what" to str("Part of the item name")), listOf("what")))
        put(tool("supplies_list", "The supplies whose prices are watched, their latest prices, and any current deal.", props(), listOf()))
        put(tool("supply_watch", "Start watching the price of something he rebuys.", props("item" to str("e.g. Pampers size 1 diapers")), listOf("item")))
        put(tool("supply_unwatch", "Stop watching the price of something.", props("item" to str("The item")), listOf("item")))
        put(tool("supply_check", "Check current prices on the watched supplies right now (about twenty seconds).", props(), listOf()))
        put(tool("pursuit_add", "Pursuit (his clothing brand): save an idea (kind idea, tag design/drop/post/collab), a shot for the shoot list (kind shot), a launch to-do (kind task, who = him or his partner) or an order (kind order). Never sends anything.",
            props("kind" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("idea", "shot", "task", "order"))), "text" to str("The thing itself, in his words"),
                "tag" to str("design, drop, post, collab, or a product like hoodie"), "who" to str("For tasks: who's doing it")), listOf("kind", "text")))
        put(tool("pursuit_list", "Pursuit: read back open ideas, shots, launch to-dos or orders, optionally matching words (e.g. hoodie).",
            props("kind" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("idea", "shot", "task", "order"))), "query" to str("Words to match, optional")), listOf()))
        put(tool("pursuit_done", "Pursuit: tick off a to-do, shot or order (or retire an idea) by a few words of it.", props("text" to str("Words from the item")), listOf("text")))
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
        put(tool("simulate", "Run a life simulation against his real numbers and days: 'what if I dash Saturdays too', 'what if we move to a 1,400 a month place', 'when do I get the Macan if I save 50 more a week'. Runs in the background and reports back.", props("question" to str("The what-if, with every detail he gave")), listOf("question")))
        put(tool("self_check", "Check yourself now: your voice, ears, brain, reminders and permissions; fix what you can and report.", props(), listOf()))
        put(tool("baby_patterns", "Ziyair's patterns from his logged feeds and sleep: how often he eats now vs last week, his longest night stretch and where it's heading, signs of a growth spurt, the next checkup.", props(), listOf()))
        put(tool("run_skill", "Run one of the skills you built for yourself, by name.", props("name" to str("The skill's name")), listOf("name")))
        put(tool("upgrade_now", "Look back over the week and propose upgrades for yourself (new skills, lessons, things to build) for him to approve. When he says 'upgrade yourself' or 'get better'.", props(), listOf()))
        put(tool("upgrades_list", "The upgrades waiting for his OK, and the skills you've built.", props(), listOf()))
        put(tool("upgrade_decide", "He approved or turned down a waiting upgrade.", props("which" to str("Words from its title, or its id"), "approve" to bool("true to approve, false to drop")), listOf("which", "approve")))
        put(tool("recall", "Search his whole life timeline: everything said between you two, feeds, diapers and sleep, money in and out, bills, appointments, deliveries, messages that came in, reminders, jobs, your own thoughts. For any question about the past: 'when did...', 'last time...', 'what did X say about...', 'how many times...', 'have I ever...'.",
            props("query" to str("Key words to look for, with synonyms (e.g. 'fever temperature sick')"), "from" to str("Only from this day, yyyy-MM-dd"), "to" to str("Only up to this day, yyyy-MM-dd"),
                "kinds" to str("Optional, comma separated: him, jarvis, baby, earned, expense, purchase, bill, appointment, package, message, reminder, journal, list, job, thought")), listOf("query")))
        put(tool("scan_phone", "Get to know him from his own phone: reads back through his texts from businesses, calendar, birthdays, apps and his shopping apps' order history (like Walmart), and learns his bills, what he buys, his routines and who matters. Takes a few minutes in the background. Only when he asks.",
            props("screens" to bool("Also walk through shopping apps' order history on screen (default true)")), listOf()))
        put(tool("dash_day", "He's dashing or not on a given day: 'not dashing today', 'taking tomorrow off', 'I'm dashing tonight after all'. Jarvis only plans shifts and targets on dash days.",
            props("on" to bool("true if he's dashing, false if it's a day off"), "tomorrow" to bool("About tomorrow instead of today")), listOf("on")))
        put(tool("run_job", "A whole job with several parts that needs planning across his life: 'handle my week', 'get us ready for tomorrow', 'sort out everything for Ziyair's checkup', 'what do I need to do before payday, and do it'. Runs in the background with all the agents and reports back when done.",
            props("goal" to str("The whole job, with every detail he gave")), listOf("goal")))
        put(tool("follow_up_add", "Keep something open and chase it over the next days: a reply he's waiting on, a delivery, something due by a date. Closes itself when the reply or package arrives; brings it up if it slips.",
            props("what" to str("What to follow up on, short"), "kind" to str("reply, delivery, date or task"), "who" to str("Who he's waiting on, for a reply"),
                "in_hours" to num("Due in this many hours"), "date" to str("Or due on yyyy-MM-dd"), "time" to str("HH:mm on that date"),
                "then" to str("remind (default) or draft (offer to send a nudge)")), listOf("what")))
        put(tool("follow_ups_list", "What's open to follow up on.", props(), listOf()))
        put(tool("follow_up_done", "Close a follow-up (it's sorted, or he says drop it).", props("which" to str("Words from it, or its id")), listOf("which")))
        put(tool("mind_recent", "What Jarvis has been thinking about and doing on his own lately: things he noticed, did, suggested, and his latest idea.", props(), listOf()))
        put(tool("meal_save", "Save one dinner to this week's meal plan (replaces that day's meal).", props("day" to str("Monday to Sunday"), "name" to str("The meal, short"), "ingredients" to str("What to buy for it, comma separated")), listOf("day", "name", "ingredients")))
        put(tool("meals_week", "This week's meal plan.", props(), listOf()))
        put(tool("meal_tonight", "What's for dinner tonight on the meal plan.", props(), listOf()))
        put(tool("meals_to_list", "Put the meal plan's ingredients on the shopping list.", props(), listOf()))
        put(tool("cheapest_store", "Which store had the lowest grocery prices on the last price check.", props(), listOf()))
        put(tool("money_week", "Where his money went: this week or last week, by category, from his bank, card and Cash App notifications.", props("last_week" to bool("Last week instead of this week")), listOf()))
        put(tool("subscriptions_list", "Charges that repeat about monthly (subscriptions he might have forgotten).", props(), listOf()))
        put(tool("draft_text", "Get a text or reply ready BEFORE reading it back to him (it is not sent). Then read the exact words back in double quotes and ask. When he says yes, it goes out straight away.",
            props("who" to str("Contact name, or the person whose message he's replying to"), "message" to str("The exact words"), "reply" to bool("true when it's a reply to a message he just got")), listOf("who", "message")))
        put(tool("judge_offer", "Is the DoorDash offer on his screen right now a good one? Reads the pay and miles and gives the verdict. Never accepts or declines it.", props(), listOf()))
        put(tool("offer_rule", "Change DoorDash order judging: minimum dollars per mile, or turn it on/off.", props("per_mile" to num("Minimum $ per mile"), "on" to bool("Judge offers or not")), listOf()))
        put(tool("driving_mode", "Turn driving mode on or off (short answers, messages read aloud).", props("on" to bool("true for on")), listOf("on")))
        // ---- connections ----
        put(tool("email_check", "Read his Gmail now for new bills, receipts and delivery emails and hand them to the right agents (read only; never sends or deletes).", props(), listOf()))
        put(tool("email_search", "Look something up in his Gmail (read only), e.g. 'from:landlord', 'order Walmart', 'Avista bill'. Returns the five newest matches.",
            props("query" to str("Gmail search words")), listOf("query")))
        put(tool("receipts", "Receipts and order confirmations from his email, with totals.", props("days" to num("How many days back, default 7")), listOf()))
        put(tool("health_today", "His sleep last night and his steps today and yesterday, from Health Connect.", props(), listOf()))
        put(tool("youtube_stats", "His YouTube channel: subscribers, views, videos, how his latest uploads are doing, and the change since last time.", props(), listOf()))
        put(tool("telegram_send", "Send something to his Telegram chat so he has it on his laptop (a list, a link, an address, notes). Only ever to his own chat.",
            props("text" to str("What to send")), listOf("text")))
        put(tool("drive_time", "How long the drive is right now with live traffic (and whether traffic is worse than usual), from where he is or a starting place.",
            props("to" to str("Destination, place name or address"), "from" to str("Optional start; leave out for where he is now")), listOf("to")))
        put(tool("gas_prices", "The cheapest regular gas near him (or near a place), with the station and distance.", props("near" to str("Optional place; leave out for where he is")), listOf()))
        put(tool("nest_devices", "His Google Nest devices on Device Access (thermostat, cameras, doorbell) and their state.", props(), listOf()))
        put(tool("nest_thermostat", "Change the Nest thermostat: mode heat, cool, auto or off, and/or a temperature in °F.",
            props("mode" to str("heat, cool, auto or off"), "temp_f" to num("Target temperature in °F"), "which" to str("Thermostat name if he has more than one")), listOf()))
        put(tool("backup_now", "Back up Jarvis's memory to his Google Drive now (encrypted with his passphrase).", props(), listOf()))
        put(tool("backup_status", "When Jarvis's memory was last backed up to Drive.", props(), listOf()))
    }

    fun run(name: String, input: JSONObject): String { return try {
        if (name.startsWith("agent_")) return Agents.run(context, name.removePrefix("agent_"), input.optString("task"), input.optBoolean("confirmed"))
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
                val (name, number) = findNumber(input.getString("who")) ?: return closeContact(input.getString("who"))?.let { "No contact called that, but there's $it. Ask him if he meant $it; if he says yes, call $it." } ?: "No contact found for that name."
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Calling $name."
            }
            "send_text" -> {
                if (!input.optBoolean("confirmed")) return "Not sent. Read the message back and ask him to confirm first."
                val msg = input.getString("message")
                if (!readBackOk(msg)) return NOT_READ_BACK
                // if they messaged recently, reply right in that chat (no tap needed)
                Inbox.findByName(partnerAlias(input.getString("who")))?.takeIf { it.reply != null }?.let { m ->
                    if (Inbox.reply(context, m, msg)) return "Sent to ${m.sender} on ${m.app}."
                }
                val (name, number) = findNumber(input.getString("who")) ?: return closeContact(input.getString("who"))?.let { "Not sent: no contact called that, but there's $it. Ask him if he meant $it." } ?: "No contact found for that name."
                // he said yes: send it straight away, no tapping
                if (has(Manifest.permission.SEND_SMS)) try {
                    val sms = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                    val parts = sms.divideMessage(msg)
                    if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null) else sms.sendTextMessage(number, null, msg, null, null)
                    Trace.log(context, "text", "sent to $name")
                    return "Sent to $name."
                } catch (e: Exception) { Problems.log(context, "sms: ${e.message}") }
                if (!canOpenApps()) return NEED_OPEN_APPS
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // and press Send for him
                if (JarvisAccess.me != null) {
                    Thread.sleep(1800)
                    for (l in listOf("Send", "Send SMS", "Send message", "Send MMS")) if (JarvisAccess.tap(l, exactOnly = true).startsWith("Tapped")) return "Sent to $name."
                }
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
            "open_app" -> Apps.open(context, input.optString("name").ifBlank { input.optString("app").ifBlank { input.optString("app_name").ifBlank { input.optString("package") } } }.ifBlank { return "Which app?" })
            "media_control" -> Apps.media(context, input.getString("action"))
            "stop_navigation" -> JarvisAccess.stopNavigation(context)
            "screen_read" -> JarvisAccess.read()
            "phone_task" -> Hands.run(context, input.getString("task"), input.optBoolean("confirmed"))
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
                if (!readBackOk(input.optString("message"))) return NOT_READ_BACK
                val m = Inbox.find(input.optString("who")) ?: return "I can't find a recent message from them to reply to. Use send_text instead if it's a text."
                if (m.reply == null) return "That app doesn't allow quick replies from notifications."
                if (Inbox.reply(context, m, input.getString("message"))) "Replied to ${m.sender} on ${m.app}." else "The reply didn't go through."
            }
            "remember" -> if (Memory.add(context, input.getString("fact"))) "Saved to memory." else "Already remembered."
            "memory_list" -> Memory.listNumbered(context)
            "learn_lesson" -> Coach.addLesson(context, input.optString("lesson"), "correction")
            "memory_fix" -> Memory.fix(context, input.optInt("number"), input.optString("new_fact"))
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
            "judge_offer" -> OfferJudge.now(context)
            "recall" -> Timeline.recall(context, input)
            "baby_patterns" -> BabyInsights.forJarvis(context)
            "simulate" -> Simulator.start(context, input.optString("question"))
            "self_check" -> SelfCare.now(context)
            "run_skill" -> Skills.byName(context, input.optString("name"))?.let { Skills.run(context, it) } ?: "No skill called that."
            "upgrade_now" -> Upgrade.start(context)
            "upgrades_list" -> Upgrade.list(context)
            "upgrade_decide" -> Upgrade.decide(context, input.optString("which"), input.optBoolean("approve", true))
            "scan_phone" -> Scan.start(context, input.optBoolean("screens", true))
            "dash_day" -> DashDays.set(context, input.optBoolean("on", true), input.optBoolean("tomorrow"))
            "run_job" -> Jobs.start(context, input.optString("goal"))
            "follow_up_add" -> Loops.add(context, input)
            "follow_ups_list" -> Loops.list(context)
            "follow_up_done" -> Loops.done(context, input.optString("which"))
            "mind_recent" -> Mind.recent(context)
            "meal_save" -> Meals.save(context, input.getString("day"), input.getString("name"), input.optString("ingredients"))
            "meals_week" -> Meals.week(context)
            "meal_tonight" -> Meals.tonight(context)
            "meals_to_list" -> Meals.toList(context)
            "cheapest_store" -> Meals.cheapest(context)
            "money_week" -> Spending.week(context, input.optBoolean("last_week"))
            "subscriptions_list" -> Spending.subscriptions(context)
            "draft_text" -> { Prefs(context).sp.edit().putString("draft", JSONObject().put("who", input.getString("who")).put("message", input.getString("message"))
                .put("reply", input.optBoolean("reply")).put("at", System.currentTimeMillis()).toString()).apply()
                "Ready, not sent. Now read it back to him word for word in double quotes and ask if you should send it." }
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
                if (v !in Piper.NAMES) "Voices available: Jarvis (his own blended voice), Alan, George, Lewis, Daniel, Fable." else {
                    Piper.setPick(context, v)
                    if (Piper.pickReady(context)) "Switched to ${v.replaceFirstChar { it.uppercase() }}." else {
                        Thread { Piper.download(context) }.start()
                        "Downloading the full-quality voices now (about 350 MB, once, best on Wi-Fi); I'll switch to ${v.replaceFirstChar { it.uppercase() }} as soon as it's ready."
                    }
                }
            }
            "set_home_wifi" -> HomeNet.setHere(context)
            "list_add" -> Lists.add(context, input.getString("items"), input.optString("list")).also { Supplies.noticeListAdd(context, input.getString("items")) }
            "list_check" -> Lists.check(context, input.getString("items"), input.optString("list"))
            "list_read" -> Lists.read(context, input.optString("list"))
            "list_clear" -> Lists.clear(context, input.optString("list"), input.optBoolean("everything"))
            "log_expense" -> input.optDouble("amount", 0.0).let { amt -> if (amt.isNaN() || amt <= 0.0) "Not logged yet: no amount. Ask him how much it was, then log it." else Expenses.log(context, amt, input.optString("what"), input.optString("day")) }
            "expenses_summary" -> Expenses.week(context)
            "spending" -> Spend.report(context)
            "set_budget" -> Spend.setBudget(context, input.getDouble("dollars"))
            "status_report" -> Status.report(context)
            "briefing" -> Briefing.build(context)
            "bills_list" -> Bills.list(context)
            "bill_add" -> { val due = try { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(input.getString("due"))?.time } catch (_: Exception) { null }
                if (due == null) "Need the due date as year-month-day." else "Tracking it: " + Bills.upsert(context, input.getString("name"), input.optDouble("amount", Double.NaN).takeIf { !it.isNaN() }, due, if (input.optBoolean("monthly")) "monthly" else "none") }
            "bill_paid" -> Bills.paid(context, input.getString("name"))
            "bill_remove" -> Bills.remove(context, input.getString("name"))
            "life_sweep" -> LifeWatch.sweep(context)
            "appts_list" -> Appts.list(context)
            "appt_add" -> { val t = input.optString("time").takeIf { Regex("\\d{1,2}:\\d{2}").matches(it) }
                val at = try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(input.getString("date") + " " + (t ?: "09:00"))?.time } catch (_: Exception) { null }
                if (at == null) "Need the date as year-month-day." else "Added: " + Appts.upsert(context, input.getString("what"), at, t != null, input.optString("where").ifBlank { null }) }
            "appt_remove" -> Appts.remove(context, input.getString("what"))
            "set_baby_birthday" -> Appts.setBirthday(context, input.getString("date"))
            "packages_list" -> Packages.list(context)
            "package_done" -> Packages.done(context, input.optString("what"))
            "supplies_list" -> Supplies.list(context)
            "supply_watch" -> Supplies.watch(context, input.getString("item"))
            "supply_unwatch" -> Supplies.unwatch(context, input.getString("item"))
            "supply_check" -> Supplies.check(context)
            "pursuit_add" -> Pursuit.add(context, input.optString("kind", "idea"), input.getString("text"), input.optString("tag").ifBlank { null }, input.optString("who").ifBlank { null })
            "pursuit_list" -> Pursuit.list(context, input.optString("kind").ifBlank { null }, input.optString("query").ifBlank { null })
            "pursuit_done" -> Pursuit.done(context, input.getString("text"))
            "run_protocol" -> Protocols.run(context, input.getString("name"))
            "lights" -> Lights.set(context, input.optString("which", "all"), if (input.has("on")) input.optBoolean("on") else null,
                if (input.has("brightness")) input.optInt("brightness") else null, input.optString("color").ifBlank { null })
            "find_phone" -> FindPhone.ring(context)
            "email_check" -> Gmail.sweep(context)
            "email_search" -> Gmail.search(context, input.getString("query"))
            "receipts" -> Gmail.receiptsSummary(context, input.optInt("days", 7).coerceIn(1, 90))
            "health_today" -> HealthLink.today(context)
            "youtube_stats" -> YouTube.stats(context)
            "telegram_send" -> Telegram.send(context, input.getString("text"))
            "drive_time" -> Maps.driveTime(context, input.getString("to"), input.optString("from").ifBlank { null })
            "gas_prices" -> Maps.gas(context, input.optString("near").ifBlank { null })
            "nest_devices" -> Nest.list(context)
            "nest_thermostat" -> Nest.thermostat(context, input.optString("which").ifBlank { null }, input.optString("mode").ifBlank { null }, if (input.has("temp_f")) input.optDouble("temp_f") else null)
            "backup_now" -> DriveBackup.backupNow(context)
            "backup_status" -> DriveBackup.status(context)
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

    /** The confirm rule, in code: a message only goes out if Jarvis read those exact words back in the last few minutes. */
    private fun readBackOk(message: String): Boolean {
        val h = Prefs(context).history()
        val recent = (h.length() - 1 downTo maxOf(0, h.length() - 6)).map { h.getJSONObject(it) }
            .filter { it.optString("role") == "assistant" && System.currentTimeMillis() - it.optLong("at") < 10 * 60_000L }.take(2).map { it.optString("content") }
        val ok = Fast.readBack(recent, message)
        if (!ok) Trace.log(context, "blocked", "not sent, wasn't read back: \"${message.take(80)}\"")
        return ok
    }

    /** "Call Mom": calls straight away, but only when the name clearly matches one contact (otherwise Claude sorts it out). */
    fun callExact(who: String): String? {
        val w = who.lowercase().trim()
        val hit = findNumber(who)?.takeIf { (n, _) -> val l = n.lowercase(); l == w || l.startsWith("$w ") || l.split(" ").contains(w) || partnerAlias(who) != who } ?: return null
        return run("call", JSONObject().put("who", hit.first)).takeIf { it.startsWith("Calling") }
    }

    /** "Yes" to a text Jarvis just read back: send exactly that, through the same checks as always. Null if there's nothing waiting. */
    fun sendDraft(lastReply: String): String? {
        val p = Prefs(context)
        val d = try { JSONObject(p.sp.getString("draft", "") ?: "") } catch (_: Exception) { return null }
        if (System.currentTimeMillis() - d.optLong("at") > 5 * 60_000L) return null
        val msg = d.optString("message"); if (!Fast.readBack(listOf(lastReply), msg)) return null
        p.sp.edit().remove("draft").apply()
        val input = JSONObject().put("who", d.optString("who")).put("message", msg).put("confirmed", true)
        return if (d.optBoolean("reply")) run("reply_message", input).let { if (it.startsWith("I can't find")) run("send_text", input) else it } else run("send_text", input)
    }

    /** A misheard name ("Justustice"): the one contact it's clearly meant to be, or null. */
    private fun closeContact(who: String): String? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val names = ArrayList<String>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext() && names.size < 3000) c.getString(0)?.let { names.add(it) } }
        return Fast.closestName(who, names)
    }

    fun findNumber(whoIn: String): Pair<String, String>? {
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
        const val NOT_READ_BACK = "Not sent: those exact words weren't read back to him. Read the exact message back in quotes and ask him first."
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
