# Iris HDR Viewfinder Test V1.4.13

V1.4.13 is built from the exact successful V1.4.12 Actions compiled candidate. It keeps V1.4.12 shutter-priority physical Brightness and anti-banding behavior, while correcting the widening-bracket HDR artifacts seen on walls, shelf lighting and plant edges.

## Brightness: -5 EV to +2 EV in AUTO and MANUAL SAFE

**Brightness** now spans `-5.0 EV` to `+2.0 EV` in `0.1 EV` steps and remains enabled in both HDR AUTO and HDR MANUAL SAFE.

AUTO continues to use clean hidden AE as the unbiased baseline. SHORT remains on the unbiased highlight-safe baseline and LONG receives the requested Brightness exposure intent using shutter time first, then ISO only for residual target error.

MANUAL SAFE now uses the selected manual SHORT/LONG/ISO settings as its unbiased base, keeps SHORT as the highlight-safe owner, and applies the same shutter-priority Brightness bias to LONG. Negative Brightness is clamped so LONG never falls below the SHORT exposure product and the HDR pair cannot invert.

Known 50 Hz lighting retains the 10 ms anti-banding family; known 60 Hz retains the 8.333333 ms family. Unknown/PWM retains baseline integration rather than inventing an unsafe intermediate shutter. Forced 60 fps still caps live LONG integration at 16.666666 ms.

## V1.4.13 highlight artifact correction

V1.4.12 proved that the physical Brightness solver worked, but increasing the LONG/SHORT separation also made the older full-RGB highlight handoff increasingly visible. The normalized SHORT JPEG was being multiplied by the wider exposure ratio and blended into valid LONG pixels before the color-protection stage, creating wall/highlight blotchiness and displaced plant-edge artifacts.

V1.4.13 removes that full-RGB handoff. LONG remains the complete owner of ordinary scene RGB and luminance. SHORT contributes only missing highlight radiance after at least two LONG channels are genuinely near clipping. A one-sided normalized-luminance agreement test rejects a SHORT sample that becomes materially darker than the same bright LONG sample, which protects thin leaves/branches against a bright shelf or wall without any neighborhood sampling or cross-edge blur.

SHORT chromaticity is emergency-only after virtually complete multi-channel LONG clipping. The broad V1.4.8-V1.4.10 appearance/color reconstruction does not return.

## Stable tone ownership

The physical exposure ratio is still used to normalize SHORT correctly, but Brightness no longer changes the display tone policy. Live and saved fusion use one fixed ~3 EV V1.4.7 display shape: knee `0.70`, white anchor `0.74`, display ceiling `0.88`, with a fixed 3-EV highlight headroom mapping. This prevents Brightness from simultaneously moving LONG exposure, SHORT admission and the tone curve.

## Compact navigation-safe controls

The diagnostic control panel uses smaller text/buttons/slider rows and reserves the Android navigation-bar bottom inset. The Brightness slider therefore remains above the Android gesture pill/system bar in portrait while preserving both portrait and landscape layouts.

## Preserved mechanics

The exact successful V1.4.12 immutable shutter-time pair, hidden AE remetering, 50/60-Hz guards, post-RAW boost ownership, producer-owned orientation, FOV-safe 30 fps path, optional cropped 60 fps path, RAW/JPEG capture set, DNG/JPEG saving, logging, deterministic full-index patch proof, real GLSL/Java compiler gates and full Android assemble order are inherited unchanged except for V1.4.13 authority/version/hash/regression pins.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
