# Iris HDR Viewfinder Test V1.4.10

V1.4.10 is a localized image-quality correction on the exact successful V1.4.9 compiled candidate. Capture timing, SHORT/LONG exposure ownership, highlight-color reconstruction, Camera2 routing, FOV/FPS behavior, DNG/JPEG handling and UI remain unchanged.

## Monotonic appearance mapping

V1.4.9's narrow lower-mid Gaussian appearance bump could expand dark tonal differences and then collapse adjacent lower-mid levels into a nearly flat output region. V1.4.10 replaces that bump with a broad pointwise perceptual lift:

`y' = y + 1.50 * y^2 * (1 - y)^4`

For normalized perceptual luma `y` in `[0,1]`, its derivative is strictly positive and bounded. The mapping therefore preserves tonal ordering and cannot create the V1.4.9 plateau/reversal behavior. The saved JPEG path still precomputes the sRGB transfer into a LUT, so its inner pixel loop does not add per-pixel exp/pow work.

## Monotonic HDR highlight compression

V1.4.9 allowed the adaptive white anchor to approach or fall below the fixed HDR knee on wider brackets, causing severe highlight flattening and allowing non-monotonic mapping. V1.4.10 raises the knee to `0.78` linear and constrains the adaptive white anchor to `0.88..0.95`, always above the knee. The above-white display ceiling remains above the white anchor for every supported bracket width.

This keeps recovered highlights bright while preserving their ordering and separation instead of compressing a large bright range into only a few display levels.

## Edge-artifact protection

The correction introduces no neighborhood sampling, edge filter, sharpening, chroma spread or cross-edge operation. Both HDR tone mapping and appearance lift remain pointwise and apply one scalar to all RGB channels, preserving RGB ratios. The proven V1.4.9 SHORT/LONG highlight-color handoff is byte-for-byte unchanged.

This is specifically intended to avoid reviving earlier Iris edge failures such as cyan/blue foliage-versus-sky halos, colored fine-edge contamination and thin-detail smearing while correcting the tonal response.

## Preserved V1.4.9 behavior

V1.4.9's frozen shutter-time SHORT/LONG capture pair, remeter isolation, highlight-only source-color reconstruction, fast full-resolution fusion, adaptive exposure headroom, explicit Camera2 sRGB curve, `NOISE_REDUCTION_MODE_OFF`, `EDGE_MODE_OFF`, FOV SAFE fixed-30 mode, optional explicit cropped-60 mode, full RAW/JPEG still session, DNG/JPEG orientation and runtime logging remain unchanged.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
