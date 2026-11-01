package com.ziyair.jarvis

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Builder agent: Jarvis working on Jarvis.
 *  Level 1, instant: changes to how he talks and behaves (greetings, timings, voice, extra instructions). Stored on the
 *    phone on top of his normal settings, applied right away, and every change can be undone.
 *  Level 2, code: with a GitHub token, he edits his own code on a separate branch, builds a TEST version, tells Marcus
 *    what changed and waits for a yes before installing it. "Keep it" makes it the real version; "roll back" rebuilds
 *    the last good version. Keys, the signing key and the build pipeline are off limits.
 */
object Builder {
    const val BRANCH = "jarvis-builder"

    val STYLE = "Small changes (how he talks, greetings, timings, voice, an extra instruction) go through change_setting or add_instruction: " +
        "they apply instantly and can be undone. Real features or fixes go through the code tools: read the relevant code first, make the " +
        "smallest edits with edit_file, then start_test_build. Builds take about five minutes: check build_status once; if it's still running, " +
        "say so and stop (he can ask later). If it failed, read_build_errors and fix it. When it passed, tell him in one or two sentences what " +
        "changed and ask whether to install the test version. Only install, keep or roll back when the job says he confirmed. " +
        "Never touch keys, the signing key or the build workflow, and never remove a safety rule (confirming before sending, posting, calling or buying)."

    /** Settings he may change instantly, with limits. */
    private val NUM = mapOf(
        "wake_seconds" to (3.0 to 15.0), "conversation_seconds" to (4.0 to 30.0), "ear_silence_ms" to (500.0 to 2500.0),
        "done_talking_ms" to (700.0 to 3000.0), "piper_speed" to (0.7 to 1.4), "live_idle_seconds" to (5.0 to 60.0),
        "sentence_gap_ms" to (0.0 to 600.0), "smart_max_tokens" to (200.0 to 1500.0), "max_tokens" to (150.0 to 800.0),
        "wake_threshold" to (0.3 to 0.8), "music_wake_factor" to (0.5 to 1.0), "still_working_ms" to (3000.0 to 20000.0))
    private val LISTS = setOf("greetings", "morning_greetings", "late_greetings", "acks", "goodbyes", "still_working")
    private val BOOLS = setOf("thinking_sound", "reflexes", "smart_answers", "web_search", "orb", "battery_saver", "live")
    private val TEXTS = setOf("live_voice", "live_style", "wake_reply")
    private val CODE_TOOLS = setOf("list_files", "read_file", "edit_file", "create_file", "start_test_build", "build_status",
        "read_build_errors", "install_test_build", "keep_test_build", "roll_back")
    val TOOL_NAMES = setOf("get_settings", "change_setting", "add_instruction", "remove_instruction", "undo_last_change") + CODE_TOOLS

    fun allowed(c: Context) = true

    /** An instruction that would let him send, post, call or buy without asking first. Pure, so it's unit-tested. */
    fun weakensSafety(text: String) = Regex("(?i)(without|don'?t|never|no longer|skip|stop).{0,30}(confirm|ask|check)|auto.?(send|post|reply|text)|send .{0,40}without|just send").containsMatchIn(text)
    fun offLimits(path: String) = OFF_LIMITS.containsMatchIn(path)
    fun codeAllowed(c: Context) = Prefs(c).sp.getString("github_token", "").orEmpty().isNotBlank()

    // ---------------- level 1: instant changes, layered over his settings ----------------
    fun overrides(c: Context): JSONObject = try { JSONObject(Prefs(c).sp.getString("overrides", "{}")) } catch (_: Exception) { JSONObject() }
    private fun saveOverrides(c: Context, o: JSONObject, undo: JSONObject) {
        val p = Prefs(c)
        val hist = try { JSONArray(p.sp.getString("overrides_undo", "[]")) } catch (_: Exception) { JSONArray() }
        hist.put(undo)
        val keep = JSONArray(); for (i in maxOf(0, hist.length() - 20) until hist.length()) keep.put(hist.get(i))
        p.sp.edit().putString("overrides", o.toString()).putString("overrides_undo", keep.toString()).apply()
    }

