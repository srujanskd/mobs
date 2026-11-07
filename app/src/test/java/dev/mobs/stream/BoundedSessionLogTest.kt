package dev.mobs.stream

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BoundedSessionLogTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preservesStartupAndNewestEvidenceWithinFixedDiskBudget() {
        val file = File(folder.root, "session-fixture.log")
        BoundedSessionLog(file, 256).use { log ->
            log.write("startup")
            repeat(1000) { log.write("event-$it") }
        }
        val files = folder.root.listFiles()!!.toList()
        assertEquals(3, files.size)
        assertTrue(file.readText().startsWith("startup\n"))
        assertTrue(File(folder.root, "session-fixture.log1").readText().contains("event-999"))
        assertTrue(files.all { it.length() <= 256 })
        assertEquals(files.toSet(), SessionRecorder.newestSessionFiles(folder.root, 1).toSet())
    }

    @Test fun oversizedUnicodeLineCannotExceedSegmentBudget() {
        val file = File(folder.root, "session-fixture.context")
        BoundedSessionLog(file, 256).use { it.write("🔥".repeat(10000)) }
        assertTrue(file.length() <= 256)
    }

    @Test fun cpuParserHandlesSpacesAndParenthesesInThreadNames() {
        val fields = MutableList(25) { "0" }.apply { this[0] = "R"; this[11] = "123"; this[12] = "45" }
        assertEquals(168L, threadCpuTicks("123 (worker (video) 1) " + fields.joinToString(" ")))
        assertNull(threadCpuTicks("bad"))
        assertNull(threadCpuTicks("123 (gone) R 0"))
    }
}
