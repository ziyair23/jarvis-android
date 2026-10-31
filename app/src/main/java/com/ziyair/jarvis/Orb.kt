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

/** Draws the orb. Reads Orb.state every frame, so any number of these mirror Jarvis (overlay and lock screen). */
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

    private fun withAlpha(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val t = (System.nanoTime() - born) / 1e9f
        val dt = (t - lastT).coerceIn(0f, 0.1f); lastT = t
        val st = Orb.state
        val voice = if (st == Orb.SPEAK) Orb.voiceLevel() else -1f
        val target = when (st) {
            Orb.LISTEN -> 0.15f + Orb.level * 0.85f
            // pulses with his real voice, syllable by syllable (a gentle made-up pulse for the phone's voice)
            Orb.SPEAK -> if (voice >= 0f) 0.22f + voice * 0.78f else 0.35f + 0.45f * abs(sin(t * 7.3f)) * (0.6f + 0.4f * abs(sin(t * 2.1f + 1f)))
            Orb.THINK -> 0.3f + 0.1f * sin(t * 4f)
            else -> 0.12f + 0.05f * sin(t * 1.5f)
        }
        shown += (target - shown) * (if (target > shown) 0.5f else 0.18f)   // quick attack, soft release
        // a ring ripples out on each strong syllable, like the films
        if ((st == Orb.SPEAK || st == Orb.LISTEN) && shown > 0.55f && t - lastRipple > 0.22f && target - prevTarget > 0.12f) { ripples.add(t); lastRipple = t }
        prevTarget = target
        ripples.removeAll { t - it > 0.9f }
        spin += dt * when (st) { Orb.THINK -> 260f; Orb.SPEAK -> 70f; else -> 40f }

        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy)
        val main = when (st) { Orb.THINK -> 0xFFFFC46B.toInt(); else -> 0xFF4FD8FF.toInt() }
        val accent = when (st) { Orb.THINK -> 0xFF4FD8FF.toInt(); else -> 0xFFB8F1FF.toInt() }

        // dark backing so it reads on any app
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(0xE6070C14.toInt(), 0xB3070C14.toInt(), 0x00070C14),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, fill)
        // glow
        fill.shader = RadialGradient(cx, cy, r * 0.95f, intArrayOf(withAlpha(main, 0.55f + 0.35f * shown), withAlpha(main, 0.18f), 0),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 0.95f, fill)
        // core
        val core = r * (0.2f + 0.11f * shown)
        fill.shader = RadialGradient(cx, cy, core, intArrayOf(Color.WHITE, withAlpha(accent, 0.95f), withAlpha(main, 0.0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, core, fill)
        fill.shader = null

        // ripples
        for (born in ripples) {
            val k = (t - born) / 0.9f
            ring.strokeWidth = r * 0.03f * (1 - k)
            ring.color = withAlpha(main, 0.7f * (1 - k))
            cv.drawCircle(cx, cy, r * (0.35f + 0.62f * k), ring)
        }
        // rings
        for (i in 0..2) {
            val rr = r * (0.42f + 0.14f * i + if (st == Orb.LISTEN) 0.05f * shown else 0f)
            box.set(cx - rr, cy - rr, cx + rr, cy + rr)
            ring.strokeWidth = r * (0.045f - 0.01f * i)
            ring.color = withAlpha(if (i == 1) accent else main, 0.85f - 0.22f * i)
            val dir = if (i % 2 == 0) 1f else -1f
            val start = spin * dir * (1f + 0.35f * i) + i * 40f
            val sweep = 55f + 30f * i + if (st == Orb.THINK) 25f * sin(t * 3f + i) else 0f
            cv.drawArc(box, start, sweep, false, ring)
            cv.drawArc(box, start + 180f, sweep, false, ring)
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
