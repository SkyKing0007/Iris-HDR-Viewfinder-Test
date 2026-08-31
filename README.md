# Iris HDR Viewfinder Test V1.4.3

Standalone Camera2 experiment for a responsive alternating-exposure HDR viewfinder plus matched RAW/JPEG capture, without modifying Photon/Iris.

## V1.4.3 live architecture

`Camera2 PRIVATE Surface -> SurfaceTexture/external OES -> timestamp-matched staging -> atomic SHORT/LONG GPU pair -> HDR shader -> display`

The steady-state live session still contains only the PRIVATE preview Surface. `CAPTURE HDR SET` temporarily configures PRIVATE + JPEG + RAW, acquires matched SHORT/LONG inputs, then restores preview while DNG/JPEG writing and fused-JPEG generation continue.

## AUTO HDR cadence correction

V1.4.2 inserted a separate AE `capture()` about every 500 ms. On the tested LEVEL_3 camera that broke the otherwise healthy repeating schedule: AUTO fell to roughly 9-11 CaptureResults/s, while the exact same build delivered about 30 fps in NORMAL AE and HDR MANUAL and about 14.8 complete HDR pairs/s in MANUAL.

V1.4.3 removes the third hidden request completely. AUTO remains one two-request repeating burst:

`SHORT manual bracket -> LONG Camera2 AE meter -> SHORT -> LONG -> ...`

The LONG member uses `AE_MODE_ON`, `AE_ANTIBANDING_MODE_AUTO`, and the selected target FPS range. Its real CaptureResult shutter/ISO/flicker evidence becomes the exposure authority. SHORT is updated from that evidence only when the bracket changes materially, with a 500 ms minimum rebuild interval. The selected FPS-range key is also carried on the manual SHORT request (where AE is OFF and therefore does not use it), so the repeating pair does not alternate a Camera2 session-sensitive FPS parameter frame by frame. AUTO UI reporting is limited to about 2 Hz instead of posting work on every LONG frame. MANUAL retains the proven all-manual exposure schedule.

At a real 30 sensor fps, the architectural ceiling is therefore about 15 complete SHORT/LONG pairs/s, matching the V1.4.2 MANUAL device proof.

## Flicker policy

For 50 Hz, 60 Hz, or unknown/PWM lighting, Camera2 AUTO antibanding owns the real LONG shutter and SHORT uses the same integration window while reducing sensor gain. That prevents the short member from sampling a different LED/PWM phase. Stable bright/no-flicker scenes may spend the bracket in shutter time for additional highlight headroom. The target remains 8x / 3 EV, but actual EV is reported after sensor limits.

## Live orientation correction

V1.4.2 still showed a sideways portrait preview in NORMAL and HDR. The direct OES pass already consumes `SurfaceTexture.getTransformMatrix()`. V1.4.3 therefore disables the rejected second display quarter-turn instead of applying another 90-degree mapping. The sensor/display relation is retained only as an axis-swap signal for FIT geometry, so portrait stays aspect-correct without a second image rotation.

Camera2 processed preview continues to select `SCALER_ROTATE_AND_CROP_NONE` where supported, preventing compatibility rotate/crop from becoming an additional FOV owner. RAW/native sensor aspect still drives PRIVATE preview and JPEG selection, and presentation remains FIT rather than center-crop-to-fill.

## DNG orientation correction

RAW Bayer bytes remain sensor-native. V1.4.2 DNGs were correctly 4096x3072 but had TIFF Orientation=9 because `DngCreator.setOrientation()` was never called. V1.4.3 explicitly maps capture orientation to valid TIFF/EXIF values and sets it before writing:

- 0 degrees -> 1 (normal)
- 90 degrees -> 6 (rotate 90)
- 180 degrees -> 3 (rotate 180)
- 270 degrees -> 8 (rotate 270)

The good JPEG orientation/fusion path remains separate and protected.

## Atomic HDR presentation and color

SHORT and LONG still land in staging textures and publish only as a complete temporally adjacent pair. No `new SHORT + old LONG` half-pair is displayed. All four GLSL files and `JpegFusion.java` remain byte-identical to successful V1.4.2, preserving the existing sRGB transfer functions, exposure normalization, highlight-aware SHORT admission and bounded rolloff.

## Capture outputs

Each `CAPTURE HDR SET` produces SHORT DNG, LONG DNG, SHORT JPEG, LONG JPEG, FUSED_HDR JPEG, and metadata JSON in `Downloads/IrisHDRViewfinder`.
