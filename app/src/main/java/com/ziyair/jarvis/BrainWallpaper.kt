package com.ziyair.jarvis

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Jarvis's brain as a live wallpaper (lock screen, and home screen if the phone applies it to both).
 * The same brain as the Brain tab: a folded cortex, cerebellum and stem as points and fibres, slowly turning.
 * It's alive: it brightens while he listens, pulses with his voice while he talks, sparks faster while he thinks,
 * and when an agent picks up a job its part of the brain lights up and a pulse runs along its thread to Jarvis.
 * Only draws while it's on screen, and slows right down when nothing's happening, so it's easy on the battery.
 */
class BrainWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = BrainEngine()

    inner class BrainEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private val draw = Runnable { frame() }
        private val m = BrainModel.get()
        private val n = m.p.size / 3
        private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n); private val pf = FloatArray(n)
        private var yaw = 0.6f
        private val pitch = -0.22f
        private var lastT = 0f
        private val born = SystemClock.elapsedRealtime()
        private var shown = 0f

        private val dens = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * dens }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 13f * dens; letterSpacing = 0.08f }
        private val path = Path()

        // per-depth batches, so thousands of fibres are a handful of draw calls
        private val B = 4
        private val lineBuf = Array(B) { FloatArray(m.e.size * 2) }
        private val lineN = IntArray(B)
        private val dotBuf = Array(3 * B) { FloatArray(n * 2) }
        private val dotN = IntArray(3 * B)

        private val sparks = ArrayList<Spark>()
        private val pulses = ArrayList<Pulse>()
        private val wasActive = HashSet<String>()
        private var agents: List<String> = emptyList()
        private var agentsAt = 0L
        private val dust = Array(60) { floatArrayOf((it * 0.37f) % 6.28f, 1.25f + (it * 0.173f) % 0.5f, ((it * 0.61f) % 1.4f) - 0.7f, 0.05f + (it * 0.031f) % 0.1f) }
        private val rnd = java.util.Random(7)

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(draw)
            if (v) { lastT = now(); handler.post(draw) }
        }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; handler.removeCallbacks(draw); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { handler.removeCallbacks(draw); super.onDestroy() }

        private fun now() = (SystemClock.elapsedRealtime() - born) / 1000f

        private fun frame() {
            if (!visible) return
            val busy = Orb.state != Orb.HIDDEN || Agents.active.isNotEmpty()
            val h = surfaceHolder
            var c: Canvas? = null
            try { c = h.lockCanvas(); if (c != null) render(c) } catch (_: Exception) {}
            finally { if (c != null) try { h.unlockCanvasAndPost(c) } catch (_: Exception) {} }
            handler.postDelayed(draw, if (busy) 33L else 50L)   // 30 fps while he's doing something, 20 at rest
        }

        private fun col(r: Int, g: Int, b: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), r, g, b)

        private fun render(cv: Canvas) {
            val t = now(); val dt = (t - lastT).coerceIn(0f, 0.1f); lastT = t
            val W = cv.width.toFloat(); val H = cv.height.toFloat()
            val cx = W / 2; val cy = H * 0.56f; val S = min(W, H * 0.6f) * 0.36f

            // what Jarvis is doing right now
            val st = Orb.state
            val voice = if (st == Orb.SPEAK) Orb.voiceLevel() else -1f
            val target = when (st) {
                Orb.LISTEN -> 0.35f + Orb.level * 0.65f
                Orb.SPEAK -> if (voice >= 0f) 0.35f + voice * 0.65f else 0.45f + 0.3f * abs(sin(t * 6.5f))
                Orb.THINK -> 0.45f + 0.08f * sin(t * 3f)
                else -> 0.12f + 0.06f * (0.5f + 0.5f * sin(t * 1.1f))
            }
            shown += (target - shown) * (if (target > shown) 0.4f else 0.08f)
            if (SystemClock.elapsedRealtime() - agentsAt > 3000) {
                agentsAt = SystemClock.elapsedRealtime()
                agents = try { if (Agents.on(applicationContext)) Agents.ALL.filter { Agents.enabled(applicationContext, it.id) }.map { it.id } else emptyList() } catch (_: Exception) { emptyList() }
            }
            val active = Agents.active.keys.toSet()
            for (id in active) if (id !in wasActive) for (k in 0..2) pulses.add(Pulse(id, t + k * 0.25f))
            wasActive.clear(); wasActive.addAll(active)

            yaw += dt * (0.1f + 0.25f * shown)

            // background: near-black with a faint oxblood bloom behind the brain
            cv.drawColor(Color.rgb(11, 11, 12))
            fill.shader = RadialGradient(cx, cy, S * 2.2f, intArrayOf(col(176, 82, 74, 0.10f + 0.22f * shown), col(176, 82, 74, 0f)), null, Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, S * 2.2f, fill); fill.shader = null

            // project
            val cyw = cos(yaw); val syw = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
            for (i in 0 until n) {
                val x0 = m.p[i * 3]; val y0 = m.p[i * 3 + 1]; val z0 = m.p[i * 3 + 2]
                val x = x0 * cyw + z0 * syw; var z = -x0 * syw + z0 * cyw
                val y = y0 * cp - z * sp; z = y0 * sp + z * cp
                val f = 3.2f / (3.2f - z)
                px[i] = cx + x * S * f; py[i] = cy - y * S * f; pz[i] = z; pf[i] = f
            }

            // fibres, batched by depth: faint at the back, warmer at the front
            lineN.fill(0)
            for (k in 0 until m.e.size / 2) {
                val a = m.e[k * 2]; val b = m.e[k * 2 + 1]
                val bucket = (((pz[a] + pz[b]) / 2 + 1) / 2 * B).toInt().coerceIn(0, B - 1)
                val buf = lineBuf[bucket]; var o = lineN[bucket]
                buf[o++] = px[a]; buf[o++] = py[a]; buf[o++] = px[b]; buf[o++] = py[b]; lineN[bucket] = o
            }
            for (b in 0 until B) { line.color = col(201, 102, 92, 0.05f + 0.15f * (b + 0.5f) / B + 0.12f * shown); cv.drawLines(lineBuf[b], 0, lineN[b], line) }

            // sparks: a fibre flashes for a moment; more while he thinks or talks, and around an agent at work
            val rate = 1 + (shown * 4).toInt() + (if (st == Orb.THINK) 2 else 0)
            for (k in 0 until rate) if (rnd.nextFloat() < 0.6f) sparks.add(Spark(rnd.nextInt(m.e.size / 2), t))
            for (id in active) m.near[id]?.takeIf { it.isNotEmpty() }?.let { ne -> repeat(3) { sparks.add(Spark(ne[rnd.nextInt(ne.size)], t)) } }
            sparks.removeAll { t - it.t > 0.7f }
            for (s in sparks) {
                val a = m.e[s.e * 2]; val b = m.e[s.e * 2 + 1]; val k = 1 - (t - s.t) / 0.7f
                line.color = col(232, 140, 125, 0.65f * k); line.strokeWidth = 1.5f * dens
                cv.drawLine(px[a], py[a], px[b], py[b], line)
                fill.color = col(250, 205, 192, 0.55f * k); cv.drawCircle((px[a] + px[b]) / 2, (py[a] + py[b]) / 2, 1.6f * dens, fill)
            }
            line.strokeWidth = 0.8f * dens

            // neurons, batched by kind and depth
            dotN.fill(0)
            for (i in 0 until n) {
                val kind = when (m.kind[i]) { 0, 2 -> 0; 1 -> 1; else -> 2 }
                val bucket = kind * B + (((pz[i] + 1) / 2) * B).toInt().coerceIn(0, B - 1)
                val buf = dotBuf[bucket]; var o = dotN[bucket]
                buf[o++] = px[i]; buf[o++] = py[i]; dotN[bucket] = o
            }
            for (kind in 0..2) for (b in 0 until B) {
                val d = (b + 0.5f) / B
                dot.strokeWidth = (if (kind == 2) 1.6f else 2.2f) * dens * (0.85f + 0.3f * d)
                dot.color = when (kind) {
                    0 -> col(239, 235, 228, 0.08f + 0.42f * d + 0.15f * shown)
                    1 -> col(226, 180, 170, 0.12f + 0.4f * d)
                    else -> col(201, 102, 92, 0.06f + 0.12f * d)
                }
                cv.drawPoints(dotBuf[kind * B + b], 0, dotN[kind * B + b], dot)
            }

            // dust drifting around
            for (d in dust) {
                d[0] += d[3] * dt
                val x0 = cos(d[0]) * d[1]; val z0 = sin(d[0]) * d[1]; val y0 = d[2]
                val x = x0 * cyw + z0 * syw; var z = -x0 * syw + z0 * cyw; val y = y0 * cp - z * sp; z = y0 * sp + z * cp
                val f = 3.2f / (3.2f - z)
                fill.color = col(201, 102, 92, 0.08f + 0.2f * (z + 1) / 2)
                cv.drawCircle(cx + x * S * f, cy - y * S * f, 1.4f * dens * f, fill)
            }

            // agents: a thread from each to Jarvis at the centre; a working one glows and sends pulses inward
            fun proj(p: FloatArray): FloatArray {
                val x = p[0] * cyw + p[2] * syw; var z = -p[0] * syw + p[2] * cyw; val y = p[1] * cp - z * sp; z = p[1] * sp + z * cp
                val f = 3.2f / (3.2f - z); return floatArrayOf(cx + x * S * f, cy - y * S * f, z, f)
            }
            val nodes = agents.mapNotNull { id -> BrainModel.POS[id]?.let { id to proj(it) } }
            for ((id, p) in nodes) {
                val on = id in active
                path.reset(); path.moveTo(p[0], p[1]); path.quadTo((p[0] + cx) / 2, (p[1] + cy) / 2 - 24 * dens, cx, cy)
                ring.strokeWidth = (if (on) 2f else 1f) * dens
                ring.color = if (on) col(236, 170, 160, 0.9f) else col(201, 102, 92, 0.18f + 0.12f * (p[2] + 1) / 2)
                cv.drawPath(path, ring)
            }
            pulses.removeAll { t - it.t > 1.4f }
            for (pu in pulses) {
                if (t < pu.t) continue
                val p = nodes.firstOrNull { it.first == pu.id }?.second ?: continue
                val k = (t - pu.t) / 1.4f; val mx = (p[0] + cx) / 2; val my = (p[1] + cy) / 2 - 24 * dens
                val x = (1 - k) * (1 - k) * p[0] + 2 * (1 - k) * k * mx + k * k * cx
                val y = (1 - k) * (1 - k) * p[1] + 2 * (1 - k) * k * my + k * k * cy
                fill.color = col(255, 230, 222, 0.95f); cv.drawCircle(x, y, 4f * dens, fill)
            }
            for ((id, p) in nodes.sortedBy { it.second[2] }) {
                val on = id in active
                val r = (if (on) 7f else 4.5f) * dens * p[3]
                fill.shader = RadialGradient(p[0], p[1], r * 3.2f, intArrayOf(col(201, 102, 92, if (on) 0.6f else 0.22f), col(201, 102, 92, 0f)), null, Shader.TileMode.CLAMP)
                cv.drawCircle(p[0], p[1], r * 3.2f, fill); fill.shader = null
                fill.color = if (on) Color.rgb(240, 195, 186) else col(201, 102, 92, 0.55f + 0.35f * (p[2] + 1) / 2)
                cv.drawCircle(p[0], p[1], r, fill)
                // only a working agent shows its name, so the lock screen stays clean
                if (on) { text.color = col(239, 235, 228, 0.9f); cv.drawText(Agents.def(id)?.name ?: id, p[0], p[1] - r - 9 * dens, text) }
            }

            // Jarvis: the halo at the centre, breathing at rest, alive with his voice
            val R = 22f * dens * (1 + 0.12f * shown)
            fill.shader = RadialGradient(cx, cy, R * 2.6f, intArrayOf(col(176, 82, 74, 0f), col(176, 82, 74, 0f), col(176, 82, 74, 0.2f + 0.5f * shown), col(176, 82, 74, 0f)),
                floatArrayOf(0f, 0.38f, 0.5f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, R * 2.6f, fill); fill.shader = null
            fill.color = Color.rgb(21, 21, 23); cv.drawCircle(cx, cy, R * 0.96f, fill)
            ring.strokeWidth = 2f * dens; ring.color = col(201, 102, 92, 0.55f + 0.45f * shown); cv.drawCircle(cx, cy, R, ring)
            if (st == Orb.THINK) { ring.strokeWidth = 3f * dens; ring.color = col(236, 170, 160, 0.95f)
                cv.drawArc(cx - R, cy - R, cx + R, cy + R, (t * 140f) % 360f, 70f, false, ring) }
            if (st == Orb.SPEAK || st == Orb.LISTEN) {
                fill.shader = RadialGradient(cx, cy, R * 0.9f, intArrayOf(col(176, 82, 74, 0.4f * shown), col(176, 82, 74, 0f)), null, Shader.TileMode.CLAMP)
                cv.drawCircle(cx, cy, R * 0.9f, fill); fill.shader = null
            }
        }
    }
}

