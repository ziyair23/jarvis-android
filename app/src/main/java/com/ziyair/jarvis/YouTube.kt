package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * His YouTube channel's numbers (public stats, YouTube Data API key typed into setup): subscribers, views, videos,
 * how his latest uploads are doing, and the change since the last check. About 3 quota units a check; the free
 * quota is 10,000 a day.
 */
object YouTube {
    data class Channel(val title: String, val subs: Long, val views: Long, val videos: Long, val uploads: String, val hidden: Boolean)
    data class Video(val title: String, val views: Long, val likes: Long, val comments: Long)

    // ---------------- pure (unit-tested) ----------------
    /** "@name", "name", a channel URL or a UC… ID -> the query string for channels.list. */
    fun channelQuery(input: String): String {
        val s = input.trim().removeSuffix("/")
        val id = Regex("(UC[0-9A-Za-z_-]{22})").find(s)?.value
        if (id != null) return "id=$id"
        val handle = s.substringAfterLast("/").removePrefix("@")
        return "forHandle=" + URLEncoder.encode("@$handle", "UTF-8")
    }

    fun parseChannel(body: String): Channel? {
        val items = JSONObject(body).optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val it = items.getJSONObject(0); val st = it.optJSONObject("statistics") ?: JSONObject()
        return Channel(it.optJSONObject("snippet")?.optString("title").orEmpty(), st.optString("subscriberCount").toLongOrNull() ?: 0,
            st.optString("viewCount").toLongOrNull() ?: 0, st.optString("videoCount").toLongOrNull() ?: 0,
            it.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty(), st.optBoolean("hiddenSubscriberCount"))
    }

    fun parseVideos(body: String): List<Video> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { i -> val v = items.getJSONObject(i); val st = v.optJSONObject("statistics") ?: JSONObject()
            Video(v.optJSONObject("snippet")?.optString("title").orEmpty(), st.optString("viewCount").toLongOrNull() ?: 0,
                st.optString("likeCount").toLongOrNull() ?: 0, st.optString("commentCount").toLongOrNull() ?: 0) }
    }

    fun delta(now: Long, before: Long?): String = if (before == null || now == before) "" else if (now > before) " (up ${n(now - before)})" else " (down ${n(before - now)})"

    fun n(v: Long): String = when { v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1e6).replace(".0M", "M"); v >= 10_000 -> "${v / 1000}K"; else -> String.format(Locale.US, "%,d", v) }

    // ---------------- calls ----------------
    private fun get(u: String): Pair<Int, String> = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 10000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect(); code to body
    } catch (e: Exception) { -1 to (e.message ?: "") }

    fun stats(c: Context): String {
        val p = Prefs(c)
        if (p.youtubeKey.isBlank() || p.youtubeChannel.isBlank()) return "YouTube isn't set up. Add the YouTube API key and your channel (@handle) in setup."
        val key = URLEncoder.encode(p.youtubeKey, "UTF-8")
        val (code, body) = get("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics,contentDetails&${channelQuery(p.youtubeChannel)}&key=$key")
        if (code != 200) return "YouTube said $code" + (if (code == 400 || code == 403) "; check the API key (YouTube Data API v3 must be turned on)." else ".")
        val ch = parseChannel(body) ?: return "Couldn't find the channel \"${p.youtubeChannel}\"."
        // last three uploads (2 cheap calls)
        var latest = ""
        if (ch.uploads.isNotBlank()) {
            val (c2, b2) = get("https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=3&playlistId=${ch.uploads}&key=$key")
            if (c2 == 200) {
                val a = JSONObject(b2).optJSONArray("items")
                val ids = (0 until (a?.length() ?: 0)).mapNotNull { a!!.getJSONObject(it).optJSONObject("contentDetails")?.optString("videoId") }
                if (ids.isNotEmpty()) {
                    val (c3, b3) = get("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id=${ids.joinToString(",")}&key=$key")
                    if (c3 == 200) latest = parseVideos(b3).joinToString("; ") { "\"${it.title.take(50)}\" ${n(it.views)} views, ${n(it.likes)} likes, ${n(it.comments)} comments" }
                }
            }
        }
        val last = try { JSONObject(p.sp.getString("yt_last", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        fun prev(k: String) = if (last.has(k)) last.optLong(k) else null
        val since = last.optLong("at").takeIf { it > 0 }?.let { " since " + java.text.SimpleDateFormat("EEE h a", Locale.US).format(java.util.Date(it)) }.orEmpty()
        p.sp.edit().putString("yt_last", JSONObject().put("at", System.currentTimeMillis()).put("subs", ch.subs).put("views", ch.views).put("videos", ch.videos).toString()).apply()
        return "${ch.title}: " + (if (ch.hidden) "subscriber count hidden" else "${n(ch.subs)} subscribers${delta(ch.subs, prev("subs"))}") +
            ", ${n(ch.views)} total views${delta(ch.views, prev("views"))}, ${ch.videos} videos" + (if (since.isNotBlank() && last.has("subs")) " (changes$since)" else "") + "." +
            (if (latest.isNotBlank()) " Latest: $latest." else "")
    }
}
