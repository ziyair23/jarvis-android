package com.ziyair.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Camera memory. "Jarvis, remember where I parked" snaps a photo (glasses first, phone camera otherwise), keeps it with
 * the place and time, and "where did I park?" brings it back: he says it out loud and the photo pops up on the phone.
 * "Take a picture" saves a glasses photo to his gallery (Pictures/Jarvis).
 */
object Sights {
    private const val KEY = "sights"
    private const val KEEP = 200

    /** A photo for this moment: the glasses when they're on, else the phone's camera. Null = reason in [why]. */
    @Volatile var why = ""; private set
    fun snap(c: Context): Pair<File, Boolean>? {
        if (Glasses.available(c)) { Glasses.capture(c)?.let { return it to true }; why = Glasses.why; return null }
        Eyes.capture(c)?.let { return it to false }
        why = Eyes.why; return null
    }

    fun all(c: Context): JSONArray = try { JSONArray(Prefs(c).sp.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    private fun store(c: Context, e: JSONObject) {
        val a = all(c); a.put(e)
        val out = JSONArray(); for (i in maxOf(0, a.length() - KEEP) until a.length()) out.put(a.get(i))
        Prefs(c).sp.edit().putString(KEY, out.toString()).apply()
    }

    /** "Remember this / where I parked": photo + place + time, saved for later. */
    fun remember(c: Context, note: String): String {
        val (f, glasses) = snap(c) ?: return why
        val uri = toGallery(c, f, "jarvis_memory") ?: return "Took the photo but couldn't save it."
        val e = JSONObject().put("at", System.currentTimeMillis()).put("note", note.ifBlank { "something he looked at" }.take(200))
            .put("uri", uri.toString()).put("glasses", glasses)
        Maps.here(c)?.let { (la, lo) -> e.put("lat", la).put("lng", lo) }
        store(c, e)
        Trace.log(c, "sights", "remembered: ${e.optString("note")}")
        return "Saved (photo${if (e.has("lat")) " and location" else ""}). Confirm in a few words."
    }

    /** Words that matter for matching ("where did I park" ~ "where I parked"). */
    fun words(t: String): Set<String> = t.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ")
        .map { it.removeSuffix("ed").removeSuffix("ing").removeSuffix("s") }.filter { it.length > 2 && it !in STOP }.toSet()
    private val STOP = setOf("the", "where", "did", "what", "was", "that", "this", "remember", "put", "and", "for", "with", "you", "show", "tell", "about")

    /** The best saved sight for a question: shared words first, then the most recent. Pure, for tests. */
    fun best(entries: List<JSONObject>, query: String): JSONObject? {
        if (entries.isEmpty()) return null
        val q = words(query)
        val scored = entries.mapIndexed { i, e -> Triple(e, (words(e.optString("note")) intersect q).size, i) }
        val top = scored.maxWith(compareBy<Triple<JSONObject, Int, Int>>({ it.second }, { it.third }))
        return if (top.second > 0 || q.isEmpty()) top.first else entries.last()
    }

    /** "Where did I park?": the saved photo for Claude to look at, plus where and when; the photo also pops up on his phone. */
    fun recall(c: Context, query: String): Any {
        val a = all(c)
        val list = (0 until a.length()).map { a.getJSONObject(it) }
        val e = best(list, query) ?: return "Nothing saved yet. He can say \"remember this\" or \"remember where I parked\" next time."
        val ago = ago(System.currentTimeMillis() - e.optLong("at"))
        val where = if (e.has("lat")) " Saved at ${"%.5f".format(e.optDouble("lat"))},${"%.5f".format(e.optDouble("lng"))}." else ""
        val uri = Uri.parse(e.optString("uri"))
        show(c, uri, "Jarvis remembered", e.optString("note"))
        val text = "Saved $ago: \"${e.optString("note")}\".$where The photo just popped up on his phone. Describe what the photo shows that answers him (landmarks, level, signs, row numbers)."
        val img = jpeg64(c, uri) ?: return "$text (The photo itself couldn't be read back.)"
        return JSONArray().put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", img)))
    }

    fun ago(ms: Long): String { val m = (ms / 60000).toInt(); return when { m < 2 -> "just now"; m < 60 -> "$m minutes ago"; m < 48 * 60 -> "${m / 60} hours ago"; else -> "${m / 1440} days ago" } }

    /** "Take a picture": glasses photo straight to his gallery. */
    fun photo(c: Context): String {
        if (!Glasses.available(c)) return if (Glasses.registered(c)) "The glasses aren't connected right now." else Glasses.why.ifBlank { "The glasses aren't linked yet: say \"connect my glasses\"." }
        val f = Glasses.capture(c) ?: return Glasses.why
        val uri = toGallery(c, f, "jarvis_photo") ?: return "Couldn't save the photo."
        show(c, uri, "Photo from your glasses", "Saved to Pictures/Jarvis")
        return "Photo saved to his gallery. Say so in a few words."
    }

    private fun toGallery(c: Context, f: File, prefix: String): Uri? = try {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
        }
        c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.also { u ->
            c.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            f.delete()
        }
    } catch (e: Exception) { Trace.log(c, "sights", "save failed: ${e.message}"); null }

    /** A smaller JPEG for looking at (keeps the request light). */
    fun jpeg64(c: Context, uri: Uri, max: Int = 1280): String? = try {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: null
        bytes?.let { shrink(it, max) }
    } catch (_: Exception) { null }

    fun shrink(bytes: ByteArray, max: Int = 1280): String? {
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        val out = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val o = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 80, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    /** The photo on his phone: a notification with the picture; tap opens it full size. */
    fun show(c: Context, uri: Uri, title: String, text: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("sights") == null) nm.createNotificationChannel(NotificationChannel("sights", "Jarvis photos", NotificationManager.IMPORTANCE_HIGH))
            val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
            val open = PendingIntent.getActivity(c, uri.hashCode(), Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, "sights").setSmallIcon(R.drawable.ic_jarvis).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true)
            if (bmp != null) n.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp))
            nm.notify(4000 + (System.currentTimeMillis() % 1000).toInt(), n.build())
        } catch (e: Exception) { Trace.log(c, "sights", "show failed: ${e.message}") }
    }
}
