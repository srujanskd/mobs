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


## OnePlus streaming workload fixes, first candidate, November 3, 2026

The reported setup alternates between dual cameras with chat, rear camera with logo/chat, and front camera with logo/chat. The local October 29 CSV still fails the thermal gate with a severe fraction of 0.2999. It describes an older build and cannot establish this candidate's temperature or power use.

The current candidate limits browser capture to 15 fps normally and 10 fps at moderate heat. Severe/cooldown limits remain 5/1 fps. This changes animation smoothness in web widgets; normal camera and encoded output still request 1080p30. Quiet widgets retain the one-second refresh. Browser callbacks are delayed until near their capture deadline instead of firing on every display refresh. The camera watchdog and statistics clock run once per second while cameras supply output frames; camera-free scenes retain the video output clock.

Visible sources using the exact same widget URL share one document across scene cuts. An off-scene document now expires after five seconds, checked once per second, and is destroyed immediately when thermal limits are active. Its JavaScript and connections no longer remain alive for the entire session. Returning to an expired document reloads the widget and may reset its transient chat history. A quick normal-temperature scene cut still retains the document. Repeated edits to another scene cannot extend the expiry deadline.

Camera thermal requests now prefer a genuinely capped supported range at or below the desired cadence before a variable range such as 15..30. For example, a phone advertising 10..10 and 15..30 now selects 10..10 under the 15 fps thermal target. A phone advertising only 30..30 still needs the existing compositor limits and thermal stop; the app cannot invent a sensor rate. This is not a claim about the OnePlus 15's advertised ranges.

A forced WebView renderer crash previously terminated the host app. The regression uses Android's documented chrome://crash fixture; the handler now retires the failed widget, reports an error and allows a replacement document to render. Scene URL validation continues to accept only HTTPS. This follows the [WebView renderer-exit contract](https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)). Connection-failure UI state also uses the existing stream-key redactor.

Targeted checks reproduced the inactive-document retention and camera-range bugs before their fixes. On the API 37 emulator, the updated scene test produced 30 browser uploads and 58 output frames over two seconds. The separate full-resolution browser fixture configured explicitly at 30 fps still produced 30 fps, 45 distinct colors and one reused bitmap over 1.5 seconds. The idle fixture produced two captures in two seconds. The intentional renderer-crash test failed before the handler and passed afterward. These are bounded-work and correctness measurements, not physical battery or temperature measurements.

For phone validation, install the optimized 0.2.3-dev candidate and compare matched 30-minute runs against v0.2.2 using the commands above. Reuse her actual three scenes and exact widget URLs, and record which scene is active. Start each run cool with the same brightness, network and ambient conditions. Include a quiet-chat period, active animations, repeated scene cuts and preview off/on. Export diagnostics and inspect receiver audio/video sync. Do not treat emulator results as proof that overheating is fixed on the OnePlus 15.

Final candidate correctness checks: 90 JVM tests, all 37 default API 37 emulator tests, and 10 Python performance-checker tests passed. Physical-camera opt-in acceptance and streaming endurance were not run.

Debug, unsigned release, optimized installable and benchmark test APK builds passed. Debug/release lint each reported zero errors and the existing 17 warnings. The optimized candidate passed signature and alignment verification and installed/launched on the API 37 emulator. Application ID is dev.mobs, version code 6, version name 0.2.3-dev, minimum API 29. The installable artifact is build/mobs-v0.2.3-dev.apk.


## Scene continuity follow-up, November 3, 2026

The 0.2.3-dev2 candidate replaces the first candidate's browser expiry. The user explicitly requested that web overlays continue across scene changes. Documents now stay loaded by exact widget URL for the compositor session, including hidden intervals and thermal mode changes. Hidden widgets stop Choreographer callbacks, bitmap drawing and GL uploads, and release capture buffers and GPU textures. Their JavaScript and network connections continue so chat can keep receiving messages. This is an intentional background-work exception; it can use more memory and CPU than destroying hidden pages. No per-scene copies are created for the same URL.

