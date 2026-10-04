package dev.mobs

import android.view.Surface
import dev.mobs.render.TextOverlay
import dev.mobs.stream.AudioProfile
import dev.mobs.stream.ConnectionState
import dev.mobs.stream.PrepareResult
import dev.mobs.stream.StreamDestination
import dev.mobs.stream.StreamEngine
import dev.mobs.stream.StreamEngineState
import dev.mobs.stream.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeStreamEngine : StreamEngine {
    override val state = MutableStateFlow(StreamEngineState())
    var detached = 0
    var released = 0
    var stopped = 0
    var switches = 0
    val started = mutableListOf<StreamDestination>()

    override fun prepare(video: VideoProfile, audio: AudioProfile) = PrepareResult.Success
    override fun attachPreview(surface: Surface, width: Int, height: Int) = Unit
    override fun setPreviewSize(width: Int, height: Int) = Unit
    override fun detachPreview() { detached++ }
    override fun switchCamera() { switches++ }
    override fun setMicrophoneMuted(muted: Boolean) {
        state.update { it.copy(microphoneMuted = muted) }
    }
    override fun startStream(destination: StreamDestination) {
        started += destination
        state.update { it.copy(streamActive = true) }
    }
    val failures = mutableListOf<String?>()
    override fun stopStream(failure: String?) {
        stopped++
        failures += failure
        state.update {
            it.copy(streamActive = false, connection = failure?.let(ConnectionState::Failed) ?: ConnectionState.Idle)
        }
    }
    override fun setTextOverlay(overlay: TextOverlay?) {
        state.update { it.copy(textOverlay = overlay) }
    }
    override fun release() { released++ }
}
