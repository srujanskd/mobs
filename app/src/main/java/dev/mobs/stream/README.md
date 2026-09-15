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

`StreamSessionLifetime` makes shutdown decisions inside the service using session activity, which remains true through transport retries. It releases idle sessions after the editor unbinds, including when retries end after the editor has left.

The engine forces hardware video encoding and prepares once, at service creation, for a fixed 1920x1080 30 fps landscape profile. A
device that rejects that profile is reported through `StreamEngineState.profileError`; the engine
never substitutes a lower resolution or frame rate.
