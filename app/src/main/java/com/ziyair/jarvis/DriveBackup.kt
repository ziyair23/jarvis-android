package com.ziyair.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Nightly encrypted backup of Jarvis's mind to Google Drive: memory, lessons, lists, baby log, bills, money, the
 * timeline, agents' notebooks, settings. It's locked on the phone with his backup passphrase (AES-256-GCM) before
 * it leaves, and lives in Jarvis's own hidden Drive folder (only this app can see it). Keys and sign-ins are never
 * in it: after a restore on a new phone he types those in again.
 */
object DriveBackup {
    private const val NAME = "jarvis-backup.bin"
    private val MAGIC = "JVB1".toByteArray()
    private const val ITER = 200_000

    /** Never backed up: every key, token and sign-in. */
    val SECRETS = setOf("claude_key", "pico_key", "groq_key", "gemini_key", "github_token", "govee_key", "tts_key",
        "google_client_id", "google_client_secret", "backup_pass", "youtube_key", "telegram_token", "maps_key", "eleven_key", "eleven_voice_key")
    fun secret(key: String) = key in SECRETS || key.startsWith("g_refresh_") || key.startsWith("g_access_") || key.startsWith("g_exp_") ||
        Regex("(?i)(_key|_token|_secret|password|passphrase)$").containsMatchIn(key)

