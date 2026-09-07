# Iris HDR Viewfinder Test V1.4.11 V2.28

V2.28 is based on the exact successful V2.27 compiled candidate: commit `6d19588bd1028c66d80609c9a9119de30df63f80`, Actions run `34143712210`, artifact `10026908607`.

## What V2.28 corrects

The V2.27 backlit-window sample isolated a saved-still registration-confidence failure rather than another exposure/tone failure. LONG was flat/white while SHORT retained blue sky, foliage and window detail, but the final FUSED JPEG remained a neutral LONG plateau because the global registration confidence collapsed before any SHORT recovery seed could form.

V2.28 corrects that root cause without reopening the V2.27 fusion/tone architecture:

- **Analysis-domain cycle consistency.** Global registration is solved on a <=384px analysis image. Subpixel forward/backward cycle error is now judged in that same analysis domain instead of incorrectly applying analysis-scale residuals to full-resolution pixel thresholds.
- **Coarse anchor is global authority.** Strong bidirectional coarse registration can no longer be globally killed by exposure-dependent parabolic subpixel asymmetry. Genuine coarse inconsistency still fails closed.
- **Subpixel refinement is soft.** If refined cycle quality is poor, the applied shift falls continuously back toward the coarse anchor instead of invalidating the pair.
- **Local motion protection is unchanged.** The complete successful V2.27 bidirectional local residual-field method is byte-identical. Local motion/disocclusion barriers and the geodesic SHORT topology remain intact.
- **SHORT visual detail is an acceptance condition.** A recovered region must preserve SHORT-supported luminance ordering/spatial variation through the existing V2.27 whole-RGB tone path; merely changing color while retaining a flat LONG plateau is insufficient.

## Protected architecture

`hdr_display.frag`, `CameraController.java`, NAFNet/model, adaptive 1x..64x bracket, temporal body SNR, preview-only Camera2 NR, V2.27 highlight transfer/gamma fade, DNG, touch AF, background processing, media ownership and all non-registration runtime bytes are protected.

The V2.28 runtime change is exactly two files: `JpegFusion.java` and `HdrGlView.java`.

## Build proof

The successful V2.27 workflow remains the verification-mechanics authority. Java 17, Android SDK/API 37, Gradle 9.6.0, pinned glslang installation, reserved-identifier scan, exact runtime GLSL compile, real project Java compile, full `:app:assembleDebug`, post-build invariance and artifact upload retain the same successful ordering/mechanics. Only authority/version/hash/allowlist/regression/output payloads change.

V2.28 is **PREPARED / UPLOAD-READY only after final clean-extract replay**. It is not build-proven until its own GitHub Actions run succeeds.
