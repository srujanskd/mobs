# Performance and stability checks

The suite catches measured regressions. A passing JVM simulation or emulator test does not establish camera cadence, dual-camera support, power use, audio sync, or phone endurance. Physical-device baseline results have not been recorded yet. The budgets are provisional engineering limits and must be reviewed against repeated phone measurements.

## Idle web overlay work

Browser capture now follows WebView invalidations, retains a 250 ms animation grace period, and uses a one-second idle safety refresh. A static 320×180 page produced 61 snapshots in a two-second emulator baseline and 2 after the change. The full-resolution animated JavaScript test still captured 30 fps with 43 distinct colors in 1.5 seconds and one reused bitmap. Tests also cover CSS animation starting after idle, a delayed JavaScript update, inactive-page reactivation, scene persistence, and a held upload during resizing and close. These are software-capture work counts, not measurements of battery savings or phone temperature.

Compare the same phone, scene/widget URLs, brightness, network, ambient temperature and APK build type for at least 30 minutes using the session-run commands below. Include a quiet chat period and active scrolling/animations. Compare battery drain, thermal status, camera/output cadence and received playback. Keep the broadcast at 1080p30 and do not infer whole-app power savings from the snapshot reduction. The independent app-closure report remains unresolved until crash/process-exit evidence is available.

## Checks on every change

```sh
source scripts/android-env.sh
python3 -m unittest discover -s tests/performance -v
./gradlew testDebugUnitTest assembleDebug assembleRelease :app:assembleBenchmark :benchmarks:assembleBenchmark lintDebug lintRelease
./gradlew :app:connectedDebugAndroidTest
```

GitHub Actions builds all three app variants and the separate benchmark APK, runs JVM and Python tests, and runs device correctness tests on an API 35 emulator. Reports are uploaded even when checks fail. Configure the Android build and device-test jobs as required branch checks in the hosting repository. This workspace cannot enforce repository branch protection without hosting permissions.

Coverage includes source geometry fuzzing, collection/source limits, camera visibility rules, scene duplication and round-trip persistence, corrupted/versioned storage, 500 durable edits, 10,000 separate reconnect outages, and a three-hour virtual-time session with scene changes and reconnects. Device tests inspect actual EGL output pixels for layer placement, painter order, opacity, empty scenes, repeated scene cuts, and renderer thread cleanup. Compose tests exercise adding a source, dragging, and the separate Save and Apply actions.

Virtual time makes policy endurance fast and deterministic. It does not run the real camera or transport. Hardware video encoding remains mandatory, so the Android emulator may reject the app's streaming profile while still running the editor and compositor correctness tests.

## Startup performance

Use an unlocked, idle physical phone at stable temperature. The Macrobenchmark module targets the optimized, non-debuggable, profileable `benchmark` variant and performs ten cold starts with a fixed permission state and no ahead-of-time compilation. Do not disable Macrobenchmark's device or thermal validation.

```sh
./gradlew :benchmarks:connectedBenchmarkAndroidTest
python3 scripts/performance/check_startup.py path/to/benchmarkData.json --output performance-results/startup.json
python3 scripts/performance/check_startup.py new/benchmarkData.json --baseline accepted/benchmarkData.json
```

Startup currently gates median initial display at 1500 ms and p95 at 2000 ms, plus a maximum 10% regression against an explicitly supplied same-device/OS baseline. Initial display measures the first activity frame, not camera readiness. JSON and Perfetto traces are retained by Macrobenchmark. The manual physical-device workflow requires a dedicated runner with the `android-performance` label. It is intentionally separate from emulator CI. No physical runner is provisioned by this change.

## Streaming and memory runs

Build/install the benchmark APK. Choose the stream destination on the phone. The test tools do not accept, print, or store stream keys. Record one session per run directory, and stop it normally after the measurement.

```sh
./gradlew :app:assembleBenchmark
adb -s PHONE_SERIAL install -r app/build/outputs/apk/benchmark/app-benchmark.apk
python3 scripts/performance/device_run.py begin performance-results/rear-01 --serial PHONE_SERIAL --case rear --network wifi --brightness-pct 50 --ambient-c 23 --apk app/build/outputs/apk/benchmark/app-benchmark.apk
# Start the stream on the phone, run at least 30 minutes, and stop it normally.
python3 scripts/performance/device_run.py end performance-results/rear-01 --serial PHONE_SERIAL
python3 scripts/performance/check_session.py performance-results/rear-01/session.csv --manifest performance-results/rear-01/manifest.json --output performance-results/rear-01/result.json
```

