package com.ziyair.jarvis

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Message Jarvis from his laptop through his own Telegram bot (token from @BotFather, typed into setup).
 * The phone asks Telegram for new messages (long polling, no server needed), runs them through Jarvis's brain
 * exactly like something he said, and answers in the chat. Only his chat is ever answered: the first chat that
 * sends /start after the token is saved becomes his, and every other chat is ignored. Sends, calls and buys still
 * wait for his yes, same as by voice.
 */
object Telegram {
    @Volatile private var worker: Thread? = null
    @Volatile private var stop = false
    @Volatile var lastError = ""

    // ---------------- pure (unit-tested) ----------------
    data class Update(val id: Long, val chat: Long, val name: String, val text: String)

    fun parseUpdates(body: String): List<Update> {
        val a = JSONObject(body).optJSONArray("result") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val u = a.getJSONObject(i); val m = u.optJSONObject("message") ?: u.optJSONObject("edited_message")
            val chat = m?.optJSONObject("chat")
            Update(u.optLong("update_id"), chat?.optLong("id") ?: 0L, m?.optJSONObject("from")?.optString("first_name").orEmpty(), m?.optString("text").orEmpty())
        }
    }

    /** Who may talk to Jarvis: his paired chat only; with none paired yet, only a /start claims it. */
    fun allowed(owner: Long, u: Update): Boolean = if (owner == 0L) u.text.trim().startsWith("/start") else u.chat == owner

    /** Telegram caps a message at 4096 characters. */
    fun chunks(t: String, max: Int = 3900): List<String> = if (t.length <= max) listOf(t) else t.chunked(max)

    // ---------------- state ----------------
    fun owner(c: Context) = Prefs(c).sp.getLong("tg_owner", 0L)
    fun ownerName(c: Context) = Prefs(c).sp.getString("tg_owner_name", "").orEmpty()
    fun unpair(c: Context) = Prefs(c).sp.edit().remove("tg_owner").remove("tg_owner_name").remove("tg_offset").apply()
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
        if (Prefs(app).telegramToken.isBlank() || running()) return
        stop = false
        worker = Thread({ loop(app) }, "jarvis-telegram").also { it.isDaemon = true; it.start() }
    }

    fun stop() { stop = true; worker?.interrupt(); worker = null }
    fun restart(c: Context) { stop(); Thread.sleep(50); start(c) }

    private fun loop(c: Context) {
        val p = Prefs(c)
        var backoff = 2000L
        api(p.telegramToken, "deleteWebhook", emptyMap())
        while (!stop) {
            val token = p.telegramToken
            if (token.isBlank()) return
            val offset = p.sp.getLong("tg_offset", 0L)
            val (code, body) = api(token, "getUpdates", mapOf("timeout" to "50", "offset" to offset.toString(), "allowed_updates" to "[\"message\"]"), readMs = 65000)
            if (stop) return
            if (code != 200) {
                lastError = if (code == 401 || code == 404) "Telegram bot token isn't right" else "Telegram unreachable ($code)"
                if (code == 401 || code == 404) { Problems.log(c, lastError); return }
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = minOf(backoff * 2, 120_000L); continue
            }
            backoff = 2000L; lastError = ""
            for (u in parseUpdates(body)) {
                p.sp.edit().putLong("tg_offset", u.id + 1).apply()
                if (u.text.isBlank() || !allowed(owner(c), u)) continue
                if (owner(c) == 0L) {
                    p.sp.edit().putLong("tg_owner", u.chat).putString("tg_owner_name", u.name).apply()
                    Trace.log(c, "telegram", "paired with ${u.name}")
                    api(token, "sendMessage", mapOf("chat_id" to u.chat.toString(), "text" to "Paired, sir. This chat is now yours. Message me here any time from your laptop."))
                    LogBus.changed(); continue
                }
                handle(c, token, u)
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
