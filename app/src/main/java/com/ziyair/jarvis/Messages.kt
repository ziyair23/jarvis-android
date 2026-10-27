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
            last?.getCharSequence("text")?.toString()?.let { text = it }
            last?.getCharSequence("sender")?.toString()?.let { if (it.isNotBlank()) sender = it }
        }
        if (text.isBlank() || Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE).matches(text)) return

        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val m = Msg(sender, text, appName(sbn.packageName), System.currentTimeMillis(), reply)
        Inbox.add(m)

        val prefs = Prefs(this)
        val announce = when (prefs.readMessages) { "always" -> true; "driving" -> DriveMode.on; else -> false }
        if (announce && prefs.running) {
            Inbox.lastAnnounced = m
            val ask = if (reply != null) " Want to reply, sir?" else ""
            try {
                startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_SAY)
                    .putExtra("text", "Message from $sender: $text.$ask").putExtra("listen", reply != null))
            } catch (_: Exception) {}
        }
    }

    private fun appName(pkg: String) = when (pkg) {
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "Messages"
        "com.whatsapp" -> "WhatsApp"; "com.instagram.android" -> "Instagram"; "com.snapchat.android" -> "Snapchat"
        "com.facebook.orca" -> "Messenger"; "org.telegram.messenger" -> "Telegram"
        else -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    }

    companion object {
        val MESSAGING_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp",
            "com.instagram.android", "com.snapchat.android", "com.facebook.orca", "org.telegram.messenger", "com.discord")
    }
}
