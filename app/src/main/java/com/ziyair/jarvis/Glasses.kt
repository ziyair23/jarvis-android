package com.ziyair.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Jarvis's eyes in his Meta glasses (Meta's Wearables Device Access Toolkit). The camera is never left on: each look
 * opens a session, grabs one photo, and closes everything again, so the glasses' privacy light only blinks for it.
 */
object Glasses {
    @Volatile private var ready = false
    @Volatile var why = ""; private set

    fun enabled(c: Context) = Brain(c).config().optBoolean("glasses_camera", true)

    private fun btOk(c: Context) = Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Start Meta's kit once (needs Bluetooth permission first). Never throws. */
    fun init(c: Context): Boolean {
        if (ready) return true
        if (!btOk(c)) { why = "Jarvis needs Bluetooth permission for the glasses: say \"connect my glasses\" to set it up."; return false }
        return try {
            var ok = true
            Wearables.initialize(c.applicationContext).onFailure { e, _ -> ok = false; why = "Meta's glasses kit didn't start: ${e.description}" }
            ready = ok; ok
        } catch (e: Throwable) { why = "Meta's glasses kit didn't start: ${e.message}"; false }
    }

    /** Linked to Jarvis in the Meta AI app (one-time setup). */
    fun registered(c: Context): Boolean = init(c) && try {
        runBlocking { withTimeoutOrNull(2000) { Wearables.registrationState.first() } } == RegistrationState.REGISTERED
    } catch (_: Throwable) { false }

    /** Glasses are on, linked and nearby. */
    fun available(c: Context): Boolean = enabled(c) && registered(c) && try {
        runBlocking { withTimeoutOrNull(1500) { Wearables.devices.first { it.isNotEmpty() } } } != null
    } catch (_: Throwable) { false }

    /** One photo from the glasses (background thread; ~3-8 s). Null = see [why]. */
    fun capture(c: Context): File? {
        if (!registered(c)) { if (why.isBlank()) why = "The glasses aren't linked to Jarvis yet: say \"connect my glasses\"."; return null }
        why = "The glasses didn't take a photo."
        return try { runBlocking { withTimeoutOrNull(25_000) { grab(c) } } } catch (e: Throwable) { why = "Glasses camera: ${e.message}"; null }
            .also { Trace.log(c, "glasses", if (it != null) "photo taken" else "no photo: $why") }
    }

    private suspend fun grab(c: Context): File? {
        val perm = Wearables.checkPermissionStatus(Permission.CAMERA).fold(onSuccess = { it }, onFailure = { e, _ -> why = e.description; null })
        if (perm != PermissionStatus.Granted) { why = "Jarvis isn't allowed to use the glasses camera yet: say \"connect my glasses\" and tap Allow."; return null }
        val session = Wearables.createSession(AutoDeviceSelector()).fold(onSuccess = { it }, onFailure = { e, _ -> why = "Couldn't reach the glasses: ${e.description}"; null }) ?: return null
        try {
            session.start()
            val st = withTimeoutOrNull(10_000) { session.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
            if (st != DeviceSessionState.STARTED) { why = "The glasses didn't connect (are they on and unfolded?)."; return null }
            val camera = session.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24, compressVideo = true))
                .fold(onSuccess = { it }, onFailure = { e, _ -> why = "Glasses camera: ${e.description}"; null }) ?: return null
            try {
                val stream = camera.stream
                var failed = false
                stream.start().onFailure { e, _ -> failed = true; why = "Glasses camera: ${e.description}" }
                if (failed) return null
                val s = withTimeoutOrNull(10_000) { stream.state.first { it == StreamState.STREAMING || it == StreamState.CLOSED || it == StreamState.STOPPED } }
                if (s != StreamState.STREAMING) { why = "The glasses camera didn't start."; return null }
                delay(400)   // a moment for exposure to settle
                val photo = stream.capturePhoto().fold(onSuccess = { it }, onFailure = { e, _ -> why = "Glasses photo: ${e.description}"; null }) ?: return null
                val bmp = decode(photo) ?: return null
                val f = File(c.cacheDir, "glasses_${System.currentTimeMillis()}.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                return f
            } finally { try { camera.stop() } catch (_: Throwable) {} }
        } finally { try { session.stop() } catch (_: Throwable) {} }
    }

    private fun decode(p: PhotoData): Bitmap? = when (p) {
        is PhotoData.Bitmap -> p.bitmap
        is PhotoData.HEIC -> {
            val buf = p.data.duplicate().apply { rewind() }
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b ->
                val deg = try {
                    when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                    }
                } catch (_: Exception) { 0f }
                if (deg == 0f) b else try { Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true).also { b.recycle() } } catch (_: Throwable) { b }
            }
        }
    }

    /** "Connect my glasses": opens the one-time setup (Bluetooth permission, link in the Meta AI app, camera access). */
    fun setup(c: Context): String {
        c.startActivity(Intent(c, GlassesSetup::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened the glasses setup on his phone: he taps Allow, links Jarvis in the Meta AI app, then allows the camera. Tell him that in one sentence."
    }
}

/** The one-time glasses link: Bluetooth permission, register with the Meta AI app, then camera access. */
class GlassesSetup : ComponentActivity() {
    private val camAsk = registerForActivityResult(Wearables.RequestPermissionContract()) { r ->
        val ok = r.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted
        say(if (ok) "Glasses are linked, sir. Ask me what you're looking at any time." else "The glasses camera wasn't allowed, sir. Say connect my glasses to try again.")
        finish()
    }
    private var asked = false
    private var camAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7) else next()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) next() else { say("Jarvis needs Bluetooth access for the glasses, sir."); finish() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }

    override fun onResume() { super.onResume(); if (asked) next() }

    private fun next() {
        if (!Glasses.init(this)) { say(Glasses.why); finish(); return }
        if (!Glasses.registered(this)) {
            if (asked) return   // still on its way back from the Meta AI app
            asked = true
            try { Wearables.startRegistration(this) } catch (e: Throwable) { say("The Meta AI app didn't open: ${e.message}"); finish() }
            return
        }
        if (camAsked) return
        camAsked = true
        try { camAsk.launch(Permission.CAMERA) } catch (e: Throwable) { say("Couldn't ask for the glasses camera: ${e.message}"); finish() }
    }

    private fun say(t: String) {
        Trace.log(this, "glasses", t)
        try { startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_SAY).putExtra("text", t)) } catch (_: Exception) {}
    }
}
