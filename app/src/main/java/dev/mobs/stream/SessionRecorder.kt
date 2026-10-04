package dev.mobs.stream

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One battery reading. Fields are null where the device does not report them. */
data class BatterySample(
    val percent: Int?,
    val temperatureC: Float?,
    /** Instantaneous current; the sign convention for discharge varies by vendor. */
    val currentMicroAmps: Int?,
    val voltageMillivolts: Int?,
    /** Connected to a charger or USB power; such rows do not measure battery drain. */
    val plugged: Boolean,
)

/**
 * Writes one CSV row per second while a stream is active, one file per session, for the phase-1
 * power and thermal runs. Rows carry no server or stream key. Files are flushed every row so a
 * crash or thermal shutdown keeps the data, and only the newest [keepFiles] sessions are kept.
 */
internal class SessionRecorder(
    private val directory: File,
    private val engine: StreamEngine,
    private val thermal: StateFlow<ThermalState>,
    private val battery: () -> BatterySample,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val elapsedMs: () -> Long,
    private val nowMs: () -> Long,
    private val keepFiles: Int = 20,
) {
    init {
        scope.launch {
            engine.state.map { it.streamActive }.distinctUntilChanged().collectLatest { active ->
                if (active) withContext(io) { record() }
            }
        }
    }

    /** Runs until the session ends; cancellation closes the file. */
    private suspend fun record() {
        directory.mkdirs()
        val start = elapsedMs()
        val file = File(directory, "session-${FILE_TIME.format(Instant.ofEpochMilli(nowMs()))}.csv")
        prune()
        file.bufferedWriter().use { out ->
            out.write(CSV_HEADER)
            out.newLine()
            while (true) {
                out.write(csvRow(nowMs(), elapsedMs() - start, engine.state.value, thermal.value, battery()))
                out.newLine()
                out.flush()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun prune() {
        directory.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.name }
            ?.drop(keepFiles - 1)
            ?.forEach { it.delete() }
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L

        /** Fixed width, so names sort in time order for pruning. */
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        const val CSV_HEADER = "time,elapsed_s,connection,sent_bps,target_bps,output_fps,dropped_frames," +
            "previewing,thermal,headroom,battery_pct,battery_temp_c,current_ua,voltage_mv,plugged"

        fun csvRow(nowMs: Long, elapsedMs: Long, state: StreamEngineState, thermal: ThermalState, battery: BatterySample) =
            listOf(
                Instant.ofEpochMilli(nowMs).toString(),
                String.format(Locale.ROOT, "%.1f", elapsedMs / 1_000.0),
                state.connection.csvName(),
                state.stats.bitrateBps,
                state.stats.targetBitrateBps,
                state.stats.outputFps,
                state.stats.droppedVideoFrames,
                state.previewing,
                thermal.level.name.lowercase(Locale.ROOT),
                thermal.headroom?.let { String.format(Locale.ROOT, "%.3f", it) },
                battery.percent,
                battery.temperatureC?.let { String.format(Locale.ROOT, "%.1f", it) },
                battery.currentMicroAmps,
                battery.voltageMillivolts,
                battery.plugged,
            ).joinToString(",") { it?.toString().orEmpty() }

        /** A fixed word per state: reasons are free text and may contain commas. */
        private fun ConnectionState.csvName() = when (this) {
            ConnectionState.Idle -> "idle"
            is ConnectionState.Connecting -> "connecting"
            ConnectionState.Connected -> "connected"
            is ConnectionState.Reconnecting -> "reconnecting"
            is ConnectionState.Failed -> "failed"
            ConnectionState.Disconnected -> "disconnected"
        }
    }
}

/** Reads the sticky battery broadcast and BatteryManager properties; no receiver stays registered. */
internal class BatteryReader(private val context: Context) {
    private val manager = context.getSystemService(BatteryManager::class.java)

    fun sample(): BatterySample {
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatterySample(
            percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
            temperatureC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            currentMicroAmps = current.takeIf { it != Int.MIN_VALUE && it != 0 },
            voltageMillivolts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        )
    }
}
