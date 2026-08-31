# Iris HDR Viewfinder Test V1.4.5

V1.4.5 fixes absolute HDR brightness ownership and expands manual/high-FPS controls without reviving the rejected V1.4.2 cadence architecture.

## HDR exposure ownership

AUTO HDR first obtains a clean Camera2 AE anchor with AUTO antibanding. Once converged, that result owns the absolute LONG exposure. The steady HDR view then runs as a two-request manual SHORT/LONG pair at the selected cadence, avoiding alternating AE-OFF/AE-ON exposure drift. A short clean remeter phase periodically refreshes the anchor; no one-shot `capture()` meter is inserted into the live pair.

Where Camera2 exposes `CONTROL_POST_RAW_SENSITIVITY_BOOST`, the actual AE boost is copied into the manual HDR pair so processed PRIVATE/JPEG brightness remains consistent across devices.

SHORT uses the sensor's minimum available ISO. LONG owns the ISO selection / AE anchor. Under 50/60-Hz or unknown/PWM lighting, MANUAL SAFE keeps compatible integration timing and creates separation through gain where required.

## Fused brightness

The prior fixed `1.6*x/(1+1.6*x)` tone map is removed. LONG/normal-exposure midtones pass through unchanged in linear light; a smooth shoulder acts only above the highlight knee. Live GL fusion and saved JPEG fusion share that rule. Exposure-ratio normalization is widened beyond 32x so 1/8000/min-ISO SHORT frames remain correctly normalized.

## Controls

Manual shutter steps now include 1/8000, 1/4000, 1/2000, 1/1000, 1/500, 1/480, 1/240, 1/120, 1/100, 1/60, 1/50, 30 ms (~1/33.3), 1/30, 1/25, 1/20, 1/15 and 1/8. Camera2 sensor limits remain authoritative.

`60 FPS CROP` is an explicit user opt-in. When ON and exact `[60,60]` is supported, the live PRIVATE preview uses a 16,666,666-ns cadence even if the device's high-FPS sensor mode is cropped. RAW/JPEG still capture remains a separate full-resolution session and is never forced to inherit that preview cadence. With the toggle OFF, the existing FOV-safe policy remains.

## Processed-image controls

When supported, processed requests use explicit `TONEMAP_MODE_CONTRAST_CURVE` with a sampled sRGB transfer curve. `NOISE_REDUCTION_MODE_OFF` and `EDGE_MODE_OFF` are also requested when advertised by the device. RAW Bayer bytes are unaffected by those processed-output controls.

## Runtime logger

The V1.4.4 production logger is preserved. Logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`

Send the newest text file for device freezes, crashes, black preview, unusual FPS/FOV or Camera2/GPU failures.

## Preserved behavior

V1.4.5 retains producer-owned orientation, valid DNG TIFF orientation, atomic complete SHORT/LONG publication, native-aspect FIT, FOV-safe fallback, MANUAL SAFE anti-flicker behavior, full-resolution RAW/JPEG capture and the proven V1.4.4 session/logger lifecycle except where exposure ownership is intentionally replaced above.
