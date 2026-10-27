package dev.mobs.render

import android.opengl.Matrix
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class CameraSourceTransformDeviceTest {
    @Test fun defaultDualCameraInsetPreservesShapes() = verify(1280, 720, 1920f * .32f, 1080f * .32f)
    @Test fun squareInsetPreservesShapes() = verify(1280, 720, 400f, 400f)
    @Test fun wideInsetPreservesShapes() = verify(1280, 720, 800f, 200f)
    @Test fun tallInsetPreservesShapes() = verify(1280, 720, 200f, 600f)
    @Test fun quarterTurnPreservesShapes() = verify(1280, 720, 614.4f, 345.6f, rotation = 90)
    @Test fun mirroredQuarterTurnPreservesShapes() = verify(1280, 720, 614.4f, 345.6f, rotation = 270, mirrored = true)
    @Test fun rearCameraPreservesShapes() = verify(1920, 1080, 1920f, 1080f)
    @Test fun producerCropAndRotationPreserveShapes() {
        val producer = identity()
        Matrix.translateM(producer, 0, .1f, .95f, 0f)
        Matrix.scaleM(producer, 0, .8f, -.9f, 1f)
        Matrix.translateM(producer, 0, .5f, .5f, 0f)
        Matrix.rotateM(producer, 0, 90f, 0f, 0f, 1f)
        Matrix.translateM(producer, 0, -.5f, -.5f, 0f)
        applyCameraDisplayRotation(producer, CameraOrientation { 90 })
        verify(1280, 720, 400f, 400f, producer = producer)
    }

    private fun verify(w: Int, h: Int, vw: Float, vh: Float, rotation: Int = 0,
                       mirrored: Boolean = false, producer: FloatArray = identity()) {
        val matrix = producer.copyOf()
        applyCameraSourceTransform(matrix, rotation, mirrored, w, h, vw, vh)
        // Equal distances in camera pixels must cover equal distances in output pixels.
        val horizontalPixels = hypot(matrix[0] * w, matrix[1] * h)
        val verticalPixels = hypot(matrix[4] * w, matrix[5] * h)
        assertEquals("Camera image is stretched", horizontalPixels / vw, verticalPixels / vh, .0001f)
        // Cropping stays centered and never samples outside the producer's image.
        val center = FloatArray(4)
        val before = FloatArray(4)
        Matrix.multiplyMV(center, 0, matrix, 0, floatArrayOf(.5f, .5f, 0f, 1f), 0)
        Matrix.multiplyMV(before, 0, producer, 0, floatArrayOf(.5f, .5f, 0f, 1f), 0)
        assertArrayEquals(before, center, .0001f)
        for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            val point = FloatArray(4)
            Matrix.multiplyMV(point, 0, matrix, 0, floatArrayOf(x, y, 0f, 1f), 0)
            assertTrue(point[0] in -.0001f..1.0001f && point[1] in -.0001f..1.0001f)
        }
    }
    private fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
}
