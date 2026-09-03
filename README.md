# Iris HDR Viewfinder Test V1.5.4 V1.2

V1.5.4 V1.2 is the **semantic CFA provenance + literal MANUAL shutter correction** built directly from the exact successful V1.5.4 V1.1 GitHub Actions compiled candidate. It addresses the recurring cyan/magenta highlight-edge, broken clipped-boundary and pale partial-highlight artifact family at the physical reconstruction contract rather than adding another scene threshold or post-RGB color patch.

## Runtime authority

Exact successful V1.5.4 V1.1 Actions candidate:

- commit `cc45ba874776f0ce99e9503497a970d6e4b57cbe`
- tree `2911c0edff409ac65e337bd7871af958cb9f675b`
- Actions run `33711764471`
- job `100512589458`
- artifact ID `9877178323`
- artifact `Iris-HDR-Viewfinder-Test-V1.5.4-V1.1`
- artifact ZIP digest `sha256:9aab5e3acede1d5bfe00614c66bb30bd4fad84baeedc3290145aafe98e79d640`
- APK SHA-256 `c9bc4e7887824904750bebf6db3108ac8073613125277b4d950db357454ec0d1`
- source-candidate TAR SHA-256 `9aaad68090fa2bf0c8cc422e6281171e34c83bde40cbc030722fbe3fe3abf806`
- runtime manifest SHA-256 `505eaeb9384a62cf24e000a4ff06f3ab36f535fcda2bbd61807376c17e99899f`
- repository manifest SHA-256 `9edeccd8f60452f29d5e1d51a8cd080dacd100ef473b60ba18059f74f03dd089`

The candidate is authority-seeded from that exact Actions source universe. Repository `app/src` is not used as runtime authority.

## Verification-mechanics authority

The build workflow preserves the exact successful V1.5.4 V1.1 16-stage sequence: parent artifact reconstruction and manifests, exact allowlists/protected hashes, semantic validators, complete GLSL reserved-word scan, pinned real `glslangValidator`, real project Java compile, deterministic full-index forward/rollback patches at `core.abbrev` 7/12/40 with `fuzz=0`, PRE-BUILD SAFETY PROOF, full `:app:assembleDebug`, exactly one APK, signing proof, post-build invariance and clean-extract replay. No build/compiler mechanics are redesigned.

## V1.5.4 V1.2 reconstruction contract

### 1. Terminal semantic CFA provenance

The fused RAW carrier keeps the successful V1.5.4 V1.1 whole-Bayer-quad exposure ownership, but downstream **color authority is no longer a continuously guessed float**. Every site carries one semantic state:

- `0 = NORMAL_MEASURED`
- `1 = CENSORED_UNKNOWN_CHROMA`
- `2 = SHORT_VALIDATED`

Confidence may still be used to prove geometry/radiometry before classification, but it cannot change a terminal semantic meaning downstream.

### 2. Censored is brightness evidence, not color evidence

A censored LONG observation may retain a finite scene-linear/lower-bound carrier so highlight shape and brightness are not discarded. But the demosaic maps `CENSORED_UNKNOWN_CHROMA` to **zero color trust**. It cannot partially steer directional color reconstruction or R-G/B-G opponent reconstruction merely because its numerical value is plausible.

This is the fail-closed rule: uncertain highlights may become neutral, but they may not manufacture cyan/magenta Bayer-edge structure.

### 3. SHORT color must be physically proven

`SHORT_VALIDATED` requires all of the following after the already-proven V1.5.4 V1.1 geometry/radiometry stages:

- complete usable unsaturated SHORT CFA phase support;
- SHORT/LONG correspondence support;
- observable geometry from locally proven flow or strong coherent-neighbor geometry.

The final global fallback flow remains useful as safe sampling geometry, but its confidence is intentionally below the semantic color-validation gate. A saturated/textureless center therefore cannot self-certify SHORT chroma merely because a smooth global vector exists.

### 4. Existing physical clipping protections remain

Preserved unchanged in architecture:

- one coherent exposure owner per exact 2x2 Bayer quad;
- locally proven -> coherent-neighbor -> global geometry hierarchy;
- metadata exposure ratio + robust global residual radiometric authority;
- optional row correction collapses toward 1.0 when unproven;
- parity-preserving mirrored photographic boundary and real interior tile halo;
- broad clipping-risk coherence for incomplete quads;
- complete current SHORT quads remain sovereign;
- neutral fallback is green/luminance anchored and cannot create white luminance;
- no neighboring hue donor, RGB blur, synthetic peach/orange fill or post-RGB hue repair.

### 5. HDR MANUAL SAFE sliders are literal shutter controls

MANUAL no longer sends the selected values through the AUTO exposure-product solver.

- LONG slider directly owns LONG `SENSOR_EXPOSURE_TIME`.
- LONG ISO slider directly owns LONG sensitivity.
- SHORT slider directly owns SHORT `SENSOR_EXPOSURE_TIME`.
- SHORT sensitivity is the physical sensor minimum.
- If SHORT is moved longer than LONG, SHORT clamps to LONG rather than swapping control meanings.
- Live scene statistics do not rewrite the MANUAL pair.
- The Brightness EV control remains recorded/available to AUTO and metadata, but does not silently regenerate MANUAL shutter/ISO values.
- A 60-fps live request may still obey unavoidable Camera2 frame-duration legality at request build time; full RAW/JPEG still capture remains on its separate session and preserves the selected exposure.

AUTO HDR retains its successful adaptive exposure policy unchanged.

## Exact runtime scope

Only these three runtime files are intended to differ from successful V1.5.4 V1.1:

- `app/src/main/assets/shaders/raw_hdr_fusion.frag`
- `app/src/main/assets/shaders/raw_hdr_demosaic.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`

`RawHdrFusion.java`, `HdrGlView.java`, `MainActivity.java`, `CaptureSetSaver.java`, `JpegFusion.java`, `hdr_display.frag`, WYSIWYG crop/color ownership, AUTO exposure, GTM, RAW/DNG/JPEG routing and every other runtime byte are protected against the exact successful V1.5.4 V1.1 authority.

## Permanent V1.5.4 V1.2 regressions

- terminal `NORMAL_MEASURED / CENSORED_UNKNOWN_CHROMA / SHORT_VALIDATED` provenance survives into demosaic;
- censored CFA has zero directional/opponent chroma authority;
- hard radiometric SHORT takeover alone cannot manufacture color validity;
- global fallback flow cannot self-certify SHORT chroma;
- complete proven SHORT color remains untouched;
- parity-safe edge handling remains unchanged;
- no luminance-raising neutralization;
- MANUAL SHORT/LONG are literal shutter controls with SHORT=min ISO and LONG ISO separate;
- live adaptive bracket logic and Brightness cannot silently regenerate the MANUAL sensor pair;
- AUTO/live/capture owners outside the exact three-file allowlist remain byte protected.

## Backup

No new backup is created, as requested. Exact V1.5.4 V1.1 source hashes plus deterministic forward/rollback patches provide localized rollback proof.

## Version/status

- `versionCode 35`
- `versionName 1.0-v1.5.4-v1.2`
- status: **PREPARED / UPLOAD-READY, NOT BUILD-PROVEN** until GitHub Actions passes the real pinned GLSL compiler, real project Java compiler and full Android assemble/invariance sequence.
