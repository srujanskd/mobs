# YouTube integration research

Verified against official documentation on 2026-09-08. Recommendations below are design judgments, not platform guarantees.

## Recommended delivery order

1. Ship camera/audio/scenes to a user-provided RTMPS URL and stream key; the user creates the broadcast in YouTube Studio. Text and manually updated goals need no YouTube API. Treat stream keys as credentials and redact logs.
2. Add Google authorization and create/select broadcast flows, plus a native chat overlay. A stream key alone does not identify its broadcast/chat; manual streaming must also accept the YouTube video URL/ID for chat.
3. Add automatic goal providers only with explicit data limitations. Preserve manual goals as a reliable fallback.

## Ingest and lifecycle

YouTube recommends RTMPS. Use H.264 video and AAC audio for a conservative first implementation, CBR, and two-second keyframes. The required product profile is now 1080p30. YouTube currently recommends 10 Mbps H.264 for it, plus audio. Benchmark that setting first; test bitrate adaptation with resolution and frame rate fixed, validating motion quality and stream health. Lower resolution/frame-rate profiles do not satisfy the product requirement. [Encoder requirements](https://support.google.com/youtube/answer/2853702?hl=en)

With the API, create a `liveBroadcast`, create/reuse a `liveStream`, and bind them. Send media, wait for stream status `active`, then transition the broadcast to `live`; complete it on intentional stop. Model broadcast status separately from transport connectivity. For IRL reconnects, avoid blindly enabling auto-stop: YouTube documents that it ends a broadcast about a minute after ingest stops. [Broadcast lifecycle](https://developers.google.com/youtube/v3/live/life-of-a-broadcast)

RTMPS still uses `cdn.ingestionType=rtmp`; use `cdn.ingestionInfo.rtmpsIngestionAddress` and `streamName`, rather than guessing endpoints. Stream health reports issues such as missing audio or low bitrate. [Stream resource](https://developers.google.com/youtube/v3/live/docs/liveStreams)

Creating broadcasts requires user OAuth authorization, using a supported scope such as `youtube.force-ssl`; an API key cannot authorize channel mutations. YouTube does not support service accounts for normal channel access. Plan consent configuration and applicable OAuth verification before distributing publicly. [Broadcast creation](https://developers.google.com/youtube/v3/live/docs/liveBroadcasts/insert), [YouTube authorization](https://developers.google.com/youtube/v3/guides/authentication), [OAuth verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)

## Chat as a scene source

Use `liveChatMessages.streamList` preferentially. It is a server stream with recent history on first connection and a `nextPageToken` for resuming; retain the token and deduplicate message IDs after reconnects. It cannot retrieve arbitrary earlier history. Handle disabled, ended, forbidden, and exhausted-quota states explicitly. [streamList](https://developers.google.com/youtube/v3/live/docs/liveChatMessages/streamList)

The official streaming guide supplies a protobuf definition from which a Java client can be generated and demonstrates OAuth or API-key authorization. Discover a video's chat using `videos.list` and `liveStreamingDetails.activeLiveChatId`; owned broadcasts also expose `snippet.liveChatId`. Prototype Android gRPC consumption early. [Streaming guide](https://developers.google.com/youtube/v3/live/streaming-live-chat)

If falling back to REST `list`, obey `pollingIntervalMillis` and advance `nextPageToken`; fixed rapid polling wastes quota and is rate-limited. Chat failure must not stop video. [REST chat](https://developers.google.com/youtube/v3/live/docs/liveChatMessages/list)

Render native chat data into the same GPU composition as camera/text, not merely as an Android UI layer. Provide font, colors, background opacity, number of messages, expiry, positioning, and show/hide controls. Process deletion and banned-user events so queued/displayed messages are removed. Already broadcast pixels cannot be retracted; a short optional moderation delay can help. The API provides author identity/roles, text, deleted-message events, bans, Super Chats and membership events; deleting a message on YouTube requires owner/moderator authorization. [Chat resource](https://developers.google.com/youtube/v3/live/docs/liveChatMessages)

## Goals and constraints

- **Manual goal:** title, current value, target, units, colors; local and fully reliable. Recommended MVP.
- **Subscriber goal:** channel statistics round subscriber count to three significant figures. Label it approximate; do not promise exact instant increments or a complete new-subscriber event feed. [Channel statistics](https://developers.google.com/youtube/v3/docs/channels)
- **Super Chat goal:** sum `amountMicros` using integer arithmetic and preserve ISO currency codes. Different currencies must not be added as though they were the same currency. Label the result observed contributions, not net creator revenue. Dedupe IDs and persist a checkpoint; recent chat history may miss earlier contributions if connected late. [Chat monetary fields](https://developers.google.com/youtube/v3/live/docs/liveChatMessages)
- **Historical reconciliation:** `superChatEvents.list` lists a channel's Super Chats from the prior 30 days with owner authorization; investigate matching to the desired goal window before claiming complete broadcast totals. [Super Chat events](https://developers.google.com/youtube/v3/live/docs/superChatEvents/list)
- **Availability:** Super Chat/Stickering depends on channel eligibility and is unavailable for several types of content, including made-for-kids, unlisted, private, age-restricted, and streams with chat off. Test standard chat on an unlisted stream; use fixtures for Super Chats until an eligible public end-to-end test is arranged. [Eligibility](https://support.google.com/youtube/answer/9277801?hl=en)

## Release risks and acceptance checks

The current quota page states a default 10,000 daily units combined for endpoints other than the separate search/upload buckets, reset at midnight Pacific. Live APIs consume quota; do not assume a per-user allocation or unlimited chat. Measure actual chat costs and quota behavior in the project; the current calculator does not give a clear `streamList` cost. Test quota exhaustion and reconnect backoff. [Quota reference](https://developers.google.com/youtube/v3/determine_quota_cost)

Creators need a verified channel and no live restrictions in the prior 90 days; the minimum live-streaming age is 16. YouTube documents mobile and encoder workflows separately, so do not copy YouTube-app-specific subscriber thresholds into this encoder's eligibility UI. Surface YouTube's actual eligibility errors and direct creators to enable live streaming. [Live eligibility](https://support.google.com/youtube/answer/2474026?hl=en)

Acceptance checks: an unlisted 30-minute stream containing camera, microphone, text, goal, and live chat; scene switching without reconnecting encoder; chat arriving once after reconnect; deleted chat disappearing; chat unavailable while video remains live; expired authorization; quota failure; ingest reconnect without unintended broadcast completion; observed contribution totals staying consistent after process restart.
