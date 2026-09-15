package dev.mobs.editor

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobs.stream.StreamEngine
import dev.mobs.stream.StreamEngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Editor commands and state depend only on the media interface. The service owns its lifetime. */
class CameraViewModel : ViewModel() {
    private var engine: StreamEngine? = null
    private var stateJob: Job? = null
    private val mutableState = MutableStateFlow<StreamEngineState?>(null)
    val state = mutableState.asStateFlow()

    fun bind(engine: StreamEngine) {
        unbind()
        this.engine = engine
        mutableState.value = engine.state.value
        stateJob = viewModelScope.launch {
            engine.state.collect { mutableState.value = it }
        }
    }

    fun unbind() {
        stateJob?.cancel()
        stateJob = null
        engine?.detachPreview()
        engine = null
        mutableState.value = null
    }

    fun updatePreview(surface: Surface, width: Int, height: Int) {
        val engine = engine ?: return
        if (engine.state.value.previewing) engine.setPreviewSize(width, height)
        else engine.attachPreview(surface, width, height)
    }

    fun detachPreview() {
        engine?.detachPreview()
    }

    fun toggleMicrophone() {
        val engine = engine ?: return
        engine.setMicrophoneMuted(!engine.state.value.microphoneMuted)
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    override fun onCleared() {
        unbind()
    }
}
