package com.ziyair.jarvis

import java.util.Locale

/**
 * Rules for the protocols he makes himself (lab idea L11). Plain Kotlin (no Android), so it can be unit tested.
 * A protocol is a name plus a few steps, each step being one of Jarvis's safe phone/home tools.
 */
object ProtocolRules {
    const val MAX_STEPS = 8
    const val MAX_PROTOCOLS = 20

    /** Steps a protocol may use: phone, music, lights, timers, info. */
    val ALLOWED = setOf(
        "lights", "phone_control", "quiet_mode", "open_app", "play_music", "media_control", "set_timer", "set_alarm",
        "remind_me", "driving_mode", "get_weather", "calendar_events", "status_report", "list_read", "house_speaker"
    )

    /** Things that talk to other people or act on the screen: never inside a protocol. */
    private val PEOPLE_OR_SCREEN = setOf(
        "send_text", "call", "reply_message", "answer_call", "decline_call", "screen_tap", "screen_type", "screen_action"
    )

    val BUILT_IN = setOf("dash", "work", "baby", "night", "feed", "home", "goodnight", "good night", "sleep", "bed")

    /** "The Gym Protocol" -> "gym", "gym mode" -> "gym". */
    fun normName(raw: String): String {
        var t = raw.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(the|my) "), "")
        t = t.replace(Regex(" (protocol|mode|routine)$"), "")
        return t.trim()
    }

    /** Null when fine, otherwise the short spoken reason it can't be saved. */
    fun check(rawName: String, tools: List<String>, existing: Set<String>): String? {
        val name = normName(rawName)
        if (name.length < 2 || name.length > 30) return "I need a short name for it, sir."
        if (name in BUILT_IN) return "That one's built in, sir. Pick another name."
        if (tools.any { it in PEOPLE_OR_SCREEN }) return "I can't put texting, calls or screen taps in a protocol, sir."
        if (tools.isEmpty()) return "That protocol has nothing in it yet, sir."
        if (tools.size > MAX_STEPS) return "That's more than $MAX_STEPS steps, sir. Trim it down a little."
        val bad = tools.filter { it !in ALLOWED }
        if (bad.isNotEmpty()) return "I can only put phone, music, lights, timers and info in a protocol, sir."
        if (name !in existing && existing.size >= MAX_PROTOCOLS) return "You've got $MAX_PROTOCOLS protocols already, sir. Delete one first."
        return null
    }

    /**
     * Instant matching of what he said ("gym protocol", "run gym", "start the gym mode") against his saved names.
     * Returns the saved name or null. Bare names only match when the phrase also says protocol/mode/run/start,
     * so ordinary words in a sentence never set one off.
     */
    fun match(spoken: String, names: Collection<String>): String? {
        val t = spoken.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^((hey|ok|okay) )?(jarvis )?"), "")
        val m = Regex("^(?:(?:run|start|engage|activate|do|begin|go) )?(?:the |my )?(.+?)(?: (protocol|mode|routine))?$").find(t) ?: return null
        val hasCue = m.groupValues[2].isNotEmpty() || Regex("^(run|start|engage|activate|do|begin|go) ").containsMatchIn(t)
        if (!hasCue) return null
        val said = normName(m.groupValues[1])
        return names.firstOrNull { it == said }
    }

    /** A few spoken words describing a step, for "Gym protocol saved: quiet, music, lights off." */
    fun describe(tool: String, detail: String?): String {
        val d = detail?.trim().orEmpty()
        return when (tool) {
            "play_music" -> if (d.isNotEmpty()) d else "music"
            "open_app" -> if (d.isNotEmpty()) "open $d" else "an app"
            "lights" -> "lights" + if (d.isNotEmpty()) " $d" else ""
            "phone_control" -> when { d.startsWith("quiet_on") -> "quiet"; d.startsWith("quiet_off") -> "ringer on"; d.startsWith("flashlight_on") -> "flashlight"; d.startsWith("volume") -> "volume"; else -> "phone" }
            "set_timer" -> "a timer"
            "set_alarm" -> "an alarm"
            "remind_me" -> "a reminder"
            "media_control" -> if (d.isNotEmpty()) d else "media"
            "driving_mode" -> "driving mode"
            "get_weather" -> "weather"
            "calendar_events" -> "calendar"
            "status_report" -> "status"
            "list_read" -> "your list"
            "quiet_mode" -> "quiet mode"
            "house_speaker" -> "house speaker"
            else -> tool.replace('_', ' ')
        }
    }

    fun savedLine(name: String, steps: List<String>, updated: Boolean): String {
        val title = name.replaceFirstChar { it.uppercase() }
        return "$title protocol ${if (updated) "updated" else "saved"}: ${steps.joinToString(", ")}."
    }

    fun listLine(custom: List<String>): String =
        if (custom.isEmpty()) "Built in: dash, baby, home and goodnight. None of your own yet."
        else "Built in: dash, baby, home and goodnight. Yours: ${custom.joinToString(", ")}."
}
