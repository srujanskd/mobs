package dev.mobs.stream

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
    /**
     * BatteryManager's instantaneous current. Documented as microamps, but some vendors report
     * milliamps, and the sign for discharge varies; compare only within one device.
     */
    val currentNow: Int?,
    val voltageMillivolts: Int?,
    /** Connected to a charger or USB power; such rows do not measure battery drain. */
    val plugged: Boolean,
)

/**
 * Writes one CSV row per second while a stream is active, one file per session, for the phase-1
 * power and thermal runs. Rows carry no server or stream key. Files are flushed every row so a
 * crash or thermal shutdown keeps the data, and only the newest [keepFiles] sessions are kept.
 *
 * With a [logCapture], the session's first [logWindowMs] of app log is saved beside the CSV, for
 * diagnosing startup problems on phones that cannot be attached to a debugger.
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
    private val logCapture: LogCapture? = null,
    private val logWindowMs: Long = 60_000,
    private val memory: () -> MemorySample = { MemorySample() },
    private val onFailure: (String) -> Unit = {},
) {
    init {
        scope.launch {
            engine.state.map { it.streamActive }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    try { withContext(io) { record() } }
                    catch (_: java.io.IOException) { onFailure("Session recording failed. Check available storage.") }
                    catch (_: SecurityException) { onFailure("Session recording storage is unavailable.") }
                }
            }
        }
    }

    /** Runs until the session ends; cancellation closes the file. */
    private suspend fun record() = coroutineScope {
        directory.mkdirs()
        val start = elapsedMs()
        val name = "session-${FILE_TIME.format(Instant.ofEpochMilli(nowMs()))}"
        prune()
        // Starts a little before this coroutine ran, so lines from going live are included.
        val log = logCapture?.start(File(directory, "$name.log"), nowMs() - LOG_LEAD_MS)
        val logTimer = log?.let { launch { delay(logWindowMs); it.close() } }
        try {
            writeCsv(File(directory, "$name.csv"), start)
        } finally {
            logTimer?.cancel()
            log?.close()
        }
    }

    private suspend fun writeCsv(file: File, start: Long) {
        file.bufferedWriter().use { out ->
            out.write(CSV_HEADER)
            out.newLine()
            try {
                while (true) {
                    out.write(csvRow(nowMs(), elapsedMs() - start, engine.state.value, thermal.value, battery(), memory()))
                    out.newLine()
                    out.flush()
                    delay(SAMPLE_INTERVAL_MS)
                }
            } finally {
                // Missing this marker means a crash, truncated export, or failed recorder.
                val state = engine.state.value
                out.write(csvRow(nowMs(), elapsedMs() - start, state, thermal.value, battery(), memory(),
                    if (state.streamActive) "aborted" else "end"))
                out.newLine()
                out.flush()
            }
        }
    }

    /** Keeps the newest sessions; a session's CSV and log share a name stem and go together. */
    private fun prune() {
        val files = directory.listFiles { f -> f.name.startsWith("session-") }.orEmpty()
        val stale = files.map { it.nameWithoutExtension }.distinct().sortedDescending().drop(keepFiles - 1).toSet()
        files.filter { it.nameWithoutExtension in stale }.forEach { it.delete() }
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        private const val LOG_LEAD_MS = 2_000L

        /** Fixed width, so names sort in time order for pruning. */
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        const val CSV_HEADER = "time,elapsed_s,connection,sent_bps,target_bps,output_fps,dropped_frames," +
            "previewing,thermal,headroom,battery_pct,battery_temp_c,current_now,voltage_mv,plugged," +
            "width,height,profile_fps,source_count,rear_camera,front_camera,scene_error,java_heap_bytes,native_heap_bytes,rss_kb,sample_kind,rear_capture_fps,front_capture_fps"

        /** Files of the newest [sessions] sessions, newest first; each has a CSV and maybe a log. */
        fun newestSessionFiles(directory: File, sessions: Int): List<File> {
            val files = directory.listFiles { f -> f.name.startsWith("session-") }.orEmpty()
            val keep = files.map { it.nameWithoutExtension }.distinct().sortedDescending().take(sessions).toSet()
            return files.filter { it.nameWithoutExtension in keep }.sortedByDescending { it.name }
        }

        fun csvRow(nowMs: Long, elapsedMs: Long, state: StreamEngineState, thermal: ThermalState, battery: BatterySample, memory: MemorySample = MemorySample(), sampleKind: String = "sample") =
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
                battery.currentNow,
                battery.voltageMillivolts,
                battery.plugged,
                state.video.width, state.video.height, state.video.fps,
                state.scene?.sources?.count { it.visible && it.opacity > 0f } ?: 1,
                state.scene?.cameras?.contains(dev.mobs.scene.SourceKind.REAR_CAMERA) ?: (state.cameraFacing == CameraFacing.REAR),
                state.scene?.cameras?.contains(dev.mobs.scene.SourceKind.FRONT_CAMERA) ?: (state.cameraFacing == CameraFacing.FRONT),
                state.sceneError != null, memory.javaHeapBytes, memory.nativeHeapBytes, memory.rssKb,
                sampleKind, state.stats.rearCaptureFps, state.stats.frontCaptureFps,
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
            currentNow = current.takeIf { it != Int.MIN_VALUE && it != 0 },
            voltageMillivolts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        )
    }
}

/** Sampled once a second, outside the media path. RSS includes native and mapped buffers. */
data class MemorySample(val javaHeapBytes: Long? = null, val nativeHeapBytes: Long? = null, val rssKb: Long? = null)

internal fun processMemorySample(): MemorySample {
    val runtime = Runtime.getRuntime()
    val rss = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        }
    }.getOrNull()
    return MemorySample(runtime.totalMemory() - runtime.freeMemory(), android.os.Debug.getNativeHeapAllocatedSize(), rss)
}
