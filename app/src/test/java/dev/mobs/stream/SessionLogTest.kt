package dev.mobs.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLogTest {
    @Test
    fun streamKeyIsRedactedWhereverItAppears() {
        assertEquals(
            "RtmpClient: onStatus: <REDACTED> is now published",
            redactLogLine("RtmpClient: onStatus: abcd-1234-efgh is now published", "abcd-1234-efgh"),
        )
    }

    @Test
    fun rtmpUrlsKeepServerAndApplicationOnly() {
        assertEquals(
            "connecting to rtmps://a.rtmps.youtube.com/live2/<REDACTED> now",
            redactLogLine("connecting to rtmps://a.rtmps.youtube.com/live2/unknown-key now", null),
        )
    }

    @Test
    fun linesWithoutSecretsAreUnchanged() {
        val line = "H264Packet: waiting for a valid sps and pps"
        assertEquals(line, redactLogLine(line, "abcd-1234"))
        assertEquals(line, redactLogLine(line, ""))
    }
}
