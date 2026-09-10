# Iris HDR Viewfinder Test V1.4.11 V2.39

V2.39 is a focused RAW-quality and native-resolution microdetail correction on the exact successful V2.38 compiled candidate: commit `5c8f6d73fd071c5728f2e5d5d49a9c34c6498538`, tree `3f6c779866d5db75d25847d2ffbd9c6955c57546`, Actions run `34523350151`, artifact `10170612070`.

V2.38 remains the motion/alignment and presentation authority. V2.39 changes exactly two runtime shaders to address the remaining device-proven noise and fine-detail failures without changing exposure, alignment, DNG levels, NAFNet, tone mapping or broad HDR topology:

- `raw_chroma_dealias.frag`: performs local noise-model-aware cleanup in the linear RAW-derived RGB domain before fusion/tone. It consumes the existing conservative Camera2 S*x+O sigma carrier, gives chroma stronger authority than luma in statistically noise-like interiors, range-gates every neighbor, protects coherent high-frequency structure, leaves physical saturation with the proven V2.38 highlight/chroma owner, and preserves the original sigma+saturation alpha byte-for-byte.
- `hdr_display.frag`: proves 1–2 px SHORT microdetail independently at native resolution using multiple coherent SHORT-over-LONG gradient votes, direct cycle-validated local geometry, source bounds, low-frequency correspondence and a low-residual-motion barrier. Directly proven microdetail may bypass the coarse 16×16 connectivity atlas, while broad clipped/effective HDR recovery continues to use the proven connected topology. Only that directly proven microdetail path may use a bounded 4×4 Catmull-Rom SHORT reconstruction; its RGB result is clamped to actual surrounding SHORT extrema and its sigma remains the conservative bilinear authority.

The exact V2.38 motion robustness remains mandatory: tiled global consensus, direct-vs-inferred local-flow ownership, pre-clamp source-bounds rejection and full-resolution motion/disocclusion barriers are unchanged. Atlas-independent microdetail cannot bypass those protections, and large local residual motion explicitly removes its new authority.

The device RAW code domain remains unchanged. `BitsPerSample=16` storage with camera-reported WhiteLevel around 1023 and BlackLevel around 64 is retained as the physical Camera2 sensor-code domain; V2.39 does not rescale it to 65535.

V2.37/V2.38 Manual Safe slider ownership, SPLIT behavior, extreme-emitter AUTO, saved/live FUSED presentation, common-quad CFA/highlight reconstruction, V2.38 saturated-edge chroma repair, color matrices, SHORT/LONG acquisition, DNG/media ownership, JpegFusion registration, NAFNet and GPU lifetime/allocation remain protected.

Build mechanics inherit the exact successful V2.38 procedure unchanged: exact Actions-artifact authority reconstruction, strict authority-seeded allowlist, deterministic full-index forward/rollback proof at `core.abbrev` 7/12/40 plus GNU `fuzz=0` replay, pinned real GLSL compilation, real project Java compilation, full `:app:assembleDebug`, exactly-one-APK/model proof, post-build candidate/protected invariance and final compiled-candidate artifact export.
