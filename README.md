# Iris HDR Viewfinder Test V1.5.2

V1.5.2 is the source-coherent HDR fusion and live-stability correction built directly from the exact successful V1.5.1 GitHub Actions candidate.

It preserves the successful V1.5.1 broad-pink protection and fixes the remaining device failures without local tone mapping:

- **SHORT is the fixed highlight exposure authority.** LONG remains the body/shadow owner while healthy; once LONG approaches physical clipping, the complete 2x2 Bayer quad transitions coherently toward SHORT. R/G1/G2/B cannot choose different exposure mixtures.
- **Geometry remains fail-closed.** The existing flow field already carries local-evidence in alpha. V1.5.2 uses that evidence: inherited motion may fill textureless saturated interiors, but it is rejected at a real LONG boundary unless radiometric correspondence is proven. This targets the white rear-window blotch and green shifted car/foliage edges.
- **Trusted texture is preserved.** Fully trusted CFA neighborhoods use the exact V1.5.1 demosaic path. Only incomplete/untrusted highlight neighborhoods suppress unproven green-direction and opponent-chroma steering, targeting peach/orange speckling without smoothing real pine needles/dirt.
- **Live HDR stops chasing AE generations.** Processed-preview SHORT/LONG response must agree for three consecutive 200-ms statistics updates before becoming a visible target. The visible curve then moves at one bounded rate; changing AUTO exposure generations no longer reopen a fast calibration window.
- **Live highlight color is SHORT-or-neutral once LONG is damaged.** LONG chromaticity may not re-enter and create peach/orange switching.

There is **no LTM**, no capture/exposure-policy change, no new backup, and no GPU/performance redesign in this build. The only build-mechanics correction is canonical full-index/no-ext-diff forward/rollback patch generation, with rollback generated directly from V1.5.2 back to the exact V1.5.1 authority and replayed on that real authority universe. CPU/GPU ownership and still-processing latency remain a separate follow-up after image correctness is proven.

Runtime authority: successful V1.5.1 commit `50c9bdd2709db67bd466ced1f5a82efa182f97cc`, Actions run `33675597653`, artifact `9864289091`.

Current V1.5.2 status: **PREPARED / UPLOAD-READY, NOT YET V1.5.2 BUILD-PROVEN** until the packaged Actions workflow passes the pinned real GLSL compiler, real Java compiler, full `:app:assembleDebug`, invariance and final candidate export.
