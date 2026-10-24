# Changelog

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
