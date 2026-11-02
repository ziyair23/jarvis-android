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
