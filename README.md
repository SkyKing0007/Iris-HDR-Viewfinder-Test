# Iris HDR Viewfinder Test V1.4.11 V2.38

V2.38 is an architectural fusion/alignment robustness correction on the exact successful V2.37 compiled candidate: commit `020975e80802560a25c4aaa39c2b6aab93f1dcc0`, tree `6f4c94ae1c6eaadbaa78dcac50e04cccbdf9dc57`, Actions run `34507561872`, artifact `10164509321`.

V2.37 remains the presentation/color/highlight authority. V2.38 changes exactly three runtime files to address three device-proven failures without adding sharpening, semantic scene logic or a second fusion pipeline:

- `JpegFusion.java`: whole-frame registration is audited by a spatially distributed 5x4 tile consensus so a moving foreground cannot easily become global camera-motion authority. Local-flow alpha distinguishes direct cycle-validated measurements from inferred/fill cells.
- `hdr_display.frag`: only direct confident local residuals may warp final SHORT; pre-clamp source bounds are mandatory; non-clipped effective recovery requires full-resolution static correspondence; saved RAW fusion adds a noise-normalized 1.5px microstructure class so genuine SHORT grass/pine-needle/hair/fabric/text/mesh detail can own complete RGB when LONG demonstrably lost it.
- `raw_chroma_dealias.frag`: the existing physical RAW saturation bit enables a strictly local saturated-edge chroma reliability correction using unsaturated same-luminance-side support while preserving center luminance. Distant hue donors and blanket desaturation remain forbidden.

Motion/disocclusion is deliberately fail-closed: where temporal correspondence is not trustworthy, immutable LONG remains the source instead of forcing alignment. Physical hard clipping can still use bounded SHORT HDR recovery when real source bounds exist; V2.38 does not invent correspondence between two genuinely different moving moments.

V2.37 manual Brightness/Gamma ownership, SPLIT preview behavior, extreme-emitter AUTO rendering, FUSED presentation/tone, CFA common-quad highlight reconstruction, RAW color matrices, acquisition, DNG/media ownership, NAFNet and GPU lifetime/allocation are protected.

Build mechanics retain the successful V2.37 15-step sequence: exact Actions-artifact authority reconstruction, strict allowlist, deterministic full-index forward/rollback proof at `core.abbrev` 7/12/40 plus GNU `fuzz=0` replay, pinned real GLSL, real project Java, full `:app:assembleDebug`, post-build candidate/protected invariance and final compiled-candidate artifact export.
