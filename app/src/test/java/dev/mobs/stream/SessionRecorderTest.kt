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
        assertEquals(5, lines.size) // Header, samples at 0, 1 and 2 seconds, and terminal marker.
        assertEquals(
            "2026-10-04T10:00:01Z,1.0,reconnecting,6400000,7000000,30,3,false,moderate,0.610,87,36.4,-812000,4120,false,1920,1080,30,1,true,false,false,,,,sample,0,0,normal,30,15,0,0,0,0,0.000,0.000,0,0,0,0,0,0,0,0,0,,${dev.mobs.BuildConfig.VERSION_NAME}",
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
        assertEquals("2026-10-04T10:00:00Z,0.0,idle,0,0,0,0,false,none,,,,,,true,1920,1080,30,1,true,false,false,,,,sample,0,0,normal,30,15,0,0,0,0,0.000,0.000,0,0,0,0,0,0,0,0,0,,${dev.mobs.BuildConfig.VERSION_NAME}", row)
    }

    @Test
    fun exportsHeatLimitsAndBrowserWorkWithoutWidgetUrls() {
        val widget = dev.mobs.scene.SceneSource(name = "Chat", kind = dev.mobs.scene.SourceKind.BROWSER, url = "https://example.com/private-widget")
        val state = StreamEngineState(
            heatMode = HeatMode.HOT,
            scene = dev.mobs.scene.Scene(name = "Live", sources = listOf(widget, widget.copy(id = "duplicate"), widget.copy(id = "hidden", visible = false))),
            stats = StreamStats(outputFps = 15, browserUploadFps = 5, browserUploadBytesPerSecond = 4_608_000),
        )
        val row = SessionRecorder.csvRow(EPOCH_MS, 0, state, ThermalState(level = ThermalLevel.SEVERE), battery)
        val values = SessionRecorder.CSV_HEADER.split(",").zip(row.split(",")).toMap()
        assertEquals("hot", values["heat_mode"])
        assertEquals("30", values["profile_fps"])
        assertEquals("15", values["video_fps_limit"])
        assertEquals("5", values["browser_fps_limit"])
        assertEquals("5", values["browser_upload_fps"])
        assertEquals("4608000", values["browser_upload_bytes_per_second"])
        assertEquals("1", values["browser_documents"])
        assertFalse(row.contains("private-widget"))
    }

    @Test
    fun exportsActualCaptureConfigurationAndCpuCounterWithMatchingColumns() {
        val render = dev.mobs.render.RenderStats(compositionFps = 15, meanFrameMs = 2.5, maxFrameMs = 12.0,
            rearWidth = 1920, rearHeight = 1080, frontWidth = 640, frontHeight = 360,
            rearRequestedFpsMin = 15, rearRequestedFpsMax = 15, frontRequestedFpsMin = 15, frontRequestedFpsMax = 30,
            retainedBrowserDocuments = 3)
        val row = SessionRecorder.csvRow(EPOCH_MS, 0, StreamEngineState(stats = StreamStats(render = render)),
            ThermalState(), battery, MemorySample(processCpuMs = 12345))
        val columns = SessionRecorder.CSV_HEADER.split(",")
        assertEquals(columns.size, row.split(",").size)
        val values = columns.zip(row.split(",")).toMap()
        assertEquals("15", values["composition_fps"])
        assertEquals("2.500", values["composition_mean_ms"])
        assertEquals("12.000", values["composition_max_ms"])
        assertEquals("1920", values["rear_input_width"])
        assertEquals("640", values["front_input_width"])
        assertEquals("360", values["front_input_height"])
        assertEquals("30", values["front_requested_fps_max"])
        assertEquals("3", values["browser_retained_documents"])
        assertEquals("12345", values["process_cpu_ms"])
        assertEquals(dev.mobs.BuildConfig.VERSION_NAME, values["app_version"])
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
    fun logRemainsOpenPastTheOverheatingPointAndClosesWithTheSession() = runTest {
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
        )
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        assertEquals(listOf("session-20261004-100000-000.log" to EPOCH_MS - 2_000), opened)
        advanceTimeBy(26 * 60_000)
        assertEquals("Runtime logs must survive the 25-minute overheating point", 0, closed)
        engine.state.value = StreamEngineState(streamActive = false)
        runCurrent()
        assertEquals(1, closed)
    }

    @Test
    fun eachSessionGetsFreshContextAndExportsItWithRotatedLogs() = runTest {
        val engine = FakeStreamEngine()
        var starts = 0
        SessionRecorder(folder.root, engine, MutableStateFlow(ThermalState()), { battery }, backgroundScope,
            StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, { EPOCH_MS + testScheduler.currentTime },
            diagnostics = {
                starts++
                var first = true
                val sample: (StreamEngineState, ThermalState) -> String? = { state, _ ->
                    if (first) { first = false; "start-context" } else if (!state.streamActive) "stop-context" else null
                }
                sample
            })
        repeat(2) {
            engine.state.value = StreamEngineState(streamActive = true)
            runCurrent()
            advanceTimeBy(1_500)
            engine.stopStream()
            runCurrent()
        }
        assertEquals(2, starts)
        val contextFiles = sessions().filter { it.extension == "context" }
        assertEquals(2, contextFiles.size)
        assertTrue(contextFiles.all { it.readLines() == listOf("start-context", "stop-context") })
        val latest = contextFiles.maxBy { it.name }
        File(folder.root, latest.nameWithoutExtension + ".log1").writeText("tail")
        File(folder.root, latest.nameWithoutExtension + ".context2").writeText("prior context")
        val shared = SessionRecorder.newestSessionFiles(folder.root, 1)
        assertEquals(setOf("csv", "context", "context2", "log1"), shared.map { it.extension }.toSet())
    }

    @Test
    fun contextLogFailureKeepsTheCsvAndItsEndMarker() = runTest {
        // A directory where the context file belongs makes opening it fail.
        File(folder.root, "session-20261004-100000-000.context").mkdirs()
        val engine = FakeStreamEngine()
        val failures = mutableListOf<String>()
        SessionRecorder(folder.root, engine, MutableStateFlow(ThermalState()), { battery }, backgroundScope,
            StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, { EPOCH_MS + testScheduler.currentTime },
            onFailure = failures::add,
            diagnostics = { { _: StreamEngineState, _: ThermalState -> "context" } })
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        advanceTimeBy(2_500)
        engine.stopStream()
        runCurrent()
        assertEquals(1, failures.size)
        val rows = File(folder.root, "session-20261004-100000-000.csv").readLines()
        assertTrue(rows.size >= 4)
        assertEquals("end", rows.last().split(",")[25])
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

    @Test
    fun storageFailureDoesNotStopTheStreamAndANewSessionCanRecordAfterRecovery() = runTest {
        val blocked = File(folder.root, "blocked").apply { writeText("not a directory") }
        val engine = FakeStreamEngine()
        val failures = mutableListOf<String>()
        SessionRecorder(blocked, engine, MutableStateFlow(ThermalState()), { battery }, backgroundScope,
            StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, { EPOCH_MS + testScheduler.currentTime },
            onFailure = failures::add)
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        assertEquals(1, failures.size)
        assertTrue(engine.state.value.streamActive)
        assertEquals(0, engine.stopped)
        engine.state.value = StreamEngineState()
        runCurrent()
        blocked.delete()
        blocked.mkdirs()
        advanceTimeBy(1_000)
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        engine.stopStream("fixture terminal failure")
        runCurrent()
        val rows = blocked.listFiles()!!.single().readLines()
        assertEquals("end", rows.last().split(",")[25])
        assertEquals("failed", rows.last().split(",")[2])
    }

    private companion object {
        const val EPOCH_MS = 1_791_108_000_000L // 2026-10-04T10:00:00Z
    }
}
