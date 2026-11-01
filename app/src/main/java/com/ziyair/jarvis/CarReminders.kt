package com.ziyair.jarvis

import java.util.Locale

/**
 * Car reminders (lab idea L26). "Remind me in the car to get gas" is said when the car's Bluetooth connects;
 * "remind me when I park to grab the bag" when it drops. Kept on the phone, handled as a reflex (free, works with
 * no signal). Plain Kotlin (no Android) so it can be unit tested; the phone side only loads and saves [dump].
 */
object CarReminders {
    const val PER_SIDE = 5
    const val KEEP_MS = 7 * 24 * 60 * 60_000L
    private const val SEP = '\u0001'

    class Item(val out: Boolean, val at: Long, val text: String)

    sealed class Cmd {
        /** [out] = when he gets out of the car (Bluetooth drops), else when he gets in. */
        class Add(val text: String, val out: Boolean) : Cmd()
        object Read : Cmd()
        object Clear : Cmd()
    }

    // ---------- storage ----------

    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun dump(list: List<Item>) = list.joinToString("\n") { "${if (it.out) "O" else "I"}$SEP${it.at}$SEP${clean(it.text)}" }

    fun load(s: String?): MutableList<Item> {
        val out = mutableListOf<Item>()
        if (s.isNullOrBlank()) return out
        for (line in s.split('\n')) {
            val f = line.split(SEP)
            try { if (f.size >= 3 && f[2].isNotBlank()) out.add(Item(f[0] == "O", f[1].toLong(), f.drop(2).joinToString(" "))) } catch (_: Exception) { }
        }
        return out
    }

    // ---------- what he says ----------

    private const val CAR = "(?:the|my) car"
    private const val IN = "(?:when|once|next time) i(?: get| am|'m)? (?:in|into|back in) $CAR"
    private const val OUT = "(?:when|once) i (?:get out of|leave|step out of) $CAR|(?:when|once) i(?:'m| am) out of $CAR|(?:when|once) i park(?: the car)?"
    private const val TASK = "(?:to |that |about )?(.+)"
    private val LEAD = Regex("^((hey|ok|okay|yo) )?(jarvis )?((can you|could you|would you|will you|please|just) )*")
    private val TAIL = Regex("( (please|sir|jarvis))+$")

    private val ADD_FRONT = Regex("^remind me ($IN|$OUT|in $CAR) $TASK$")
    private val ADD_LEAD = Regex("^($IN|$OUT) remind me $TASK$")
    private val ADD_BACK = Regex("^remind me (?:to |that |about )(.+?) ($IN|$OUT)$")
    private val READ = Regex("^(?:what are|what're|read|read me|any|do i have any|have i got any|got any|what's on) (?:my |the )?car reminders$")
    private val CLEAR = Regex("^(?:clear|cancel|delete|forget|remove|wipe) (?:my |all my |all the |the )?car reminders$")

    private val TIMED = Regex("^(in (an?|\\d+|a few) |tomorrow|tonight|at \\d|this (morning|afternoon|evening))")

    private fun isOut(clause: String) = !clause.startsWith("in ") && Regex(OUT).matches(clause)

    fun norm(said: String): String {
        var t = said.lowercase(Locale.US).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(LEAD, "").replace(TAIL, "")
        return t.trim()
    }

    fun parse(said: String): Cmd? {
        val t = norm(said)
        if (t.isEmpty()) return null
        if (READ.matches(t)) return Cmd.Read
        if (CLEAR.matches(t)) return Cmd.Clear
        ADD_FRONT.find(t)?.let { r -> return add(r.groupValues[2], isOut(r.groupValues[1])) }
        ADD_LEAD.find(t)?.let { r -> return add(r.groupValues[2], isOut(r.groupValues[1])) }
        ADD_BACK.find(t)?.let { r -> return add(r.groupValues[1], isOut(r.groupValues[2])) }
        return null
    }

    private fun add(task: String, out: Boolean): Cmd? {
        val t = task.trim().replace(Regex("^at (home|work)( |$)"), "").replace(Regex("^(to|that|about)( |$)"), "").trim()
        // a time as well ("in an hour", "tomorrow"): that's a timed reminder, so Claude handles it
        if (TIMED.containsMatchIn(t)) return null
        return if (t.isEmpty()) null else Cmd.Add(t, out)
    }

    // ---------- doing it ----------

    fun add(list: MutableList<Item>, text: String, out: Boolean, now: Long) {
        list.removeAll { it.out == out && it.text.equals(clean(text), true) }
        list.add(Item(out, now, clean(text)))
        val side = list.filter { it.out == out }.sortedBy { it.at }
        if (side.size > PER_SIDE) side.take(side.size - PER_SIDE).forEach { list.remove(it) }
    }

    /**
     * Runs a command. [carSet] = his car's Bluetooth name is saved (else nothing could ever trigger);
     * [inCarNow] = he's in the car right now.
     */
    fun handle(list: MutableList<Item>, cmd: Cmd, carSet: Boolean, inCarNow: Boolean, now: Long): String {
        expire(list, now)
        return when (cmd) {
            is Cmd.Add -> when {
                !carSet -> "Save your car's Bluetooth name in Jarvis first, sir."
                else -> {
                    add(list, cmd.text, cmd.out, now)
                    if (cmd.out) "I'll remind you when you park, sir."
                    else if (inCarNow) "I'll remind you next time you're in the car, sir."
                    else "I'll remind you in the car, sir."
                }
            }
            is Cmd.Read -> {
                val ins = list.filter { !it.out }.sortedBy { it.at }.map { spoken(it.text) }
                val outs = list.filter { it.out }.sortedBy { it.at }.map { spoken(it.text) }
                if (ins.isEmpty() && outs.isEmpty()) "No car reminders, sir."
                else listOfNotNull(
                    if (ins.isEmpty()) null else "In the car: ${join(ins)}.",
                    if (outs.isEmpty()) null else "When you park: ${join(outs)}."
                ).joinToString(" ")
            }
            is Cmd.Clear -> {
                val n = list.size
                list.clear()
                when (n) { 0 -> "No car reminders, sir."; 1 -> "Cleared 1 car reminder, sir."; else -> "Cleared $n car reminders, sir." }
            }
        }
    }

    private fun expire(list: MutableList<Item>, now: Long) { list.removeAll { now - it.at > KEEP_MS } }

    /** Takes (removes and returns) what's due as he gets in ([out] false) or out ([out] true), oldest first. */
    fun take(list: MutableList<Item>, out: Boolean, now: Long): List<String> {
        expire(list, now)
        val due = list.filter { it.out == out }.sortedBy { it.at }
        list.removeAll(due)
        return due.map { it.text }
    }

    /** " Reminder: get gas." / " Reminders: get gas, and grab the bag." — "" when nothing's due. */
    fun line(due: List<String>): String {
        val s = due.map { spoken(it) }.filter { it.isNotEmpty() }
        return when (s.size) { 0 -> ""; 1 -> " Reminder: ${s[0]}."; else -> " Reminders: ${join(s)}." }
    }

    private fun join(s: List<String>) = when (s.size) { 1 -> s[0]; 2 -> "${s[0]}, and ${s[1]}"; else -> s.dropLast(1).joinToString(", ") + ", and " + s.last() }

    /** His words turned round to "you" ("call my mom" → "call your mom"), at most 15 words. */
    fun spoken(text: String) = CallNotes.spoken(text)
}
