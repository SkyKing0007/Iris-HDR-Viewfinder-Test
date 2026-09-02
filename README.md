# Iris HDR Viewfinder Test V1.5.3

V1.5.3 is the **complete SHORT highlight-authority correction** built directly from the exact successful V1.5.2 GitHub Actions candidate.

The V1.5.2 test proved that broad pink was fixed and quad-coherent fusion substantially improved false-color behavior, but it also proved two remaining information-loss paths: clipped LONG could still survive when soft confidence gates reduced SHORT ownership, and the shared global display curve still hard-ceiled at 6 EV while AUTO can capture a 7-EV SHORT.

V1.5.3 fixes both without LTM:

- **Clipped LONG cannot win.** If any LONG CFA phase reaches the physical clipping zone and the aligned SHORT quad has usable unsaturated support, the complete 2x2 Bayer quad is 100% SHORT-owned. Alignment/photometric confidence can shape the pre-clipping transition, but it cannot choose clipped LONG as a radiometric fallback.
- **SHORT starts before clipping.** The complete Bayer quad transitions coherently toward SHORT through the HDR highlight shoulder, so exterior/window/ceiling structure is not withheld until the last few sensor code values.
- **Valid SHORT color stays valid.** Hard SHORT takeover restores physical color trust. The V1.5.1 neutral-chroma fail-closed path remains only for genuinely missing color evidence and cannot turn a fully supported SHORT quad into neutral white.
- **No peach/orange regression.** R/G1/G2/B still share one source-ownership scalar per Bayer quad. The V1.5.2 trust-aware demosaic remains byte-identical.
- **No broad-pink regression.** The proven V1.5.1 `raw_hdr_demosaic.frag` clipping-provenance/camera-neutral completion and CFA-parity edge behavior are byte-identical to V1.5.2.
- **No live-pulsation regression.** V1.5.2's generation-stable `HdrGlView` response calibration is byte-identical.
- **Live HDR follows the same ownership intent.** Physically clipped LONG cores cannot be displayed merely because pair-rate reliability is conservative; current SHORT signal/headroom owns the core, while a coherent shoulder brings in valid SHORT detail earlier.
- **The GTM no longer reclips the recovered SHORT.** One shared RGB-uniform global tone map remains in place, with no LTM. Its fixed scene range is increased from 64x (6 EV) to 256x (8 EV), so a valid AUTO 7-EV SHORT still has visible code separation after fusion instead of collapsing into the old white plateau.

There is no capture/exposure-policy change, no alignment rewrite, no backup, and no GPU/performance redesign in this build.

Runtime authority: successful V1.5.2 commit `2ed7072212bc1e9571163a914be6497c6254b702`, Actions run `33682632400`, artifact `9866894122`.

Current V1.5.3 status: **PREPARED / UPLOAD-READY, NOT YET V1.5.3 BUILD-PROVEN** until the packaged Actions workflow passes the pinned real GLSL compiler, real Java compiler, full `:app:assembleDebug`, invariance and final candidate export.
