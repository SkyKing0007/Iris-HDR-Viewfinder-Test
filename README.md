# Iris HDR Viewfinder Test V1.4.11

V1.4.11 is a controlled image-quality experiment built from the exact successful V1.4.10 Actions compiled candidate while restoring the proven V1.4.7 HDR ownership model. It keeps V1.4.9/V1.4.10 immutable shutter-time capture controls and timing/FOV/orientation mechanics.

## Fixed ~3 EV AUTO HDR bracket

AUTO HDR returns to the V1.4.7 fixed 8x exposure-product target (about 3 EV) when flicker is reported as NONE. Under 50/60 Hz, unknown or PWM-like lighting, the proven same-integration safety behavior is retained and SHORT uses sensor-minimum gain. The wider V1.4.8-V1.4.10 aperture-derived 3-4.25 EV policy is removed.

## WYSIWYG Brightness EV

A new **Brightness** control spans `-1.0 EV` to `+1.0 EV` in `0.1 EV` steps with `0.0 EV` as the no-brightness-change baseline.

SHORT/LONG reconstruction completes first. Brightness is then applied as one scene-linear exposure gain before the final HDR display fit. It therefore cannot change physical SHORT/LONG exposure, bracket width, highlight admission or fusion weights. The exact displayed EV is frozen at shutter and used by the saved fused JPEG so the live HDR Fused viewfinder and saved result share the same brightness intent.

The V1.4.8-V1.4.10 global appearance-lift stage is removed. The V1.4.7 HDR knee/white-anchor/display-ceiling mapping is restored so requested brightness is retained through shadows and midtones while recovered highlight headroom is compressed into the display range rather than post-SDR clipped.

## LONG-first highlight color ownership

V1.4.7's red/orange surface speckles and warm skin contamination are addressed without global chroma filtering. LONG remains the default color owner through the HDR handoff. SHORT chromaticity is admitted only when at least two LONG encoded channels are genuinely near clipping and SHORT itself has usable signal.

The correction is strictly pixel-local: no neighborhood sampling, chroma blur, sharpening, cross-edge color transfer, extra GPU pass, texture or framebuffer is introduced. This preserves the foliage/sky and thin-edge protections required by prior Iris regressions.

## Preserved behavior

The successful V1.4.10 Camera2 session mechanics, immutable shutter-time SHORT/LONG controls, post-RAW boost ownership, producer-owned orientation, FOV-safe 30 fps path, optional cropped 60 fps path, RAW/JPEG capture set, DNG/JPEG saving, runtime logging and protected shader/runtime files remain unchanged unless explicitly listed in the V1.4.11 runtime allowlist.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
