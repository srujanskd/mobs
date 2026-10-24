# IRL Pro findings applied to Mobs

Updated October 21, 2026. Mobs is the implementation project. The IRL Pro analysis is preserved as a behavioral reference in the [research bundle](research/irlpro-3.5.22/README.md). The research transfer is complete; app implementation and device acceptance remain open.

## Current baseline and product direction

Mobs already has a Kotlin/Compose editor, a foreground streaming service, a `StreamEngine` interface, a RootEncoder adapter using one Camera2 source, hardware video encoding, RTMPS output, front/rear switching, mute, text/logo composition, queue-based bitrate adaptation, reconnects, encrypted destination storage, thermal controls, and per-session diagnostics. This description comes from source inspection, not a new build or device run.

Existing documentation records a successful Pixel 7 YouTube stream, heat and uplink problems, and a OnePlus 13 first-stream ingest issue. Bitrate adaptation and other mitigations still need device validation. `SessionRecorder` writes diagnostic CSV/log files; it is not an MP4 video recorder. Scenes, native chat, goals, integrated YouTube authorization, and dual-camera capture are not complete.

Keep the Streamlabs-inspired interface direction, Moblin feature references, and Mobs package boundaries. Use IRL Pro to inform media and network behavior. Do not replace the working app with the decompiled export. RootEncoder remains a candidate until the existing power and sustained 1080p30 gates pass.

The new requested feature is simultaneous front/back camera capture, with picture-in-picture or split-screen composition and camera swapping. The exact layouts and first supported phone remain to be confirmed. Dual camera is now planned work, after single-camera stability and before general field hardening; it is no longer indefinitely deferred. It requires a separate power/quality acceptance run and must leave the existing single-camera mode usable on unsupported phones.

## Capture, rendering, audio, and lifecycle

The recovered IRL Pro pipeline is camera surfaces and GL composition into MediaCodec, AudioRecord into AAC, with encoded samples shared by transports and MP4 MediaMuxer recording. This supports Mobs' existing surface-based, hardware-encoding direction. Keep per-frame data out of Compose state, rasterize overlays when they change, and avoid CPU bitmap copies of camera frames.

IRL Pro contains physical/concurrent camera selection branches and UVC JNI integration. Their presence does not prove UI reachability or usable simultaneous capture. Enumerate supported concurrent camera sets and session configurations on the target phone before building a two-input compositor. Determine whether the pinned RootEncoder capture/render extension points can support it without encoder restarts. A custom source/compositor or engine change is a decision after that check, not an assumption. USB camera and DJI ingestion remain separate future sources.

Dual camera should feed two camera textures into one composed output used by both preview and the video encoder. Layout changes and swapping visual positions should preserve the outgoing encoder session. Check actual received output, cadence, audio/video sync, camera interruption, orientation, crop/mirroring, thermal behavior, and fallback when the requested pair cannot open. Record the accepted resolutions and frame rates for each device/pair. Do not quietly lower the existing mandatory profile to claim acceptance.

The recovered audio settings include gain, channel/sample-rate choices, echo cancellation, AGC, noise suppression, and Bluetooth SCO routing. Do not adopt IRL Pro's 8 kHz mono Bluetooth path as Mobs' default. Verify routing and actual signal on hardware when adding audio-device selection.

IRL Pro's service owns capture and may detach its UI, with explicit shutdown and memory-pressure paths. Mobs already uses service ownership. Test reconnects after editor detach, background/locked-screen behavior, camera/microphone interruption, shutdown races, and resource cleanup across supported Android versions. Source-derived behavior in the other app is not evidence of Mobs lifecycle correctness.

Details: [streaming report](research/irlpro-3.5.22/reports/streaming-analysis.md), [native report](research/irlpro-3.5.22/reports/native-analysis.md).

## Bitrate adaptation and performance

