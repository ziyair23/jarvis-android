package com.ziyair.jarvis

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build

/**
 * The glasses' mic. When his smart glasses (Ray-Ban / Oakley Meta) are on and connected, the wake word and the ear
 * listen through the glasses instead of the phone, so the phone can stay in his pocket. When the glasses go away the
 * phone's own mic takes over again, the same as before. While music is playing it keeps music quality and listens on
 * the phone (a Bluetooth mic link turns music into phone-call sound).
 */
object GlassesMic {
    @Volatile var linked = false; private set
    @Volatile private var musicTicks = 0

    fun nameRe(c: Context) = Regex(Brain(c).config().optString("glasses_name", "ray-?ban|oakley|meta"), RegexOption.IGNORE_CASE)

    /** Is this a pair of glasses? (Pure: name only, so a car or earbuds never take over the mic.) */
    fun isGlasses(name: CharSequence?, re: Regex): Boolean = !name.isNullOrBlank() && re.containsMatchIn(name)

    fun enabled(c: Context) = Brain(c).config().optBoolean("glasses_mic", true)

    private fun bt(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /** The glasses' mic, if they're on and connected. */
    fun input(c: Context): AudioDeviceInfo? {
        if (!enabled(c)) return null
        val am = c.getSystemService(AudioManager::class.java) ?: return null
        val re = nameRe(c)
        return try { am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { bt(it.type) && isGlasses(it.productName, re) } } catch (_: Exception) { null }
    }

    /** Point a freshly made mic at the glasses when they're on; otherwise leave it on the phone. Never throws. */
    fun use(c: Context, rec: AudioRecord) {
        val dev = try { link(c) } catch (_: Exception) { null }
        try { rec.preferredDevice = dev } catch (_: Exception) {}
    }

    /** Opens the Bluetooth mic link to the glasses. Null = listen on the phone. */
    @Suppress("DEPRECATION")
    fun link(c: Context): AudioDeviceInfo? {
        val dev = input(c)
        val am = c.getSystemService(AudioManager::class.java) ?: return null
        if (dev == null) { unlink(c, "glasses off"); return null }
        if (!linked && am.isMusicActive) return null                  // music playing: keep it sounding good, phone mic listens
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) return dev  // live talking already has the glasses
        if (!linked) {
            if (Build.VERSION.SDK_INT >= 31) {
                val re = nameRe(c)
                val out = am.availableCommunicationDevices.firstOrNull { bt(it.type) && isGlasses(it.productName, re) } ?: return null
                if (!am.setCommunicationDevice(out)) return null
            } else { am.startBluetoothSco(); am.isBluetoothScoOn = true }
            linked = true; musicTicks = 0
            Trace.log(c, "glasses", "listening through ${dev.productName}")
        }
        return dev
    }

    /** Live talking cleared the Bluetooth link itself; open it again next time. */
    fun forget() { linked = false }

    @Suppress("DEPRECATION")
    fun unlink(c: Context, why: String) {
        if (!linked) return
        linked = false
        try {
            val am = c.getSystemService(AudioManager::class.java)
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
            }
        } catch (_: Exception) {}
        Trace.log(c, "glasses", "back to the phone mic: $why")
    }

    /** Every minute: glasses gone, or music has been playing a while, means let go of the glasses' mic. */
    fun tick(c: Context) {
        if (!linked) return
        if (input(c) == null) { unlink(c, "glasses off"); return }
        val music = try { c.getSystemService(AudioManager::class.java).isMusicActive } catch (_: Exception) { false }
        musicTicks = if (music) musicTicks + 1 else 0
        if (musicTicks >= 2) unlink(c, "music playing")
    }
}
