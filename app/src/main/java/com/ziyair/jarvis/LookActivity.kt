package com.ziyair.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Jarvis's eyes: opens the back camera, holds for a moment to focus, takes one photo, and closes. */
object Eyes {
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var photo: File? = null
    @Volatile var why = ""

    /** Blocks (background thread) until the photo is taken, or ~20 s. */
    fun capture(context: Context): File? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { why = "Camera isn't allowed: he needs to tap Allow calls, texts, calendar & camera in Jarvis setup."; return null }
        if (!Settings.canDrawOverlays(context)) { why = Actions.NEED_OPEN_APPS; return null }
        photo = null; why = "The camera didn't take a photo."
        val l = CountDownLatch(1); latch = l
        context.startActivity(Intent(context, LookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        l.await(20, TimeUnit.SECONDS)
        return photo
    }

    fun deliver(f: File?) { photo = f; latch?.countDown() }
}

class LookActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("jarvis-eyes").apply { start() }
    private val bg = Handler(bgThread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rotation = 90
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        val tex = TextureView(this)
        root.addView(tex)
        root.addView(TextView(this).apply {
            text = "Hold steady, sir…"; textSize = 18f; setTextColor(0xFFEFE6D6.toInt()); setBackgroundColor(0x99000000.toInt())
            setPadding(40, 24, 40, 24); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        ui.postDelayed({ finishWith(null) }, 15_000)
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { open(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun open(st: SurfaceTexture) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { finishWith(null); return }
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.first()
            val ch = cm.getCameraCharacteristics(id)
            rotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val jpegs = map.getOutputSizes(ImageFormat.JPEG)
            val pick = jpegs.filter { it.width.toLong() * it.height <= 2_100_000 }.maxByOrNull { it.width.toLong() * it.height } ?: jpegs.minByOrNull { it.width.toLong() * it.height }!!
            val prev = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
            prev?.let { st.setDefaultBufferSize(it.width, it.height) }
            val r = ImageReader.newInstance(pick.width, pick.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()
                save(bytes)
            }, bg)
            reader = r
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { camera = d; start(d, Surface(st), r) }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, error: Int) { d.close(); finishWith(null) }
            }, bg)
        } catch (_: Exception) { finishWith(null) }
    }

    @Suppress("DEPRECATION")
    private fun start(d: CameraDevice, preview: Surface, r: ImageReader) {
        try {
            d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        s.setRepeatingRequest(req.build(), null, bg)
                        bg.postDelayed({ shoot(d, s, r) }, 1800)
                    } catch (_: Exception) { finishWith(null) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finishWith(null) }
            }, bg)
        } catch (_: Exception) { finishWith(null) }
    }

    private fun shoot(d: CameraDevice, s: CameraCaptureSession, r: ImageReader) {
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }
            s.capture(req.build(), null, bg)
        } catch (_: Exception) { finishWith(null) }
    }

    /** Shrink to a sensible size (long side ~1500px), turn it upright, save. */
    private fun save(bytes: ByteArray) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1500) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            val f = File(cacheDir, "jarvis-look.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            finishWith(f)
        } catch (_: Exception) { finishWith(null) }
    }

    private fun finishWith(f: File?) {
        if (done) return
        done = true
        Eyes.deliver(f)
        ui.post { finish() }
    }

    override fun onDestroy() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        bgThread.quitSafely()
        if (!done) { done = true; Eyes.deliver(null) }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
