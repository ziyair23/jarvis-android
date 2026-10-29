package com.ziyair.jarvis

import android.content.Context

/** Stand-in used when the app is built without Google's Home kit. */
object GoogleHome {
    const val AVAILABLE = false
    fun connected(context: Context) = false
    fun connectedCached() = false
    fun forget() {}
    fun control(context: Context, which: String?, on: Boolean?, level: Int?) = "Google Home isn't built into this version of Jarvis yet."
    fun list(context: Context) = "Google Home isn't built into this version of Jarvis yet."
}
