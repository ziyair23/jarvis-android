package com.ziyair.jarvis

import java.util.Locale

/**
 * Pursuit idea vault (lab idea L12). "Pursuit idea: the silhouette fills with paint on drop two" is saved in his own
 * words with a kind (design, drop, post, video, collab, idea); "what Pursuit ideas do I have for hoodies?" reads back
 * the best three. On the phone, free, works with no signal. Plain Kotlin (no Android) so it can be unit tested.
 */
object IdeaVault {
    const val MAX = 300
    const val READ = 3
    private const val SEP = '\u0001'

    class Idea(val at: Long, val kind: String, val text: String)

    sealed class Cmd {
        class Add(val text: String, val kind: String?) : Cmd()
        /** [about] words to match, [kind] a kind to filter on; both null = newest. */
        class Find(val about: String?, val kind: String?) : Cmd()
        class Count(val kind: String?) : Cmd()
        object DeleteLast : Cmd()
    }

    val KINDS = listOf("design", "drop", "post", "video", "collab")

    // ---------- storage ----------

    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()

    fun dump(l: List<Idea>) = l.joinToString("\n") { "${it.at}$SEP${it.kind}$SEP${clean(it.text)}" }

    fun load(s: String?): MutableList<Idea> {
        val out = mutableListOf<Idea>()
        if (s.isNullOrBlank()) return out
        for (line in s.split('\n')) {
            val f = line.split(SEP)
            try { if (f.size >= 3 && f[2].isNotBlank()) out.add(Idea(f[0].toLong(), f[1], f.drop(2).joinToString(" "))) } catch (_: Exception) { }
        }
        return out
    }

    // ---------- what he says ----------

    private const val TYPE = "design|drop|content|post|video|caption|collab|collaboration|shoot|tiktok|reel"
    private fun kindOf(word: String?): String? = when (word) {
        null, "" -> null
        "design" -> "design"; "drop" -> "drop"; "video", "shoot", "tiktok", "reel" -> "video"
        "content", "post", "caption" -> "post"; "collab", "collaboration" -> "collab"
        else -> null
    }

    private const val LEAD = "((hey|ok|okay|yo) )?(jarvis )?((can you|could you|please|just|go ahead and) )*"
    private val ADD = listOf(
        Regex("^$LEAD(?:(?:save|add|log|note|new|another|got|i've got|i have) )?(?:(?:a|an|one more) )?(?:new )?(?:pursuit|brand) (?:($TYPE) )?idea(?: (?:for|about) (?:pursuit|the brand))?(?: (?:that|is|was))?(?: |$)"),
        Regex("^$LEAD(?:(?:save|add|log|note|new|another|got|i've got|i have) )?(?:(?:a|an|one more) )?(?:new )?($TYPE) idea(?: (?:for|about) (?:pursuit|the brand))?(?: (?:that|is|was))?(?: |$)"),
        Regex("^$LEAD(?:(?:save|add|log|note|new|another|got|i've got|i have) )?(?:(?:a|an|one more) )?(?:new )?idea for (?:pursuit|the brand)(?: (?:that|is|was))?(?: |$)"),
    )
    private val FIND = Regex("^$LEAD(?:what|which|read|read me|any|tell me|give me|go over|what are|what're|list)( my| the)?(?: (pursuit|brand))?(?: ($TYPE))? ideas( do i have| have i got| i have| i've got| did i have| did i save)?(?: (?:for|about|on|with) (.+))?$")
    private val COUNT = Regex("^$LEAD(?:how many)(?: (pursuit|brand))?(?: ($TYPE))? ideas(?: do i have| have i got| i have| i've got| are there| saved)?$")
    private val DELETE = Regex("^$LEAD(?:scratch|delete|remove|forget|undo|bin) (?:that|the last|my last|the) (?:(?:pursuit|brand) )?(?:(?:$TYPE) )?idea$")
    private val TAIL = Regex("(?:^|[\\s,.!?]+)(?:sir|jarvis|please)?[\\s,.!?]*$", RegexOption.IGNORE_CASE)

