package com.ziyair.jarvis

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One incoming message, kept in memory so Jarvis can read it out and reply through the app's own reply button. */
data class Msg(val sender: String, val text: String, val app: String, val at: Long, val reply: Notification.Action?)

object Inbox {
    private val items = ArrayList<Msg>()
    var lastAnnounced: Msg? = null

    @Synchronized fun add(m: Msg) {
        items.removeAll { it.sender == m.sender && it.text == m.text }
        items.add(m)
        while (items.size > 25) items.removeAt(0)
    }

    @Synchronized fun recent(): List<Msg> = items.filter { System.currentTimeMillis() - it.at < 3 * 3600_000L }.reversed()

    @Synchronized fun find(who: String): Msg? {
        val w = who.trim().lowercase()
        if (w.isBlank() || w in setOf("them", "him", "her", "last", "that", "it", "back")) return lastAnnounced ?: items.lastOrNull()
        return items.lastOrNull { it.sender.lowercase().contains(w) } ?: items.lastOrNull { w.contains(it.sender.lowercase()) }
    }

    /** Only an actual name match (no "her/them" guessing), so a text never goes to the wrong person. */
    @Synchronized fun findByName(who: String): Msg? {
        val w = who.trim().lowercase()
        if (w.length < 2) return null
        return items.lastOrNull { it.sender.lowercase() == w } ?: items.lastOrNull { it.sender.lowercase().split(" ").contains(w) }
    }

    /** True if this exact message was already read out in the last two minutes (apps re-post the same notification). */
    private var lastSaid = ""; private var lastSaidAt = 0L
    @Synchronized fun alreadySaid(m: Msg): Boolean {
        val k = m.sender + "|" + m.text
        val dup = k == lastSaid && m.at - lastSaidAt < 120_000
        lastSaid = k; lastSaidAt = m.at
        return dup
    }

    /** Reply using the notification's own reply action (works for Messages, Samsung Messages, WhatsApp and most chat apps). */
    fun reply(context: Context, m: Msg, text: String): Boolean {
        val action = m.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: Exception) { false }
    }
}

/** Driving state: set when the car's Bluetooth connects, or by voice. */
object DriveMode {
    @Volatile var on = false
}

class MessageListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_CALL || (sbn.packageName in DIALERS && n.fullScreenIntent != null)) { announceCall(n); return }
        val isMsg = n.category == Notification.CATEGORY_MESSAGE || sbn.packageName in MESSAGING_APPS
        if (!isMsg) return
        val ex = n.extras ?: return

        var sender = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        // MessagingStyle: use the newest message and its sender
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgs.isNullOrEmpty()) {
            val last = msgs.last() as? Bundle
            // no sender (and no sender person) means it's HIS own message, e.g. a reply Jarvis just sent: don't read it back
            if (last != null && last.getCharSequence("sender") == null && !last.containsKey("sender_person")) return
            last?.getCharSequence("text")?.toString()?.let { text = it }
            last?.getCharSequence("sender")?.toString()?.let { if (it.isNotBlank()) sender = it }
        }
        if (text.isBlank() || Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE).matches(text)) return

        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val m = Msg(sender, text, appName(sbn.packageName), System.currentTimeMillis(), reply)
        Inbox.add(m)

        val prefs = Prefs(this)
        val announce = when (prefs.readMessages) { "always" -> true; "driving" -> DriveMode.on; else -> false }
        if (announce && prefs.running && !Inbox.alreadySaid(m)) {
            Inbox.lastAnnounced = m
            val ask = if (reply != null) " Want to reply, sir?" else ""
            try {
                startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_SAY)
                    .putExtra("text", "Message from $sender: $text.$ask").putExtra("listen", reply != null))
            } catch (_: Exception) {}
        }
    }

    private var lastCaller = ""; private var lastCallAt = 0L

    /** "Abbey is calling, sir." Only for a ringing call (it has an Answer button), once per call. */
    private fun announceCall(n: Notification) {
        val prefs = Prefs(this)
        if (!prefs.running || !prefs.announceCalls) { Problems.log(this, "call ringing but not announced: " + if (!prefs.running) "Jarvis wasn't running" else "call announcements are switched off"); return }
        val labels = n.actions?.map { it.title?.toString()?.lowercase().orEmpty() }.orEmpty()
        val canAnswer = labels.any { it.contains("answer") || it.contains("accept") }
        if (!canAnswer && n.fullScreenIntent == null) return
        if (!canAnswer && labels.any { it.contains("hang up") || it.contains("end") }) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        var who = if (Regex("incoming|call", RegexOption.IGNORE_CASE).containsMatchIn(title) && text.isNotBlank()) text else title
        who = who.replace(Regex("(?i)incoming (voice |video )?call( from)?"), "").trim()
        if (who.isBlank()) { Problems.log(this, "call ringing but couldn't read who's calling"); return }
        if (Stealth.on(prefs)) Problems.log(this, "call from $who not said out loud: quiet mode was on")
        val now = System.currentTimeMillis()
        if (who == lastCaller && now - lastCallAt < 60_000) return
        lastCaller = who; lastCallAt = now
        val digits = who.replace(Regex("[^0-9]"), "")
        CallControl.ringing(n.actions?.firstOrNull { a -> a.title?.toString()?.lowercase()?.let { it.contains("answer") || it.contains("accept") } == true },
            n.actions?.firstOrNull { a -> a.title?.toString()?.lowercase()?.let { it.contains("decline") || it.contains("reject") || it.contains("dismiss") } == true })
        val unknown = digits.length >= 7 && digits.length >= who.length - 4
        val spammy = Regex("unknown|private|spam|scam|no caller", RegexOption.IGNORE_CASE).containsMatchIn(who)
        // unknown numbers: offer to decline; known people: offer to answer while driving
        val (line, ask) = when {
            unknown -> "Unknown number calling, sir. Probably spam. Decline it?" to true
            spammy -> "${who.replaceFirstChar { it.uppercase() }} calling, sir. Probably spam. Decline it?" to true
            DriveMode.on -> "$who is calling, sir. Answer it?" to true
            else -> "$who is calling, sir." to false
        }
        try { startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", line).putExtra("ringing", true).putExtra("listen", ask)) } catch (_: Exception) {}
    }

    private fun appName(pkg: String) = when (pkg) {
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "Messages"
        "com.whatsapp" -> "WhatsApp"; "com.instagram.android" -> "Instagram"; "com.snapchat.android" -> "Snapchat"
        "com.facebook.orca" -> "Messenger"; "org.telegram.messenger" -> "Telegram"
        else -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    }

    companion object {
        val DIALERS = setOf("com.google.android.dialer", "com.samsung.android.incallui", "com.samsung.android.dialer",
            "com.android.server.telecom", "com.android.dialer", "com.android.incallui")
        val MESSAGING_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp",
            "com.instagram.android", "com.snapchat.android", "com.facebook.orca", "org.telegram.messenger", "com.discord")
    }
}
