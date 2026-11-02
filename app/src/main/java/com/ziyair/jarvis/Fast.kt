package com.ziyair.jarvis

/**
 * Speed rules, kept pure so they're unit-tested.
 *  - QuickLog: everyday logging he says out loud ("he just ate", "changed him", "I made 40 bucks", "add wipes to the list"),
 *    recognised on the phone with no trip to Claude.
 *  - line(): when a quick action clearly worked, the confirmation he says straight away (no extra trip to phrase "done").
 */
object Fast {
    sealed class Quick {
        data class Baby(val kind: String) : Quick()
        data class Earn(val amount: Double) : Quick()
        data class ListAdd(val items: String) : Quick()
    }

    private fun re(s: String) = Regex("^($s)$")

    /** [t] is already normalised (lowercase, no punctuation, no "hey jarvis"). [baby] is his son's name, lowercase. */
    fun parse(t: String, baby: String): Quick? {
        val who = "he|he's|hes|the baby|baby|little man|" + (if (baby.isNotBlank()) Regex.escape(baby) + "|" else "") + "ziyair|zaire|zyair|ziair"
        val whoIs = "(he's|hes|he is|the baby's|the baby is|baby's|baby is|" + (if (baby.isNotBlank()) "${Regex.escape(baby)}'s|${Regex.escape(baby)} is|" else "") + "ziyair's|ziyair is)"
        when {
            re("($who) (just )?(ate|finished (his|the) bottle|had (a|his) bottle|finished eating|is done eating)|(we )?(just )?fed (him|the baby|$who)|(log|logged) (a |the )?(feed|feeding|bottle)|bottle's done|bottles done|bottle is done").matches(t) -> return Quick.Baby("feed")
            re("(we )?(just )?changed (him|his diaper|the baby|a diaper|$who)|(log |logged )?(a |the )?diaper( change)?|($who) (just )?(pooped|peed|had a dirty diaper)|dirty diaper").matches(t) -> return Quick.Baby("diaper")
            re("$whoIs (asleep|down|out|sleeping|napping)|($who) (just )?(fell asleep|went down|is down for a nap)|(we )?(just )?put (him|the baby) down|(log |logged )?(a |the )?nap").matches(t) -> return Quick.Baby("sleep")
            re("$whoIs (up|awake)|($who) (just )?woke up|he's awake now|hes awake now").matches(t) -> return Quick.Baby("wake")
        }
        // money he made on the road: only clear additions ("made 40 on that one"), never a day total ("made 112 today")
        Regex("^(i )?(just )?(made|got|earned|picked up) (\\d{1,4})( dollars| bucks)?( (on|from|for) (that|this|the last|my last) (one|order|run|delivery|trip|dash))?$").find(t)?.let {
            val n = it.groupValues[4].toDouble(); if (n in 1.0..2000.0) return Quick.Earn(n)
        }
        Regex("^(add|put) (.+?) (to|on) (the |my )?(shopping |grocery )?list$").find(t)?.let { m ->
            val items = m.groupValues[2].trim()
            if (items.isNotBlank() && items.split(" ").size <= 5 && !items.startsWith("that ") && items != "it" && items != "that") return Quick.ListAdd(items)
        }
        return null
    }

    /** What he says after logging a baby event, from what the log returned. */
    fun babyLine(kind: String, out: String): String {
        val next = Regex("Next feed heads-up at ([0-9: ]+[AP]M)").find(out)?.groupValues?.get(1)
        return when (kind) {
            "feed" -> "Feed logged, sir." + (next?.let { " Next one around $it." } ?: "")
            "diaper" -> "Diaper logged."
            "sleep" -> "Logged him down."
            "wake" -> "Logged him awake."
            else -> "Logged."
        }
    }

    /** Tools whose success can be said as-is. Anything not clearly a success goes back to Claude to explain. */
    val FAST = setOf("set_timer", "remind_me", "set_alarm", "cancel_reminders", "call", "log_baby", "log_earnings", "list_add", "log_expense")

    fun line(tool: String, out: String, input: org.json.JSONObject? = null): String? {
        val o = out.trim()
        return when (tool) {
            "set_timer" -> if (o.startsWith("Timer set for")) o else null
            "remind_me" -> if (o.startsWith("Reminder set for")) o else null
            "set_alarm" -> if (o.startsWith("Alarm set")) o.replace(" in the Clock app", "") else null
            "cancel_reminders" -> if (o.startsWith("All timers")) o else null
            "call" -> if (Regex("^Calling [^.]{1,40}\\.$").matches(o)) o else null
            "log_baby" -> if (o.startsWith("Logged ")) babyLine(Regex("^Logged (\\w+)").find(o)?.groupValues?.get(1) ?: "", o) else null
            "log_earnings" -> Regex("^Logged\\. Today is now (\\$[0-9,.]+)\\.").find(o)?.let { "Logged. You're at ${it.groupValues[1]} today." }
            "list_add" -> if (o.startsWith("Added ")) o.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." } else null
            "log_expense" -> if (o.startsWith("Logged ")) o.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." } else null
            else -> null
        }
    }
}
