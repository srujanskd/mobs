package dev.mobs.render

import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceBounds
import dev.mobs.scene.SourceKind
import dev.mobs.stream.HeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsetCameraPauseTest {
    private val full = SourceBounds()
    private val inset = SourceBounds(.74f, .68f, .26f, .32f)
    private fun camera(kind: SourceKind, bounds: SourceBounds, visible: Boolean = true) =
        SceneSource(name = kind.name, kind = kind, bounds = bounds, visible = visible)
    private val chat = SceneSource(name = "Chat", kind = SourceKind.BROWSER, url = "https://example.com/chat", bounds = SourceBounds(0f, 0f, .3f, .5f))

    @Test fun selectsTheSmallerCameraInBothLayoutsOfTheReportedScenes() {
        // Scene 1 and scene 2 from the November 5 OnePlus context export.
        assertEquals(SourceKind.FRONT_CAMERA, insetCamera(Scene(name = "1", sources = listOf(
            camera(SourceKind.REAR_CAMERA, full), camera(SourceKind.FRONT_CAMERA, inset), chat))))
        assertEquals(SourceKind.REAR_CAMERA, insetCamera(Scene(name = "2", sources = listOf(
            camera(SourceKind.FRONT_CAMERA, full), camera(SourceKind.REAR_CAMERA, inset), chat))))
    }

    @Test fun singleCameraScenesHaveNoInset() {
        assertNull(insetCamera(Scene(name = "Rear", sources = listOf(camera(SourceKind.REAR_CAMERA, full), chat))))
        assertNull(insetCamera(Scene(name = "Hidden front", sources = listOf(
            camera(SourceKind.REAR_CAMERA, full), camera(SourceKind.FRONT_CAMERA, inset, visible = false)))))
    }

    @Test fun pausesImmediatelyAtModerateHeatAndAtEveryHotterMode() {
        for (mode in HeatMode.entries.filter { it > HeatMode.NORMAL }) {
            assertTrue(mode.name, InsetCameraPause().update(mode, 0))
        }
        assertFalse(InsetCameraPause().update(HeatMode.NORMAL, 0))
    }

    @Test fun resumesOnlyAfterTheFullHoldOfContinuousNormalHeat() {
        val pause = InsetCameraPause(holdMs = 300_000)
        assertTrue(pause.update(HeatMode.WARM, 0))
        assertTrue(pause.update(HeatMode.NORMAL, 10_000))
        assertTrue(pause.update(HeatMode.NORMAL, 309_999))
        assertFalse(pause.update(HeatMode.NORMAL, 310_000))
    }

    @Test fun reheatingDuringTheHoldRestartsIt() {
        val pause = InsetCameraPause(holdMs = 300_000)
        pause.update(HeatMode.WARM, 0)
        pause.update(HeatMode.NORMAL, 1_000)
        pause.update(HeatMode.WARM, 200_000)
        pause.update(HeatMode.NORMAL, 250_000)
        assertTrue("Hold must restart after reheating", pause.update(HeatMode.NORMAL, 549_999))
        assertFalse(pause.update(HeatMode.NORMAL, 550_000))
    }

    @Test fun thirtySecondHeatRecoveryAloneDoesNotFlapTheInset() {
        // Simulates the single-camera equilibrium: light heat recovers NORMAL after 30 s,
        // then the phone reaches moderate again a few minutes after dual capture would resume.
        val pause = InsetCameraPause()
        var resumes = 0
        var paused = pause.update(HeatMode.WARM, 0)
        for (t in 1_000L..3_600_000L step 1_000L) {
            val mode = if ((t / 1_000) % 240 < 120) HeatMode.WARM else HeatMode.NORMAL
            val next = pause.update(mode, t)
            if (paused && !next) resumes++
            paused = next
        }
        assertEquals(0, resumes)
    }
}
