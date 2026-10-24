package dev.mobs.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneCollection
import dev.mobs.scene.SceneStore
import dev.mobs.stream.StreamEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Serializes writes, keeps edits separate from the broadcast, and reapplies after service recreation. */
class SceneViewModel(private val store: SceneStore, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutableCollection = MutableStateFlow<SceneCollection?>(null)
    val collection = mutableCollection.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var engine: StreamEngine? = null
    private sealed interface StoreAction {
        data class Save(val value: SceneCollection) : StoreAction
        data object Refresh : StoreAction
    }
    private val actions = Channel<StoreAction>(Channel.UNLIMITED)
    private var revision = 0L
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing = mutableRefreshing.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(io) { store.load() }
                mutableCollection.value = loaded
                engine?.setScene(loaded.active)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                mutableError.value = "Saved scenes could not be read. Your file was kept. Close the editor and restore the scene file before saving new scenes."
            }
        }
        viewModelScope.launch {
            for (action in actions) {
                when (action) {
                    is StoreAction.Save -> {
                        try { withContext(io) { store.save(action.value) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { mutableError.value = "Scenes could not be saved. Check available storage and try again." }
                    }
                    StoreAction.Refresh -> {
                        try {
                            val before = revision
                            val loaded = withContext(io) { store.load() }
                            // An edit made during disk IO remains newer than the reloaded collection.
                            if (revision == before) mutableCollection.value = loaded
                            val currentEngine = engine
                            if (currentEngine == null || !currentEngine.state.value.prepared) {
                                mutableError.value = "Scenes reloaded, but the camera engine is not ready. Reopen the preview to retry."
                                continue
                            }
                            val current = mutableCollection.value ?: loaded
                            val output = currentEngine.state.value.scene?.takeIf { scene -> current.scenes.any { it.id == scene.id } }
                                ?: current.active
                            currentEngine.refreshScene(output)
                            mutableError.value = currentEngine.state.value.sceneError
                            if (mutableError.value == null) mutableNotice.value = "Scenes reloaded. Active sources restarting."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { mutableError.value = "Could not refresh scenes. Saved scenes were kept. Check storage and try again." }
                        finally { mutableRefreshing.value = false }
                    }
                }
            }
        }
    }

    fun bind(value: StreamEngine?) {
        engine = value
        val active = mutableCollection.value?.active ?: return
        if (value?.state?.value?.scene == null) value?.setScene(active)
    }

    fun save(scene: Scene, apply: Boolean): Boolean {
        val current = mutableCollection.value ?: return false
        try {
            val next = current.save(scene).let { if (apply) it.copy(activeId = scene.id) else it }
            if (apply) {
                engine?.setScene(scene)
                if (engine?.state?.value?.scene?.id != scene.id || engine?.state?.value?.sceneError != null) {
                    mutableError.value = engine?.state?.value?.sceneError ?: "Camera engine is not ready"
                    return false
                }
            }
            persist(next)
            return true
        } catch (_: IllegalArgumentException) {
            mutableError.value = "Scene limit reached or scene data is invalid"
            return false
        }
    }

    fun refresh() {
        if (mutableRefreshing.value || mutableCollection.value == null) return
        mutableRefreshing.value = true
        mutableNotice.value = null
        actions.trySend(StoreAction.Refresh)
    }
    fun dismissNotice() { mutableNotice.value = null }

    fun activate(scene: Scene) = save(scene, apply = true)
    fun delete(id: String) {
        val current = mutableCollection.value ?: return
        if (current.scenes.size == 1 || current.activeId == id) {
            mutableError.value = "Switch to another scene before deleting this scene"
            return
        }
        persist(current.delete(id))
    }
    fun dismissError() { mutableError.value = null }
    private fun persist(value: SceneCollection) {
        revision++
        mutableCollection.value = value
        actions.trySend(StoreAction.Save(value))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                SceneViewModel(SceneStore(File(app.filesDir, "scenes.mobs")))
            }
        }
    }
}
