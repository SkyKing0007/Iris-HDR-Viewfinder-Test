# Iris HDR Viewfinder Test V1.5.4 V1.1

V1.5.4 V1.1 is the **saved-RAW HDR authority-separation correction** built directly from the exact successful V1.5.4 GitHub Actions compiled candidate. It fixes the common architectural producer demonstrated independently by the MANUAL SAFE and AUTO 5.0-EV plant-rack captures; it is not an APK-to-APK cosmetic tune. The OpenGL ES 3.1 compute/GPU migration remains intentionally deferred until this reconstruction contract is device-proven.

## Runtime authority

Exact successful V1.5.4 Actions candidate:

- commit `b352a4f12f011496f8c0eeb3a51553b35cc33c48`
- tree `8b09e5ab8e647a82cea1330473a4c259c9738f54`
- Actions run `33706757668`
- job `100497419969`
- artifact ID `9875501146`
- artifact name `Iris-HDR-Viewfinder-Test-V1.5.4`
- artifact ZIP digest `sha256:f72440fd34b51f91378058d79f29ab14fb95a3b55a9c721e51bf7ec846fd991b`
- APK SHA-256 `53f9a84767b92c088e88d882bb2e7ba1a6fef8489a9f759db3498eab25b326e6`
- source-candidate TAR SHA-256 `99139eb5df3000656608a60cff112d07744ab63d20a17f64df0eb3f0c3ec110d`
- runtime manifest SHA-256 `eafb637ae41d304e75afc4376957ec16570381f1022b27cd760ad9fcf675c485`
- repository manifest SHA-256 `1ec9c43c432267c2cbfe6a0740978f401170b146db6ad0dc019755ce5a19217a`
- regressions SHA-256 `c0cafba33deb4d56865408adbcb6c31c6a53e7b13dba55db6ce9648b52fb4e3b`

The downloaded Actions source candidate was clean-extracted and proved byte-identical to the prior frozen V1.5.4 handoff candidate before V1.5.4 V1.1 was transformed.

Verification-mechanics authority is the exact successful V1.5.4 16-stage authority-seeded sequence: Java 17, Android 37, Gradle 9.6.0, stable signing, pinned real glslang, complete reserved-identifier scan, real Java compile, deterministic full-index patch proof, full `:app:assembleDebug`, post-build invariance, and clean candidate export.

## V1.5.4 V1.1 reconstruction contract

### 1. Geometry authority is resolved before fusion

A local flow candidate is no longer kept merely because its patch contained many samples. If forward/backward confidence rejects it, the cell receives exactly one safe geometry in this hierarchy:

1. locally proven flow;
2. coherent proven-neighbor flow;
3. global flow fallback.

The rejected local vector can therefore never become mandatory SHORT input when LONG clips. The flow texture alpha now records whether geometry is locally authoritative versus inherited/fallback, so pre-clipping edge admission remains conservative without allowing clipped LONG to return.

### 2. Radiometric confidence controls whether local correction is consumed

Camera2 RAW exposure metadata plus the robust global residual remain radiometric authority. Row-local PWM/rolling-shutter correction is an optional refinement only. Its confidence now weights its value toward exactly `1.0`; an unproven local scale is no longer applied merely because a confidence value was stored beside it.

### 3. Exposure ownership remains physical

LONG remains body/shadow/SNR authority. SHORT remains highlight authority. If LONG is physically clipped and SHORT has usable CFA support, the complete Bayer quad remains 100% SHORT-owned; geometry or photometric confidence may never resurrect clipped LONG white.

### 4. SHORT color is proven by SHORT itself

After safe geometry and safe radiometry are selected, SHORT chroma trust comes from complete unsaturated same-phase Bayer support in SHORT. Clipped LONG cannot validate color it no longer measures, and weak row-photometric confidence cannot bleach otherwise complete SHORT CFA evidence.

### 5. Complete current quads are sovereign in demosaic

The one-quad clipping-risk expansion remains for incomplete boundaries to preserve the successful broad-pink protection. But a complete physically trusted current Bayer quad blocks borrowed neighboring risk, so valid SHORT leaves, pots, walls, lights, and other real color cannot be neutralized simply because the adjacent quad is uncertain. Neutral fallback remains `vec3(g)`, so uncertainty can remove unsupported opponent chroma but cannot create white luminance.

## Permanent device regressions retained

- no broad pink / ceiling magenta;
- no peach/orange synthetic CFA fill;
- no independent R/G1/G2/B exposure ownership;
- no clipped-LONG white leakage when usable SHORT exists;
- no luminance-raising white paint;
- no abrupt demosaic trust family switch;
- no rejected local flow consumed by hard SHORT takeover;
- no unproven row scale consumed at full strength;
- no clipped LONG used as SHORT color validator;
- no neighboring uncertainty allowed to bleach a fully proven current SHORT quad.

## Protected successful V1.5.4 behavior

These runtime files remain byte-identical to successful V1.5.4:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java`
- capture/DNG/JPEG routing outside the three-file runtime allowlist

Therefore V1.5.4's fast scene-cut AE with steady-state anti-flicker hysteresis, MANUAL SAFE SHORT behavior, -16..+1 EV Brightness, generation-stable live calibration, shared 8-EV no-LTM GTM, and capture topology are not redesigned in this correction.

## Backup

No new backup is created for this reconstruction correction, as requested. The existing `backup-v1.5.3-v1.2-pre-es31-gpu-transition` remains reserved for the later ES 3.1/GPU architectural transition.

## Version/status

- `versionCode 34`
- `versionName 1.0-v1.5.4-v1.1`
- status: **PREPARED / UPLOAD-READY, NOT BUILD-PROVEN** until GitHub Actions passes the real GLSL, Java, and full Android build gates.
