# Iris HDR Viewfinder Test V1.4.20

V1.4.20 preserves V1.4.19's adaptive LONG/SHORT exposure policy, exact-generation pairing, full clipped-core SHORT recovery, five-knot scene response and edge-guided multiscale fusion. It addresses two device-proven problems: residual live modulation under fast SHORT exposures/electronic lighting, and ~28-second post-capture processing caused by full-resolution Java CPU fusion serialized with file I/O.

## GPU-first full-resolution still fusion

The final FUSED JPEG now uses an offscreen OpenGL ES 3 worker. It compiles the same `fullscreen.vert` and `hdr_display.frag` used by the live viewfinder, so clipped-core recovery, one-sided edge support, current-pair disagreement, chroma protection and the multiscale transition share one shader implementation.

The 4096x3072 frame is processed in bounded 512-row tiles with the required fusion halo. Only JPEG decode/orientation, sparse scene-curve learning, GPU readback packing and JPEG encode remain CPU-side. The old full-resolution Java per-pixel HDR loop is removed.

The frozen 32x24 reliability map is still consumed, but tiled coordinates are transformed back into full-frame coordinates before sampling.

## Parallel capture saving

`CaptureSetSaver` no longer serializes DNG writes, source JPEG writes, metadata and fusion on one executor. Two I/O workers save capture files while a dedicated fusion worker starts immediately after matched SHORT/LONG JPEGs/results are available. Completion still waits for every requested output.

Minimal timing markers identify remaining costs without broad logging: `DNG_SAVE`, `SOURCE_JPEG_SAVE`, `FUSION_DECODE`, `FUSION_CURVE`, `FUSION_GPU`, `FUSION_ENCODE`, `FUSION_WRITE`, and `FUSION_PIPELINE`.

## Temporal radiance stabilization

V1.4.19 removed 5-Hz luma-mask gating, but its visible five-knot photometric curve was still updated directly by the 200-ms statistics pass. Under a fast SHORT exposure, PWM/electronic lighting could therefore pull the response curve and create visible breathing even when manual exposure sliders were fixed.

V1.4.20 separates the learned target curve from the visible curve. The target still learns from scene overlap, while the visible curve moves smoothly at render/pair cadence. Samples are excluded from global response learning when LONG is locally stable but raw exposure-normalized SHORT changes by at least 0.12 EV. Coherent LONG+SHORT changes remain eligible, so real scene changes are not frozen.

## Preserved behavior

- V1.4.19 LONG/SHORT exposure policy and CameraController bytes are unchanged.
- Exact exposure-generation SHORT/LONG pairing remains unchanged.
- True recoverable LONG-clipped cores retain complete SHORT detail authority.
- Outer boundaries remain one-sided and edge-guided.
- Questionable SHORT color can retain LONG chromaticity while using SHORT detail/luminance.
- Brightness remains LONG-appearance intent and never becomes post-fusion gain.
- Orientation, FOV/cadence, DNG, sRGB and stable-signing protections remain intact.

## Rollback protection

No new backup branch is created for V1.4.20 by project decision. The exact successful V1.4.19 compiled candidate is the base, protected by exact hashes and deterministic full-index forward/rollback patches.

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
