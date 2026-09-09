# Iris HDR Viewfinder Test V1.4.11 V2.33

V2.33 is a surgical finishing build on the exact successful V2.32 compiled candidate: commit `d106aa22d7a4f3dba5d00098f4243f3fce56082f`, tree `d282094797f2ad19bf7885d4ddae5d2468f8ba86`, Actions run `34295814090`, artifact `10083118010`.

The V2.32 device samples show that RAW SHORT/LONG fusion, registration, LONG geometry and connected SHORT recovery are now working correctly. V2.33 therefore does **not** reopen fusion. Runtime scope is exactly two shaders.

`raw_chroma_dealias.frag` fixes the remaining broad magenta/green/yellow highlight artifacts. Physical RAW saturation is treated only as chroma unreliability, never as a command to desaturate. A saturated pixel can borrow chromaticity only from coherent unsaturated luma-compatible boundary samples, preserving genuine colored lights. Unsaturated thin-edge cleanup still requires the inherited Bayer periodic-alternation proof. Center luminance/detail stays exact and the V2.32 alpha noise/saturation carrier is copied unchanged.

`hdr_display.frag` keeps the successful V2.32 fusion sections hash-frozen and adds a saved-still recovered-highlight presentation function only after mode-5 has already selected SHORT. The shared/live V2.32 HDR transfer remains byte-identical. The saved transfer is pointwise and monotonic, with no neighbor sampling, histogram, quantile, scene-global knots or per-channel curves. It preserves the body while increasing visible separation through the recovered highlight interval where the supplied ceiling reference shows the gradual illumination falloff and clean neutral X/star reflection.

The verifier permanently pins the V2.32 extended-linear carrier, registration/evidence math, topology and mode-5 source-selection prefix. It also encodes regressions for the chandelier/grow-light magenta caps, green/yellow dotted edges, real colored lights, luminance/alpha invariance, live-HDR invariance and the ceiling/X highlight-slope target.

Build mechanics and delivery remain the exact successful V2.32 style: authoritative Actions artifact reconstruction, deterministic full-index forward/rollback proof, pinned real GLSL, real project Java, full `:app:assembleDebug`, post-build invariance and a flat vscode.dev handoff containing only the actual changed files.
