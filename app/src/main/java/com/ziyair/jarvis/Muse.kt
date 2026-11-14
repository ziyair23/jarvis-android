package com.ziyair.jarvis

import android.content.Context

/**
 * Handing web jobs to Muse (Meta's personal agent, which has its own cloud browser): bookings, appointments, forms,
 * price hunting. Muse lives in WhatsApp as a chat, so Jarvis sends the job into that chat (through the notification's
 * reply button when one is there, no screen needed; otherwise by working WhatsApp on the screen), then reads Muse's
 * answers out loud as they come in. Muse asks its own confirmation before anything sensitive (buying, sending).
 * Jarvis reads the job back and gets his yes before handing it off.
 */
object Muse {
    @Volatile var waitingSince = 0L; private set
    @Volatile var lastJob = ""; private set
    private const val WAIT_MS = 3 * 3600_000L

    fun name(c: Context) = Brain(c).config().optString("muse_name", "Muse")

    /** Is this chat Muse? (Pure, for tests.) */
    fun isMuse(sender: String, app: String, name: String = "Muse"): Boolean =
        app.equals("WhatsApp", true) && sender.trim().split(Regex("[^A-Za-z0-9]+")).any { it.equals(name, true) }

    fun waiting(now: Long = System.currentTimeMillis()) = waitingSince > 0 && now - waitingSince < WAIT_MS

    /** The message Muse gets: the job, plus what Muse needs to know to finish it without a back-and-forth. */
    fun message(job: String): String = job.trim().trimEnd('.') + ". Please do this for me and tell me when it's done (ask me first before paying for anything)."

    fun handoff(c: Context, job: String, confirmed: Boolean): String {
        if (job.isBlank()) return "What should Muse do?"
        if (!confirmed) return "Read the job back to him in one sentence and get a clear yes first, then call ask_muse again with confirmed=true."
        val text = message(job)
        val n = name(c)
        val viaNote = Inbox.recent().firstOrNull { isMuse(it.sender, it.app, n) && it.reply != null }
        val sent = if (viaNote != null && Inbox.reply(c, viaNote, text)) "sent" else {
            val r = Hands.run(c, "Open WhatsApp, open the chat named \"$n\" (Meta's Muse agent; use the search at the top if it isn't near the top of the list), " +
                "type exactly this message: \"$text\" and tap Send. Then go back to the home screen.", true)
            if (Regex("(?i)\\b(sent|done|success)").containsMatchIn(r) && !Regex("(?i)(couldn'?t|can'?t|not found|no chat|failed|locked)").containsMatchIn(r)) "sent" else r
        }
        if (sent != "sent") {
            Trace.log(c, "muse", "handoff failed: $sent")
            return "Couldn't reach Muse: $sent. If he hasn't set Muse up in WhatsApp yet: open the Muse app, sign in, and connect WhatsApp; Muse then shows up as a chat named Muse."
        }
        waitingSince = System.currentTimeMillis(); lastJob = job.take(200)
        Trace.log(c, "muse", "handed off: $job")
        Timeline.add(c, "muse", "Handed to Muse: $job")
        return "Handed to Muse. Tell him in a few words that Muse is on it and you'll read him its answer when it comes in."
    }

    /** A message from Muse while it's working on his job: say it out loud (even when message reading is off). */
    fun onReply(c: Context, sender: String, app: String, text: String): Boolean {
        if (!waiting() || !isMuse(sender, app, name(c))) return false
        waitingSince = System.currentTimeMillis()   // keep listening while it's still going
        Timeline.add(c, "muse", "Muse: ${text.take(300)}")
        return true
    }
}
