# Iris HDR Viewfinder Test V1.4.11 V2.24

V2.24 is based on exact successful V2.23 Actions authority (`e4b75493b0ddd1212a1c50e9ac4913902c164b33`, run `34014611207`, artifact `9983535386`).

## What changes

V2.23 HDR capture, SHORT/LONG exposure policy, GPU fusion, highlight recovery, tone/brightness, DNG handling, shaders, `HdrGlView`, `JpegFusion`, and the exact NAFNet model weights remain protected. V2.24 changes only the post-fusion NAFNet application contract plus UI/focus/lifecycle ownership around the existing capture.

- The camera selector is compact (`ID0`, etc.); detailed capability text remains in runtime logging.
- The adjacent button is labeled exactly `Denoise`; each press toggles NAFNet on/off, with the choice frozen at capture start. OFF writes the exact pre-NAFNet fused JPEG.
- NAFNet ON no longer receives general image-reconstruction authority. Broad/DC neural residual is removed per RGB channel, and correction is admitted only inside positively proven locally smooth source interiors.
- Coherent microstructure is source-authoritative everywhere, independent of semantics or neighboring background: hair, pine needles, fabric/denim weave, microfiber, fur, foliage, grass, stitching, mesh, text, thin branches and comparable dense detail fail closed to the original fused image.
- Touch-to-focus is Camera2 AF-only; it never sets AE regions and does not alter V2.23 HDR SHORT/LONG exposure solving or fusion.
- After GPU fusion bytes exist and both acquired RAW Images have been released, Iris shows `HDR Captured. You can now move the phone.` and starts a foreground `mediaProcessing` lifetime lease. From that proven boundary onward, ordinary Home/backgrounding no longer aborts optional NAFNet/final file writes. The service owns no image math and stops when the capture succeeds or fails.

## Protected V2.23 image owners

`HdrGlView.java`, `JpegFusion.java`, all four GLSL shaders, the exact 62,454,048-byte NAFNet model and its license, `FrameMeta.java`, and `MediaStoreWriter.java` remain byte-identical to successful V2.23.

## Build proof

The successful V2.22/V2.23 15-step GitHub Actions procedure is preserved in the same order. V2.24 updates only the new authority/version/hash/allowlist/regression payload needed for this candidate. Real project Java compilation and full `:app:assembleDebug` remain authoritative in GitHub Actions.

V2.24 is **PREPARED / UPLOAD-READY only after clean-extract replay**. It is not build-proven until its GitHub Actions run succeeds.
