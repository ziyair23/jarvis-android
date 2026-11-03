package com.ziyair.jarvis

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
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
 * Drawn on the graphics chip in step with the screen (60 or 120 fps, whatever the phone runs), with nothing
 * allocated per frame, so it never stutters.
 * Always a little alive: sparks firing, chains of neurons lighting one after another, a wave of thought sweeping
 * through every few seconds, soft pulses running between agents and Jarvis. And it reacts: brighter while he
 * listens, pulsing with his voice while he talks, racing while he thinks, and an agent's region lights up (with its
 * name) while it works. Only draws while it's on screen.
 */
class BrainWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = BrainEngine()

    inner class BrainEngine : Engine(), Choreographer.FrameCallback {
        private var visible = false
        private val m = BrainModel.get()
        private val n = m.p.size / 3
        private val ne = m.e.size / 2
        private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)
        private var yaw = 0.6f
        private val pitch = -0.22f
        private var lastNs = 0L
        private var t = 0f
        private var shown = 0f

        private val dens = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * dens }
        private val glowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * dens; strokeCap = Paint.Cap.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 13f * dens; letterSpacing = 0.08f }
        private val path = Path()

        // made once, then scaled into place (no new objects while animating)
        private val unitGlow = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(255, 201, 102, 92), Color.argb(0, 201, 102, 92)), null, Shader.TileMode.CLAMP)
        private val unitHalo = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(0, 176, 82, 74), Color.argb(0, 176, 82, 74), Color.argb(255, 176, 82, 74), Color.argb(0, 176, 82, 74)),
            floatArrayOf(0f, 0.38f, 0.5f, 1f), Shader.TileMode.CLAMP)
        private val unitCore = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(255, 176, 82, 74), Color.argb(0, 176, 82, 74)), null, Shader.TileMode.CLAMP)

        // per-depth batches, so thousands of fibres are a handful of draw calls
        private val B = 4
        private val lineBuf = Array(B) { FloatArray(m.e.size * 2) }
        private val lineN = IntArray(B)
        private val dotBuf = Array(3 * B) { FloatArray(n * 2) }
        private val dotN = IntArray(3 * B)
        private val waveBuf = FloatArray(n * 2)

        // sparks: a ring buffer of (edge, start time)
        private val SPARKS = 400
        private val sparkE = IntArray(SPARKS); private val sparkT = FloatArray(SPARKS) { -9f }; private var sparkI = 0
        private var sparkDebt = 0f
        // chains: a signal hopping neuron to neuron
        private val CH = 24
        private val chPoint = IntArray(CH); private val chLeft = IntArray(CH); private val chNext = FloatArray(CH) { -1f }
        private var nextChain = 0.5f
        // a wave of thought sweeping through
        private var waveAt = 2f
        // pulses along agent threads
        private val PU = 24
        private val puAgent = IntArray(PU); private val puT = FloatArray(PU) { -9f }; private val puSoft = BooleanArray(PU); private var puI = 0
        private var nextSoft = 1.5f

        private val ids = BrainModel.POS.keys.toTypedArray()
        private val agentOn = BooleanArray(ids.size)
        private val agentBusy = BooleanArray(ids.size)
        private val wasBusy = BooleanArray(ids.size)
        private val nx = FloatArray(ids.size); private val ny = FloatArray(ids.size); private val nz = FloatArray(ids.size); private val nf = FloatArray(ids.size)
        private val order = IntArray(ids.size)
        private var agentsAt = 0L
        private val names = ids.map { id -> Agents.def(id)?.name ?: id }

        private val DUST = 70
        private val dA = FloatArray(DUST) { (it * 0.37f) % 6.28f }; private val dR = FloatArray(DUST) { 1.2f + (it * 0.173f) % 0.6f }
        private val dY = FloatArray(DUST) { ((it * 0.61f) % 1.5f) - 0.75f }; private val dS = FloatArray(DUST) { 0.06f + (it * 0.031f) % 0.12f }
        private val rnd = java.util.Random(7)

        // his day, under the brain: what's on Jarvis's mind and what's coming up (read from the phone every few seconds)
        private var cardAt = 0L
        private var cardOn = true
        private var head = ""; private var updated = ""
        private var times = arrayOf<String>(); private var whats = arrayOf<String>()
        private var shop = ""; private var shopLines = arrayOf<String>()
        private var headLines = arrayOf<String>()
        private var headW = 0f
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * dens; letterSpacing = 0.18f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f * dens; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
        private val timeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * dens; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        private val itemP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * dens; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        private val shade = Paint()
        private var shadeH = 0f

        private fun readCard() {
            cardAt = SystemClock.elapsedRealtime()
            try {
                val ctx = applicationContext
                cardOn = Brain(ctx).config().optBoolean("lock_card", true) && Mind.enabled(ctx)
                val c = DayPlan.card(ctx)
                head = if (Mind.thinking) "Thinking things over…" else c.optString("headline")
                updated = c.optLong("thoughtAt").takeIf { it > 0 }?.let { java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(it)) }.orEmpty()
                val a = c.optJSONArray("items") ?: org.json.JSONArray()
                times = Array(minOf(a.length(), 4)) { a.getJSONObject(it).optString("t") }
                whats = Array(minOf(a.length(), 4)) { a.getJSONObject(it).optString("x") }
                val l = c.optJSONArray("list") ?: org.json.JSONArray()
                shop = (0 until l.length()).joinToString(", ") { l.getString(it) }
                headW = 0f
            } catch (_: Exception) {}
        }

        /** Splits [s] into at most [max] lines that fit [w] (the last one ends in … if it had to be cut). */
        private fun wrap(s: String, p: Paint, w: Float, max: Int): Array<String> {
            val out = ArrayList<String>(); var rest = s.trim()
            while (rest.isNotEmpty() && out.size < max) {
                var n = p.breakText(rest, true, w, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp }
                var line = rest.substring(0, n).trim(); rest = rest.substring(n).trim()
                if (out.size == max - 1 && rest.isNotEmpty()) { while (line.isNotEmpty() && p.measureText("$line…") > w) line = line.dropLast(1); line = line.trimEnd() + "…"; rest = "" }
                out.add(line)
            }
            return out.toTypedArray()
        }

        private fun drawCard(cv: Canvas, W: Float, H: Float) {
            if (head.isBlank() && whats.isEmpty() && shop.isBlank()) return
            val left = 28f * dens; val w = W - 2 * left
            val top = H * (if (shop.isBlank()) 0.655f else 0.615f)
            if (shadeH != H) { shadeH = H; shade.shader = LinearGradient(0f, top - 60 * dens, 0f, H, intArrayOf(Color.argb(0, 11, 11, 12), Color.argb(215, 11, 11, 12), Color.argb(235, 11, 11, 12)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP) }
            cv.drawRect(0f, top - 60 * dens, W, H, shade)
            var y = top
            label.color = col(201, 102, 92, 0.95f); cv.drawText("ON HIS MIND", left, y, label)
            if (updated.isNotBlank()) { label.color = col(162, 157, 149, 0.7f); val tw = label.measureText(updated.uppercase()); cv.drawText(updated.uppercase(), left + w - tw, y, label) }
            y += 27f * dens
            if (headW != w) { headW = w; headLines = wrap(head, headP, w, 2); shopLines = wrap(shop, itemP, w, 2) }
            headP.color = col(239, 235, 228, 0.96f)
            for (l in headLines) { cv.drawText(l, left, y, headP); y += 25f * dens }
            y += 8f * dens
            val col1 = 74f * dens
            for (i in whats.indices) {
                timeP.color = col(162, 157, 149, 0.85f); cv.drawText(times[i], left, y, timeP)
                itemP.color = col(239, 235, 228, 0.84f)
                val t = whats[i]; val fit = itemP.breakText(t, true, w - col1, null)
                cv.drawText(if (fit < t.length) t.substring(0, maxOf(0, fit - 1)).trimEnd() + "…" else t, left + col1, y, itemP)
                y += 22f * dens
            }
            // the shopping list
            if (shopLines.isNotEmpty()) {
                y += 10f * dens
                label.color = col(201, 102, 92, 0.95f); cv.drawText("LIST", left, y, label)
                y += 22f * dens
                itemP.color = col(239, 235, 228, 0.84f)
                for (l in shopLines) { cv.drawText(l, left, y, itemP); y += 21f * dens }
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            val ch = Choreographer.getInstance()
            ch.removeFrameCallback(this)
            if (v) { lastNs = 0L; ch.postFrameCallback(this) }
        }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; Choreographer.getInstance().removeFrameCallback(this); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { Choreographer.getInstance().removeFrameCallback(this); super.onDestroy() }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) / 1e9f).coerceIn(0f, 0.1f)
            lastNs = frameTimeNanos
            t += dt
            val h = surfaceHolder
            var c: Canvas? = null
            try { c = h.lockHardwareCanvas() } catch (_: Throwable) {}
            if (c == null) try { c = h.lockCanvas() } catch (_: Throwable) {}
            try { if (c != null) render(c, dt) } catch (_: Throwable) {}
            finally { if (c != null) try { h.unlockCanvasAndPost(c) } catch (_: Throwable) {} }
            if (visible) Choreographer.getInstance().postFrameCallback(this)
        }

        private fun col(r: Int, g: Int, b: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), r, g, b)

        private fun spark(e: Int) { sparkE[sparkI] = e; sparkT[sparkI] = t; sparkI = (sparkI + 1) % SPARKS }
        private fun pulse(a: Int, soft: Boolean, delay: Float = 0f) { puAgent[puI] = a; puT[puI] = t + delay; puSoft[puI] = soft; puI = (puI + 1) % PU }

        private fun circle(cv: Canvas, shader: Shader, x: Float, y: Float, r: Float, alpha: Float) {
            if (alpha <= 0.004f || r <= 0f) return
            glow.shader = shader; glow.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            cv.save(); cv.translate(x, y); cv.scale(r, r); cv.drawCircle(0f, 0f, 1f, glow); cv.restore()
        }

        private fun render(cv: Canvas, dt: Float) {
            val W = cv.width.toFloat(); val H = cv.height.toFloat()
            if (SystemClock.elapsedRealtime() - cardAt > 4000) readCard()
            val withCard = cardOn && (head.isNotBlank() || whats.isNotEmpty() || shop.isNotBlank())
            val cx = W / 2; val cy = H * (if (!withCard) 0.56f else if (shop.isBlank()) 0.43f else 0.4f)
            val S = min(W, H * 0.6f) * 0.36f * (1 + 0.012f * sin(t * 0.9f))   // the whole brain breathes, very slightly

            // what Jarvis is doing right now
            val st = if (Orb.state == Orb.HIDDEN && Mind.thinking) Orb.THINK else Orb.state
            val voice = if (st == Orb.SPEAK) Orb.voiceLevel() else -1f
            val target = when (st) {
                Orb.LISTEN -> 0.35f + Orb.level * 0.65f
                Orb.SPEAK -> if (voice >= 0f) 0.35f + voice * 0.65f else 0.45f + 0.3f * abs(sin(t * 6.5f))
                Orb.THINK -> 0.5f + 0.08f * sin(t * 3f)
                else -> 0.16f + 0.07f * (0.5f + 0.5f * sin(t * 1.1f))
            }
            val k = 1f - Math.pow(if (target > shown) 0.0005 else 0.08, dt.toDouble()).toFloat()   // same feel at any frame rate
            shown += (target - shown) * k

            if (SystemClock.elapsedRealtime() - agentsAt > 3000) {
                agentsAt = SystemClock.elapsedRealtime()
                try { val on = Agents.on(applicationContext); for (i in ids.indices) agentOn[i] = on && Agents.def(ids[i]) != null && Agents.enabled(applicationContext, ids[i]) } catch (_: Exception) {}
            }
            var anyBusy = false
            for (i in ids.indices) {
                agentBusy[i] = Agents.active.containsKey(ids[i])
                if (agentBusy[i]) anyBusy = true
                if (agentBusy[i] && !wasBusy[i]) for (j in 0..2) pulse(i, false, j * 0.22f)
                wasBusy[i] = agentBusy[i]
            }

            yaw += dt * (0.14f + 0.3f * shown)

            // background: near-black with an oxblood bloom behind the brain
            cv.drawColor(Color.rgb(11, 11, 12))
            circle(cv, unitGlow, cx, cy, S * 2.3f, 0.12f + 0.2f * shown)

            // project
            val cyw = cos(yaw); val syw = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
            for (i in 0 until n) {
                val x0 = m.p[i * 3]; val y0 = m.p[i * 3 + 1]; val z0 = m.p[i * 3 + 2]
                val x = x0 * cyw + z0 * syw; var z = -x0 * syw + z0 * cyw
                val y = y0 * cp - z * sp; z = y0 * sp + z * cp
                val f = 3.2f / (3.2f - z)
                px[i] = cx + x * S * f; py[i] = cy - y * S * f; pz[i] = z
            }

            // fibres, batched by depth: faint at the back, warmer at the front
            lineN.fill(0)
            for (e in 0 until ne) {
                val a = m.e[e * 2]; val b = m.e[e * 2 + 1]
                val bucket = (((pz[a] + pz[b]) / 2 + 1) / 2 * B).toInt().coerceIn(0, B - 1)
                val buf = lineBuf[bucket]; var o = lineN[bucket]
                buf[o++] = px[a]; buf[o++] = py[a]; buf[o++] = px[b]; buf[o++] = py[b]; lineN[bucket] = o
            }
            for (b in 0 until B) { line.color = col(201, 102, 92, 0.06f + 0.16f * (b + 0.5f) / B + 0.12f * shown); cv.drawLines(lineBuf[b], 0, lineN[b], line) }

            // sparks: always some firing; many more while he listens, talks or thinks
            sparkDebt += dt * (16f + 70f * shown + (if (st == Orb.THINK) 50f else 0f))
            while (sparkDebt >= 1f) { spark(rnd.nextInt(ne)); sparkDebt -= 1f }
            for (i in ids.indices) if (agentBusy[i]) m.near[ids[i]]?.takeIf { it.isNotEmpty() }?.let { nr -> if (rnd.nextFloat() < dt * 40f) spark(nr[rnd.nextInt(nr.size)]) }

            // chains: a signal hopping from neuron to neuron along the fibres
            if (t >= nextChain) {
                val slot = (0 until CH).firstOrNull { chLeft[it] <= 0 }
                if (slot != null) { chPoint[slot] = rnd.nextInt(n); chLeft[slot] = 6 + rnd.nextInt(8); chNext[slot] = t }
                nextChain = t + (if (st != Orb.HIDDEN || anyBusy) 0.15f else 0.55f) + rnd.nextFloat() * 0.6f
            }
            for (s in 0 until CH) {
                if (chLeft[s] <= 0 || t < chNext[s]) continue
                val p = chPoint[s]; val from = m.adjStart[p]; val to = m.adjStart[p + 1]
                if (to <= from) { chLeft[s] = 0; continue }
                val e = m.adj[from + rnd.nextInt(to - from)]
                spark(e)
                chPoint[s] = if (m.e[e * 2] == p) m.e[e * 2 + 1] else m.e[e * 2]
                chLeft[s]--; chNext[s] = t + 0.055f
            }

            add.color = Color.WHITE
            for (s in 0 until SPARKS) {
                val age = t - sparkT[s]; if (age < 0f || age > 0.7f) continue
                val e = sparkE[s]; val a = m.e[e * 2]; val b = m.e[e * 2 + 1]; val kk = 1 - age / 0.7f
                glowLine.color = col(232, 140, 125, 0.7f * kk)
                cv.drawLine(px[a], py[a], px[b], py[b], glowLine)
                add.color = col(250, 205, 192, 0.6f * kk); cv.drawCircle((px[a] + px[b]) / 2, (py[a] + py[b]) / 2, 1.7f * dens, add)
            }

            // neurons, batched by kind and depth; the wave of thought lights a band of them as it passes
            if (t >= waveAt + 1.8f) waveAt = t + (if (st != Orb.HIDDEN) 1.2f else 3.5f) + rnd.nextFloat() * 2f
            val wv = if (t >= waveAt) -1.15f + (t - waveAt) / 1.8f * 2.3f else 9f
            dotN.fill(0); var wn = 0
            for (i in 0 until n) {
                val kind = when (m.kind[i]) { 0, 2 -> 0; 1 -> 1; else -> 2 }
                val bucket = kind * B + (((pz[i] + 1) / 2) * B).toInt().coerceIn(0, B - 1)
                val buf = dotBuf[bucket]; var o = dotN[bucket]
                buf[o++] = px[i]; buf[o++] = py[i]; dotN[bucket] = o
                if (kind != 2 && abs(m.p[i * 3 + 2] - wv) < 0.07f) { waveBuf[wn++] = px[i]; waveBuf[wn++] = py[i] }
            }
            for (kind in 0..2) for (b in 0 until B) {
                val d = (b + 0.5f) / B
                dot.strokeWidth = (if (kind == 2) 1.6f else 2.2f) * dens * (0.85f + 0.3f * d)
                dot.color = when (kind) {
                    0 -> col(239, 235, 228, 0.1f + 0.42f * d + 0.15f * shown)
                    1 -> col(226, 180, 170, 0.14f + 0.4f * d)
                    else -> col(201, 102, 92, 0.06f + 0.12f * d)
                }
                cv.drawPoints(dotBuf[kind * B + b], 0, dotN[kind * B + b], dot)
            }
            if (wn > 0) { dot.strokeWidth = 3f * dens; dot.color = col(240, 170, 158, 0.55f); dot.xfermode = add.xfermode
                cv.drawPoints(waveBuf, 0, wn, dot); dot.xfermode = null }

            // dust drifting around
            for (i in 0 until DUST) {
                dA[i] += dS[i] * dt * (1 + shown)
                val x0 = cos(dA[i]) * dR[i]; val z0 = sin(dA[i]) * dR[i]; val y0 = dY[i] + 0.04f * sin(t * 0.7f + i)
                val x = x0 * cyw + z0 * syw; var z = -x0 * syw + z0 * cyw; val y = y0 * cp - z * sp; z = y0 * sp + z * cp
                val f = 3.2f / (3.2f - z)
                fill.color = col(201, 102, 92, 0.08f + 0.22f * (z + 1) / 2)
                cv.drawCircle(cx + x * S * f, cy - y * S * f, 1.4f * dens * f, fill)
            }

            // agents: a thread from each to Jarvis at the centre
            var cnt = 0
            for (i in ids.indices) {
                if (!agentOn[i]) continue
                val q = BrainModel.POS.getValue(ids[i])
                val x = q[0] * cyw + q[2] * syw; var z = -q[0] * syw + q[2] * cyw; val y = q[1] * cp - z * sp; z = q[1] * sp + z * cp
                val f = 3.2f / (3.2f - z)
                nx[i] = cx + x * S * f; ny[i] = cy - y * S * f; nz[i] = z; nf[i] = f
                order[cnt++] = i
                path.reset(); path.moveTo(nx[i], ny[i]); path.quadTo((nx[i] + cx) / 2, (ny[i] + cy) / 2 - 24 * dens, cx, cy)
                ring.strokeWidth = (if (agentBusy[i]) 2f else 1f) * dens
                ring.color = if (agentBusy[i]) col(236, 170, 160, 0.9f) else col(201, 102, 92, 0.2f + 0.14f * (z + 1) / 2)
                cv.drawPath(path, ring)
            }
            // soft pulses now and then, so there's always a little traffic between his agents and him
            if (t >= nextSoft && cnt > 0) { pulse(order[rnd.nextInt(cnt)], true); nextSoft = t + 1.2f + rnd.nextFloat() * 1.8f }
            for (s in 0 until PU) {
                val age = t - puT[s]; if (age < 0f || age > 1.4f) continue
                val i = puAgent[s]; if (!agentOn[i]) continue
                val kk = age / 1.4f; val mx = (nx[i] + cx) / 2; val my = (ny[i] + cy) / 2 - 24 * dens
                val x = (1 - kk) * (1 - kk) * nx[i] + 2 * (1 - kk) * kk * mx + kk * kk * cx
                val y = (1 - kk) * (1 - kk) * ny[i] + 2 * (1 - kk) * kk * my + kk * kk * cy
                circle(cv, unitGlow, x, y, (if (puSoft[s]) 7f else 11f) * dens, if (puSoft[s]) 0.5f else 0.9f)
                add.color = if (puSoft[s]) col(236, 170, 160, 0.6f) else col(255, 232, 224, 1f)
                cv.drawCircle(x, y, (if (puSoft[s]) 2.2f else 3.6f) * dens, add)
            }
            // agents back to front
            for (a in 1 until cnt) { val v = order[a]; var b = a - 1; while (b >= 0 && nz[order[b]] > nz[v]) { order[b + 1] = order[b]; b-- }; order[b + 1] = v }
            for (o in 0 until cnt) {
                val i = order[o]; val busy = agentBusy[i]
                val r = (if (busy) 7f else 4.5f) * dens * nf[i]
                circle(cv, unitGlow, nx[i], ny[i], r * 3.4f, if (busy) 0.65f else 0.22f + 0.06f * sin(t * 1.7f + i))
                fill.color = if (busy) Color.rgb(240, 195, 186) else col(201, 102, 92, 0.55f + 0.35f * (nz[i] + 1) / 2)
                cv.drawCircle(nx[i], ny[i], r, fill)
                // only a working agent shows its name, so the lock screen stays clean
                if (busy) { text.color = col(239, 235, 228, 0.9f); cv.drawText(names[i], nx[i], ny[i] - r - 9 * dens, text) }
            }

            // Jarvis: the halo at the centre, breathing at rest, alive with his voice
            val R = 22f * dens * (1 + 0.12f * shown)
            circle(cv, unitHalo, cx, cy, R * 2.6f, 0.25f + 0.55f * shown)
            fill.color = Color.rgb(21, 21, 23); cv.drawCircle(cx, cy, R * 0.96f, fill)
            ring.strokeWidth = 2f * dens; ring.color = col(201, 102, 92, 0.55f + 0.45f * shown); cv.drawCircle(cx, cy, R, ring)
            if (st == Orb.THINK) { ring.strokeWidth = 3f * dens; ring.color = col(236, 170, 160, 0.95f)
                cv.drawArc(cx - R, cy - R, cx + R, cy + R, (t * 160f) % 360f, 70f, false, ring) }
            circle(cv, unitCore, cx, cy, R * 0.9f, 0.08f + 0.45f * shown)
            if (withCard) drawCard(cv, W, H)
        }
    }
}

