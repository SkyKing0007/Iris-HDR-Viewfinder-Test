# Iris HDR Viewfinder Test V1.4.7

V1.4.7 is a localized HDR highlight-reconstruction/display correction built directly on the successful V1.4.6 compiled candidate. Capture scheduling, FOV/cadence policy, SHORT/LONG request ownership, AUTO metering, RAW/JPEG still capture, orientation and GPU/session lifecycle are unchanged.

## Adaptive HDR highlight recovery

V1.4.6 proved that the SHORT processed frame already contains recoverable bright-window detail, while the prior fixed HDR shoulder collapsed much of that recovered range back into near-white output codes. V1.4.7 corrects only the live/saved merge and display mapping.

- LONG is the clean shadow and ordinary-midtone owner. SHORT contributes zero until LONG approaches saturation.
- SHORT is normalized in linear light using the actual exposure relationship already tracked by the pipeline.
- Wider SHORT/LONG brackets move SHORT admission closer to LONG clipping, so dark/noisy SHORT data is not imported unnecessarily.
- Near clipping, the handoff uses the full normalized SHORT RGB vector rather than switching R/G/B independently.
- Highlight compression is hue-preserving and exposure-ratio aware. It reserves visible 8-bit output space for scene values above LONG white instead of forcing recovered HDR values back to 252-255.
- Live HDR FUSED and saved FUSED JPEG use equivalent constants and equations.

The result is designed to retain V1.4.6's clean LONG-owned shadows while recovering significantly more bright-window structure autonomously.

## Cross-device performance contract

The HDR policy is based on exposure ratio and pixel values, not Xiaomi/device IDs or SoC/GPU classes. The live path remains one GLES 3.0 pass over the existing three textures with no new texture allocations, neighborhood samples, compute stages or extra frame buffers. Saved JPEG fusion retains the existing 32-row strip processing and does not add full-frame float buffers. This keeps the correction applicable from lower-end supported Camera2/GLES3 devices through flagship hardware.

## Preserved V1.4.6 camera pipeline

The explicit Camera2 sRGB contrast curve remains unchanged. `NOISE_REDUCTION_MODE_OFF` and `EDGE_MODE_OFF` remain requested where supported. FOV SAFE remains fixed 30 fps, optional cropped 60 remains explicit, AUTO HDR meter frames remain isolated from displayed cadence/FOV evidence, and the full RAW/JPEG still session remains independent from live-preview cadence.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
