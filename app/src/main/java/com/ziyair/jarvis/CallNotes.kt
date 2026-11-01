package com.ziyair.jarvis

import java.util.Locale

/**
 * Call notes (lab idea L17). After a call he can say "note that she owes me twenty"; the next time that person
 * calls, the ring announcement carries the note. Notes stay on the phone. Plain Kotlin (no Android) so it can be
 * unit tested; the phone side only loads and saves the text from [dump].
 */
object CallNotes {
    const val WINDOW_MS = 60 * 60_000L
    const val PER_PERSON = 5
    const val MAX_PEOPLE = 50
    const val MAX_WORDS = 15
    private const val SEP = '\u0001'

    class Note(val who: String, val at: Long, val text: String)

    class Book {
        val notes = mutableListOf<Note>()
        /** Recent callers' names, newest first. */
        val callers = mutableListOf<String>()
        var lastCaller = ""
        var lastCallAt = 0L
    }

    sealed class Cmd {
        /** "note that …": goes to whoever called last. */
        class Add(val text: String) : Cmd()
        /** "note for <name> …": [rest] is the name followed by the note. */
        class AddFor(val rest: String) : Cmd()
        class Read(val name: String) : Cmd()
        class Clear(val name: String) : Cmd()
    }

    // ---------- storage ----------

    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun dump(b: Book): String {
        val lines = mutableListOf("C$SEP${clean(b.lastCaller)}$SEP${b.lastCallAt}")
        b.callers.forEach { lines.add("P$SEP${clean(it)}") }
        b.notes.forEach { lines.add("N$SEP${clean(it.who)}$SEP${it.at}$SEP${clean(it.text)}") }
        return lines.joinToString("\n")
    }

    fun load(s: String?): Book {
        val b = Book()
        if (s.isNullOrBlank()) return b
        for (line in s.split('\n')) {
            val f = line.split(SEP)
            try {
                when (f[0]) {
                    "C" -> { b.lastCaller = f[1]; b.lastCallAt = f[2].toLong() }
                    "P" -> if (f[1].isNotBlank()) b.callers.add(f[1])
                    "N" -> b.notes.add(Note(f[1], f[2].toLong(), f.drop(3).joinToString(" ")))
                }
            } catch (_: Exception) { /* skip a damaged line */ }
        }
        return b
    }

    // ---------- names ----------

    /** A caller's name as Jarvis says it: no emoji or symbols. */
    fun display(who: String) = who.replace(Regex("[^\\p{L}\\p{N}' .+-]"), "").replace(Regex("\\s+"), " ").trim()

    fun key(name: String) = name.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun known(b: Book): List<String> = (b.callers + b.notes.sortedByDescending { it.at }.map { it.who }).distinctBy { key(it) }

    /** The person he means by a spoken name: full name first, then first name; the most recent wins a tie. */
    fun findWho(b: Book, spoken: String): String? {
        val k = key(spoken)
        if (k.isEmpty()) return null
        val names = known(b)
        return names.firstOrNull { key(it) == k } ?: names.firstOrNull { key(it).split(" ").first() == k }
    }

    /** Splits "abbey smith she wants the hoodie" into the person and the note, using the names Jarvis knows. */
    fun resolve(b: Book, rest: String): Pair<String, String>? {
        val r = key(rest)
        if (r.isEmpty()) return null
        val cands = known(b).flatMap { n -> listOf(key(n) to n, key(n).split(" ").first() to n) }
            .filter { it.first.isNotEmpty() }.sortedByDescending { it.first.length }
        val hit = cands.firstOrNull { r == it.first || r.startsWith(it.first + " ") }
        // only people Jarvis knows (callers or earlier notes); anything else goes to Claude as usual
            ?: return null
        val after = rest.trim().split(Regex("\\s+")).drop(hit.first.split(" ").size).joinToString(" ")
        return hit.second to after.replace(Regex("^(that|saying|says)\\s+", RegexOption.IGNORE_CASE), "").trim()
    }

    // ---------- commands ----------

    private val P = "(?:my |the |all |all my |all the )?(?:call )?notes? (?:on|for|about) (.+)"

