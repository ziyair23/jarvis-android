package com.ziyair.jarvis

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.util.Calendar

/**
 * Recognises HIS home Wi-Fi (not just any Wi-Fi) without needing location permission: each network has its own
 * router address and address range, which Jarvis remembers as "home". Learned by tapping "This is my home Wi-Fi",
 * saying "Jarvis, this is home", or automatically the first time he runs the home or goodnight protocol on Wi-Fi.
 */
object HomeNet {
    /** "router|dhcp|range" for a Wi-Fi network. */
    fun fingerprint(context: Context, network: Network? = null): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = network ?: cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: ""
        val dhcp = if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress ?: "" else ""
        val range = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.let { la ->
            val b = la.address.address; val bits = la.prefixLength
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
            val n = ip and mask
            "${(n ushr 24) and 255}.${(n ushr 16) and 255}.${(n ushr 8) and 255}.${n and 255}/$bits"
        } ?: ""
        if (gw.isBlank() && range.isBlank()) return null
        return "$gw|$dhcp|$range"
    }

    fun saved(p: Prefs) = p.sp.getString("home_net", "") ?: ""
    fun name(p: Prefs) = p.sp.getString("home_net_name", "") ?: ""

    /** Saves the Wi-Fi he's on right now as home. */
    fun setHere(context: Context): String {
        val fp = fingerprint(context) ?: return "He's not on Wi-Fi right now, so there's nothing to save as home."
        val p = Prefs(context)
        @Suppress("DEPRECATION")
        val ssid = try { context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && !it.contains("unknown") } } catch (_: Exception) { null }
        p.sp.edit().putString("home_net", fp).putString("home_net_name", ssid ?: "").putBoolean("at_home", true).apply()
        return "Saved this Wi-Fi${ssid?.let { " ($it)" } ?: ""} as home. Next time he gets back, Jarvis will welcome him home."
    }

    /** Learn home quietly the first time he does a home-ish thing on Wi-Fi. */
    fun learnIfUnknown(context: Context) { if (saved(Prefs(context)).isBlank()) setHere(context) }

    fun isHome(context: Context, network: Network?): Boolean {
        val s = saved(Prefs(context)); if (s.isBlank()) return false
        val fp = fingerprint(context, network) ?: return false
        val (g1, d1, r1) = s.split("|").let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        val (g2, d2, r2) = fp.split("|").let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        return g1 == g2 && r1 == r2 && (d1.isBlank() || d2.isBlank() || d1 == d2)
    }

    /** What Jarvis says (and does) when he walks in: the home protocol, out loud. Blocking. */
    fun welcome(context: Context): Pair<String, Boolean> {
        val p = Prefs(context)
        DriveMode.on = false
        val late = Lights.late()
        val parts = mutableListOf("Welcome home, sir.")
        if (Lights.available(context)) {
            val r = try { Lights.set(context, "all", true, if (late) 15 else 80, "warm") } catch (_: Exception) { "" }
            if (r.startsWith("Done")) parts.add(if (late) "Lights are on, kept low for the little man." else "Lights are on.")
        }
        val earned = Earnings.today(context)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var ask = false
        if (earned > 0) parts.add("You've made ${Earnings.money(earned)} today.")
        else if (h >= 10 && h <= 22) { parts.add("How'd the shift go?"); ask = true }
        lastFeedLine(p)?.let { parts.add(it) }
        val todo = HomeReminders.take(context)
        if (todo.isNotEmpty()) parts.add("You asked me to remind you: " + todo.joinToString(", and ") + ".")
        if (ask) { parts.remove("How'd the shift go?"); parts.add("How'd the shift go?") }
        return parts.joinToString(" ") to ask
    }

    private fun lastFeedLine(p: Prefs): String? {
        val a = Baby.all(p)
        for (i in a.length() - 1 downTo 0) {
            val e = a.getJSONObject(i)
            if (e.optString("k") != "feed") continue
            val mins = ((System.currentTimeMillis() - e.optLong("at")) / 60000).toInt()
            if (mins > 12 * 60) return null
            val name = p.babyName.ifBlank { "The baby" }
            return if (mins < 60) "$name ate about $mins minutes ago." else "$name last ate about ${mins / 60} hour${if (mins / 60 == 1) "" else "s"}${if (mins % 60 >= 30) " and a half" else ""} ago."
        }
        return null
    }
}
