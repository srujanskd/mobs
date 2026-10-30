# Changelog

## 0.2.2

October 30, 2026.

- Add automatic heat management. Moderate heat reduces browser snapshots to 15 fps and caps encoder bitrate at 6 Mbps. Severe heat suspends and dims preview, limits video to 15 fps and browser snapshots to 5 fps, caps bitrate at 4 Mbps, and requests lower camera cadence where supported.
- Stop cleanly at critical heat or after one continuous minute at severe heat. Require 30 continuous seconds at light heat or below before restoring normal operation. A stopped stream requires an explicit restart.
- Reuse unchanged browser overlays instead of redrawing and uploading them at full cadence. Keep JavaScript updates, animation, scene persistence and borrowed-buffer lifetime covered by regressions.
- Disable packet-by-packet RTMP logging. Export heat mode, runtime cadence limits, actual browser upload rate and payload size, and active browser document count in session CSVs.
- Include private crash reports and Android process-exit history in shared diagnostics. Omit exception messages and raw native traces to protect stream keys and widget links; prepare exports off the main thread.

### Installation

Download `mobs-v0.2.2.apk`, the optimized installable APK. Android 10 or newer is required. Version code is 5. The signing certificate matches v0.2.1 and earlier testing releases, supporting in-place upgrades. This remains a testing release.

### Known limits

- Heat protection deliberately reduces smoothness and bitrate, and can stop a broadcast. Saved scenes, picture dimensions and audio settings are retained. The editor explains active limits and provides a Stop control while preview is suspended.
- Sustained temperature improvement, physical-camera cadence and receiver audio/video sync through thermal transitions still require matched tests on the target phone. The policy thresholds are initial application limits, not measured device acceptance.
- Browser capture uses a software WebView. Animated widgets can still be expensive; off-scene documents retain JavaScript and live connections.

### Validation

- 88 JVM tests, all 33 API 37 emulator regressions, and 10 Python performance-checker tests passed.
- Debug, release, optimized installable, and benchmark test APKs built successfully.
- Debug and release lint passed with zero errors and 17 warnings each.
- APK signature, alignment, application ID, minimum SDK and version metadata verified. Signing certificate matches v0.2.1.
- Physical-phone thermal and endurance acceptance has not been established for this patch.

## 0.2.1

October 27, 2026.

- Preserve camera proportions with a centered crop when resizing scene sources or applying rotation and mirroring, fixing stretched camera insets.
- Keep browser widget documents and chat history alive across scene cuts, duplicated sources, visibility changes, and resizing. Explicit refresh still reloads widgets.
- Capture browser overlays at their output resolution and target the stream's 30 fps cadence. Reuse one bitmap and GPU texture storage; pause snapshots while a widget is off scene.
- Add camera transform, browser buffer lifetime, capture cadence, and scene persistence regressions.

### Installation

Download `mobs-v0.2.1.apk`, the optimized installable APK. It uses the same local Android testing key as previous releases for in-place upgrades. Android 10 or newer is required. This is a testing release.

### Known limits

- Browser capture uses a software WebView on the main thread. Higher resolution and cadence increase work; complex widgets, multiple simultaneous pages, and sustained camera/encoder performance still need physical-phone validation.
- Off-scene browser documents retain their live connections and JavaScript until an explicit refresh or preview shutdown.
- Physical-device camera quality, zoom cadence, and streaming endurance remain unverified.

### Validation

- All 72 JVM tests, 28 API 37 emulator regressions, and 10 Python performance-checker tests passed.
- Debug, release, optimized installable, and benchmark test APKs built successfully.
- Debug and release lint passed with zero errors and 15 warnings each.
- APK signature, alignment, application ID, minimum SDK, and version metadata verified. The signing certificate matches v0.2.0.
- Physical-device quality and sustained performance have not been validated for this patch.

## 0.2.0

October 25, 2026.

- Pinch the live preview to zoom continuously across the camera's supported range. Zoom applies to preview and broadcast, with the final value saved when the gesture ends.
- Automatic exposure, white balance, continuous video autofocus where supported, and FAST noise reduction, edge processing, tone mapping, and color-fringe correction. Unsupported controls retain the camera template defaults.
- New regressions for Android pinch events, cancellation, zoom limits, stale callbacks, 10,000 gesture updates without intermediate disk writes, and advertised camera processing modes.
- New opt-in physical-phone diagnostics for rear, front, and concurrent-camera zoom cadence, output stalls, and camera-reported zoom.

### Installation

Download `mobs-v0.2.0.apk`, the optimized APK of approximately 4 MB. It uses the same local Android testing key as v0.1 for in-place upgrades. Android 10 or newer is required. This is a testing release.

### Known limits

- Pinch controls the first visible camera source. In dual-camera mode, pinch starts at 1× or above; choose ultrawide with the lens buttons, which may restart both cameras.
- Optical lens changes and perceived smoothness depend on the phone. Some zoom levels use digital crop.
- Physical zoom cadence, color quality, and streaming endurance remain unverified. Camera mode tests and emulator tests do not establish visual quality or hardware performance.

### Validation

- 72 JVM tests and 10 Python performance-checker tests passed.
- All 17 emulator regressions passed on API 37, including pinch events and camera processing.
- Debug, release, and optimized installable APKs built successfully; debug and release lint passed with no errors.
- APK signature, alignment, application ID, minimum SDK, and version metadata verified.
- Physical zoom-performance diagnostics are included but have not run on a phone.

## 0.1

October 24, 2026.

First testing release of Mobs, a native Android streaming studio.

- Full-screen landscape preview with floating controls, fit/fill preview modes, accessible icon labels, and scene switching.
- Saved scene drafts with cameras, text, owned images, color layers, and transparent HTTPS chat/web widgets. Move, resize, reorder, hide, lock, and adjust source opacity before applying changes to output.
- Front and rear camera sources, concurrent capture on supported phones, lens/zoom presets, optional mirroring, and saved rotation correction per camera.
- Hardware-confirmed zoom selection, staged optical transitions, and a bounded concurrent-camera restart for ultrawide recovery. Refresh reloads scenes and rebuilds active sources without stopping audio or transport.
- RTMPS streaming with an encrypted destination, mute controls, reconnects, adaptive bitrate, thermal protection, preview-off mode, and redacted session logs.
- Scene persistence migrations, compositor and camera lifecycle regressions, performance budgets, benchmark tooling, and the existing IRL Pro research handoff.

### Installation

The installable local APK uses the existing Android debug signing key to preserve upgrade compatibility with earlier test builds. This is a local testing release, not an app-store-signed production build. Minimum Android version is Android 10.

### Known limits

- Android does not guarantee the full optical zoom range during concurrent front/rear capture. If ultrawide is refused after recovery, use a single rear-camera scene.
- Camera controls provide per-source rotation correction for devices needing calibration. Changes affect preview and stream together.
- Output is fixed at 1920×1080, 30 fps. Physical-device endurance, power, and full streaming acceptance remain open.
- YouTube sign-in, native chat, and goal sources are not included. HTTPS browser widgets can supply scene chat overlays.

### Validation

- 68 Android unit tests, 12 emulator tests, and 10 performance-gate tests passed.
- Debug, release, app benchmark, and benchmark test APKs built successfully.
- Debug and release lint completed with no errors.
- APK signature, alignment, application ID, minimum SDK, and version metadata verified.
