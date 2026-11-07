package dev.mobs.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLogTest {
    @Test fun widgetUrlsAreRemovedFromLongRunningLogs() {
        assertEquals("load <REDACTED> failed", redactLogLine("load https://widgets.example/private/token?auth=secret failed", null))
        assertEquals("{\"url\":\"<REDACTED>\"}", redactLogLine("{\"url\":\"https://widgets.example/token\"}", null))
    }

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

    @Test
    fun serverCredentialsAreRedactedInLogsAndHiddenOnScreen() {
        assertEquals("rtmp://<REDACTED>@ingest.example/app/<REDACTED>",
            redactLogLine("rtmp://user:hunter2@ingest.example/app/key", null))
        assertEquals("rtmps://ingest.example/live", displayServer("rtmps://user:hunter2@ingest.example/live"))
        assertEquals("rtmps://a.rtmps.youtube.com/live2", displayServer("rtmps://a.rtmps.youtube.com/live2"))
    }
}
