package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Google Nest through Device Access (Smart Device Management API): his own Device Access project (one-time $5
 * from Google) and the same Google client as Gmail. Device Access only covers Nest thermostats, cameras,
 * doorbells and the Nest Hub Max's camera. Lights and the regular Nest Hub still go through Govee and casting.
 */
object Nest {
    private const val API = "https://smartdevicemanagement.googleapis.com/v1"
    data class Device(val name: String, val label: String, val type: String, val traits: JSONObject)

    // ---------------- pure (unit-tested) ----------------
    fun kind(type: String) = when (type.substringAfterLast('.')) { "THERMOSTAT" -> "thermostat"; "CAMERA" -> "camera"; "DOORBELL" -> "doorbell"; "DISPLAY" -> "display"; else -> type.substringAfterLast('.').lowercase() }

    fun parse(body: String): List<Device> {
        val a = JSONObject(body).optJSONArray("devices") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val d = a.getJSONObject(i); val t = d.optJSONObject("traits") ?: JSONObject()
            val custom = t.optJSONObject("sdm.devices.traits.Info")?.optString("customName").orEmpty()
            val room = d.optJSONArray("parentRelations")?.optJSONObject(0)?.optString("displayName").orEmpty()
            Device(d.optString("name"), custom.ifBlank { room.ifBlank { kind(d.optString("type")) } }, d.optString("type"), t)
        }
    }

    fun f(c: Double) = Math.round(c * 9 / 5 + 32)
    fun c(f: Double) = (f - 32) * 5 / 9

    fun describe(d: Device): String {
        val t = d.traits; val parts = ArrayList<String>()
        t.optJSONObject("sdm.devices.traits.Connectivity")?.optString("status")?.takeIf { it == "OFFLINE" }?.let { parts.add("offline") }
        t.optJSONObject("sdm.devices.traits.Temperature")?.optDouble("ambientTemperatureCelsius")?.takeIf { !it.isNaN() }?.let { parts.add("${f(it)}°F inside") }
        t.optJSONObject("sdm.devices.traits.Humidity")?.optDouble("ambientHumidityPercent")?.takeIf { !it.isNaN() }?.let { parts.add("${it.toInt()}% humidity") }
        t.optJSONObject("sdm.devices.traits.ThermostatMode")?.optString("mode")?.takeIf { it.isNotBlank() }?.let { parts.add("mode ${it.lowercase()}") }
        t.optJSONObject("sdm.devices.traits.ThermostatHvac")?.optString("status")?.takeIf { it.isNotBlank() && it != "OFF" }?.let { parts.add(it.lowercase() + "ing") }
        t.optJSONObject("sdm.devices.traits.ThermostatTemperatureSetpoint")?.let { s ->
            s.optDouble("heatCelsius").takeIf { !it.isNaN() }?.let { parts.add("heat set to ${f(it)}°F") }
            s.optDouble("coolCelsius").takeIf { !it.isNaN() }?.let { parts.add("cool set to ${f(it)}°F") } }
        return "${d.label} (${kind(d.type)})" + (if (parts.isEmpty()) "" else ": " + parts.joinToString(", "))
    }

    // ---------------- calls ----------------
    private fun ready(c: Context): String? = when {
        Prefs(c).nestProject.isBlank() -> "Nest isn't set up: add your Device Access project ID in setup."
        !GoogleAuth.connected(c, GoogleAuth.NEST) -> "Nest isn't connected yet: tap Connect Nest in setup."
        else -> null
    }

    fun devices(c: Context): List<Device>? {
        val (code, body) = GoogleAuth.api(c, GoogleAuth.NEST, "GET", "$API/enterprises/${Prefs(c).nestProject}/devices")
        return if (code == 200) parse(body) else null
    }

    fun list(c: Context): String {
        ready(c)?.let { return it }
        val d = devices(c) ?: return "Couldn't reach Nest${GoogleAuth.lastError.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}."
        if (d.isEmpty()) return "Device Access works but shows no devices. It only covers Nest thermostats, cameras and doorbells; your lights and Nest Hub aren't part of it."
        return d.joinToString(". ") { describe(it) } + "."
    }

    /** Thermostat: mode (heat, cool, heatcool, off) and/or a temperature in °F. */
    fun thermostat(c: Context, which: String?, mode: String?, tempF: Double?): String {
        ready(c)?.let { return it }
        val all = devices(c) ?: return "Couldn't reach Nest."
        val th = all.filter { kind(it.type) == "thermostat" }
        if (th.isEmpty()) return "There's no Nest thermostat on your account."
        val d = th.firstOrNull { !which.isNullOrBlank() && it.label.contains(which, true) } ?: th[0]
        val done = ArrayList<String>()
        if (!mode.isNullOrBlank()) {
            val m = mode.uppercase(Locale.US).replace(" ", "").replace("AUTO", "HEATCOOL").replace("HEAT_COOL", "HEATCOOL")
            if (m !in setOf("HEAT", "COOL", "HEATCOOL", "OFF")) return "Thermostat modes are heat, cool, auto or off."
            val (code, b) = cmd(c, d, "sdm.devices.commands.ThermostatMode.SetMode", JSONObject().put("mode", m))
            if (code != 200) return "Nest didn't take the mode change ($code): ${b.take(80)}"
            done.add("mode ${m.lowercase()}")
        }
        if (tempF != null) {
            if (tempF < 50 || tempF > 90) return "Keep it between 50 and 90°F."
            val cur = if (done.isNotEmpty()) mode!!.uppercase(Locale.US) else d.traits.optJSONObject("sdm.devices.traits.ThermostatMode")?.optString("mode").orEmpty()
            val (name, params) = when {
                cur.startsWith("COOL") -> "SetCool" to JSONObject().put("coolCelsius", c(tempF))
                cur.startsWith("HEATCOOL") || cur.startsWith("AUTO") -> "SetRange" to JSONObject().put("heatCelsius", c(tempF - 2)).put("coolCelsius", c(tempF + 2))
                cur == "OFF" -> return "The thermostat is off. Tell me heat or cool first."
                else -> "SetHeat" to JSONObject().put("heatCelsius", c(tempF))
            }
            val (code, b) = cmd(c, d, "sdm.devices.commands.ThermostatTemperatureSetpoint.$name", params)
            if (code != 200) return "Nest didn't take the temperature ($code): ${b.take(80)}"
            done.add("${Math.round(tempF)}°F")
        }
        return if (done.isEmpty()) describe(d) + "." else "${d.label} set to ${done.joinToString(", ")}."
    }

    private fun cmd(c: Context, d: Device, command: String, params: JSONObject) =
        GoogleAuth.api(c, GoogleAuth.NEST, "POST", "$API/${d.name}:executeCommand", JSONObject().put("command", command).put("params", params))

}
