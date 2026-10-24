# UI, integrations, settings, and storage analysis

Analysis date: 2026-10-21. Target: IRL Pro 3.5.22, versionCode 305221, APK SHA-256 `f5aa0343467b9890e72cf2292a4ddc68a15660c8e30996ab44f45e1e29c89bcd`.

This report describes static decompiled evidence. No target execution, endpoint requests, login, or private app-data extraction was performed. All source paths below are relative to the workspace and refer to JADX reconstruction, not original source. Some names are obfuscated. `evidence/ui/preferences.json` resolves preference XML keys, labels, defaults, and array references across all packaged `prefs_*.xml` files. It is a declaration inventory, not proof every entry is reachable or supported by every device.

## App and navigation

The app is a native Android activity/service implementation with AndroidX preferences, named Larix classes, and a substantial `com.wmspanel.libstream` media implementation. It is not a single packaged web app.

- `artifacts/jadx/sources/com/wmspanel/streamer/LaunchActivity.java:159` starts New Relic, migrates the legacy `vertical_video` preference to the current orientation key, parses an incoming settings link, and initializes the default timestamp layer. `p0()` at line 218 creates an intent for `StreamerServiceActivity`.
- `LaunchActivity.java:131` displays a yes/no settings-import confirmation when pending parsed settings have a summary. `onStart` at line 200 chooses that confirmation or the normal launch path.
- `artifacts/jadx/resources/res/xml/prefs_root.xml:2` declares sections for streamer/chat, bonding, outgoing connections, incoming connections, video, audio, recording, display, overlays, Grove settings links, advanced options, and support. The incoming-connections entry is explicitly hidden by `app:isPreferenceVisible="false"`. Its classes still exist, so presence does not prove a user-visible feature.
- `artifacts/jadx/sources/com/wmspanel/streamer/aux.java:500` launches `SettingsActivity`. The same shared activity class initializes live controls and chat at line 509.

The UI contains camera-preview, stream/record controls, camera selection, chat, alerts, battery and temperature, title and viewer count, connection state/rate, and audio-meter views. Source reconstruction alone does not establish the exact pixels on a particular Android version, nor whether all buttons are enabled for a particular camera.

## Chat and dashboard implementation

Chat is remotely hosted web content, while title and viewer counts have separate JSON fetch paths.

`aux.java:h1`, lines 515–591, reads Twitch/Kick usernames, strips/normalizes them through `c0.a`, lowercases them, and reads chat layout/filter preferences. With both usernames empty it hides the chat and associated controls. Otherwise it loads:

`https://irlpro.app/multichat/`

The query contains `twitchchannel`, `kickchannel`, `bots`, `show_platform`, `hide_commands`, `hide_badges=true`, `app_version=305221`, `androidId`, and `sessionId`. `hide_commands` is the inverse of the show-commands setting. It sets the WebView's initial scale to the configured chat scale. ConstraintLayout modifications at lines 594–621 implement small, right-side, and margin layouts.

Packaged defaults in `artifacts/jadx/resources/res/values/strings.xml:1002` and following are chat scale 220, alert scale 280, bot messages false, commands false, platform icon false, and viewer count true. The XML explicitly defaults small chat, right chat, and margin chat to false. Streamer preferences for usernames and dashboard credentials/custom URL start empty. StreamElements dashboard enable starts false. Values may differ for an existing installation with saved preferences.

`aux.java:623–649` rebuilds the dashboard pager. It may add StreamElements, Streamlabs, Toonation, and a user-supplied custom dashboard URL:

- StreamElements uses `https://streamelements.com/dashboard/xxxxxxxxxxxxxxx/activity/popout`, a literal placeholder-shaped path in this artifact. Do not substitute a real account ID from inference.
- Streamlabs uses `/dashboard/recent-events?token=<user setting>`.
- Toonation uses `https://toon.at/widget/controller/<user setting>`.
- Custom dashboard loads the saved URL unchanged at this call site.

`artifacts/jadx/sources/com/wmspanel/streamer/ui/AlertPageFragment.java:55–82` enables JavaScript, DOM storage, database support, image loading, and optional algorithmic darkening in dashboard WebViews. The WebView client at lines 151–188 keeps Twitch, StreamElements, toon.at, and google subdomains inside the view, opening other navigation through an Android VIEW intent. The check reads the scheme but does not restrict it in this particular helper. Page-finished handlers inject host-specific JavaScript for scrolling, a StreamElements localStorage marker, and Twitch consent-banner removal. Actual compatibility with today's sites is unverified.

