package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Fetches his channel numbers for YouTube pulse (lab L13). Read-only, 3 quota units per ask. */
object YouTube {
    private const val API = "https://www.googleapis.com/youtube/v3/"

    private class Bad(val line: String) : Exception(line)

    private fun get(path: String, key: String): JSONObject {
        // the key goes in a header, never in the URL
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.setRequestProperty("X-Goog-Api-Key", key)
        c.connectTimeout = 5000; c.readTimeout = 5000
        try {
            val code = c.responseCode
            if (code == 403) {
                val err = try { c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() } catch (_: Exception) { "" }
                if (err.contains("quotaExceeded") || err.contains("dailyLimitExceeded")) throw Bad(YouTubePulse.QUOTA)
            }
            if (code == 400 || code == 401 || code == 403) throw Bad(YouTubePulse.NOT_ALLOWED)
            if (code != 200) throw Bad(YouTubePulse.NO_SIGNAL)
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** The line Jarvis says to "how's my channel doing?". Never throws. */
    fun pulse(context: Context): String {
        val p = Prefs(context)
        val keys = listOf(p.youtubeKey, p.ttsKey, p.geminiKey).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val q = YouTubePulse.channelQuery(p.youtubeChannel)
        if (keys.isEmpty() || q == null) return YouTubePulse.NO_KEY
        if (!Brain(context).isOnline()) return YouTubePulse.NO_SIGNAL
        // a key restricted to something else (the voice) gets one retry with the next key he has
        var out = YouTubePulse.NOT_ALLOWED
        for (key in keys.take(2)) { out = pulse(context, p, q, key); if (out != YouTubePulse.NOT_ALLOWED) break }
        return out
    }

    private fun pulse(context: Context, p: Prefs, q: String, key: String): String {
        return try {
            val (name, value) = q.split("=", limit = 2)
            val ch = get("channels?part=statistics,contentDetails&$name=" + URLEncoder.encode(value, "UTF-8"), key)
                .optJSONArray("items")?.optJSONObject(0) ?: return YouTubePulse.BAD_CHANNEL
            val s = ch.optJSONObject("statistics") ?: JSONObject()
            val now = System.currentTimeMillis()
            val stats = YouTubePulse.Stats(s.optString("subscriberCount").toLongOrNull() ?: 0L, s.optString("viewCount").toLongOrNull() ?: 0L,
                s.optString("videoCount").toLongOrNull() ?: 0L, now, s.optBoolean("hiddenSubscriberCount", false))
            val uploads = ch.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty()
            val videos = if (uploads.isEmpty()) emptyList() else latest(uploads, key)
            synchronized(YouTubePulse) {
                val l = YouTubePulse.load(p.sp.getString("yt_snaps", ""))
                val prev = YouTubePulse.before(l, now)
                YouTubePulse.record(l, stats)
                p.sp.edit().putString("yt_snaps", YouTubePulse.dump(l)).apply()
                YouTubePulse.line(stats, prev, videos, now)
            }
        } catch (e: Bad) { e.line }
        catch (e: Exception) { Problems.log(context, "youtube: ${e.message?.replace(Regex("key=[^&\\s]+"), "key=…")}"); YouTubePulse.NO_SIGNAL }
    }

    /** His newest uploads (up to 6), newest first. */
    private fun latest(uploads: String, key: String): List<YouTubePulse.Video> {
        val items = get("playlistItems?part=contentDetails&maxResults=6&playlistId=" + URLEncoder.encode(uploads, "UTF-8"), key).optJSONArray("items")
            ?: return emptyList()
        val ids = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("contentDetails")?.optString("videoId")?.takeIf { id -> id.isNotEmpty() } }
        if (ids.isEmpty()) return emptyList()
        val vs = get("videos?part=snippet,statistics&id=" + URLEncoder.encode(ids.joinToString(","), "UTF-8"), key).optJSONArray("items") ?: return emptyList()
        return (0 until vs.length()).mapNotNull { i ->
            val v = vs.optJSONObject(i) ?: return@mapNotNull null
            val sn = v.optJSONObject("snippet") ?: return@mapNotNull null
            val at = try { java.time.Instant.parse(sn.optString("publishedAt")).toEpochMilli() } catch (_: Exception) { return@mapNotNull null }
            YouTubePulse.Video(sn.optString("title"), at, v.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L)
        }.sortedByDescending { it.publishedAt }
    }
}
