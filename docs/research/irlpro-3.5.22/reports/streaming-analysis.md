# Streaming implementation recovered from IRL Pro 3.5.22

Analyzed artifact SHA-256: `f5aa0343467b9890e72cf2292a4ddc68a15660c8e30996ab44f45e1e29c89bcd`.

This report describes static code observations from the JADX export in `artifacts/jadx/sources`. It does not establish execution on a phone, successful delivery to a receiver, codec support on a particular device, or server-side implementation. The export is reconstructed Java, with obfuscated names and decompiler errors. Citations below use export-relative paths and one-based line numbers. The parent investigation retains the REA package Evidence and full exporter log.

## Architecture

`com/wmspanel/streamer/StreamerService.java:96` is an Android Service implementing the libstream capture/connection callback interface. It owns a `com.wmspanel.libstream.lpt7` streamer, the SRTLA wrapper, connection maps, the adaptive conditioner, recording timers, and an optional UI callback. The library implementation is present as Java classes under `com.wmspanel.libstream`; the observed pipeline uses Android media APIs rather than an all-native media engine.

The recovered path is:

1. `StreamerService.T` builds audio/video configuration and selects cameras, then creates a libstream instance.
2. Camera capture renders camera surfaces and overlay presentations into an encoder surface. Android MediaCodec encodes the video. AudioRecord supplies audio to an AAC MediaCodec encoder.
3. Encoded buffers feed protocol connections and, when enabled, an MP4 MediaMuxer recorder.
4. SRT/RIST pass through `SrtSender` JNI. RTMP has a Java implementation. SRTLA creates a separate native forwarding layer with one Android-network-bound UDP descriptor per selected transport.
5. A conditioner samples transport statistics and updates MediaCodec bitrate and optionally camera FPS.

This is an architectural derivation from call paths, not a recorded runtime trace.

## Service setup and lifecycle

`StreamerService.T`, lines 1235-1399, reads preference helpers and configures the libstream builder. The exact method names in this obfuscated export are not stable identifiers across app versions.

- Lines 1264-1272 attach the service callback, context, user agent `IRLPRO/null`, and audio configuration.
- Lines 1273-1333 choose Camera or Camera2, enumerate camera capabilities, select resolution/FPS, build video configuration, set preview Surface and Surface size, apply orientation/rotation, and choose physical camera or concurrent camera mode.
- Lines 1334-1351 pass web-overlay configurations to libstream. Overlay domains, custom CSS, dimensions, scale, z-index, and Android ID are also placed in New Relic custom-event maps. This is an observed call path; actual telemetry submission was not captured.
- Lines 1364-1378 build the streamer and configure Bluetooth audio broadcasts if enabled.
- Lines 1380-1396 start audio capture, video capture, and the stream image-layer controller. A timer is scheduled at one-second intervals.
- `onCreate`, lines 2061-2100, creates the foreground notification channel/actions and reads the Android ID.
- `onStartCommand`, lines 2188-2229, dispatches start/stop broadcast, mute, pause, and exit actions.
- `onTaskRemoved`, lines 2234-2237, exits through `i1`; `onTrimMemory`, lines 2241-2252, stops recording at level 60 and releases the streamer at level 80.
- `P0`, lines 1060 onward, performs stream/library/UI/timer cleanup. `j1`, lines 1871-1889, stops a broadcast, clears recording/conditioner/connections, stops SRTLA, and conditionally closes the bonding session.

The service can disconnect its UI while retaining its streamer: `U`, lines 1424-1445, shuts down preview, moves/clears UI callbacks, and can register rotation sensing. This is a code-supported design for background capture, not confirmation that every Android version permits it.

## Capture, rendering, and codecs

### Video

`p133lpT6/v5.java:185-211` assembles video settings. It defaults to H.264 when the HEVC preference is unavailable or a HEVC encoder cannot be selected, can force HEVC for certain connection/bonding conditions, verifies resolution, and sets FPS, keyframe interval, profile/level, target bitrate, and bitrate mode. `v5.a`, lines 36-52, either derives bitrate from a recommendation table or reads a configured value, then multiplies kbps by 1,000. The forced-HEVC adjustment divides a manually configured value by 1.5 when the relevant preference is enabled.

`com/wmspanel/libstream/c.java:25-30` calls `MediaCodec.createEncoderByType` and `MediaFormat.createVideoFormat`. `com/wmspanel/libstream/d.java:15-33` populates encoder settings. The visible MIME choices are `video/avc` and `video/hevc`, not evidence of AV1 support.

