# Render

GPU composition and cached overlay textures. The first overlay uses RootEncoder's stock filter path, as the plan asks, before any custom compositor is considered.

- `TextOverlay` — a text source with output-relative size, colors, wrap width, anchor, and margin.
- `TextRasterizer` — lays the text out with `StaticLayout` (wrapping, up to three lines, ellipsized) on a rounded background, at output resolution. It runs once per change, never per frame.
- `LogoOverlay` — an image source picked with the system photo picker (no storage permission). `ContentLogoDecoder` decodes it straight to its output size (`logoDecodeSize`: at most 15% of output height and 30% of width, never upscaled) as a software bitmap that GL can upload, so the full-resolution original never sits in memory. Like the text overlay, it lasts for the service session; persisting it belongs to scene storage.
- `placeOverlay` — converts the bitmap's pixel size and anchor into output percentages without stretching, keeping it inside the frame.

`RootEncoderStreamEngine` uploads each cached bitmap (logo, then text) through its own `ImageObjectFilterRender`. RootEncoder's `GlStreamInterface` releases every filter when GL stops (preview detached with no stream) and drops filters queued before GL starts, so the engine re-adds the overlay whenever GL starts. Because the filter draws into the composed output, the preview shows exactly what the encoder receives.

## Scene composition

`SceneRenderer` renders scene layers into one surface feeding RootEncoder. Camera2 writes directly into external GL textures; text and imported images are uploaded only when content or size changes. No camera frame is converted to a bitmap. Static scenes are paced at the output frame rate and release cameras. A pending scene update is coalesced to the latest request, and only one draw is queued. Removed static textures and camera resources are released across scene changes. Browser documents are retained by exact widget URL for the compositor session, so scene cuts, duplicated sources, visibility changes, and viewport resizing preserve chat history and live connections. Inactive documents stop bitmap snapshots while JavaScript continues. An explicit refresh or preview shutdown destroys the retained documents and textures. The renderer thread is released at shutdown.

Concurrent capture uses a reported rear/front camera pair, checks actual session configuration support, opens both cameras before configuring either, and requests 1920x1080 rear plus 1280x720 front. The encoder output remains 1920x1080 at 30 fps. Camera layers fill their scene bounds with a centered, aspect-preserving crop. Resizing an inset or applying a quarter-turn rotation crops the excess edges instead of stretching faces. The crop uses each input's capture dimensions and transformed texture axes, preserving producer orientation, crop metadata, and mirroring. Unique camera-buffer timestamps drive fresh-frame counters. Missing frames for five seconds or a camera failure closes capture and reports a scene error. Device tests must still establish orientation, cadence, output quality, transitions, and power on each supported phone.

The `Mobs.sceneFrame` Perfetto section includes CPU submission and output swap waiting; it does not measure GPU completion. EGL pixel tests validate normalized placement, ordering, opacity, blank scenes, cuts, and thread cleanup. See the [performance guide](../../../../../../../docs/performance-and-stability.md) for commands and physical acceptance requirements.


## Browser capture

Browser overlays render at their output pixel dimensions, up to the encoder's full frame, with a 100% page scale. Capture is paced by Android Choreographer at the stream frame rate, currently 30 fps, instead of a 100 ms timer. A single reusable transparent bitmap is borrowed by the GL uploader and returned through its completion callback. Consumers must not recycle it. Slow uploads skip captures instead of queuing stale frames. The bitmap is cleared before each draw, and layout runs when requested or when the viewport changes. GL texture storage is allocated on first upload or a size change; subsequent frames use `texSubImage2D`. Inactive pages release capture buffers and GPU textures while keeping their documents alive.

Capture still draws a software WebView into a bitmap on the main thread. Native resolution removes the old half-size upscale but costs more pixel work. This is not a GPU-only browser compositor and does not establish smoothness for complex CSS, WebGL, embedded video, multiple simultaneous widgets, or a thermally throttled phone. Validate those cases on the target phone alongside camera capture and encoding before claiming sustained 30 fps.

Unchanged browser pages reuse their last uploaded texture. WebView invalidations mark the page dirty, with a 250 ms capture grace period after changes to keep asynchronous animation updates smooth. After that, idle pages capture only a one-second safety refresh for WebView implementations that miss invalidations. Page load, reactivation and resizing force a fresh capture. JavaScript and live connections keep running while idle or off scene. This reduces bitmap clearing, software drawing and GL uploads for quiet chat widgets without reducing camera or encoder cadence. Continuously animated widgets still require full-rate work.
