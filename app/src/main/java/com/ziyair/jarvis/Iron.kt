package com.ziyair.jarvis

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.UUID

/** Govee lights through Govee's free developer key (Govee Home app > Profile > Settings > Apply for API Key). */
object Lights {
    private const val BASE = "https://openapi.api.govee.com/router/api/v1"

    private fun call(key: String, method: String, path: String, body: JSONObject? = null): JSONObject? = try {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("Govee-API-Key", key); c.setRequestProperty("Content-Type", "application/json")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (ok) JSONObject(text) else null
    } catch (_: Exception) { null }

    /** [(name, sku, device)] */
    private fun devices(key: String): List<Triple<String, String, String>> {
        val arr = call(key, "GET", "/user/devices")?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { d ->
            val caps = d.optJSONArray("capabilities") ?: JSONArray()
            (0 until caps.length()).any { caps.getJSONObject(it).optString("instance") == "powerSwitch" }
        }.map { Triple(it.optString("deviceName"), it.optString("sku"), it.optString("device")) }
    }

    private fun control(key: String, sku: String, device: String, type: String, instance: String, value: Any): Boolean =
        call(key, "POST", "/device/control", JSONObject().put("requestId", UUID.randomUUID().toString()).put("payload",
            JSONObject().put("sku", sku).put("device", device)
                .put("capability", JSONObject().put("type", type).put("instance", instance).put("value", value)))) != null

    private val COLORS = mapOf("red" to 0xFF0000, "orange" to 0xFF7A00, "yellow" to 0xFFD000, "green" to 0x00FF40, "blue" to 0x0050FF,
        "purple" to 0x8A2BE2, "pink" to 0xFF4FA0, "white" to 0xFFFFFF, "warm" to 0xFFB46B, "warm white" to 0xFFB46B, "cool" to 0xD8E8FF)

    /** which: a light's name or "all". on: true/false/null. brightness 1-100 or null. color name or null. */
    fun set(context: Context, which: String?, on: Boolean?, brightness: Int?, color: String?): String {
        val key = Prefs(context).goveeKey
        if (key.isBlank()) return "No lights connected yet: he needs to add his Govee API key in Jarvis setup."
        val all = devices(key)
        if (all.isEmpty()) return "Couldn't reach his Govee lights. The key may be wrong."
        val w = which?.trim()?.lowercase().orEmpty()
        val targets = if (w.isBlank() || w == "all" || w == "lights" || w == "the lights") all
            else all.filter { it.first.lowercase().contains(w) || w.contains(it.first.lowercase()) }.ifEmpty { return "No light called $which. He has: ${all.joinToString { it.first }}." }
        var done = 0
        for ((_, sku, dev) in targets) {
            var ok = true
            if (on != null) ok = control(key, sku, dev, "devices.capabilities.on_off", "powerSwitch", if (on) 1 else 0) && ok
            if (on != false && brightness != null) ok = control(key, sku, dev, "devices.capabilities.range", "brightness", brightness.coerceIn(1, 100)) && ok
            if (on != false && color != null) COLORS[color.lowercase()]?.let { ok = control(key, sku, dev, "devices.capabilities.color_setting", "colorRgb", it) && ok }
            if (ok) done++
        }
        val names = targets.joinToString { it.first }
        return if (done == targets.size) "Done: $names." else "Only $done of ${targets.size} lights responded ($names)."
    }

    fun list(context: Context): String {
        val key = Prefs(context).goveeKey
        if (key.isBlank()) return "No lights connected yet."
        return devices(key).joinToString { it.first }.ifBlank { "No Govee lights found." }
    }

    fun late() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 21 || it < 7 }
}

/** His shift times come from the routine in setup (lunch/morning and dinner ranges). */
object Shifts {
    private fun starts(p: Prefs) = listOf(p.lunch, p.dinner).mapNotNull { r ->
        Regex("(\\d{1,2}):(\\d{2})").find(r)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() } }

    /** Millis of the next shift start. */
    fun next(context: Context): Long? {
        val now = Calendar.getInstance()
        var best: Long? = null
        for (dayAdd in 0..1) for (m in starts(Prefs(context))) {
            val c = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayAdd); set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0) }
            if (c.timeInMillis > now.timeInMillis && (best == null || c.timeInMillis < best!!)) best = c.timeInMillis
        }
        return best
    }

    fun nextLine(context: Context): String = next(context)?.let {
        val tomorrow = Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        "Next shift ${if (tomorrow) "tomorrow " else ""}at ${Scheduler.clock(it)}."
    } ?: ""
}

