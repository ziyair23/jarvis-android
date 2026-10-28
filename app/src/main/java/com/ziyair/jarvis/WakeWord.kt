package com.ziyair.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.FloatBuffer

/**
 * "Hey Jarvis" detection, fully on the phone, no account or key.
 * Uses the free openWakeWord models (melspectrogram -> speech embedding -> "hey jarvis" classifier),
 * following the same streaming steps as the openWakeWord Python library:
 * 16 kHz audio in 80 ms chunks (1280 samples); each chunk adds 8 mel frames; every chunk the last
 * 76 mel frames become one 96-value embedding; the last 16 embeddings are scored by the wake model.
 */
class WakeWord(context: Context, private val threshold: Float, private val onWake: () -> Unit) {
    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private val env = OrtEnvironment.getEnvironment()
    private val mel: OrtSession
    private val emb: OrtSession
    private val wake: OrtSession

    @Volatile private var running = false
    private var worker: Thread? = null

    init {
        fun load(name: String): OrtSession =
            env.createSession(context.assets.open(name).use { it.readBytes() }, OrtSession.SessionOptions())
        mel = load("melspectrogram.onnx")
        emb = load("embedding_model.onnx")
        wake = load("hey_jarvis.onnx")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        worker = Thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
            val fx = ArrayList<android.media.audiofx.AudioEffect>()
            try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx.add(it) } } catch (_: Exception) {}
            val chunk = ShortArray(CHUNK)
            val raw = ShortArray(CHUNK + 480)          // this chunk plus 3 hops of context, like the Python code
            val melFrames = ArrayList<FloatArray>()     // rows of 32
            val embeddings = ArrayList<FloatArray>()    // rows of 96
            repeat(76) { melFrames.add(FloatArray(32) { 1f }) }
            var hits = 0
            try {
                rec.startRecording()
                while (running) {
                    var got = 0
                    while (got < CHUNK && running) {
                        val n = rec.read(chunk, got, CHUNK - got)
                        if (n <= 0) { try { Thread.sleep(200) } catch (_: Exception) {}; break }
                        got += n
                    }
                    if (!running || got < CHUNK) continue
                    System.arraycopy(raw, CHUNK, raw, 0, 480)
                    System.arraycopy(chunk, 0, raw, 480, CHUNK)

                    melFrames.addAll(melspec(raw))
                    while (melFrames.size > 97) melFrames.removeAt(0)
                    if (melFrames.size < 76) continue
                    embeddings.add(embed(melFrames.subList(melFrames.size - 76, melFrames.size)))
                    while (embeddings.size > 16) embeddings.removeAt(0)
                    if (embeddings.size < 16) continue

                    val score = score(embeddings)
                    val th = if (audio.isMusicActive) threshold * 0.85f else threshold
                    hits = if (score >= th) hits + 1 else 0
                    if (hits >= 2) {
                        hits = 0
                        embeddings.clear()           // don't fire twice on the same words
                        running = false
                        onWake()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                fx.forEach { try { it.release() } catch (_: Exception) {} }
                rec.release()
            }
        }.also { it.name = "jarvis-wake"; it.start() }
    }

    fun stop() {
        running = false
        try { worker?.join(400) } catch (_: Exception) {}
        worker = null
    }

    fun close() {
        stop()
        try { mel.close(); emb.close(); wake.close() } catch (_: Exception) {}
    }

    private fun melspec(samples: ShortArray): List<FloatArray> {
        val input = FloatBuffer.allocate(samples.size)
        for (s in samples) input.put(s.toFloat())
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, samples.size.toLong())).use { t ->
            mel.run(mapOf(mel.inputNames.first() to t)).use { res ->
                val out = res[0] as OnnxTensor
                val buf = out.floatBuffer
                val total = buf.remaining()
                val rows = total / 32
                val list = ArrayList<FloatArray>(rows)
                for (r in 0 until rows) list.add(FloatArray(32) { buf.get() / 10f + 2f })
                return list
            }
        }
    }

    private fun embed(frames: List<FloatArray>): FloatArray {
        val input = FloatBuffer.allocate(76 * 32)
        for (f in frames) input.put(f)
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 76, 32, 1)).use { t ->
            emb.run(mapOf(emb.inputNames.first() to t)).use { res ->
                val buf = (res[0] as OnnxTensor).floatBuffer
                return FloatArray(96) { buf.get() }
            }
        }
    }

    private fun score(embeddings: List<FloatArray>): Float {
        val input = FloatBuffer.allocate(16 * 96)
        for (e in embeddings) input.put(e)
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 16, 96)).use { t ->
            wake.run(mapOf(wake.inputNames.first() to t)).use { res ->
                return (res[0] as OnnxTensor).floatBuffer.get()
            }
        }
    }

    companion object {
        const val RATE = 16000
        const val CHUNK = 1280
    }
}
