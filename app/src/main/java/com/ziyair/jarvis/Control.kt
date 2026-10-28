package com.ziyair.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Opening apps and controlling whatever is playing. */
object Apps {
    private val ALIASES = mapOf(
        "youtube" to "com.google.android.youtube", "tiktok" to "com.zhiliaoapp.musically", "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android", "ig" to "com.instagram.android", "snapchat" to "com.snapchat.android", "snap" to "com.snapchat.android",
        "maps" to "com.google.android.apps.maps", "google maps" to "com.google.android.apps.maps", "doordash" to "com.doordash.driverapp",
        "dasher" to "com.doordash.driverapp", "youtube music" to "com.google.android.apps.youtube.music", "spotify" to "com.spotify.music",
        "messages" to "com.google.android.apps.messaging", "gmail" to "com.google.android.gm", "chrome" to "com.android.chrome",
        "google home" to "com.google.android.apps.chromecast.app", "home" to "com.google.android.apps.chromecast.app",
        "photos" to "com.google.android.apps.photos", "gallery" to "com.sec.android.gallery3d", "settings" to "com.android.settings",
        "facebook" to "com.facebook.katana", "whatsapp" to "com.whatsapp", "netflix" to "com.netflix.mediaclient", "discord" to "com.discord")

    fun open(context: Context, nameIn: String): String {
        val name = nameIn.trim().lowercase().removePrefix("the ").removeSuffix(" app")
        if (!Settings.canDrawOverlays(context)) return Actions.NEED_OPEN_APPS
        if (name in setOf("camera", "the camera", "cam")) {
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            context.startActivity(Intent(if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Camera open."
        }
        if (name in setOf("video camera", "video")) {
            context.startActivity(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Video camera open."
        }
        val pm = context.packageManager
        val pkg = ALIASES[name]?.takeIf { pm.getLaunchIntentForPackage(it) != null } ?: run {
            // match against the names of every app on the phone
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            (apps.firstOrNull { it.first.equals(name, true) } ?: apps.firstOrNull { it.first.lowercase().startsWith(name) }
                ?: apps.firstOrNull { it.first.lowercase().contains(name) })?.second
        } ?: return "No app called $nameIn on the phone."
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return "That app can't be opened directly."
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return "Opened ${pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))}." +
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) " He'll need to unlock to see it." else ""
    }

    /** The music/video app that's playing (or last played), through Android's media controls. */
    fun player(context: Context, pkg: String? = null): android.media.session.MediaController? = try {
        val msm = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        val list = msm.getActiveSessions(android.content.ComponentName(context, MessageListener::class.java))
        val pool = if (pkg != null) list.filter { it.packageName == pkg } else list
        pool.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: pool.firstOrNull()
    } catch (_: Exception) { null }

    /** Play/pause/skip: tells the music app directly (works even while Jarvis has the music ducked); falls back to headphone-button signals. */
    fun media(context: Context, action: String): String {
        player(context)?.let { mc ->
            val tc = mc.transportControls
            val playing = mc.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            when (action) {
                "play" -> tc.play(); "pause" -> tc.pause(); "toggle" -> if (playing) tc.pause() else tc.play()
                "next" -> tc.skipToNext(); "previous" -> tc.skipToPrevious(); "stop" -> tc.stop()
                else -> return "Unknown music control."
            }
            val app = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(mc.packageName, 0)).toString() } catch (_: Exception) { "the music app" }
            return when (action) { "play" -> "Playing on $app."; "pause" -> "Paused $app."; "toggle" -> if (playing) "Paused." else "Playing."
                "next" -> "Skipped to the next track on $app."; "previous" -> "Went back a track on $app."; else -> "Stopped $app." }
        }
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "Unknown music control."
        }
        val am = context.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return when (action) { "play" -> "Playing."; "pause" -> "Paused."; "toggle" -> "Done."; "next" -> "Skipped."; "previous" -> "Went back a track."; else -> "Stopped." }
    }
}

/**
 * Screen control through Android Accessibility: Jarvis can read what's on screen, tap things by name,
 * scroll, type, and press back/home. He turns it on once in Settings > Accessibility > Jarvis.
 */
class JarvisAccess : AccessibilityService() {
    override fun onServiceConnected() { me = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // DoorDash offers: read pay and miles off the Dasher screen and give a verdict
        if (event?.packageName?.toString() != OfferJudge.DASHER) return
        try {
            val texts = ArrayList<String>()
            walk(rootInActiveWindow) { n -> (n.text ?: n.contentDescription)?.toString()?.let { if (it.isNotBlank() && it.length < 80) texts.add(it) } }
            OfferJudge.check(this, texts)
        } catch (_: Exception) {}
    }
    override fun onInterrupt() {}
    override fun onDestroy() { if (me === this) me = null; super.onDestroy() }

    companion object {
        @Volatile var me: JarvisAccess? = null
        fun on(context: Context): Boolean {
            if (me != null) return true
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains(context.packageName + "/")
        }
        const val NEED = "Screen control is off: he needs to open the Jarvis app and tap Allow screen control."

        private fun pause(ms: Long) = try { Thread.sleep(ms) } catch (_: Exception) {}

        fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, fn: (AccessibilityNodeInfo) -> Unit) {
            if (n == null || depth > 40) return
            fn(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, fn)
        }