Session CSVs include output profile/cadence, each camera's fresh-frame rate, source count, scene errors, Java/native heap and RSS, connection state, transport drops, thermal readings, battery readings, and a clean-stop marker. Missing measurements, missing stop markers, recorder gaps, nonfinite numbers, counter resets, and short captures fail closed. A recorder storage failure is shown in the app and must not crash the broadcast. A session terminated by a thermal or transport failure cannot pass.

After 30 seconds of warm-up, the default gates require mean output and active-camera cadence of at least 29 fps, output p5 at least 28 fps, no severe thermal readings, at most 1% disconnected samples and transport drops, and at most 64 MiB RSS growth between the first and last post-warm-up minute. RSS is process resident memory, not proof that every GPU/driver allocation is included. Transport drops are not raw camera frame drops. CPU submission timing, GPU completion, audio/video sync, network latency, and received playback quality still require Perfetto and receiver-side inspection.

For a relative comparison, supply both manifests and CSVs:

```sh
python3 scripts/performance/check_session.py new/session.csv --manifest new/manifest.json --baseline-csv accepted/session.csv --baseline-manifest accepted/manifest.json
```

The checker rejects mismatched device/OS, serial, case, network, brightness, or ambient temperatures differing by more than 2 C, and checks CSV hashes. Relative limits are 2% mean-fps loss, 3% p5-fps loss, and 16 MiB extra memory growth. Keep accepted runs under review; the tools never overwrite a baseline automatically. Repeat at least three matched runs per case, alternate baseline/candidate order, and report variance. These initial tolerances permit measurement noise; they cannot promise zero performance change.

Run rear only, front only, static sources, front + rear, and a representative full scene separately. Also run repeated scene cuts while live, preview off/on, editor departure/return, Wi-Fi/cellular changes, a 10-second outage, denied/revoked camera access, and camera interruption by another app. A failure in one camera must produce a visible scene error, not silently count duplicated frames as successful capture. Check both camera orientations and mirrored front-camera framing on each supported phone.

For the three-hour battery gate, use `--endurance`. It requires at least 10,800 seconds, no external power, 100% starting charge, and at least 10% reserve. Record cellular signal, ambient conditions, battery health, brightness, scene contents, and receiver results. This automates only the measurable CSV portion of the release gate. Required chat and goal sources are still pending; a current scene cannot satisfy the final full-feature release gate. Run Galaxy S25 Ultra and Pixel 7 separately, and dual-camera endurance as a separate case. No device acceptance gate is closed by adding these tests.

## Budgets and maintenance

Budgets live in `scripts/performance/budgets.json`. Change them only with reviewed device evidence and a reason. Add scene-source tests whenever a source kind is introduced. Add receiver-side checks when the local ingest fixture is implemented. The current device suite does not open a YouTube broadcast or deliberately disrupt a user's network.

