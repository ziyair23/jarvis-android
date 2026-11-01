package com.ziyair.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Jarvis's hands: does a task on the phone the way a person would, by LOOKING at the screen.
 * Each step it takes a screenshot, lists everything tappable with a number, and asks a small, cheap Claude model
 * what to do next (tap #7, type this, scroll, back...). Repeats until the task is done (12 steps at most).
 * Never sends, posts, buys or deletes anything unless he already said yes.
 */
object Hands {
    private const val MAX_STEPS = 12
    @Volatile var busy = false

    class Shot(val jpeg: ByteArray, val scale: Float)
    class El(val id: Int, val label: String, val kind: String, val cx: Int, val cy: Int, val node: AccessibilityNodeInfo)

    /** A downscaled screenshot (720 px wide) through screen control. Android 11+. */
    fun screenshot(): Shot? {
        val s = JarvisAccess.me ?: return null
        if (Build.VERSION.SDK_INT < 30) return null
        val done = CountDownLatch(1)
        var bmp: Bitmap? = null
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Exception) {}
                    try { r.hardwareBuffer.close() } catch (_: Exception) {}
                    done.countDown()
                }
                override fun onFailure(errorCode: Int) { done.countDown() }
            })
        } catch (_: Exception) { return null }
        done.await(3, TimeUnit.SECONDS)
        val full = bmp ?: return null
        val scale = 720f / full.width
        val small = Bitmap.createScaledBitmap(full, 720, (full.height * scale).toInt(), true)
        full.recycle()
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        small.recycle()
        return Shot(out.toByteArray(), scale)
    }

    /** Everything visible that can be tapped or typed into, numbered, with its centre on the screenshot. */
    fun elements(scale: Float): List<El> {
        val root = JarvisAccess.me?.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<El>()
        val r = Rect()
        JarvisAccess.walk(root) { n ->
            if (!n.isVisibleToUser || out.size >= 70) return@walk
            val label = (n.text?.toString()?.trim().orEmpty().ifBlank { n.contentDescription?.toString()?.trim().orEmpty() })
                .ifBlank { n.hintText?.toString()?.trim().orEmpty() }.replace(Regex("\\s+"), " ").take(60)
            val kind = when { n.isEditable -> "text box"; n.isClickable || n.isCheckable -> "button"; n.isScrollable -> "list"; else -> return@walk }
            if (label.isBlank() && kind == "button") {
                // unlabeled icons still count: the model can see them in the picture
            }
            n.getBoundsInScreen(r)
            if (r.width() < 8 || r.height() < 8) return@walk
            out.add(El(out.size + 1, label, kind, (r.centerX() * scale).toInt(), (r.centerY() * scale).toInt(), n))
        }
        return out
    }

    private fun describe(els: List<El>, app: String): String =
        "App on screen: $app\nThings on screen (number, kind, label, centre x,y on the picture):\n" +
            els.joinToString("\n") { "#${it.id} ${it.kind} \"${it.label}\" (${it.cx},${it.cy})" }

    private fun tools(): JSONArray {
        fun t(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = listOf()) = JSONObject().put("name", name).put("description", desc)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req)))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        fun n(d: String) = JSONObject().put("type", "number").put("description", d)
        fun b(d: String) = JSONObject().put("type", "boolean").put("description", d)
        fun enumOf(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
        return JSONArray()
            .put(t("tap", "Tap an element by its number (best), or at x,y on the picture if it isn't listed.", JSONObject().put("id", n("Element number")).put("x", n("x on picture")).put("y", n("y on picture")).put("long_press", b("Long press"))))
            .put(t("type", "Type into the focused text box (or the one with this number).", JSONObject().put("text", s("Text to type")).put("id", n("Text box number, optional")).put("submit", b("Press enter/search after")), listOf("text")))
            .put(t("scroll", "Scroll the screen.", JSONObject().put("direction", enumOf("down", "up")), listOf("direction")))
            .put(t("button", "A phone button.", JSONObject().put("which", enumOf("back", "home", "recent_apps", "notifications")), listOf("which")))
            .put(t("open_app", "Open an app by name.", JSONObject().put("name", s("App name")), listOf("name")))
            .put(t("wait", "Wait for the screen to load.", JSONObject().put("seconds", n("1-4"))))
            .put(t("done", "Finish. Say what happened in one short sentence (or what's stopping you).", JSONObject().put("result", s("What happened")).put("success", b("Did it work")), listOf("result", "success")))
    }

    /** Runs the task (blocking, background thread). Returns one line about what happened. */
    fun run(context: Context, task: String, confirmed: Boolean): String {
        if (JarvisAccess.me == null) return JarvisAccess.NEED
        if (Build.VERSION.SDK_INT < 30) return "Seeing the screen needs Android 11 or newer."
        val key = Prefs(context).claudeKey
        if (key.isBlank()) return "No Claude key set."
        if (busy) return "Already doing something on the phone."
        busy = true
        Trace.log(context, "hands", "task: $task (confirmed=$confirmed)")
        try {
            val cfg = Brain(context).config()
            val model = cfg.optString("hands_model", "claude-haiku-5-5")
            val system = "You operate an Android phone for Marcus, step by step, by looking at screenshots. Task: $task\n" +
                (if (confirmed) "He has already confirmed this, so finishing it (pressing Send, Post and so on) is OK.\n"
                 else "He has NOT confirmed anything risky: never press Send, Post, Share, Buy, Pay, Delete, Call or Accept. Get everything ready, then call done saying it's ready for his yes.\n") +
                "Each turn you get a screenshot and a numbered list of what's on screen. Use exactly one tool per turn. Prefer tapping by number. " +
                "If an app needs opening, use open_app. If a page is loading, wait. If you're stuck after a couple of tries, call done with success=false and say why. " +
                "Never type passwords or payment details. Keep going until the task is truly finished, then call done."
            val msgs = JSONArray()
            var lastEls: List<El> = emptyList()
            for (step in 1..MAX_STEPS) {
                val shot = screenshot() ?: return "Couldn't see the screen (it may be locked)."
                lastEls = elements(shot.scale)
                val app = JarvisAccess.me?.rootInActiveWindow?.packageName?.toString().orEmpty()
                // only the newest screenshot travels with the request (older ones become a note): keeps it cheap
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    val c = m.opt("content")
                    if (c is JSONArray) for (j in 0 until c.length()) {
                        val part = c.getJSONObject(j)
                        if (part.optString("type") == "tool_result") {
                            val inner = part.optJSONArray("content") ?: continue
                            val keep = JSONArray()
                            for (k in 0 until inner.length()) if (inner.getJSONObject(k).optString("type") != "image") keep.put(inner.get(k))
                            part.put("content", keep)
                        }
                        if (part.optString("type") == "image") c.put(j, JSONObject().put("type", "text").put("text", "(earlier screenshot)"))
                    }
                }
                val view = JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", android.util.Base64.encodeToString(shot.jpeg, android.util.Base64.NO_WRAP))))
                    .put(JSONObject().put("type", "text").put("text", describe(lastEls, app)))
                if (msgs.length() == 0) msgs.put(JSONObject().put("role", "user").put("content", view))
                else {
                    // attach the new screen to the pending tool result
                    val last = msgs.getJSONObject(msgs.length() - 1)
                    val parts = last.getJSONArray("content")
                    val tr = parts.getJSONObject(parts.length() - 1)
                    val inner = tr.optJSONArray("content") ?: JSONArray()
                    for (k in 0 until view.length()) inner.put(view.get(k))
                    tr.put("content", inner)
                }
                val body = JSONObject().put("model", model).put("max_tokens", 300).put("system", system).put("tools", tools())
                    .put("tool_choice", JSONObject().put("type", "any")).put("messages", msgs)
                val resp = post(key, body) ?: return "Lost connection while working on the phone."
                Spend.record(context, cfg, model, resp.optJSONObject("usage"))
                val content = resp.optJSONArray("content") ?: return "Something went wrong on the phone."
                var call: JSONObject? = null
                for (i in 0 until content.length()) if (content.getJSONObject(i).optString("type") == "tool_use") call = content.getJSONObject(i)
                val c = call ?: return "Couldn't work out the next step."
                msgs.put(JSONObject().put("role", "assistant").put("content", content))
                val input = c.optJSONObject("input") ?: JSONObject()
                val name = c.optString("name")
                Trace.log(context, "hands", "step $step: $name $input")
                if (name == "done") return input.optString("result").ifBlank { if (input.optBoolean("success")) "Done." else "Couldn't finish it." }
                val result = act(context, name, input, lastEls, shot.scale)
                msgs.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "tool_result")
                    .put("tool_use_id", c.optString("id")).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result))))))
                Thread.sleep(if (name == "wait") 0 else 700)
            }
            return "Ran out of steps before finishing. It's partway there on screen."
        } catch (e: Exception) {
            Problems.log(context, "hands: ${e.message}")
            return "Something went wrong working the phone."
        } finally { busy = false }
    }

    private fun act(context: Context, name: String, input: JSONObject, els: List<El>, scale: Float): String = try {
        when (name) {
            "tap" -> {
                val el = if (input.has("id")) els.firstOrNull { it.id == input.optInt("id") } else null
                val long = input.optBoolean("long_press")
                if (el != null) {
                    var t: AccessibilityNodeInfo? = el.node; var d = 0
                    while (t != null && !t.isClickable && d < 6) { t = t.parent; d++ }
                    val ok = t?.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (ok) "Tapped #${el.id}." else if (JarvisAccess.tapAt(el.cx / scale, el.cy / scale, long)) "Tapped #${el.id} by position." else "That tap didn't register."
                } else if (input.has("x") && input.has("y")) {
                    if (JarvisAccess.tapAt(input.optDouble("x").toFloat() / scale, input.optDouble("y").toFloat() / scale, long)) "Tapped there." else "That tap didn't register."
                } else "Need an element number or x,y."
            }
            "type" -> {
                val el = if (input.has("id")) els.firstOrNull { it.id == input.optInt("id") } else null
                el?.node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                el?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                JarvisAccess.type(input.optString("text"), input.optBoolean("submit")).substringBefore(" Now ")
            }
            "scroll" -> JarvisAccess.press(if (input.optString("direction") == "up") "scroll_up" else "scroll_down").substringBefore(" Now ")
            "button" -> JarvisAccess.press(input.optString("which", "back")).substringBefore(" Now ")
            "open_app" -> Apps.open(context, input.optString("name"))
            "wait" -> { Thread.sleep((input.optDouble("seconds", 1.5).coerceIn(0.5, 4.0) * 1000).toLong()); "Waited." }
            else -> "Unknown action."
        }
    } catch (e: Exception) { "That didn't work: ${e.message}" }

    private fun post(key: String, body: JSONObject): JSONObject? = try {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = 30000
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (!ok) { null } else JSONObject(text)
    } catch (_: Exception) { null }
}
