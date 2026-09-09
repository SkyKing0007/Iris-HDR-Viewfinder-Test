# Iris HDR Viewfinder Test V1.4.11 V2.34

V2.34 is a targeted correction on the exact successful V2.33 compiled candidate: commit `68cbefcd7c42bf138258309c3d063743e4767ca8`, tree `226d9eddd057c2fab584ed243d21214c086aff29`, Actions run `34303285781`, artifact `10085756068`.

The V2.33 device samples confirm that registration, LONG geometry, SHORT recovery and connected fusion ownership remain correct. V2.34 therefore freezes that fusion core and corrects two independent residual failures around it.

First, the broad magenta/green/yellow highlight artifacts are corrected **before RGB**. Physical CFA saturation is treated as censored information rather than a valid color measurement. `raw_green.frag` carries green censorship explicitly, and `raw_reconstruct.frag` refuses to form ordinary R-G/B-G opponent terms unless both the color measurement and green reference are uncensored. When a highlight channel is censored, only the invalid channel may be recovered from coherent fully-valid same-phase boundary chromaticity in balanced sensor space before WB/CCM; valid center channels do not move. The V2.33 post-RGB chroma-dealias pass remains as a luminance-preserving safety net.

Second, the saved-still ceiling presentation no longer depends on the binary `shortOwns` mask after fusion. Once V2.33 has selected `mergedScene`, one continuous pointwise whole-RGB transfer maps the saved radiance field. This prevents a smooth ceiling gradient from receiving different display curves solely because ownership changed and targets the supplied visual authority: gradual radial chandelier falloff, a clean neutral low-contrast X/star reflection, no detached over-bright island and no ownership-boundary rings.

V2.34 also improves smooth recovered-highlight precision without increasing GPU allocation. The successful V2.33 RGBA8 texture count and lifetime remain unchanged; only the extended-linear RGB carrier distribution changes. The 1x..8x recovered-highlight interval now receives 128 distinct 8-bit code values versus 61 in V2.33, with a bounded monotonic tail through 32x.

The verifier hash-freezes the successful V2.33 registration/evidence/physical-loss section, mode-3/4 topology, mode-5 source-selection prefix through `mergedScene`, shared/live `adaptiveHdrToneMap`, and protected Java/runtime owners. It adds permanent regressions for censored-green opponent bias, genuine colored highlights, valid-channel invariance, carrier precision, owner-independent saved presentation, the ceiling X/radial-gradient target and unchanged GPU lifetime.

The older Photon/Claude magenta-highlight document is reference evidence only; Iris HDR Viewfinder artifacts, source candidates, DNG/JPEG device samples and regressions remain implementation authority.

Build mechanics and delivery remain the exact successful V2.33 style: authoritative Actions artifact reconstruction, deterministic full-index forward/rollback proof, pinned real GLSL, real project Java, full `:app:assembleDebug`, post-build invariance and a flat vscode.dev handoff containing only the actual changed files.