The benchmark setup follows [Android's Macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview). Concurrent capture follows [Android's concurrent camera sequence](https://source.android.com/docs/core/camera/concurrent-streaming), including opening both camera IDs before configuring either capture session.

## Physical camera smoke checks

On a connected, unlocked physical phone, run:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mobs.render.CameraSceneDeviceTest -Pandroid.testInstrumentationRunnerArguments.physicalCameras=true
```

These opt-in checks open a visible test activity, verify fresh rear-camera frames, cut through a static scene and back, and verify fresh frames from both advertised concurrent cameras. They do not publish video. The dual check is skipped on unsupported phones. A fresh-frame smoke test is separate from the strict 30-minute cadence/performance and three-hour battery gates. Default CI skips these physical capture checks.

## Local verification on October 24, 2026

Passed 54 JVM tests, 10 Python gate tests, and four editor/compositor tests on the API 37 emulator. The two physical-camera checks are excluded on emulators. Debug, optimized benchmark, unsigned release, and the standalone Macrobenchmark APK built successfully. Debug and release lint each reported zero errors and eight warnings, covering the existing target SDK, newer build-tool versions, and transitive Bouncy Castle trust-manager warnings. The hosted workflows and physical-phone benchmarks have not run.

Visual inspection confirmed that the landscape editor fits its scene controls, draft canvas, and source panel. The emulator correctly refuses hardware 1080p30 streaming and does not advertise the required dual-camera pair. These are capability checks, not physical capture or endurance results.

## Pinch zoom and automatic video processing

JVM regressions cover accumulating gesture samples against current engine state, both zoom limits, invalid input, saving only at gesture end, stale scene/source callbacks, hidden/transparent sources, and 10,000 samples without intermediate disk writes. The workload is a correctness stress test, not a hardware timing benchmark.

`PreviewPinchZoomDeviceTest` sends Android multi-pointer events to the same touch listener installed on the preview. It checks zoom in/out, cancellation, subsequent gestures, single-finger input, absent cameras, and binding a gesture to its original camera callback. `CameraVideoProcessingDeviceTest` checks real Camera2 record-request builders against advertised automatic exposure/white-balance/focus and FAST processing modes, preserving defaults for unsupported modes. These can run on an emulator; they do not prove physical image quality.

```sh
source scripts/android-env.sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mobs.editor.PreviewPinchZoomDeviceTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mobs.render.CameraVideoProcessingDeviceTest
```

`CameraZoomPerformanceDeviceTest` is an opt-in physical-phone diagnostic. It separately exercises rear, front, and supported concurrent cameras at 1080p30. Cameras without zoom above 1× are skipped. After confirming 1× and warming up for 30 seconds, it requests a continuous 20-second zoom in/out ramp at approximately 30 updates per second, up to 2× or the advertised maximum. It requires at least 500 submitted updates, a camera-reported peak within 0.1× of the ramp maximum, and confirmation of the final 1× zoom. It checks errors, mean delivered output and active-camera cadence against the existing 29 fps budget, and a provisional 500 ms maximum output stall. Reports include actual update count, output frame rate, per-camera frame rate, and maximum output gap. This diagnostic uses the debug renderer with an ImageReader consumer; it does not measure end-to-end touch latency, encoded output, power, thermal endurance, memory growth, optical transition quality, or stock-camera color parity. Use the optimized session and endurance runs above for release performance acceptance.

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mobs.render.CameraZoomPerformanceDeviceTest -Pandroid.testInstrumentationRunnerArguments.physicalCameras=true
adb exec-out run-as dev.mobs cat files/zoom-performance-REAR_CAMERA-false.json > rear-zoom.json
adb exec-out run-as dev.mobs cat files/zoom-performance-FRONT_CAMERA-false.json > front-zoom.json
adb exec-out run-as dev.mobs cat files/zoom-performance-REAR_CAMERA-true.json > dual-zoom.json
```

No physical zoom performance results have been recorded yet. A skipped test is not acceptance. Run under stable lighting and temperature; retain separate reports for repeated runs. Dual-camera ultrawide transitions still use explicit lens buttons and may restart capture. The ramp diagnostic stays at 1× and above and does not test those transitions. Evaluate visible smoothness and color on recorded footage under daylight, indoor, mixed-light, and low-light conditions, including lens switches and exposure/white-balance changes. Automated mode checks cannot judge whether those colors look good.

Local verification on October 25, 2026: all 72 JVM tests and 10 Python performance-checker tests passed. The four new pinch-event tests and the Camera2 processing-mode test passed in separate runs on the API 37 emulator. Debug and instrumentation APKs compiled successfully. These results do not establish physical zoom performance or visual image quality.

For v0.2.0 release validation, all 17 emulator regressions subsequently passed in one full API 37 run. Physical-device-only tests were excluded. Debug/release lint passed with zero errors and 15 warnings each. The release and optimized benchmark APKs built successfully; the installable APK has version code 3 and retains the v0.1 signing certificate.


## Camera comparison feedback, October 27, 2026

Camera quality and colors were reported as worse than IRL Pro, but the comparison is unconfirmed. No matched physical-phone footage is available in this checkout. Keep this open until both apps are recorded on the same phone, lens, zoom, resolution, frame rate, codec and bitrate, under the same lighting and framing. Compare daylight, indoor/mixed light, low light and motion after autofocus, exposure and white balance settle. Compare decoded recordings as well as previews to distinguish capture/color differences from compression or display differences.

Mobs currently requests automatic exposure and white balance, continuous video focus where available, and supported FAST processing modes through the Camera2 record template. The default encoded output is 1920×1080 at 30 fps and 10 Mbps before adaptation. In concurrent-camera scenes the front input is 1280×720, so an enlarged front source can be softer than a single-camera view. These are configuration facts, not evidence of parity or a diagnosed cause. Retain the settings, phone/OS versions and paired clips before changing color processing or bitrate defaults.


## Browser overlay capture, October 27, 2026

The old 100 ms snapshot loop captured an animated fixture at 9.3 fps in a 1.5-second API 37 emulator sample. Browser capture now targets the stream frame rate through Choreographer and uses the overlay's output pixel dimensions instead of the fixed 960×540 base with 50% page scale. It reuses one bitmap and updates existing GPU texture storage between size changes.

`BrowserSourceDeviceTest` exercises a changing JavaScript page at 1920×1080, requires at least 24 captures per second with fresh pixel changes, and checks one reused bitmap. A separate test holds an upload through resize and shutdown to verify bounded work and safe buffer lifetime. Transparency, off-scene updates and close behavior remain covered. The local 1.5-second sample captured 46 frames with 44 distinct colors and one bitmap. This is a short synthetic software-capture test, not sustained physical-device or encoded-stream performance.

```sh
source scripts/android-env.sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mobs.render.BrowserSourceDeviceTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mobs.render.SceneRendererDeviceTest
```

Validate the actual chat widget on a physical phone alongside the camera and encoder. Include animated message arrivals and scrolling, multiple visible URLs, resizing, scene cuts, and a sustained thermal run. Full-resolution software capture processes four times the pixels of the former half-resolution capture; higher cadence also increases work. Complex animated pages may still need a hardware-rendered browser composition path. A 30 fps capture target does not establish that a particular page animates at 30 fps or that camera/encoder cadence stays within budget.


## Heat protection, October 30, 2026

Runtime heat policy now acts before critical temperature. Moderate Android thermal status caps browser snapshots at 15 fps and encoder bitrate at 6 Mbps. Severe status suspends and dims preview, caps browser snapshots at 5 fps, caps composition/GL output at 15 fps and bitrate at 4 Mbps, and requests a supported lower camera capture range in place. The camera request falls back to the supported profile range when no lower range is advertised. Capture counters remain actual fresh frames, not the requested cap. Saved scenes, picture size, microphone/audio and transport session are retained; camera sources are not removed.

Critical status stops streaming immediately. Sixty continuous seconds at severe or higher also stops streaming, even if critical is never reported. Cooldown prevents preview attachment and stream start. Runtime limits restore only after 30 continuous seconds at light status or below, checked at one-second intervals. A stopped stream never restarts automatically. The editor explains the limits and provides Stop while preview is suspended. The service owns the policy when the editor is unbound.

The 60-second severe timeout, 30-second recovery delay and bitrate/frame-rate caps are initial application policy, not vendor temperature thresholds or a measured guarantee of safe case temperature. No new battery-temperature threshold is inferred from the OnePlus sample, and the discrepant headroom forecast does not override Android thermal status. Match physical runs to tune these limits. This runtime policy supersedes earlier statements that all lower thermal levels leave the stream unchanged.

RTMP packet-level logging is disabled. The existing browser idle-capture optimization remains enabled. New session columns append heat mode, video/browser fps limits, actual aggregate browser texture uploads per second, estimated upload payload bytes per second, and the number of distinct active browser documents. URLs are not exported. Upload bytes are RGBA bitmap payload, not measured hardware memory bandwidth. Requested output profile stays 1080p30 in the CSV, with separate runtime limits and measured output counters.

The full-quality release gate is unchanged: a run with severe status or sustained reduced cadence still fails. Passing a heat-policy unit test does not satisfy that gate. Compare v0.2.1 and the candidate on the same OnePlus, starting cool with matched brightness, ambient temperature, network and scene. Include rear-only, dual-camera, real widget on/off and preview on/off as separate matched cases, and repeat runs. Check receiver audio/video sync and delivered cadence through thermal transitions, including reconnects. Confirm sustained severe heat ends cleanly, source resources close, cooling recovery restores preview/cadence, and the user must explicitly restart after a thermal stop. Retain CSVs, complete text logs, thermal-service sensor readings and receiver recordings. No physical-phone thermal acceptance has been established by the local changes.


Local verification for this policy: 88 JVM tests and 10 Python performance-checker tests passed. All six `BrowserSourceDeviceTest` tests and all five `SceneRendererDeviceTest` tests passed on the API 37 emulator in separate class runs. The browser rate-limit test kept JavaScript updates alive at the 5 fps cap and restored normal cadence; the compositor test reduced output to 15 fps and restored normal cadence without replacing its output surface. An idle page produced two snapshots in two seconds; the animated 1920x1080 fixture produced 30 fps with one reused bitmap in its 1.5-second sample. Debug lint reported zero errors and 17 warnings. The optimized, non-debuggable benchmark APK built successfully. These short emulator checks establish behavior and bounded capture work, not phone power consumption, thermal safety, sustained physical-camera cadence or receiver audio/video sync.


For v0.2.2 release validation, all 33 default API 37 emulator regressions passed in one full run, including crash diagnostic sharing and editor controls. The 88 JVM tests and 10 Python performance-checker tests passed. Debug/release lint each reported zero errors and 17 warnings. Debug, unsigned release, optimized installable and standalone benchmark test APKs built successfully. The installable APK has version code 5 and the same signing certificate as v0.2.1. These checks do not establish physical-phone thermal acceptance.
