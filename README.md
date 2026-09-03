# Iris HDR Viewfinder Test V1.4.11 V2.6

V2.6 is derived only from the exact successful V1.4.11 V2.5 GitHub Actions compiled candidate (`ac7b2d133f9a36d70a4e34a5011c2b3f6718d7de`, run `33712399624`). No backup branch is created. No V1.5/RAW runtime code is imported.

## Change 1: real SHORT provenance in damaged LONG regions

The supplied office capture proves that the SHORT JPEG already contains the real grass, pine needles, dirt, tree trunks and parked-car structure. V2.5 still allowed pixelwise source switching and a luma/chroma hybrid that could produce a rendered pixel that was neither the original LONG RGB nor the original SHORT RGB.

V2.6 changes the saved still (`mode=3`) contract:

- LONG remains the base source.
- A low-frequency 3x3 neighborhood with a six-pixel radius decides only a scalar LONG-vs-SHORT ownership mask.
- The center RGB triplet is never spatially filtered, filled or reconstructed from neighbors.
- When SHORT owns, all three channels come from the same captured SHORT JPEG pixel.
- Smooth bright areas do not qualify merely for being bright: LONG must show stronger damage and SHORT must have useful signal plus headroom.
- The saved-JPEG SHORT is not treated as RAW radiance. Its rendered RGB receives a bounded bracket-aware display lift while preserving RGB ratios, rather than full physical 8x/9x scene multiplication.
- The V2.5 luma-from-SHORT/chroma-from-LONG hybrid is removed.
- Live HDR (`mode=2`) keeps the successful V2.5 physical-ratio highlight behavior.

## Change 2: restrained global photographic body tone curve

Capture exposure and AUTO are intentionally unchanged in V2.6. The new curve is a display/tone-reproduction correction after fusion:

- true/near black stays anchored;
- shadows and midtones receive a modest global lift;
- the lift tapers away through upper midtones;
- by linear luminance 0.68 the extra body lift is zero;
- the existing HDR shoulder still begins at 0.70, so already-recovered highlights keep their headroom and ordering;
- RGB ratios are preserved and there is no local-HDR/pop operator.

Brightness (-16..+1 EV) and Gamma (0.50..2.00) remain optional presentation controls, not capture-exposure controls.

## Preserved successful V2.5 behavior

- real MANUAL SHORT shutter ownership, including MANUAL SAFE flicker behavior;
- exact scene-cut AUTO and fixed ~8x / 3 EV bracket;
- GLES3-primary full-resolution saved fusion with CPU fallback only after explicit GL failure;
- RAW DNG + individual SHORT/LONG JPEG saves;
- producer-owned orientation/FIT, side-by-side app identity, fixed-height status row and capture-time control freeze.

Before GitHub Actions this package is **PREPARED / UPLOAD-READY**, not build-proven. Actions must run the pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug`.
