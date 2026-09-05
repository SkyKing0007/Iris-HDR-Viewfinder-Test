# Iris HDR Viewfinder Test V1.4.11 V2.20

V2.20 is the **ratio-invariant connected-region fusion correction** derived from exact successful V2.19 Actions authority (`13393e945e7313d981b38ce5a44e31eceff7dc79`, run `33947113826`, artifact `9963691541`).

## Device evidence

The supplied V2.19 MANUAL capture used SHORT 1/1000 ISO50 and LONG 1/100 ISO100 = **20x / 4.32 EV**. Global alignment and radiometric normalization were already correct, but the existing one-pass 1/16 ownership closure could not span the large LONG-clipped shutter/window regions, leaving internal LONG holes and white/cyan/orange source fragments.

## V2.20 contract

- AUTO and MANUAL use the same fusion engine; the exposure ratio changes how much LONG is lost, not the topology mechanics.
- LONG remains the primary clean saved body/default RGB/detail owner.
- Existing V2.19 capture, registration direction, physical ratio calculation, appearance gain, flicker behavior and Photon-normalized tone are preserved.
- Mode 3 now separates a strict registered recovery seed from a broader physically allowed `LONG loss && SHORT valid` domain.
- Mode 4 becomes **monotonic geodesic reconstruction under that mask**, ping-ponged at the existing 1/16 atlas until occupancy converges.
- The supplied 20x failure, 4x and full 64x are permanent topology regressions.
- Hard-clipped interiors inherit coherent bounded residual geometry from proven boundary seeds; they do not need a local LONG gradient that clipping has destroyed.
- Mode 5 remains binary real-source ownership: LONG by default, aligned SHORT only inside converged recovery ownership.
- No fractional high-frequency RGB mix, synthetic radiance fill, CPU source fusion or third-source RGB is introduced.

## Runtime scope

Exactly two runtime files change relative to successful V2.19:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`

`CameraController.java`, `JpegFusion.java`, `CaptureSetSaver.java`, `MainActivity.java`, capture/DNG/flicker ownership and all other runtime files are byte-protected from successful V2.19.

V2.20 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real GLSL, real project javac and full `:app:assembleDebug`.
