# Mobs

Native Android IRL streaming app. The project currently shows a rear-camera preview at a fixed 1920x1080 30 fps landscape profile, with camera and microphone permissions, mute, and front/rear switching. A YouTube stream URL and stream key entered in the app (stored Keystore-encrypted) let it go live over RTMPS, with connection state, elapsed time, bitrate, dropped frames, backoff reconnects, and a confirmed stop. The capture session runs inside a foreground service behind a `StreamEngine` interface. Overlays, scene editing, YouTube sign-in, chat, goals, and DJI support are planned work.

The product scope and device acceptance gates are in [the Android streaming plan](docs/android-streaming-plan.md). The first media milestone is rear-camera streaming at 1080p30 with text, a goal bar, and a BRB cut that preserves the encoder session.

## Local development

Requirements: JDK 17, Android SDK platform 37, build tools 36.0.0, and platform tools. Gradle 9.7.1 is included through the wrapper and downloads on first use. Android 10 / API 29 is the initial minimum supported OS. `dev.mobs` is a provisional application ID to replace before publication.

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
- `scene`: durable source definitions, normalized placements, and scene validation.
- `render`: GPU composition and cached overlay textures.
- `stream`: media-engine adapter, service ownership, and connection state. See [its README](app/src/main/java/dev/mobs/stream/README.md).
- `youtube`: authorization, broadcasts, and chat.
- `goals`: shared goal values and updates.
- `storage`: Room, DataStore, owned media, and secret protection. Currently holds the Keystore-encrypted stream-key destination.

The package boundaries are recorded in the source directories. There are no fake engine or platform implementations. Add Room, DataStore, and OAuth dependencies when their implementations begin. RootEncoder is integrated behind `StreamEngine` and remains a candidate until the physical-device proof of concept passes; `RootEncoderStreamEngine` is the only file that imports `com.pedro.*`.

Keep camera frames out of Compose state. `StreamService` owns the media session independently of the UI, and the surface lifecycle drives preview attach and detach so the camera closes when no screen is showing it.

The service retains an active stream through connection retries even when the editor unbinds, and stops once neither an editor nor an active stream needs it. Video preparation forces hardware encoding; unsupported devices cannot fall back to a software video encoder. The permission gate offers Settings recovery after permanent denial and refreshes access on resume.

The activity is locked to a single landscape orientation while the output profile is fixed, so the encoder keeps one 1920x1080 geometry. Reverse landscape and portrait arrive with the portrait/landscape stream profiles; RootEncoder's sensor-driven orientation handling stays off until then, because it would change the output aspect ratio mid-session.

## Validation

```sh
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease
```

The GitHub Actions workflow runs the same checks and uploads the debug APK when the repository is hosted on GitHub. Release builds remain unsigned. Unit tests cover session retention during reconnects, shutdown after terminal failure, editor rebinding, ViewModel controls and Go live gating, stream URL/key validation and key redaction, reconnect backoff, and elapsed-time formatting. Add tests for scene validation and persistence, event deduplication, and broadcast state transitions as those behaviors are implemented.

The September 15, 2026 camera-preview review fixes passed `testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease`. Both lint reports have zero errors and six warnings: `OldTargetApi` because target SDK remains 36, and five `TrustAllX509TrustManager` warnings from the Bouncy Castle TLS jars that RootEncoder's RTMPS transport pulls in. Review those TLS defaults before relying on RTMPS certificate validation.

Nothing here is verified on a phone yet. That includes going live: no stream has reached YouTube from this build, and the RTMPS handshake with hostname verification enabled, reconnect behavior, and the reported bitrate and dropped-frame figures all await a device run. No device was attached when the camera preview landed, so preview output, 1080p30 capture, camera switching, mute, and the foreground service notification are all unverified on hardware. No streaming or endurance results exist yet. Pixel 7 and Galaxy S25 Ultra measurements remain required by the plan. An emulator or successful APK build cannot establish the 1080p30 or three-hour endurance gates.

Dependency versions are pinned in `gradle/libs.versions.toml`. The Gradle/JDK/SDK selection follows [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes); Compose uses the [Kotlin Compose compiler plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler). The wrapper distribution has a pinned SHA-256 checksum.

Versions checked against publisher release metadata on September 13, 2026: AGP 9.4.0, Kotlin and Compose compiler 2.4.20, Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0, and Gradle 9.7.1. RootEncoder 2.8.1, its latest release as of September 15, 2026, is not published to Maven Central, so `settings.gradle.kts` adds JitPack restricted to that group. Its Apache 2.0 license and transitive Ktor and Bouncy Castle dependencies need a license review before distribution. Compose UI, Material 3, and tooling stay aligned through the BOM rather than independent version overrides. Transitive libraries remain under their parent dependencies' version constraints.

AGP now uses [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin). The root buildscript selects Kotlin 2.4.20 explicitly, using AGP's supported version override mechanism, and the separate Kotlin Android plugin is removed. JDK 17 remains the project toolchain and the AGP-documented default. SDK build tools use AGP's default 36.0.0. Compile SDK is 37 as required by the current Compose artifacts. Target SDK remains 36, with minimum API 29.

[Kotlin's compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) lists fully supported versions through Gradle 9.7.0 and AGP 9.3.1 for Kotlin 2.4.20. The newer combination here is validated against this starter project's builds and lint; that is narrower evidence than upstream certification or device testing. Recheck compatibility when adding the media engine or other Gradle plugins.

CI action tags are pinned to checkout 7.0.1, setup-java 6.0.1, setup-android 4.0.1, setup-gradle 6.3.0, and upload-artifact 7.0.1. These actions use Node 24 on GitHub-hosted Ubuntu runners. The workflow must be run on GitHub to validate the hosted environment.

Do not commit signing keys, OAuth secrets, stream keys, or machine-specific SDK paths. Release signing and publishing are not configured.
