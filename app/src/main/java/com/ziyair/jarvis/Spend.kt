package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a running estimate of what Jarvis costs on his Claude account this month (from the token counts
 * Claude sends back with every reply), and warns him before he goes over his budget.
 */
object Spend {
    private fun month() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun budget(p: Prefs) = p.sp.getFloat("budget", 5f).toDouble()
    fun thisMonth(p: Prefs): Double = if (p.sp.getString("spend_month", "") == month()) p.sp.getFloat("spend", 0f).toDouble() else 0.0

    /** Dollars per million tokens: [input, output]. Defaults are Claude's published per-token prices; adjustable from config. */
    private fun price(cfg: JSONObject, model: String): Pair<Double, Double> {
        val table = cfg.optJSONObject("prices")
        val k = when { model.contains("opus") -> "opus"; model.contains("sonnet") -> "sonnet"; else -> "haiku" }
        val arr = table?.optJSONArray(k)
        if (arr != null && arr.length() == 2) return arr.optDouble(0) to arr.optDouble(1)
        return when (k) { "opus" -> 5.0 to 25.0; "sonnet" -> 3.0 to 15.0; else -> 1.0 to 5.0 }
    }

    @Synchronized fun record(context: Context, cfg: JSONObject, model: String, usage: JSONObject?) {
        usage ?: return
        val p = Prefs(context)
        val (pin, pout) = price(cfg, model)
        val dollars = (usage.optLong("input_tokens") * pin + usage.optLong("output_tokens") * pout +
            usage.optLong("cache_creation_input_tokens") * pin * 1.25 + usage.optLong("cache_read_input_tokens") * pin * 0.1) / 1_000_000.0 +
            (usage.optJSONObject("server_tool_use")?.optInt("web_search_requests") ?: 0) * 0.01
        val m = month()
        val before = thisMonth(p)
        val after = before + dollars
        p.sp.edit().putString("spend_month", m).putFloat("spend", after.toFloat()).apply()
        val b = budget(p)
        val warned = p.sp.getString("spend_warned", "") ?: ""
        val line = when {
            after >= b && !warned.startsWith("$m:100") -> { p.sp.edit().putString("spend_warned", "$m:100").apply(); "Heads up, sir: I've used about ${Earnings.money(Math.round(after * 100) / 100.0)} of your ${Earnings.money(b)} Claude budget this month. Might be time to top up, or I can keep answers shorter." }
            after >= b * 0.8 && warned != "$m:80" && !warned.startsWith("$m:") -> { p.sp.edit().putString("spend_warned", "$m:80").apply(); "Heads up, sir: I've used about ${Earnings.money(Math.round(after * 100) / 100.0)} of your ${Earnings.money(b)} Claude budget this month." }
            else -> null
        }
        if (line != null) try {
            context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", line))
        } catch (_: Exception) {}
    }

    fun report(context: Context): String {
        val p = Prefs(context)
        val s = thisMonth(p); val b = budget(p)
        return "About ${String.format(Locale.US, "$%.2f", s)} used this month of a ${Earnings.money(b)} budget (an estimate from Claude's token counts; his Claude Console shows the exact balance)."
    }

    fun setBudget(context: Context, dollars: Double): String {
        val p = Prefs(context)
        p.sp.edit().putFloat("budget", dollars.toFloat()).putString("spend_warned", "").apply()
        return "Budget set to ${Earnings.money(dollars)} a month. " + report(context)
    }
}
