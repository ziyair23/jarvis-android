package com.ziyair.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Signing in to Google for Gmail (read only), the Drive backup (Jarvis's own hidden folder) and Nest.
 *
 * Uses his own "Desktop app" OAuth client from Google Cloud, typed into setup. The browser opens Google's own
 * sign-in page, and Google hands the answer back to a tiny one-time listener inside Jarvis on 127.0.0.1 (the
 * loopback flow Google still supports for desktop-type clients), with PKCE. Only the refresh token is kept, in the
 * app's private storage. Two separate sign-ins: "google" (Gmail + Drive) and "nest" (Device Access).
 */
object GoogleAuth {
    const val GOOGLE = "google"
    const val NEST = "nest"
    val GOOGLE_SCOPES = listOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/drive.appdata")
    const val NEST_SCOPE = "https://www.googleapis.com/auth/sdm.service"

    @Volatile var lastError = ""

    // ---------------- pure helpers (unit-tested) ----------------
    fun pkceVerifier(r: SecureRandom = SecureRandom()): String { val b = ByteArray(48); r.nextBytes(b); return b64url(b) }
    fun pkceChallenge(verifier: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun authUrl(which: String, clientId: String, redirect: String, state: String, challenge: String, nestProject: String = ""): String {
        val base = if (which == NEST) "https://nestservices.google.com/partnerconnections/${nestProject.trim()}/auth" else "https://accounts.google.com/o/oauth2/v2/auth"
        val scope = if (which == NEST) NEST_SCOPE else GOOGLE_SCOPES.joinToString(" ")
        return "$base?client_id=${enc(clientId)}&redirect_uri=${enc(redirect)}&response_type=code&access_type=offline&prompt=consent" +
            "&scope=${enc(scope)}&state=${enc(state)}&code_challenge=${enc(challenge)}&code_challenge_method=S256"
    }

    /** "GET /?code=abc&state=xyz HTTP/1.1" -> {code=abc, state=xyz}. */
    fun parseRequestLine(line: String): Map<String, String> {
        val path = line.split(" ").getOrNull(1) ?: return emptyMap()
        val q = path.substringAfter('?', "")
        if (q.isBlank()) return emptyMap()
        return q.split('&').mapNotNull { kv -> val i = kv.indexOf('='); if (i <= 0) null else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.toMap()
    }

    // ---------------- stored tokens ----------------
    fun connected(c: Context, which: String) = Prefs(c).sp.getString("g_refresh_$which", "").orEmpty().isNotBlank()
    fun account(c: Context, which: String) = Prefs(c).sp.getString("g_who_$which", "").orEmpty()
    /** Forgets the sign-in here and tells Google to cancel it too. */
    fun disconnect(c: Context, which: String) {
        val p = Prefs(c); val refresh = p.sp.getString("g_refresh_$which", "").orEmpty()
        p.sp.edit().remove("g_refresh_$which").remove("g_access_$which").remove("g_exp_$which").remove("g_who_$which").apply()
        if (refresh.isNotBlank()) Thread { form("https://oauth2.googleapis.com/revoke", mapOf("token" to refresh)) }.start()
    }

    /** Which of the Google features he left unticked on the consent screen (granular consent). Pure. */
    fun missing(granted: String): List<String> = listOfNotNull("Gmail".takeIf { !granted.contains("gmail.readonly") }, "Drive backup".takeIf { !granted.contains("drive.appdata") })

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A fresh access token, or null (not connected / no signal / Google said no). Blocking; call off the main thread. */
    @Synchronized fun token(c: Context, which: String): String? {
        val p = Prefs(c)
        val exp = p.sp.getLong("g_exp_$which", 0L)
        val cached = p.sp.getString("g_access_$which", "").orEmpty()
        if (cached.isNotBlank() && System.currentTimeMillis() < exp - 60_000L) return cached
        val refresh = p.sp.getString("g_refresh_$which", "").orEmpty()
        if (refresh.isBlank() || p.googleClientId.isBlank()) return null
        val (code, body) = form("https://oauth2.googleapis.com/token", mapOf("client_id" to p.googleClientId, "client_secret" to p.googleClientSecret,
            "refresh_token" to refresh, "grant_type" to "refresh_token"))
        if (code !in 200..299) {
            lastError = "Google sign-in $code"
            // revoked, expired (apps left in "Testing" lose sign-ins after 7 days) or the client changed: he has to connect again
            if (body.contains("invalid_grant") || body.contains("invalid_client")) {
                disconnect(c, which); Problems.log(c, "Google ($which) signed out: ${body.take(120)}. Tap Connect in setup again.")
            }
            return null
        }
        val j = JSONObject(body)
        val at = j.optString("access_token")
        p.sp.edit().putString("g_access_$which", at).putLong("g_exp_$which", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L).apply()
        return at
    }

    /** Opens Google's sign-in in the browser and waits (on a background thread) for the answer. [done] gets a sentence for the screen. */
    fun connect(c: Context, which: String, done0: (String) -> Unit) {
        val p = Prefs(c)
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val done: (String) -> Unit = { m -> if (once.compareAndSet(false, true)) done0(m) }
        if (p.googleClientId.isBlank() || p.googleClientSecret.isBlank()) { done("Add the Google client ID and secret first, then Save."); return }
        if (which == NEST && p.nestProject.isBlank()) { done("Add your Nest Device Access project ID first, then Save."); return }
        val server = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { done("Couldn't start sign-in: ${e.message}"); return }
        val redirect = "http://127.0.0.1:${server.localPort}"
        val verifier = pkceVerifier(); val state = pkceVerifier().take(20)
        val url = authUrl(which, p.googleClientId, redirect, state, pkceChallenge(verifier), p.nestProject)
        Thread {
            try {
                server.soTimeout = 5 * 60_000
                server.accept().use { sock ->
                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    val q = parseRequestLine(line)
                    val ok = q["state"] == state && !q["code"].isNullOrBlank()
                    val page = if (ok) "Jarvis is connected. You can close this and go back to the app." else "Sign-in didn't finish: ${esc(q["error"] ?: "try again")}."
                    val html = "<html><body style=\"background:#120a0b;color:#e8dcd6;font:20px sans-serif;padding:40px\">$page</body></html>"
                    sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
                    sock.getOutputStream().flush()
                    if (!ok) { done("Sign-in didn't finish (${q["error"] ?: "no code"})."); return@Thread }
                    val (code, body) = form("https://oauth2.googleapis.com/token", mapOf("client_id" to p.googleClientId, "client_secret" to p.googleClientSecret,
                        "code" to q["code"]!!, "code_verifier" to verifier, "redirect_uri" to redirect, "grant_type" to "authorization_code"))
                    if (code !in 200..299) { done("Google said no ($code): ${JSONObject(body).optString("error_description", body.take(100))}"); return@Thread }
                    val j = JSONObject(body)
                    val refresh = j.optString("refresh_token")
                    if (refresh.isBlank()) { done("Google didn't hand over a lasting sign-in. Remove Jarvis at myaccount.google.com/permissions and try again."); return@Thread }
                    p.sp.edit().putString("g_refresh_$which", refresh).putString("g_access_$which", j.optString("access_token"))
                        .putLong("g_exp_$which", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L).apply()
                    if (which == GOOGLE) Gmail.profile(c)?.let { p.sp.edit().putString("g_who_$which", it).apply() }
                    Trace.log(c, "connect", "Google ($which) connected")
                    val gaps = if (which == GOOGLE) missing(j.optString("scope")) else emptyList()
                    done(when { which == NEST -> "Nest connected, sir."; gaps.isEmpty() -> "Google connected, sir: Gmail and Drive backup are on."
                        else -> "Google connected, but ${gaps.joinToString(" and ")} was left unticked. Tap to disconnect, connect again and tick every box." })
                }
            } catch (e: java.net.SocketTimeoutException) { done("Sign-in timed out. Tap Connect again.")
            } catch (e: Exception) { done("Sign-in failed: ${e.message}")
            } finally { try { server.close() } catch (_: Exception) {} }
        }.start()
        try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { done("No browser to sign in with."); try { server.close() } catch (_: Exception) {} }
    }

    // ---------------- http ----------------
    fun form(url: String, fields: Map<String, String>): Pair<Int, String> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("content-type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect(); code to body
    } catch (e: Exception) { -1 to (e.message ?: "no connection") }

    /** Authorized JSON call. Returns (status, body). */
    fun api(c: Context, which: String, method: String, url: String, json: JSONObject? = null, raw: ByteArray? = null, contentType: String? = null): Pair<Int, String> {
        val t = token(c, which) ?: return 0 to "not connected"
        return try {
            val k = URL(url).openConnection() as HttpURLConnection
            k.requestMethod = method; k.connectTimeout = 8000; k.readTimeout = 20000
            k.setRequestProperty("Authorization", "Bearer $t")
            val payload = raw ?: json?.toString()?.toByteArray()
            if (payload != null) { k.doOutput = true; k.setRequestProperty("content-type", contentType ?: "application/json"); k.outputStream.use { it.write(payload) } }
            val code = k.responseCode
            val body = (if (code in 200..299) k.inputStream else k.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            k.disconnect(); code to body
        } catch (e: Exception) { -1 to (e.message ?: "no connection") }
    }

    /** Authorized download of raw bytes. */
    fun bytes(c: Context, which: String, url: String): ByteArray? {
        val t = token(c, which) ?: return null
        return try {
            val k = URL(url).openConnection() as HttpURLConnection
            k.connectTimeout = 8000; k.readTimeout = 30000; k.setRequestProperty("Authorization", "Bearer $t")
            val out = if (k.responseCode in 200..299) k.inputStream.use { it.readBytes() } else null
            k.disconnect(); out
        } catch (_: Exception) { null }
    }

}