object Status {
    fun battery(context: Context): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) } ?: -1
        val charging = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return level to (charging != 0)
    }

    /** Everything at a glance, for "status report". */
    fun report(context: Context): String {
        val (lvl, chg) = battery(context)
        val parts = mutableListOf("Battery $lvl%${if (chg) " and charging" else ""}.")
        Weather.now(context)?.let { parts.add("Weather: $it" + Weather.roadsNote(context)) }
        parts.add("Earned today ${Earnings.money(Earnings.today(context))}.")
        parts.add("Baby: " + Baby.status(context))
        Shifts.nextLine(context).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val r = Prefs(context).reminders()
        if (r.length() > 0) {
            val n = (0 until r.length()).map { r.getJSONObject(it) }.minByOrNull { it.getLong("at") }!!
            parts.add("Next reminder: ${n.optString("label")} at ${Scheduler.clock(n.getLong("at"))}.")
        }
        if (DriveMode.on) parts.add("Driving mode is on.")
        return parts.joinToString(" ")
    }
}

/** "Where are you?" — loud even on silent, through the alarm channel. */
object FindPhone {
    fun ring(context: Context): String {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            val r = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            r.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { r.stop() } catch (_: Exception) {} }, 8000)
            try { PhoneControl.run(context, "flashlight_on", null) } catch (_: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { PhoneControl.run(context, "flashlight_off", null) } catch (_: Exception) {} }, 8000)
        } catch (_: Exception) {}
        return "Ringing loudly for 8 seconds with the flashlight on, volume turned all the way up. Tell him: over here, sir."
    }
}

/** Named routines, like the films. Each returns what happened for Jarvis to sum up in one line. */
object Protocols {
    fun run(context: Context, nameIn: String): String {
        val name = nameIn.lowercase()
        val out = mutableListOf<String>()
        fun step(label: String, f: () -> String) { out.add(try { f() } catch (e: Exception) { "$label failed" }) }
        when {
            Regex("good ?night|sleep|bed").containsMatchIn(name) -> return goodnight(context)
            name.contains("dash") || name.contains("work") -> {
                DriveMode.on = true; out.add("Driving mode on.")
                step("Dasher") { Apps.open(context, "dasher") }
                step("music") { Apps.media(context, "play") }
                Weather.now(context)?.let { out.add("Weather: $it" + Weather.roadsNote(context)) }
                out.add("Earned so far today ${Earnings.money(Earnings.today(context))}.")
            }
            name.contains("baby") || name.contains("night") || name.contains("feed") -> {
                step("quiet") { PhoneControl.run(context, "quiet_on", null) }
                if (Prefs(context).goveeKey.isNotBlank()) step("lights") { Lights.set(context, "all", true, 5, "warm") }
                step("volume") { PhoneControl.run(context, "volume", 3) }
                out.add(Baby.status(context))
            }
            name.contains("home") -> {
                DriveMode.on = false; out.add("Driving mode off.")
                if (Prefs(context).goveeKey.isNotBlank()) step("lights") { Lights.set(context, "all", true, if (Lights.late()) 15 else 80, "warm") }
                out.add("Earned today ${Earnings.money(Earnings.today(context))}. Ask him how the shift went if nothing's logged.")
                out.add(Baby.status(context))
            }
            else -> return "No protocol called $nameIn. Known: dash, baby, home, goodnight."
        }
        return out.joinToString(" ")
    }

    /** Lights off, next shift, phone quiet until the shift starts (starred contacts like his girlfriend still ring). */
    fun goodnight(context: Context): String {
        val out = mutableListOf<String>()
        if (Prefs(context).goveeKey.isNotBlank()) out.add(Lights.set(context, "all", false, null, null))
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            try {
                nm.notificationPolicy = NotificationManager.Policy(
                    NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
                        NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS,
                    NotificationManager.Policy.PRIORITY_SENDERS_STARRED, NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
            } catch (_: Exception) {}
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            val until = Shifts.next(context)
            if (until != null) {
                Scheduler.addAction(context, until - 30 * 60_000L, "quiet_off", "end quiet mode")
                out.add("Phone quiet until ${Scheduler.clock(until - 30 * 60_000L)}; starred contacts and alarms still get through.")
            } else out.add("Phone on quiet; starred contacts still get through.")
        } else out.add("Couldn't quiet the phone: quiet mode isn't allowed in the Jarvis app.")
        Shifts.nextLine(context).takeIf { it.isNotBlank() }?.let { out.add(it) }
        out.add(Baby.status(context))
        return out.joinToString(" ")
    }
}
