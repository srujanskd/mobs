package dev.mobs.render

/**
 * GLSL for the "vivid" look applied to every camera layer, close to the punchy, slightly warm
 * rendering of iPhone video. It runs per pixel in the compositor, so preview and stream match,
 * and costs a few ALU ops per fragment with no extra texture reads or passes.
 *
 * - Contrast: a light smoothstep S-curve adds midtone punch without clipping black or white.
 * - Vibrance: muted colors gain more saturation than already-saturated ones, so skies and
 *   foliage pop while neon signs and red clothing do not clip. Skin-like hues (r > g > b) get
 *   a smaller boost to keep faces natural.
 * - Warmth: a small red-up/blue-down shift on top of the camera's auto white balance.
 *
 * Tune the constants here; they act on gamma-encoded RGB, which suits a perceptual grade.
 */
internal const val CAMERA_COLOR_GRADE_GLSL = """
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CONTRAST = 0.16;
const float SATURATION = 0.10;
const float VIBRANCE = 0.32;
const float SKIN_PROTECT = 0.6;
const vec3 WARMTH = vec3(1.015, 1.0, 0.985);
vec3 grade(vec3 c) {
    c = mix(c, c * c * (3.0 - 2.0 * c), CONTRAST);
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float skin = clamp((c.r - c.g) * 5.0, 0.0, 1.0) * clamp((c.g - c.b) * 5.0, 0.0, 1.0);
    float boost = SATURATION + VIBRANCE * (1.0 - (mx - mn)) * (1.0 - SKIN_PROTECT * skin);
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, 1.0 + boost) * WARMTH;
    return clamp(c, 0.0, 1.0);
}
"""
