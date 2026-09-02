# Iris HDR Viewfinder Test V1.5.1

V1.5.1 is the provenance-safe highlight and no-LTM live-HDR correction built directly from the exact successful V1.5.0 GitHub Actions candidate.

Two independent device failures from V1.5.0 are addressed:

1. **Saved FUSED_HDR broad pink / pink-edge highlights.** V1.5.0 could mix SHORT and LONG independently at CFA sites, discard that physical trust information, then let an incomplete clipped Bayer neighborhood become chromatic during demosaic/WB/color conversion. V1.5.1 carries physical color provenance with the fused CFA, makes highlight color trust at Bayer-quad granularity, expands only the trust decision by one Bayer quad at clipping boundaries, and drives only unproven opponent chroma toward neutral. Bright real color is never neutralized merely because it is bright.

2. **Live HDR FUSED looking nearly like clipped LONG.** No local tone mapping is added. The live and saved paths still share one spatially uniform global tone mapper, but V1.5.1 gives recovered multi-stop radiance explicit fixed stop-domain display range instead of collapsing it rapidly toward white. LONG-like body appearance through 0.70 is preserved; 1x..64x highlight radiance is mapped monotonically through the remaining global display headroom.

The saved RAW architecture remains SHORT+LONG RAW fusion before one demosaic. Capture policy, matched frame ownership, RAW alignment, photometric normalization, physical lens/crop ownership, DNG publication, and the rest of the successful V1.5.0 runtime are protected byte-for-byte.

There is **no LTM**, no generic "bright pixel -> white" smoothstep repair, no hue donor, and no RGB blur to hide clipping artifacts.

Runtime authority: successful V1.5.0 commit `4b1753c7e07705946e5a43ae9edf081795f252f6`, Actions run `33668681576`, artifact `9861657341`.

Current V1.5.1 status: **PREPARED / UPLOAD-READY, NOT YET V1.5.1 BUILD-PROVEN**. Local authority/static/semantic checks are being exhausted, while the pinned real GLSL compiler, real Android Java compiler, full `:app:assembleDebug`, signing, exactly-one-APK proof and final post-build invariance must succeed in GitHub Actions before V1.5.1 becomes build authority.
