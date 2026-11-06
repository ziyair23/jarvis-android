package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Message Jarvis from his laptop through his own Telegram bot (token from @BotFather, typed into setup).
 * The phone asks Telegram for new messages (long polling, no server needed), runs them through Jarvis's brain
 * exactly like something he said, and answers in the chat. Only his chat is ever answered: setup shows a one-time
 * 6-digit code, and only a private chat that sends "/start <code>" becomes his (his chat AND his Telegram user).
 * Everyone else, and every group, is ignored. Sends, calls and buys still wait for his yes, same as by voice.
 */
object Telegram {
    @Volatile private var worker: Thread? = null
    @Volatile private var generation = 0
    @Volatile var lastError = ""

    // ---------------- pure (unit-tested) ----------------
    data class Update(val id: Long, val chat: Long, val name: String, val text: String, val from: Long = 0L, val type: String = "private")

    fun parseUpdates(body: String): List<Update> {
        val a = JSONObject(body).optJSONArray("result") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val u = a.getJSONObject(i); val m = u.optJSONObject("message") ?: u.optJSONObject("edited_message")
            val chat = m?.optJSONObject("chat")
            Update(u.optLong("update_id"), chat?.optLong("id") ?: 0L, m?.optJSONObject("from")?.optString("first_name").orEmpty(), m?.optString("text").orEmpty(),
                m?.optJSONObject("from")?.optLong("id") ?: 0L, chat?.optString("type").orEmpty())
        }
    }

    /** Pairing: a private chat sending exactly "/start <code>" with the code shown in setup. */
    fun pairs(code: String, u: Update): Boolean = code.length == 6 && u.type == "private" && u.from != 0L &&
        u.text.trim().replace(Regex("\\s+"), " ").let { it == "/start $code" || it == code }

    /** After pairing: only his private chat, from his own Telegram account. */
    fun allowed(owner: Long, ownerFrom: Long, u: Update): Boolean = owner != 0L && u.type == "private" && u.chat == owner && u.from == ownerFrom

    /** Telegram caps a message at 4096 characters. */
    fun chunks(t: String, max: Int = 3900): List<String> = if (t.length <= max) listOf(t) else t.chunked(max)

    // ---------------- state ----------------
    fun owner(c: Context) = Prefs(c).sp.getLong("tg_owner", 0L)
    private fun ownerFrom(c: Context) = Prefs(c).sp.getLong("tg_owner_from", 0L)
    fun ownerName(c: Context) = Prefs(c).sp.getString("tg_owner_name", "").orEmpty()
    fun unpair(c: Context) = Prefs(c).sp.edit().remove("tg_owner").remove("tg_owner_from").remove("tg_owner_name").remove("tg_offset").remove("tg_code").apply()
    /** The one-time pairing code shown in setup (made fresh after every unpair). */
    fun code(c: Context): String {
        val p = Prefs(c); val have = p.sp.getString("tg_code", "").orEmpty()
        if (have.length == 6) return have
        val n = String.format(java.util.Locale.US, "%06d", java.security.SecureRandom().nextInt(1_000_000))
        p.sp.edit().putString("tg_code", n).apply(); return n
    }
    fun running() = worker?.isAlive == true

    private fun api(token: String, method: String, params: Map<String, String>, readMs: Int = 15000): Pair<Int, String> = try {
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = readMs
        c.setRequestProperty("content-type", "application/x-www-form-urlencoded")
        c.outputStream.use { o -> o.write(params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect(); code to body
    } catch (e: Exception) { -1 to (e.message ?: "") }

    /** Jarvis sends something to his laptop chat (a list, a note, an address). */
    fun send(c: Context, text: String): String {
        val p = Prefs(c); val o = owner(c)
        if (p.telegramToken.isBlank()) return "Telegram isn't set up. Add the bot token in setup."
        if (o == 0L) return "Telegram isn't paired yet: send /start to your bot from your laptop."
        for (part in chunks(text)) {
            val (code, body) = api(p.telegramToken, "sendMessage", mapOf("chat_id" to o.toString(), "text" to part))
            if (code != 200) return "Telegram didn't take it ($code): ${body.take(80)}"
        }
        return "Sent to your Telegram."
    }

    // ---------------- the listener ----------------
    fun start(c: Context) {
        val app = c.applicationContext
        if (Prefs(app).telegramToken.isBlank()) return
        synchronized(this) {
            if (running()) return
            val gen = ++generation
            worker = Thread({ loop(app, gen) }, "jarvis-telegram").also { it.isDaemon = true; it.start() }
        }
    }

    /** Stops the listener: a blocked read can't be interrupted, so an old thread just notices it's stale and leaves. */
    fun stop() { synchronized(this) { generation++; worker?.interrupt(); worker = null } }
    fun restart(c: Context) { stop(); start(c) }

    private fun loop(c: Context, gen: Int) {
        val p = Prefs(c)
        var backoff = 2000L
        api(p.telegramToken, "deleteWebhook", emptyMap())
        while (gen == generation) {
            try {
                val token = p.telegramToken
                if (token.isBlank()) return
                val offset = p.sp.getLong("tg_offset", 0L)
                val (code, body) = api(token, "getUpdates", mapOf("timeout" to "50", "offset" to offset.toString(), "allowed_updates" to "[\"message\"]"), readMs = 65000)
                if (gen != generation) return
                if (code != 200) {
                    lastError = if (code == 401 || code == 404) "Telegram bot token isn't right" else "Telegram unreachable ($code)"
                    if (code == 401 || code == 404) { Problems.log(c, lastError); return }
                    Thread.sleep(backoff); backoff = minOf(backoff * 2, 120_000L); continue
                }
                backoff = 2000L; lastError = ""
                for (u in parseUpdates(body)) {
                    p.sp.edit().putLong("tg_offset", u.id + 1).apply()
                    if (u.text.isBlank()) continue
                    if (owner(c) == 0L) {
                        if (!pairs(code(c), u)) continue
                        p.sp.edit().putLong("tg_owner", u.chat).putLong("tg_owner_from", u.from).putString("tg_owner_name", u.name).remove("tg_code").apply()
                        Trace.log(c, "telegram", "paired with ${u.name}")
                        api(token, "sendMessage", mapOf("chat_id" to u.chat.toString(), "text" to "Paired, sir. This chat is now yours. Message me here any time from your laptop."))
                        LogBus.changed(); continue
                    }
                    if (!allowed(owner(c), ownerFrom(c), u)) continue
                    handle(c, token, u)
                }
            } catch (_: InterruptedException) { return
            } catch (e: Exception) {
                // a bad answer (captive Wi-Fi page, half a reply): log it and keep listening
                Problems.log(c, "telegram: ${e.message}")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = minOf(backoff * 2, 120_000L)
            }
        }
    }

    private fun handle(c: Context, token: String, u: Update) {
        api(token, "sendChatAction", mapOf("chat_id" to u.chat.toString(), "action" to "typing"))
        val text = u.text.trim()
        val reply = when {
            text == "/start" -> "Already paired, sir."
            text == "/status" -> try { Status.report(c) } catch (_: Exception) { "Running." }
            else -> try {
                Agents.active["phone"] = System.currentTimeMillis()
                Brain(c).ask("(on Telegram, typed from his laptop) $text").ifBlank { "Done." }
            } catch (e: Exception) { "Something went wrong: ${e.message}" } finally { Agents.active.remove("phone") }
        }
        for (part in chunks(reply)) api(token, "sendMessage", mapOf("chat_id" to u.chat.toString(), "text" to part))
        LogBus.changed()
    }
}
