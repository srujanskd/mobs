package dev.mobs.stream

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.graphics.Bitmap
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import dev.mobs.render.LogoOverlay
import dev.mobs.render.OverlayPlacement
import dev.mobs.render.TextOverlay
import dev.mobs.render.TextRasterizer
import dev.mobs.render.placeOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [StreamEngine] backed by RootEncoder's [RtmpStream]. RootEncoder is a candidate engine, not a
 * settled dependency: the plan keeps it behind this adapter until the device power and 1080p30
 * gates pass. No RootEncoder type may leak out of this file.
 */
class RootEncoderStreamEngine(context: Context) : StreamEngine, ConnectChecker {

    private val appContext = context.applicationContext
    private val camera = Camera2Source(appContext)
    private val microphone = MicrophoneSource()
    private val stream = RtmpStream(appContext, this, camera, microphone)
    private val reconnect = ReconnectPolicy()

    /**
     * Lowers the encoder bitrate when RootEncoder's send queue backs up, then probes back toward
     * the profile bitrate. Resolution and frame rate stay fixed; only bitrate adapts, as the plan
     * allows. Without it a slow uplink lets the queue overflow and YouTube receives too little.
     */
    private var bitrateAdapter: QueueAwareBitrateAdapter? = null

    /** The current destination's server, for state that must not contain the key. */
    @Volatile private var server = ""

    /**
     * One cached image source in RootEncoder's renderer. GlStreamInterface releases and drops all
     * filters when it stops (preview detached with no stream) and discards filters queued before
     * it starts, so [filter] is cleared on stop and a new one is added on each start.
     */
    private class OverlaySlot {
        /** Prepared once per change and reused whenever the filter has to be re-added. */
        var image: Pair<Bitmap, OverlayPlacement>? = null
        var filter: ImageObjectFilterRender? = null

        /** The image [filter] holds, so lifecycle syncs do not re-upload an unchanged texture. */
        var applied: Pair<Bitmap, OverlayPlacement>? = null
    }

    private val logoSlot = OverlaySlot()
    private val textSlot = OverlaySlot()

    /** Filters draw in the order they were added, so after a GL restart text stays above the logo. */
    private val overlaySlots = listOf(logoSlot, textSlot)

    private val _state = MutableStateFlow(StreamEngineState())
    override val state: StateFlow<StreamEngineState> = _state.asStateFlow()

    init {
        // Landscape-locked for the proof of concept, so the encoder keeps one fixed 1920x1080
        // geometry. Sensor-driven orientation arrives with the portrait stream profile.
        stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
        stream.getGlInterface().autoHandleOrientation = false
        stream.getStreamClient().apply {
            // ReconnectPolicy owns the budget. RootEncoder's own counter only resets on stop, so
            // a finite value here would end a long session after enough separate short drops.
            setReTries(Int.MAX_VALUE)
            // Off by default in RootEncoder 2.8.1. Without it any CA-signed certificate is
            // accepted for any host, and the stream key travels inside this connection.
            setTlsHostVerification(true)
        }
        stream.setFpsListener { fps ->
            if (_state.value.streamActive) _state.update { it.copy(stats = it.stats.copy(outputFps = fps)) }
        }
        camera.setCameraCallback(object : CameraCallbacks {
            override fun onCameraOpened() = _state.update { it.copy(camera = CameraStatus.Open) }

            override fun onCameraChanged(facing: CameraHelper.Facing) =
                _state.update { it.copy(cameraFacing = facing.toCameraFacing()) }

            override fun onCameraDisconnected() =
                _state.update { it.copy(camera = CameraStatus.Disconnected) }

            override fun onCameraError(error: String) =
                _state.update { it.copy(camera = CameraStatus.Error(error)) }
        })
    }

