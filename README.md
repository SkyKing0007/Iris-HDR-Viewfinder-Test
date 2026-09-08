# Iris HDR Viewfinder Test V1.4.11 V2.29

V2.29 is based on the exact successful V2.28 V1.1 compiled candidate: commit `07d6259f1c2a5a0d9143b5c4dafd7d466090220c`, Actions run `34159490301`, artifact `10032144212`.

## What V2.29 corrects

V2.28 proved the global SHORT→LONG registration repair, but the bathroom/window samples exposed a later source-ownership boundary failure: the 16×16 topology atlas could still become the final source selector, effective LONG information loss was restricted to bright/near-white regions, unsupported panes could be sampled with a path-propagated atlas residual flow, and the body-tone stage projected recovered scene energy back to <=1 before the HDR shoulder saw it.

V2.29 keeps successful V2.28 registration byte-identical and corrects only the downstream ownership/exposure/denoise boundaries:

- **Full-resolution final SHORT ownership.** The 16×16 atlas proves only connected recoverability. Final LONG-versus-SHORT source choice is re-evaluated at the actual output pixel, so one atlas cell can contain both source owners instead of producing a block-shaped final boundary.
- **Information-loss recovery is not a near-white synonym.** Observable LONG can surrender when exposure-normalized SHORT proves materially stronger medium/broad scene variation under the inherited radiometric validity checks. This covers real lost shading/detail in mid-bright exterior regions without making darkness itself a SHORT trigger.
- **Stable global registration fallback.** Mode 5 no longer consumes path-propagated atlas BA as a final warp. The globally aligned SHORT image remains the immutable fallback; the inherited V2.28 local residual field is used only where that actual field supplies it.
- **Recovered HDR energy reaches the shoulder.** `applyPhotographicBodyTone` no longer gamut-projects scene-linear values above 1.0 before `adaptiveHdrToneMap`. The existing V2.27 monotonic stop-domain shoulder remains the first display-referred compression owner for recovered highlights.
- **AUTO LONG is the physical-SNR body observation.** At low bracket, AUTO may no longer realize LONG with a shorter integration than SHORT. If needed, LONG preserves its independently solved exposure product by trading ISO for shutter; SHORT is not modified.
- **Smooth chroma noise cannot switch NAFNet off.** Local/broad chroma variance is no longer interpreted as structure veto evidence. Luma smoothness, gradient, and the existing luma + R-G/B-G coherent-structure tensor remain fail-closed protection for real microstructure.

## Bathroom ownership acceptance

The prior bathroom audit measured affected LONG exterior values around encoded 0.17–0.26. V2.28's `smoothstep(0.55, 0.86, ...)` effective-loss context was exactly zero there, so those pixels could never recover regardless of real SHORT detail. V2.29's observability context is active in that range while preserving the same information-dominance and radiometric-plausibility requirements. The regression suite also proves that two pixels in one connected atlas cell can resolve to different final owners.

## Protected architecture

Successful V2.28 `HdrGlView.java` and `JpegFusion.java` are byte-identical, including global registration, analysis-domain cycle correction, local bidirectional residual field, and saved-still orchestration. DNG, capture lifecycle, touch AF, media ownership, NAFNet weights/model, display controls, and all other runtime files are protected.

The V2.29 runtime change is exactly three files:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/NafNetDenoiser.java`

## Build proof

The successful V2.28 procedure remains the verification-mechanics authority. Its 15-step setup/compiler/build/post-build sequence and ordering are unchanged: exact authority artifact reconstruction, deterministic forward/rollback proof, Java 17, Android 37, Gradle 9.6.0, pinned glslang, complete reserved-identifier scan, real GLSL compile, real project Java compile, regression replay, frozen full `:app:assembleDebug`, post-build invariance, exactly-one-APK proof, and deterministic artifact export.

V2.29 is **PREPARED / UPLOAD-READY only after final clean-extract replay**. It is not build-proven until its own GitHub Actions run succeeds.
