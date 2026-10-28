package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A small log of anything that went wrong (shown on the dashboard's Chat tab), so problems can be found and fixed. */
object Problems {
    @Synchronized fun log(context: Context, what: String) {
        try {
            val p = Prefs(context)
            val a = all(p)
            a.put(JSONObject().put("at", System.currentTimeMillis()).put("t", what.take(200)))
            val keep = JSONArray(); for (i in maxOf(0, a.length() - 30) until a.length()) keep.put(a.get(i))
            p.sp.edit().putString("problems", keep.toString()).apply()
        } catch (_: Exception) {}
    }
    fun all(p: Prefs): JSONArray = try { JSONArray(p.sp.getString("problems", "[]")) } catch (_: Exception) { JSONArray() }
    fun clear(context: Context) = Prefs(context).sp.edit().putString("problems", "[]").apply()
}
