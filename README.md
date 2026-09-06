# Iris HDR Viewfinder Test V1.4.11 V2.21

V2.21 is the **topology-complete recovery-domain + full-distribution AUTO tone correction** derived from exact successful V2.20 Actions authority (`b0fb984d31ea8204f89160db2f3f7c1e885624a6`, run `33980347593`, artifact `9973585046`).

## Exact device evidence

The supplied V2.20 AUTO window capture was a clean **4x / 2 EV** pair (SHORT 1/167 ISO50, LONG 1/167 ISO200). V2.20's convergent region reconstruction improved the previous 20x failure, but FUSED still contained disconnected flat gray islands absent from SHORT and LONG. That proves convergence distance is no longer the limiting factor: confidence-shaped holes remained inside the *allowed recovery domain* itself.

The same capture also exposed a separate presentation failure. AUTO selected about **-1.3 EV / Gamma 1.80**. Iris and Photon were already close in P90/P95/P98, but Iris P10/P25/P50 were substantially brighter, producing a hazy, low-contrast interior. The old AUTO fit controlled only P50/P90 and therefore could satisfy its objective by lifting the lower tonal range too much.

## V2.21 fusion contract

- Preserve the successful V2.20 GPU-only convergence loop and binary source ownership.
- LONG remains the primary clean saved body/default RGB/detail source.
- Existing successful V2.20 registration, actual exposure-ratio authority and appearance normalization remain unchanged.
- Strict SHORT recovery seeds still require real LONG loss + valid SHORT + trustworthy local geometry.
- The propagation domain is now **physical LONG loss + usable SHORT**, independent of local-confidence dropouts.
- Confidence can no longer punch a false LONG hole through the featureless interior of a proven clipped component.
- A target cell with a trustworthy measured residual may still block propagation when its motion disagrees with the connected component residual, preserving a real motion/disocclusion barrier.
- Mode 4 continues geodesic reconstruction until occupancy converges; Mode 5 remains binary real-source selection.
- Exact regressions retain 4x / 20x / 64x ratio invariance and add the V2.20 4x domain-hole failure.

## V2.21 AUTO presentation contract

- Physical capture/exposure remains unchanged.
- AUTO predicts the existing shader's final result but now fits **P10/P25/P50/P90 together**, rather than only P50/P90.
- Shadow targets come from the fused scene's own low-end ratios, so deep real shadows remain deep without imposing one fixed black point on every scene.
- LONG highlight pressure controls upper-body compression, preserving already-correct P90/highlights.
- The exact V2.20 window must no longer solve to Gamma 1.80 haze; shelf, chandelier and ordinary-scene regressions prevent a global-darkening workaround.
- AUTO Dehaze/Micro remains neutral and MANUAL presentation behavior remains unchanged.

## Runtime scope

Exactly two runtime files change relative to successful V2.20:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`

`HdrGlView.java`, `JpegFusion.java`, `CaptureSetSaver.java`, `MainActivity.java`, DNG/capture/flicker ownership and all other runtime files are byte-protected from successful V2.20.

V2.21 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real GLSL, real project javac and full `:app:assembleDebug`.
