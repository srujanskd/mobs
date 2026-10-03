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
