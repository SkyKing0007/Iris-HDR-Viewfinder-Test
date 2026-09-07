# Iris HDR Viewfinder Test V1.4.11 V2.27

V2.27 is based on the exact successful V2.26 compiled candidate: commit `555f06179f078f2a08453abbd032c67845b6e293`, Actions run `34082370328`, artifact `10004124294`.

## What V2.27 corrects

The V2.26 bright-window/chandelier/bathroom samples and the low-light closet samples isolate two different remaining problems under one AUTO architecture: valid SHORT highlight radiance was reaching the fused image but being compressed too aggressively near white, while low-dynamic-range scenes still wasted the second frame behind a forced bracket and then relied too heavily on digital brightness/gamma rescue.

V2.27 corrects the root owners instead of adding scene-specific thresholds:

- **Adaptive 1x..64x bracket.** LONG body/SNR and SHORT highlight-headroom targets remain independent. The old universal 4x floor is removed, so low-DR scenes may converge toward an equal-exposure pair while high-DR scenes naturally retain a wide SHORT/LONG separation.
- **Real two-frame body SNR when useful.** A near-equal, registered, radiometrically agreeing pair may average complete RGB with one conservative scalar weight. Motion/disagreement fails exactly to LONG. At a wide 4x HDR bracket SHORT body weight is zero.
- **Binary HDR highlights stay protected.** V2.26 registration/effective-loss ownership and the successful geodesic topology remain. A proven highlight is still aligned SHORT RGB; temporal body averaging cannot become fractional highlight color ownership.
- **Preview-only Camera2 denoise.** Live preview prefers `NOISE_REDUCTION_MODE_FAST` and may fall back to `HIGH_QUALITY` only when FAST is unavailable. HDR still source JPEGs remain NR-OFF; RAW/DNG truth is unchanged.
- **Guaranteed highlight separation.** The V2.26 exponential shoulder is replaced by a universal stop-domain transfer with a guaranteed detail slope through 0.965 and a slope-continuous specular tail. Gamma fades to identity through recovered highlights so it cannot flatten SHORT detail afterward.
- **SNR-aware AUTO presentation.** Weak/high-ISO physical evidence can no longer be hidden by simply reaching +1 EV / gamma 2.0. AUTO first uses the available physical pair; if evidence remains noisy, software rescue is restrained rather than exposing chroma noise.
- **Existing NAFNet remains constrained cleanup.** Its model and structure-protection bytes are unchanged. V2.27 does not solve noise by increasing neural strength on fabric, hair, foliage, text or other coherent detail.

## Protected architecture

V2.26 saved-still registration/render orchestration, mode-4 geodesic topology, manual flicker math, clean-AE bootstrap, NAFNet/model, touch AF, background-safe processing, DNG handling, media writing and non-HDR shaders remain protected. The runtime change is exactly four files: `CameraController.java`, `HdrGlView.java`, `JpegFusion.java`, and `hdr_display.frag`.

## Build proof

The successful V2.26 workflow remains exactly 15 steps. Java 17, Android SDK/API 37, Gradle 9.6.0, pinned glslang installation, reserved scan, exact GLSL compile, real project Java compile and full `:app:assembleDebug` step bodies are byte-identical to V2.26. Only authority/version/hash/allowlist/regression/output payloads change.

V2.27 is **PREPARED / UPLOAD-READY only after final clean-extract replay**. It is not build-proven until its own GitHub Actions run succeeds.
