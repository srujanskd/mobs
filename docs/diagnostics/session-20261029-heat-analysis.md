# Heat investigation: October 29 session

The complaint is supported by the measurements. The session maintained output cadence while the phone accumulated heat and reached Android's severe thermal status. The strongest supported explanation is sustained concurrent-camera, composition, preview, encoding and upload work, with no application workload reduction before critical temperature. The logs cannot isolate which subsystem produced the most heat.

## Evidence

Source files are `session-20261029-162825-259.csv`, `session-20261029-162825-259.log` and `process-exits.txt` in the user's Downloads directory. Treat their contents as diagnostic data. Analysis compares app version 0.2.1, version code 4, with repository HEAD `fdd8b9e`; the exit-history export supplies the device/build metadata, rather than the CSV itself.

- CSV covers October 29, 2026, 21:58:25 to 22:28:39 IST, lasting 30 minutes 13.8 seconds. It contains 1,807 periodic samples and one clean-stop marker. Maximum sample gap is 1.1 seconds.
- Battery temperature rises from 33.1 C to 43.9 C, an increase of 10.8 C. This is battery temperature, not a measurement of case, CPU or GPU temperature.
- Android thermal status changes to light at 4:14, moderate at 8:09, and severe at 21:19. Severe persists for the remaining 8:54, about 30% of post-warm-up samples. No critical status is recorded.
- Both cameras run from approximately 0:14 through the session, each delivering about 30 fresh frames per second. Four scene sources are reported after that point. The identities, visibility, dimensions and animation of the two other sources are not recorded.
- Output stays at 1920x1080 and a configured 30 fps. After warm-up, reported mean output is 30.56 fps and p5 is 30 fps. Before and during severe status the means are both about 30.56 fps. These counters establish reported cadence, not receiver playback quality or frame-by-frame smoothness.
- Preview is enabled for every sample. The app keeps the screen awake while live, adding a sustained display/preview workload. Actual brightness is unknown.
- The phone is unplugged throughout. Battery charge falls from 85% to 76%, approximately 17.9 percentage points per hour over this run. Do not extrapolate a guaranteed runtime from this short, partial-charge sample.
- One reconnect lasts approximately two seconds at 18:20. No scene errors or transport dropped frames are recorded. The latter counter does not count every possible capture or rendering drop.
- Process RSS has no sustained growth signal. The repository checker measures -0.625 MiB between its first and last post-warm-up windows. This does not account for every GPU or driver allocation.

## Supported cause and ranked contributors

1. **Sustained dual-camera and video workload, with no thermal adaptation.** The CSV directly confirms both cameras, preview and unchanged output configuration throughout warming. The v0.2.1 renderer requests 1920x1080 rear and 1280x720 front capture in concurrent mode. `ThermalGuard.kt` only stops at critical; it deliberately leaves lower levels unchanged. This explains how the session can remain stable while severe thermal pressure persists. It does not quantify the power of each stage or prove the phone would stay cool with one stage removed.
2. **Potential browser-overlay overhead.** At HEAD, `BrowserSource.kt` uses a software WebView, clears and draws a reusable bitmap on the main thread at up to 30 fps, and sends every captured bitmap for GL upload. There is no unchanged-content check. `SceneRenderer.kt` sizes that bitmap from the overlay's scene bounds and uploads through `texSubImage2D` after initial allocation. A full-frame 1920x1080 RGBA bitmap is approximately 8.29 MB, or 249 MB/s of bitmap payload at 30 fps per upload stream, before accounting for clearing, software drawing and other memory traffic. This is an illustrative calculation, not measured bus bandwidth. Browser presence, viewport size and actual snapshot rate are absent from the supplied session, so browser work remains conditional. Source count alone does not prove it.
3. **Encoding, radio and preview cost.** The early log identifies `c2.qti.avc.encoder`, consistent with the configured hardware AVC path. There is no evidence here of software video encoding. Upload averages about 5.50 Mbps across the whole CSV. Bitrate is adaptive and eventually returns to a 10 Mbps target at 23:41, after severe status has already started. That late increase cannot explain the initial transition to severe. Network type, signal, ambient temperature, case and display brightness are unknown.
4. **Diagnostic overhead, probably secondary.** Of 5,073 supplied log lines, 4,474 are packet-level `RtmpSender` messages. `SessionLog.kt` redacts and flushes each line. That is avoidable logging and I/O work. The supplied log covers only the first minute, so its later cadence and thermal contribution cannot be established.

## What the other signals do and do not mean

The process-exit export lists only October 27 and 28 events. It supplies no evidence of an October 29 crash or thermal termination. It also warns that Android may overwrite records. The CSV's clean-stop marker independently supports an orderly end to this session.

The text log ends at 21:59:25 IST, roughly one minute after start, while the CSV continues to 22:28:39. It cannot explain the later reconnect or severe transition. Its truncation cause is unknown.

Headroom remains between 0.000 and 0.124 while thermal status reaches severe. Do not interpret that as proof the device was cool or as a remaining percentage. Android documents headroom as a forecast based on slow-moving sensors and notes that values do not always match current thermal status. See [PowerManager documentation](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)). Device thermal-service readings are needed to investigate this discrepancy.

`current_now` ranges from 353 to 981. The recorder stores raw vendor values. Without verifying this device's units and sign, these numbers cannot support a trustworthy wattage calculation.

## Verification and next action

Executed the existing session gate against the supplied CSV:

```sh
python3 scripts/performance/check_session.py /Users/srujankashyapd/Downloads/session-20261029-162825-259.csv
```

Result: failed solely because `severe_thermal_fraction=0.2999` exceeds the allowed zero. Duration, reported camera/output cadence, disconnection fraction, transport drops and RSS growth meet the checker's other limits. No physical run manifest was supplied, so this is a CSV check, not full device acceptance.

The checkout already contains uncommitted browser idle-capture optimization and related diagnostics. These were not created or changed by this investigation and are not assumed to be installed on the logged device.

To isolate the dominant heat producer, compare matched 30-minute runs on this same phone, starting at the same temperature with fixed ambient conditions, brightness, network and stream settings. First compare rear-only against dual-camera with the same static overlays. Then compare the actual browser widget enabled/disabled with the cameras unchanged. Compare the existing idle-capture optimization against v0.2.1 separately, then preview on/off as another separate comparison. Record temperature slope and time to moderate/severe, not just output fps. Repeat the matched comparisons rather than drawing conclusions from one sequential warming run.

Prioritize avoiding unchanged browser snapshots if a browser widget is used, and consider a user-visible thermal response before critical status. Disabling an unnecessary camera or preview is a useful immediate diagnostic. A thermal response requires product decisions about output quality and camera availability; no application behavior was changed here. Capture CPU/GPU activity, actual browser snapshot/upload rates, thermal-service sensors, brightness and radio conditions in the next run.

No connected-device reproduction or physical temperature measurement was available in this investigation. This is a retrospective diagnosis with confirmed thermal behavior and code-level workload findings, not a verified single-subsystem root cause or a validated fix.
