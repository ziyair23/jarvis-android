package com.ziyair.jarvis

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.home.ForcePermissionFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One-time "Allow Jarvis to control your Google Home" screen (Google's own consent page). */
class HomeConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Connecting Google Home…"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(0xFFEFE6D6.toInt()); setBackgroundColor(0xFF14110E.toInt()); setPadding(60, 60, 60, 60)
        }
        setContentView(tv)
        val client = GoogleHome.client(this)
        client.registerActivityResultCallerForPermissions(this)
        lifecycleScope.launch {
            val msg = try {
                val r = client.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH, null)
                GoogleHome.forget()
                if (r.status.name.contains("SUCCESS")) "Google Home connected, sir. Try \"Hey Jarvis, lights off.\""
                else { Problems.log(this@HomeConnectActivity, "google home connect: ${r.status} ${r.errorMessage}"); "Didn't connect (${r.status}). ${r.errorMessage ?: ""}" }
            } catch (e: Throwable) { Problems.log(this@HomeConnectActivity, "google home connect: ${e.message}"); "Didn't connect: ${e.message}" }
            tv.text = msg
            delay(3500)
            finish()
        }
    }
}
