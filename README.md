# Mobs

Native Android IRL streaming app with an OBS-style scene creator. Create and save multiple scenes, add rear/front cameras, text, owned images, color layers, and HTTPS web overlays, drag and resize sources, reorder layers, change opacity/visibility, and lock placement. Save keeps a draft; Apply changes output, and the camera screen has direct scene-switch buttons. Supported phones can capture front and rear simultaneously into the same 1920x1080 stream. Concurrent-camera capability and exact session configurations are checked at runtime; phone capture, orientation, power, and endurance acceptance remain unverified.

Camera/microphone capture and RTMPS run in a foreground service behind `StreamEngine`. Stream setup uses a Keystore-encrypted destination, with mute, connection state, bitrate adaptation, reconnects, preview-off mode, thermal protection, and confirmed stop. The scene compositor sends surface textures to the existing hardware encoder and keeps the audio/transport session across scene cuts. The draft editor uses labeled camera tiles; Apply and the main camera preview show the actual composed output. YouTube sign-in, native chat, goal sources, DJI, crop controls, and scene audio policies remain future work.

The product scope and device acceptance gates are in [the Android streaming plan](docs/android-streaming-plan.md). The current milestone is general scene composition and device-validated dual capture at 1080p30. A goal source and full-feature phone acceptance remain open.

## Studio controls

The landscape studio uses a full-screen preview with floating scene chips, icon controls with long-press labels, microphone control, and a separate Go live button. System bars stay hidden until swiped in. The frame icon switches between filling the display and fitting the entire encoded output; filling crops only the preview on wider displays. The refresh icon beside connection status reloads all saved scenes and restarts the active scene's cameras, web widgets, and overlay textures. It keeps the stream and microphone state, and leaves unapplied drafts off the output. Open the camera control to switch front/rear, choose a lens/zoom level, or mirror a camera. Mirroring defaults off and changes preview and broadcast together. Each camera source saves its own mirror and zoom settings. Camera rotation preserves the sensor orientation already supplied in Camera2's SurfaceTexture matrix and adds only current display compensation. Supported cameras disable Android's additional automatic rotate/crop. Single-camera lens changes cross through 1× when switching between ultrawide and telephoto and reset the previous crop. In dual mode, crossing the ultrawide boundary reopens both cameras, waits for asynchronous close callbacks, and retries an unconfirmed ultrawide request once with fresh sessions. Android only guarantees 1× through maximum digital zoom during concurrent operation; the app reports a refusal if the phone still cannot confirm ultrawide. The live lens highlight follows capture-result zoom rather than the requested scene setting, with pending and failed states when the camera has not confirmed the request. Lens presets come from the selected camera's reported zoom range. Android selects optical lenses where supported; some presets use digital crop, and Android 10 supports crop zoom only.

In the scene editor, choose **Add source → Chat / web link** and paste an HTTPS widget URL from Streamlabs, StreamElements, or another provider. Resize and position it like any other source, then **Apply to output**. Web pages render with transparency at up to 10 fps and are included in encoded output. The draft canvas shows a placeholder; the output preview shows the live page. Browser audio, video, and WebGL widgets are not supported by this bitmap overlay path. Widget links are stored locally with scenes and can contain private tokens. Scene storage v3 reads existing v1/v2 scenes without removing sources. Camera controls include a saved 0°/90°/180°/270° rotation correction for each source, applied to preview and output.

## IRL Pro research handoff

The IRL Pro 3.5.22 investigation was transferred into this project on October 21, 2026. Start with [findings applied to Mobs](docs/irlpro-handoff.md), which connects the recovered media, adaptation, protocol, bonding, UI/storage, TLS, and runtime behavior to the existing app. The [research bundle](docs/research/irlpro-3.5.22/README.md) preserves all six original reports, complete evidence, original analysis helpers, and a compressed snapshot of decompiled resources/source, smali, and native libraries with SHA-256 verification. It does not require keeping the original research workspace to read the findings or inspect archived artifacts.

Mobs remains the implementation project. The October 24 implementation adds scene composition and simultaneous front/back capture, following the user's request to make dual capture an essential scene-editor feature. This is separate from the documentation transfer. Device capability, output quality, and power acceptance remain open. Existing scene, goal, chat, and YouTube work remains required; optional SRT/SRTLA and recording work is described in the handoff.

## Local development

Requirements: JDK 17, Android SDK platform 37, build tools 36.0.0, and platform tools. Gradle 9.8.0 is included through the wrapper and downloads on first use; the Gradle daemon itself runs on JDK 25, which the foojay toolchain resolver downloads if it is missing (`gradle/gradle-daemon-jvm.properties`). Android 10 / API 29 is the initial minimum supported OS. `dev.mobs` is a provisional application ID to replace before publication.

