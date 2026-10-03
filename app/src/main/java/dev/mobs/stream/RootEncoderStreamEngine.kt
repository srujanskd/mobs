package dev.mobs.stream

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtmp.RtmpStream
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

    /** The current destination's server, for state that must not contain the key. */
    @Volatile private var server = ""

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
        _state.update { it.copy(prepared = true, profileError = null, video = video) }
        return PrepareResult.Success
    }

    override fun attachPreview(surface: Surface, width: Int, height: Int) {
        if (!_state.value.prepared || stream.isOnPreview) return
        try {
            stream.startPreview(surface, width, height)
            _state.update { it.copy(previewing = true) }
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
        _state.update {
            it.copy(
                streamActive = true,
                connection = ConnectionState.Connecting(destination.server),
                stats = StreamStats(),
            )
        }
        try {
            stream.startStream(destination.endpoint)
        } catch (e: RuntimeException) {
            // The message may quote the endpoint, which contains the key; log the type only.
            Log.w(TAG, "startStream failed: ${e.javaClass.simpleName}")
            stopStream()
            _state.update { it.copy(connection = ConnectionState.Failed("Unable to start stream")) }
        }
    }

    override fun stopStream() {
        if (stream.isStreaming) stream.stopStream()
        _state.update { it.copy(streamActive = false, connection = ConnectionState.Idle, stats = StreamStats()) }
    }

    override fun release() {
        stream.release()
        _state.update {
            StreamEngineState(cameraFacing = it.cameraFacing, microphoneMuted = it.microphoneMuted)
        }
    }

    // RootEncoder's url argument includes the stream key, so state carries [server] instead.
    override fun onConnectionStarted(url: String) {
        if (!_state.value.streamActive) return
        _state.update { it.copy(connection = ConnectionState.Connecting(server)) }
    }

    override fun onConnectionSuccess() {
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
        stream.stopStream()
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

    override fun onAuthSuccess() = Unit

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
