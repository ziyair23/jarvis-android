package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent tester, L12, against lab/specs/L12.md only (plus the L13 polish). */
class IdeaVaultIndepTest {
    private val t0 = 1_800_000_000_000L
    private fun add(s: String) = IdeaVault.parse(s) as IdeaVault.Cmd.Add
    private fun say(l: MutableList<IdeaVault.Idea>, s: String, now: Long) = IdeaVault.handle(l, IdeaVault.parse(s)!!, now)

    @Test fun at1() {
        assertEquals("the silhouette fills with paint on Drop 2", add("Jarvis, Pursuit idea: the silhouette fills with paint on Drop 2.").text)
        assertEquals("oxblood hoodie, cream wordmark", add("New brand idea: oxblood hoodie, cream wordmark.").text)
        add("Video idea: film the hoodie on the bridge at dusk.").let { assertEquals("film the hoodie on the bridge at dusk", it.text); assertEquals("video", it.kind) }
        assertEquals("chain the drops like TV episodes", add("Idea for Pursuit: chain the drops like TV episodes.").text)
        add("Save a Pursuit drop idea that the first 20 get a sticker").let { assertEquals("the first 20 get a sticker", it.text); assertEquals("drop", it.kind) }
        add("Caption idea: built for the late shift, sir.").let { assertEquals("built for the late shift", it.text); assertEquals("post", it.kind) }
        add("Collab idea for the brand: team up with the bakery").let { assertEquals("team up with the bakery", it.text); assertEquals("collab", it.kind) }
    }

    @Test fun at2() {
        for (s in listOf("any ideas?", "any ideas for the caption", "what ideas do you have", "I have an idea let's go to the park", "idea", "Pursuit idea", "Pursuit idea hoodies", "what's a good idea for dinner"))
            assertNull(s, IdeaVault.parse(s))
    }

    // Asking Jarvis to come up with ideas is the same as "any ideas for the caption": it goes to Claude, not the vault.
    @Test fun brainstormAsksGoToClaude() {
        for (s in listOf("any video ideas for the drop", "give me video ideas for the hoodie", "any caption ideas", "list collab ideas"))
            assertNull(s, IdeaVault.parse(s))
        assertTrue(IdeaVault.parse("read my video ideas") is IdeaVault.Cmd.Find)
        assertTrue(IdeaVault.parse("what video ideas do I have") is IdeaVault.Cmd.Find)
        assertTrue(IdeaVault.parse("what Pursuit ideas do I have for hoodies") is IdeaVault.Cmd.Find)
    }

    @Test fun at3() {
        assertEquals("design", IdeaVault.guessKind("oxblood hoodie with the cream wordmark"))
        assertEquals("video", IdeaVault.guessKind("TikTok of the hoodie on the bridge"))
        assertEquals("drop", IdeaVault.guessKind("price the first drop at 45"))
        assertEquals("idea", IdeaVault.guessKind("work in silence, let the results talk"))
    }

    @Test fun at4to6() {
        val l = mutableListOf<IdeaVault.Idea>()
        say(l, "Pursuit idea: oxblood hoodie with the cream wordmark", t0)
        say(l, "Pursuit idea: black hoodie, mint diamond on the sleeve", t0 + 1)
        say(l, "Video idea: TikTok of my hoodie on the bridge at dusk", t0 + 2)
        assertEquals("Saved to Pursuit ideas, sir.", say(l, "Pursuit idea: blank-dot tees", t0 + 3))
        val back = IdeaVault.load(IdeaVault.dump(l))
        assertEquals("3 on hoodies, sir. Best match: TikTok of your hoodie on the bridge at dusk. Then: black hoodie, mint diamond on the sleeve. And: oxblood hoodie with the cream wordmark.",
            say(back, "what Pursuit ideas do I have for hoodies?", t0 + 4))
        assertEquals("One video idea, sir: TikTok of your hoodie on the bridge at dusk.", say(back, "read my video ideas", t0 + 4))
        assertTrue(say(back, "read my Pursuit ideas", t0 + 4).startsWith("4 Pursuit ideas, sir. Newest: blank-dot tees."))
        assertEquals("4 Pursuit ideas saved, sir.", say(back, "how many Pursuit ideas do I have", t0 + 4))
        assertEquals("1 video idea saved, sir.", say(back, "how many video ideas do I have", t0 + 4))
        val d = mutableListOf<IdeaVault.Idea>()
        say(d, "drop idea: first 20 get a sticker", t0); say(d, "drop idea: price it at 45", t0 + 1)
        assertEquals("2 drop ideas, sir. Newest: price it at 45. Then: first 20 get a sticker.", say(d, "read my drop ideas", t0 + 2))
        assertEquals("Scratched: price it at 45.", say(d, "scratch that idea", t0 + 3))
        val e = mutableListOf<IdeaVault.Idea>()
        assertEquals("No Pursuit ideas yet, sir.", say(e, "scratch that idea", t0))
    }

    @Test fun at7and8() {
        val l = mutableListOf<IdeaVault.Idea>()
        IdeaVault.add(l, "Mint Caps", null, t0); IdeaVault.add(l, "mint caps", null, t0 + 1)
        assertEquals(1, l.size)
        for (i in 1..305) IdeaVault.add(l, "idea number $i", null, t0 + 10 + i)
        assertEquals(300, l.size); assertTrue(l.none { it.text == "idea number 5" })
        val s = IdeaVault.dump(listOf(IdeaVault.Idea(t0, "design", "a\nb"))) + "\nbroken\n"
        assertEquals(listOf("a b"), IdeaVault.load(s).map { it.text })
        assertEquals("No saved Pursuit ideas match.", IdeaVault.forClaude(emptyList(), "caps", null) { "" })
        assertEquals("- Nov 1 [design] mint caps", IdeaVault.forClaude(listOf(IdeaVault.Idea(t0, "design", "mint caps")), "caps", null) { "Nov 1" })
    }

    @Test fun l13Polish() = assertEquals("Put your @handle in Jarvis settings, sir.", YouTubePulse.BAD_LINK)
}
