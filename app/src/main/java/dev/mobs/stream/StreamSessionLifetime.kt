package dev.mobs.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Service-owned lifetime policy, separate from transient transport connectivity. */
internal class StreamSessionLifetime(
    private val engine: StreamEngine,
    scope: CoroutineScope,
    private val stopService: () -> Unit,
) {
    private var editorBound = false
    private var editorHasBound = false

    init {
        scope.launch { engine.state.collect { stopIfUnused() } }
    }

    fun onEditorBound() {
        editorBound = true
        editorHasBound = true
    }

    fun onEditorUnbound() {
        editorBound = false
        engine.detachPreview()
        stopIfUnused()
    }

    private fun stopIfUnused() {
        if (editorHasBound && !editorBound && !engine.state.value.streamActive) stopService()
    }
}
