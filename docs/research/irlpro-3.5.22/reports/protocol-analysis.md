# Streaming protocols, credentials and TLS in IRL Pro 3.5.22

## Evidence boundary

This report uses the supplied APK's JADX reconstruction under `artifacts/jadx/sources/`. Every path below is relative to that directory and line numbers refer to the saved reconstructed Java, not original source. Code paths are static observations. No network requests, credentials, stream starts or certificate tests were performed for this report. The UI-to-service and connection-dispatch references establish reachable implementation paths, not observed successful interoperability.

## Protocol dispatch

`com/wmspanel/streamer/StreamerService.java:1156` implements `R(Connection)`, the connection creation path. It chooses SRT for `q2.isSrt`, RIST for `q2.isRist`, and otherwise constructs a `com.wmspanel.libstream.com4` configuration. `p046LpT5/v0.java:42` copies URL, media mode, auth enum, username and password from the database `Connection` to that object. `com/wmspanel/libstream/o.java:270` dispatches this configuration's URI scheme:

- `rtmp` creates `s`, the `RtmpConnection` implementation, using port 1935 when omitted.
- `rtmps` creates the same `s` with the TLS boolean true and defaults to port 443.
- `rtsp` creates `v`, the `RtspConnection` implementation, defaulting to port 554.
- `rtsps` creates the same `v` with the TLS boolean true and defaults to port 322.
- Other schemes are rejected by this TCP-streaming dispatcher with `unsupported scheme` and return -1.

The RTMP path joins URI path elements between the authority and last element into the app name, and uses the final element as the stream name. It requires at least five slash-separated elements. URI user-info is removed from the reconstructed protocol URL before creating the connection. Authentication fields come from the separate username/password config fields. See `o.java:285`, `o.java:303` and `o.java:316`.

Both `s` and `v` inherit `a0`, the shared nonblocking TCP/optional-TLS connection. `o.java:410` enqueues each instance, and `o.java:149` drains the queue and calls `a0.f()` to connect. `a0.java:126` creates a nonblocking `SocketChannel`, optionally sets SO_SNDBUF, and registers with a selector during connect. Reads/writes and queueing are implemented with ByteBuffers. `a0.java:302` exposes bytes/frame counters. This is Java stream transport rather than direct JNI in the RTMP/RTSP branch.

## RTMP state and authentication

`com/wmspanel/libstream/s.java` is labeled `RtmpConnection`. Its state machine performs the RTMP handshake, AMF `connect`, `createStream`, and `publish` commands. The command serialization is supported by `t.java` and response parsing by `r.java`. The flow is visible at `s.java:473`, `s.java:519`, `s.java:541` and `s.java:677`.

The AMF connect object includes `app`, `tcUrl`, `flashVer`, `fpad`, `capabilities`, `audioCodecs`, `videoCodecs` and `videoFunction`. `tcUrl` is formatted with the literal `rtmp://` even when the underlying socket has TLS enabled. The actual socket's TLS choice is independent of that string. See `s.java:491` and `o.java:299`.

The auth enum supports provider-specific RTMP handshakes. `s.java:263` builds the Adobe/RTMP-style `authmod=adobe&user=...` app query. With a server salt it computes an MD5-based response using a four-byte SecureRandom client challenge and the saved server challenge/opaque values. `s.java:290` handles `authmod=llnw`, using server nonce, client nonce, nc=00000001 and qop=auth. The helper `p037LPt5/y.java:29` computes MD5 in lowercase hex; `y.java:58` computes MD5 then base64 for the alternate response construction.

`o.java:497` retains auth context for LLNW/RTMP/AKAMAI and recreates the connection after an AUTH_FAIL when the required server challenge fields and nonempty credentials exist. The recreated connection preserves the original TLS boolean at `o.java:531`. These are recoverable auth algorithms; no claim is made that any current external provider accepts them.

## RTSP state, RTP and credentials

`com/wmspanel/libstream/v.java:117` names states OPTIONS, OPTIONS_AUTH, ANNOUNCE_WAIT, ANNOUNCE, ANNOUNCE_AUTH, SETUP_VIDEO, SETUP_AUDIO, RECORD and send states. It constructs an SDP ANNOUNCE, then SETUP/RECORD requests. Audio SETUP requests TCP-interleaved channels 2-3; video SETUP requests channels 0-1. See `v.java:437`, `v.java:473`, `v.java:490`, `v.java:507`. Thus this recovered RTSP publisher explicitly requests RTP/AVP/TCP interleaving, not UDP media transport.

`v.java:279` records digest realm/nonce or basic realm from WWW-Authenticate headers. `v.java:802` prefers digest when realm, nonce, username and password exist, constructing:

```text
MD5( MD5(username + ':' + realm + ':' + password)
     + ':' + nonce + ':' + MD5(method + ':' + uri) )
```