        private fun label(n: AccessibilityNodeInfo): String =
            (n.text?.toString()?.trim().orEmpty().ifBlank { n.contentDescription?.toString()?.trim().orEmpty() }).replace(Regex("\\s+"), " ")

        /** A short list of what's on screen: the app, then visible labels, marking things he can tap. */
        fun read(): String {
            val s = me ?: return NEED
            val root = s.rootInActiveWindow ?: return "Can't see the screen right now (it may be locked)."
            val items = LinkedHashSet<String>()
            walk(root) { n ->
                if (!n.isVisibleToUser) return@walk
                val l = label(n)
                if (l.isNotBlank() && l.length < 120) items.add((if (n.isClickable || n.isCheckable) "[tap] " else "") + (if (n.isEditable) "[text box] " else "") + l)
            }
            val app = root.packageName?.toString().orEmpty()
            return "Screen ($app): " + items.take(60).joinToString(" | ").ifBlank { "nothing readable" }
        }

        private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var c: AccessibilityNodeInfo? = n; var d = 0
            while (c != null && d < 6) { if (c.isClickable) return c; c = c.parent; d++ }
            return null
        }

        /** Tap the thing whose text or description best matches. */
        fun tap(what: String, long: Boolean = false): String {
            val s = me ?: return NEED
            val root = s.rootInActiveWindow ?: return "Can't see the screen right now."
            val w = what.trim().lowercase()
            var exact: AccessibilityNodeInfo? = null; var starts: AccessibilityNodeInfo? = null; var contains: AccessibilityNodeInfo? = null
            walk(root) { n ->
                if (!n.isVisibleToUser) return@walk
                val l = label(n).lowercase()
                if (l.isBlank()) return@walk
                when { l == w -> if (exact == null) exact = n; l.startsWith(w) -> if (starts == null) starts = n; l.contains(w) -> if (contains == null) contains = n }
            }
            val node = exact ?: starts ?: contains ?: return "Nothing on screen matches \"$what\". " + read()
            val target = clickable(node)
            val ok = if (target != null) target.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
                else { val r = Rect(); node.getBoundsInScreen(r); gestureTap(s, r.centerX().toFloat(), r.centerY().toFloat()) }
            if (!ok) return "Couldn't tap \"$what\"."
            pause(900)
            return "Tapped \"${label(node)}\". Now " + read()
        }

        fun type(text: String, submit: Boolean): String {
            val s = me ?: return NEED
            val root = s.rootInActiveWindow ?: return "Can't see the screen right now."
            var field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            if (field == null) walk(root) { n -> if (field == null && n.isEditable && n.isVisibleToUser) field = n }
            val f = field ?: return "No text box on screen."
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Couldn't type there."
            if (submit && Build.VERSION.SDK_INT >= 30) f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            pause(700)
            return "Typed it. Now " + read()
        }

        fun press(action: String): String {
            val s = me ?: return NEED
            val ok = when (action) {
                "back" -> s.performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> s.performGlobalAction(GLOBAL_ACTION_HOME)
                "recent_apps" -> s.performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> s.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "lock_screen" -> Build.VERSION.SDK_INT >= 28 && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                "screenshot" -> Build.VERSION.SDK_INT >= 28 && s.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                "scroll_down", "scroll_up" -> scroll(s, action == "scroll_down")
                else -> return "Unknown action."
            }
            if (!ok) return "That didn't work."
            if (action in setOf("lock_screen", "screenshot")) return "Done."
            pause(800)
            return "Done. Now " + read()
        }

        private fun scroll(s: JarvisAccess, down: Boolean): Boolean {
            var list: AccessibilityNodeInfo? = null
            walk(s.rootInActiveWindow) { n -> if (list == null && n.isScrollable && n.isVisibleToUser) list = n }
            list?.let { if (it.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true }
            // fall back to a swipe in the middle of the screen (TikTok, Reels, Shorts)
            val dm = s.resources.displayMetrics
            val x = dm.widthPixels / 2f; val top = dm.heightPixels * .3f; val bottom = dm.heightPixels * .75f
            return swipe(s, x, if (down) bottom else top, x, if (down) top else bottom)
        }

        private fun gestureTap(s: JarvisAccess, x: Float, y: Float) = swipe(s, x, y, x, y, 60)

        private fun swipe(s: JarvisAccess, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
            val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            return s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
        }

        /** Stop Google Maps directions: bring Maps up and press its exit button. */
        fun stopNavigation(context: Context): String {
            if (me == null) return NEED
            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") ?: return "Google Maps isn't installed."
            if (!Settings.canDrawOverlays(context)) return Actions.NEED_OPEN_APPS
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            pause(1500)
            for (w in listOf("close navigation", "exit navigation", "exit")) {
                val r = tap(w)
                if (r.startsWith("Tapped")) return "Directions cancelled."
            }
            return "Couldn't find the exit button in Maps. " + read()
        }
    }
}
