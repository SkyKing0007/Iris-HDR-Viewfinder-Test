# Iris HDR Viewfinder Test V1.4.11 V2.7

V2.7 is derived only from the exact successful V1.4.11 V2.6 GitHub Actions compiled candidate (`baea5c2f5ee865d1cededa42d3b14f42dc5f23bd`, run `33785286598`, artifact `9905210635`). No backup branch is created. The successful V2.6 capture/exposure architecture and exact 15-step Actions verification/build procedure remain the foundation.


## V1.1 compiler-only correction

The first V2.7 Actions attempt (`33812132460`) passed exact V2.6 authority reconstruction, pinned GLSL installation, reserved-identifier scanning and real GLSL compilation, then stopped at the unchanged real Java compiler gate because `JpegFusion.java` still called `mapLut(float,float[])` after the V2.7 rewrite had accidentally omitted that proven V2.6 helper. V1.1 restores the exact V2.6 `mapLut` implementation byte-for-byte. No registration, radiometric ownership, SHORT/LONG fusion, tone, capture or shader math changes. The failure is now a permanent verifier regression.

## LONG-reference registered SHORT

Saved HDR (`mode=3`) now registers SHORT into LONG coordinates before any SHORT ownership decision. Registration is bounded, forward/backward, uniqueness-aware and cycle-consistent; uncertainty fails closed. LONG never moves and remains the scene geometry/appearance reference.

The registered SHORT is then mapped into LONG's rendered linear-light appearance with one robust per-channel calibration derived from non-clipped overlap. V2.6's power-based saved-SHORT lift is not used by V2.7.

## Radiometric lost-LONG recovery

SHORT does not gain ownership merely because a region is bright. The center must have usable SHORT signal/headroom and the mapped SHORT must prove that LONG lost radiance. Multi-channel LONG damage is required, which protects single-channel color saturation.

Spatial coherence is deliberately sparse and implementation-identical on GPU and CPU: four cardinal samples at radius 2 plus four at radius 6. This lets smooth genuinely clipped whites/lights recover from SHORT without requiring artificial texture, while preventing broad gray/black replacement patches from spreading into healthy LONG regions.

When SHORT is admitted, the complete registered mapped SHORT RGB center sample is used. Otherwise the complete LONG RGB center sample remains authoritative. There is no luma-from-SHORT/chroma-from-LONG hybrid, neighbor RGB fill, inpainting, sharpening, local tone mapping or OpenCV runtime dependency.

## Real-photo simulation gate

The final implementation-equivalent architecture was accepted on the exact re-uploaded 1536x2048 LONG/SHORT pairs before runtime translation:

- Window `133823`: positive SHORT recovery; source-equivalent ownership above 0.5 is about 1.113%.
- Desk `134010`: positive recovery of genuinely clipped ceiling/light/paper/desk areas; about 8.235%.
- Under-desk `134056`: leakage-protection scene; about 0.103%, with the known gray bottle/binder contamination absent.

The Android-source-equivalent registration estimator remains subpixel/full-confidence on these pairs and reproduces essentially the same ownership rates as the accepted simulation. Low registration confidence, clipped SHORT, one-channel saturation and other unsafe conditions fail closed.

## Preserved V2.6 behavior

- V2.6 global photographic body tone and 0.70 HDR shoulder remain downstream.
- Brightness remains `-16.0..+1.0 EV`; Gamma remains `0.50..2.00`.
- Live `mode=2` retains the successful physical-ratio HDR behavior.
- AUTO/MANUAL capture ownership and real MANUAL SHORT shutter behavior are unchanged.
- CameraController, DNG ownership, capture routing, preview cadence/FOV/orientation and unrelated runtime files are protected by byte equality.

## Verification mechanics

V2.7 keeps the exact successful V2.6 15-step Actions sequence: exact prior artifact reconstruction and allowlist proof; Java 17; Android SDK 37; Gradle 9.6.0; pinned real `glslangValidator`; complete reserved-identifier scan; real GLSL compile; real project Java compile; regressions on the compiler-tested candidate; full `:app:assembleDebug`; exactly-one-APK and post-build invariance; artifact upload. Deterministic full-index forward/rollback patch proof remains inside the same precompiler gate.

Before GitHub Actions this package is **PREPARED / UPLOAD-READY, NOT BUILD-PROVEN**. No unrun real compiler is represented as passed.