Camera inputs absent from the new scene close through Camera2; old image/text/color textures are deleted. Shared cameras still needed by the new scene remain active. Scene activation uses update, not the explicit source-refresh path. Explicit refresh, renderer failure recovery and capture-session shutdown can still reload a web page.

The regression first failed against the previous candidate: a long hidden interval caused a second document load. It now checks that hidden intervals beyond five seconds, repeated scene updates and moderate heat preserve a single document with no hidden snapshots; explicit refresh must still create a replacement. The Camera2 regression also checks device availability and two zero-capture samples after switching to a camera-free scene, then verifies fresh frames when returning. Emulator Camera2 checks do not establish OnePlus dual-camera thermal performance.

Follow-up validation passed: 90 JVM tests, all 37 default API 37 emulator tests, debug/release/benchmark builds and debug/release lint. Lint retains zero errors and 17 existing warnings per variant. The signed, aligned optimized APK installed and launched successfully on the emulator. The replacement artifact is build/mobs-v0.2.3-dev2.apk, version code 7.

## November 4 overheating investigation

The supplied 0.2.3-dev2 session lasted 1520.4 seconds, with 1504.3 seconds of dual-camera operation. Battery temperature rose from 35.3 C to 42.7 C and charge fell from 65% to 57%. Android reported light heat at 106.6 seconds, moderate at 431.0, and severe at 1476.2. It ended normally; the supplied process-exit history contains no November 4 exit. This does not explain prior closure reports.

During moderate heat, rear/front capture averaged 29.73/29.72 fps, output 29.91 fps, browser uploads 0.96 fps, and the preview remained attached. Bitrate was capped at 6 Mbps. Severe heat successfully detached preview and reduced rear/front capture to about 15.7 fps and output to 15.38 fps over its short final interval. The existing controls worked, but the expensive camera/video work was left unchanged throughout the preceding moderate period. Mean browser upload payload was about 342 kB/s over the session. RSS growth measured by the checker was 9.97 MiB; this trace does not show runaway browser uploads or process-memory growth.

The October 29 session spent 1799.7 of 1813.8 seconds with both cameras and also reached severe heat. The October 27 session used both cameras for only 102.5 of 2106.7 seconds and stayed at light heat. These runs were not matched for starting temperature, ambient conditions, scenes, network, brightness, or build. They implicate sustained dual-camera workload but do not isolate sensor, ISP, GPU, display, CPU or radio power.

The original feedback command was:

```sh
python3 scripts/performance/check_session.py /Users/srujankashyapd/Downloads/session-20261104-134728-377.csv
```

It reports `passed: false`, a severe fraction of 0.0303, and a duration below 1800 seconds. That immutable old capture cannot turn green after a code change. The new service-policy replay uses the recorded thermal transition times and checks that moderate status reduces the camera/compositor target before severe heat. It failed against the old policy with expected 15, actual 30. Camera sizing tests also failed against the original fixed-size behavior. These tests establish workload controls, not the phone's thermal response.

The dev3 candidate requests 15 fps at moderate heat, through the existing camera repeating request, compositor pacing and RootEncoder FPS limit. Normal operation still targets 30 fps. Runtime input sizing now selects the smallest advertised same-aspect size covering the source after center crop and scene rotation, with a 640×360 floor and the original capture size as its ceiling. For an unrotated 32%-width/height inset on a 1080p canvas, an advertised 640×360 input replaces 1280×720, reducing its delivered input pixels by 75%. This is a pixel-count reduction, not a measured ISP or battery saving. A larger source restores the required size. The encoder surface and web documents survive this camera reconfiguration. A smaller concurrent pair is checked with Android's [configuration query](https://developer.android.com/reference/android/hardware/camera2/CameraManager#isConcurrentSessionConfigurationSupported(java.util.Map)); rejection retries the original sizes before opening devices.

