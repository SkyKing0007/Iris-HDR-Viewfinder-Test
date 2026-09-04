# Iris HDR Viewfinder Test V1.4.11 V2.15

V2.15 is the **immutable-SHORT fusion correction** derived from exact successful V2.14 Actions authority (`9a6b4dfc3885f32d5dffbbee3954392a35a2ab08`, run `33915396541`, artifact `9952986089`).

## Primary evidence

The implementation is driven by the supplied original-resolution SHORT/LONG/FUSED captures and the 600–800% crops. The failure is explicit: FUSED still produced peach/orange blotchy speckles and previously produced disconnected gray/blue contour fragments even though SHORT already contained the correct ground, foliage, road, sign and border structure.

## Non-negotiable fusion contract

**SHORT is the immutable spatial, chromatic and high-frequency authority.** It is never globally translated, locally warped, flow-sampled, bilinear-resampled for fusion ownership, or fractionally mixed with LONG RGB.

LONG is aligned into SHORT coordinates. LONG may contribute only a deliberately low-frequency **single achromatic luminance scalar**. At 3072×4096 the envelope atlas is 96×128 (1/32 per axis), then neighborhood-smoothed before full-resolution presentation. The scalar is applied equally to SHORT R/G/B, so LONG cannot inject hue, texture, edge shape, peach/orange speckles, gray borders or displaced structure.

If LONG alignment/radiometry is uncertain, envelope confidence collapses to zero and saved fusion fails closed to exposure-mapped SHORT. Honest clipping/noise from SHORT is preferable to invented content.

Live HDR also fails closed to exposure-normalized SHORT rather than mixing LONG/SHORT RGB. SPLIT continues to show the independent sources.

## Exposure contract

Successful V2.14 `CameraController.java` and `CaptureSetSaver.java` are byte-protected. SHORT effective exposure may never exceed LONG in AUTO or MANUAL; Long ISO remains LONG-only; actual Camera2 result metadata must prove LONG/SHORT >= 1 before fusion.

## Runtime scope

Exactly three runtime files change relative to successful V2.14:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java`

The other nine `app/src/**` runtime files are byte-protected, including `CameraController.java` and `CaptureSetSaver.java`.

V2.15 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real glslang, real project javac, full `:app:assembleDebug`, exactly-one-APK proof and post-build invariance.
