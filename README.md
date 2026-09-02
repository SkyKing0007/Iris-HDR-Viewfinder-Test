# Iris HDR Viewfinder Test V1.4.21

V1.4.21 is a narrow HDR fusion-boundary correction on top of successful V1.4.20. It preserves V1.4.20 GPU-first still fusion, parallel capture saving, temporal radiance/PWM stabilization, adaptive LONG/SHORT exposure policy, exact-generation pairing and full clipped-core SHORT recovery.

## Validity-guided boundary

The chandelier regression showed that SHORT and LONG were already registered to about 0.26 pixel, yet FUSED created cyan/bright contours around clipped bulbs that were not present in either source. The remaining defect was therefore fusion reconstruction rather than gross alignment.

V1.4.20 reconstructed one low-frequency band with a broader `coarseMask` and a detail band with a different `fineMask`. Where those masks disagreed, fusion could combine SHORT base tone with LONG detail (or vice versa) and synthesize a perimeter outside the center values represented by either exposure.

V1.4.21 removes that split ownership. A single edge-aware ownership mask now controls the center reconstruction, and final linear RGB is a convex interpolation between LONG and recovered SHORT. True recoverable clipped cores still reach complete SHORT authority; only the transition perimeter changes.

## Edge guide ownership

The boundary guide is now validity-aware. LONG luminance defines edges while LONG retains scene information. As LONG enters true clipping or a strongly damaged highlight shoulder, calibrated exposure-normalized SHORT luminance progressively takes over the edge guide. This lets SHORT preserve glass/filament/foliage boundaries that clipped LONG can no longer describe.

The bilateral ownership mask remains bounded by LONG damage support, so neighboring SHORT highlight authority cannot leak across intact dark LONG structure.

## Preserved V1.4.20 behavior

- Offscreen GLES3 tiled still fusion continues to use the exact live HDR shader.
- DNG/source-JPEG I/O and GPU fusion remain parallel.
- The GPU still worker never terminates the process EGL display.
- Scene-learned five-knot SHORT response remains pair-rate smoothed.
- Stable LONG + SHORT-only PWM/modulation samples remain excluded from global response learning.
- LONG/SHORT exposure generation, manual sliders, Brightness ownership and camera-control bytes are unchanged.
- Chroma reliability/neutral-highlight anti-pink protections remain unchanged.
- Orientation, FOV/cadence, DNG, sRGB and stable-signing protections remain intact.

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