    /** What he said (already lower-cased, punctuation stripped) as a call-note command, or null. */
    fun parse(t: String): Cmd? {
        Regex("^(?:what are|what're|read|read me|any|tell me|what's in) $P$").find(t)?.let { return Cmd.Read(it.groupValues[1].trim()) }
        Regex("^what did i note (?:on|for|about) (.+)$").find(t)?.let { return Cmd.Read(it.groupValues[1].trim()) }
        Regex("^(?:delete|clear|forget|remove|wipe) $P$").find(t)?.let { return Cmd.Clear(it.groupValues[1].trim()) }
        Regex("^(?:note|add a note|make a note|save a note|call note) (?:for|about|on) (.+)$").find(t)?.let { return Cmd.AddFor(it.groupValues[1].trim()) }
        Regex("^(?:note that|make a note that|add a note that|call note|note down that) (.+)$").find(t)?.let { return Cmd.Add(it.groupValues[1].trim()) }
        return null
    }

    /** A call is ringing: remember who, so "note that" right after knows who it's about. */
    fun called(b: Book, who: String, now: Long) {
        val d = display(who)
        if (d.isEmpty()) return
        b.lastCaller = d; b.lastCallAt = now
        b.callers.removeAll { key(it) == key(d) }
        b.callers.add(0, d)
        while (b.callers.size > MAX_PEOPLE) b.callers.removeAt(b.callers.size - 1)
    }

    fun add(b: Book, who: String, text: String, now: Long) {
        b.notes.add(Note(who, now, clean(text)))
        val mine = b.notes.filter { key(it.who) == key(who) }.sortedBy { it.at }
        if (mine.size > PER_PERSON) mine.take(mine.size - PER_PERSON).forEach { b.notes.remove(it) }
        // too many people: drop the person whose newest note is oldest
        while (b.notes.map { key(it.who) }.distinct().size > MAX_PEOPLE) {
            val old = b.notes.groupBy { key(it.who) }.minByOrNull { e -> e.value.maxOf { it.at } }!!.key
            b.notes.removeAll { key(it.who) == old }
        }
    }

    fun notesFor(b: Book, who: String) = b.notes.filter { key(it.who) == key(who) }.sortedByDescending { it.at }

    /** Runs a command. Returns what Jarvis says, or null to let it go to Claude as usual. */
    fun handle(b: Book, cmd: Cmd, now: Long): String? = when (cmd) {
        is Cmd.Add -> {
            if (b.lastCaller.isBlank() || now - b.lastCallAt > WINDOW_MS || now < b.lastCallAt || cmd.text.isBlank()) null
            else { add(b, b.lastCaller, cmd.text, now); "Noted for ${b.lastCaller}, sir." }
        }
        is Cmd.AddFor -> {
            val r = resolve(b, cmd.rest)
            when {
                r == null -> null
                r.second.isBlank() -> "Say the note after the name, sir."
                else -> { add(b, r.first, r.second, now); "Noted for ${r.first}, sir." }
            }
        }
        is Cmd.Read -> {
            val who = findWho(b, cmd.name)
            val list = who?.let { notesFor(b, it) }.orEmpty()
            if (who == null || list.isEmpty()) "No notes on ${who ?: titled(cmd.name)}, sir."
            else "$who, sir: " + list.take(3).joinToString(" ") { spoken(it.text) + "." }
        }
        is Cmd.Clear -> {
            val who = findWho(b, cmd.name)
            val n = if (who == null) 0 else notesFor(b, who).size
            if (who == null || n == 0) "No notes on ${who ?: titled(cmd.name)}, sir."
            else { b.notes.removeAll { key(it.who) == key(who) }; "Cleared ${if (n == 1) "1 note" else "$n notes"} on $who, sir." }
        }
    }

    private fun titled(s: String) = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // ---------- the ring line ----------

    private val SWAP = mapOf("i" to "you", "me" to "you", "my" to "your", "mine" to "yours", "myself" to "yourself",
        "i'm" to "you're", "i'll" to "you'll", "i've" to "you've", "i'd" to "you'd", "im" to "you're")

    /** His note read back to him: "she owes me twenty" → "she owes you twenty", at most 15 words. */
    fun spoken(text: String): String {
        val words = text.trim().trimEnd('.', '!', '?', ',').split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = words.take(MAX_WORDS).map { w -> SWAP[w.lowercase(Locale.US)] ?: w }
        return out.joinToString(" ")
    }

    /** " Your note: she owes you twenty." for a known caller with a note, else "". */
    fun ringNote(b: Book, who: String): String {
        val n = notesFor(b, display(who)).firstOrNull() ?: return ""
        val s = spoken(n.text)
        return if (s.isEmpty()) "" else " Your note: $s."
    }

    /** Puts the note into the ring announcement, before "Answer it?" if it's there. */
    fun ringLine(base: String, note: String): String {
        if (note.isEmpty()) return base
        val q = " Answer it?"
        return if (base.endsWith(q)) base.removeSuffix(q) + note + q else base + note
    }
}