On macOS with Homebrew:

```sh
brew install openjdk@17 android-commandlinetools
source scripts/android-env.sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease
```

Source `scripts/android-env.sh` in each new terminal on this Mac. It discovers Homebrew's JDK 17 and defaults the SDK to `~/Library/Android/sdk`. On other operating systems, set `JAVA_HOME` and `ANDROID_HOME` to your existing installations and invoke the wrapper directly, using `gradlew.bat` on Windows.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With USB debugging enabled and a phone connected:

```sh
adb devices
./gradlew installDebug
adb shell am start -n dev.mobs/.MainActivity
```

For Android Studio, open this directory, select JDK 17 as the Gradle JDK, and use SDK platform 37. Android Studio is optional and is not installed by the commands above. A local `local.properties` file can set `sdk.dir`; it is ignored by Git.

## Code layout

Start with packages in the existing `app` module. Introduce each package's implementation when its milestone begins:

- `editor`: Compose screens and controls. Contains the permission gate, the camera screen with stream setup and Go live / Stop, its ViewModel, and the service binding.
- `scene`: bounded source/scene models, normalized placements, versioned atomic storage, and owned image imports.
- `render`: GPU composition and cached overlay textures. Currently the text overlay rasterizer and placement math; see [its README](app/src/main/java/dev/mobs/render/README.md).
- `stream`: media-engine adapter, service ownership, and connection state. See [its README](app/src/main/java/dev/mobs/stream/README.md).
- `youtube`: authorization, broadcasts, and chat.
- `goals`: shared goal values and updates.
- `storage`: Room, DataStore, owned media, and secret protection. Currently holds the Keystore-encrypted stream-key destination.

The package boundaries are recorded in the source directories. There are no fake engine or platform implementations. Add Room, DataStore, and OAuth dependencies when their implementations begin. RootEncoder is integrated behind `StreamEngine` and remains a candidate until the physical-device proof of concept passes; `RootEncoderStreamEngine` is the only file that imports `com.pedro.*`.

Keep camera frames out of Compose state. `StreamService` owns the media session independently of the UI, and the surface lifecycle drives preview attach and detach so the camera closes when no screen is showing it.

The service retains an active stream through connection retries even when the editor unbinds, and stops once neither an editor nor an active stream needs it. Video preparation forces hardware encoding; unsupported devices cannot fall back to a software video encoder. The permission gate offers Settings recovery after permanent denial and refreshes access on resume.

The activity is locked to a single landscape orientation while the output profile is fixed, so the encoder keeps one 1920x1080 geometry. Reverse landscape and portrait arrive with the portrait/landscape stream profiles; RootEncoder's sensor-driven orientation handling stays off until then, because it would change the output aspect ratio mid-session.

## Scene editor and regression suite

Open **Scenes** to edit a draft, add sources, and drag or resize them using the selection corner. Sources at the top of the list draw above others. Save a draft, then use **Apply to output** or a scene button on the camera screen to switch. Dual capture requires Android 11+ and a reported front/rear concurrent pair with supported capture sizes. Unsupported pairs are explained in the app. Current dual capture requests 1080p rear and 720p front into fixed 1080p30 output; secondary capture size is explicit and is not evidence of native 1080p from both lenses. Camera-free scenes release both cameras while keeping static output paced.

The [performance and stability guide](docs/performance-and-stability.md) lists automated coverage, startup benchmarks, streaming budgets, phone-run commands, and remaining device gates. The suite includes scene persistence and geometry stress tests, a virtual three-hour session, EGL pixel checks, and Compose editor tests. CI runs correctness tests; measured performance gates use physical phones and reviewed baselines. No physical-device baseline or new dual-camera acceptance result exists yet.

## Validation

```sh
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease
```

The GitHub Actions workflow runs the same checks and uploads the debug APK when the repository is hosted on GitHub. Release builds remain unsigned. For on-device power and thermal runs, `./gradlew assembleBenchmark` builds `app/build/outputs/apk/benchmark/app-benchmark.apk`: the release configuration (R8, not debuggable) signed with the debug key, and `profileable` so Android Studio's Power Profiler and Perfetto can attach. It installs over a debug build with `adb install -r`. Measure with this build, not debug. It starts cleanly on the emulator (October 4, 2026); streaming with the R8 build is not yet checked on a phone. Unit tests cover session retention during reconnects, the thermal stop and Go Live refusal at critical temperature, session CSV recording and pruning, shutdown after terminal failure, editor rebinding, ViewModel controls and Go live gating, stream URL/key validation and key redaction, reconnect backoff, elapsed-time formatting, overlay placement, and text overlay updates. Add tests for scene validation and persistence, event deduplication, and broadcast state transitions as those behaviors are implemented.

