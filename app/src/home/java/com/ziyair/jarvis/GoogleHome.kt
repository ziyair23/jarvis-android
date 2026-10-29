package com.ziyair.jarvis

import android.content.Context
import com.google.home.DeviceType
import com.google.home.DeviceTypeFactory
import com.google.home.FactoryRegistry
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsState
import com.google.home.Trait
import com.google.home.TraitFactory
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Every light and plug in his Google Home, through Google's Home APIs (he connects it once in Jarvis setup). */
object GoogleHome {
    const val AVAILABLE = true
    @Volatile private var client: HomeClient? = null
    @Volatile private var grantedAt = 0L
    @Volatile private var granted = false

    @Suppress("UNCHECKED_CAST")
    fun client(context: Context): HomeClient = client ?: synchronized(this) {
        client ?: Home.getClient(context.applicationContext, HomeConfig(
            coroutineContext = Dispatchers.IO,
            factoryRegistry = FactoryRegistry(
                traits = listOf(OnOff, LevelControl) as List<TraitFactory<Trait>>,
                types = listOf(OnOffLightDevice, DimmableLightDevice, ColorTemperatureLightDevice, ExtendedColorLightDevice, OnOffPluginUnitDevice) as List<DeviceTypeFactory<DeviceType>>,
            ),
        )).also { client = it }
    }

    /** Has he connected Google Home? (checked at most every few minutes) */
    fun connected(context: Context): Boolean {
        if (System.currentTimeMillis() - grantedAt < 5 * 60_000L) return granted
        granted = try { runBlocking { withTimeoutOrNull(6000) { client(context).hasPermissions().first() } } == PermissionsState.GRANTED } catch (_: Throwable) { false }
        grantedAt = System.currentTimeMillis()
        return granted
    }
    fun forget() { grantedAt = 0L }
    fun connectedCached() = granted

    private suspend fun kind(d: HomeDevice): DeviceType? = when {
        d.has(ExtendedColorLightDevice) -> d.type(ExtendedColorLightDevice).first()
        d.has(ColorTemperatureLightDevice) -> d.type(ColorTemperatureLightDevice).first()
        d.has(DimmableLightDevice) -> d.type(DimmableLightDevice).first()
        d.has(OnOffLightDevice) -> d.type(OnOffLightDevice).first()
        d.has(OnOffPluginUnitDevice) -> d.type(OnOffPluginUnitDevice).first()
        else -> null
    }
    private fun isPlug(d: HomeDevice) = d.has(OnOffPluginUnitDevice) && !d.has(OnOffLightDevice) && !d.has(DimmableLightDevice)

    /** which: a light/plug name, a room name, or "all" (all lights, not plugs). on: true/false/null. level: 1-100 or null. */
    fun control(context: Context, which: String?, on: Boolean?, level: Int?): String = try {
        runBlocking {
            withTimeoutOrNull(20_000) {
                val all = client(context).devices().list().filter { kind(it) != null }
                if (all.isEmpty()) return@withTimeoutOrNull "No lights or plugs found in his Google Home."
                val w = which?.trim()?.lowercase().orEmpty().removePrefix("the ")
                val everything = w.isBlank() || w in setOf("all", "lights", "all lights", "all the lights", "everything", "house")
                var targets = if (everything) all.filter { !isPlug(it) } else all.filter { it.name.lowercase().contains(w) || w.contains(it.name.lowercase()) }
                if (targets.isEmpty() && !everything) targets = all.filter { (it.room()?.name?.lowercase() ?: "").let { r -> r.isNotBlank() && (r.contains(w) || w.contains(r)) } }
                if (targets.isEmpty()) return@withTimeoutOrNull "Nothing called $which in his Google Home. He has: ${all.joinToString { it.name }}."
                var done = 0
                for (d in targets) {
                    val t = kind(d) ?: continue
                    try {
                        when {
                            on == false -> t.trait(OnOff)?.off()
                            level != null && t.trait(LevelControl) != null -> t.trait(LevelControl)!!.moveToLevelWithOnOff(
                                (level.coerceIn(1, 100) * 254 / 100).coerceIn(1, 254).toUByte(), null,
                                LevelControlTrait.OptionsBitmap(false, false), LevelControlTrait.OptionsBitmap(false, false))
                            else -> t.trait(OnOff)?.on()
                        }
                        done++
                    } catch (e: Throwable) { Problems.log(context, "google home ${d.name}: ${e.message}") }
                }
                val names = targets.joinToString { it.name }
                if (done == targets.size) "Done: $names." else if (done > 0) "Done for $done of ${targets.size} ($names)." else "Google Home didn't respond for $names."
            } ?: "Google Home took too long to answer."
        }
    } catch (e: Throwable) { Problems.log(context, "google home: ${e.message}"); "Google Home didn't respond: ${e.message}" }

    fun list(context: Context): String = try {
        runBlocking {
            withTimeoutOrNull(15_000) {
                val all = client(context).devices().list().filter { kind(it) != null }
                if (all.isEmpty()) "No lights or plugs found." else all.joinToString { d -> d.name + (d.room()?.name?.let { " ($it)" } ?: "") + if (isPlug(d)) " [plug]" else "" }
            } ?: "Google Home took too long to answer."
        }
    } catch (e: Throwable) { "Google Home didn't respond: ${e.message}" }
}