This APK contains no bundled implementation of the multichat server page. Its full HTML/JS behavior cannot be reconstructed from its load URL alone.

## JSON endpoints and update cadence

`artifacts/jadx/sources/com/wmspanel/streamer/StreamerServiceActivity.java:510–554` schedules a runnable every 15,000 ms and cycles a counter from 0 to 4. Twitch is requested at counter 0 and Kick at counter 4 if the corresponding username exists. Therefore each platform is queried every five executions, approximately 75 seconds after steady scheduling, not every 15 seconds individually. This is derived from the counter and delay; no runtime timing measurement was made.

- Twitch statistics: `https://irlpro.app/api/twitch_stream/`, query `streamer`, `appVersion`, `androidId`, `sessionId`, lines 524–530. Response parser at lines 454–467 expects a JSON `data` array with exactly one object and reads `viewer_count` and `title`.
- Kick statistics: `https://kick.com/api/v2/channels/<URI-encoded username>`, lines 536–542. Parser at lines 487–496 expects optional non-null `livestream` and reads `viewer_count` and `session_title`.
- Bonding session creation: `artifacts/jadx/sources/p046LpT5/t0.java:74–105` builds `https://irlpro.app/api/bonding/` with query `destination`, `appVersion=305221`, `androidId`, `type=bond`, `sessionId`, and an empty request body supplied through `.g(...)`. It requires JSON `status="ok"`, then reads `streamid` and `stream_name`; error responses read `error`. The streaming report covers use of this session.

These are recovered client contracts. No claim is made that an endpoint is reachable today, that a server still accepts these inputs, or that authentication/rate-limiting behavior is known. Server source and service deployment are absent.

## User-defined overlays

Persisted `ImageLayerConfig` fields include name, URL, cached content, active flag, z-index, size, normalized x/y, HTTP ETag/Last-Modified, type, and update interval. `WebLayerConfig` adds width, height, CSS, percent scale, and draw destination. See their declarations in `artifacts/jadx/sources/com/wmspanel/streamer/ImageLayerConfig.java:10` and `WebLayerConfig.java:10`.

`artifacts/jadx/sources/com/wmspanel/libstream/b0.java:161–207` renders a WebView through an Android Presentation on a VirtualDisplay. A SurfaceTexture and Surface connect it to an external texture, with width, height, size, and position from the layer configuration. JavaScript, DOM storage, and database support are enabled. The page loads on the Presentation show callback at lines 210–215. At lines 218–224 custom CSS is interpolated into a JavaScript-created style element. This is an actual rendering path, not just a settings declaration.

`artifacts/jadx/sources/com/wmspanel/streamer/preference/PreferenceFragmentImageLayerWeb.java:26` sets the active-web-layer limit to 7. At lines 71–103 it counts active layers, orders the list, exposes URL/name/active controls, and disables inactive entries when the limit is reached.

`LaunchActivity.java:222–240` prepopulates an inactive-by-Java-default text timestamp layer when the layer box is empty and the once-only preference is unset. It uses date markup, z-index 4, size 0.2, x=0.5, y=0.98, and a one-second update interval. The code does not explicitly set active true here.

## Persistence and exact schema

`artifacts/jadx/sources/p046LpT5/h1.java:16` builds the ObjectBox store using application context. The default AndroidX SharedPreferences hold ordinary settings and user-entered chat/dashboard values.

`artifacts/jadx/sources/p046LpT5/g1.java:14–128` contains the complete recovered ObjectBox model with stable entity/property IDs and UIDs. It registers four entities:

1. Connection, entity ID 1. Fields include id, name, url, mode, active, username, password, auth, passphrase, pbkeylen, latency, maxbw, streamid, srtMode, retransmitalgo, bonding, nodrop, and ristProfile.
2. IncomingConnection, entity ID 2. It has a similar connection record plus buffering. It is a separate model entity even though its Java class extends Connection.
3. ImageLayerConfig, entity ID 3. Fields cover image/text source, cache, activity, z-index, transform, validators, and update interval.
4. WebLayerConfig, entity ID 4. Fields cover the image base plus dimensions, CSS, scale, and draw selection.

