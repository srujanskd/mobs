# Render

GPU composition and cached overlay textures. The first overlay uses RootEncoder's stock filter path, as the plan asks, before any custom compositor is considered.

- `TextOverlay` — a text source with output-relative size, colors, wrap width, anchor, and margin.
- `TextRasterizer` — lays the text out with `StaticLayout` (wrapping, up to three lines, ellipsized) on a rounded background, at output resolution. It runs once per change, never per frame.
- `LogoOverlay` — an image source picked with the system photo picker (no storage permission). `ContentLogoDecoder` decodes it straight to its output size (`logoDecodeSize`: at most 15% of output height and 30% of width, never upscaled) as a software bitmap that GL can upload, so the full-resolution original never sits in memory. Like the text overlay, it lasts for the service session; persisting it belongs to scene storage.
- `placeOverlay` — converts the bitmap's pixel size and anchor into output percentages without stretching, keeping it inside the frame.

`RootEncoderStreamEngine` uploads each cached bitmap (logo, then text) through its own `ImageObjectFilterRender`. RootEncoder's `GlStreamInterface` releases every filter when GL stops (preview detached with no stream) and drops filters queued before GL starts, so the engine re-adds the overlay whenever GL starts. Because the filter draws into the composed output, the preview shows exactly what the encoder receives.

## Scene composition

`SceneRenderer` renders scene layers into one surface feeding RootEncoder. Camera2 writes directly into external GL textures; text and imported images are uploaded only when content or size changes. No camera frame is converted to a bitmap. Static scenes are paced at the output frame rate and release cameras. A pending scene update is coalesced to the latest request, and only one draw is queued. Removed textures, camera sessions, surfaces, devices, and the renderer thread are released across scene changes and preview shutdown.

Concurrent capture uses a reported rear/front camera pair, checks actual session configuration support, opens both cameras before configuring either, and requests 1920x1080 rear plus 1280x720 front. The encoder output remains 1920x1080 at 30 fps. Unique camera-buffer timestamps drive fresh-frame counters. Missing frames for five seconds or a camera failure closes capture and reports a scene error. Device tests must still establish orientation, cadence, output quality, transitions, and power on each supported phone.

The `Mobs.sceneFrame` Perfetto section includes CPU submission and output swap waiting; it does not measure GPU completion. EGL pixel tests validate normalized placement, ordering, opacity, blank scenes, cuts, and thread cleanup. See the [performance guide](../../../../../../../docs/performance-and-stability.md) for commands and physical acceptance requirements.
