# Iris HDR Viewfinder Test V1.4.23

V1.4.23 is an integrated flicker-safe SHORT search and bounded HDR reconstruction correction on top of successful V1.4.22. It preserves LONG as the natural appearance exposure and keeps V1.4.20+'s GPU-first live/still pipeline.

## Information-gain SHORT search

A darker SHORT is now a probe rather than an automatically accepted tier. Localized LONG-damaged cells must show measurable new usable recovery information before the darker tier is accepted. A no-gain or uncorrectable-flicker probe rolls back to the previously accepted tier and locks further search until the LONG scene changes materially. This prevents both chandelier runaway and stable-scene 1/240↔1/480 oscillation.

## Pair-rate rolling/PWM protection

A new `flicker_field.frag` performs a small 16x64 current-pair analysis before live HDR fusion. It estimates local SHORT/LONG illumination mismatch from same-row overlap, producing separate luma and chroma confidence. Fast/PWM-risk SHORT is normalized only where evidence is sufficient; otherwise that SHORT contribution fails closed locally.

Visible luma/chroma decisions no longer depend on the slower 32x24 / 200ms history. Flicker-safe/outdoor SHORT bypasses mandatory correction so normal scenes are not altered unnecessarily.

Still capture carries the same fast-SHORT guard. Its full-frame 16x64 field is learned from sparse overlap samples and uploaded once before the existing tiled GLES3 fusion, so full-resolution HDR math remains GPU-owned.

## Bounded single-ownership HDR fusion

LONG and calibrated SHORT remain in one scene-linear radiance domain through ownership. LONG owns valid scene content; safe SHORT owns genuinely lost LONG highlight detail. Source selection happens exactly once in radiance.

The fused radiance and the final mapped highlight are both bounded by their valid LONG/SHORT source endpoints. This removes the old double-ownership behavior that could synthesize hard rings, out-of-range contours or unstable highlight boundaries. Unstable SHORT chroma falls back to LONG chromaticity without discarding trustworthy SHORT luma/detail.

## Preserved architecture

- Adaptive LONG scene-body appearance policy remains unchanged.
- Exact exposure-generation SHORT/LONG pairing and frozen still controls remain unchanged.
- Five-knot scene response, GPU-tiled still fusion, parallel DNG/source I/O and EGL lifetime protections remain.
- 512-row tiled full-resolution still fusion continues to use the shared `hdr_display.frag` path.
- FOV/cadence, orientation, DNG, sRGB and stable-signing protections remain.

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
