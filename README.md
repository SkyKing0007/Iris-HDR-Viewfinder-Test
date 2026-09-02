# Iris HDR Viewfinder Test V1.4.22

V1.4.22 is an AUTO-SHORT headroom and same-domain HDR fusion correction on top of successful V1.4.21. It preserves V1.4.21's LONG appearance policy, GPU-first live/still fusion, coherent boundary ownership, temporal/PWM protections, capture-performance architecture and exact exposure-generation pairing.

## LONG appearance, SHORT recoverability

LONG remains responsible for the natural scene-body exposure. SHORT is now treated as a highlight-recovery measurement rather than merely a fixed EV-gap partner.

The existing 32x24 paired scene analysis records localized cells where LONG has genuinely lost highlight information. AUTO then asks whether SHORT retains usable signal and headroom in those same cells. This lets a small chandelier bulb, shelf LED, television highlight or reflection request more SHORT headroom even when it occupies too little area to influence a global clip percentage.

AUTO bracket headroom may expand to 7 EV, with repeated evidence required to widen and slower hysteretic release. The displayed EV gap is therefore a result of recoverability needs rather than the primary objective.

## 50/60-Hz lighting

Known mains flicker remains conservative by default. SHORT stays on full flicker-safe periods while the requested headroom is achievable there. If localized evidence repeatedly proves that a minimum-ISO full-period SHORT is still saturated, AUTO may use binary subdivisions of the detected period while keeping ISO at sensor minimum. LONG remains the stable flicker-safe appearance reference.

This allows a direct emitter to move from, for example, about 1/120 ISO-min toward 1/240, 1/480 or 1/960 when the data proves that is necessary. Existing temporal SHORT-only modulation rejection remains active; V1.4.22 does not claim to reconstruct illumination that was physically absent during an extreme PWM off-phase.

## Same-domain fusion

V1.4.21 made source ownership spatially coherent, but SHORT highlight compression still happened before mixing with untreated LONG. V1.4.22 removes that domain mismatch.

Recovered SHORT now remains calibrated scene-linear radiance through the ownership decision. The same monotonic highlight operator is evaluated for both LONG and SHORT endpoints, and one coherent ownership field blends the display result only where LONG has actually lost information.

Bright-but-valid areas no longer independently invite SHORT. A narrow validity-guided feather may extend only around a real clipped core. Ordinary LONG remains unchanged outside that recovery region, while a truly recoverable clipped core retains complete SHORT detail authority.

High-luma single-channel clipping remains recoverable for colored emitters/reflections; low-luma skin-like single-channel saturation remains excluded.

## Preserved architecture

- Shared live/still `hdr_display.frag` processing remains GPU-owned.
- Offscreen GLES3 still fusion remains tiled and memory-bounded.
- DNG/source-JPEG saving and GPU fusion remain concurrent.
- The still EGL worker never terminates the process EGL display.
- Pair-rate visible photometric smoothing and five-knot scene response remain intact.
- Chroma reliability and neutral-highlight anti-pink protections remain intact.
- Exact exposure-generation pairing, frozen capture controls, orientation, FOV/cadence, DNG, sRGB and stable-signing protections remain intact.

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
