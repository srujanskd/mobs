# Stream

`StreamEngine` is the boundary between the app and the capture/encode/transport library. Screens,
scenes, and goals depend on this interface only, so replacing the engine cannot reach into scene
storage or UI.

- `StreamEngine.kt` — the interface, the stream profiles, and the observable session state.
  Transport connection state is deliberately separate from any YouTube broadcast state.
- `RootEncoderStreamEngine.kt` — the RootEncoder implementation. The only file allowed to import
  `com.pedro.*`. RootEncoder remains a candidate until the Pixel 7 and Galaxy S25 Ultra power and
  1080p30 gates pass.
- `StreamService.kt` — foreground service that owns the engine for the life of the session,
  independently of the editor screen. Declared with the camera and microphone service types, so it
  must be started from a visible user action with both permissions already granted.

- `StreamDestination.kt` — validated RTMP(S) server plus stream key. The key appears only in the
  endpoint handed to the engine; `toString()` and `ConnectionState` carry the server alone.
- `ReconnectPolicy.kt` — exponential backoff (1 s doubling to 30 s, 20 attempts per outage). The
  budget resets on every successful connection. RootEncoder's own retry counter is set unbounded
  because it only resets on stop, which would end a long session after enough separate drops.

RTMPS hostname verification is switched on explicitly; RootEncoder 2.8.1 leaves it off, which would
accept any CA-signed certificate for any host while the stream key is sent. `StreamStats` exposes
bitrate and dropped video frames, sampled on RootEncoder's roughly once-a-second bitrate callback.

`StreamSessionLifetime` makes shutdown decisions inside the service using session activity, which remains true through transport retries. It releases idle sessions after the editor unbinds, including when retries end after the editor has left.

The engine forces hardware video encoding and prepares once, at service creation, for a fixed 1920x1080 30 fps landscape profile. A
device that rejects that profile is reported through `StreamEngineState.profileError`; the engine
never substitutes a lower resolution or frame rate.

Only bitrate adapts. RootEncoder's `QueueAwareBitrateAdapter` lowers the encoder bitrate when the
send queue backs up and probes back toward the profile's 10 Mbps, with a floor of 1 Mbps.
`StreamStats.targetBitrateBps` exposes the bitrate it currently requests next to the measured rate.

- `ThermalMonitor.kt` — Android thermal status through its listener, and thermal headroom polled
  every 10 seconds (the documented minimum) on API 30+. The service owns it; the editor receives it
  with the engine as a `BoundSession`.
- `ThermalGuard.kt` — stops a live stream at `CRITICAL` or above, reported as
  `ConnectionState.Failed` with the reason. The editor also refuses Go Live at that level. Lower
  levels never change the stream; preview reduction is the planned first response.
- `SessionRecorder.kt` — one CSV row per second while live: sent and target bitrate, encoder
  output fps, dropped frames, preview state, thermal status and headroom, battery percentage,
  temperature, current, voltage, and whether power was connected. No server or key is written.
  One file per session, newest 20 kept, in the app's external files directory:
  `adb pull /sdcard/Android/data/dev.mobs/files/sessions`. Rows with `plugged=true` do not
  measure battery drain, and the sign of `current_ua` varies by vendor.
