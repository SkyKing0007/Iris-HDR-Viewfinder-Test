# Iris HDR Viewfinder Test V1.4.11 V2.36

V2.36 is the full artifact-correction build on the exact successful V2.35 compiled candidate: commit `265e2ace3212e559f5020c62354875b4853ce2fe`, tree `acc4948b05fc7d60dc5246f6d3dcafb50f3331ba`, Actions run `34358340770`, artifact `10106804351`.

V2.35 device testing proved the Claude saturated-highlight correction because the prior magenta highlight failure disappeared. V2.36 therefore preserves that common-quad physical clipping / power-3 highlight guide unchanged in purpose while correcting the separate Viewfinder failures exposed by the same samples:

- ordinary green reconstruction now uses the audited old-Iris `edgeGreen` +/-1 / +/-2 directional second-order geometry inside the current Viewfinder CFA owner only; Sabre/VGN topology is not imported;
- R-G/B-G are formed in one normalized calculation-WB domain and the common Camera2 green scale is restored exactly once, eliminating V2.35's mixed-domain edge-color path;
- strict 2x2 clipping still controls opponent-color permission, but terminal neutral fallback uses a phase-invariant censored fraction so the binary CFA decision cannot become a hard 2x2 RGB pattern;
- `raw_chroma_dealias` no longer performs the inherited fixed-distance boundary-hue search and remains only a local luminance-preserving chroma/periodic-alias cleanup;
- AUTO remains scene-adaptive and keeps its physical capture/SNR policy, while saved presentation cannot add positive brightness above +0.0 EV or gamma above 1.65. These are neutral ceilings, not a restaurant/Costco histogram match;
- NAFNet, registration/fusion, SHORT/LONG ownership/acquisition, DNG/media ownership, GPU lifetime and `hdr_display.frag` remain successful V2.35 behavior.

Build mechanics remain the exact successful V2.35 style: exact Actions-artifact authority reconstruction, strict changed-file allowlist, deterministic full-index forward/rollback proof at core.abbrev 7/12/40 plus fuzz=0 text replay, pinned real GLSL, real project Java, full `:app:assembleDebug`, post-build candidate/protected invariance and a flat vscode.dev handoff containing only the actual changed files.
