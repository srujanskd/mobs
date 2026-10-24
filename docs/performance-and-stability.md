# Performance and stability checks

The suite catches measured regressions. A passing JVM simulation or emulator test does not establish camera cadence, dual-camera support, power use, audio sync, or phone endurance. Physical-device baseline results have not been recorded yet. The budgets are provisional engineering limits and must be reviewed against repeated phone measurements.

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
