package dev.mobs.stream

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** No URLs, scene names, text contents, SSIDs, IPs, location or stream destination. */
internal class SessionDiagnostics(private val context: Context) {
    private var lastPeriodic = Long.MIN_VALUE
    private var lastState: String? = null
    private val sceneNumbers = mutableMapOf<String, Int>()
    private val clockTicks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()

    fun sample(state: StreamEngineState, thermal: ThermalState): String? {
        val now = SystemClock.elapsedRealtime()
        return try {
            val scene = state.scene
            val event = JSONObject().apply {
                put("scene", scene?.let { sceneNumbers.getOrPut(it.id) { sceneNumbers.size + 1 } } ?: 0)
                put("sources", JSONArray().apply {
                    scene?.sources?.forEach { source -> put(JSONObject().apply {
                        put("kind", source.kind.name); put("visible", source.visible); put("opacity", source.opacity)
                        put("x", source.bounds.x); put("y", source.bounds.y)
                        put("width", source.bounds.width); put("height", source.bounds.height)
                        put("rotation", source.rotation); put("zoom", source.zoom); put("mirrored", source.mirrored)
                    }) }
                })
                put("thermal", thermal.level.name); put("heat_mode", state.heatMode.name)
                put("stream_active", state.streamActive); put("preview", state.previewing)
                put("connection", state.connection.javaClass.simpleName)
                // The service redacts URLs and the active stream key before writing this record.
                put("scene_error_reason", state.sceneError ?: JSONObject.NULL)
                put("recording_error_reason", state.recordingError ?: JSONObject.NULL)
                put("connection_reason", when (val connection = state.connection) {
                    is ConnectionState.Failed -> connection.reason
                    is ConnectionState.Reconnecting -> connection.reason
                    else -> JSONObject.NULL
                })
                put("scene_error", state.sceneError != null); put("recording_error", state.recordingError != null)
            }
            val signature = event.toString()
            val periodic = lastPeriodic == Long.MIN_VALUE || now - lastPeriodic >= 10_000
            if (!periodic && signature == lastState) return null
            val first = lastState == null
            lastState = signature
            event.put("elapsed_realtime_ms", now)
            event.put("wall_time_ms", System.currentTimeMillis())
            event.put("kind", if (first) "start" else if (periodic) "sample" else "state_change")
            if (periodic) {
                lastPeriodic = now
                event.put("threads", threads())
                event.put("clock_ticks_per_second", clockTicks ?: JSONObject.NULL)
                event.put("environment", environment())
                event.put("gl_vendor", state.stats.render.glVendor ?: JSONObject.NULL)
                event.put("gl_renderer", state.stats.render.glRenderer ?: JSONObject.NULL)
            }
            if (first) event.put("device", device())
            event.toString()
        } catch (_: Exception) {
            JSONObject().put("kind", "diagnostics_unavailable").put("elapsed_realtime_ms", now).toString()
        }
    }

    private fun threads(): JSONObject = runCatching {
        val tasks = File("/proc/self/task").listFiles() ?: return JSONObject().put("available", false)
        JSONObject().put("available", true).put("total", tasks.size).put("truncated", tasks.size > 512)
            .put("samples", JSONArray().apply {
                tasks.take(512).forEach { task -> runCatching {
                    val stat = File(task, "stat").readText()
                    val start = stat.indexOf('('); val end = stat.lastIndexOf(')')
                    val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
                    put(JSONObject().put("tid", task.name.toInt()).put("name", stat.substring(start + 1, end))
                        .put("cpu_ticks", threadCpuTicks(stat) ?: JSONObject.NULL)
                        .put("start_ticks", fields.getOrNull(19)?.toLongOrNull() ?: JSONObject.NULL))
                } }
            })
    }.getOrElse { JSONObject().put("available", false) }

    private fun environment(): JSONObject = JSONObject().apply {
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            put("interactive", power.isInteractive); put("power_save", power.isPowerSaveMode)
            put("brightness_setting", Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1))
            put("brightness_mode", Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
        }
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            display?.let {
                put("display_refresh_hz", it.refreshRate)
                put("display_width", it.mode.physicalWidth); put("display_height", it.mode.physicalHeight)
            }
        }
        runCatching {
            val network = context.getSystemService(ConnectivityManager::class.java)
            val caps = network.getNetworkCapabilities(network.activeNetwork)
            put("network", when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            })
            put("network_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
            put("network_metered", network.isActiveNetworkMetered)
            // These are Android capability estimates, not a bandwidth/signal measurement.
            put("estimated_upstream_kbps", caps?.linkUpstreamBandwidthKbps ?: JSONObject.NULL)
        }.onFailure { put("network", "unavailable") }
    }

    private fun device(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
        put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
        put("build_id", Build.ID); put("app_version", dev.mobs.BuildConfig.VERSION_NAME)
        put("build_type", dev.mobs.BuildConfig.BUILD_TYPE)
        put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("cpu_count", Runtime.getRuntime().availableProcessors())
        put("limits", "App-process CPU only; no separate WebView-process CPU, GPU completion time, ISP power or privileged thermal sensors. Brightness is the system setting, not measured panel luminance.")
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            put("cameras", JSONArray().apply {
                manager.cameraIdList.forEach { id ->
                    val info = manager.getCameraCharacteristics(id)
                    put(JSONObject().apply {
                        put("id", id); put("facing", info.get(CameraCharacteristics.LENS_FACING))
                        put("hardware_level", info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                        put("fps_ranges", JSONArray(info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { "${it.lower}..${it.upper}" }))
                        put("texture_sizes", JSONArray(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty().map { "${it.width}x${it.height}" }))
                    })
                }
            })
        }.onFailure { put("cameras_unavailable", true) }
    }
}
