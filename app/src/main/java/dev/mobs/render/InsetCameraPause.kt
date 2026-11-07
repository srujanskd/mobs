package dev.mobs.render

import dev.mobs.scene.Scene
import dev.mobs.scene.SourceKind
import dev.mobs.stream.HeatMode

/**
 * Concurrent rear+front capture is the dominant sustained heat source on the OnePlus traces:
 * single-camera runs at the same 1080p30/10 Mbps settled near 38 C at light heat, while every
 * dual-camera run kept climbing past 43 C, even with camera/output cadence halved. The only
 * effective lever is closing one sensor, so at moderate heat the smaller camera is frozen.
 *
 * Pausing is immediate. Resuming waits for [holdMs] of continuous normal heat mode, because a
 * single camera only cools the phone back to about light heat; resuming as soon as the
 * 30-second heat recovery ends would make the inset flap on and off every few minutes.
 */
internal class InsetCameraPause(private val holdMs: Long = DEFAULT_HOLD_MS) {
    var paused = false
        private set
    private var normalSince: Long? = null

    fun update(mode: HeatMode, nowMs: Long): Boolean {
        if (mode > HeatMode.NORMAL) {
            paused = true
            normalSince = null
        } else if (paused) {
            val since = normalSince ?: nowMs.also { normalSince = it }
            if (nowMs - since >= holdMs) {
                paused = false
                normalSince = null
            }
        }
        return paused
    }

    companion object {
        const val DEFAULT_HOLD_MS = 5 * 60_000L
    }
}

/** The camera drawn with the smallest area when a scene shows both; null for single-camera scenes. */
internal fun insetCamera(scene: Scene): SourceKind? {
    if (scene.cameras.size != 2) return null
    // Scenes hold at most one layer per camera kind.
    return scene.sources
        .filter { it.visible && it.opacity > 0f && it.kind in Scene.CAMERA_KINDS }
        .minByOrNull { it.bounds.width * it.bounds.height }
        ?.kind
}