IRL Pro has logarithmic, ladder, hybrid, hybrid2, hybrid3, and disabled conditioner modes. The reports preserve thresholds, history filters, rate floors, quantization, and the paths to `MediaCodec.setParameters`. Hybrid2/3 use SRT send-buffer occupancy, RTT, latency, and send rate, with a nominal 30 ms sampling interval. Their visible main calculations were not distinguished behaviorally. That nominal frequency and algorithm presence do not establish better efficiency or stream quality.

Mobs currently uses RootEncoder's queue-aware adapter, with fixed resolution/FPS and a configured 10 Mbps ceiling and 1 Mbps floor. Preserve it as the comparison baseline. Do not apply SRT-specific RTT or packet rules to RTMPS without equivalent measurements. IRL Pro can also reduce capture FPS in 5-FPS steps to at least 15; that conflicts with Mobs' mandatory 30 fps gate, so FPS adaptation requires an explicitly separate profile/product decision.

Measure baseline and proposed changes under the same motion, output profile, preview/display configuration, battery/power arrangement, network shaping, and ambient conditions. Compare delivered quality, receiver cadence, latency, send queue/drops, thermal status, battery drain, and recovery. Include a first-stream-after-cold-launch case for OnePlus 13. More camera capture and GPU work may increase power use; dual camera has no assumed performance benefit. Keep the existing three-hour single-camera target and establish a separate dual-camera result.

