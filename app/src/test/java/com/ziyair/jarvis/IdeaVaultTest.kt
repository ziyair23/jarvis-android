package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeaVaultTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private fun say(l: MutableList<IdeaVault.Idea>, s: String, now: Long = t0) = IdeaVault.parse(s)?.let { IdeaVault.handle(l, it, now) }
    private fun add(s: String) = IdeaVault.parse(s) as IdeaVault.Cmd.Add

    @Test fun savePhrasingsKeepHisWords() {
        assertEquals("the silhouette fills with paint on Drop 2", add("Jarvis, Pursuit idea: the silhouette fills with paint on Drop 2, sir.").text)
        assertEquals("oxblood hoodie, cream wordmark", add("new brand idea oxblood hoodie, cream wordmark").text)
        assertEquals("film the hoodie on the bridge at dusk", add("video idea: film the hoodie on the bridge at dusk").text)
        assertEquals("video", add("video idea: film the hoodie on the bridge at dusk").kind)
        assertEquals("drop", add("save a Pursuit drop idea that drops chain like TV episodes").kind)
        assertEquals("chain the drops like TV episodes", add("idea for Pursuit: chain the drops like TV episodes").text)
        assertEquals("post", add("caption idea discipline over motivation, every single day").kind)
        assertEquals("collab", add("collab idea for the brand team up with a local barber").kind)
        assertNull(add("Pursuit idea: oxblood tee").kind)   // kind guessed when saved
    }

    @Test fun notIdeas() {
        listOf("any ideas?", "any ideas for the caption", "what ideas do you have", "I have an idea let's go to the park", "idea",
            "Pursuit idea", "Pursuit idea: hoodie", "what's a good idea for dinner", "say that again", "note that she owes me twenty")
            .forEach { assertNull(it, IdeaVault.parse(it)) }
    }

    @Test fun kindsGuessed() {
        assertEquals("design", IdeaVault.guessKind("oxblood hoodie with the cream wordmark"))
        assertEquals("video", IdeaVault.guessKind("TikTok of the hoodie on the bridge"))
        assertEquals("drop", IdeaVault.guessKind("price the first drop at 45"))
        assertEquals("idea", IdeaVault.guessKind("work in silence, let the results talk"))
    }

    @Test fun saveFindAndRead() {
        val l = mutableListOf<IdeaVault.Idea>()
        assertEquals("Saved to Pursuit ideas, sir.", say(l, "Pursuit idea: oxblood hoodie with the cream wordmark", t0))
        say(l, "Pursuit idea: black hoodie, mint diamond on the sleeve", t0 + min)
        say(l, "video idea: TikTok of my hoodie on the bridge at dusk", t0 + 2 * min)
        say(l, "Pursuit idea: tees with the blank dot surrounded by colour", t0 + 3 * min)
        assertEquals("3 on hoodies, sir. Best match: TikTok of your hoodie on the bridge at dusk. Then: black hoodie, mint diamond on the sleeve. And: oxblood hoodie with the cream wordmark.",
            say(l, "what Pursuit ideas do I have for hoodies?", t0 + 4 * min))
        assertEquals("One video idea, sir: TikTok of your hoodie on the bridge at dusk.", say(l, "read my video ideas", t0 + 4 * min))
        assertEquals("No Pursuit ideas on caps yet, sir.", say(l, "any Pursuit ideas about caps", t0 + 4 * min))
        assertEquals("4 Pursuit ideas, sir. Newest: tees with the blank dot surrounded by colour. Then: TikTok of your hoodie on the bridge at dusk. And: black hoodie, mint diamond on the sleeve.",
            say(l, "read my Pursuit ideas", t0 + 5 * min))
        assertEquals("4 Pursuit ideas saved, sir.", say(l, "how many Pursuit ideas do I have", t0 + 5 * min))
        assertEquals("1 video idea saved, sir.", say(l, "how many video ideas have I got", t0 + 5 * min))
    }

    @Test fun twoResultsAndEmpty() {
        val l = mutableListOf<IdeaVault.Idea>()
        assertEquals("No Pursuit ideas yet, sir.", say(l, "read my ideas"))
        assertEquals("No drop ideas yet, sir.", say(l, "how many drop ideas do I have"))
        say(l, "drop idea: first drop is the blank dot tee", t0); say(l, "drop idea: second drop is finding the passion", t0 + min)
        assertEquals("2 drop ideas, sir. Newest: second drop is finding the passion. Then: first drop is the blank dot tee.", say(l, "what drop ideas do I have", t0 + 2 * min))
    }

    @Test fun scratchTheLastOne() {
        val l = mutableListOf<IdeaVault.Idea>()
        assertEquals("No Pursuit ideas yet, sir.", say(l, "scratch that idea"))
        say(l, "Pursuit idea: oxblood hoodie", t0); say(l, "Pursuit idea: mint diamond cap", t0 + min)
        assertEquals("Scratched: mint diamond cap.", say(l, "Jarvis, scratch that idea", t0 + 2 * min))
        assertEquals(1, l.size)
        assertEquals("Scratched: oxblood hoodie.", say(l, "delete my last Pursuit idea", t0 + 3 * min))
    }

    @Test fun duplicatesLimitAndStorage() {
        val l = mutableListOf<IdeaVault.Idea>()
        IdeaVault.add(l, "Oxblood hoodie", null, t0); IdeaVault.add(l, "oxblood hoodie", null, t0 + min)
        assertEquals(1, l.size)
        (1..IdeaVault.MAX + 5).forEach { IdeaVault.add(l, "idea number $it", null, t0 + it * min) }
        assertEquals(IdeaVault.MAX, l.size)
        assertTrue(l.none { it.text == "Oxblood hoodie" || it.text == "oxblood hoodie" })
        val back = IdeaVault.load(IdeaVault.dump(listOf(IdeaVault.Idea(t0, "design", "line one\nline two"))) + "\nbroken")
        assertEquals(1, back.size); assertEquals("line one line two", back[0].text); assertEquals("design", back[0].kind)
    }

    @Test fun stemsAndClaudeView() {
        assertEquals(IdeaVault.stem("hoodie"), IdeaVault.stem("hoodies"))
        assertEquals(IdeaVault.stem("story"), IdeaVault.stem("stories"))
        assertEquals(IdeaVault.stem("tee"), IdeaVault.stem("tees"))
        assertEquals(IdeaVault.stem("day"), IdeaVault.stem("days"))
        val l = mutableListOf(IdeaVault.Idea(t0, "design", "oxblood hoodie"), IdeaVault.Idea(t0 + min, "video", "bridge shoot"))
        assertEquals("- D [design] oxblood hoodie", IdeaVault.forClaude(l, "hoodies", null) { "D" })
        assertEquals("No saved Pursuit ideas match.", IdeaVault.forClaude(l, "caps", null) { "D" })
    }

    @Test fun scratchThatLongAfterSavingAsksFirst() {
        val l = mutableListOf<IdeaVault.Idea>()
        say(l, "Pursuit idea: oxblood hoodie", t0)
        assertEquals("Your last saved idea is: oxblood hoodie. Say 'delete my last Pursuit idea' to bin it.", say(l, "scratch that idea", t0 + 11 * min))
        assertEquals(1, l.size)
        assertEquals("Scratched: oxblood hoodie.", say(l, "delete the last idea", t0 + 12 * min))
        assertTrue(l.isEmpty())
    }

    @Test fun kindWordAloneIsABrainstorm() {
        listOf("any drop ideas", "give me caption ideas for the hoodie", "what video ideas").forEach { assertNull(it, IdeaVault.parse(it)) }
        assertTrue(IdeaVault.parse("any Pursuit ideas about caps") is IdeaVault.Cmd.Find)
        assertTrue(IdeaVault.parse("how many video ideas have I got") is IdeaVault.Cmd.Count)
    }
}
