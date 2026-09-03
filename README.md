# Iris HDR Viewfinder Test V1.4.11 V2.5

V2.5 is derived only from the exact successful V1.4.11 V2.4 GitHub Actions compiled candidate (`603de4980e454f6b6ec2f1acdd5cc5c1c5d05368`, run `33708991821`). No backup branch is created. No V1.5/RAW runtime code is imported.

## Change 1: Gamma-safe SHORT chroma

V2.4 solved the damaged-LONG veto problem, but its still-only recovery treated SHORT luminance/detail reliability and SHORT chroma reliability as identical. The supplied indoor ceiling capture proves that a very dark SHORT JPEG can contain tiny RGB/chroma variations that are nearly invisible at neutral presentation and become rainbow speckle when positive Gamma lifts the fused result.

V2.5 keeps V2.4 SHORT luminance/detail ownership, but splits chroma confidence before Gamma:

- V2.4 `reliableShortTextureWeight` remains the luminance/detail ownership scalar.
- SHORT chroma adds a stricter encoded-luma confidence ramp from `0.16` to `0.28`.
- Low-signal SHORT can still recover real luminance/detail while chromaticity remains LONG-owned.
- Strong-signal SHORT still receives complete SHORT chromaticity, so the earlier peach/orange damaged-LONG veto does not return.
- GPU saved fusion and CPU fallback implement the same math.
- No neighborhood filter, hallucination/fill, sharpening, or global desaturation is introduced.
- Gamma remains presentation-only and unchanged; the false chroma is stabilized before Gamma can expose it.

## Change 2: MANUAL SHORT slider owns the real shutter

V2.4 `HDR MANUAL SAFE` used one common flicker-safe integration for SHORT and LONG. That meant a requested `SHORT 1/240s` could still execute near LONG's `1/50s`, so moving the SHORT slider appeared to do nothing and the frozen still capture inherited the same wrong effective SHORT shutter.

V2.5 changes that contract:

- selected SHORT shutter remains the physical MANUAL SHORT integration;
- SHORT remains at sensor-minimum ISO;
- under detected 50/60-Hz flicker, only LONG may snap to a flicker-safe integration;
- LONG ISO compensates to preserve the requested LONG exposure product;
- if SHORT is dragged slower than LONG, SHORT clamps at LONG instead of silently swapping controls;
- the existing explicit 60-FPS frame-duration cap still applies when cropped-60 is enabled;
- frozen still capture continues to use the corrected effective SHORT exposure.

## Preserved successful V2.4 behavior

- damaged LONG cannot veto valid SHORT luminance/detail merely because the rendered JPEGs disagree;
- exact scene-cut AUTO behavior and 100ms live statistics;
- fixed ~8x / 3 EV AUTO bracket;
- Brightness **-16.0 EV through +1.0 EV** in 0.1 EV steps;
- Gamma **0.50 through 2.00**, 0.05 steps, 1.00 neutral;
- GLES3-primary full-resolution saved fusion with explicit CPU fallback only on GPU failure;
- live `mode=2` HDR ownership unchanged; these chroma changes are saved still `mode=3` only;
- RAW DNG + individual SHORT/LONG JPEG saves;
- producer-owned orientation/FIT, side-by-side app identity, fixed-height status row, and capture-time control freeze.

Before GitHub Actions this package is **PREPARED / UPLOAD-READY**, not build-proven. Actions must run the pinned real GLSL compiler, real project Java compiler, and full `:app:assembleDebug`.