Cursor classes next to the entities show database read/write mappings. No field-level encryption is shown in these entity/model declarations or the inspected store-builder call. This is not a complete disk-forensics assessment and does not establish OS-level accessibility of private storage.

The APK manifest allows backup and uses `res/xml/backup_rules.xml`; that rules file excludes only two Google shared-preference files. The inspected XML does not explicitly exclude the app's own settings/database. Effective backup depends on Android version, platform policy, and actual backup operation, which were not tested.

## Settings import/export

Grove export is local URI construction, not a cloud save at this call path.

`artifacts/jadx/sources/com/wmspanel/streamer/preference/ExportGroveActivity.java:253–285` builds `larix://set/v1` with selected parameter groups, individual outgoing/incoming connections, and web layers. The class generates a QR bitmap at lines 230–247 and copies the link to clipboard at line 130.

`artifacts/jadx/sources/p038LPt6/c.java:37–93` serializes connections as repeated bracketed query keys such as `conn[][url]`, `conn[][name]`, `conn[][overwrite]=on`, `conn[][user]`, and `conn[][pass]`; SRT adds `srtmode`, `srtlatency`, `srtmaxbw`, `srtpass`, `srtpbkl`, and `srtstreamid`; RIST adds `ristProfile`; incoming connections use prefix `tb` and add `buffer`.

Other exporter groups in the same file are `enc[aud]`, `enc[record]`, `streamer`, `enc[vid]`, and `oweb`. `streamer` export can include user-provided Streamlabs/Toonation keys, and connection export can include passwords/passphrases. Therefore a real user's exported settings URL may carry credentials. No real user settings were accessed in this investigation.

`c.java:211–225` serializes web-layer URL/name/active/width/height/CSS/scale/draw/z and maps stored positions from 0..1 to integer -100..100 via `round(position*200)-100`.

`artifacts/jadx/sources/p046LpT5/w0.java:337–345` recognizes the configured deep-link scheme and set prefix. `F` at lines 379–399 URL-decodes query pairs, while `H` at 402–435 builds nested JSON arrays/objects from bracket keys. The confirmation path in `SettingsActivity.java:70–100` applies `w0.x` and camera-dependent `w0.J` only after the user selects yes. The root manifest also exposes the launcher for browsable `larix:` links. Import parsing exists independently of a network service.

## Diagnostics and privacy-relevant data flow

`LaunchActivity.java:167` initializes New Relic with an embedded application token. `LarixApplication.java:82–89` initializes the obfuscated Bugfender facade, enables logging, and installs a filter suppressing frequent media/Chromium log tags. The underlying facade identifies itself as Bugfender in `artifacts/jadx/sources/p008COm6/com8.java:27`. Embedded telemetry application identifiers are not reproduced here.

`aux.java:744–747` obtains `Settings.Secure` `android_id` and attaches it to New Relic. The chat URL and Twitch API query include that ID and the New Relic session ID. At lines 531–553 chat usernames/options are also attached to a `WebViewLoad/ChatBox` custom event and Bugfender device keys.

`StreamerServiceActivity.java:555–652` builds a `StatsPing/speaker` custom event from battery/charging/temperature, usernames, Android ID, video codec/size/FPS/bitrate mode, adaptive-bitrate state, web-layer count, broadcast/recording state, phone uptime, SRTLA host/port/IP, link names/rates/weights/flight/window/packet ACK/NAK/RTT, bonding service/session ID, stream duration, connection names/status/rates/schemes/totals. The same runnable schedules at 15 seconds. This proves event construction and SDK invocation; actual collection, transport contents, retention, consent state, and server storage were not observed.

Buttons, WebView loads, and caught exceptions also emit New Relic calls. Broad telemetry is implemented in the shipped client. It should not be confused with proof that every event is transmitted under all conditions.

## What remains unknown

- Exact runtime layouts, permission branches, device-specific preference availability, and successful streaming/overlay rendering.
- Remote multichat HTML/JS, Twitch proxy behavior, current dashboard authentication, and bonding server implementation.
- Actual saved user preferences or database rows, WebView cookies/session state, and on-disk backup behavior.
- HTTP request/response bytes or SDK telemetry transmissions in a running app.
- Exhaustive privacy/security review of all bundled SDK classes or every obfuscated helper. No vulnerability exploitability claim follows from this static inventory.