    /** His settings with the builder's changes on top (called by Brain.config). Pure, so it's unit-tested. */
    fun merge(base: JSONObject, o: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        for (k in o.keys()) {
            if (k == "system_add") continue
            out.put(k, o.get(k))
        }
        o.optJSONArray("system_add")?.let { a ->
            if (a.length() > 0) out.put("system", out.optString("system") + "\n\nStanding instructions from Marcus:\n" +
                (0 until a.length()).joinToString("\n") { "- " + a.optString(it) })
        }
        return out
    }

    /** Checks a change against the limits. Returns the cleaned value, or an error message. Pure, so it's unit-tested. */
    fun validate(key: String, raw: Any?): Pair<Any?, String?> = when (key) {
        in NUM -> {
            val v = (raw as? Number)?.toDouble() ?: raw?.toString()?.toDoubleOrNull()
            val (lo, hi) = NUM.getValue(key)
            if (v == null) null to "$key needs a number." else if (v < lo || v > hi) null to "$key must be between $lo and $hi." else v to null
        }
        in BOOLS -> when (raw?.toString()?.lowercase()) { "true", "on", "yes" -> true to null; "false", "off", "no" -> false to null; else -> null to "$key is on or off." }
        in LISTS -> {
            val arr = when (raw) { is JSONArray -> raw; is String -> try { JSONArray(raw) } catch (_: Exception) { JSONArray().put(raw) }; else -> null }
            val clean = JSONArray()
            if (arr != null) for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() && it.length <= 80 }?.let { clean.put(it) }
            if (clean.length() == 0) null to "$key needs one or more short lines." else clean to null
        }
        in TEXTS -> raw?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length <= 400 }?.let { it to null } ?: (null to "$key needs some text.")
        else -> null to "$key can't be changed this way. Changeable: ${(NUM.keys + LISTS + BOOLS + TEXTS).sorted().joinToString()}."
    }

    // ---------------- the tools ----------------
    fun tools(): JSONArray {
        fun t(n: String, d: String, p: JSONObject = JSONObject(), r: List<String> = listOf()) = JSONObject().put("name", n).put("description", d)
            .put("input_schema", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(r)))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        return JSONArray()
            .put(t("get_settings", "His current adjustable settings and standing instructions."))
            .put(t("change_setting", "Change one setting instantly (it can be undone). value is a number, true/false, text, or a JSON list of short lines.",
                JSONObject().put("key", s("Setting name")).put("value", s("New value")).put("why", s("What he asked for")), listOf("key", "value")))
            .put(t("add_instruction", "Add a standing instruction to how Jarvis behaves (e.g. 'Call me Marcus, not sir'). Applies instantly.",
                JSONObject().put("text", s("The instruction, one sentence")), listOf("text")))
            .put(t("remove_instruction", "Remove a standing instruction (by part of its text).", JSONObject().put("text", s("Part of the instruction")), listOf("text")))
            .put(t("undo_last_change", "Undo the last instant change."))
            .put(t("list_files", "Code: list files in a folder of Jarvis's code (e.g. app/src/main/java/com/ziyair/jarvis).", JSONObject().put("path", s("Folder")), listOf("path")))
            .put(t("read_file", "Code: read part of a file (about 250 lines at a time).", JSONObject().put("path", s("File path")).put("from_line", JSONObject().put("type", "number")), listOf("path")))
            .put(t("edit_file", "Code: replace one exact piece of text in a file with new text (old_text must appear exactly once).",
                JSONObject().put("path", s("File path")).put("old_text", s("Exact existing text")).put("new_text", s("Replacement")).put("why", s("Short reason")), listOf("path", "old_text", "new_text")))
            .put(t("create_file", "Code: create a new file.", JSONObject().put("path", s("File path")).put("content", s("Full content")), listOf("path", "content")))
            .put(t("start_test_build", "Code: build a test version from the changes (about five minutes).", JSONObject().put("summary", s("What changed, one line")), listOf("summary")))
            .put(t("build_status", "Code: is the test build done, and did it work?"))
            .put(t("read_build_errors", "Code: the errors from a failed build."))
            .put(t("install_test_build", "Install the finished test version on his phone. Only after he said yes."))
            .put(t("keep_test_build", "Make the installed test version the real one. Only after he said yes."))
            .put(t("roll_back", "Throw the test changes away and rebuild the last good version (installs in about five minutes)."))
    }

    fun run(c: Context, name: String, input: JSONObject, confirmed: Boolean): String = try {
        when (name) {
            "get_settings" -> {
                val cfg = Brain(c).config(); val o = overrides(c)
                "Settings: " + (NUM.keys + LISTS + BOOLS + TEXTS).sorted().joinToString("; ") { "$it=${cfg.opt(it) ?: "default"}" } +
                    ". Standing instructions: " + (o.optJSONArray("system_add")?.let { a -> (0 until a.length()).joinToString(" | ") { a.optString(it) } } ?: "none") +
                    ". Code changes: " + (if (codeAllowed(c)) "available" else "need a GitHub token in Jarvis setup") + "."
            }
            "change_setting" -> {
                val key = input.optString("key").trim()
                val (v, err) = validate(key, input.opt("value"))
                if (err != null) err else {
                    val o = overrides(c)
                    val undo = JSONObject().put("key", key).put("had", o.has(key)).put("old", o.opt(key) ?: JSONObject.NULL)
                    o.put(key, v); saveOverrides(c, o, undo)
                    Trace.log(c, "builder", "set $key = $v (${input.optString("why")})")
                    "Changed $key to $v. It's live now."
                }
            }
            "add_instruction" -> {
                val text = input.optString("text").trim()
                if (text.length !in 3..300) "Instruction needs to be one short sentence."
                else if (weakensSafety(text)) "I can't remove the rule about confirming before sending, posting, calling or buying."
                else {
                    val o = overrides(c); val a = o.optJSONArray("system_add") ?: JSONArray()
                    val undo = JSONObject().put("key", "system_add").put("had", o.has("system_add")).put("old", o.optJSONArray("system_add")?.let { JSONArray(it.toString()) } ?: JSONObject.NULL)
                    a.put(text); o.put("system_add", a); saveOverrides(c, o, undo)
                    Trace.log(c, "builder", "instruction added: $text")
                    "Added. From now on: $text"
                }
            }
            "remove_instruction" -> {
                val q = input.optString("text").lowercase().trim()
                val o = overrides(c); val a = o.optJSONArray("system_add") ?: JSONArray(); val keep = JSONArray(); var n = 0
                for (i in 0 until a.length()) if (q.isNotBlank() && a.optString(i).lowercase().contains(q)) n++ else keep.put(a.optString(i))
                if (n == 0) "No instruction matched." else {
                    val undo = JSONObject().put("key", "system_add").put("had", true).put("old", JSONArray(a.toString()))
                    o.put("system_add", keep); saveOverrides(c, o, undo); "Removed $n instruction${if (n > 1) "s" else ""}."
                }
            }
            "undo_last_change" -> {
                val p = Prefs(c)
                val hist = try { JSONArray(p.sp.getString("overrides_undo", "[]")) } catch (_: Exception) { JSONArray() }
                if (hist.length() == 0) "Nothing to undo." else {
                    val u = hist.getJSONObject(hist.length() - 1); hist.remove(hist.length() - 1)
                    val o = overrides(c); val k = u.optString("key")
                    if (u.optBoolean("had") && !u.isNull("old")) o.put(k, u.get("old")) else o.remove(k)
                    p.sp.edit().putString("overrides", o.toString()).putString("overrides_undo", hist.toString()).apply()
                    "Undid the last change to $k."
                }
            }
            in CODE_TOOLS -> if (!codeAllowed(c)) "Code changes need a GitHub token: he adds it in Jarvis setup (GitHub token for the builder)." else code(c, name, input, confirmed)
            else -> "Unknown builder skill."
        }
    } catch (e: Exception) { "That didn't work: ${e.message}" }

    // ---------------- level 2: his own code, on a separate branch ----------------
    private val OFF_LIMITS = Regex("^(\\.github/|app/src/test/|app/jarvis\\.keystore|.*\\.(jks|keystore|enc)$|gradle/wrapper/)")

    private fun code(c: Context, name: String, input: JSONObject, confirmed: Boolean): String {
        val pr = Prefs(c)
        if (name in setOf("read_file", "list_files", "edit_file", "create_file", "start_test_build") && Spend.thisMonth(pr) >= Spend.budget(pr))
            return "He's at his Claude budget for the month, so code changes wait until next month (or until he raises the budget)."
        val repo = Brain.REPO
        val token = Prefs(c).sp.getString("github_token", "").orEmpty()
        fun gh(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
            val u = URL("https://api.github.com/repos/$repo$path").openConnection() as HttpURLConnection
            u.requestMethod = method; u.connectTimeout = 10000; u.readTimeout = 30000
            u.setRequestProperty("Authorization", "Bearer $token")
            u.setRequestProperty("Accept", "application/vnd.github+json")
            u.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) { u.doOutput = true; u.setRequestProperty("Content-Type", "application/json"); u.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = u.responseCode
            val text = (if (code in 200..299) u.inputStream else u.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            u.disconnect(); return code to text
        }
        fun branchExists() = gh("GET", "/git/ref/heads/$BRANCH").first == 200
        fun ensureBranch(): String? {
            if (branchExists()) return null
            val (cd, main) = gh("GET", "/git/ref/heads/main")
            if (cd != 200) return "Couldn't read the main code (GitHub $cd). Check the token can read and write this repo."
            val sha = JSONObject(main).getJSONObject("object").getString("sha")
            val (cd2, _) = gh("POST", "/git/refs", JSONObject().put("ref", "refs/heads/$BRANCH").put("sha", sha))
            return if (cd2 in 200..299) null else "Couldn't start a work branch (GitHub $cd2)."
        }
        fun ref() = if (branchExists()) BRANCH else "main"
        fun getFile(path: String): Pair<String, String>? {
            val (cd, t) = gh("GET", "/contents/$path?ref=${ref()}")
            if (cd != 200) return null
            val j = JSONObject(t)
            return String(Base64.decode(j.optString("content"), Base64.DEFAULT)) to j.optString("sha")
        }
        fun putFile(path: String, text: String, sha: String?, msg: String): String {
            if (OFF_LIMITS.containsMatchIn(path)) return "That file is off limits."
            if (Regex("sk-ant-[A-Za-z0-9_-]{10,}|AIza[0-9A-Za-z_-]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}").containsMatchIn(text)) return "Refused: that would put a key into the code."
            ensureBranch()?.let { return it }
            val body = JSONObject().put("message", "builder: $msg").put("content", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)).put("branch", BRANCH)
            if (sha != null) body.put("sha", sha)
            val (cd, t) = gh("PUT", "/contents/$path", body)
            return if (cd in 200..299) "Saved $path." else "Couldn't save $path (GitHub $cd: ${t.take(120)})."
        }
        return when (name) {
            "list_files" -> {
                val (cd, t) = gh("GET", "/contents/${input.optString("path").trim('/')}?ref=${ref()}")
                if (cd != 200) "Couldn't list that (GitHub $cd)." else {
                    val a = JSONArray(t); (0 until a.length()).joinToString(", ") { a.getJSONObject(it).let { f -> f.optString("name") + if (f.optString("type") == "dir") "/" else "" } }
                }
            }
            "read_file" -> {
                val path = input.optString("path")
                val (text, _) = getFile(path) ?: return "Couldn't read $path."
                val lines = text.lines(); val from = maxOf(1, input.optInt("from_line", 1)); val to = minOf(lines.size, from + 249)
                "$path lines $from-$to of ${lines.size}:\n" + (from..to).joinToString("\n") { "$it: ${lines[it - 1]}" }
            }
            "edit_file" -> {
                val path = input.optString("path"); val old = input.optString("old_text"); val new = input.optString("new_text")
                if (OFF_LIMITS.containsMatchIn(path)) return "That file is off limits."
                val (text, sha) = getFile(path) ?: return "Couldn't read $path."
                val n = text.split(old).size - 1
                if (old.isBlank() || n != 1) "old_text must appear exactly once in $path (found $n times). Read the file and copy it exactly."
                else putFile(path, text.replace(old, new), sha, input.optString("why").ifBlank { "edit $path" })
            }
            "create_file" -> {
                val path = input.optString("path")
                if (getFile(path) != null) "$path already exists: use edit_file." else putFile(path, input.optString("content"), null, "add $path")
            }
            "start_test_build" -> {
                if (!branchExists()) return "There are no code changes to build yet."
                val (cd, t) = gh("POST", "/actions/workflows/build.yml/dispatches", JSONObject().put("ref", BRANCH))
                Prefs(c).sp.edit().putString("builder_summary", input.optString("summary")).putLong("builder_started", System.currentTimeMillis()).apply()
                if (cd in 200..299) "Test build started. It takes about five minutes." else "Couldn't start the build (GitHub $cd: ${t.take(120)})."
            }
            "build_status" -> {
                val (cd, t) = gh("GET", "/actions/runs?branch=$BRANCH&per_page=1")
                if (cd != 200) "Couldn't check the build (GitHub $cd)." else {
                    val runs = JSONObject(t).optJSONArray("workflow_runs")
                    if (runs == null || runs.length() == 0) "No test build yet." else runs.getJSONObject(0).let { r ->
                        when {
                            r.optString("status") != "completed" -> "Test build ${r.optInt("run_number")} is still running."
                            r.optString("conclusion") == "success" -> "Test build ${r.optInt("run_number")} is ready to install. It changes: ${Prefs(c).sp.getString("builder_summary", "")}."
                            else -> "Test build ${r.optInt("run_number")} failed. Read the build errors."
                        }
                    }
                }
            }
            "read_build_errors" -> {
                val (cd, t) = gh("GET", "/contents/build-error.txt?ref=ci-log")
                if (cd != 200) "No build errors saved." else String(Base64.decode(JSONObject(t).optString("content"), Base64.DEFAULT)).lines()
                    .filter { it.startsWith("e: ") || it.contains("error:") }.take(25).joinToString("\n").ifBlank { "The error log is empty." }
            }
            "install_test_build" -> {
                if (!confirmed) return "Not installed: he hasn't said yes yet. Ask him first."
                val (rc, rt) = gh("GET", "/actions/runs?branch=$BRANCH&status=success&per_page=1")
                val run = if (rc == 200) JSONObject(rt).optJSONArray("workflow_runs")?.optJSONObject(0)?.optInt("run_number") ?: 0 else 0
                if (run == 0) return "No finished test build to install."
                val (cd, t) = gh("GET", "/releases/tags/test-$run")
                if (cd != 200) return "Test build $run isn't published yet. Try again in a minute."
                val rel = JSONObject(t)
                val apk = rel.optJSONArray("assets")?.let { as_ -> (0 until as_.length()).map { as_.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } }
                    ?: return "The test build has no app file."
                val ok = Updater.installFrom(c, apk.optString("browser_download_url"))
                if (ok) { Prefs(c).sp.edit().putBoolean("on_test_build", true).apply(); "Installing the test version now. Jarvis restarts in a moment. Say 'keep it' if it's good or 'roll back' if not." }
                else "The install didn't start."
            }
            "keep_test_build" -> {
                if (!confirmed) return "Not kept: he hasn't said yes yet."
                val (cd, t) = gh("POST", "/merges", JSONObject().put("base", "main").put("head", BRANCH).put("commit_message", "builder: keep ${Prefs(c).sp.getString("builder_summary", "test changes")}"))
                if (cd !in 200..299 && cd != 204) "Couldn't make it the real version (GitHub $cd: ${t.take(120)})." else {
                    gh("DELETE", "/git/refs/heads/$BRANCH")
                    Prefs(c).sp.edit().putBoolean("on_test_build", false).apply()
                    "Kept. The change is now part of the real Jarvis; the next normal update includes it."
                }
            }
            "roll_back" -> {
                gh("DELETE", "/git/refs/heads/$BRANCH")
                val (cd, _) = gh("POST", "/actions/workflows/build.yml/dispatches", JSONObject().put("ref", "main"))
                Prefs(c).sp.edit().putBoolean("on_test_build", false).apply()
                if (cd in 200..299) "Rolled back. The test changes are gone and the last good version is rebuilding; it installs itself in about five minutes."
                else "Threw the test changes away, but couldn't start the rebuild (GitHub $cd)."
            }
            else -> "Unknown code skill."
        }
    }
}
