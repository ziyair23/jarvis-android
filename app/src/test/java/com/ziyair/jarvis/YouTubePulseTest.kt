package com.ziyair.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class YouTubePulseTest {
    private fun at(day: Int, hour: Int): Long =
        Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, day, hour, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val now = at(11, 14)   // Wednesday 2 PM
    private val hr = 3600_000L
    private fun st(subs: Long, t: Long, hidden: Boolean = false) = YouTubePulse.Stats(subs, 50_000, 30, t, hidden)
    private fun v(title: String, ageH: Long, views: Long) = YouTubePulse.Video(title, now - ageH * hr, views)

    @Test fun phrasings() {
        listOf("Jarvis, how's my channel doing?", "how's my YouTube doing", "how is my youtube channel doing", "how's the channel looking",
            "how many subscribers do I have", "how many subs have I got on YouTube", "how's my latest video doing", "YouTube stats, sir",
            "check my channel", "how's my channel doing today", "how's my channel on youtube doing")
            .forEach { assertTrue(it, YouTubePulse.isAsk(it)) }
        listOf("open YouTube", "play my YouTube playlist", "how's my day looking", "how's the weather", "how's my youtube music",
            "how many miles do I have", "check my messages").forEach { assertFalse(it, YouTubePulse.isAsk(it)) }
    }

    @Test fun fullLine() {
        val vids = listOf(v("Hoodie drop teaser #pursuit 🔥", 48, 900), v("a", 200, 1000), v("b", 400, 1200), v("c", 600, 800), v("d", 800, 1400), v("e", 900, 1100), v("f", 1000, 99999))
        assertEquals("1,240 subscribers, 18 more this week, sir. Newest, Hoodie drop teaser, 900 views in 2 days. Your last five averaged 1,100.",
            YouTubePulse.line(st(1240, now), st(1222, at(4, 9)), vids, now))
    }

    @Test fun changeWording() {
        assertEquals("1,240 subscribers, 3 fewer since yesterday, sir. No videos up yet.", YouTubePulse.line(st(1240, now), st(1243, at(10, 20)), emptyList(), now))
        assertEquals("1,240 subscribers, no change since Monday, sir. No videos up yet.", YouTubePulse.line(st(1240, now), st(1240, at(9, 14)), emptyList(), now))
        assertEquals("1 subscriber, sir. Newest, First, 1 view in 5 hours. The one before had 7.", YouTubePulse.line(st(1, now), null, listOf(v("First", 5, 1), v("x", 50, 7)), now))
        assertEquals("Subscriber count is hidden on the channel, sir. Newest, Clip, 40 views in under an hour.",
            YouTubePulse.line(st(0, now, true), st(10, at(9, 1)), listOf(YouTubePulse.Video("Clip", now - 10 * 60_000, 40)), now))
    }

    @Test fun snapshotsOnePerDayCompareWithAboutAWeekBack() {
        val l = mutableListOf<YouTubePulse.Stats>()
        YouTubePulse.record(l, st(1000, at(1, 10)))
        YouTubePulse.record(l, st(1100, at(4, 10)))
        YouTubePulse.record(l, st(1150, at(8, 10)))
        YouTubePulse.record(l, st(1200, now - hr))
        YouTubePulse.record(l, st(1210, now))          // same day: replaces
        assertEquals(4, l.size)
        assertEquals(1100L, YouTubePulse.before(l, now)!!.subs)   // oldest within 8 days
        val l2 = mutableListOf(st(900, at(1, 10)))
        assertEquals(900L, YouTubePulse.before(l2, now)!!.subs)   // nothing in the last week: newest earlier one
        assertNull(YouTubePulse.before(mutableListOf(st(5, now - hr)), now))   // only today
        YouTubePulse.record(l, st(1300, now + 61 * 24 * hr))
        assertEquals(1, l.size)   // older than 60 days dropped
    }

    @Test fun storageRoundTrip() {
        val l = mutableListOf(st(1240, now), st(7, now + 24 * hr, true))
        val back = YouTubePulse.load(YouTubePulse.dump(l) + "\nbroken")
        assertEquals(2, back.size); assertEquals(1240L, back[0].subs); assertEquals(now, back[0].at); assertTrue(back[1].subsHidden)
        assertTrue(YouTubePulse.load(null).isEmpty())
    }

    @Test fun channelInput() {
        assertEquals("forHandle=@pursuit.official", YouTubePulse.channelQuery("@pursuit.official"))
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("https://www.youtube.com/@marcus27/videos".replace("/videos", "")))
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("youtube.com/@marcus27?si=abc"))
        assertEquals("id=UC1234567890abcdefghijkl", YouTubePulse.channelQuery("https://youtube.com/channel/UC1234567890abcdefghijkl"))
        assertNull(YouTubePulse.channelQuery(""))
        assertNull(YouTubePulse.channelQuery("my channel!!"))
    }

    @Test fun titlesForTheEar() {
        assertEquals("Hoodie drop teaser", YouTubePulse.title("Hoodie drop teaser #pursuit #streetwear 🔥🔥"))
        assertEquals("one two three four five six seven eight…", YouTubePulse.title("one two three four five six seven eight nine ten"))
    }

    @Test fun subsOnlyWithMyAndTheChannelOnlyWithDoing() {
        listOf("how are my subs doing", "how are my subs", "how are my subscribers looking", "how's the channel doing")
            .forEach { assertTrue(it, YouTubePulse.isAsk(it)) }
        listOf("how are the subs", "how are subs", "how's the channel", "how are the subscribers")
            .forEach { assertFalse(it, YouTubePulse.isAsk(it)) }
    }

    @Test fun tabAndVideoLinksNeverNameAStranger() {
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("https://www.youtube.com/@marcus27/shorts"))
        assertEquals("forHandle=@marcus27", YouTubePulse.channelQuery("marcus27"))
        assertNull(YouTubePulse.channelQuery("https://www.youtube.com/watch"))
    }

    @Test fun oldCustomLinksAreNotUsable() {
        assertNull(YouTubePulse.channelQuery("https://www.youtube.com/c/PursuitOfficial"))
        assertEquals("Put your @handle in Jarvis settings, sir.", YouTubePulse.BAD_LINK)
    }
}