    override fun prepare(video: VideoProfile, audio: AudioProfile): PrepareResult {
        if (_state.value.prepared) return PrepareResult.Success
        try {
            val videoReady = stream.prepareVideo(
                width = video.width,
                height = video.height,
                bitrate = video.bitrateBps,
                fps = video.fps,
                iFrameInterval = video.keyframeIntervalSeconds,
                rotation = LANDSCAPE_ROTATION,
            )
            if (!videoReady) {
                return unsupported(video, "No hardware video encoder accepted ${video.width}x${video.height}@${video.fps}")
            }
            val audioReady = stream.prepareAudio(
                sampleRate = audio.sampleRate,
                isStereo = audio.stereo,
                bitrate = audio.bitrateBps,
                echoCanceler = audio.echoCanceler,
                noiseSuppressor = audio.noiseSuppressor,
            )
            if (!audioReady) {
                return unsupported(video, "Microphone capture or AAC encoding rejected ${audio.sampleRate} Hz")
            }
        } catch (e: IllegalArgumentException) {
            return unsupported(video, e.message ?: "Unsupported capture configuration")
        } catch (e: IllegalStateException) {
            return unsupported(video, e.message ?: "Engine busy")
        }
        bitrateAdapter = QueueAwareBitrateAdapter(video.bitrateBps) { bitrate ->
            stream.setVideoBitrateOnFly(bitrate)
            _state.update { it.copy(stats = it.stats.copy(targetBitrateBps = bitrate.toLong())) }
        }
        _state.update { it.copy(prepared = true, profileError = null, video = video) }
        return PrepareResult.Success
    }

