package com.ziyair.jarvis

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Jarvis's voice through the Google Nest Hub (or any Google Cast speaker or speaker group) on the home Wi-Fi.
 * Finds speakers on the network, serves the spoken audio from the phone, and tells the speaker to play it,
 * the same way casting music works. No Google account, key or extra app needed.
 */
object Cast {
    data class Speaker(val name: String, val host: String, val port: Int, val group: Boolean)

    private val found = ConcurrentHashMap<String, Speaker>()
    @Volatile private var discovering = false
    private var nsd: NsdManager? = null

    fun mode(p: Prefs) = p.sp.getString("cast_mode", "announcements") ?: "announcements"   // announcements | everything | off
    fun setMode(p: Prefs, m: String) = p.sp.edit().putString("cast_mode", m).apply()
    fun speakers(): List<Speaker> = found.values.sortedWith(compareBy({ !it.group }, { it.name }))

    /** Keep looking for Cast speakers on Wi-Fi in the background. */
    fun discover(context: Context) {
        if (discovering) return
        val m = context.getSystemService(NsdManager::class.java) ?: return
        nsd = m
        discovering = true
        try {
            m.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) { discovering = false }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) { found.remove(info.serviceName) }
                override fun onServiceFound(info: NsdServiceInfo) { resolve(m, info) }
            })
        } catch (_: Exception) { discovering = false }
    }

    @Suppress("DEPRECATION")
    private fun resolve(m: NsdManager, info: NsdServiceInfo) {
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ resolve(m, info) }, 1500)
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress ?: return
                    val attrs = i.attributes
                    val name = attrs["fn"]?.let { String(it) } ?: i.serviceName
                    val model = attrs["md"]?.let { String(it) } ?: ""
                    found[i.serviceName] = Speaker(name, host, i.port, model.contains("Group", true))
                }
            })
        } catch (_: Exception) {}
    }

    /** His chosen speaker, else a speaker group (whole house), else the first one found (the Hub). */
    fun target(p: Prefs): Speaker? {
        val want = p.sp.getString("cast_speaker", "")?.trim().orEmpty()
        val all = speakers()
        if (want.isNotBlank()) all.firstOrNull { it.name.contains(want, true) }?.let { return it }
        return all.firstOrNull()
    }

    // ---- tiny web server so the speaker can fetch the audio from the phone ----
    private var server: ServerSocket? = null
    @Volatile private var clip: File? = null
    @Volatile private var clipType = "audio/wav"

    private fun serve(f: File, type: String): Int {
        clip = f; clipType = type
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0); server = s
        thread(name = "jarvis-cast-http", isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val sock = s.accept()
                    thread(isDaemon = true) {
                        sock.use { c ->
                            c.soTimeout = 10000
                            val reader = c.getInputStream().bufferedReader()
                            val first = reader.readLine() ?: return@use
                            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                            val file = clip
                            val out = c.getOutputStream()
                            if (file == null || !file.exists() || !first.startsWith("GET")) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use
                            }
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: $clipType\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n".toByteArray())
                            file.inputStream().use { it.copyTo(out) }
                            out.flush()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return s.localPort
    }

    private fun phoneIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } ?: return null
        val lp: LinkProperties = cm.getLinkProperties(net) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** 16-bit WAV from Piper's float samples. */
    fun wav(context: Context, samples: FloatArray, rate: Int): File {
        val f = File(context.cacheDir, "jarvis-cast-${System.currentTimeMillis() % 100000}.wav")
        context.cacheDir.listFiles { x -> x.name.startsWith("jarvis-cast-") && x != f }?.forEach { it.delete() }
        val data = samples.size * 2
        DataOutputStream(f.outputStream().buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
            fun le16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
            o.writeBytes("RIFF"); le32(36 + data); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(data)
            for (s in samples) le16((s.coerceIn(-1f, 1f) * 32767).toInt())
        }
        return f
    }

    /** Play an audio file on the speaker. Blocking (run off the main thread). Returns true once it's playing. */
    /** Blocks until the speaker has finished saying it. */
    fun play(context: Context, f: File, type: String, maxMs: Long): Boolean {
        val sp = target(Prefs(context)) ?: return false
        val ip = phoneIp(context) ?: return false
        val port = serve(f, type)
        val url = "http://$ip:$port/${f.name}"
        return try { castLoad(sp, url, type, maxMs) } catch (_: Exception) { false }
    }

    // ---- Google Cast protocol (CastV2): length-prefixed protobuf messages over TLS on port 8009 ----
    private fun castLoad(sp: Speaker, url: String, type: String, maxMs: Long): Boolean {
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf<TrustManager>(object : X509TrustManager {  // the speaker uses a self-signed certificate on the home network
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(sp.host, sp.port), 4000)
        val s = tls.socketFactory.createSocket(raw, sp.host, sp.port, true) as SSLSocket
        s.soTimeout = 8000
        s.use { sock ->
            val out = DataOutputStream(sock.getOutputStream())
            val inp = DataInputStream(sock.getInputStream())
            val me = "sender-jarvis"
            fun send(dest: String, ns: String, payload: JSONObject) = out.write(frame(me, dest, ns, payload.toString())).also { out.flush() }
            fun recv(): Pair<String, String>? {
                val len = inp.readInt(); if (len <= 0 || len > 1_000_000) return null
                val b = ByteArray(len); inp.readFully(b)
                return parse(b)
            }
            send("receiver-0", NS_CONN, JSONObject().put("type", "CONNECT"))
            send("receiver-0", NS_RECV, JSONObject().put("type", "LAUNCH").put("appId", "CC1AD845").put("requestId", 1))
            // wait for the media player app to start and tell us where it is
            var transport: String? = null
            var session: String? = null
            val deadline = System.currentTimeMillis() + 9000
            while (transport == null && System.currentTimeMillis() < deadline) {
                val (ns, body) = (try { recv() } catch (_: java.net.SocketTimeoutException) { null }) ?: continue
                if (ns == NS_HEART) { send("receiver-0", NS_HEART, JSONObject().put("type", "PONG")); continue }
                if (ns != NS_RECV) continue
                val apps = JSONObject(body).optJSONObject("status")?.optJSONArray("applications") ?: continue
                for (i in 0 until apps.length()) { val a = apps.getJSONObject(i); if (a.optString("appId") == "CC1AD845") { transport = a.optString("transportId"); session = a.optString("sessionId") } }
            }
            val t = transport ?: return false
            send(t, NS_CONN, JSONObject().put("type", "CONNECT"))
            send(t, NS_MEDIA, JSONObject().put("type", "LOAD").put("requestId", 2).put("autoplay", true)
                .put("media", JSONObject().put("contentId", url).put("contentType", type).put("streamType", "BUFFERED")
                    .put("metadata", JSONObject().put("metadataType", 0).put("title", "Jarvis"))))
            // wait until it starts, then until it finishes, then close the player so the Hub goes back to normal
            var started = false
            val until = System.currentTimeMillis() + 8000 + maxMs
            while (System.currentTimeMillis() < until) {
                val (ns, body) = (try { recv() } catch (_: java.net.SocketTimeoutException) { null }) ?: continue
                if (ns == NS_HEART) { send("receiver-0", NS_HEART, JSONObject().put("type", "PONG")); continue }
                if (ns != NS_MEDIA) continue
                if (body.contains("LOAD_FAILED") || body.contains("LOAD_CANCELLED")) return false
                if (body.contains("\"PLAYING\"") || body.contains("\"BUFFERING\"")) started = true
                if (started && body.contains("\"IDLE\"")) break
            }
            try { session?.let { send("receiver-0", NS_RECV, JSONObject().put("type", "STOP").put("sessionId", it).put("requestId", 3)) } } catch (_: Exception) {}
            return started
        }
    }

    private const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    // CastMessage protobuf: 1 protocol_version=0, 2 source_id, 3 destination_id, 4 namespace, 5 payload_type=0 (string), 6 payload_utf8
    private fun frame(src: String, dst: String, ns: String, payload: String): ByteArray {
        val b = ByteArrayOutputStream()
        fun varint(v0: Long) { var v = v0; while (v >= 0x80) { b.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; b.write(v.toInt()) }
        fun str(field: Int, s: String) { val d = s.toByteArray(); varint(((field shl 3) or 2).toLong()); varint(d.size.toLong()); b.write(d) }
        varint((1 shl 3).toLong()); varint(0)
        str(2, src); str(3, dst); str(4, ns)
        varint((5 shl 3).toLong()); varint(0)
        str(6, payload)
        val body = b.toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(body.size); write(body) }
        return out.toByteArray()
    }

    /** Returns (namespace, payload) from a CastMessage. */
    private fun parse(b: ByteArray): Pair<String, String> {
        var i = 0; var ns = ""; var payload = ""
        fun varint(): Long { var r = 0L; var shift = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl shift); if (x < 0x80) return r; shift += 7 } }
        while (i < b.size) {
            val key = varint().toInt(); val field = key shr 3; val wire = key and 7
            when (wire) {
                0 -> varint()
                2 -> { val len = varint().toInt(); val s = String(b, i, len); i += len; if (field == 4) ns = s; if (field == 6) payload = s }
                else -> return ns to payload
            }
        }
        return ns to payload
    }
}
