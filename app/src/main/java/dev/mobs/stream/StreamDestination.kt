package dev.mobs.stream

import java.net.URI
import java.net.URISyntaxException

/**
 * An RTMP(S) ingest server plus the stream key appended to it. The key is a credential: it never
 * appears in [toString], logs, or [ConnectionState], which carry [server] only.
 */
class StreamDestination private constructor(val server: String, val streamKey: String) {

    /** The full `rtmps://host/app/streamKey` URL handed to the engine. */
    val endpoint: String get() = "$server/$streamKey"

    override fun equals(other: Any?) =
        other is StreamDestination && other.server == server && other.streamKey == streamKey

    override fun hashCode() = 31 * server.hashCode() + streamKey.hashCode()

    override fun toString() = "StreamDestination(server=$server, streamKey=<redacted>)"

    companion object {
        /** YouTube's documented RTMPS primary ingest. */
        const val YOUTUBE_RTMPS_SERVER = "rtmps://a.rtmps.youtube.com/live2"

        fun parse(server: String, streamKey: String): DestinationResult {
            val key = streamKey.trim()
            val normalizedServer = server.trim().trimEnd('/')
            val uri = try {
                URI(normalizedServer)
            } catch (e: URISyntaxException) {
                return DestinationResult.Invalid(DestinationProblem.SERVER_MALFORMED)
            }
            val problem = when {
                uri.scheme?.lowercase() !in SCHEMES -> DestinationProblem.SERVER_SCHEME
                uri.host.isNullOrEmpty() -> DestinationProblem.SERVER_MALFORMED
                // RTMP needs an application name, e.g. YouTube's `/live2`.
                uri.path.isNullOrEmpty() || uri.path == "/" -> DestinationProblem.SERVER_APP_MISSING
                key.isEmpty() -> DestinationProblem.KEY_BLANK
                key.any { it.isWhitespace() } -> DestinationProblem.KEY_WHITESPACE
                else -> null
            }
            if (problem != null) return DestinationResult.Invalid(problem)
            // The transport may match the scheme case-sensitively.
            val lowerScheme = uri.scheme.lowercase() + normalizedServer.substring(uri.scheme.length)
            return DestinationResult.Valid(StreamDestination(lowerScheme, key))
        }

        private val SCHEMES = setOf("rtmp", "rtmps")
    }
}

sealed interface DestinationResult {
    data class Valid(val destination: StreamDestination) : DestinationResult
    data class Invalid(val problem: DestinationProblem) : DestinationResult
}

enum class DestinationProblem {
    SERVER_SCHEME,
    SERVER_MALFORMED,
    SERVER_APP_MISSING,
    KEY_BLANK,
    KEY_WHITESPACE,
}
