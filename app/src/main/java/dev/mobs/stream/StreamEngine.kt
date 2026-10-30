package dev.mobs.stream

import dev.mobs.scene.Scene
import dev.mobs.scene.DualCameraSupport
import android.view.Surface
import dev.mobs.render.LogoOverlay
import dev.mobs.render.TextOverlay
import kotlinx.coroutines.flow.StateFlow

/**
 * The media session the rest of the app talks to. Everything above this interface — screens,
 * scenes, goals — must stay free of the underlying capture/encoder library so that replacing the
 * engine does not reach into scene storage or UI.
 */
interface StreamEngine {

    val state: StateFlow<StreamEngineState>

    /**
     * Configures capture and both encoders. Must succeed before a preview or a stream starts, and
     * the profiles cannot change while either is running.
     */
    fun prepare(video: VideoProfile = VideoProfile.LANDSCAPE_1080P30, audio: AudioProfile = AudioProfile.DEFAULT): PrepareResult

    /** Draws the composed output into [surface]. The engine owns the camera, not the view. */
    fun attachPreview(surface: Surface, width: Int, height: Int)

    fun setPreviewSize(width: Int, height: Int)

    /** Preview-only scale; encoded scene geometry remains unchanged. */
    fun setPreviewFill(fill: Boolean)

    fun detachPreview()

    /** Applies a saved scene without restarting audio, encoders, or transport. */
    fun setScene(scene: Scene)

    /** Reload active source resources without restarting audio, encoders, or transport. */
    fun refreshScene(scene: Scene)

    fun switchCamera()

    fun setMicrophoneMuted(muted: Boolean)

    fun startStream(destination: StreamDestination)

    /**
     * Draws [overlay] into the composed output, which feeds both preview and encoder. Null removes
     * it. The overlay persists across preview detach and stream restarts until replaced.
     */
    fun setTextOverlay(overlay: TextOverlay?)

    /** Draws [logo] into the composed output beneath the text overlay. Null removes it. */
    fun setLogoOverlay(logo: LogoOverlay?)

    /** Ends the session. A non-null [failure] reports it as [ConnectionState.Failed] instead of idle. */
    fun stopStream(failure: String? = null)

    /** Apply service-owned runtime heat limits without restarting transport. */
    fun setHeatMode(mode: HeatMode)

    fun release()
}

/**
 * Mandatory output profile from the plan: native 1920x1080 at 30 fps, landscape, one camera.
 * [bitrateBps] starts at YouTube's documented 1080p30 H.264 recommendation. Runtime heat
 * protection may reduce bitrate and cadence without rewriting this requested profile.
 */
data class VideoProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val keyframeIntervalSeconds: Int,
) {
    companion object {
        val LANDSCAPE_1080P30 = VideoProfile(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 10_000_000,
            keyframeIntervalSeconds = 2,
        )
    }
}

data class AudioProfile(
    val sampleRate: Int,
    val stereo: Boolean,
    val bitrateBps: Int,
    val echoCanceler: Boolean,
    val noiseSuppressor: Boolean,
) {
    companion object {
        val DEFAULT = AudioProfile(
            sampleRate = 48_000,
            stereo = true,
            bitrateBps = 128_000,
            echoCanceler = false,
            noiseSuppressor = false,
        )
    }
}

sealed interface PrepareResult {
    data object Success : PrepareResult

    /** The device rejected the requested profile. Report it; do not silently stream something else. */
    data class Unsupported(val reason: String) : PrepareResult
}

data class StreamEngineState(
    val heatMode: HeatMode = HeatMode.NORMAL,
    val scene: Scene? = null,
    val cameraZoom: Map<String, dev.mobs.scene.CameraZoomState> = emptyMap(),
    val sceneError: String? = null,
    val recordingError: String? = null,
    val dualCameraSupport: DualCameraSupport = DualCameraSupport(),
    val prepared: Boolean = false,
    /** Set when the device rejected the requested profile. Never a reason to stream something else. */
    val profileError: String? = null,
    val previewing: Boolean = false,
    /** Remains true through transport retries until the session stops or fails permanently. */
    val streamActive: Boolean = false,
    val cameraFacing: CameraFacing = CameraFacing.REAR,
    val microphoneMuted: Boolean = false,
    val camera: CameraStatus = CameraStatus.Closed,
    val connection: ConnectionState = ConnectionState.Idle,
    val video: VideoProfile = VideoProfile.LANDSCAPE_1080P30,
    val stats: StreamStats = StreamStats(),
    val textOverlay: TextOverlay? = null,
    val logoOverlay: LogoOverlay? = null,
)

/** Transport statistics for the current session. Reset whenever a session starts or stops. */
data class StreamStats(
    /** `SystemClock.elapsedRealtime()` of the session's first successful connection. */
    val connectedSinceElapsedMs: Long? = null,
    /** Measured send rate. */
    val bitrateBps: Long = 0,
    /** Encoder bitrate the adapter currently asks for; below the profile bitrate under congestion. */
    val targetBitrateBps: Long = 0,
    /** Video frames the encoder produced in the last second. */
    val outputFps: Int = 0,
    /** Successful browser texture uploads per second, across active documents. */
    val browserUploadFps: Int = 0,
    val browserUploadBytesPerSecond: Long = 0,
    val rearCaptureFps: Int = 0,
    val frontCaptureFps: Int = 0,
    val droppedVideoFrames: Long = 0,
)

enum class CameraFacing { REAR, FRONT }

sealed interface CameraStatus {
    data object Closed : CameraStatus
    data object Open : CameraStatus

    /** The camera was taken away mid-session; the UI must say so instead of showing a frozen frame. */
    data object Disconnected : CameraStatus
    data class Error(val reason: String) : CameraStatus
}

/**
 * Transport state only. A YouTube broadcast being live is a separate fact tracked elsewhere, so
 * that a connected socket never makes the app claim to be on air.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    /** [server] excludes the stream key. */
    data class Connecting(val server: String) : ConnectionState
    data object Connected : ConnectionState

    /** The session is still active and waiting to retry; video is not reaching the server. */
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState

    /** The session ended without the user stopping it. */
    data class Failed(val reason: String) : ConnectionState
    data object Disconnected : ConnectionState
}
