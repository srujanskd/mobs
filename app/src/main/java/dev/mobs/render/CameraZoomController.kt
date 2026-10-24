package dev.mobs.render

import dev.mobs.scene.CameraZoomState
import kotlin.math.abs
import kotlin.math.round

internal data class ZoomCommand(val sequence: Int, val ratio: Float)

/** Cross the main lens at 1x before switching between telephoto and ultrawide.
 * Capture results, rather than requested scene values, confirm the selected zoom.
 */
internal class CameraZoomController {
    private var sequence = 0
    private var frames = 0
    private var retries = 0
    private var current: ZoomCommand? = null
    private var requested = 1f
    private var actual: Float? = null
    private var failed = false
    val state: CameraZoomState get() = CameraZoomState(requested, actual, failed)

    fun request(ratio: Float): ZoomCommand {
        require(ratio.isFinite() && ratio > 0f)
        val previous = actual ?: current?.ratio ?: 1f
        requested = ratio
        failed = false
        frames = 0
        retries = 0
        val crossesMain = previous > 1.01f && ratio < .99f || previous < .99f && ratio > 1.01f
        return issue(if (crossesMain) 1f else ratio)
    }

    fun observe(command: ZoomCommand, reported: Float?): ZoomCommand? {
        if (command != current || failed) return null
        if (reported != null && reported.isFinite() && reported > 0f) actual = round(reported * 100) / 100
        frames++
        if (reported != null && abs(reported - command.ratio) < .03f) {
            if (abs(command.ratio - requested) >= .03f) {
                frames = 0
                return issue(requested)
            }
            return null
        }
        if (frames >= 15 && retries == 0) {
            retries++
            frames = 0
            return issue(command.ratio)
        }
        if (frames >= 45) failed = true
        return null
    }

    private fun issue(ratio: Float) = ZoomCommand(++sequence, ratio).also { current = it }
}
