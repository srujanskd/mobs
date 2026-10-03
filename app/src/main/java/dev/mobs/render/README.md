# Render

GPU composition and cached overlay textures. The first overlay uses RootEncoder's stock filter path, as the plan asks, before any custom compositor is considered.

- `TextOverlay` — a text source with output-relative size, colors, wrap width, anchor, and margin.
- `TextRasterizer` — lays the text out with `StaticLayout` (wrapping, up to three lines, ellipsized) on a rounded background, at output resolution. It runs once per change, never per frame.
- `placeOverlay` — converts the bitmap's pixel size and anchor into output percentages without stretching, keeping it inside the frame.

`RootEncoderStreamEngine` uploads the cached bitmap through an `ImageObjectFilterRender`. RootEncoder's `GlStreamInterface` releases every filter when GL stops (preview detached with no stream) and drops filters queued before GL starts, so the engine re-adds the overlay whenever GL starts. Because the filter draws into the composed output, the preview shows exactly what the encoder receives.
