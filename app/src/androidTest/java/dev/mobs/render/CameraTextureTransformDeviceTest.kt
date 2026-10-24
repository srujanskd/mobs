package dev.mobs.render

import android.opengl.Matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CameraTextureTransformDeviceTest {
    @Test fun cameraProvidedSensorRotationIsNotAddedTwiceAndCropIsPreserved() {
        // GLConsumer produces verticalFlip * crop * sensorRotation.
        val cropAndFlip = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        Matrix.translateM(cropAndFlip, 0, 0f, 1f, 0f)
        Matrix.scaleM(cropAndFlip, 0, 1f, -1f, 1f)
        Matrix.translateM(cropAndFlip, 0, .1f, .05f, 0f)
        Matrix.scaleM(cropAndFlip, 0, .8f, .9f, 1f)
        val cameraProvided = cropAndFlip.copyOf()
        rotate(cameraProvided, 90)
        applyCameraDisplayRotation(cameraProvided, CameraOrientation { 90 })
        val expected = cropAndFlip.copyOf().also { rotate(it, 180) }
        assertArrayEquals(expected, cameraProvided, .0001f)
    }

    @Test fun displayChangeUpdatesTheTransformWithoutReopeningTheCamera() {
        var display = 90
        val orientation = CameraOrientation { display }
        val producer = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        rotate(producer, 90)
        val first = producer.copyOf()
        applyCameraDisplayRotation(first, orientation)
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        assertArrayEquals(identity.copyOf().also { rotate(it, 180) }, first, .0001f)
        display = 270
        val reversed = producer.copyOf()
        applyCameraDisplayRotation(reversed, orientation)
        val expected = identity.copyOf()
        assertArrayEquals(expected, reversed, .0001f)
    }

    @Test fun shaderTopLeftSamplesTheCorrectCornerOfTheOnePlusUpsideDownFrame() {
        // Camera2 producer metadata: vertical flip followed by a sensor quarter-turn.
        val producer = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        Matrix.translateM(producer, 0, 0f, 1f, 0f)
        Matrix.scaleM(producer, 0, 1f, -1f, 1f)
        rotate(producer, 90)
        applyCameraDisplayRotation(producer, CameraOrientation { 90 })
        // Production quad's upper-left vertex has UV (0,0). GL raw texture Y runs upward.
        val sample = FloatArray(4)
        Matrix.multiplyMV(sample, 0, producer, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
        val rawPixel = floatArrayOf(sample[0], 1f - sample[1])
        // The reported 180° frame has its upright top-left marker in the raw bottom-right.
        assertArrayEquals(floatArrayOf(1f, 1f), rawPixel, .0001f)
    }

    private fun rotate(matrix: FloatArray, degrees: Int) {
        Matrix.translateM(matrix, 0, .5f, .5f, 0f)
        Matrix.rotateM(matrix, 0, degrees.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(matrix, 0, -.5f, -.5f, 0f)
    }
}
