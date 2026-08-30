# Iris HDR Viewfinder Test V1

Standalone Camera2 experiment for proving alternating-exposure HDR preview behavior without modifying Photon/Iris.

## V1 goals

- Enumerate back cameras that expose both `RAW` and `MANUAL_SENSOR` Camera2 capabilities.
- `NORMAL AE`: ordinary auto-exposure YUV preview.
- `SPLIT`: alternating manual short/long preview frames shown side-by-side.
- `HDR FUSED`: alternating manual short/long preview frames fused by an OpenGL ES 3.0 shader.
- Timestamp-match `YUV_420_888` preview frames against `TotalCaptureResult.SENSOR_TIMESTAMP`.
- Show actual exposure/ISO/frame metadata and dropped-render information.
- Capture one matched short/long still pair and save:
  - `*_SHORT.dng`
  - `*_LONG.dng`
  - `*_SHORT.jpg`
  - `*_LONG.jpg`
  - `*_FUSED_HDR.jpg`
  - `*_metadata.json`
- Files are written to `Downloads/IrisHDRViewfinder`.

## Important scope

This APK is intentionally separate from Photon/Iris. It does not use Photon code, Sabre, Motion, Night, Super Res, or the Iris DNG path.

The DNG files are diagnostic sensor references only. `FUSED_HDR.jpg` is an experimental JPEG-domain fusion of the matched short and long Camera2 HAL JPEGs. The live HDR viewfinder is fused from matched YUV preview frames on OpenGL ES 3.0.

## Reference lineage

The behavioral reference was Android's historical Camera2 HDR Viewfinder concept: alternating short/long manual requests and combining the two preview exposures. This project is a fresh implementation; it does not use the historical RenderScript implementation.

The build toolchain is pinned to the modern Android camera-samples baseline checked on 2026-08-30:

- Android Gradle Plugin 9.2.1
- Gradle 9.6.0
- compile/target SDK 37
- Java 17

## First-use test

1. Grant camera permission.
2. Pick a RAW+Manual back camera.
3. Leave mode on `NORMAL AE` for about one second.
4. Press `AUTO BRACKET` to center short/long settings around the most recent AE result.
5. Switch to `SPLIT` and verify the two sides visibly represent different exposures.
6. Switch to `HDR FUSED` and inspect highlight/shadow behavior and latency.
7. Press `CAPTURE HDR SET`.
8. Open `Downloads/IrisHDRViewfinder` and verify all six files exist for the same capture ID.

## V1 limitations

- This is an experiment, not a production HDR pipeline.
- Live fusion is intentionally lightweight and does not perform motion alignment.
- Full-resolution fused JPEG is produced from the matched HAL JPEG pair, not from RAW demosaic/merge.
- Output JPEG is SDR/sRGB-like for V1; P3/Ultra HDR are deliberately out of scope.
- RAW/DNG is diagnostic only and receives no custom processing.
