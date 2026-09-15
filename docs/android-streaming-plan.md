# Android IRL streaming plan

Prepared September 8, 2026. Scope confirmed: phone camera first, with editable scenes and YouTube streaming, chat, text, and goals. This is a proposed implementation plan; no Android app or device benchmark exists yet.

## Recommendation

Build a native Kotlin Android app using Moblin as a product and source-code reference. A direct Swift-to-Kotlin translation would carry over assumptions about Apple capture, rendering, and lifecycle APIs. Reimplement those layers around Android APIs and an existing streaming engine.

Moblin provides useful references for scene settings, widget placement, and stream controls. Its settings code imports SwiftUI and AVFoundation, while the project includes ReplayKit and MetalPetal. These are concrete reasons the Android media and UI layers need new implementations. [Scene settings](https://github.com/eerimoq/moblin/blob/main/Moblin/Various/Settings/SettingsScene.swift), [project dependencies](https://github.com/eerimoq/moblin/blob/main/Moblin.xcodeproj/project.pbxproj).

Moblin's MIT license permits modification and redistribution with its copyright and permission notices retained in copies or substantial portions. Track any copied or translated code and review dependency and asset licenses individually before distribution. [Moblin license](https://github.com/eerimoq/moblin/blob/main/LICENSE).

## UX direction: Streamlabs Mobile

Added September 9, 2026. The user strongly prefers Streamlabs Mobile's simplicity and wants close visual and interaction fidelity, especially platform connection and overlay handling. Use it as the main interface reference, with Moblin informing advanced streaming and DJI capabilities. This preference does not add every Streamlabs platform or widget service to release scope.

Streamlabs documents an editor with selectable layers that can be moved/resized, widget additions, and mobile overlay themes. Its mobile connection flow supports platform accounts and a custom RTMP destination. These official guides establish workflow references, not proof of the exact current Android screen layout. Inspect the user's reference app/version or supplied screen captures before claiming pixel-level fidelity. [Editor guide](https://support.streamlabs.com/hc/en-us/articles/4413183964955-How-to-Use-the-Editor-Mode-in-Streamlabs-Mobile-App), [mobile overlays](https://support.streamlabs.com/hc/en-us/articles/4413175103643-Stream-Overlays-for-Streamlabs-Mobile-App-are-Here), [platform setup](https://support.streamlabs.com/hc/en-us/articles/4413184057371-Streamlabs-Desktop-For-Mobile).

Proposed interface:

- Open into the camera/active-scene preview, with clear Go live, microphone, camera switch, scene selection, and Edit controls. Keep advanced encoder/network controls in settings.
- Connect platform opens YouTube authorization, returns to the selected channel, and shows connected/reconnect state. Go live collects title, privacy, and broadcast selection/creation. Custom RTMPS is an advanced option. Stream-key entry can remain the first engineering milestone, but the intended normal product workflow uses platform sign-in.
- Edit opens Layers and Add layer. Present Camera, DJI camera, Text, Image, YouTube chat, and Goal with understandable names. Show DJI discovery only when adding that source. Drag/resize directly on preview, reorder layers in the list, and use a compact properties sheet for styling, visibility, lock, and delete.
- Provide a few bundled overlay presets that arrange existing native layers and apply shared typography/colors. Each layer stays editable. Selecting a preset previews it before applying; replacement must not silently destroy the user's layout. Theme selection does not imply importing Streamlabs theme packages or connecting to its widget backend.
- Scene selection uses small saved previews for Live, Starting soon, BRB, and Ending. Avoid continuously rendering inactive previews. Use explicit Edit/Done behavior; editing a draft scene while live should require Apply before replacing broadcast layout. Keep dedicated live actions, such as mute, scene switch, and goal increments, immediate and clearly labeled.
- Keep operator-only chat and broadcast chat visibly distinct. Explain missing platform connection inline when adding chat, preserving the user's scene draft through authorization.

Implement overlays as the planned cached native textures, regardless of the familiar layer-editing UI. Retain required 1080p30 output and three-hour battery target. Public-facing app identity and bundled artwork will be project-owned. No Streamlabs service interoperability or additional platform authorization is assumed from the design reference.

Design acceptance: a new user can connect YouTube, select a scene preset, add/edit chat and a goal, and reach the broadcast confirmation without opening advanced settings. Existing users return to their saved scene and connected channel. Prototype disconnected/expired-login states, layer editing, live scene switching, and DJI connection before implementing final screens.

## First usable version details

The central workflow is: create scenes, add and arrange sources, configure YouTube, preview, go live, and switch scenes without restarting the broadcast.

- Scene collection with create, rename, duplicate, delete, reorder, and persistent storage.
- Starter scenes: Starting soon, Live camera, Be right back, and Ending. BRB can hide video and mute the microphone through an explicit scene setting.
- One active phone camera, front/rear switching, zoom, microphone selection where supported, mute, and audio level display.
- Text with font size, color, background, alignment, and line wrapping; static image/logo sources; a solid background source.
- YouTube chat source with position, size, message count, expiry, and author styling. Include a separate operator-only chat panel with its own visibility setting.
- Manual goals with label, current value, target, unit, progress bar, reset, and live increment/decrement controls. A goal is shared data that several scene sources may display.
- Drag, resize, reorder, hide/show, lock, and remove sources. Keep normalized positions relative to a fixed output canvas.
- Landscape and portrait stream profiles chosen before going live. Require native 1920×1080 at 30 fps for landscape and 1080×1920 at 30 fps for portrait. Validate capture, composition, and encoded output at this profile from phase 1; upscaled 720p or duplicated lower-frame-rate camera output does not satisfy the requirement.
- YouTube RTMPS output, stream health, elapsed time, dropped frames, connection state, reconnect, and an explicit stop action.

Example live scene: full-screen rear camera, title at the top, recent chat in the lower left, and a goal bar along the bottom. The editor shows selection handles only to the streamer.

Defer screen capture, simultaneous cameras, arbitrary browser sources, animated media, transitions beyond cuts, external donation services, multistreaming, bonding, replay buffers, and desktop OBS plugin compatibility. These can follow the camera-first release.

## Architecture

Performance and battery life are primary product requirements, as confirmed by the user. Engine selection and supported stream profiles must pass measured power and sustained-performance gates before adding feature scope. Visual polish must fit those budgets.

Use Kotlin and Jetpack Compose for the editor and controls. Evaluate RootEncoder as the Android capture/encoding/transport engine, initially using its Camera2 source. Its documented capabilities include RTMPS, screen capture, camera switching, real-time text/image overlays, and OpenGL filters. Pin the version only after the proof of concept passes. [RootEncoder](https://github.com/pedroSG94/RootEncoder), [filter extension points](https://github.com/pedroSG94/RootEncoder/wiki/Real-time-filters).

Video flow: Camera2 texture → OpenGL scene compositor → shared output frame → encoder surface → H.264 → RTMPS → YouTube. Send that same composition to the preview. Audio flow: microphone → timestamped audio capture → AAC → the same RTMPS session. Use the engine's synchronization and codec handling first; isolate it behind an adapter so replacing it does not change scene storage or UI. Android MediaCodec supports surface input for video encoding. [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).

Compose controls are not broadcast content. The compositor must draw every visible source into the encoded frame. Rasterize text, chat, and goal graphics to cached textures when their content changes, then composite on the GPU. Avoid copying camera frames through CPU bitmaps. Keep chat fetching, image decoding, and disk writes off the render thread.

Keep one encoder session alive across scene changes. Swap an immutable render description at a frame boundary and reuse camera capture when possible. For a scene without a camera, continue generating background frames and apply its explicit audio policy. Test camera switching separately because devices may interrupt capture temporarily.

Recommended stack, clarified September 9, 2026:

- Kotlin, coroutines/Flow, ViewModel, and Jetpack Compose for screens and control state. Embed a SurfaceView-based preview; keep per-frame media handling on dedicated engine/render threads rather than emitting frames through Compose or general UI flows. Cache costly UI calculations and limit state invalidation. [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance/bestpractices).
- Camera2 through the engine's camera adapter initially, for explicit session/surface integration. CameraX is a viable alternative with device compatibility work built in; do not claim Camera2 inherently uses less battery. Compare adapters if device behavior requires it. [CameraX architecture](https://developer.android.com/media/camera/camerax/architecture).
- OpenGL ES with EGL for the scene compositor, SurfaceTexture for camera/decoded video inputs, and a shared composed output sent to preview and MediaCodec input surfaces. Start with this established Android surface path; Vulkan or C++ requires a measured benefit before adding complexity. [Android SurfaceTexture architecture](https://source.android.com/docs/core/graphics/arch-st).
- Hardware-backed MediaCodec H.264 video encoding and AAC audio encoding; AudioRecord-based microphone capture via the streaming engine. For DJI video, MediaCodec decoding to a surface. Validate the actual selected video codec and sustained output on both phones. [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).
- RootEncoder as the first capture/codec/RTMPS integration candidate, behind our StreamEngine adapter. Evaluate its stock graphics path before implementing a custom compositor; preserve existing transport/codec code where it meets requirements. An inbound DJI RTMP receiver/demuxer remains a separate dependency decision and feasibility task. [RootEncoder](https://github.com/pedroSG94/RootEncoder).
- Android Bluetooth LE/GATT for DJI control, using the researched protocol/Android port as a reference. A camera-source adapter joins incoming decoded video to the same scene system. [Android BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/overview).
- Official Google authorization for YouTube, a REST client for broadcast operations, and the official streaming chat API where validated. Room for scenes and goals, DataStore for small preferences, and Keystore-backed secret protection. These choices primarily improve maintainability; do not claim a meaningful battery gain from selecting a particular HTTP client or database.
- Foreground service for stream ownership independent of the editor screen; Android thermal monitoring and device-supported power profiling. No server required for the initial app. Use ordinary local scene presets rather than browser-based implementations of built-in overlays.

Native Android is the preferred fit for this Android-only, device-heavy app. Flutter or React Native could host controls around the same native media engine, but would add integration work without removing the camera, codec, Bluetooth, or lifecycle work. This is an integration decision, not a claim that either framework makes efficient streaming impossible. Do not introduce an FFmpeg software transcoding pipeline for the live path. Consider native libraries only for a specific missing protocol or measured processing bottleneck.

The energy advantage must come from hardware media blocks, surface-based frames, bounded work, cached overlays, and restrained preview/display activity. Language choice alone cannot establish three-hour endurance.

Suggested code boundaries:

- `scene`: source definitions, placements, transforms, validation, and schema migrations.
- `render`: texture lifecycle, text layout, ordering, crop, opacity, and composition.
- `stream`: capture/encoder adapter, foreground service, connection state, retry, and statistics.
- `youtube`: authorization, broadcast selection, chat connection, and event normalization.
- `goals`: goal values, manual updates, and later event-driven providers.
- `editor`: Compose screens, hit testing, drag/resize, preview, and live controls.
- `storage`: Room for scene/goal metadata, app-owned media files, and settings storage. Keep credentials out of scene exports and logs; protect stored secrets with Android Keystore-backed encryption.

Start these as packages in one app module. Split Gradle modules when independent ownership or build time warrants it. No backend or streaming relay is required for camera → YouTube RTMPS; add server infrastructure only when an integration actually needs it.

## Scene and event model

A `SceneCollection` owns scenes, sources, assets, and goals. A `Scene` owns ordered `SceneItem` placements and an audio policy. A `Source` holds content or references an input/provider. A `SceneItem` holds source ID, normalized bounds, crop, opacity, visibility, and lock state. Stable IDs allow the same chat feed, camera, or goal to appear in several scenes without duplicating the underlying connection or value.

Store output aspect ratio on the stream profile. Separate durable configuration from live runtime state such as the current camera handle and chat socket. Persist a schema version and save edits atomically. Copy imported images into app-owned storage so a later gallery move does not break a scene.

Route chat and future monetization events through a bounded event stream. Deduplicate by platform event ID. Keep message rendering and goal updates separate so changing scenes cannot count an event twice. Manual goal controls remain available even if YouTube authorization or chat fails.

## Battery and sustained performance

These are design requirements and proposed budgets, not measured results. The user currently has a Pixel 7 and plans to use a Samsung Galaxy S25 Ultra, with at least three hours of battery-only streaming required. Use Pixel 7 for development and power-baseline work; treat Galaxy S25 Ultra as the primary release/endurance target. Measure and publish Pixel 7 endurance separately. Do not infer Samsung performance from Pixel measurements or claim either phone passes until tested.

The mandatory endurance gate is a three-hour cellular broadcast at mandatory 1080p30 with camera, microphone, title, logo, chat, and goal bar active. Proposed acceptance protocol: start at 100% and retain at least 10% battery after three hours, unplugged, at a documented usable display brightness with battery preview mode enabled. That reserve is a design target, not an additional user-specified requirement. Average drain must therefore be at most 30 percentage points per hour across the full test; this arithmetic does not imply a linear battery gauge. Also measure normal preview mode, Wi-Fi, weak signal, and outdoor conditions separately. Document ambient temperature, signal, battery health, and any automatic quality changes with the result. Agree on and record repeatable baseline conditions before measuring; no universal weather/signal guarantee is implied.

Until the S25 Ultra is available, the engine decision remains provisionally validated on Pixel 7. The three-hour S25 Ultra requirement cannot be closed using an emulator, specifications, or another phone's battery capacity. 1080p30 is mandatory together with three-hour endurance. If either fails, optimize or replace the media path and keep the release gate open. A lower-resolution or lower-frame-rate profile cannot satisfy this requirement.

### Media work

- Require a validated hardware H.264 encoder for supported live profiles. Check reported codec capabilities and actual sustained behavior; do not silently fall back to software video encoding. If hardware encoding cannot sustain 1080p30, mark the device/profile unsupported rather than accepting a lower profile as success. Android exposes hardware/software codec classification from API 29, but the hardware flag is vendor-reported, so it is not a substitute for measurements. [MediaCodecInfo](https://developer.android.com/reference/android/media/MediaCodecInfo).
- Keep camera frames on the surface/texture path into the compositor and encoder. No full-frame CPU readback, per-frame Bitmap conversion, or app-level YUV/RGB conversion in normal streaming. This avoids app copies; it does not claim drivers never copy buffers.
- Capture at an appropriate supported size and frame rate for the output. Start at 1080p30, SDR, one camera. Avoid a 4K/60 capture request for 1080p30 output. Measure stabilization and optional camera processing before enabling them by default.
- Composite visible sources into one output target, minimizing full-frame intermediate passes. A library filter chain that allocates a full-resolution target per widget is a benchmark concern. Use a custom batched compositor if measurements justify it. Preserve layer ordering and transparency when batching.
- Cache static text/images. Update chat and goal textures only on content, style, expiry, or layout changes. Coalesce chat bursts at a proposed maximum of 5 visual updates per second; the outgoing video remains 30 fps. Bound decoded image sizes, message history, and texture cache memory.
- Do not render inactive scene thumbnails continuously or retain active capture for every saved scene. Release a camera when a camera-free BRB scene remains active, accepting a measured warm-up delay on return. Keep encoding paced background frames and follow the scene's audio setting.
- Reuse buffers and textures. Keep app-owned allocations out of the per-frame path and use bounded queues. Discard stale raw video work before encoding when necessary; do not arbitrarily drop dependent encoded packets. Keep audio clock continuity.

### Preview and controls

Use the shared composition for preview, with an independent preview presentation rate. Propose 15 fps preview in battery mode while output stays at 30 fps. Add an explicit dimmed, minimal-controls mode that stops preview presentation and scene thumbnails while encoding continues. Measure it on each phone; a black UI alone is not equivalent to switching the display off. Locked-screen capture is a separate device/lifecycle capability to validate.

Keep frame events out of Compose state. Update stream statistics around once per second and audio meters around 10 times per second initially. Stop invisible UI work. Maintain one chat connection per broadcast, and only keep providers running when a visible source, operator panel, or goal actually needs them. Use event-driven work and API-prescribed polling instead of short repeating timers. Avoid an unconditional maximum-performance mode or CPU busy-waiting. Acquire only lifecycle-required locks and release them on stop and error paths.

### Thermal response

Observe Android thermal status and available headroom, with fallback for missing vendor support. Query headroom no more often than the documented interval, currently 10 seconds. Use hysteresis so quality does not oscillate. [Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal).

First reduce preview/UI refresh and optional animation work while preserving core chat, text, goals, and audio. Treat network bitrate adaptation separately: a lower bitrate alone is not a reliable thermal solution. Preserve 1080p30 capture and encoded output in all automatic battery modes. If heat persists beyond optional-work reductions, report the condition; do not silently reduce resolution, lower camera cadence, or duplicate frames to disguise a lower capture rate. A thermal stop or inability to sustain this profile fails the normal-condition endurance gate. At critical thermal conditions, stop cleanly and explain why. Record all automatic changes during benchmarks so reduced output quality cannot masquerade as an efficiency improvement.

### Benchmark protocol and release gates

Move power validation into phase 1 and repeat it for meaningful media-path changes. Compare a minimal camera + microphone + RTMPS baseline against the same pipeline with composition, then a representative scene containing a camera, title, logo, five visible chat messages, and a goal bar. Replay a fixed chat fixture at ordinary and burst rates. Also compare preview on, preview reduced, and preview disabled. Keep the compositor baseline and camera-direct baseline separate where supported.

Use release builds on physical phones. Match camera view and motion, codec settings, brightness, starting charge band, battery health, ambient temperature, network/signal, and output quality. Run unplugged after warm-up; avoid USB charging skew. Alternate baseline and feature runs, with at least three repeats of 30–60 minutes. Run Wi-Fi and cellular as separate cases, then test outdoor heat, weak signal, and charging as separate stress conditions.

Measure whole-device average power and energy per streamed minute where instrumentation supports them, plus battery percentage points per hour, actual output fps, raw-frame drops, render timing, CPU/GPU activity, thermal status, and memory. Use system tracing/Perfetto, Android Studio Power Profiler or Macrobenchmark power metrics where the phone supports them, and Batterystats as supporting evidence. Record instrumentation limits. Android now recommends these profiling alternatives over the unmaintained Battery Historian UI. [Power measurement guidance](https://developer.android.com/topic/performance/power/setup-battery-historian).

Provisional engineering gates, to ratify against the phase-1 device baseline:

- Standard overlays add no more than 10% whole-device average power over matched camera-only streaming, with measurement variance reported. Absolute device/session battery targets are required too; an inefficient baseline cannot establish success.
- App compositor CPU submission and GPU work each have a proposed p95 budget of 5 ms at 1080p30, measured separately with supported tracing. The full pipeline must still sustain output cadence; low submission time alone proves nothing about GPU completion.
- Fewer than 1% app-side raw video drops on stable-network soak tests, bounded latency, and audio/video offset within a proposed 100 ms without accumulating drift. Evaluate network disruptions separately.
- No sustained severe thermal state at the claimed default profile under documented normal conditions. Thermal fallback must remain predictable in stress tests.
- At least a three-hour continuous final soak on the Galaxy S25 Ultra using the full scene and cellular protocol above, with the proposed 10% battery reserve. Run a corresponding Pixel 7 test and report its achieved duration. No crash, unbounded queues, or monotonically growing memory after warm-up.

If the baseline fails the desired session duration, optimize or replace the media path before expanding features; retain mandatory 1080p30. RootEncoder is a candidate, not a fixed dependency decision. Native Kotlin/C++ or a particular graphics API is not itself proof of energy efficiency; use native code only for a measured need.

## DJI direct connection

Added September 9, 2026 following the user's interest in Moblin's Mimo-free workflow. Target cameras are Osmo Action 4 and Action 5 Pro. Treat direct pairing/start and DJI video as a scene source as a separate feature milestone, with exact camera firmware recorded in the compatibility matrix. Phone-camera support remains required.

Moblin's controller uses Bluetooth commands to pair, supply Wi-Fi settings, configure an RTMP destination, and start/stop streaming. Bluetooth is the control connection; video uses the configured network. [Moblin controller](https://github.com/eerimoq/moblin/blob/main/Moblin/Integrations/Dji/DjiDevice/DjiDevice.swift).

An existing Android implementation, `dimadesu/dji-remote`, explicitly ports this functionality from Moblin. Its author confirms personal Action 4 testing and lists Action 5 Pro support. This is evidence of feasibility, not validation on our phones or firmware. Evaluate its Bluetooth state machine and protocol implementation, with license review before copying code. [Android DJI remote](https://github.com/dimadesu/dji-remote).

Proposed workflow: Add source → DJI camera → discover/pair → provide a reachable Wi-Fi network and local ingest destination → start camera → show received video in the scene. Users may still need Android permission prompts, hotspot setup, and camera-side pairing confirmation. The promise is routine streaming without opening Mimo; it does not replace initial activation where DJI requires Mimo. [DJI activation instructions](https://repair.dji.com/help/content?customId=01700006759&documentType=&lang=en&paperDocType=ARTICLE&re=US&spaceId=17).

For the requested overlays, implement camera → Wi-Fi/local RTMP receiver → hardware decoder surface → existing scene compositor → hardware encoder → YouTube. RootEncoder's outbound streaming support alone does not establish a working inbound RTMP server/decoder. Evaluate that receiver separately. Choose camera audio or phone microphone explicitly and account for incoming-video latency in synchronization. Add source-loss handling that can show BRB or switch to the phone camera without closing the YouTube encoder session.

Start topology validation with the camera connected to the phone's hotspot and the camera publishing to a listener reachable on that network, while the phone publishes to YouTube over cellular. Verify hotspot addressing, incoming reachability, network routing, local listener lifecycle, and reconnect on Pixel 7 and Galaxy S25 Ultra. Do not assume an app can silently enable/configure every hotspot setting.

The DJI path adds Wi-Fi reception, video decoding, and potentially hotspot work while avoiding phone-camera capture. Its net phone power cost must be measured; there is no assumed saving. Camera battery is an additional endurance constraint. Keep the three-hour 1080p30 phone-camera gate and add a separate full-scene DJI-mode run, recording both phone and camera power arrangements and achieved duration. Verify the camera delivers actual 1080p30 frames; a configuration command or duplicated frames are insufficient evidence.

Feasibility checks before committing this milestone: pair/start/stop repeatedly without Mimo, inspect received codec/resolution/cadence, render overlays into received YouTube playback, compare power with phone-camera mode, test camera disconnect/rejoin, and repeat on Action 4 and 5 Pro firmware available for testing. Scope one camera at a time. Re-estimate the overall schedule after the receiver and device tests; this is more work than adding a Bluetooth button.

## YouTube integration details

Deliver stream-key setup first: the streamer creates/selects a broadcast in YouTube Studio, enters its RTMPS configuration, and associates the live video with the app. A stream key sends media but does not grant chat/API permissions. Add the official API authorization/configuration needed for chat independently; native chat remains required for the first usable version.

Next, add Google authorization and in-app broadcast selection/creation, binding to a live stream, ingest readiness checks, and broadcast start/end handling. Keep transport connection state distinct from YouTube broadcast state so the app does not claim to be live merely because RTMPS connected. [YouTube Live API overview](https://developers.google.com/youtube/v3/live/getting-started).

Use the official chat API. Prefer `liveChatMessages.streamList` where its Android integration proves workable; otherwise use `list` and respect the returned polling interval. Bound the visible history, reconnect without duplicate messages, remove messages when deletion/ban events arrive, and degrade to a hidden or clearly disconnected chat source without ending video. An already-broadcast message cannot be removed from frames viewers have seen. [Chat streaming](https://developers.google.com/youtube/v3/live/docs/liveChatMessages/streamList), [chat polling](https://developers.google.com/youtube/v3/live/docs/liveChatMessages/list).

Manual goals meet the initial requirement. Later, support gross Super Chat/Super Sticker event totals for eligible channels, with event deduplication and currency-specific totals. Do not label these as creator payout. Treat subscriber goals as approximate when backed by rounded public statistics; do not promise exact real-time subscriber alerts. External donations need their own provider integration. [Chat event schema](https://developers.google.com/youtube/v3/live/docs/liveChatMessages), [channel statistics](https://developers.google.com/youtube/v3/docs/channels).

Review API quota, OAuth publication, and channel eligibility during the first week. Google project approval work can proceed alongside engineering. Detailed source-backed constraints are in [YouTube research](youtube-research.md).

For mandatory 1080p30, begin benchmarking with YouTube's documented 10 Mbps H.264 recommendation, AAC audio, and two-second keyframes. Evaluate bitrate adaptation at fixed 1080p30 against motion quality and stream health. Record the bitrate used for each endurance test; a low-quality low-bitrate run must not stand in for the intended viewing quality. Network outages are separate recovery tests. YouTube also documents HEVC/AV1 ingest, but consider a hardware alternative only after verifying device encoder, transport compatibility, actual power, and received quality. Disable automatic broadcast stop for reconnect-oriented IRL sessions; YouTube documents that auto-stop can complete a broadcast about a minute after ingest ends. Explicitly complete the broadcast when the user stops intentionally. [Encoder settings](https://support.google.com/youtube/answer/2853702?hl=en), [broadcast lifecycle](https://developers.google.com/youtube/v3/live/life-of-a-broadcast).

## Delivery sequence and exit checks

These are planning estimates for one experienced Android developer working full time, with access to physical test phones. They are not measured delivery commitments. Google approval time and major vendor-specific codec failures are outside the estimate.

1. **Streaming and power proof, 1–2 weeks initially.** Camera and mic → YouTube RTMPS at 1080p30, with one text source. Verify the overlay in YouTube playback on a second device, audio/video sync, camera switch, 30 minutes of continuous streaming, and reconnect after a short outage. Establish repeated camera-only and representative-overlay power baselines before committing to RootEncoder and its compositor path. Extend this phase if the power gate fails. Begin Google project setup here.
2. **Scenes and editor, 2–3 weeks.** Implement the model, persistence, starter scenes, source placement, cached textures, live scene cuts, and manual goals. Verify saved scenes survive relaunch and preview matches received output for order, crop, opacity, and position. Repeated scene changes must preserve the encoder session and audio continuity.
3. **YouTube chat and usable alpha, 1–2 weeks.** Bind the selected live video, connect official chat, render it on stream, handle deletion/disconnection, and retain stream-key setup. Verify chat failure never stops the broadcast and live manual goal edits appear remotely. Camera + scenes + chat + text + goals is the alpha acceptance gate.
4. **Integrated YouTube flow, 1–2 weeks.** Add sign-in, broadcast selection/creation, readiness, start/end flow, expired/revoked authorization handling, and account switching. Test on a real eligible channel. Keep a clear Studio-based setup path when API restrictions apply.
5. **Field hardening, 2–3 weeks.** Validate foreground lifecycle, thermal behavior, battery use, interrupted mic/camera access, Wi-Fi/cellular handoff, reconnect, and long sessions. Test at least a midrange and a flagship phone across two vendors. Scope the supported device/profile list from results.

Initial estimates were 4–7 engineering weeks for alpha and 7–12 for beta. Treat these as provisional under the stronger battery requirement: re-estimate after phase 1, including any custom compositor or device tuning required. Performance gates take precedence over those dates. Full Moblin or desktop OBS parity is outside these estimates.

## Reliability requirements

Use a foreground streaming service started from a visible, authorized user action with the appropriate camera and microphone service types and ongoing notification. Android restricts starting these services from the background. Do not promise unrestricted background/locked-screen operation without testing the target Android versions and vendors. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

Use bounded network queues and retry with backoff. Reduce bitrate under congestion where the engine supports it; reconnect on transport loss. Direct RTMPS cannot guarantee seamless video through a network outage. Avoid resolution changes midstream in the first release. Handle camera/microphone interruption explicitly, with a visible status and a safe fallback rather than a silent frozen stream.

Apply the power and three-hour sustained-performance gates above. Verify successful reconnection when connectivity returns. Record actual frame drops, heat, battery use, and recovery time. Exercise Unicode/emoji chat, long messages, large image imports, stale authorization, quota errors, and repeated scene switches. Focus automated tests on scene persistence/migrations, placement math, event deduplication, and broadcast state transitions; use physical devices for capture/codec/lifecycle verification.

The immediate implementation milestone is deliberately concrete: stream the rear camera to YouTube with a text label and a goal bar, then cut to a BRB scene without restarting the stream.
