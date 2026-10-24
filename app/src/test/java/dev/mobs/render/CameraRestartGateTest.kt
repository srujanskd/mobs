package dev.mobs.render

import org.junit.Assert.*
import org.junit.Test

class CameraRestartGateTest {
    @Test fun concurrentPairMustBothCloseBeforeReopening() {
        val gate = CameraRestartGate<String>()
        var opens = 0
        gate.closing("rear"); gate.closing("front")
        gate.request { opens++ }
        gate.closed("rear")
        assertEquals(0, opens)
        gate.closed("front")
        assertEquals(1, opens)
        gate.closed("front")
        assertEquals(1, opens)
    }
    @Test fun cameraOpeningDuringRestartMustAlsoCloseBeforeTheLatestSelectionOpens() {
        val gate = CameraRestartGate<String>()
        var selected = ""
        gate.opening("old request")
        gate.request { selected = "stale" }
        gate.request { selected = "latest" }
        gate.closing("late camera")
        gate.opened("old request")
        assertEquals("", selected)
        gate.closed("late camera")
        assertEquals("latest", selected)
    }
    @Test fun cancelledRestartCannotOpenAfterShutdown() {
        val gate = CameraRestartGate<String>()
        gate.closing("rear")
        gate.request { fail("Must not open after shutdown") }
        gate.cancel()
        gate.closed("rear")
        assertFalse(gate.waiting)
    }
    @Test fun concurrentUltrawideRecoveryIsBoundedAndResetsForANewSelection() {
        val recovery = ConcurrentZoomRecovery()
        recovery.select("rear 0.6 + front")
        assertTrue(recovery.retry(true, .6f))
        assertFalse(recovery.retry(true, .6f))
        recovery.select("rear 0.6 + front")
        assertFalse(recovery.retry(true, .6f))
        recovery.select("rear 5 + front")
        assertFalse(recovery.retry(true, 5f))
        recovery.select("rear 0.6 + front")
        assertTrue(recovery.retry(true, .6f))
        assertFalse(recovery.retry(false, .6f))
    }
}
