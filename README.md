# Iris HDR Viewfinder Test V1.4.11 V2.32

V2.32 continues from the exact successful V2.31 V1.1 compiled candidate: commit `97f93f5a8f3ab02a30f01df59702a67a40a2b3f5`, tree `aceb5a25936eac0b8f8f9b8c6e5353b9e0dd9cb9`, Actions run `34272023077`, artifact `10074269678`.

V2.31 V1.1 is the build/runtime authority. V2.28/V2.29 registration and LONG-geometry / SHORT-recovery behavior remain inherited semantic authorities where their bytes/mechanics are unchanged. Photon Camera APK observations are reference-only and are not Iris implementation authority.

## Why V2.32 exists

The supplied RAW HDR samples proved that acquisition is now physically useful: the manual pair used SHORT 1/500 ISO50 and LONG about 1/120 ISO100, approximately 8.33x / 3.06EV. The visible red/green/blue stippling, zippering and grille/shutter moire are not explained by a missing bracket. The Iris RAW front end was converting the two mosaics into display-like RGB too early and was not carrying the physical Camera2 sensor-noise model into reconstruction/ownership.

## V2.32 RAW/CFA correction

Production saved fusion remains RAW-only. Timestamp-matched SHORT/LONG `RAW_SENSOR` mosaics plus matched Camera2 metadata are the source authority. Paired HAL JPEGs remain saved references only and never feed alignment, radiometry, ownership or production fusion.

The saved pipeline is now staged as:

`RAW R16UI -> sensor-code black/white + lens shading + physical noise -> same-CFA cleanup -> staged green owner -> noise-aware R-G/B-G reconstruction -> linear chroma/moire cleanup -> transient registration proxy -> V2.31 registration/topology -> noise-normalized HDR ownership -> final tone/sRGB/JPEG`

Key contracts:

- `RawFusion.java` carries `SENSOR_NOISE_PROFILE` and fails closed if the per-capture S/O variance model is missing or invalid.
- `raw_preprocess.frag` keeps sensor sample/black/white in one code domain, applies lens shading once, preserves extended-linear signal rather than upper-clamping at 1.0, performs physical-sigma-gated same-CFA outlier cleanup, and carries signal/sigma/raw-saturation in the packed source.
- `raw_green.frag` is the staged structural green owner. Native green remains measured; missing green at R/B sites is selected with noise-normalized directional evidence.
- `raw_reconstruct.frag` reconstructs R-G/B-G from staged green and statistically supported CFA neighbors. WB follows structural reconstruction, and LONG's matched color transform remains the common SHORT/LONG color owner. Saved production output remains an extended-linear carrier rather than sRGB.
- `raw_chroma_dealias.frag` works in linear opponent chroma, uses physical sigma, targets isolated/periodic CFA false color and preserves center luminance.
- `raw_proxy.frag` is intentionally sRGB and exists only to feed the byte-preserved `JpegFusion.java` registration estimator. It is not a production fusion source.
- saved SHORT remains `GL_NEAREST`; explicit four-tap interpolation occurs only after extended-linear decode in `hdr_display.frag`.
- physical raw saturation is carried separately from transformed RGB so sensor clipping is not guessed from display color.

## Noise-normalized HDR ownership

The exact 2026-09-08 3.06EV pair carries physical DNG/Camera2 noise profiles. Across normal body levels, exposure-normalized SHORT uncertainty is more than 2x LONG and can be substantially larger in dark regions. V2.32 therefore subtracts a conservative four-sigma allowance from multi-scale SHORT/LONG range evidence before it can qualify as recovered structure.

This retains V2.31's bathroom sky/house/tree connected-recovery objective but prevents amplified SHORT sensor/CFA variation from masquerading as detail. Final HDR replacement remains binary whole-RGB SHORT versus LONG, with motion/disocclusion fail-closed behavior.

## Preserved authorities

V2.32 does not reopen the successful V2.31 V1.1 high-DR acquisition policy. `CameraController.java` is byte-identical protected runtime, including the real >=3EV high-DR bracket invariant. `JpegFusion.java`, NAFNet/model, DNG/saver/background/media ownership and all other protected runtime bytes are unchanged.

The saved GPU path retains the inherited four full-resolution RGBA8 work targets and reuses them sequentially for CFA/green/reconstruct/proxy/final stages. No new persistent full-resolution image allocation is introduced.

## Scope

V2.31 V1.1 -> V2.32 runtime changes are exactly eight paths:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/assets/shaders/raw_chroma_dealias.frag`
- `app/src/main/assets/shaders/raw_green.frag` (new)
- `app/src/main/assets/shaders/raw_preprocess.frag`
- `app/src/main/assets/shaders/raw_proxy.frag` (new)
- `app/src/main/assets/shaders/raw_reconstruct.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/RawFusion.java`

Build/verification infrastructure changes remain exactly four files: `.github/workflows/build.yml`, `BUILD_WORKFLOW_COPY.yml`, `app/build.gradle.kts`, and `scripts/verify_orientation.py`. Delivery documents are this README, `PACKAGE_INFO.txt`, `PACKAGE_MANIFEST_SHA256.txt`, and `VSCODE_DEV_UPLOAD.txt`.

The candidate runtime universe is exactly 22 `app/src` files. The successful V2.31 V1.1 build/verification order remains unchanged; the workflow only advances authority/version/hashes/allowlist/regressions and adds the two new active shaders to the same pinned real GLSL compile gate.

Before Actions, this handoff is prepared/upload-ready only. GitHub Actions remains authority for the pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug`.
