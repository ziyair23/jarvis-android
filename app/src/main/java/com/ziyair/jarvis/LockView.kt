package com.ziyair.jarvis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.KeyguardManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Lights the screen and shows what Jarvis just said, on top of the lock screen, without unlocking. */
class LockView : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var body: TextView
    private lateinit var status: TextView
    private var fixed = ""          // a reply passed in (for a reply that came with no live words)
    private var quietSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xF50F0F10.toInt())
            setOnClickListener { finish() }
        }
        val orb = (170 * resources.displayMetrics.density).toInt()
        root.addView(OrbView(this).apply {
            setOnClickListener { try { startService(Intent(this@LockView, JarvisService::class.java).setAction(JarvisService.ACTION_HUSH)) } catch (_: Exception) {} }
        }, LinearLayout.LayoutParams(orb, orb).apply { bottomMargin = 24 })
        root.addView(TextView(this).apply {
            text = "Jarvis"; textSize = 26f; setTextColor(0xFFEFEBE4.toInt())
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        // what he's doing right now, live: LISTENING the moment he hears his name
        status = TextView(this).apply { textSize = 13f; letterSpacing = 0.22f; setTextColor(0xFFC9665C.toInt()); gravity = Gravity.CENTER; setPadding(0, 18, 0, 0) }
        root.addView(status)
        body = TextView(this).apply {
            textSize = 20f; setTextColor(0xFFA29D95.toInt()); gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0); setLineSpacing(8f, 1f)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "Tap the halo to stop him, anywhere else to close"; textSize = 13f; setTextColor(0xFF7D7872.toInt()); gravity = Gravity.CENTER; setPadding(0, 60, 0, 0)
        })
        setContentView(root)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); show(intent) }

    private fun show(i: Intent?) {
        fixed = i?.getStringExtra("text").orEmpty()
        quietSince = 0L
        h.removeCallbacksAndMessages(null)
        h.post(tick)
    }

    /** Follows him live: what he's doing, what Marcus said and what he's saying; closes a while after he goes quiet. */
    private val tick = object : Runnable { override fun run() {
        val st = Orb.state
        status.text = when (st) { Orb.LISTEN -> "LISTENING"; Orb.THINK -> "THINKING"; Orb.SPEAK -> "SPEAKING"; else -> "" }
        val heard = Orb.heard.takeIf { System.currentTimeMillis() - Orb.turnAt < 60_000L || st != Orb.HIDDEN }.orEmpty()
        val said = Orb.said.ifBlank { fixed }
        body.text = when {
            st == Orb.LISTEN && Orb.said.isBlank() -> if (heard.isNotBlank()) "\u201C$heard\u201D" else "Go ahead, sir."
            said.isNotBlank() -> said
            heard.isNotBlank() -> "\u201C$heard\u201D"
            else -> body.text
        }
        if (st == Orb.HIDDEN) { if (quietSince == 0L) quietSince = System.currentTimeMillis() } else quietSince = 0L
        if (quietSince > 0L && System.currentTimeMillis() - quietSince > 8_000L) { finish(); return }
        h.postDelayed(this, 150)
    } }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        /** He heard his name while the phone is off or locked: light up straight away so Marcus can see he's listening. */
        fun listening(context: Context) {
            if (Brain(context).config().optBoolean("wake_buzz", true)) try {
                val v = context.getSystemService(android.os.Vibrator::class.java)
                v?.vibrate(android.os.VibrationEffect.createOneShot(35, 120))
            } catch (_: Exception) {}
            showIfLocked(context, "")
        }

        /** Only when the phone is locked or the screen is off, and only if he allowed Jarvis to open things. */
        fun showIfLocked(context: Context, text: String) {
            if (!Prefs(context).wakeScreen || !Settings.canDrawOverlays(context)) return
            val pm = context.getSystemService(PowerManager::class.java)
            val km = context.getSystemService(KeyguardManager::class.java)
            if (pm.isInteractive && !km.isKeyguardLocked) return
            try {
                context.startActivity(Intent(context, LockView::class.java).putExtra("text", text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Exception) {}
        }
    }
}
