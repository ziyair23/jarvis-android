package com.ziyair.jarvis

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class HomeConnectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "Google Home isn't in this build yet, sir.", Toast.LENGTH_LONG).show()
        finish()
    }
}