The Authorization string includes username, realm, nonce, uri and response. This implementation does not add qop/nc/cnonce in this RTSP digest branch. Otherwise, if a basic realm and credentials exist, it emits `Authorization: Basic ` followed by base64 of the US-ASCII `username:password`. Base64 is encoding; transport privacy for that branch depends on using RTSPS. `w.java:107` participates in challenge parsing. No password values from user data were read.

## TLS certificate behavior and reachability

The following chain establishes that the custom trust manager is part of the RTMPS/RTSPS implementation, rather than an unused helper:

1. Service creates TCP protocol config in `StreamerService.java:1186`, via `v0.e`.
2. `o.java:299` sets TLS true specifically for `rtmps`, then constructs `s` at line 324. `o.java:351` sets TLS true for `rtsps`, then constructs `v` at line 355.
3. Both constructors pass that flag to `a0`, at `s.java:206` and `v.java:132`.
4. The queued connection's `f()` method is called by `o.java:160`.
5. `a0.java:215` tests the flag and calls `h()` before connecting.
6. `a0.java:158` obtains `SSLContext.getInstance("TLSv1")`, initializes it with a new `a0.aux` trust manager, creates an SSLEngine for host/port, and sets client mode true.
7. `a0.java:100` defines that `X509TrustManager`. `checkServerTrusted` and `checkClientTrusted` only log and return. `getAcceptedIssuers` returns null. They do not check a chain against a trust store in this code.
8. On socket completion `a0.java:377` starts the handshake. It executes SSLEngine delegated tasks and starts protocol processing after handshake completion.

There is no `setEndpointIdentificationAlgorithm`, explicit `HostnameVerifier`, or equivalent hostname comparison in this `a0` implementation. Supplying host/port to `createSSLEngine` does not itself show a hostname-verification call. The source-derived conclusion is specific: this recovered RTMPS/RTSPS path installs a trust manager with no chain-validation logic and shows no explicit endpoint-identity verification. The effective TLS versions, Android provider behavior, certificate acceptance and live connection outcome were not tested. In particular, the algorithm name `TLSv1` is not evidence of the version negotiated in a real session.

This finding must not be generalized to every HTTPS request in the app. The bonding helper below uses a different networking stack with different TLS setup.

## HTTP(S), bonding and a separate trust path

`p055Lpt5/q2.java:28` recognizes http and https for playback checks; its output-supported predicate at line 64 includes https but excludes plain http. `com/wmspanel/streamer/Connection.java:62` returns true from `g()` for any https output URL, even when the bonding flag is false. That routes the connection through the bonding-session path in `StreamerService.java:1662`. This is not a direct HTTP media-output implementation in `libstream/o`; that class rejects schemes outside RTMP/RTMPS/RTSP/RTSPS.

`p046LpT5/t0.java:74` constructs the bonding request to `https://irlpro.app/api/bonding/` with query parameters `destination`, `appVersion=305221`, `androidId`, `type=bond` and the current New Relic `sessionId`. It uses a POST with an empty form body. The destination URL is a query parameter, not media bytes streamed by this Java HTTP client. It parses JSON status/error and streamid/stream_name response fields. `t0.java:136` creates a replacement Connection to `srtla://stream.global.irl.run:5000`, attaches the returned streamid, and sets latency 2500. The destination string may contain user-provided stream parameters, so it should be treated as configuration data during any future runtime capture.

The same helper accepts a selected mode `nodrop` in its dispatch, but its `c(Context,String)` throws `UnsupportedOperationException("Not implemented")` at `t0.java:129`. The presence of that branch does not establish a functioning nodrop service.

The bonding HTTP client is `v.c`, an obfuscated OkHttp implementation recognized by its API structure and New Relic OkHttp instrumentation calls. `t0.java:55` changes connect/read/write timeouts to five seconds and does not override trust or hostname verification. `v/c.java:219` sets the default hostname verifier to `h0.prn`; `v/c.java:467` obtains the platform trust manager when no custom socket factory was supplied. `e0/com6.java:230` obtains TrustManagerFactory.getDefaultAlgorithm() and initializes it against a null KeyStore, meaning platform defaults. `a0/com2.java:481` explicitly invokes the configured HostnameVerifier after the SSLSocket handshake and throws SSLPeerUnverifiedException on mismatch. These source paths establish a separate default trust/hostname-verification setup for this helper. No certificate pinning requirement or successful current server connection was verified.

## Limits

This report recovers the primary Java output dispatch, RTMP auth construction, RTSP interleaving/auth, and the distinct streaming-TLS and bonding-HTTPS trust paths. It does not reverse engineer every RTMP chunk parser, media packetizer, HTTP playback class, Android WebView, or third-party telemetry transport. It does not establish live vulnerability exploitation, current endpoint operation, backend internals, or service-account behavior. The main service method `e1()` has visibly inconsistent JADX control-flow reconstruction, so this report avoids treating its displayed loop ordering as authoritative runtime ordering.
