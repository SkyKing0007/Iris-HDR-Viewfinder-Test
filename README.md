# Iris HDR Viewfinder Test V1.4.12

V1.4.12 is a focused correction built from the exact successful V1.4.11 Actions compiled candidate. It keeps the V1.4.7 LONG-dominant HDR reconstruction and V1.4.11 LONG-first highlight color correction, but removes V1.4.11's failed post-fusion Brightness multiplier.

## Shutter-priority AUTO Brightness

The existing **Brightness** control remains `-1.0 EV` to `+1.0 EV` in `0.1 EV` steps, with `0.0 EV` as the exact no-bias baseline.

Brightness now changes the physical AUTO LONG exposure product by `2^EV`. The clean hidden AE result remains the unbiased baseline and SHORT remains derived from that unbiased V1.4.7 highlight-safe baseline. LONG then receives the requested Brightness EV with shutter time selected first; ISO solves only the residual needed to reach the target exposure product.

There is no Brightness multiplier in `hdr_display.frag` or `JpegFusion`. The live HDR Fused viewfinder therefore shows the actual biased SHORT/LONG pair, and the saved JPEG fuses the same frozen physical pair.

## 50/60 Hz anti-banding guard

The existing anti-banding ownership is preserved. Known 50 Hz lighting uses 10,000,000 ns half-cycle periods and known 60 Hz uses 8,333,333 ns periods. Brightness may step LONG to the next longer safe period only when that step can still satisfy the requested exposure product without requiring ISO below the sensor minimum. Unknown/PWM lighting retains the clean-AE baseline shutter and lets ISO solve the residual rather than inventing an unsafe intermediate shutter.

Forced 60 fps still caps live LONG exposure at 16,666,666 ns. At 60 Hz, a representative `1/120 ISO357` baseline with `+0.5 EV` therefore resolves to approximately `1/60 ISO252`, while SHORT remains on the unbiased highlight-safe baseline.

## HDR and color ownership preserved

The V1.4.7 fixed 8x (~3 EV) baseline SHORT target remains the AUTO HDR reference when flicker is NONE. Any additional bracket width now comes only from explicit user Brightness raising LONG; the rejected V1.4.8-V1.4.10 aperture-derived automatic 3-4.25 EV widening does not return.

LONG remains the default highlight chroma owner. SHORT color is admitted only when at least two LONG channels are truly near clipping and SHORT has usable signal. No neighborhood sampling, chroma blur, sharpening, cross-edge color transfer, extra GPU pass, texture or framebuffer is introduced.

## Preserved mechanics

The exact successful V1.4.11 immutable shutter-time capture controls, periodic clean AE remetering, post-RAW boost ownership, producer-owned orientation, FOV-safe 30 fps path, optional cropped 60 fps path, RAW/JPEG capture set, DNG/JPEG saving, runtime logging, full-index deterministic patch proof and GitHub Actions compiler/build order are inherited unchanged except for V1.4.12 authority/version/hash/regression pins.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
