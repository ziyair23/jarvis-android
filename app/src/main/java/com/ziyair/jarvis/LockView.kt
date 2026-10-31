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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xF2050A12.toInt())
            setOnClickListener { finish() }
        }
        val orb = (170 * resources.displayMetrics.density).toInt()
        root.addView(OrbView(this).apply {
            setOnClickListener { try { startService(Intent(this@LockView, JarvisService::class.java).setAction(JarvisService.ACTION_HUSH)) } catch (_: Exception) {} }
        }, LinearLayout.LayoutParams(orb, orb).apply { bottomMargin = 24 })
        root.addView(TextView(this).apply {
            text = "J A R V I S"; textSize = 20f; setTextColor(0xFF7FE3FF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        body = TextView(this).apply {
            textSize = 22f; setTextColor(0xFFE6F7FF.toInt()); gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0); setLineSpacing(8f, 1f)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "Tap the orb to stop him, anywhere else to close"; textSize = 13f; setTextColor(0xFF5B7A8C.toInt()); gravity = Gravity.CENTER; setPadding(0, 60, 0, 0)
        })
        setContentView(root)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); show(intent) }

    private fun show(i: Intent?) {
        body.text = i?.getStringExtra("text").orEmpty()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ finish() }, 15_000)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
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
