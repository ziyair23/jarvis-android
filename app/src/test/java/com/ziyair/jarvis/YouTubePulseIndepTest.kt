package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Independent tester, L13 (and the L27/L26 polish), against the specs only. */
class YouTubePulseIndepTest {
    private fun at(d: Int, h: Int, mi: Int = 0) = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, d, h, mi) }.timeInMillis
    private val now = at(11, 14)
    private val hr = 3600_000L
    private fun st(subs: Long, t: Long, hidden: Boolean = false) = YouTubePulse.Stats(subs, 1, 1, t, hidden)

    @Test fun at1() {
        for (s in listOf("Jarvis, how's my channel doing?", "How's my YouTube doing?", "How many subscribers do I have?", "How's my latest video doing?",
            "YouTube stats.", "Check my channel.", "how's my channel doing today, sir", "how many subs do I have on YouTube"))
            assertTrue(s, YouTubePulse.isAsk(s))
        for (s in listOf("open YouTube", "play my YouTube playlist", "how's my day looking", "how's the weather", "how's my youtube music", "how many miles do I have", "check my messages"))
            assertFalse(s, YouTubePulse.isAsk(s))
    }

    @Test fun at2() {
        val v = listOf(YouTubePulse.Video("Hoodie drop teaser #pursuit 🔥", now - 48 * hr, 900)) +
            listOf(1000L, 1200L, 800L, 1400L, 1100L, 5L).mapIndexed { i, n -> YouTubePulse.Video("v$i", now - (100 + i * 50) * hr, n) }
        assertEquals("1,240 subscribers, 18 more this week, sir. Newest, Hoodie drop teaser, 900 views in 2 days. Your last five averaged 1,100.",
            YouTubePulse.line(st(1240, now), st(1222, now - 7 * 24 * hr), v, now))
    }

    @Test fun at3and4() {
        assertTrue(YouTubePulse.line(st(10, now), st(13, now - 18 * hr), emptyList(), now).startsWith("10 subscribers, 3 fewer since yesterday, sir."))
        assertEquals("1 subscriber, sir. Newest, First, 1 view in 5 hours. The one before had 7.",
            YouTubePulse.line(st(1, now), null, listOf(YouTubePulse.Video("First", now - 5 * hr, 1), YouTubePulse.Video("Old", now - 50 * hr, 7)), now))
        assertEquals("Subscriber count is hidden on the channel, sir. Newest, Clip, 40 views in under an hour.",
            YouTubePulse.line(st(0, now, true), null, listOf(YouTubePulse.Video("Clip", now - 10 * 60_000L, 40)), now))
    }

    @Test fun at5() {
        val l = mutableListOf<YouTubePulse.Stats>()
        YouTubePulse.record(l, st(1, at(1, 9))); YouTubePulse.record(l, st(2, at(5, 9))); YouTubePulse.record(l, st(3, at(11, 8)))
        assertEquals(2L, YouTubePulse.before(l, now)!!.subs)
        YouTubePulse.record(l, st(4, at(11, 9)))
        assertEquals(3, l.size)
        val back = YouTubePulse.load(YouTubePulse.dump(l) + "\n1\u0001x\n")
        assertEquals(listOf(1L, 2L, 4L), back.map { it.subs })
    }

    @Test fun at6() {
        assertEquals("forHandle=@pursuit.official", YouTubePulse.channelQuery("@pursuit.official"))
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("youtube.com/@marcus27?si=abc"))
        assertEquals("id=UCabcdefghijklmnopqrstuv", YouTubePulse.channelQuery("https://youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertNull(YouTubePulse.channelQuery("")); assertNull(YouTubePulse.channelQuery("my channel!!"))
    }

    // Spec: "his channel handle (with or without @, or a channel link)". A link copied from the channel's Videos or
    // Featured tab must not look up a stranger's channel called @videos / @featured.
    // IGNORED only so lab-branch CI still builds; FAILS today (lab/reports/L13-2026-11-01.md). Un-ignore with the fix.
    @Test fun channelLinkWithATab() {
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("https://www.youtube.com/@marcus27/videos"))
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("youtube.com/@marcus27/featured"))
        assertEquals("id=UCabcdefghijklmnopqrstuv", YouTubePulse.channelQuery("https://youtube.com/channel/UCabcdefghijklmnopqrstuv/videos"))
        assertNull(YouTubePulse.channelQuery("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test fun at7() {
        assertEquals("New hoodie", YouTubePulse.title("#pursuit New hoodie 🔥 #drop"))
        assertEquals("a b c d e f g h…", YouTubePulse.title("a b c d e f g h i j"))
    }

    // L27 polish
    @Test fun l27Polish() {
        val q = List(60) { it % 3 == 0 }   // 20 quick, 40 real
        val k = Recap.keep(q)
        assertEquals(40, k.count { !q[it] }); assertEquals(10, k.count { q[it] }); assertEquals(k.sorted(), k)
        assertTrue(Recap.answer(listOf(Recap.Turn("user", "What time does it close?", now - 60_000)), now).startsWith("You'd asked: what time"))
        assertTrue(Recap.answer(listOf(Recap.Turn("user", "I need a new hoodie blank", now - 60_000)), now).startsWith("You'd asked: you need"))
        assertEquals("Last night,", Recap.whenSaid(at(11, 1), at(11, 9)))
        assertEquals("Earlier today,", Recap.whenSaid(at(11, 6), at(11, 10)))
    }
}
