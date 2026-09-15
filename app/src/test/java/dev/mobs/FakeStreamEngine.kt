package dev.mobs

import android.view.Surface
import dev.mobs.stream.AudioProfile
import dev.mobs.stream.PrepareResult
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

    override fun prepare(video: VideoProfile, audio: AudioProfile) = PrepareResult.Success
    override fun attachPreview(surface: Surface, width: Int, height: Int) = Unit
    override fun setPreviewSize(width: Int, height: Int) = Unit
    override fun detachPreview() { detached++ }
    override fun switchCamera() { switches++ }
    override fun setMicrophoneMuted(muted: Boolean) {
        state.update { it.copy(microphoneMuted = muted) }
    }
    override fun startStream(endpoint: String) = Unit
    override fun stopStream() { stopped++ }
    override fun release() { released++ }
}
