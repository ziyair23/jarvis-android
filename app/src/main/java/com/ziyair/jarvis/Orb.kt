package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The glowing Jarvis orb: floats at the bottom of the screen while he's active and shows what he's doing.
 * Listening: cyan, swells with his voice. Thinking: gold rings spinning fast. Speaking: pulses as he talks.
 * Tap it to cut him off. Hidden the rest of the time, and in quiet mode.
 */
object Orb {
    const val HIDDEN = 0
    const val LISTEN = 1
    const val THINK = 2
    const val SPEAK = 3

    @Volatile var state = HIDDEN; private set
    @Volatile var level = 0f; private set

    private val main = Handler(Looper.getMainLooper())
    private var view: OrbView? = null
    private var warned = false

    fun set(c: Context, s: Int) {
        state = s
        if (s != LISTEN) level = 0f
        val app = c.applicationContext
        main.post { if (state == HIDDEN) hide(app) else show(app) }
    }

    fun level(v: Float) { level = v.coerceIn(0f, 1f) }

    // ---- his actual voice: loudness every 20 ms of what's being played, read against the speaker's position ----
    private val env = ArrayList<Float>()
    private var envRate = 24000
    @Volatile private var head: (() -> Long)? = null
    @Synchronized fun voiceStart(rate: Int, playhead: () -> Long) { env.clear(); envRate = rate; head = playhead }
    @Synchronized fun voiceAdd(s: FloatArray) {
        val w = maxOf(1, envRate / 50)
        var i = 0
        while (i < s.size) {
            var m = 0f
            val end = minOf(i + w, s.size)
            for (j in i until end) { val a = abs(s[j]); if (a > m) m = a }
            env.add(m); i = end
        }
    }
    /** 0..1 how loud he is right now, or -1 if unknown (phone voice). */
    @Synchronized fun voiceLevel(): Float {
        val h = head ?: return -1f
        val pos = try { h() } catch (_: Exception) { return -1f }
        val idx = (pos / maxOf(1, envRate / 50)).toInt()
        val v = env.getOrNull(idx) ?: return 0f
        return (v * 2.6f).coerceIn(0f, 1f)
    }
    fun voiceEnd() { head = null }

    /** For the in-app orb: state and live level. */
    fun now(): Pair<Int, Float> = state to when (state) { LISTEN -> level; SPEAK -> voiceLevel(); else -> 0f }

    /** True while the Jarvis app is open on screen (it has its own big orb, so the floating one steps aside). */
    @Volatile var inApp = false
        set(v) { field = v; main.post { if (v) view?.let { it.alpha = 0f } else if (state != HIDDEN) view?.let { it.alpha = 1f } } }

    fun enabled(c: Context): Boolean {
        val p = Prefs(c)
        return p.sp.getBoolean("show_orb", true) && Brain(c).config().optBoolean("orb", true) && !Stealth.on(p)
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun show(c: Context) {
        view?.let { it.animate().cancel(); it.animate().alpha(if (inApp) 0f else 1f).setDuration(150).start(); return }
        if (!Settings.canDrawOverlays(c)) { if (!warned) { warned = true; Trace.log(c, "orb", "can't float the orb: 'Display over other apps' is off for Jarvis") }; return }
        try { if (!enabled(c)) return } catch (_: Exception) { return }
        val wm = c.getSystemService(WindowManager::class.java)
        val size = dp(c, 132)
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(c, 96)
        }
        val v = OrbView(c).apply {
            alpha = 0f
            setOnClickListener {
                try { c.startService(Intent(c, JarvisService::class.java).setAction(JarvisService.ACTION_HUSH)) } catch (_: Exception) {}
            }
        }
        try {
            wm.addView(v, lp)
            view = v
            v.animate().alpha(if (inApp) 0f else 1f).setDuration(180).start()
        } catch (e: Exception) { Trace.log(c, "orb", "couldn't show the orb: ${e.message}") }
    }

