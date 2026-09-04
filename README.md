# Iris HDR Viewfinder Test V1.4.11 V2.13 V1.1

V2.13 V1.1 is the **compiler-contract correction of the unchanged independent two-exposure HDR + source-proven rendering candidate** derived from exact successful V2.12 Actions authority (`34ac4dd0b47be62833f25990c4284c3206741f52`, run `33892034499`, artifact `9944278389`).

## Visual failure being corrected

The supplied V2.12 office capture proved SHORT and LONG were effectively identical (`~1/156s ISO50`), while FUSED was brighter and developed unsupported-looking peach/orange pastel fill at the window/ground. 800% inspection showed real mottled source texture was flattened/expanded by saved presentation. MANUAL SPLIT also showed Long ISO could indirectly re-solve SHORT through the shared flicker-safe pair solver.

## V2.13 V1.2 post-build hash correction

Failed Actions run `33900980849` proved `CameraController` consumed `stats.shortP90Linear` while `HdrGlView.SceneStats` published P50/P95/P98/P99 but omitted P90. V1.1 changes no HDR policy: it publishes the intended SHORT P90 statistic from the existing 32x24 stats surface and adds the exact producer/consumer mismatch as a permanent regression.

## V2.13 capture contract

- SHORT and LONG are independent captures.
- MANUAL SHORT is solved only from SHORT shutter + minimum ISO + flicker mode; Long ISO cannot alter SHORT or the left SPLIT frame.
- AUTO unknown/PWM flicker stays explicitly `FLICKER UNSAFE`, but never collapses SHORT onto LONG.
- AUTO learns bracket depth from robust SHORT body/headroom statistics, targets at least 4x in HDR, and may grow to 64x when scene/sensor constraints support it.
- P99/near-clip pressure reduces SHORT exposure; it never collapses LONG onto SHORT.

## V2.13 fusion/presentation contract

V2.11/V2.12 registration and evidence/support geometry remain the production foundation. Hard multi-channel LONG clipping with valid registered/static SHORT now has a direct source-ownership route into FUSED. If the physical bracket collapses below 2x, the saved path fails closed to trustworthy SHORT rather than rendering processed LONG as pseudo-HDR.

Saved clarity is now a separate GPU presentation pass driven by the already-FUSED image. Dehaze/microcontrast remain luminance-only and RGB-ratio preserving; LONG can no longer act as the spatial guide after SHORT has won source ownership.

## Runtime scope

Exactly three runtime files change relative to successful V2.12:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`

`CaptureSetSaver.java`, `MainActivity.java`, `FrameMeta.java`, `JpegFusion.java`, `MediaStoreWriter.java`, DNG/orientation, registration math, shader modes 3/4, V2.10 50/60-Hz safety semantics and GPU-only saved output ownership remain protected.

V2.13 is **PREPARED / UPLOAD-READY only after clean-extract replay** and is not build-proven until GitHub Actions passes real glslang, real project javac, full `:app:assembleDebug`, one-APK proof and post-build invariance.