    override fun attachPreview(surface: Surface, width: Int, height: Int) {
        if (!_state.value.prepared || stream.isOnPreview) return
        try {
            stream.startPreview(surface, width, height)
            _state.update { it.copy(previewing = true) }
            syncOverlayFilter()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Preview surface rejected", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Preview already running", e)
        }
    }

    override fun setPreviewSize(width: Int, height: Int) {
        if (!stream.isOnPreview) return
        stream.getGlInterface().setPreviewResolution(width, height)
    }

    override fun detachPreview() {
        if (!stream.isOnPreview) return
        stream.stopPreview()
        syncOverlayFilter()
        _state.update {
            it.copy(
                previewing = false,
                camera = if (stream.isStreaming) it.camera else CameraStatus.Closed,
            )
        }
    }

    override fun switchCamera() {
        camera.switchCamera()
        _state.update { it.copy(cameraFacing = camera.getCameraFacing().toCameraFacing()) }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        if (muted) microphone.mute() else microphone.unMute()
        _state.update { it.copy(microphoneMuted = microphone.isMuted()) }
    }

    override fun startStream(destination: StreamDestination) {
        if (!_state.value.prepared || stream.isStreaming) return
        server = destination.server
        reconnect.reset()
        stream.getStreamClient().resetDroppedVideoFrames()
        // Each session starts at the full profile bitrate and adapts down from there.
        val profileBitrate = _state.value.video.bitrateBps
        bitrateAdapter?.reset()
        stream.setVideoBitrateOnFly(profileBitrate)
        _state.update {
            it.copy(
                streamActive = true,
                connection = ConnectionState.Connecting(destination.server),
                stats = StreamStats(targetBitrateBps = profileBitrate.toLong()),
            )
        }
        try {
            stream.startStream(destination.endpoint)
            syncOverlayFilter()
        } catch (e: RuntimeException) {
            // The message may quote the endpoint, which contains the key; log the type only.
            Log.w(TAG, "startStream failed: ${e.javaClass.simpleName}")
            stopStream("Unable to start stream")
        }
    }

    override fun stopStream(failure: String?) {
        if (stream.isStreaming) stream.stopStream()
        syncOverlayFilter()
        val connection = failure?.let(ConnectionState::Failed) ?: ConnectionState.Idle
        _state.update { it.copy(streamActive = false, connection = connection, stats = StreamStats()) }
    }

    override fun setTextOverlay(overlay: TextOverlay?) {
        val video = _state.value.video
        val bitmap = overlay?.let { TextRasterizer.rasterize(it, video.width, video.height) }
        synchronized(this) {
            textSlot.image = if (overlay != null && bitmap != null) {
                bitmap to placeOverlay(bitmap.width, bitmap.height, video.width, video.height, overlay.anchor, overlay.margin)
            } else {
                null
            }
        }
        _state.update { it.copy(textOverlay = overlay?.takeIf { bitmap != null }) }
        syncOverlayFilter()
    }

    override fun setLogoOverlay(logo: LogoOverlay?) {
        val video = _state.value.video
        synchronized(this) {
            logoSlot.image = logo?.let {
                it.image to placeOverlay(it.image.width, it.image.height, video.width, video.height, it.anchor, it.margin)
            }
        }
        _state.update { it.copy(logoOverlay = logo) }
        syncOverlayFilter()
    }

    override fun release() {
        synchronized(this) { overlaySlots.forEach { it.filter = null } }
        stream.release()
        _state.update {
            StreamEngineState(cameraFacing = it.cameraFacing, microphoneMuted = it.microphoneMuted)
        }
    }

    // RootEncoder's url argument includes the stream key, so state carries [server] instead.
    override fun onConnectionStarted(url: String) {
        if (!_state.value.streamActive) return
        // Every retry connects afresh and lands here; keep showing the attempt, not "Connecting".
        _state.update {
            if (it.connection is ConnectionState.Reconnecting) it
            else it.copy(connection = ConnectionState.Connecting(server))
        }
    }

    override fun onConnectionSuccess() {
        if (!_state.value.streamActive) return
        reconnect.reset()
        _state.update {
            it.copy(
                connection = ConnectionState.Connected,
                stats = it.stats.copy(
                    connectedSinceElapsedMs = it.stats.connectedSinceElapsedMs ?: SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    override fun onConnectionFailed(reason: String) {
        // A late callback after the user stopped must not restart or report anything.
        if (!_state.value.streamActive) return
        // Reconnect keeps the encoder session alive; the UI shows the attempt rather than a stop.
        val delay = reconnect.nextDelayMs()
        if (delay != null && stream.getStreamClient().reTry(delay, reason)) {
            _state.update { it.copy(connection = ConnectionState.Reconnecting(reconnect.attempt, reason)) }
        } else {
            stream.stopStream()
            syncOverlayFilter()
            _state.update {
                it.copy(streamActive = false, connection = ConnectionState.Failed(reason), stats = StreamStats())
            }
        }
    }

    override fun onDisconnect() {
        // Fires after a user stop too; only an unexpected disconnect is worth showing.
        if (!_state.value.streamActive) return
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    override fun onAuthError() {
        if (!_state.value.streamActive) return
        stream.stopStream()
        syncOverlayFilter()
        _state.update {
            it.copy(
                streamActive = false,
                connection = ConnectionState.Failed("Stream key rejected"),
                stats = StreamStats(),
            )
        }
    }

    /** RootEncoder reports roughly once a second while streaming; sample drops at the same pace. */
    override fun onNewBitrate(bitrate: Long) {
        if (!_state.value.streamActive) return
        val dropped = stream.getStreamClient().getDroppedVideoFrames()
        _state.update { it.copy(stats = it.stats.copy(bitrateBps = bitrate, droppedVideoFrames = dropped)) }
    }

    override fun onStreamingStats(report: StreamingStatsReport) {
        if (!_state.value.streamActive) return
        bitrateAdapter?.onStreamingStats(report)
    }

    override fun onAuthSuccess() = Unit

    /**
     * Makes RootEncoder's renderer match every [OverlaySlot]. Called after every operation that
     * can start or stop GL, and from transport callbacks, hence the lock.
     */
    @Synchronized
    private fun syncOverlayFilter() {
        val gl = stream.getGlInterface()
        for (slot in overlaySlots) {
            if (!gl.isRunning) {
                // GL released the filter along with everything else; add a fresh one on next start.
                slot.filter = null
                slot.applied = null
                continue
            }
            val image = slot.image
            val current = slot.filter
            if (image == null) {
                current?.let(gl::removeFilter)
                slot.filter = null
                slot.applied = null
                continue
            }
            if (current != null && slot.applied === image) continue
            val (bitmap, placement) = image
            val filter = current ?: ImageObjectFilterRender().also {
                gl.addFilter(it)
                slot.filter = it
            }
            filter.setImage(bitmap)
            // Scale first: RootEncoder rescales the current position whenever the scale changes.
            filter.setScale(placement.widthPercent, placement.heightPercent)
            filter.setPosition(placement.leftPercent, placement.topPercent)
            slot.applied = image
        }
    }

    /** Records the rejected [video] profile so the error names what the device refused. */
    private fun unsupported(video: VideoProfile, reason: String): PrepareResult {
        Log.w(TAG, "prepare failed: $reason")
        _state.update { it.copy(prepared = false, profileError = reason, video = video) }
        return PrepareResult.Unsupported(reason)
    }

    private fun CameraHelper.Facing.toCameraFacing() = when (this) {
        CameraHelper.Facing.FRONT -> CameraFacing.FRONT
        CameraHelper.Facing.BACK -> CameraFacing.REAR
    }

    private companion object {
        const val TAG = "StreamEngine"

        /** 0 keeps width as the long edge, so 1920x1080 encodes as landscape. */
        const val LANDSCAPE_ROTATION = 0
    }
}