`com/wmspanel/libstream/lpt7.java:285-306` selects camera implementation `i` for the older Camera API, `j` for Camera2, or `h` for the other configured camera mode. Camera2 code in `j.java:467-483,528-541,558-562` uses CameraManager, camera output surfaces, and capture requests. `j.java:233-250` makes the encoder surface current, handles a snapshot, sets presentation time, and swaps buffers. `f.java:252-263,385-426,527-538,652` owns encoder-thread and web-presentation work. These establish surface composition before video encoding.

The presence of concurrent-camera code does not prove the UI exposes it or that a specific device supports it.

### Audio

`p133lpT6/m5.java:83-95` builds audio configuration from source, bitrate, channel count, sample rate, gain, acoustic echo cancellation, automatic gain control, and noise suppression preferences. Gain conversion is `10^(dB/20)`, lines 36-37. Bluetooth mode forces mono and 8 kHz in `m5.g` and `m5.j`, lines 70-75 and 98-103.

`com/wmspanel/libstream/con.java:23-28` creates the `audio/mp4a-latm` MediaCodec encoder. Its initial format is 44.1 kHz mono, but the builder subsequently supplies actual configured values, so that initial constructor value is not a universal app setting. `libstream/prn.java:96,108-128` reads AudioRecord, builds/configures it, and starts recording. `StreamerService.c1`, lines 1595-1610, starts Bluetooth SCO if selected; otherwise it starts audio capture through the streamer with either configured capture parameters or defaults.

## Protocol dispatch and JNI

`StreamerService.R`, lines 1155-1202, recognizes supported connection schemes and constructs distinct SRT, RIST, or general connection configurations. It registers successful connection IDs with the conditioner's connection map and initializes per-connection statistics.

`com/wmspanel/libstream/o.java:277-355` parses general connection URIs and constructs protocol connection objects. The RTMP branch constructs `s`; SRT setup is in `o.java:380-400`, and RIST uses the `x` connection implementation. This is code evidence of protocol implementations, not proof of interoperability with any receiver.

`com/wmspanel/libsrtsender/SrtSender.java:36-40` loads `c++_shared`, `srt`, `rist`, and `srtsender`. JNI declarations at lines 47-114 include SRT startup/cleanup/connect/close, SRT statistics, RIST connect/statistics, MPEG-TS PAT/PMT sending, AAC-frame sending, video-frame sending, receive, register, and unregister operations. The Java declarations identify the boundary; they do not recover all native packetization behavior. See the separate native analysis for native details.

The configured HEVC plus RTMP combination is rejected unless the relevant allowance preference is enabled at `StreamerService.java:1701-1703`.

## SRTLA bonding

The strongest recovered application-specific mechanism is in `com/wmspanel/streamer/Srtla.java` and `com/wmspanel/streamer/com1.java`.

### Network acquisition and descriptor handoff

- `Srtla.java:108-117` loads `libsrtla`, creates Wi-Fi/cellular/Ethernet enable and weight maps, defaults all to enabled with weight 100, and initializes synthetic address allocation at `10.0.0.0`.
- `Srtla.start`, lines 341-370, requests cellular, Wi-Fi, and Ethernet networks with transport IDs 0, 1, and 3, capability IDs 15 and 12, and a callback per transport. It selects a random local port in the inclusive range 20,000-25,000 and starts a thread named `SRTLA Runner`.
- Each `onAvailable` callback, lines 58-104, creates a connection object and calls `com1.a`.
- `com1.java:87-95` creates a DatagramSocket, binds it to that Android `Network`, obtains and detaches a ParcelFileDescriptor, assigns a synthetic `10.x.x.x` identifier, and increments the generation counter. The synthetic address is an identifier for the native bridge; it is not evidence of a configured Android interface address or VPN.
- `Srtla.getConns`, lines 263-299, keeps the greatest generation per transport type, removes older entries, filters disabled transports, recreates descriptors when re-enabled, and updates each selected weight. This means the visible Java selection keeps one latest connection per type, not an arbitrary list of concurrent cellular connections.
- `getConnsString`, lines 301-308, serializes each selected connection as `syntheticIP|fd|weight` separated by commas. `getLastUpdate` supplies the atomic generation counter.
- `setWeight`, lines 235-243, only accepts values 0-100. `StreamerService.a1`, lines 1562-1571, supplies user preference values for each transport.

`com1.a` initially assigns the cellular weight even for other transport objects. `getConns` then updates the selected object's weight using its own type. This is a visible implementation detail; no runtime timing problem was established.

### Forwarder lifecycle

