package dev.mobs.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamDestinationTest {
    private fun valid(server: String, key: String) =
        (StreamDestination.parse(server, key) as DestinationResult.Valid).destination

    private fun problem(server: String, key: String) =
        (StreamDestination.parse(server, key) as DestinationResult.Invalid).problem

    @Test
    fun youtubeServerAndKeyFormEndpointAfterTrimming() {
        val destination = valid(" rtmps://a.rtmps.youtube.com/live2/ ", "  abcd-efgh-ijkl-mnop  ")
        assertEquals("rtmps://a.rtmps.youtube.com/live2", destination.server)
        assertEquals("rtmps://a.rtmps.youtube.com/live2/abcd-efgh-ijkl-mnop", destination.endpoint)
    }

    @Test
    fun portAndPlainRtmpAreAccepted() {
        assertEquals("rtmp://10.0.0.2:1935/live/k", valid("RTMP://10.0.0.2:1935/live", "k").endpoint)
    }

    @Test
    fun serverProblemsAreReportedBeforeKeyProblems() {
        assertEquals(DestinationProblem.SERVER_SCHEME, problem("https://a.rtmps.youtube.com/live2", ""))
        assertEquals(DestinationProblem.SERVER_SCHEME, problem("a.rtmps.youtube.com/live2", "k"))
        assertEquals(DestinationProblem.SERVER_MALFORMED, problem("rtmps://", "k"))
        assertEquals(DestinationProblem.SERVER_MALFORMED, problem("rtmps://bad host/live2", "k"))
        assertEquals(DestinationProblem.SERVER_APP_MISSING, problem("rtmps://a.rtmps.youtube.com", "k"))
        assertEquals(DestinationProblem.SERVER_APP_MISSING, problem("rtmps://a.rtmps.youtube.com/", "k"))
    }

    @Test
    fun keyMustBePresentAndUnbroken() {
        assertEquals(DestinationProblem.KEY_BLANK, problem(StreamDestination.YOUTUBE_RTMPS_SERVER, "   "))
        assertEquals(DestinationProblem.KEY_WHITESPACE, problem(StreamDestination.YOUTUBE_RTMPS_SERVER, "abcd efgh"))
    }

    @Test
    fun toStringNeverContainsTheKey() {
        val destination = valid(StreamDestination.YOUTUBE_RTMPS_SERVER, "secret-key-1234")
        assertFalse(destination.toString().contains("secret-key-1234"))
        assertFalse(DestinationResult.Valid(destination).toString().contains("secret-key-1234"))
    }
}
