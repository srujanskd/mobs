# Mobs

Native Android IRL streaming app. The project currently contains a buildable Kotlin and Jetpack Compose starter screen. Camera capture, broadcasting, scene editing, storage, YouTube authorization, chat, goals, and DJI support are planned work.

The product scope and device acceptance gates are in [the Android streaming plan](docs/android-streaming-plan.md). The first media milestone is rear-camera streaming at 1080p30 with text, a goal bar, and a BRB cut that preserves the encoder session.

## Local development

Requirements: JDK 17, Android SDK platform 37, build tools 36.0.0, and platform tools. Gradle 9.7.1 is included through the wrapper and downloads on first use. Android 10 / API 29 is the initial minimum supported OS. `dev.mobs` is a provisional application ID to replace before publication.

On macOS with Homebrew:

```sh
brew install openjdk@17 android-commandlinetools
source scripts/android-env.sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew assembleDebug assembleRelease lintDebug lintRelease
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

- `editor`: Compose screens and controls. Contains the starter screen.
- `scene`: durable source definitions, normalized placements, and scene validation.
- `render`: GPU composition and cached overlay textures.
- `stream`: media-engine adapter, service ownership, and connection state.
- `youtube`: authorization, broadcasts, and chat.
- `goals`: shared goal values and updates.
- `storage`: Room, DataStore, owned media, and secret protection.

The package boundaries are recorded in the source directories. There are no fake engine or platform implementations. Add Room, DataStore, OAuth, and media dependencies when their implementations begin. RootEncoder remains a candidate until the physical-device proof of concept passes.

Keep camera frames out of Compose state. The stream service will own the media session independently of the UI. Introduce camera/microphone permissions and foreground service declarations together with their runtime permission and lifecycle handling.

## Validation

```sh
./gradlew assembleDebug assembleRelease lintDebug lintRelease
```

The GitHub Actions workflow runs the same checks and uploads the debug APK when the repository is hosted on GitHub. Release builds remain unsigned. No automated behavior tests exist yet because this setup contains only a static screen. Add tests for scene validation and persistence, event deduplication, and broadcast state transitions as those behaviors are implemented.

The September 13, 2026 dependency update passed `clean assembleDebug assembleRelease lintDebug lintRelease`. Both lint reports have zero errors and one `OldTargetApi` warning because target SDK remains 36. No dependency-update warnings remain.

No streaming or endurance results exist yet. Pixel 7 and Galaxy S25 Ultra measurements remain required by the plan. An emulator or successful APK build cannot establish the 1080p30 or three-hour endurance gates.

Dependency versions are pinned in `gradle/libs.versions.toml`. The Gradle/JDK/SDK selection follows [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes); Compose uses the [Kotlin Compose compiler plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler). The wrapper distribution has a pinned SHA-256 checksum.

Versions checked against publisher release metadata on September 13, 2026: AGP 9.4.0, Kotlin and Compose compiler 2.4.20, Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0, and Gradle 9.7.1. Compose UI, Material 3, and tooling stay aligned through the BOM rather than independent version overrides. Transitive libraries remain under their parent dependencies' version constraints.

AGP now uses [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin). The root buildscript selects Kotlin 2.4.20 explicitly, using AGP's supported version override mechanism, and the separate Kotlin Android plugin is removed. JDK 17 remains the project toolchain and the AGP-documented default. SDK build tools use AGP's default 36.0.0. Compile SDK is 37 as required by the current Compose artifacts. Target SDK remains 36, with minimum API 29.

[Kotlin's compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) lists fully supported versions through Gradle 9.7.0 and AGP 9.3.1 for Kotlin 2.4.20. The newer combination here is validated against this starter project's builds and lint; that is narrower evidence than upstream certification or device testing. Recheck compatibility when adding the media engine or other Gradle plugins.

CI action tags are pinned to checkout 7.0.1, setup-java 6.0.1, setup-android 4.0.1, setup-gradle 6.3.0, and upload-artifact 7.0.1. These actions use Node 24 on GitHub-hosted Ubuntu runners. The workflow must be run on GitHub to validate the hosted environment.

Do not commit signing keys, OAuth secrets, stream keys, or machine-specific SDK paths. Release signing and publishing are not configured.