`Srtla.java:194-227` runs a loop until `shouldKill`, waits 500 ms each iteration, and only enters native `startSRTLA` when descriptors are available. It maps return codes 0, 1, 2, 3, and 10 to success/randomness/listen/bind/server errors and records failures in both a UI error queue and New Relic event maps. A native return does not itself end the outer loop unless `shouldKill` is true.

`Srtla.end`, lines 246-248, sets the stop flag. JNI can read it through `shouldKill`, lines 337-339. Java alone does not show native teardown latency. `cleanupFD`, lines 127-129, only logs the descriptor-cleanup callback; actual close ownership must be checked in the native library. The Java callbacks in this class do not override `onLost`, and no unregisterNetworkCallback call appears in this class. These bounded observations do not prove a leaked socket or network registration because the native and Android lifetimes also matter.

`updateConn`, lines 375-396, sets the connected flag and copies native statistics into the connection matching a synthetic identifier. `isConnected` therefore means a native statistics callback has occurred, not an independently verified end-to-end live video session.

`StreamerService.e1`, lines 1696-1783, contains a URI rewrite from the selected SRTLA endpoint to `srt://localhost:<localPort>`, selects retransmission algorithm 1, and clears the connection passphrase/key length/max bandwidth/SRT mode for that local leg. The original network leg's security must be established separately. A timeout comparison of 10,000 ms is present, plus error polling and a call to start SRTLA.

**Decompiler limitation resolved with raw instructions:** The reconstructed `e1` Java has impossible control flow and uses `iH1` and `jCurrentTimeMillis` before their assignments. The parent exported `artifacts/smali/com.wmspanel.streamer.StreamerService.smali`, and its `e1` instructions at lines 4700-5065 resolve the critical branch. The real order is: apply transport preferences; parse host and default the remote port to 5000; stop any prior SRTLA wrapper with `r0`; sleep 100 ms; call `h1` to start the wrapper and obtain its local port; record start time; sleep 200 ms per poll. On `isConnected`, rewrite the URL and local-leg parameters, optionally post the 2.5-second algorithm adjustment, then jump out of the poll loop and call `R` to create the SRT connection. On a nonempty native error, stop SRTLA, sleep 100 ms, and return that error path. With no error and no connected callback, loop until elapsed time is greater than 10,000 ms, then stop SRTLA and take the timeout error path. The key branches are `:cond_1c3` at line 4831, successful `goto :goto_27a` at line 4925, error `goto :goto_27e` at line 4967, and timeout comparison/branch at lines 4970-4980. Thus the displayed Java `while(true)` does not reflect an infinite loop in the DEX. This is static control-flow recovery, still not runtime verification.

## Adaptive bitrate and FPS

`LPT5/prn.java:140-183` chooses an algorithm based on the preference returned by `u5.a`:

- 1: logarithmic descend, `LPT5/com5`.
- 2: ladder ascend, `LPT5/com4`.
- 3: hybrid, `LPT5/com1`.
- 4: hybrid2, `LPT5/com2`.
- 5: off, `LPT5/com6`.
- 6: hybrid3, `LPT5/com3`.
- Default/auto: hybrid2 for SRT or bonding, logarithmic for another selected connection, and off if no suitable connection is available.

The base sampler, `prn.java:93-126`, gathers TCP skipped audio/video frames, SRT dropped packets and send statistics, and RIST quality below 90 into the algorithm's inputs. Timing is scheduled through a Timer posting to the main Handler, `prn.java:476-485`. The base poll interval is 500 ms; hybrid2 and hybrid3 override it to 30 ms. A scheduled 30 ms interval is an intended cadence, not a measured performance claim.

### Hybrid2 and hybrid3

The recovered `LPT5/com2.java:85-179` and `com3.java:85-179` use the same visible main calculation with different obfuscated field names. The resource labels differ. The analysis did not establish a behavioral difference between those main calculations.

Inputs include SRT send-buffer packet count, RTT, configured connection latency, and send rate. The code maintains exponentially smoothed buffer size and RTT at 0.99/0.01, RTT delta at 0.8/0.2, send rate at 0.97/0.03, and decaying jitter maxima. Its effective RTT value is `int(msRTT) + 49`.

For hybrid2, after computing thresholds from those histories:

