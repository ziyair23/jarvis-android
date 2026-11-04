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

    // ---------------- reminders, set right on the phone ----------------
    private val NUMW = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "half an" to 0, "half a" to 0)

    /**
     * "remind me to call mom at 5", "remind me at 5 30 pm to grab diapers", "remind me in 20 minutes to flip the laundry",
     * "remind me tomorrow at 9 to call the doctor". When it fires, and what. Null if there's no clear time (Claude asks).
     * [t] is normalised text. Pure, unit-tested.
     */
    fun reminder(t: String, now: java.util.Calendar): Pair<Long, String>? {
        if (!t.startsWith("remind me ")) return null
        if (Regex("\\b(every|each|daily|weekly)\\b").containsMatchIn(t)) return null   // repeating reminders go to Claude
        var s = t.removePrefix("remind me ").trim()
        val tomorrow = Regex("\\btomorrow\\b").containsMatchIn(s); val tonight = Regex("\\b(tonight|this evening)\\b").containsMatchIn(s)
        s = s.replace(Regex("\\b(tomorrow|tonight|this evening|today)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val at = (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        var what: String
        val inRe = Regex("\\bin (\\d{1,3}|${NUMW.keys.joinToString("|")}) ?(minutes?|mins?|hours?|hrs?)\\b")
        val atRe = Regex("\\bat (\\d{1,2})(?: ?(\\d{2}))?(?: ?(am|pm|a m|p m|o'?clock))?\\b")
        val i = inRe.find(s); val a = atRe.find(s)
        when {
            i != null && !tomorrow -> {
                val q = i.groupValues[1]; val n = q.toIntOrNull() ?: NUMW[q] ?: return null
                val mins = if (i.groupValues[2].startsWith("h")) (if (q.startsWith("half")) 30 else n * 60) else (if (q.startsWith("half")) 0 else n)
                if (mins <= 0 || mins > 24 * 60) return null
                at.add(java.util.Calendar.MINUTE, mins); what = s.removeRange(i.range)
            }
            a != null -> {
                var h = a.groupValues[1].toInt(); val m = a.groupValues[2].toIntOrNull() ?: 0
                val ap = a.groupValues[3].replace(" ", "")
                if (h !in 1..23 || m !in 0..59) return null
                when {
                    ap == "pm" && h < 12 -> h += 12
                    ap == "am" && h == 12 -> h = 0
                    ap.isEmpty() || ap.startsWith("o") -> if (h <= 12) {
                        // no am or pm: tonight means evening; tomorrow means a sensible daytime hour; otherwise the next time it comes round
                        if (tonight && h < 12) h += 12
                        else if (tomorrow) { if (h <= 6) h += 12 }
                        else {
                            // the next time it comes round today (morning, then evening); if both have passed, tomorrow
                            val am = h % 12
                            val hit = listOf(am, am + 12).firstOrNull { hh -> (at.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, hh); set(java.util.Calendar.MINUTE, m) }.timeInMillis > now.timeInMillis }
                            h = hit ?: if (am in 7..11) am else am + 12
                        }
                    }
                }
                at.set(java.util.Calendar.HOUR_OF_DAY, h % 24); at.set(java.util.Calendar.MINUTE, m)
                if (tomorrow) at.add(java.util.Calendar.DAY_OF_YEAR, 1) else if (at.timeInMillis <= now.timeInMillis) at.add(java.util.Calendar.DAY_OF_YEAR, 1)
                what = s.removeRange(a.range)
            }
            else -> return null
        }
        what = what.replace(Regex("\\s+"), " ").trim().removePrefix("to ").removePrefix("that ").removeSuffix(" to").trim()
        if (what.split(" ").size < 2 || what.length < 4) return null
        return at.timeInMillis to what
    }

    // ---------------- actions done right on the phone, no trip to Claude ----------------
    sealed class Direct {
        data class Open(val app: String) : Direct()
        data class Call(val who: String) : Direct()
        data class Nav(val to: String) : Direct()
        data class Play(val what: String) : Direct()
        object Yes : Direct()
    }
    private val VAGUE = Regex("^(it|that|this|them|him|her|me|something|anything|some music|music|my music|the music|again|it again|that again|back|later|the next song|next|a song|some songs|songs)$")

    /** Everyday actions that need no thinking. [t] is normalised text. Pure, unit-tested. */
    fun direct(t: String): Direct? {
        Regex("^(open|launch|pull up|bring up)( up)? (the )?(.+?)( app)?$").find(t)?.let { m ->
            val a = m.groupValues[4].trim()
            if (a.split(" ").size <= 3 && !VAGUE.matches(a) && !Regex("\\b(door|window|garage|gate|trunk|link|file|tab)\\b").containsMatchIn(a)) return Direct.Open(a)
        }
        Regex("^(call|ring|dial|phone|facetime)( up)? (.+)$").find(t)?.let { m ->
            val w = m.groupValues[3].trim().removePrefix("my ")
            if (w.split(" ").size <= 3 && !VAGUE.matches(w) && !Regex("\\b(me|back|later|an uber|a lyft|a cab|an ambulance|911)\\b").containsMatchIn(w)) return Direct.Call(w)
        }
        Regex("^(navigate|take me|directions|get me directions|give me directions|drive me|navigate me)( to)? (.+)$").find(t)?.let { m ->
            val d = m.groupValues[3].trim(); if (d.length >= 3 && !VAGUE.matches(d)) return Direct.Nav(d)
        }
        Regex("^(play|put on)( me)? (.+?)( on (youtube music|spotify))?$").find(t)?.let { m ->
            val q = m.groupValues[3].trim()
            if (q.length >= 3 && !VAGUE.matches(q) && !Regex("\\b(video|youtube video|tiktok|game|movie|show|episode|podcast)\\b").containsMatchIn(q)) return Direct.Play(q)
        }
        if (Regex("^(yes|yeah|yep|yup|sure|do it|send it|go ahead|send|ok send it|okay send it|yes send it|yeah send it|yes please|yeah go ahead)( please| it)?$").matches(t)) return Direct.Yes
        return null
    }

    // ---------------- "On it." the moment he asks for something ----------------
    val ACKS = listOf("On it, sir.", "Right away, sir.", "Sending it, sir.")
    private val ACTION = Regex("^(open|launch|start|play|put on|pause|resume|skip|turn (on|off|up|down)|turn the|set|start a|remind|add|log|navigate|take me|get me directions|directions|call|dial|ring|facetime|show me|switch|lock|unlock|dim|brighten|go to)\\b")
    private val QUESTION = Regex("^(what|what's|whats|how|where|when|why|who|which|is|are|was|were|do|does|did|should|would|could i|can i|tell me|explain)\\b")
    private val YES = Regex("^(yes|yeah|yep|yup|sure|do it|send it|go ahead|ok send it|okay send it|yes send it|yes please|send)\\b")

    /**
     * The line to say straight away when he asks Jarvis to DO something (so he knows it landed), or null.
     * [t] is normalised text; [lastReply] is what Jarvis last said (for "yes" to "Shall I send it?"). Pure, unit-tested.
     */
    fun ackFor(t: String, lastReply: String): String? {
        val s = t.replace(Regex("^(can you|could you|would you|will you|please|go ahead and|i need you to|i want you to) "), "").trim()
        if (s.isEmpty() || s.split(" ").size > 14) return null
        if (YES.containsMatchIn(s) && Regex("(?i)(send it|shall i send|want me to send|should i send|send this|reply)").containsMatchIn(lastReply) && lastReply.trimEnd().endsWith("?")) return ACKS[2]
        if (QUESTION.containsMatchIn(s)) return null
        if (Regex("^(text|message|send|reply|tell)\\b").containsMatchIn(s)) return null   // those are read back first, not done straight away
        return if (ACTION.containsMatchIn(s)) ACKS[s.length % 2] else null
    }

    // ---------------- the confirm rule, enforced in code ----------------
    private fun plain(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The messages Jarvis read back, in quotes, in what he last said. Pure. */
    fun quoted(reply: String): List<String> =
        Regex("[\"\u201c\u201d]([^\"\u201c\u201d]{2,600})[\"\u201c\u201d]").findAll(reply).map { it.groupValues[1] }.toList() +
            Regex("(?<![a-zA-Z])'([^']{6,600})'(?![a-zA-Z])").findAll(reply).map { it.groupValues[1] }.toList()

    /**
     * May this message go out? Only if it's (near enough word for word) a message Jarvis read back to him in quotes,
     * so a "yes" can never send something he didn't hear. Pure, unit-tested.
     */
    fun readBack(lastReplies: List<String>, message: String): Boolean {
        val m = plain(message); if (m.length < 2) return false
        return lastReplies.flatMap { quoted(it) }.map { plain(it) }.any { q -> q == m || (q.length >= 8 && (q.contains(m) || m.contains(q)) && minOf(q.length, m.length) * 10 >= maxOf(q.length, m.length) * 9) }
    }

    /** A contact for a misheard name: the one name the heard word starts with or is a letter or two off from. Pure. */
    fun closestName(heard: String, names: List<String>): String? {
        val h = heard.lowercase().replace(Regex("[^a-z ]"), "").trim(); if (h.length < 3) return null
        fun dist(a: String, b: String): Int { val d = IntArray(b.length + 1) { it }; for (i in 1..a.length) { var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t } }; return d[b.length] }
        val scored = names.distinct().mapNotNull { n -> val first = n.lowercase().split(" ").first().replace(Regex("[^a-z]"), ""); if (first.length < 3) return@mapNotNull null
            val score = when { h == first -> 0; h.startsWith(first) && first.length >= 4 -> 1; dist(h.split(" ").first(), first) <= (if (first.length >= 6) 2 else 1) -> 2; else -> 99 }
            if (score < 99) n to score else null }
        val best = scored.minByOrNull { it.second } ?: return null
        return if (scored.count { it.second == best.second } == 1) best.first else null
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

    /** An agent's result Jarvis can say straight away: a short, plain success (not a question, not a problem). Pure. */
    fun agentLine(out: String): String? {
        val o = out.trim()
        if (o.length !in 4..160 || o.contains('?') || o.contains('\n')) return null
        if (Regex("(?i)\\b(couldn'?t|can'?t|cannot|failed|error|problem|not found|no contact|isn'?t|didn'?t|unable|waiting|needs?|confirm|missing|which)\\b").containsMatchIn(o)) return null
        return if (Regex("^(Sent|Replied|Calling|Called|Opened|Playing|Paused|Logged|Added|Saved|Set|Started|Directions|Done|Turned|Lights|Answered|Declined|Marked|Tracking|Stopped|Removed|Watching|Ticked)\\b").containsMatchIn(o)) o else null
    }

    fun line(tool: String, out: String, input: org.json.JSONObject? = null): String? {
        val o = out.trim()
        if (tool.startsWith("agent_") && tool != "agent_research" && tool != "agent_builder") return agentLine(o)
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

/** When Jarvis may answer without hearing his name. Pure, unit-tested. */
object FollowUp {
    /** Why this follow-up isn't for him (or null if it is): too long after he spoke, too many in a row, or long chatter. */
    fun reject(text: String, msSinceHeSpoke: Long, inRow: Int): String? {
        val words = text.trim().split(Regex("\\s+")).size
        val named = Regex("(?i)\\b(jarvis|jervis|jarvas)\\b").containsMatchIn(text)
        return when {
            msSinceHeSpoke > 25_000 -> "too long since he spoke"
            inRow >= 4 -> "four follow-ups in a row"
            words > 30 && !named -> "long chatter, sounds like people talking"
            else -> null
        }
    }

    /** Jarvis only asked what was said ("I didn't catch that"): don't keep listening after it. */
    fun clarifying(reply: String) = Regex("(?i)(didn'?t (quite )?catch|not quite following|can'?t tell what|could you (say|tell me) (that |what|again)|say that again|came through as fragments|go on, sir|what would you like|i'?m listening)").containsMatchIn(reply)
}