    private fun normWord(w: String) = w.lowercase(Locale.US).replace(Regex("[^a-z0-9']"), "")

    fun parse(said: String): Cmd? {
        val words = said.trim().split(Regex("\\s+")).filter { normWord(it).isNotEmpty() }
        val n = words.joinToString(" ") { normWord(it) }
        if (n.isEmpty()) return null
        val t = n.replace(Regex("( (please|sir|jarvis))+$"), "")
        DELETE.find(t)?.let { return Cmd.DeleteLast }
        COUNT.find(t)?.let { r -> return Cmd.Count(kindOf(r.groupValues[r.groupValues.size - 1].ifEmpty { null })) }
        FIND.find(t)?.let { r ->
            val g = r.groupValues   // 1-4 lead, then: my/the, pursuit, type, have, about
            val my = g[g.size - 5]; val brand = g[g.size - 4]; val type = g[g.size - 3]; val have = g[g.size - 2]; val about = g[g.size - 1]
            // "any ideas?" is him asking Jarvis for ideas, not the vault
            if (my.trim() != "my" && brand.isEmpty() && type.isEmpty() && have.isEmpty()) return null
            return Cmd.Find(about.ifEmpty { null }, kindOf(type.ifEmpty { null }))
        }
        for (re in ADD) {
            val r = re.find(n) ?: continue
            val type = r.groupValues.drop(1).firstOrNull { it in TYPE.split("|") }
            val cut = r.value.trim().split(" ").filter { it.isNotEmpty() }.size
            val text = words.drop(cut).joinToString(" ").trimStart(':', ',', '-', ';', ' ').replace(TAIL, "").trim()
            if (text.split(" ").count { normWord(it).isNotEmpty() } < 2) return null
            return Cmd.Add(text, kindOf(type))
        }
        return null
    }

    // ---------- kinds and matching ----------

    private val CUES = listOf(
        "video" to Regex("\\b(video|videos|tiktok|tiktoks|reel|reels|youtube|shoot|film|filming|vlog|short|shorts|clip|b-roll|voiceover|promo)\\b"),
        "post" to Regex("\\b(post|posts|caption|captions|carousel|story|stories|instagram|ig|hashtag|hashtags|bio|highlight|highlights)\\b"),
        "collab" to Regex("\\b(collab|collabs|collaboration|partner with|team up|influencer|influencers)\\b"),
        "drop" to Regex("\\b(drop|drops|launch|release|restock|pre-order|preorder|price|pricing|episode)\\b"),
        "design" to Regex("\\b(design|tee|tees|t-shirt|shirt|hoodie|hoodies|crewneck|logo|graphic|print|embroidery|colorway|colourway|silhouette|font|wordmark|diamond|sleeve|cap|hat|jacket|pants|shorts)\\b"),
    )

    /** What kind of idea it is, from his words when he didn't say. */
    fun guessKind(text: String): String {
        val t = text.lowercase(Locale.US)
        return CUES.firstOrNull { it.second.containsMatchIn(t) }?.first ?: "idea"
    }

    private val STOP = setOf("a", "an", "the", "my", "for", "about", "on", "with", "and", "or", "of", "to", "in", "it", "that", "this",
        "idea", "ideas", "pursuit", "brand", "some", "any", "stuff", "things", "thing")

    fun stem(w: String): String {
        val x = w.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        return when {
            // hoodies → hoodie, stories → storie, story → storie: both forms meet
            x.length > 4 && x.endsWith("ies") -> x.dropLast(1)
            x.length > 2 && x.endsWith("y") && x[x.length - 2] !in "aeiou" -> x.dropLast(1) + "ie"
            x.length > 4 && x.endsWith("es") && (x.endsWith("shes") || x.endsWith("ches") || x.endsWith("xes")) -> x.dropLast(2)
            x.length > 3 && x.endsWith("s") && !x.endsWith("ss") -> x.dropLast(1)
            else -> x
        }
    }

