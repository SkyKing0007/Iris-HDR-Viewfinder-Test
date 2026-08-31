# Iris HDR Viewfinder Test V1.4.1

Standalone Camera2 experiment for a responsive alternating-exposure HDR viewfinder plus matched RAW/JPEG capture, without modifying Photon/Iris.

## V1.4.1 live architecture

`Camera2 PRIVATE Surface -> SurfaceTexture/external OES -> timestamp-matched SHORT/LONG GPU textures -> HDR shader -> display`

The steady-state live session contains only the PRIVATE preview Surface so RAW/JPEG output streams do not unnecessarily cap preview cadence. `CAPTURE HDR SET` temporarily configures the guaranteed PRIVATE + JPEG + RAW topology, acquires the matched SHORT/LONG inputs, then restores the preview-only session while DNG/JPEG writing and fused-JPEG generation continue.

## Display and field of view

- Android `fullUser` orientation: portrait/landscape follow auto-rotate and respect device orientation lock.
- The largest RAW stream defines the camera's native sensor aspect.
- PRIVATE preview and HAL JPEG sizes are selected to match that same native aspect instead of forcing 16:9.
- Preview uses FIT/letterbox/pillarbox presentation, never center-crop-to-fill, so geometry is not stretched and scene field of view is not intentionally discarded to fill the UI.
- SHORT/LONG JPEG orientation remains device-correct and fused-JPEG inputs normalize EXIF-only HAL rotation.

## Exposure and cadence

- `NORMAL AE` requests the best supported AE FPS range, preferring 60 fps only when both camera AE capability and the selected PRIVATE stream allow it; otherwise it uses a genuine 30 fps fallback.
- `SPLIT` and `HDR FUSED` keep AE off and own shutter, ISO and sensor frame duration directly.
- Default HDR bracket is 1/480s vs 1/60s at the same ISO: 8x / 3 EV.
- Auto Bracket log-centers an 8x bracket around the latest NORMAL AE result, then constrains the long exposure to the chosen live frame duration where possible.
- UI reports actual incoming camera FPS and completed HDR-pair FPS separately.

## Color/HDR preview

Where supported, Camera2 is asked for `TONEMAP_MODE_PRESET_CURVE` + `TONEMAP_PRESET_CURVE_SRGB` (not `CONTRAST_CURVE`). Live and saved fused HDR use the exact piecewise sRGB transfer function, exposure normalization, highlight-aware short-frame admission, and a bounded global highlight rolloff. No motion alignment is performed in this test app.

## Capture outputs

Each `CAPTURE HDR SET` produces:
- `*_SHORT.dng`
- `*_LONG.dng`
- `*_SHORT.jpg`
- `*_LONG.jpg`
- `*_FUSED_HDR.jpg`
- `*_metadata.json`

Files are written to `Downloads/IrisHDRViewfinder`. DNGs are diagnostic sensor references only; no custom DNG image processing is performed.

## Reference lineage

The behavioral reference is Android's historical Camera2 HDR Viewfinder concept: alternating per-frame manual exposures and combining the latest two exposures. V1.4.1 preserves the V1.4 fresh OpenGL ES 3.0 implementation and does not use the historical RenderScript code or Photon/Iris source.


V1.4.1 is a narrow compiler correction over the V1.4 architecture: the direct-GPU timestamp matcher now uses the existing FrameMeta.sensorTimestampNs field. No HDR math, stream selection, FPS policy, capture routing, shader, or color behavior changed from V1.4.
