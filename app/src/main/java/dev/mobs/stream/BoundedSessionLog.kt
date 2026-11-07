package dev.mobs.stream

import java.io.File
import java.io.IOException

/** Preserve the beginning plus the two newest segments. All extensions share a session stem. */
internal class BoundedSessionLog(private val file: File, private val maxBytes: Int = 512 * 1024) : AutoCloseable {
    private var output = file.outputStream().buffered()
    private var bytes = 0
    private var initial = true
    private val tail = File(file.parentFile, file.name + "1")
    private val previous = File(file.parentFile, file.name + "2")

    init { require(maxBytes > 0) }

    fun write(line: String) {
        // A corrupt vendor line cannot grow the buffer without bound.
        val data = (line.take(maxBytes / 4 - 1) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes + data.size > maxBytes) {
            output.close()
            if (!initial) {
                if (previous.exists() && !previous.delete()) throw IOException("Cannot rotate diagnostics")
                if (!tail.renameTo(previous)) throw IOException("Cannot rotate diagnostics")
            }
            initial = false
            output = tail.outputStream().buffered()
            bytes = 0
        }
        output.write(data)
        bytes += data.size
        output.flush()
    }

    override fun close() = output.close()
}

/** Linux /proc stat has a parenthesized comm field which can itself contain spaces or ')'. */
internal fun threadCpuTicks(stat: String): Long? {
    val end = stat.lastIndexOf(')')
    if (end < 0) return null
    val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
    // Remaining index 0 is field 3 (state); utime/stime are fields 14/15.
    val user = fields.getOrNull(11)?.toLongOrNull() ?: return null
    val system = fields.getOrNull(12)?.toLongOrNull() ?: return null
    return (user + system).takeIf { user >= 0 && system >= 0 && it >= 0 }
}
