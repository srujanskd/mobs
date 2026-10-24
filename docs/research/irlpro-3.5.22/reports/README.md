# IRL Pro 3.5.22 reverse-engineering dossier

Analyzed on 21 October 2026. This is an evidence-backed reconstruction of the supplied Android APK's main subsystems. It is not original source recovery, a buildable clone, or a claim that every behavior has been reproduced.

## Artifact identity

- Package `app.irlpro.android`, version `3.5.22`, versionCode `305221`.
- APK size 38,865,633 bytes; SHA-256 `f5aa0343467b9890e72cf2292a4ddc68a15660c8e30996ab44f45e1e29c89bcd`.
- Minimum Android API 28; target API 34.
- APK Signature Scheme v3 verifies. Signer certificate SHA-256 `3f84b1bf148812d2c1c24a080f537b42ae4fc42a67579cfd1b7988b126eada91`. This establishes integrity under the embedded certificate, not independent proof of developer identity or the latest Play Store version.
- One DEX, 1,037 ZIP members, 48 native libraries across arm64-v8a, armeabi-v7a, x86 and x86_64.
- JADX reported 2,486 top-level classes. The saved export contains 2,483 Java files in 335 packages. Export success does not establish that every method is correctly reconstructed.

## What the app does internally

The manifest names LarixApplication, LaunchActivity, StreamerServiceActivity and a camera/microphone foreground StreamerService. Much of the implementation remains in `com.wmspanel` namespaces, with obfuscated helper packages. The evidence supports a Larix-based application with IRL Pro additions. It does not establish source ownership, licensing arrangements or the complete history of its development.

The main path is Android camera capture, GL layer composition, MediaCodec video encoding and AudioRecord/AAC encoding. Encoded samples flow to Java RTMP/RTSP connections or to SRT/RIST through JNI. The inspected recording path reuses encoded samples in an MP4 MediaMuxer. Device-dependent camera and codec support is determined at runtime.

SRTLA is a separate forwarding layer. Java acquires Android Wi-Fi, cellular and Ethernet networks, binds UDP sockets to them, and supplies detached descriptors and weights to native code. The service points an SRT sender at a local UDP port. The arm64 native forwarder receives packets, maintains ACK/NAK/window state, and selects an outbound descriptor. Its ordinary score is `weight * floor(window / (in_flight + 1))`, with a four-second receive-freshness gate. This scheduler is separate from encoder bitrate adaptation.

Multiple bitrate conditioners are recoverable, including hybrid2/hybrid3 and ladder-based modes. They consume transport statistics, change encoder bitrate with MediaCodec.setParameters, and can adjust camera FPS. Their exact thresholds and quantization are documented; effectiveness under packet loss or changing bandwidth is not measured.

Chat and dashboards are remote WebViews. The client exposes URL construction, filters, identifiers, JSON parsers and refresh scheduling. The remote multichat page and backend are absent from the APK. The bonding API supplies a stream ID and leads to an SRTLA endpoint; this does not expose server implementation.

## Detailed findings

1. [Streaming and adaptive bitrate](streaming-analysis.md): capture configuration, codecs, GL composition, recording, reconnection, SRTLA setup and conditioner formulas.
2. [Native libraries](native-analysis.md): all-library identity/dependency/JNI inventory, focused arm64 SRTLA and sender disassembly, scheduling and packet markers.
3. [Protocols and TLS](protocol-analysis.md): RTMP/RTSP dispatch, authentication, TLS behavior and the separate HTTPS bonding client.
4. [UI, integrations and persistence](ui-integrations.md): chat/dashboard contracts, web overlays, four ObjectBox entities, import/export, telemetry and 150 preference declarations.
5. [Runtime observations](runtime-analysis.md): offline emulator observations and exact validation limits.

The RTMPS/RTSPS implementation installs a trust manager whose chain-check methods return without validation, with no explicit hostname-verification call in that inspected implementation. The separate bonding HTTPS client uses default trust and hostname checks. This is a source-derived finding with a reachable call path, not a live certificate-acceptance test.

## Evidence and reproducibility

REA was used through pinned CLI `rea-agents@6.7.0`, using its verified JADX-headless-MCP 0.7.1 engine. The source checkout is pinned at `25bea07b42119f5bb2f17719ffcdea9e37aa40ce`. The JAR SHA-256 is `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`. Android Studio's existing JDK 25.0.3 was used.

- `evidence/package.json`, REA Evidence `ev_b29121b408810c36011bde408430342debca7aabd192591c4efe31ee591a792f`, establishes package, manifest and provider identity.
- `evidence/rea-srtla-class.json`, Evidence `ev_5854ce43ec0c6901ff91160e4a9099233f058a3a844bc49ca1497b2b5a13311f`, inventories the SRTLA Java class.
- `evidence/rea-srtla-getConnsString.json`, Evidence `ev_fbd6d170d2f2ca0554a94c50e730ccb29eaf1f70f6e25ef7a91c6af3d7e6e999`, recovers the descriptor/weight string producer.
- `evidence/rea-srtla-start-references.json`, Evidence `ev_a20a6e07bd2ec82ceb426305a1124ff5a0766cdd5ea9ed63a2576acbedbc6515`, records incoming static references.
- `artifacts/jadx/sources/` and `artifacts/jadx/resources/` are full-tree exports through the same JADX engine API, not separate REA Evidence records. `scripts/ExportJadx.java` and `evidence/jadx-export.log` preserve the method and diagnostics.
- `artifacts/smali/` contains 133 selected top-level classes and their nested classes. `scripts/ExportSmali.java` preserves the exporter. Raw instructions resolve the misleading reconstructed SRTLA startup loop.
- `evidence/source-catalog.json`, `evidence/preferences.json`, `evidence/endpoint-literals.json`, `evidence/apk-inventory.json` and `evidence/native/` preserve inventories. URL literals are candidates until supported by call paths; the endpoint catalog alone is not a behavioral claim.
- `evidence/runtime/` preserves screenshots, UI trees, command records and logs. Detailed-report statements are static unless expressly corroborated there.

Run `python3 scripts/catalog_apk.py` to regenerate source/preferences/URL catalogs. `scripts/rea-android` verifies the engine digest before every CLI invocation. No global MCP configuration was changed.

## Coverage and remaining work

Artifact identity and manifest declarations are established. Main-subsystem architecture, app preference declarations, model schema, JNI boundaries, principal protocol dispatch and selected algorithms are recovered statically. Startup, synthetic-camera preview, ten settings pages and connection import have runtime evidence. A local RTMP receiver captured 5.006 seconds of H.264/AAC, and a simultaneous app-generated MP4 was pulled and probed. One second of each file decoded without reported errors.

Complete equivalence remains open. Specifically, this work does not reconstruct every native routine or obfuscated method, prove all paths executable, recover remote server/page source, or validate multi-network SRTLA performance. Physical camera differences, USB video, Bluetooth routing, background lifecycle, permission permutations, live destination authentication, recording splits and network-shaped adaptive bitrate require further tests. Other ABIs are inventoried but not behaviorally compared instruction by instruction.

JADX reported nine errors and 24 warnings. Those diagnostics are retained, including failures in camera-related code and third-party code. Even methods without an error marker can have invalid reconstructed control flow. The critical SRTLA startup loop was verified against smali; the whole export is not asserted compile-ready. An implementation project should use the reports as a behavioral specification, verify each required path, and make its own source and design choices.
