# Iris HDR Viewfinder Test V1.4.2

Standalone Camera2 experiment for a responsive alternating-exposure HDR viewfinder plus matched RAW/JPEG capture, without modifying Photon/Iris.

## V1.4.2 live architecture

`Camera2 PRIVATE Surface -> SurfaceTexture/external OES -> timestamp-matched staging -> atomic SHORT/LONG GPU pair -> HDR shader -> display`

The steady-state live session contains only the PRIVATE preview Surface. `CAPTURE HDR SET` temporarily configures PRIVATE + JPEG + RAW, acquires the matched SHORT/LONG inputs, then restores preview while DNG/JPEG writing and fused-JPEG generation continue.

## Orientation and field of view

- Android `fullUser` orientation follows portrait/landscape auto-rotate and respects the device orientation lock.
- On API 31+, preview requests explicitly select `SCALER_ROTATE_AND_CROP_NONE` when supported, preventing Camera2 compatibility rotate/crop from becoming a second orientation/FOV owner.
- The standard back-camera relation `(sensorOrientation - displayRotation)` is applied once by the display path; because the shader maps display UV back to source UV, HdrGlView uses the inverse sampling quarter-turn.
- RAW/native sensor aspect drives PRIVATE preview and JPEG selection. Preview remains FIT/letterbox/pillarbox, never center-crop-to-fill.
- The proven V1.4.1 still-JPEG orientation and EXIF-normalized fused-JPEG path are retained.

## AUTO HDR and MANUAL HDR

- `NORMAL AE` remains ordinary Camera2 automatic exposure.
- `HDR AUTO: ON` is the default for HDR/SPLIT. A low-duty hidden AE probe runs about twice per second with AE + AUTO antibanding, reads actual shutter/ISO and `STATISTICS_SCENE_FLICKER`, and updates the manual SHORT/LONG repeating pair only when the metered exposure changes materially. Meter frames are timestamp-matched and discarded from display.
- `HDR MANUAL` preserves direct user ownership of Short shutter, Long shutter, and shared ISO through the existing sliders.
- Capture uses the currently active AUTO or MANUAL exposure pair; the proven CaptureSetSaver/JpegFusion/MediaStoreWriter implementation is unchanged.

## Flicker policy

- Detected 50 Hz lighting uses a 10 ms full-wave integration target when sensor gain limits permit.
- Detected 60 Hz lighting uses an 8.333 ms integration target when permitted.
- Under artificial or unknown/PWM lighting, SHORT and LONG use the same temporal integration window and create most of the bracket with sensor gain. This avoids the V1.4.1 failure where a 1/480 s SHORT frame sampled a different LED/PWM phase than the LONG frame.
- Stable bright/no-flicker scenes may use shutter separation for additional highlight headroom.
- The target remains 8x / 3 EV, but AUTO HDR reports the actual EV separation after sensor shutter/ISO clamping instead of forcing an unsafe bracket.

## Atomic HDR presentation and cadence

SHORT and LONG images first land in staging textures. HDR/SPLIT only publishes a new displayed pair after a temporally adjacent SHORT then LONG have both arrived. A half-updated `new SHORT + old LONG` image is never presented.

Diagnostics distinguish requested target FPS, actual `CaptureResult` FPS/frame duration, GPU camera-input FPS, complete HDR-pair FPS, and dropped GPU frames. A device is not considered to be delivering 60 fps merely because its capability table advertises 60; after two sustained measurement windows below 45 CaptureResults/s, the live controller falls back to a 30-fps target.

## Color/HDR preview

The V1.4.1 sRGB/HDR shader bytes are protected unchanged: Camera2 requests `TONEMAP_MODE_PRESET_CURVE` + `TONEMAP_PRESET_CURVE_SRGB` where supported, and live/saved fusion retain exact piecewise sRGB transfer functions, exposure normalization, highlight-aware SHORT admission and bounded highlight rolloff.

## Capture outputs

Each `CAPTURE HDR SET` produces SHORT DNG, LONG DNG, SHORT JPEG, LONG JPEG, FUSED_HDR JPEG, and metadata JSON in `Downloads/IrisHDRViewfinder`. DNGs remain diagnostic sensor references only.
