package com.ziyair.jarvis

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Playing a specific song, hands-free. Tries, in order:
 *  1. ask the music app's player directly to play it (no screen needed, works locked, like Android Auto does);
 *  2. open the exact song in the app (phone unlocked), then press play on screen if it sits paused;
 *  3. tell him honestly it didn't start.
 * Blocking (runs on Jarvis's background thread), about 10 seconds at most.
 */
object Music {
    private val main = Handler(Looper.getMainLooper())

    private fun playing(c: MediaController?) = c?.playbackState?.state == PlaybackState.STATE_PLAYING
    private fun title(c: MediaController?) = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    private fun nowSong(c: MediaController?) = title(c) + " " + c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

    @Volatile private var wasPlaying = false
    @Volatile private var seen = false

    /** Waits up to ms for the app to be playing what he asked for. */
    private fun started(context: Context, pkg: String, before: String, ms: Long, query: String = ""): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            val c = Apps.player(context, pkg)
            if (c != null) seen = true
            val t = title(c).lowercase()
            // a new song started (playlists, artists), or the song he named: the old song resuming doesn't count
            if (playing(c) && t.isNotBlank() && (looksLike(nowSong(c), query) || (before.isNotBlank() && t != before.lowercase()))) return true
            Thread.sleep(300)
        }
        return false
    }

    fun play(context: Context, pkg: String, name: String, query: String, song: String?): String {
        val before = title(Apps.player(context, pkg))
        wasPlaying = playing(Apps.player(context, pkg)); seen = Apps.player(context, pkg) != null
        // Jarvis lets go of the speaker first: while he holds it the music app is paused, and when he let go
        // it resumed whatever was loaded before (the old song) instead of the one he asked for
        JarvisService.musicFocus?.invoke(false)
        Thread.sleep(250)
        // 0. the same "play this" request Google Assistant sends: the app searches and plays the top result itself
        if (Settings.canDrawOverlays(context)) try {
            val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, query)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!song.isNullOrBlank()) i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, song)
            if (i.resolveActivity(context.packageManager) != null) {
                context.startActivity(i)
                Trace.log(context, "music", "asked $name to play \"$query\" (assistant request)")
                if (started(context, pkg, before, 7000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
                Trace.log(context, "music", "still playing \"${title(Apps.player(context, pkg))}\", trying the next way")
            }
        } catch (e: Exception) { Trace.log(context, "music", "assistant request failed: ${e.message}") }
        // 1a. the app is already open in the background: ask its player directly
        Apps.player(context, pkg)?.let { c ->
            try { c.transportControls.playFromSearch(query, Bundle()) } catch (_: Exception) {}
            if (started(context, pkg, before, 4000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
        }
        // 1b. connect to the app's player service (what Android Auto uses) and ask it
        val asked = viaBrowser(context, pkg, query)
        if (asked && started(context, pkg, before, 5000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
        // the app took the request but Android won't show us its player: trust it
        if (asked && !seen) return "Playing $query on $name."

        // 2. open the song itself; needs the phone unlocked
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return "$name wouldn't start it from the lock screen. Ask him to unlock the phone and say it again."
        if (!Settings.canDrawOverlays(context)) return Actions.NEED_OPEN_APPS
        val id = if (pkg == Actions.YT_MUSIC) Apps.youtubeId(query) else null
        val uri = when {
            id != null -> Uri.parse("https://music.youtube.com/watch?v=$id")
            pkg == Actions.SPOTIFY -> Uri.parse("spotify:search:" + Uri.encode(query))
            else -> Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))
        }
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            return "Couldn't open $name."
        }
        if (started(context, pkg, before, 5000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
        // loaded but paused: press play only if the loaded song IS his (plain "play" on the old session resumed his previous song)
        Apps.player(context, pkg)?.let { c -> if (looksLike(nowSong(c), query)) { try { c.transportControls.play() } catch (_: Exception) {}; if (started(context, pkg, before, 2000, query)) return "Playing ${titleNow(context, pkg, query)} on $name." } }
        // still sitting there: tap it on screen (the song in the results, or the play button)
        if (JarvisAccess.on(context)) {
            if (id == null) JarvisAccess.tap((song ?: query).trim())
            JarvisAccess.tap("play")
            if (started(context, pkg, before, 3000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
        }
        // what's playing isn't his song (the old queue): pause it instead of claiming success
        Apps.player(context, pkg)?.let { c -> if (playing(c) && title(c) == before && !looksLike(nowSong(c), query)) try { c.transportControls.pause() } catch (_: Exception) {} }
        if (!seen) return "Opened $query in $name."
        return "Opened it in $name but it didn't start playing on its own; he may need to tap play."
    }

    /** Does this song title look like what he asked for? */
    private fun looksLike(title: String, query: String): Boolean {
        val t = title.lowercase()
        val w = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 && it !in setOf("the", "and", "song", "by", "play", "music") }
        return t.isNotBlank() && w.isNotEmpty() && w.any { t.contains(it) }
    }

    private fun titleNow(context: Context, pkg: String, fallback: String) = title(Apps.player(context, pkg)).ifBlank { fallback }

    /** Connects to the music app's media service and asks it to play the search. True if the request went through. */
    private fun viaBrowser(context: Context, pkg: String, query: String): Boolean {
        val svc = context.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return false
        val done = CountDownLatch(1)
        var ok = false
        var browser: MediaBrowser? = null
        main.post {
            try {
                browser = MediaBrowser(context, ComponentName(pkg, svc.name), object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            MediaController(context, browser!!.sessionToken).transportControls.playFromSearch(query, Bundle())
                            ok = true
                        } catch (_: Exception) {}
                        done.countDown()
                    }
                    override fun onConnectionFailed() { done.countDown() }
                    override fun onConnectionSuspended() { done.countDown() }
                }, null)
                browser!!.connect()
            } catch (_: Exception) { done.countDown() }
        }
        done.await(4, TimeUnit.SECONDS)
        // keep the connection a few seconds so the app has time to start, then let go
        main.postDelayed({ try { browser?.disconnect() } catch (_: Exception) {} }, 8000)
        return ok
    }
}
