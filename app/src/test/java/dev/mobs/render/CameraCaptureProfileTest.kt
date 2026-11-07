package dev.mobs.render

import dev.mobs.stream.HeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCaptureProfileTest {
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