The September 15, 2026 camera-preview review fixes passed `testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease`. Both lint reports have zero errors and six warnings: `OldTargetApi` because target SDK remains 36, and five `TrustAllX509TrustManager` warnings from Bouncy Castle jars that RootEncoder depends on. Reviewed October 4, 2026: RTMPS uses RootEncoder's Java socket, which passes no custom TrustManager to `SSLContext.init`, so the platform validates the certificate chain, and with host verification enabled it sets the HTTPS endpoint identification algorithm before the handshake. The engine pins `SocketType.JAVA` explicitly. Bouncy Castle is only reachable from the Ktor socket path, and R8 removes every Bouncy Castle and Ktor class from release and benchmark builds; the flagged trust managers are present only in debug builds, unused.

First Pixel 7 run, October 4, 2026: the app prepared 1080p30 with a hardware encoder and a stream reached YouTube over RTMPS. Two problems showed up within about five minutes: the phone ran hot, and YouTube reported it was not receiving enough video at the fixed 10 Mbps. Bitrate adaptation was added in response and has not yet been run on the phone; the heat is unmeasured. The display also slept during that run, so the screen is now kept on while live, which is likewise unverified. On a OnePlus 13 (Snapdragon 8 Elite), October 4, 2026, the first stream after the app starts stays on "Preparing stream" in YouTube Studio while the app reports Connected at about 10 Mbps and 30 fps with no drops; ending and restarting the stream works. The session CSVs show the only difference is a slower encoder start (1 frame encoded while connecting, against 8 on the working retry). Not reproduced on the emulator with a software encoder and a local RTMP server; under investigation using the per-session logs. The Android emulator has no hardware H.264 encoder and correctly reports 1080p30 as unsupported. Thermal status and headroom are now shown on screen, a critical thermal state stops the stream cleanly, and each live session writes a per-second CSV for the power runs (see the [stream README](app/src/main/java/dev/mobs/stream/README.md)); "Preview off" detaches the preview while live (encoding continues) and drops the window to minimum brightness; the preview surface also asks the display to refresh at a multiple of 30 Hz. A 15 fps reduced preview is not possible without modifying RootEncoder, whose renderer draws the preview in the same pass as each encoded frame and whose only fps limit also slows the encoder; it stays open pending measurements of preview-off savings. None of this has run on a phone yet. Still unverified on hardware: reconnect behavior, the text overlay in YouTube playback, audio/video sync, camera switching while live, behavior when backgrounded or with the screen off, and 30 minutes of continuous streaming. No streaming or endurance results exist yet. Pixel 7 and Galaxy S25 Ultra measurements remain required by the plan. An emulator or successful APK build cannot establish the 1080p30 or three-hour endurance gates.

Dependency versions are pinned in `gradle/libs.versions.toml`. The Gradle/JDK/SDK selection follows [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes); Compose uses the [Kotlin Compose compiler plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler). The wrapper distribution has a pinned SHA-256 checksum.

Versions checked against publisher release metadata on September 13, 2026, then updated by Android Studio's upgrade assistant on October 4, 2026 to AGP 9.4.1 and Gradle 9.8.0: AGP 9.4.1, Kotlin and Compose compiler 2.4.20, Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0, and Gradle 9.8.0. RootEncoder 2.8.1, its latest release as of September 15, 2026, is not published to Maven Central, so `settings.gradle.kts` adds JitPack restricted to that group. Its Apache 2.0 license and transitive Ktor and Bouncy Castle dependencies need a license review before distribution. Compose UI, Material 3, and tooling stay aligned through the BOM rather than independent version overrides. Transitive libraries remain under their parent dependencies' version constraints.

AGP now uses [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin). The root buildscript selects Kotlin 2.4.20 explicitly, using AGP's supported version override mechanism, and the separate Kotlin Android plugin is removed. JDK 17 remains the project toolchain and the AGP-documented default. SDK build tools use AGP's default 36.0.0. Compile SDK is 37 as required by the current Compose artifacts. Target SDK remains 36, with minimum API 29.

[Kotlin's compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) lists fully supported versions through Gradle 9.7.0 and AGP 9.3.1 for Kotlin 2.4.20. The newer combination here is validated against this starter project's builds and lint; that is narrower evidence than upstream certification or device testing. Recheck compatibility when adding the media engine or other Gradle plugins.

CI action tags are pinned to checkout 7.0.1, setup-java 6.0.1, setup-android 4.0.1, setup-gradle 6.3.0, and upload-artifact 7.0.1. These actions use Node 24 on GitHub-hosted Ubuntu runners. The workflow must be run on GitHub to validate the hosted environment.

Do not commit signing keys, OAuth secrets, stream keys, or machine-specific SDK paths. Release signing and publishing are not configured.
