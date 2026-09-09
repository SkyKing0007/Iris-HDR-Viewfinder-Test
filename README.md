# Iris HDR Viewfinder Test V1.4.11 V2.35

V2.35 is a surgical CFA highlight-color correction on the exact successful V2.34 compiled candidate: commit `3ff48aa5ed36d2a758d1d812fd616d9dc2af72e5`, tree `36238f6c4b4ec1f00757fefa1081d6d26c30dcd8`, Actions run `34312591458`, artifact `10088941635`.

Claude's magenta-highlight verdict analyzed the older Iris Camera / Photon MotionV2 implementation. It is **reference evidence only**, not runtime authority for this Viewfinder app. V2.35 ports its causal correction literally into the current active Viewfinder CFA owners:

- one physical 2x2 Bayer quad owns clipping permission for ordinary opponent chroma;
- any clipped phase blocks ordinary R-G/B-G authority for all four phases in that quad;
- opponent support remains an absolute valid-observation count, never a ratio against collapsing green support;
- the active green guide ports the old `highlightCalculationSample` 3x3 opposed-channel power-3 reconstruction with phase-preserving sampling, the physical 0.985 pre-WB threshold and an 8x ceiling;
- V2.34 `wideGreenEstimate` / fixed-distance boundary-hue reconstruction is removed;
- no replacement hue donor, histogram, local tone map or downstream desaturation is introduced.

Everything unrelated to that root correction stays V2.34: fusion, registration, LONG geometry, SHORT displacement, acquisition, source ownership, `hdr_display.frag`, saved/live presentation, `raw_chroma_dealias.frag`, NAFNet, DNG/media ownership and GPU allocation topology.

Build mechanics remain the exact successful V2.34 style: authoritative Actions artifact reconstruction, deterministic full-index forward/rollback proof, pinned real GLSL, real project Java, full `:app:assembleDebug`, post-build invariance and a flat vscode.dev handoff containing only the actual changed files.
