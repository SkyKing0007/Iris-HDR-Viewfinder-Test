# Iris HDR Viewfinder Test V1.4.6

V1.4.6 is a localized live-preview scheduling correction built directly on the successful V1.4.5 compiled candidate. HDR exposure generation, GL pairing/fusion, saved JPEG fusion, RAW/JPEG capture, tonemap and ISP-control ownership are unchanged.

## Stable cadence / FOV policy

`60 FPS CROP: OFF` is now deterministic FOV-safe 30 fps from the first preview request. The app no longer starts in a potentially cropped 60-fps sensor mode and later changes to 30 fps after watchdog/FOV evidence, which produced the visible framing jump recorded on V1.4.5.

`60 FPS CROP: ON` is the explicit high-FPS choice. When exact Camera2 `[60,60]` is available, the live PRIVATE preview requests the 16,666,666-ns 60-fps cadence and keeps that policy. Actual delivered CaptureResult/GPU/pair cadence is still displayed and logged, but under-delivery does not silently change the requested FOV/cadence behind the user.

## AUTO HDR meter isolation

AUTO HDR still obtains absolute brightness from a clean contiguous AE phase and then returns to the proven manual SHORT/LONG repeating pair. V1.4.6 isolates that hidden meter from live cadence/FOV decisions:

- METER results do not count as steady-preview FPS or FOV evidence.
- FPS measurement windows reset when entering and leaving the meter.
- Re-metering is armed only after a completed LONG result, so the previous complete HDR pair remains published.
- Periodic clean-AE refresh is reduced from 2 seconds to 5 seconds.

No one-shot `capture()` meter is introduced.

## Preserved V1.4.5 image pipeline

SHORT and LONG remain real alternating manual preview requests. HdrGlView still publishes only complete temporally adjacent pairs. HDR FUSED still samples both SHORT and LONG textures and uses the V1.4.5 sRGB/exposure-normalized highlight fusion. Saved SHORT/LONG/FUSED JPEG behavior and full-resolution RAW/JPEG still capture are unchanged.

The explicit Camera2 sRGB contrast curve, post-RAW sensitivity parity, `NOISE_REDUCTION_MODE_OFF`, `EDGE_MODE_OFF`, LONG-only ISO slider ownership, sensor-minimum SHORT gain, expanded shutter controls, MANUAL SAFE flicker handling, orientation/DNG fixes and production logger remain unchanged.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
