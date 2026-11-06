package com.ziyair.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The Roads agent, on Google Maps Platform (his own key, typed into setup): drive times with live traffic
 * (Routes API) and the cheapest gas around him (Places API, fuel prices). Only reads; navigation itself still
 * opens Google Maps.
 */
object Maps {
    data class Route(val seconds: Long, val usualSeconds: Long, val meters: Long)
    data class Gas(val name: String, val address: String, val regular: Double?, val miles: Double?)

    // ---------------- pure (unit-tested) ----------------
    private fun secs(s: String?) = s?.removeSuffix("s")?.toDoubleOrNull()?.toLong() ?: 0L

    fun parseRoute(body: String): Route? {
        val r = JSONObject(body).optJSONArray("routes")?.optJSONObject(0) ?: return null
        return Route(secs(r.optString("duration")), secs(r.optString("staticDuration")), r.optLong("distanceMeters"))
    }

    fun mins(s: Long) = ((s + 30) / 60).let { if (it < 60) "$it min" else "${it / 60} h ${it % 60} min".replace(" 0 min", "") }

    /** "18 min (12.4 miles), about 5 min slower than usual because of traffic." */
    fun describe(to: String, r: Route): String {
        val miles = String.format(Locale.US, "%.1f", r.meters / 1609.34)
        val extra = (r.seconds - r.usualSeconds) / 60
        val traffic = when { r.usualSeconds == 0L -> ""; extra >= 3 -> ", about $extra min slower than usual because of traffic"; else -> ", traffic's normal" }
        return "To $to: ${mins(r.seconds)} ($miles miles)$traffic."
    }

    fun miles(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 3958.8; val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    fun parseGas(body: String, lat: Double?, lon: Double?): List<Gas> {
        val a = JSONObject(body).optJSONArray("places") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            val prices = p.optJSONObject("fuelOptions")?.optJSONArray("fuelPrices") ?: JSONArray()
            val reg = (0 until prices.length()).map { prices.getJSONObject(it) }.firstOrNull { it.optString("type") == "REGULAR_UNLEADED" }?.optJSONObject("price")
                ?.let { it.optString("units").toDoubleOrNull()?.plus(it.optLong("nanos") / 1e9) }
            val loc = p.optJSONObject("location")
            val d = if (lat != null && lon != null && loc != null) miles(lat, lon, loc.optDouble("latitude"), loc.optDouble("longitude")) else null
            Gas(p.optJSONObject("displayName")?.optString("text").orEmpty(), p.optString("formattedAddress").substringBefore(",").take(40), reg, d)
        }
    }

    /** Cheapest first among those with a price; stations without one go last, nearest first. */
    fun rankGas(list: List<Gas>): List<Gas> = list.sortedWith(compareBy<Gas>({ it.regular == null }, { it.regular ?: 0.0 }, { it.miles ?: 99.0 }))

    // ---------------- where he is ----------------
    fun here(c: Context): Pair<Double, Double>? {
        val fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return try {
            val lm = c.getSystemService(LocationManager::class.java)
            lm.getProviders(true).mapNotNull { pr -> try { @Suppress("MissingPermission") lm.getLastKnownLocation(pr) } catch (_: SecurityException) { null } }
                .filter { System.currentTimeMillis() - it.time < 6 * 3600_000L }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
        } catch (_: Exception) { null }
    }

    private fun post(key: String, url: String, mask: String, body: JSONObject): Pair<Int, String> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 7000; c.readTimeout = 10000
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("X-Goog-Api-Key", key); c.setRequestProperty("X-Goog-FieldMask", mask)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val b = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect(); code to b
    } catch (e: Exception) { -1 to (e.message ?: "") }

    private fun noKey(p: Prefs) = p.mapsKey.isBlank()
    private fun keyHelp(code: Int, api: String) = if (code == 403 || code == 400) "Google Maps said $code: turn on the $api in Google Cloud for your Maps key." else "Couldn't reach Google Maps ($code)."
    private fun place(s: String) = JSONObject().put("address", s)
    private fun point(ll: Pair<Double, Double>) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", ll.first).put("longitude", ll.second)))

    /** Drive time with live traffic, from where he is (or [from]) to [to]. */
    fun driveTime(c: Context, to: String, from: String?): String {
        val p = Prefs(c)
        if (noKey(p)) return "Google Maps isn't set up. Add the Maps key in setup."
        val origin = from?.takeIf { it.isNotBlank() }?.let { place(it) } ?: here(c)?.let { point(it) } ?: place(p.city.ifBlank { "Spokane, WA" })
        val body = JSONObject().put("origin", origin).put("destination", place(to)).put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_AWARE").put("units", "IMPERIAL")
        val (code, b) = post(p.mapsKey, "https://routes.googleapis.com/directions/v2:computeRoutes", "routes.duration,routes.staticDuration,routes.distanceMeters", body)
        if (code != 200) return keyHelp(code, "Routes API")
        val r = parseRoute(b) ?: return "Google Maps couldn't find a drive to $to."
        return describe(to, r)
    }

    /** The cheapest regular gas near him (or near [near]). */
    fun gas(c: Context, near: String?): String {
        val p = Prefs(c)
        if (noKey(p)) return "Google Maps isn't set up. Add the Maps key in setup."
        val mask = "places.displayName,places.formattedAddress,places.location,places.fuelOptions"
        val ll = if (near.isNullOrBlank()) here(c) else null
        val (code, b) = if (ll != null) post(p.mapsKey, "https://places.googleapis.com/v1/places:searchNearby", mask, JSONObject()
            .put("includedTypes", JSONArray().put("gas_station")).put("maxResultCount", 15).put("rankPreference", "DISTANCE")
            .put("locationRestriction", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", ll.first).put("longitude", ll.second)).put("radius", 8000.0))))
        else post(p.mapsKey, "https://places.googleapis.com/v1/places:searchText", mask, JSONObject()
            .put("textQuery", "gas station near " + (near?.takeIf { it.isNotBlank() } ?: p.city.ifBlank { "Spokane, WA" })).put("pageSize", 15))
        if (code != 200) return keyHelp(code, "Places API (New)")
        val list = rankGas(parseGas(b, ll?.first, ll?.second))
        if (list.isEmpty()) return "No gas stations came back."
        val priced = list.filter { it.regular != null }
        if (priced.isEmpty()) return "Google doesn't have prices for the stations nearby right now. Closest: ${list.sortedBy { it.miles ?: 99.0 }.take(3).joinToString(", ") { it.name }}."
        fun line(g: Gas) = "${g.name} on ${g.address}, $" + String.format(Locale.US, "%.2f", g.regular) + (g.miles?.let { String.format(Locale.US, ", %.1f mi", it) } ?: "")
        return "Cheapest regular: ${line(priced[0])}." + (if (priced.size > 1) " Then ${priced.drop(1).take(2).joinToString("; ") { line(it) }}." else "")
    }
}
