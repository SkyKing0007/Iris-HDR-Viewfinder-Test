# Iris HDR Viewfinder Test V1.4.11 V2.23

V2.23 is the **pretrained NAFNet-SIDD width32 post-fusion denoise integration** based on exact successful V2.22 Actions authority (`8d47c8a37a5dfd1a6cabec6eb56a0616a83480e3`, run `34009186958`, artifact `9981936667`).

## What changes

V2.22 capture, AUTO exposure, LONG-primary saved fusion, SHORT connected highlight recovery, alignment, tone/brightness, DNG handling and shader behavior are frozen. After V2.22 has produced its completed fused/tone-rendered JPEG bytes, `CaptureSetSaver` gives those bytes to a new `NafNetDenoiser`; only the denoised result is written as `_FUSED_HDR.jpg`.

The exact vendored model is `nafnet_sidd_width32_fp16.tflite`, 62,454,048 bytes, SHA-256 `f8fbaa422411683c53e802cf7cc7cf9be0a0de00886ad4af057232e26b172a0c`. It uses LiteRT 2.1.5 `CompiledModel` with GPU-only acceleration and the published NCHW RGB `[1,3,256,256]`, `[0,1]` input/output contract.

## Full-resolution / safety contract

- No whole-image downscale: the original fused dimensions are preserved.
- 256x256 inference tiles use a 32px reflected halo and retain only the 192x192 center core.
- NAFNet is a cleanup stage, not a new image owner: the predicted correction is bounded to +/-0.12 normalized RGB per channel.
- Bright/recovered highlights receive progressively less ML correction; max-channel >=0.985 is limited to <=0.10 denoise strength.
- GPU work is serialized and LiteRT buffers/model close after every fused capture.
- Any ML failure falls back to the exact original V2.22 fused JPEG bytes.
- SHORT/LONG JPEGs and DNGs never enter the NAFNet save path.

## V2.22 bytes intentionally protected

`hdr_display.frag`, `CameraController.java`, `HdrGlView.java`, `JpegFusion.java`, `FrameMeta.java`, `MainActivity.java`, `MediaStoreWriter.java`, all non-HDR shaders and `AndroidManifest.xml` remain byte-identical to successful V2.22. `CaptureSetSaver.java` changes only at the final fused-byte save hook.

## Build proof

The successful V2.22 15-step GitHub Actions procedure is preserved in the same order. V2.23 updates only authority/version/hash/allowlist payload, adds the pinned LiteRT dependency and model-specific regressions, and proves after assemble that the APK contains the exact `.tflite` hash uncompressed. Real project Java compilation and full `:app:assembleDebug` remain authoritative in GitHub Actions.

V2.23 is **PREPARED / UPLOAD-READY only after clean-extract replay**. It is not build-proven until its GitHub Actions run succeeds.