- If severe RTT/buffer conditions occur while above the floor, it can immediately choose the floor.
- One congestion branch decreases bitrate by 10% plus 150,000 bits/s and delays that class of decrease by 250 ms.
- Another branch decreases by 150,000 bits/s and delays decreases by 200 ms.
- A favorable RTT branch increases by `bitrate/30 + 30,000` bits/s, no more often than every 400 ms.
- It clamps to its configured ceiling and floor, then quantizes downward to a multiple of 20,000 bits/s.
- `com2.z`, lines 214-233, initializes the floor to 5% of the starting rate; `x`, lines 208-211, updates the ceiling and sets a 4% floor when changing the rate limit.

`com2.s`, lines 197-204, can divide bitrate by 1.5 when pausing for a camera flip after the timeout interval, then calls the base pause behavior. Runtime traces are needed to quantify stability, actual convergence, and whether operating-system scheduling permits the nominal poll frequency.

### Other algorithms

`LPT5/com1.java:22-69` estimates achieved versus required SRT throughput, decreases on a roughly 95% shortfall threshold, quantizes decreases to 100 kbps, and allows slower recovery. Its floor is 25% of the configured rate.

`LPT5/com4.java:25-35,77-139` uses a ladder of 20%, 25%, 33.33%, 45%, 60%, 78%, and 100%. It starts at the third step, uses a 2-second poll, and moves downward based on recent skipped/drop counts. Recovery is gated by its history.

`LPT5/com5.java:21-46,54-64` reduces using a factor of approximately 1/1.414 and increases by 1.415 when its recovery condition holds, with a floor of one quarter of the configured bitrate.

### Applying bitrate and FPS

`prn.e`, lines 230-240, records bitrate history, optionally updates FPS, calls `libstream.lpt5.changeBitRate`, and tracks current rate. `libstream/lpt5.java:316-324` forwards to the active video encoder. `libstream/c.java:66-74` calls `MediaCodec.setParameters` with `video-bitrate`.

Base `prn.B`, lines 195-223, reduces target FPS when bitrate falls below half the ceiling, chooses a 5-FPS step with a minimum of 15, finds the nearest supported camera FPS range, and forwards it to libstream. Hybrid2/hybrid3 add an absolute bitrate condition below 1.1 Mbps, `com2.java:53-81`. This is dynamic encoder and capture adaptation, not resolution switching.

## Reconnection and recording

`StreamerService.onConnectionStateChanged`, lines 2029-2058, initializes connection stats for initial/connected states, otherwise removes/releases the failed connection and creates a user-visible error. For non-authentication failures it schedules a `com8` retry after either 3,000 or 10,000 ms depending on `u5.i`. Authentication failures are not scheduled for this retry. These are static scheduling values, not measured reconnect times.

`p133lpT6/t5.java:52-65` gates recording by preference and converts configured split duration from minutes to milliseconds. `t5.k`, lines 100-106, first tries the Storage Access Framework location and falls back to DCIM in an `IRLPro` directory. `StreamerService.g1`, lines 1823-1840, starts configured recording and schedules a timer. `onRecordStateChanged`, lines 2118-2140, schedules split work and handles completed recording URIs. `k1`, lines 1894-1910, clears recording timers/callbacks and calls `stopRecord`.

`com/wmspanel/libstream/lpt5.java:923-959` creates the recorder and attaches existing video/audio listeners. `libstream/lpt4.java:168-170` constructs Android MediaMuxer with format 0, and lines 71/97 write encoded audio/video samples to it. This shows reuse of the streaming encoders in the inspected recording path. A separate independent-quality recording encoder was not found in these call paths; this is a bounded negative result, not proof that every package path was exhausted.

`t5.l`, lines 109-120, implements snapshots with SAF-first/DCIM fallback. The format preference supports JPEG, PNG, and WEBP; on Android 30+, full-quality WEBP selects the lossless option. StreamerService and libstream snapshot calls operate on the rendering pipeline, but overlay inclusion for every snapshot configuration still needs runtime verification.

## Remaining work

- The critical `StreamerService.e1` SRTLA wait loop has been recovered from raw smali. Apply the same instruction-level check to other malformed reconstructed methods before implementing their state transitions.
- Verify camera/physical-camera selection, Bluetooth routing, codec constraints, concurrent capture, background capture, rotation, and USB-camera behavior on supported hardware.
- Capture SRTLA registration, descriptor updates, packet distribution, retransmission, failure and recovery, native shutdown, and receiver behavior.
- Measure each adaptive mode on shaped networks and check actual MediaCodec reaction to bitrate updates.
- Exercise authentication failure, retry cancellation, stream/record start-stop races, file splitting, and app shutdown.
- Inspect server implementation separately if complete free-bonding behavior is required. The client alone cannot recover server code.

No target app was executed and no remote service was contacted for this report.