    private fun hide(c: Context) {
        val v = view ?: return
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(260).withEndAction {
            if (state == HIDDEN && view === v) {
                try { c.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
                view = null
            } else v.animate().alpha(1f).setDuration(150).start()
        }.start()
    }
}

/**
 * The halo ("After hours"): a dark core with one thin oxblood ring and a soft glow around it.
 * Waiting: breathes slowly. Listening: the ring opens up and the glow follows his voice.
 * Thinking: a brighter arc travels around the ring. Speaking: the glow pulses with Jarvis's real voice and
 * faint rings drift outward on strong words. Reads Orb.state every frame, so every copy mirrors Jarvis.
 */
class OrbView(c: Context) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    private val born = System.nanoTime()
    private var shown = 0f
    private var spin = 0f
    private var lastT = 0f
    private var prevTarget = 0f
    private var lastRipple = 0f
    private val ripples = ArrayList<Float>()

    private fun col(rgb: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val t = (System.nanoTime() - born) / 1e9f
        val dt = (t - lastT).coerceIn(0f, 0.1f); lastT = t
        val st = Orb.state
        val voice = if (st == Orb.SPEAK) Orb.voiceLevel() else -1f
        val target = when (st) {
            Orb.LISTEN -> 0.25f + Orb.level * 0.75f
            Orb.SPEAK -> if (voice >= 0f) 0.3f + voice * 0.7f else 0.4f + 0.35f * abs(sin(t * 6.5f))
            Orb.THINK -> 0.35f + 0.08f * sin(t * 3f)
            else -> 0.18f + 0.1f * (0.5f + 0.5f * sin(t * 1.2f))   // slow breathing
        }
        shown += (target - shown) * (if (target > shown) 0.45f else 0.12f)
        if ((st == Orb.SPEAK || st == Orb.LISTEN) && shown > 0.55f && t - lastRipple > 0.28f && target - prevTarget > 0.12f) { ripples.add(t); lastRipple = t }
        prevTarget = target
        ripples.removeAll { t - it > 1.2f }
        spin += dt * 140f

        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy)
        val ringR = r * (0.5f + (if (st == Orb.LISTEN) 0.06f * shown else 0.02f * shown))

        // the glow around the ring
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(col(OX, 0f), col(OX, 0f), col(OX, 0.18f + 0.5f * shown), col(OX, 0f)),
            floatArrayOf(0f, 0.42f, 0.58f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, fill)
        fill.shader = null

        // ripples drifting outward
        for (b in ripples) {
            val k = (t - b) / 1.2f
            ring.strokeWidth = r * 0.012f
            ring.color = col(ROSE, 0.45f * (1 - k))
            cv.drawCircle(cx, cy, ringR + (r * 0.98f - ringR) * k, ring)
        }

        // dark core
        fill.color = CORE
        cv.drawCircle(cx, cy, ringR * 0.94f, fill)

        // the ring
        ring.strokeWidth = r * 0.022f
        ring.color = col(ROSE, 0.55f + 0.4f * shown)
        cv.drawCircle(cx, cy, ringR, ring)

        // thinking: a brighter arc travelling around
        if (st == Orb.THINK) {
            box.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            ring.strokeWidth = r * 0.04f
            ring.color = col(GLOW, 0.95f)
            cv.drawArc(box, spin, 70f, false, ring)
        }

        // a faint warm light in the core when he's talking
        if (st == Orb.SPEAK || st == Orb.LISTEN) {
            fill.shader = RadialGradient(cx, cy, ringR * 0.9f, intArrayOf(col(OX, 0.35f * shown), col(OX, 0f)), null, Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, ringR * 0.9f, fill)
            fill.shader = null
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    companion object {
        private val OX = Color.rgb(176, 82, 74)      // oxblood
        private val ROSE = Color.rgb(201, 102, 92)   // the ring, a shade lighter
        private val GLOW = Color.rgb(236, 170, 160)
        private val CORE = Color.rgb(21, 21, 23)
    }
}
