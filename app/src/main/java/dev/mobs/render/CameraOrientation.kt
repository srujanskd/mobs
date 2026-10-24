package dev.mobs.render

/** Camera2's SurfaceTexture matrix already includes sensor rotation and lens facing.
 * The shader transforms sampling coordinates, so apply display rotation in UV space,
 * rather than the inverse rotation of a screen-space quad.
 */
internal class CameraOrientation(private val displayDegrees: () -> Int) {
    val textureRotation: Int get() = displayDegrees() % 360
}

/** Preserve the producer's sensor, crop and flip transform; append display compensation only. */
internal fun applyCameraDisplayRotation(matrix: FloatArray, orientation: CameraOrientation) {
    val rotation = orientation.textureRotation
    if (rotation == 0) return
    android.opengl.Matrix.translateM(matrix, 0, .5f, .5f, 0f)
    android.opengl.Matrix.rotateM(matrix, 0, rotation.toFloat(), 0f, 0f, 1f)
    android.opengl.Matrix.translateM(matrix, 0, -.5f, -.5f, 0f)
}
