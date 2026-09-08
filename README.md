# Iris HDR Viewfinder Test V1.4.11 V2.31

V2.31 continues from the exact successful V2.30 compiled candidate, commit `3d11f41dc4ec6989b925ac117ff86fd49b1b079d`, tree `4fd09b02019f72c1acc5f3bcb6cb6691c751970e`, Actions run `34250645187`, artifact `10065909599`.

V2.30 passed the real GLSL/Java/full Android build but was rejected on device. The supplied capture exposed two exact runtime failures: integer `GL_R16UI` RAW samples were normalized against black/white values that Java had incorrectly divided by 65535, producing a nearly uniform purple fused image; and a bright-window scene captured SHORT and LONG at the same approximately 1/120 ISO50 setting, leaving a 0EV pair with no physical HDR information.

## V2.31 RAW reconstruction before proven fusion

Production fusion remains RAW-only: timestamp-matched SHORT/LONG `RAW_SENSOR` mosaics and their matched Camera2 metadata are the sole saved-fusion inputs. HAL JPEGs remain independent references only.

The RAW path is now staged:

`RAW R16UI -> sensor-code black/white + lens-shading preprocess -> edge-directed Bayer reconstruction -> luminance-preserving chroma de-alias -> V2.28/V2.29 registration/source ownership -> FUSED_HDR.jpg`

`raw_preprocess.frag` keeps RAW sample, black and white in identical sensor-code units, applies each frame's lens-shading map once in Bayer space, and carries the normalized sensor signal through an exact 16-bit fixed-point RGBA8 representation.

`raw_reconstruct.frag` uses bounded edge-directed green and diagonal selection plus green-difference R/B reconstruction. WB is applied only after structure reconstruction. LONG's matched WB/color transform remains the common color owner for both observations, and whole-RGB same-luminance gamut projection prevents one transformed channel from clipping independently into a pink/green bright-edge fringe.

`raw_chroma_dealias.frag` filters only opponent chroma around the exact center luminance. It is aimed at Bayer false color, one-pixel colored bright-edge artifacts and chroma moire without blurring or replacing luminance detail.

The GPU path reuses one RAW input, one shading texture, and the inherited full-resolution SHORT/LONG/presentation carriers sequentially; no second simultaneous RAW pair or new persistent full-resolution RGB scratch is introduced.

## V2.28/V2.29 fusion behavior remains authority

`JpegFusion.java` is byte-identical (`569754e8043928cf86b1f1d34f2ad6b2885e3bf7948789725d4c2092129d4782`). The proven bidirectional global registration, bounded local residual registration, immutable LONG geometry, SHORT-only displacement, binary whole-RGB source ownership and fail-closed motion/disocclusion behavior remain intact.

V2.31 also restores the successful robust achromatic overlap-derived appearance scalar using RAW-derived proxies only. Physical exposure*ISO is fallback; no HAL JPEG and no independent RGB gain participates.

## Real high-DR acquisition

Low-DR scenes may still converge toward 1x for temporal denoise. A scene with simultaneous highlight pressure, a materially darker body and at least roughly 3 stops of highlight/body separation is instead classified high-DR. Such a scene must retain at least an 8x / 3EV physical LONG/SHORT pair. SHORT cannot be brightened and LONG cannot be darkened to fake that requirement. The frozen still pair rechecks the invariant and raises LONG only, preferring physical integration/low ISO while respecting known 50/60Hz integer-cycle timing.

The exact V2.30 bright-window failure—SHORT=LONG around 1/120 ISO50—is a permanent regression fixture.

## Bathroom / house / tree recovery

V2.29 could recover the obviously lost sky while still leaving moderately flattened house/siding/tree structure LONG-owned. V2.31 keeps the strict SHORT recovery seed unchanged, but permits the connected physical-loss domain and final native-resolution recheck to complete a proven exterior component when SHORT retains a modest real multi-scale structure advantage. Healthy near-equal body structure remains LONG-owned. Final ownership is still binary whole RGB, never a per-channel blend.

## Scope and verification

V2.30 -> V2.31 runtime changes are exactly six files: `hdr_display.frag`, new `raw_chroma_dealias.frag`, new `raw_preprocess.frag`, `raw_reconstruct.frag`, `CameraController.java`, and `HdrGlView.java`. Build/verification changes are exactly `.github/workflows/build.yml`, `BUILD_WORKFLOW_COPY.yml`, `app/build.gradle.kts`, and `scripts/verify_orientation.py`. Delivery-document changes are exactly this README, `PACKAGE_INFO.txt`, `PACKAGE_MANIFEST_SHA256.txt`, and `VSCODE_DEV_UPLOAD.txt`.

The candidate runtime universe is exactly 20 `app/src` files with manifest digest `29a924836f56d39984ebca4a50973e44b4df22006b2a28de80e4e1649085b8e2`.

The successful V2.29-derived 15-step Actions procedure remains verification-mechanics authority. Local reserved-identifier and semantic/regression checks pass; deterministic patch and final clean-extract proofs are sealed with the handoff. The pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug` remain authoritative GitHub Actions gates, so this handoff is upload-ready rather than build-proven until V2.31 Actions succeeds.