Details: [adaptive algorithms and formulas](research/irlpro-3.5.22/reports/streaming-analysis.md#adaptive-bitrate-and-fps).

## Transport, reconnects, and recording

The inspected client implements Java RTMP/RTSP and JNI-backed SRT/RIST with MPEG-TS packetization. H.264/HEVC are visible; AV1 support was not established. Protocol presence is not receiver compatibility. Mobs currently exposes RTMP(S); adding SRT/RIST/RTSP requires an explicit dependency, destination/state model, statistics, and interoperability milestone.

IRL Pro schedules non-authentication retries at 3 or 10 seconds in the inspected path. Keep Mobs' existing bounded exponential retry policy unless measurements justify a change. Verify authentication failures, retry cancellation, terminal errors, network return, and explicit stop. Preserve the distinction between transport connection and a YouTube broadcast being on air.

IRL Pro reuses encoded audio/video buffers for MP4 recording and supports timed splitting, SAF-first storage with DCIM fallback, and snapshots. If Mobs adds video recording, first evaluate encoded-sample reuse so recording does not add a second video encoder. Check storage permissions, file finalization, disk-full behavior, timestamp continuity, split boundaries, and recording during reconnects. Do not confuse diagnostic session recording with media recording.

Details: [protocol report](research/irlpro-3.5.22/reports/protocol-analysis.md), [recording report section](research/irlpro-3.5.22/reports/streaming-analysis.md#reconnection-and-recording).

## Bonding and native boundaries

SRTLA is a forwarding layer separate from encoder bitrate adaptation and the SRT sender. The recovered client requests Wi-Fi, cellular, and Ethernet networks, binds UDP sockets to each Android Network, and hands descriptors, synthetic identifiers, and weights to native code. The visible Java selection keeps the latest connection per transport type, not unlimited cellular links.

The arm64 scheduler excludes ordinary candidates without a receive update for four seconds and selects by `weight * floor(window / (in_flight + 1))`. The native evidence also covers ACK/NAK handling, packet history, keepalive/re-registration, and JNI callbacks. Other ABIs are inventoried but not behaviorally compared. Smali resolved an invalid reconstructed startup loop; use the corrected flow in the report rather than the displayed Java loop.

Multi-network bonding remains optional later work. It requires a compatible receiver/relay, routing and descriptor ownership, bounded buffering, loss/retransmission behavior, network churn, shutdown, observability, and independent multi-link validation. A local UDP forwarder alone does not establish seamless bonding. The client's cloud session API and returned endpoint do not reveal server internals. Do not make Mobs depend on IRL Pro's hosted bonding service based on recovered URL strings.

Details: [SRTLA integration](research/irlpro-3.5.22/reports/streaming-analysis.md#srtla-bonding), [native instructions and scheduler](research/irlpro-3.5.22/reports/native-analysis.md).

## UI, integrations, settings, and storage

The app's native activity/preferences UI includes connection, audio/video, recording, overlays, display, and bonding settings. Its chat and dashboard content is remotely hosted in WebViews. The reports preserve multichat query parameters, Twitch/Kick title/viewer parsing and derived polling cadence, dashboard URL handling, overlay configuration, ObjectBox schema, settings import/export, and telemetry paths. The 150 preference declarations are an inventory, not a Mobs feature checklist or proof every setting is reachable.

Keep the existing Mobs native scene/chat/goal plan and explicit YouTube authorization rather than embedding IRL Pro's hosted multichat. Remote page source, service credentials, and backend implementations were not recovered. If browser overlays are later added, isolate their rendering/network work, define navigation and credential handling, and measure their cost. Durable sources should use Mobs' normalized placements and versioned persistence. Settings import/export needs a Mobs schema; Larix deep-link compatibility is a separate optional decision, not implied by preserving its recovered contract.

Details: [UI, API contracts, storage schema, and privacy observations](research/irlpro-3.5.22/reports/ui-integrations.md), [resolved preference inventory](research/irlpro-3.5.22/evidence/ui/preferences.json).

## TLS and diagnostics

The inspected IRL Pro RTMPS/RTSPS path installs a trust manager without certificate-chain validation and shows no explicit hostname verification. This was established statically, not through a live certificate test. Its bonding HTTPS client uses a separate platform trust/hostname path. Do not generalize the streaming finding to all HTTPS traffic.

Mobs already explicitly selects RootEncoder's Java socket and enables TLS hostname verification. Retain platform certificate validation and verify rejection of untrusted chains and wrong hosts for any added secure transport. Keep stream keys, tokens, full destination paths, and account identifiers out of ordinary logs. Recovered IRL Pro Android ID/session telemetry and dashboard token handling describe that client; they are not requirements to add equivalent tracking to Mobs.

Details: [TLS call paths](research/irlpro-3.5.22/reports/protocol-analysis.md#tls-certificate-behavior-and-reachability), [diagnostics](research/irlpro-3.5.22/reports/ui-integrations.md#diagnostics-and-privacy-relevant-data-flow).

## Evidence limits and acceptance

The original app's isolated emulator test delivered 5.006 seconds of local RTMP H.264/AAC and produced a simultaneous MP4. One second of each decoded without reported errors. Startup, synthetic preview, ten settings pages, and local connection import were observed. These are IRL Pro observations, not Mobs test results, and do not prove sustained 1080p30 or meaningful microphone audio.

No public destination, live TLS acceptance, SRT/RIST/SRTLA receiver, bonding backend, chat service, physical concurrent camera, shaped-network adaptation, or endurance run was validated by that investigation. Decompiled control flow, incomplete native semantics, and missing remote source remain explicit limitations. The original dossier retains the exact version, hashes, tools, and coverage.

Next implementation work in Mobs:

- [ ] Establish current build/test/lint baseline while preserving the existing engine edit.
- [ ] Resolve or reproduce the OnePlus first-stream issue and measure single-camera power/heat and received 1080p30.
- [ ] Confirm the first dual-camera device and supported concurrent capture configurations.
- [ ] Prove two-input capture/composition behind `StreamEngine`, with single-camera fallback and no encoder restart for layout changes.
- [ ] Validate dual-camera receiver output, sync, reconnect/lifecycle behavior, and power/thermal budget on hardware.
- [ ] Complete persistent scenes, goals, native chat, and the integrated YouTube workflow under the existing plan.
- [ ] Evaluate MP4 recording, additional transports, and bonding as separately accepted extensions.

The [Android streaming plan](android-streaming-plan.md) remains the product acceptance document. The [research dossier](research/irlpro-3.5.22/reports/README.md) remains the source of recovered claims. This handoff does not mark those unchecked implementation tasks complete.