The preview uses a [fixed-size SurfaceView buffer](https://developer.android.com/reference/android/view/SurfaceHolder#setFixedSize(int,int)) fitted within 1280×720 and preserving display aspect. The compositor skips destination blending for fully opaque camera sources. Encoded resolution, source geometry, audio and saved scenes are retained.

Inspection of RootEncoder 2.8.1 confirms an additional full-resolution source-to-texture pass between the Mobs compositor and the encoder/preview. It remains in this candidate. Removing that pass requires replacing or modifying the renderer integration and validating orientation, buffer ownership, encoder timestamps and preview lifecycle. These captures contain no GPU timing to establish how much it contributes. There is no evidence here sufficient to claim that pass is the sole cause of overheating.

New CSV columns persist for the entire session: `composition_fps`, `composition_mean_ms`, `composition_max_ms`, each camera's actual configured input dimensions and accepted request's FPS range, `browser_retained_documents`, `process_cpu_ms`, and `app_version`. Composition times measure CPU submission plus swap waits, not completed GPU work. CPU time is cumulative for the app process, excludes separate WebView processes and hardware units, and must be differenced against elapsed time. A requested Camera2 range is not proof of actual sensor cadence; retain the existing fresh-frame counters. The old `browser_documents` column still counts visible URLs, while the new retained count includes hidden loaded documents. No widget URLs or stream keys are added.

The supplied text logs only cover the first minute. Continuous CSV measurements are therefore needed to distinguish expensive CPU work, slow compositor submissions, high camera rates, and retained web pages near the thermal transition without a connected computer. The user cannot connect the OnePlus for profiling. A new optimized APK run with the same scenes, starting cool and unplugged, must still establish whether temperature stabilizes and receiver playback stays usable. Stop on the phone's heat warning. Export the new session even if it fails early. Do not label reduced cadence a full-quality 1080p30 pass; do not weaken the existing acceptance checker.

Final local validation for dev3: 98 JVM tests, all 38 default API 37 emulator tests, and 10 Python checker tests passed. The new Camera2 test initially skipped because the emulator lacked two 16:9 sizes; it was changed to use two advertised same-aspect sizes and then passed real inset/enlargement/reduction transitions. The final full device run has zero failures or skips. Physical-camera opt-in tests and phone endurance remain unrun. Debug, release, optimized installable, and benchmark test APK builds passed. Debug/release lint have zero errors and the existing 17 warnings each. The optimized APK passed signature/alignment checks, matches dev2's certificate, and installed/launched on the emulator. Artifact: `build/mobs-v0.2.3-dev3.apk`, version code 8, SHA-256 `6f0f0b27beb6e222136f12d4203d4b4a2dbe2b3e0a911ad8df6c8392f1d13546`.

## Diagnostics completeness follow-up, dev4

The dev3 CSV covered the entire broadcast, but its text log still stopped at one minute and its CPU counter only measured the process total. Dev4 removes that cutoff. The filtered own-process logcat reader runs until stop and writes at most 100 lines per second, with suppression markers when a subsequent interval starts. It captures warning/error messages plus renderer and selected encoder status tags. The original `.log` segment is retained; `.log1` and `.log2` contain the newest two segments. Each segment is capped at 512 KiB. Startup and recent context therefore survive a long or noisy run without unlimited text-log growth. Rotated files use the same session stem and are included in sharing and pruning.

A `.context` JSON-lines file and its two rolling segments have the same independent size limit. The first record identifies manufacturer/model, Android/build, app/build type, CPU count, camera FPS ranges and output-size capabilities. Scene records use session-local numbers and source kind/visibility/bounds/rotation/zoom, omitting names, text contents and widget URLs. State changes sampled by the one-second recorder include thermal status, heat mode, preview state, connection state, and redacted failure reasons. Transitions shorter than a sample interval can be missed. Every ten seconds, context includes app-thread cumulative CPU ticks and thread start ticks, the clock-tick rate, network transport/validation/metering and estimated upstream capability, power-save/interactivity, system brightness setting, display size/refresh and GL vendor/renderer. Thread reads may be unavailable or a thread may exit during sampling; missing data is not recorded as zero CPU. Separate WebView-process CPU and hardware-unit power remain unavailable. Camera size/rate, composition time, actual frame counters, memory, temperature, battery and bitrate continue in the full-session CSV.

The diagnostics factory resets per session so subsequent runs retain their own startup context. Existing export includes all segments plus crash/process-exit reports. No USB, extra runtime permission prompt, or manual logcat collection is needed. The normal ACCESS_NETWORK_STATE manifest permission permits reading transport metadata; no SSID, IP address, phone identifier or location is collected. Stream keys and HTTP(S) widget URLs are redacted before writing.

These additions address identifiable gaps in the supplied capture. They do not guarantee that a single phone run will expose every cause. GPU submission/swap duration is not GPU completion time; brightness is a system setting rather than measured luminance; network bandwidth capability is an estimate rather than signal strength or throughput. Root cause may still require receiver evidence or privileged/vendor profiling. Test dev4 rather than dev3 so a failure after 25 minutes has recent runtime logs and workload attribution.

Dev4 validation: 103 JVM tests, all 40 default API 37 emulator tests, and 10 Python performance-checker tests passed. Coverage includes a virtual 26-minute logging lifetime, fresh metadata per session, bounded rotation and export grouping, CPU-stat parsing, real own-process logcat capture/redaction/closure, and actual thread/device/context collection. Debug/release/benchmark builds passed; debug/release lint retain zero errors and 17 existing warnings each. The optimized APK passed signature and alignment checks, matches dev3's certificate, and installed/launched on the emulator. Artifact: `build/mobs-v0.2.3-dev4.apk`, version code 9, SHA-256 `07a5a4bb6845bc24e484d4160053dc097233070010dafb6970f4b942c1d0384c`. Physical OnePlus thermal acceptance remains unverified.


## November 5 dev4 run and inset pause, dev5

The dev4 session (OnePlus CPH2745, Android 16, cellular, unplugged) ran 2296 seconds with both cameras for the whole run. Battery temperature rose from 31.5 C to 43.3 C. Thermal status reached light at about 7 minutes and moderate at about 9 minutes. Moderate heat then cut output, camera requests (15..15) and encoder input to 15 fps. App-process CPU fell from about 41% to 26% of one core, and reported battery current fell from about 580 to 410. Despite this, temperature rose 4.2 C over the 15 minutes after moderate began. That matches the dev2 run of November 4, which kept 30 fps through moderate heat. Thread samples put app CPU at about 30% of one core across eight cores, so app-thread CPU is not the main load.

The single-camera October 27 run at the same 1080p30, about 10 Mbps, preview and chat settled at 37.7–38.6 C at light heat for 33 minutes. Its brief dual-camera interval showed the highest current reading in that run. These runs are not matched for ambient conditions, starting temperature or network. Still, concurrent rear+front capture is the only workload difference that consistently separates stable runs from runs that keep heating. Lower cadence on the same pair did not change the heating rate.

Dev5 therefore pauses the inset camera at moderate heat instead of cutting cadence. The smaller visible camera of a two-camera scene is closed. Its layer is frozen from the last frame through an offscreen framebuffer and labelled. The main camera reopens alone at its single-camera size, causing a brief camera restart like a scene cut. Moderate heat keeps 30 fps output, 10 fps browser snapshots and the 6 Mbps bitrate cap. The inset resumes after five continuous minutes of normal heat mode; this hold starts after the existing 30-second recovery. Single-camera equilibrium sits near light heat, so a shorter hold would cycle the inset every few minutes. Severe and critical behavior is unchanged. Preview stays attached at moderate heat by the user's choice.

`InsetCameraPauseTest` covers inset selection for both reported dual layouts, single-camera scenes, immediate pause, the full resume hold, hold restart after reheating, and no flapping under a simulated light/moderate cycle. `CameraRestartDeviceTest.warmModeFreezesTheInsetAndOpensOnlyTheMainCamera` freezes a live rear frame into the inset on the API 37 emulator. It verifies that only the front camera delivers frames, the rear device closes, the frozen pixels are non-black, and normal heat does not resume the inset during the hold. The emulator cannot open a concurrent pair. The physical pair-to-single transition and the resulting temperature curve on the OnePlus are not yet measured. Repeat the same scenes, starting cool and unplugged, and compare the curve after the moderate transition with the dev4 run.
