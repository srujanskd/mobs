package dev.mobs.render

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test fun cameraOpenedBeforeLandscapeSettlesUsesTheNewDisplayRotation() {
        var display = 0
        val rear = CameraOrientation { display }
        val front = CameraOrientation { display }
        assertEquals(0, rear.textureRotation)
        assertEquals(0, front.textureRotation)
        display = 90
        assertEquals(90, rear.textureRotation)
        assertEquals(90, front.textureRotation)
        display = 270
        assertEquals(270, rear.textureRotation)
        assertEquals(270, front.textureRotation)
    }

    @Test fun sensorOrientationIsNotAddedAgainToTheCameraProvidedTransform() {
        assertEquals(0, CameraOrientation { 0 }.textureRotation)
        assertEquals(90, CameraOrientation { 90 }.textureRotation)
        assertEquals(270, CameraOrientation { 270 }.textureRotation)
    }
}
