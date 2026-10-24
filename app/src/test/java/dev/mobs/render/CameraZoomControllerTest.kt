package dev.mobs.render

import org.junit.Assert.*
import org.junit.Test

class CameraZoomControllerTest {
    @Test fun telephotoToUltrawideWaitsForMainLensBeforeSendingUltrawideRequest() {
        val zoom = CameraZoomController()
        val tele = zoom.request(5f)
        zoom.observe(tele, 5f)
        assertTrue(zoom.state.confirmed)
        val bridge = zoom.request(.6f)
        assertEquals(1f, bridge.ratio)
        assertEquals(5f, zoom.state.actual)
        assertFalse(zoom.state.confirmed)
        assertNull(zoom.observe(tele, 5f))
        val wide = zoom.observe(bridge, 1f)!!
        assertEquals(.6f, wide.ratio)
        assertEquals(1f, zoom.state.actual)
        assertFalse(zoom.state.confirmed)
        zoom.observe(wide, .6f)
        assertTrue(zoom.state.confirmed)
        assertEquals(.6f, zoom.state.actual)
    }

    @Test fun ignoredZoomIsRetriedAndReportedAsFailedInsteadOfConfirmed() {
        val zoom = CameraZoomController()
        var command = zoom.request(.6f)
        repeat(15) { zoom.observe(command, 1f)?.let { command = it } }
        repeat(45) { zoom.observe(command, 1f) }
        assertTrue(zoom.state.failed)
        assertFalse(zoom.state.confirmed)
        assertEquals(1f, zoom.state.actual)
    }

    @Test fun rapidSelectionsDiscardOldResultsAndCrossBothWays() {
        val zoom = CameraZoomController()
        val wide = zoom.request(.6f)
        zoom.observe(wide, .6f)
        val bridge = zoom.request(5f)
        assertEquals(1f, bridge.ratio)
        val latest = zoom.request(2f)
        zoom.observe(bridge, 1f)
        assertEquals(.6f, zoom.state.actual)
        val tele = zoom.observe(latest, 1f)!!
        assertEquals(2f, tele.ratio)
        zoom.observe(tele, 2f)
        assertTrue(zoom.state.confirmed)
    }
}