    // ---------------- pure (unit-tested) ----------------
    fun encrypt(plain: ByteArray, pass: String, rnd: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }; val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + ci.doFinal(plain)
    }

    /** Null when the passphrase is wrong or the file isn't a Jarvis backup. */
    fun decrypt(blob: ByteArray, pass: String): ByteArray? = try {
        if (blob.size < 33 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) null else {
            val salt = blob.copyOfRange(4, 20); val iv = blob.copyOfRange(20, 32)
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
            ci.doFinal(blob.copyOfRange(32, blob.size))
        }
    } catch (_: Exception) { null }

    private fun key(pass: String, salt: ByteArray) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, ITER, 256)).encoded, "AES")

    /** Settings map -> JSON, typed, without secrets. */
    fun pack(all: Map<String, *>, timeline: String?, at: Long): JSONObject {
        val o = JSONObject()
        for ((k, v) in all) {
            if (secret(k) || v == null) continue
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            o.put(k, e)
        }
        return JSONObject().put("v", 1).put("at", at).put("prefs", o).apply { if (timeline != null) put("timeline", timeline) }
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    // ---------------- on the phone ----------------
    private fun timelineFile(c: Context) = File(c.applicationContext.filesDir, "timeline.jsonl")

    fun on(c: Context) = GoogleAuth.connected(c, GoogleAuth.GOOGLE) && Prefs(c).backupPass.length >= 8

    /** Once a night: the first time the phone is used after 20 hours. */
    fun maybeBackup(c: Context) {
        if (!on(c)) return
        if (System.currentTimeMillis() - Prefs(c).sp.getLong("backup_ok_at", 0L) < 20 * 3600_000L) return
        if (System.currentTimeMillis() - Prefs(c).sp.getLong("backup_try_at", 0L) < 2 * 3600_000L) return
        backupNow(c)
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun backupNow(c: Context): String {
        val p = Prefs(c)
        if (!GoogleAuth.connected(c, GoogleAuth.GOOGLE)) return "Drive isn't connected. Tap Connect Google in setup."
        if (p.backupPass.length < 8) return "Set a backup passphrase (8 characters or more) in setup first."
        if (!busy.compareAndSet(false, true)) return "Already backing up."
        p.sp.edit().putLong("backup_try_at", System.currentTimeMillis()).apply()
        try {
            val tl = timelineFile(c).takeIf { it.exists() && it.length() < 12L * 1024 * 1024 }?.readText()
            val plain = gzip(pack(p.sp.all, tl, System.currentTimeMillis()).toString().toByteArray())
            val blob = encrypt(plain, p.backupPass)
            val (found, id) = findId(c)
            if (!found) return "Couldn't reach Drive to back up. I'll try again later."
            val (code, body) = if (id == null) {
                val boundary = "jarvis" + System.nanoTime()
                val meta = JSONObject().put("name", NAME).put("parents", JSONArray().put("appDataFolder")).toString()
                val out = ByteArrayOutputStream()
                out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                out.write(blob); out.write("\r\n--$boundary--\r\n".toByteArray())
                GoogleAuth.api(c, GoogleAuth.GOOGLE, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", raw = out.toByteArray(), contentType = "multipart/related; boundary=$boundary")
            } else patch(c, id, blob)
            if (code !in 200..299) { Problems.log(c, "Drive backup $code: ${body.take(160)}"); return "The backup didn't go through (Drive said $code). I'll try again later." }
            p.sp.edit().putLong("backup_ok_at", System.currentTimeMillis()).putLong("backup_size", blob.size.toLong()).apply()
            Trace.log(c, "backup", "backed up ${blob.size / 1024} KB to Drive")
            return "Backed up to your Drive, locked with your passphrase (${maxOf(1, blob.size / 1024)} KB)."
        } catch (e: Exception) { Problems.log(c, "backup: ${e.message}"); return "Backup failed: ${e.message}"
        } finally { busy.set(false) }
    }

    // a real PATCH (HttpURLConnection can't send one), through OkHttp
    private fun patch(c: Context, id: String, blob: ByteArray): Pair<Int, String> {
        val t = GoogleAuth.token(c, GoogleAuth.GOOGLE) ?: return 0 to "not connected"
        return try {
            val req = Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
                .header("Authorization", "Bearer $t").patch(blob.toRequestBody("application/octet-stream".toMediaType())).build()
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: Exception) { -1 to (e.message ?: "no connection") }
    }

    /** (reached Drive, newest backup's id or null if there isn't one). */
    private fun findId(c: Context): Pair<Boolean, String?> {
        val q = java.net.URLEncoder.encode("name='$NAME'", "UTF-8")
        val order = java.net.URLEncoder.encode("modifiedTime desc", "UTF-8")
        val (code, body) = GoogleAuth.api(c, GoogleAuth.GOOGLE, "GET", "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id,modifiedTime)&orderBy=$order&q=$q")
        if (code != 200) return false to null
        val f = try { JSONObject(body).optJSONArray("files") } catch (_: Exception) { return false to null } ?: return true to null
        return true to (if (f.length() > 0) f.getJSONObject(0).optString("id").ifBlank { null } else null)
    }

    /** Pulls the backup down and puts everything back (keys stay as they are on this phone). */
    fun restore(c: Context): String = try { restoreInner(c) } catch (e: Exception) { Problems.log(c, "restore: ${e.message}"); "Couldn't restore: ${e.message}" }

    private fun restoreInner(c: Context): String {
        val p = Prefs(c)
        if (!GoogleAuth.connected(c, GoogleAuth.GOOGLE)) return "Connect Google first."
        if (p.backupPass.isBlank()) return "Type the backup passphrase in setup first."
        val (found, id0) = findId(c)
        if (!found) return "Couldn't reach Drive."
        val id = id0 ?: return "There's no Jarvis backup in this Google account yet."
        val blob = GoogleAuth.bytes(c, GoogleAuth.GOOGLE, "https://www.googleapis.com/drive/v3/files/$id?alt=media") ?: return "Couldn't download the backup."
        val plain = decrypt(blob, p.backupPass) ?: return "That passphrase doesn't open this backup."
        val j = JSONObject(String(gunzip(plain)))
        val prefs = j.optJSONObject("prefs") ?: return "The backup is empty."
        val e = p.sp.edit()
        for (k in prefs.keys()) {
            if (secret(k)) continue
            val o = prefs.getJSONObject(k)
            when (o.optString("t")) {
                "s" -> e.putString(k, o.optString("v")); "b" -> e.putBoolean(k, o.optBoolean("v")); "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v")); "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "ss" -> e.putStringSet(k, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
            }
        }
        e.commit()
        j.optString("timeline").takeIf { it.isNotBlank() }?.let { timelineFile(c).writeText(it) }
        val at = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(j.optLong("at")))
        Trace.log(c, "backup", "restored the backup from $at")
        return "Restored my memory from the backup made $at. Stop and start me once so everything loads."
    }

    fun status(c: Context): String {
        val p = Prefs(c)
        if (!GoogleAuth.connected(c, GoogleAuth.GOOGLE)) return "Drive backup: not connected."
        if (p.backupPass.length < 8) return "Drive backup: needs a passphrase in setup."
        val at = p.sp.getLong("backup_ok_at", 0L)
        return if (at == 0L) "Drive backup: on, first one tonight." else "Last backup " + java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US).format(java.util.Date(at)) + "."
    }
}
