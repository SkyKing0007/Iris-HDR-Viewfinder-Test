# Iris HDR Viewfinder Test V1.4.9

V1.4.9 is a corrective build on the successful V1.4.8 compiled candidate. V1.4.8's adaptive brightness and wide-aperture AUTO SHORT headroom are retained, while three on-device regressions are corrected: muted FUSED color, a shutter/remeter exposure race, and slow full-resolution saved fusion.

## HDR and color ownership

SHORT remains part of every HDR reliability decision. LONG continues to own clean shadows and ordinary midtones; normalized SHORT progressively contributes as LONG approaches saturation. The V1.4.8 global encoded-chroma limiter is removed because it muted ordinary color across much of the image.

Source-color reconstruction is now restricted to the actual highlight handoff. Where SHORT is contributing to HDR recovery, the already-solved FUSED luminance is preserved while chromaticity transitions toward the unscaled SHORT/LONG ISP source color. Low/moderate source chroma is not multiplied by the full display gain, strong source-supported color can retain its saturation, and gamut fitting is performed around fixed fused luma rather than by independent RGB clipping.

This is content-agnostic and cross-device: no skin/white/foliage classifier, device model table, universal sensor color matrix, extra GPU pass, extra texture/framebuffer, or neighborhood filter is introduced.

## Frozen shutter-time capture controls

At shutter press V1.4.9 snapshots SHORT exposure/ISO, LONG exposure/ISO, and post-RAW sensitivity boost before closing the preview session. A clean-AE remeter result arriving while the temporary RAW/JPEG still session is being configured is ignored for that in-flight HDR set and can only affect subsequent preview/captures.

## Faster saved fusion

V1.4.8's appearance curve is mathematically retained. For the saved full-resolution JPEG path its expensive exp/pow work is precomputed into a LUT, and the inner pixel loop contains no per-pixel exp/pow/sqrt or float-array allocation. Runtime logging now separates sensor-input acquisition time from total save/fusion time.

## Preserved V1.4.8 behavior

Adaptive brightness, aperture/capability-derived AUTO SHORT headroom, LONG-owned clean shadows, full-RGB near-clipping SHORT handoff, bracket-aware HDR compression, explicit Camera2 sRGB curve, `NOISE_REDUCTION_MODE_OFF`, `EDGE_MODE_OFF`, FOV SAFE fixed-30 mode, optional explicit cropped-60 mode, full RAW/JPEG still session, DNG/JPEG orientation and runtime logging remain otherwise unchanged.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