private class Spark(val e: Int, val t: Float)
private class Pulse(val id: String, val t: Float)

/** The brain's shape: built once (same recipe as the Brain tab) and shared. */
class BrainModel private constructor() {
    val p: FloatArray
    val kind: IntArray
    val e: IntArray
    val near: Map<String, IntArray>

    init {
        var seed = 11L
        fun rnd(): Float { seed = (seed * 16807) % 2147483647; return seed / 2147483647f }
        val pts = ArrayList<Float>(); val kinds = ArrayList<Int>()
        fun cortex(dx: Float, dy: Float, dz: Float): FloatArray {
            val a = 0.66f; val bb = 0.7f; val c = 1.0f
            var r = 1f / sqrt((dx / a) * (dx / a) + (dy / bb) * (dy / bb) + (dz / c) * (dz / c))
            r *= 1 + 0.05f * sin(11 * dx + 4 * dz) * sin(9 * dy + 3 * dx) + 0.035f * sin(17 * dz - 6 * dy + 2 * dx) + 0.02f * sin(23 * dx + 19 * dy)
            var x = dx * r; var y = dy * r; val z = dz * r
            if (y < -0.18f) y = -0.18f + (y + 0.18f) * 0.55f
            if (y < 0.05f && y > -0.45f && abs(x) > 0.35f) { x *= 1.08f; y -= 0.06f }
            x += (if (x >= 0) 1 else -1) * 0.05f
            return floatArrayOf(x, y, z)
        }
        fun add(q: FloatArray, k: Int) { pts.add(q[0]); pts.add(q[1]); pts.add(q[2]); kinds.add(k) }
        fun sphere(): FloatArray { val u = rnd() * 6.2831855f; val v = acos(2 * rnd() - 1); return floatArrayOf(sin(v) * cos(u), cos(v), sin(v) * sin(u)) }
        repeat(1500) { val s = sphere(); add(cortex(s[0], s[1], s[2]), 0) }
        repeat(260) {
            val s = sphere(); var x = s[0] * 0.46f; val y = s[1] * 0.2f; var z = s[2] * 0.27f
            val st = 1 + 0.06f * sin(y * 70); x *= st; z *= st
            add(floatArrayOf(x, y - 0.5f, z - 0.68f), 1)
        }
        repeat(90) {
            val t = rnd(); val u = rnd() * 6.2831855f; val r = 0.1f - t * 0.025f
            add(floatArrayOf(cos(u) * r, -0.4f - t * 0.55f, -0.3f - t * 0.12f + sin(u) * r * 0.8f), 2)
        }
        repeat(160) {
            val s = sphere(); val k = 0.35f + 0.45f * rnd(); val q = cortex(s[0], s[1], s[2])
            add(floatArrayOf(q[0] * k, q[1] * k, q[2] * k), 3)
        }
        p = pts.toFloatArray(); kind = kinds.toIntArray()
        val n = kind.size
        val edges = ArrayList<Int>()
        val near = ArrayList<Pair<Int, Float>>()
        for (i in 0 until n) {
            near.clear()
            for (j in 0 until n) {
                if (j == i) continue
                val dx = p[j * 3] - p[i * 3]; val dy = p[j * 3 + 1] - p[i * 3 + 1]; val dz = p[j * 3 + 2] - p[i * 3 + 2]
                val d = dx * dx + dy * dy + dz * dz
                if (d < 0.012f) near.add(j to d)
            }
            near.sortBy { it.second }
            for (k in 0 until min(3, near.size)) if (i < near[k].first) { edges.add(i); edges.add(near[k].first) }
        }
        e = edges.toIntArray()
        this.near = POS.mapValues { (_, q) ->
            (0 until e.size / 2).filter { k -> val a = e[k * 2]; val dx = p[a * 3] - q[0]; val dy = p[a * 3 + 1] - q[1]; val dz = p[a * 3 + 2] - q[2]; dx * dx + dy * dy + dz * dz < 0.12f }.toIntArray()
        }
    }

    companion object {
        /** Where each agent lives in the brain (same as the Brain tab). */
        val POS = mapOf(
            "memory" to floatArrayOf(0.3f, -0.32f, -0.12f), "phone" to floatArrayOf(0.05f, 0.28f, 0.98f), "planner" to floatArrayOf(-0.42f, 0.52f, 0.55f),
            "research" to floatArrayOf(0.44f, 0.5f, 0.52f), "money" to floatArrayOf(0.5f, 0.62f, -0.18f), "family" to floatArrayOf(-0.74f, -0.16f, 0.2f),
            "pursuit" to floatArrayOf(0.74f, -0.14f, 0.16f), "builder" to floatArrayOf(-0.1f, 0.4f, -0.96f), "coach" to floatArrayOf(-0.36f, -0.3f, -0.62f))
        @Volatile private var one: BrainModel? = null
        fun get(): BrainModel = one ?: synchronized(this) { one ?: BrainModel().also { one = it } }
    }
}
