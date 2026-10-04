package dev.mobs.editor

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mobs.render.ContentLogoDecoder
import dev.mobs.render.LogoDecoder
import dev.mobs.render.LogoOverlay
import dev.mobs.render.OverlayAnchor
import dev.mobs.render.TextOverlay
import dev.mobs.storage.KeystoreStreamDestinationStore
import dev.mobs.storage.StreamDestinationStore
import dev.mobs.stream.DestinationProblem
import dev.mobs.stream.DestinationResult
import dev.mobs.stream.StreamDestination
import dev.mobs.stream.StreamEngine
import dev.mobs.stream.StreamEngineState
import dev.mobs.stream.ThermalState
import dev.mobs.stream.isCritical
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Editor commands and state depend only on the media interface. The service owns its lifetime. */
class CameraViewModel(
    private val destinationStore: StreamDestinationStore,
    private val logoDecoder: LogoDecoder,
) : ViewModel() {
    private var engine: StreamEngine? = null
    private var thermalSource: StateFlow<ThermalState>? = null
    private var stateJob: Job? = null
    private var thermalJob: Job? = null
    private val mutableState = MutableStateFlow<StreamEngineState?>(null)
    val state = mutableState.asStateFlow()

    private val mutableThermal = MutableStateFlow(ThermalState())
    val thermal = mutableThermal.asStateFlow()

    private var logoJob: Job? = null
    private val mutableLogoFailed = MutableStateFlow(false)

    /** Set when a picked image could not be decoded; the screen clears it once shown. */
    val logoFailed = mutableLogoFailed.asStateFlow()

    private val mutableDestination = MutableStateFlow<SavedDestination>(SavedDestination.Loading)
    val destination = mutableDestination.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = destinationStore.load()
            // A destination entered while loading is newer than whatever was on disk.
            if (mutableDestination.value == SavedDestination.Loading) {
                mutableDestination.value = saved?.let(SavedDestination::Ready) ?: SavedDestination.Missing
            }
        }
    }

    fun bind(engine: StreamEngine, thermal: StateFlow<ThermalState>) {
        unbind()
        this.engine = engine
        thermalSource = thermal
        mutableState.value = engine.state.value
        mutableThermal.value = thermal.value
        stateJob = viewModelScope.launch {
            engine.state.collect { mutableState.value = it }
        }
        thermalJob = viewModelScope.launch {
            thermal.collect { mutableThermal.value = it }
        }
    }

    fun unbind() {
        stateJob?.cancel()
        stateJob = null
        thermalJob?.cancel()
        thermalJob = null
        thermalSource = null
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

    /** Validates and saves the destination, or returns why it was rejected. */
    fun saveDestination(server: String, streamKey: String): DestinationProblem? =
        when (val result = StreamDestination.parse(server, streamKey)) {
            is DestinationResult.Invalid -> result.problem
            is DestinationResult.Valid -> {
                mutableDestination.value = SavedDestination.Ready(result.destination)
                viewModelScope.launch { destinationStore.save(result.destination) }
                null
            }
        }

    fun goLive() {
        val engine = engine ?: return
        val destination = (mutableDestination.value as? SavedDestination.Ready)?.destination ?: return
        val current = engine.state.value
        if (current.streamActive || current.profileError != null) return
        // The service would stop it again immediately; the screen shows why.
        if (thermalSource?.value?.level?.isCritical() == true) return
        engine.startStream(destination)
    }

    fun stopStream() {
        engine?.stopStream()
    }

    /** Blank text removes the overlay. Applies immediately, including while live. */
    fun setTextOverlay(text: String, anchor: OverlayAnchor) {
        val engine = engine ?: return
        val trimmed = text.trim()
        engine.setTextOverlay(
            if (trimmed.isEmpty()) null
            else (engine.state.value.textOverlay ?: TextOverlay(trimmed)).copy(text = trimmed, anchor = anchor),
        )
    }

    /** Decodes [uri] at output size and draws it, keeping the current anchor. A newer pick wins. */
    fun pickLogo(uri: String) {
        val engine = engine ?: return
        logoJob?.cancel()
        logoJob = viewModelScope.launch {
            val video = engine.state.value.video
            val image = logoDecoder.decode(uri, video.width, video.height)
            if (image == null) {
                mutableLogoFailed.value = true
                return@launch
            }
            val anchor = engine.state.value.logoOverlay?.anchor
            engine.setLogoOverlay(anchor?.let { LogoOverlay(image, it) } ?: LogoOverlay(image))
        }
    }

    fun setLogoAnchor(anchor: OverlayAnchor) {
        val engine = engine ?: return
        val logo = engine.state.value.logoOverlay ?: return
        engine.setLogoOverlay(logo.withAnchor(anchor))
    }

    fun removeLogo() {
        logoJob?.cancel()
        engine?.setLogoOverlay(null)
    }

    fun dismissLogoFailure() {
        mutableLogoFailed.value = false
    }

    override fun onCleared() {
        unbind()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                CameraViewModel(KeystoreStreamDestinationStore(app), ContentLogoDecoder(app))
            }
        }
    }
}

sealed interface SavedDestination {
    data object Loading : SavedDestination
    data object Missing : SavedDestination
    data class Ready(val destination: StreamDestination) : SavedDestination
}