    private fun stems(s: String) = s.split(Regex("[\\s,.;:!?/-]+")).map { stem(it) }.filter { it.isNotEmpty() && it !in STOP }.toSet()

    fun find(l: List<Idea>, about: String?, kind: String?): List<Idea> {
        val pool = l.filter { kind == null || it.kind == kind }
        val q = about?.let { stems(it) }.orEmpty()
        if (q.isEmpty()) return pool.sortedByDescending { it.at }
        return pool.map { it to (stems(it.text) + it.kind).count { s -> s in q } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Idea, Int>> { it.second }.thenByDescending { it.first.at })
            .map { it.first }
    }

    // ---------- doing it ----------

    fun add(l: MutableList<Idea>, text: String, kind: String?, now: Long): Idea {
        val i = Idea(now, kind ?: guessKind(text), clean(text))
        l.removeAll { it.text.equals(i.text, true) }
        l.add(i)
        while (l.size > MAX) l.remove(l.minByOrNull { it.at }!!)
        return i
    }

    private fun label(kind: String?) = when (kind) { null -> "Pursuit idea"; "idea" -> "idea"; else -> "$kind idea" }
    private fun plural(n: Int, kind: String?) = if (n == 1) "1 ${label(kind)}" else "$n ${label(kind)}s"

    fun handle(l: MutableList<Idea>, cmd: Cmd, now: Long): String = when (cmd) {
        is Cmd.Add -> { add(l, cmd.text, cmd.kind, now); "Saved to Pursuit ideas, sir." }
        is Cmd.Count -> {
            val n = l.count { cmd.kind == null || it.kind == cmd.kind }
            if (n == 0) "No ${label(cmd.kind)}s yet, sir." else "${plural(n, cmd.kind)} saved, sir."
        }
        is Cmd.DeleteLast -> {
            val last = l.maxByOrNull { it.at }
            if (last == null) "No Pursuit ideas yet, sir." else { l.remove(last); "Scratched: ${spoken(last.text)}." }
        }
        is Cmd.Find -> {
            val hits = find(l, cmd.about, cmd.kind)
            val on = cmd.about?.let { " on ${it.trim()}" }.orEmpty()
            when {
                hits.isEmpty() -> if (on.isEmpty()) "No ${label(cmd.kind)}s yet, sir." else "No ${label(cmd.kind)}s$on yet, sir."
                hits.size == 1 -> "One${if (on.isEmpty()) " ${label(cmd.kind)}" else on}, sir: ${spoken(hits[0].text)}."
                else -> {
                    val top = hits.take(READ).map { spoken(it.text) }
                    val head = if (on.isEmpty()) "${plural(hits.size, cmd.kind)}, sir." else "${hits.size}$on, sir."
                    val lead = if (on.isEmpty()) "Newest" else "Best match"
                    head + " $lead: ${top[0]}." + top.drop(1).mapIndexed { i, s -> if (i == top.size - 2 && top.size > 2) " And: $s." else " Then: $s." }.joinToString("")
                }
            }
        }
    }

    /** His idea read back to him: "I want…" → "you want…", at most 15 words. */
    fun spoken(text: String) = CallNotes.spoken(text)

    /** For Claude's tool: the matches in full, newest or best first, one per line with date and kind. */
    fun forClaude(l: List<Idea>, about: String?, kind: String?, fmt: (Long) -> String): String {
        val hits = find(l, about?.ifBlank { null }, kind?.ifBlank { null }).take(10)
        if (hits.isEmpty()) return "No saved Pursuit ideas match."
        return hits.joinToString("\n") { "- ${fmt(it.at)} [${it.kind}] ${it.text}" }
    }
}
