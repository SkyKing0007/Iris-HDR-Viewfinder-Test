# Iris HDR Viewfinder Test V1.4.11 V2.3

V2.3 is derived **only** from the exact successful V1.4.11 V2.2 compiled candidate. No other Iris build is used as a runtime or algorithm source.

## Changes from V2.2

### Faster dark ↔ bright AUTO response

V2.2 already owns physical AUTO exposure through its live 32x24 LONG statistics after the initial clean HAL-AE bootstrap. V2.3 keeps that ownership and changes only the response policy:

- statistics interval: 100 ms;
- ordinary drift: unchanged 0.10 EV hysteresis and ±0.30 EV smoothing;
- scene cut: when the measured error is at least 0.70 EV, apply the measured correction immediately on the first fresh statistic, capped at ±6 EV for safety;
- stale statistics from the previous exposure pair remain rejected;
- fixed ~8x / 3 EV SHORT↔LONG bracket remains unchanged.

### GPU-primary saved fusion

V2.2 saved `FUSED_HDR.jpg` by decoding both still JPEGs and running the full-resolution fusion loop on the CPU. V2.3 moves that same V2.2-domain job onto the existing GLES3 context:

- SHORT and LONG still JPEGs are decoded and uploaded as GPU textures;
- the full-resolution HDR fusion, brightness, Gamma and tone-fit math reuse V2.2's existing `hdr_display.frag` in an off-screen still-only mode;
- only the final GPU readback and JPEG encoding remain CPU-side;
- the old CPU `JpegFusion` path remains only as an explicit failure fallback and uses the same fusion ownership rules.

### Real SHORT texture recovery

The V2.2 saved path admitted SHORT primarily when LONG was already near encoded clipping. In the supplied office capture, the outdoor ground is bright and the exposure-normalized SHORT and LONG agree, but LONG is not close enough to clipping to trigger that handoff. The result is therefore overwhelmingly LONG-owned and preserves LONG's peach/orange processed blotches instead of the pine-needle/grass/dirt texture present in SHORT.

V2.3 adds a fail-closed, pixel-local SHORT reliability gate using only information already present in the V2.2 SHORT/LONG pair:

- SHORT must have real encoded signal;
- LONG must be a genuinely bright region;
- exposure-normalized SHORT and LONG luminance must agree closely;
- otherwise LONG remains owner.

No neighborhood fill, texture synthesis, sharpening or cross-edge operator is introduced.

### Brightness range

Brightness is now **-16.0 EV through +1.0 EV** in 0.1 EV steps. It remains presentation-only after SHORT/LONG fusion and cannot alter sensor exposure or the fixed HDR bracket. Gamma remains 0.50..2.00 with 1.00 neutral.

## Preserved V2.2 behavior

- side-by-side application ID `com.skyking0007.irishdrviewfinder.v1411v2`;
- fixed ~3 EV AUTO HDR bracket;
- AUTO/MANUAL ownership;
- FOV-safe 30 fps and explicit cropped 60 fps mode;
- producer-owned orientation/FIT geometry;
- fixed-height status-row bounce correction;
- shutter-time freeze of SHORT/LONG exposure/ISO/post-RAW boost/Brightness/Gamma;
- RAW DNG and individual SHORT/LONG JPEG saves;
- existing live HDR display fusion behavior except the requested Brightness clamp expansion.

Target branch: `experiment-v1.4.11-v2-brightness-4ev`.
