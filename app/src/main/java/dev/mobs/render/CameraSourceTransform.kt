package dev.mobs.render

import android.opengl.Matrix
import kotlin.math.hypot

/** Fill the source bounds with a centered crop, preserving camera pixel proportions.
 * Measure the producer's transformed axes so sensor/display rotation and crop metadata
 * remain intact. Scene rotation and mirroring are included before calculating the crop.
 */
internal fun applyCameraSourceTransform(
    matrix: FloatArray,
    rotation: Int,
    mirrored: Boolean,
    inputWidth: Int,
    inputHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
) {
    if (rotation != 0) {
        Matrix.translateM(matrix, 0, .5f, .5f, 0f)
        Matrix.rotateM(matrix, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(matrix, 0, -.5f, -.5f, 0f)
    }
    if (mirrored) {
        Matrix.translateM(matrix, 0, 1f, 0f, 0f)
        Matrix.scaleM(matrix, 0, -1f, 1f, 1f)
    }

    val horizontalPixels = hypot(matrix[0] * inputWidth, matrix[1] * inputHeight)
    val verticalPixels = hypot(matrix[4] * inputWidth, matrix[5] * inputHeight)
    if (horizontalPixels <= 0f || verticalPixels <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) return
    val sourceAspect = horizontalPixels / verticalPixels
    val targetAspect = viewportWidth / viewportHeight
    val scaleX = minOf(1f, targetAspect / sourceAspect)
    val scaleY = minOf(1f, sourceAspect / targetAspect)
    Matrix.translateM(matrix, 0, (1f - scaleX) / 2f, (1f - scaleY) / 2f, 0f)
    Matrix.scaleM(matrix, 0, scaleX, scaleY, 1f)
}
