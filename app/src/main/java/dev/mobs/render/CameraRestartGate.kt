package dev.mobs.render

/** CameraDevice.close is asynchronous. Never reopen a pair until onClosed and in-flight opens finish. */
internal class CameraRestartGate<T> {
    private val opening = mutableSetOf<Any>()
    private val closing = mutableSetOf<T>()
    private var pending: (() -> Unit)? = null
    val waiting: Boolean get() = pending != null
    fun opening(token: Any) { opening.add(token) }
    fun opened(token: Any) { opening.remove(token); drain() }
    fun closing(device: T) { closing.add(device) }
    fun closed(device: T) { closing.remove(device); drain() }
    fun request(action: () -> Unit) { pending = action; drain() }
    fun cancel() { pending = null }
    private fun drain() {
        if (opening.isNotEmpty() || closing.isNotEmpty()) return
        val action = pending ?: return
        pending = null
        action()
    }
}

/** One cold retry per selected camera/zoom configuration. Never loop on unsupported concurrent optics. */
internal class ConcurrentZoomRecovery {
    private var selection: Any? = null
    private var used = false
    fun select(value: Any) { if (selection != value) { selection = value; used = false } }
    fun reset() { used = false }
    fun retry(dual: Boolean, zoom: Float): Boolean {
        if (!dual || zoom >= 1f || used) return false
        used = true
        return true
    }
}
