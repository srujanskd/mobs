# Runtime observations

These observations use the supplied, unmodified IRL Pro 3.5.22 APK in the existing `Medium_Phone_API_37.0` arm64 Android emulator. It ran headless with `-read-only -no-snapshot-save -no-audio` on ADB port 5580. Wi-Fi and cellular data were disabled before installation and app launch. Camera input was the emulator's synthetic camera, not a physical camera. Runtime permissions were granted only within this temporary emulator session.

## Observed behavior

1. Installation succeeded. Cold launch displayed the camera permission request and then the IRL Pro welcome dialog.
2. After permission grants and welcome dismissal, the landscape camera preview rendered synthetic imagery. The UI exposed settings, camera controls, battery current/power/temperature, frame rate, capture and mute controls. A displayed preview FPS is not an encoder benchmark.
3. Navigation opened Streamer, Bonding, Connections, Video, Audio, Recording, Display, Overlays, Import/Export and Advanced settings. Saved XML trees and screenshots corroborate the top-level options and visible defaults. Offscreen controls and every dialog option were not individually exercised.
4. A `larix://set/v1` deep link with one local RTMP connection prompted for import and added the connection after OK. The imported destination was `rtmp://127.0.0.1:19350/live/test`. The name `Local validation` was encoded with urllib's plus-for-space convention and displayed as `Local+validation`. The app's own exporter uses Android Uri.Builder; this experiment does not establish a bug in the native export/import round trip.
5. With recording disabled and no connected network, confirming broadcast displayed `No internet connection.` The local RTMP listener received no media in that attempt. Repeated confirmation did not bypass the condition.
6. Importing `larix://set/v1?enc%5Brecord%5D%5Benable%5D=on` prompted for Recording settings. After confirmation, the broadcast confirmation explicitly said recording would also start.
7. With recording enabled, the same local RTMP connection started while external network access remained disabled. ADB reverse forwarded emulator TCP port 19350 to the host's loopback receiver. FFmpeg received and saved 5.006 seconds of FLV containing H.264 Constrained Baseline, 1920×1080, and AAC-LC, 44.1 kHz, stereo. The resulting file is 320,726 bytes. FFmpeg metadata advertised 6 Mbps video and 96 kbps audio, but those advertised values are not measured payload throughput.
8. The app showed an active elapsed-time indicator. Its stop confirmation offered stop, pause and cancel. Stop completed; an app-generated MP4 appeared under DCIM/IRLPro. The pulled MP4 is 1,960,061 bytes and has a container duration of approximately 30.645 seconds, with H.264 1920×1080 and stereo AAC at 44.1 kHz. It contains 560 video frames and 1,317 audio frames according to FFprobe. Its duration and frame rate describe this emulator session, not physical-device performance.
9. FFmpeg decoded one second of each captured file without reporting decoding errors. This is a bounded decode check, not proof that every frame or timestamp in each file is correct.
10. The application was force-stopped and the read-only emulator was shut down after capturing the media and evidence. The local receiver exited after its five-second capture. No connection to a public streaming destination was configured.

## Evidence

- `evidence/runtime-install.txt`, `evidence/runtime-launch.txt`, `evidence/emulator.log`: installation and emulator startup.
- `evidence/runtime/startup.xml`, `main.xml`, `preview.xml` and corresponding PNGs: permission/welcome/preview observations.
- `evidence/runtime/settings-screens.json`, `settings-capture.log` and named XML/PNG files: ten settings pages.
- `evidence/runtime/import-command.txt`, `import-confirmation.xml`, `after-import.xml`: local connection import.
- `evidence/runtime/streaming-active.xml`, `broadcast-local.xml`: offline connectivity rejection with recording disabled.
- `evidence/runtime/record-import.xml`, `start-record-confirmation2.xml`, `local-recording-active.xml` and PNG: recording import and active session.
- `evidence/runtime/rtmp-receiver.log`, `rtmp-loopback.flv`, `rtmp-probe.json`, `rtmp-decode.txt`: local RTMP receiver and decode evidence.
- `evidence/runtime/stop-confirmation.xml`, `stopped.xml`, `recording-files-after-stop.txt`, `app-recording.mp4`, `recording-probe.json`, `recording-decode.txt`: stop transition and app recording.
- `evidence/runtime/app-logcat.txt`: log snapshot taken before the successful local media test. It includes New Relic DNS-resolution failures while offline; it is not a complete recording of the successful test.

The initial offline rejection agrees with `StreamerService.P()` in the Java export, which permits proceeding when recording is enabled even without network connectivity. The later local test confirms one actual execution of that permitted path.

## What this does not establish

No public RTMP service, RTMPS/RTSPS certificate handshake, SRT/RIST/SRTLA receiver, multi-link bond, cloud bonding endpoint or chat page was exercised. The local test verifies one unencrypted RTMP path and a simultaneous MP4 recording with synthetic media. It does not verify bitrate adaptation under congestion, failover, receiver compatibility generally, background behavior, recording split boundaries, pause/resume, camera-device differences, USB capture, Bluetooth input or microphone signal quality. The emulator used `-no-audio`; the AAC stream's presence does not prove meaningful microphone content.
