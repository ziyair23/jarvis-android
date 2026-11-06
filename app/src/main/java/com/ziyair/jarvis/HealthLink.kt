package com.ziyair.jarvis

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Sleep and steps from Health Connect (whatever his watch, Fitbit, Samsung Health or Google Fit writes there).
 * Read only. Nothing from it is saved to memory; Jarvis just reads it when asked or when planning the day.
 */
object HealthLink {
    val PERMS = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class), HealthPermission.getReadPermission(StepsRecord::class))
    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun available(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE
    private fun client(c: Context) = HealthConnectClient.getOrCreate(c)

    fun canBackground(c: Context): Boolean = try {
        client(c).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Throwable) { false }

    fun wanted(c: Context): Set<String> = if (canBackground(c)) PERMS + BACKGROUND else PERMS

    fun granted(c: Context): Boolean = available(c) && try { runBlocking { client(c).permissionController.getGrantedPermissions().containsAll(PERMS) } } catch (_: Throwable) { false }

    // ---------------- pure (unit-tested) ----------------
    /** 445 minutes -> "7 h 25 min". */
    fun hm(mins: Long): String = if (mins < 60) "$mins min" else "${mins / 60} h" + (if (mins % 60 > 0) " ${mins % 60} min" else "")

    /** Last night's sleep: sessions that ended between 6 PM yesterday and now, joined (naps under 45 min left out). */
    fun nightMinutes(sessions: List<Pair<Instant, Instant>>): Long =
        sessions.map { Duration.between(it.first, it.second).toMinutes() }.filter { it >= 45 }.sum()

    // ---------------- reading ----------------
    /** For Jarvis: last night's sleep and today's and yesterday's steps. Blocking. */
    fun today(c: Context): String {
        if (!available(c)) return "Health Connect isn't on this phone (or needs an update from the Play Store)."
        if (!granted(c)) return "Health Connect isn't allowed yet. Tap \"Allow sleep & steps\" in setup."
        return try {
            runBlocking {
                val hc = client(c); val zone = ZoneId.systemDefault(); val now = Instant.now()
                val today0 = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val yest0 = today0.minus(Duration.ofDays(1))
                suspend fun steps(a: Instant, b: Instant) = hc.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(a, b)))[StepsRecord.COUNT_TOTAL] ?: 0L
                val sToday = steps(today0, now); val sYest = steps(yest0, today0)
                val from = yest0.plus(Duration.ofHours(18))
                val recs = hc.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, now))).records
                val night = nightMinutes(recs.map { it.startTime to it.endTime })
                val bed = recs.filter { Duration.between(it.startTime, it.endTime).toMinutes() >= 45 }.minByOrNull { it.startTime }
                val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a").withZone(zone)
                val sleepTxt = if (night == 0L) "No sleep recorded last night." else
                    "Slept ${hm(night)} last night" + (bed?.let { ", from ${clock.format(it.startTime)} to ${clock.format(recs.maxOf { r -> r.endTime })}" } ?: "") + "."
                "$sleepTxt Steps: ${"%,d".format(sToday)} so far today, ${"%,d".format(sYest)} yesterday."
            }
        } catch (e: SecurityException) { "Health Connect only lets me read while the app is open on this phone. Open Jarvis and ask again."
        } catch (e: Throwable) { Problems.log(c, "health connect: ${e.message}"); "Couldn't read Health Connect: ${e.message}" }
    }
}

/** Asks for Health Connect access; also the page Health Connect opens to show why Jarvis wants it. */
class HealthPermsActivity : ComponentActivity() {
    private lateinit var ask: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ask = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
            val ok = got.containsAll(HealthLink.PERMS)
            android.widget.Toast.makeText(this, if (ok) "Sleep and steps: allowed" else "Not allowed. You can turn it on in Health Connect later.", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
        val why = intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" || intent?.action == Intent.ACTION_VIEW_PERMISSION_USAGE
        if (why) {
            AlertDialog.Builder(this).setTitle("Why Jarvis reads Health Connect")
                .setMessage("Jarvis only reads your sleep and step counts, when you ask (\"how did I sleep?\") or when he plans your day. Nothing is written, shared or sent anywhere except to answer you.")
                .setPositiveButton("OK") { _, _ -> finish() }.setOnDismissListener { finish() }.show()
            return
        }
        if (!HealthLink.available(this)) {
            AlertDialog.Builder(this).setTitle("Health Connect").setMessage("Health Connect isn't set up on this phone. Install or update it from the Play Store, then try again.")
                .setPositiveButton("OK") { _, _ -> finish() }.setOnDismissListener { finish() }.show()
            return
        }
        thread { val want = HealthLink.wanted(this); runOnUiThread { try { ask.launch(want) } catch (_: Exception) { finish() } } }
    }
}