/** The brain's shape: built once (same recipe as the Brain tab) and shared. */
class BrainModel private constructor() {
    val p: FloatArray
    val kind: IntArray
    val e: IntArray
    val near: Map<String, IntArray>
    /** For each neuron, the fibres touching it: adj[adjStart[i] until adjStart[i + 1]]. */
    val adjStart: IntArray
    val adj: IntArray

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
        val deg = IntArray(n + 1)
        for (k in 0 until e.size / 2) { deg[e[k * 2]]++; deg[e[k * 2 + 1]]++ }
        adjStart = IntArray(n + 1); for (i in 0 until n) adjStart[i + 1] = adjStart[i] + deg[i]
        adj = IntArray(adjStart[n]); val fillAt = adjStart.copyOf()
        for (k in 0 until e.size / 2) { adj[fillAt[e[k * 2]]++] = k; adj[fillAt[e[k * 2 + 1]]++] = k }
        this.near = POS.mapValues { (_, q) ->
            (0 until e.size / 2).filter { k -> val a = e[k * 2]; val dx = p[a * 3] - q[0]; val dy = p[a * 3 + 1] - q[1]; val dz = p[a * 3 + 2] - q[2]; dx * dx + dy * dy + dz * dz < 0.12f }.toIntArray()
        }
    }

    companion object {
        /** Where each agent lives in the brain (same as the Brain tab). */
        val POS = mapOf(
            "memory" to floatArrayOf(0.3f, -0.32f, -0.12f), "phone" to floatArrayOf(0.05f, 0.28f, 0.98f), "planner" to floatArrayOf(-0.42f, 0.52f, 0.55f),
            "research" to floatArrayOf(0.44f, 0.5f, 0.52f), "money" to floatArrayOf(0.5f, 0.62f, -0.18f), "family" to floatArrayOf(-0.74f, -0.16f, 0.2f),
            "pursuit" to floatArrayOf(0.74f, -0.14f, 0.16f), "builder" to floatArrayOf(-0.1f, 0.4f, -0.96f), "coach" to floatArrayOf(-0.36f, -0.3f, -0.62f),
            "bills" to floatArrayOf(0.62f, 0.18f, -0.55f), "appts" to floatArrayOf(-0.62f, 0.34f, -0.22f),
            "packages" to floatArrayOf(0.35f, -0.3f, 0.72f), "supplies" to floatArrayOf(-0.3f, -0.36f, 0.68f),
            "meals" to floatArrayOf(-0.5f, 0.1f, 0.78f))
        @Volatile private var one: BrainModel? = null
        fun get(): BrainModel = one ?: synchronized(this) { one ?: BrainModel().also { one = it } }
    }
}
