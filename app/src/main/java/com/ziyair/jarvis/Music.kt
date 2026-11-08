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
/** What he asked for, minus what he tacked on ("sorry", "please", "for me"). */
object SongQuery {
    private val FILLER = Regex("(?:[\\s,.!?]+(?:sorry|please|thanks|thank you|jarvis|sir|for me|right now|now))+[\\s,.!?]*$", RegexOption.IGNORE_CASE)
    /** "far by nf" -> ("far", "nf"); "lose yourself" -> ("lose yourself", null). Pure. */
    fun parts(q: String): Pair<String, String?> {
        val c = clean(q); val m = Regex("(?i)^(.+?)\\s+by\\s+(.+)$").find(c) ?: return c to null
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }
    fun clean(q: String): String = q.trim().replace(FILLER, "").replace(Regex("^(?:um+|uh+|so|okay|ok)[\\s,]+", RegexOption.IGNORE_CASE), "").trim().ifBlank { q.trim() }
}

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

    fun play(context: Context, pkg: String, name: String, query0: String, song: String?): String {
        val query = SongQuery.clean(query0)
        val before = title(Apps.player(context, pkg))
        wasPlaying = playing(Apps.player(context, pkg)); seen = Apps.player(context, pkg) != null
        // Jarvis lets go of the speaker first: while he holds it the music app is paused, and when he let go
        // it resumed whatever was loaded before (the old song) instead of the one he asked for
        // pause the old song properly first: an app paused by Jarvis holding the speaker auto-resumes the moment he lets go,
        // and that's what kept bringing back his last song
        Apps.player(context, pkg)?.let { c -> try { c.transportControls.pause() } catch (_: Exception) {} }
        Thread.sleep(150)
        JarvisService.musicFocus?.invoke(false)
        Thread.sleep(250)
        Trace.log(context, "music", "was on \"$before\", asking for \"$query\"")
        // look up the exact song in the background meanwhile, so the next way is ready at once if the first one stalls
        val idAhead = if (pkg == Actions.YT_MUSIC) java.util.concurrent.Executors.newSingleThreadExecutor().let { ex -> ex.submit<String?> { Apps.youtubeId(query, Prefs(context).youtubeKey) }.also { ex.shutdown() } } else null
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
                if (started(context, pkg, before, 4000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
                Trace.log(context, "music", "still playing \"${title(Apps.player(context, pkg))}\", trying the next way")
            }
        } catch (e: Exception) { Trace.log(context, "music", "assistant request failed: ${e.message}") }
        // locked: only the background ways work
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked || !Settings.canDrawOverlays(context)) {
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

            if (locked) return "$name wouldn't start it from the lock screen. Ask him to unlock the phone and say it again."
        }
        // 2. open the exact song; needs the phone unlocked
        if (!Settings.canDrawOverlays(context)) return Actions.NEED_OPEN_APPS
        Trace.log(context, "music", "opening the song directly")
        val id = try { idAhead?.get(4, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
        if (pkg == Actions.YT_MUSIC) Trace.log(context, "music", if (id != null) "found the exact song" else "couldn't find the song's link, searching in the app")
        val uri = when {
            id != null -> Uri.parse("https://music.youtube.com/watch?v=$id")
            pkg == Actions.SPOTIFY -> Uri.parse("spotify:search:" + Uri.encode(query))
            else -> Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))
        }
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            return "Couldn't open $name."
        }
        if (started(context, pkg, before, 3000, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
        // the page is open but sitting paused: press play himself, again and again for up to ~10 seconds, until it's going.
        // Only ever on HIS song (once the app has loaded it), never on the old one.
        val access = JarvisAccess.on(context)
        if (!access) Trace.log(context, "music", "screen control is off, can only press play through the player")
        var tappedSong = false
        val until = System.currentTimeMillis() + 10_000
        var round = 0
        while (System.currentTimeMillis() < until) {
            round++
            val c = Apps.player(context, pkg)
            val t = title(c)
            // only HIS song counts: when we don't know what was on before, the title has to look like what he asked for
            val his = c != null && t.isNotBlank() && (looksLike(nowSong(c), query) || (before.isNotBlank() && t != before))
            if (playing(c) && his) {
                Trace.log(context, "music", "playing \"$t\" after $round tries")
                return "Playing ${titleNow(context, pkg, query)} on $name."
            }
            if (his) {
                // his song is loaded: the player's own play button, then the headphone play key
                try { c!!.transportControls.play() } catch (_: Exception) {}
                if (started(context, pkg, before, 900, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
                Apps.media(context, "play")
                if (started(context, pkg, before, 900, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
            }
            if (access) {
                // on a search page: tap the song first; on the song page: the play button (whatever the app calls it)
                if (id == null && !tappedSong) {
                    // "far by nf" is two things on screen ("Far" and "NF"): find the song's row, not the search box
                    val (ttl, art) = SongQuery.parts(song ?: query)
                    val r = JarvisAccess.tapSongResult(ttl, art)
                    tappedSong = r.startsWith("Tapped"); Trace.log(context, "music", "song row: $r")
                }
                for (label in listOf("Play", "Play video", "Resume", "Play all", "Shuffle")) {
                    val r = JarvisAccess.tap(label, exactOnly = true)   // exact names only, so it never taps "Playlist" by mistake
                    if (r.startsWith("Tapped")) { Trace.log(context, "music", "tapped \"$label\""); break }
                }
                if (started(context, pkg, before, 1200, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
            } else Thread.sleep(700)
        }
        // last resort: look at the screen like a person would and tap the song (screen control on, phone unlocked)
        if (access && !locked && Brain(context).config().optBoolean("music_hands", true)) {
            Trace.log(context, "music", "taps didn't start it: looking at the screen to find the song")
            val (ttl, art) = SongQuery.parts(song ?: query)
            try { Hands.run(context, "In ${name}, start playing the song \"$ttl\"${art?.let { " by $it" } ?: ""}: tap that song in the search results (not a video, playlist or the search box), then press play if it's paused. Don't open anything else.", false) } catch (_: Exception) {}
            if (started(context, pkg, before, 2500, query)) return "Playing ${titleNow(context, pkg, query)} on $name."
        }
        Trace.log(context, "music", "gave up: player says \"${title(Apps.player(context, pkg))}\", playing=${playing(Apps.player(context, pkg))}")
        // what's playing isn't his song (the old queue): pause it instead of claiming success
        Apps.player(context, pkg)?.let { c -> if (playing(c) && title(c) == before && !looksLike(nowSong(c), query)) try { c.transportControls.pause() } catch (_: Exception) {} }
        if (!access) return "Opened $query in $name, but I can't press play without screen control. Tell him: open the Jarvis app, tap Allow screen control, and it'll press play for him from then on."
        if (!seen) return "Opened $query in $name."
        return "Opened $query in $name but it wouldn't start, even after I pressed play. Tell him plainly he'll need to tap it this once."
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
