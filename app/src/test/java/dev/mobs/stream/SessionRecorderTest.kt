package dev.mobs.stream

import dev.mobs.FakeStreamEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val battery = BatterySample(
        percent = 87,
        temperatureC = 36.4f,
        currentNow = -812_000,
        voltageMillivolts = 4_120,
        plugged = false,
    )

    private fun TestScope.recorder(engine: FakeStreamEngine, thermal: MutableStateFlow<ThermalState>, keep: Int = 20) =
        SessionRecorder(
            directory = folder.root,
            engine = engine,
            thermal = thermal,
            battery = { battery },
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            elapsedMs = { testScheduler.currentTime },
            nowMs = { EPOCH_MS + testScheduler.currentTime },
            keepFiles = keep,
        )

    private fun sessions() = folder.root.listFiles()!!.filter { it.name.startsWith("session-") }.sortedBy { it.name }

    @Test
    fun writesOneRowPerSecondWhileLiveAndStopsWhenTheSessionEnds() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState(level = ThermalLevel.MODERATE, headroom = 0.61f))
        recorder(engine, thermal)
        runCurrent()
        assertTrue(sessions().isEmpty())

        engine.state.value = StreamEngineState(
            streamActive = true,
            connection = ConnectionState.Reconnecting(2, "socket closed, retrying"),
            stats = StreamStats(bitrateBps = 6_400_000, targetBitrateBps = 7_000_000, outputFps = 30, droppedVideoFrames = 3),
        )
        runCurrent()
        advanceTimeBy(2_500)
        engine.state.value = engine.state.value.copy(streamActive = false)
        runCurrent()
        advanceTimeBy(5_000)

        val lines = sessions().single().readLines()
        assertEquals(SessionRecorder.CSV_HEADER, lines[0])
        assertEquals(4, lines.size) // Header plus samples at 0, 1 and 2 seconds.
        assertEquals(
            "2026-10-04T10:00:01Z,1.0,reconnecting,6400000,7000000,30,3,false,moderate,0.610,87,36.4,-812000,4120,false",
            lines[2],
        )
        assertFalse("reason text must not reach the CSV", lines.any { "socket" in it })
    }

    @Test
    fun missingReadingsLeaveEmptyFields() {
        val row = SessionRecorder.csvRow(
            EPOCH_MS,
            0,
            StreamEngineState(),
            ThermalState(),
            BatterySample(null, null, null, null, plugged = true),
        )
        assertEquals("2026-10-04T10:00:00Z,0.0,idle,0,0,0,0,false,none,,,,,,true", row)
    }

    @Test
    fun eachSessionGetsItsOwnFileAndOnlyTheNewestAreKept() = runTest {
        val engine = FakeStreamEngine()
        recorder(engine, MutableStateFlow(ThermalState()), keep = 2)
        repeat(3) {
            engine.state.value = StreamEngineState(streamActive = true)
            runCurrent()
            advanceTimeBy(1_500)
            engine.state.value = StreamEngineState(streamActive = false)
            runCurrent()
        }
        val names = sessions().map(File::getName)
        assertEquals(2, names.size)
        assertEquals("session-20261004-100001-500.csv", names[0])
        assertEquals("session-20261004-100003-000.csv", names[1])
    }

    @Test
    fun logIsCapturedFromJustBeforeTheSessionAndClosedAfterTheWindow() = runTest {
        val engine = FakeStreamEngine()
        val opened = mutableListOf<Pair<String, Long>>()
        var closed = 0
        SessionRecorder(
            directory = folder.root,
            engine = engine,
            thermal = MutableStateFlow(ThermalState()),
            battery = { battery },
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            elapsedMs = { testScheduler.currentTime },
            nowMs = { EPOCH_MS + testScheduler.currentTime },
            logCapture = { file, since ->
                opened += file.name to since
                AutoCloseable { closed++ }
            },
            logWindowMs = 5_000,
        )
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        assertEquals(listOf("session-20261004-100000-000.log" to EPOCH_MS - 2_000), opened)
        advanceTimeBy(5_001)
        assertEquals(1, closed)
        engine.state.value = StreamEngineState(streamActive = false)
        runCurrent()
        assertTrue("closing again after the window is harmless", closed >= 1)
    }

    @Test
    fun sharingPicksTheNewestSessionsWithTheirLogs() {
        listOf("100000-000.csv", "100000-000.log", "100100-000.csv", "100200-000.csv", "100200-000.log")
            .forEach { File(folder.root, "session-20261004-$it").writeText("x") }
        File(folder.root, "unrelated.txt").writeText("x")
        val names = SessionRecorder.newestSessionFiles(folder.root, sessions = 2).map(File::getName)
        assertEquals(
            listOf("session-20261004-100200-000.log", "session-20261004-100200-000.csv", "session-20261004-100100-000.csv"),
            names,
        )
    }

    @Test
    fun pruningRemovesAStaleSessionsLogWithItsCsv() = runTest {
        File(folder.root, "session-20261004-090000-000.csv").writeText("x")
        File(folder.root, "session-20261004-090000-000.log").writeText("x")
        val engine = FakeStreamEngine()
        recorder(engine, MutableStateFlow(ThermalState()), keep = 1)
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        assertEquals(listOf("session-20261004-100000-000.csv"), sessions().map(File::getName))
    }

    private companion object {
        const val EPOCH_MS = 1_791_108_000_000L // 2026-10-04T10:00:00Z
    }
}
