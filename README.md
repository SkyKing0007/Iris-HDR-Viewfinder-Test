# Iris HDR Viewfinder Test V1.4.11 V2.25

V2.25 is based on the exact successful V2.24 compiled candidate: commit `f5ed95f8da45adc806dd421bc8c805155a311ccf`, Actions run `34042332593`, artifact `9992085735`.

## What V2.25 corrects

The combined bathroom-window and bathroom-bulb audit found that AUTO still had stale cross-owner highlight vetoes. SHORT and LONG were therefore often both photon-starved even though the architecture already has separate highlight and body exposures. The same samples also exposed a flat upper-highlight tone plateau and a software AUTO-presentation oscillation.

V2.25 implements the audit literally:

- **SHORT_HEADROOM_TARGET** is solved from SHORT P99 headroom and may move SHORT brighter or darker. Highlight clipping pressure may only reduce SHORT.
- **LONG_BODY_TARGET** is solved independently from robust LONG P50/P95 body statistics. SHORT P98, LONG P98 and global LONG near-clipping may no longer veto the body's exposure.
- **BRACKET_RATIO** is derived after those two targets. The existing 4x..64x physical contract remains, but V2.25 does not hardcode a 3EV/4EV/5EV bracket.
- Exposure convergence is bounded to 0.30EV normally and 1.0EV on a scene cut.
- The recovered-highlight shoulder is now strictly monotonic toward white instead of collapsing different valid highlight values into a gray ceiling. The same equation is used by the GPU shader, AUTO presentation predictor and dormant CPU fail-closed path.
- The CPU fallback precomputes the exponential shoulder into a LUT outside the full-resolution pixel loop, preserving the established no-expensive-per-pixel-operations regression.
- AUTO presentation counts each distinct complete SHORT/LONG pair once, requires three persistent pairs, uses deadbands, and limits accepted motion to 0.08EV brightness / 0.025 gamma per update. This targets the observed 1.75↔1.80 same-exposure software pumping.

## What stays protected

V2.24 `HdrGlView` registration/recovery ownership is byte-identical. The NAFNet model and structure-safe denoise/toggle, AF-only touch focus, background-safe processing, DNG handling, media writing, capture ownership and the non-highlight shaders remain unchanged.

The runtime change is exactly three files: `CameraController.java`, `JpegFusion.java`, and `hdr_display.frag`.

## Build proof

The successful V2.24 GitHub Actions procedure is preserved without reordering or substitution. V2.25 updates only authority/version/hash/allowlist/regression payloads required by the new candidate. Changed GLSL must pass the same pinned real `glslangValidator`; changed Java must pass the real project `javac`; full `:app:assembleDebug` remains mandatory.

V2.25 is **PREPARED / UPLOAD-READY only after clean-extract replay**. It is not build-proven until its GitHub Actions run succeeds.
