package dev.mobs.render

import android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
import android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
import android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
import dev.mobs.stream.HeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCaptureProfileTest {
    @Test fun videoStabilizationUsesTheBestAdvertisedMode() {
        val off = CONTROL_VIDEO_STABILIZATION_MODE_OFF
        val on = CONTROL_VIDEO_STABILIZATION_MODE_ON
        val preview = CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        assertEquals(preview, videoStabilizationMode(true, intArrayOf(off, on, preview)))
        assertEquals(on, videoStabilizationMode(true, intArrayOf(off, on)))
        assertEquals(off, videoStabilizationMode(true, intArrayOf(off)))
        assertEquals(off, videoStabilizationMode(true, null))
        assertEquals(off, videoStabilizationMode(false, intArrayOf(off, on, preview)))
    }

    @Test fun mainCameraKeepsFullRateAndProcessingUntilSevereHeat() {
        assertEquals(CameraCaptureProfile(30, VideoProcessing.FULL), cameraCaptureProfile(inset = false, HeatMode.NORMAL, 30))
        assertEquals(CameraCaptureProfile(30, VideoProcessing.FULL), cameraCaptureProfile(inset = false, HeatMode.WARM, 30))
        assertEquals(CameraCaptureProfile(15, VideoProcessing.FULL), cameraCaptureProfile(inset = false, HeatMode.HOT, 30))
    }

    @Test fun insetDefaultsToReducedRateAndLightProcessing() {
        assertEquals(CameraCaptureProfile(INSET_FPS, VideoProcessing.LIGHT), cameraCaptureProfile(inset = true, HeatMode.NORMAL, 30))
    }

    @Test fun insetDropsFurtherAtModerateHeatBeforeItIsPaused() {
        for (mode in HeatMode.entries.filter { it > HeatMode.NORMAL }) {
            assertEquals(mode.name, CameraCaptureProfile(INSET_WARM_FPS, VideoProcessing.MINIMAL), cameraCaptureProfile(inset = true, mode, 30))
        }
    }

    @Test fun insetNeverExceedsTheOutputRate() {
        assertEquals(12, cameraCaptureProfile(inset = true, HeatMode.NORMAL, 12).fps)
        assertEquals(8, cameraCaptureProfile(inset = true, HeatMode.WARM, 8).fps)
    }
}
