# Iris HDR Viewfinder Test V1.4.11 V2.4

V2.4 is derived **only** from the exact successful V1.4.11 V2.3 GitHub Actions compiled candidate. The V1.5.2 build was inspected only to understand the already-observed ownership failure: once LONG is damaged, LONG must not veto valid SHORT evidence. No RAW/V1.5 runtime files or algorithms are imported.

## Change from successful V2.3

### Damaged LONG can no longer veto real SHORT RGB

V2.3 still required exposure-normalized SHORT and LONG JPEG luminance to agree before the still-only recovery gate could strongly select SHORT. That is self-defeating when LONG itself contains the peach/orange HAL-rendered blotch: the correct SHORT texture disagrees with the damaged LONG rendering, so LONG vetoes the evidence that should replace it.

V2.4 changes only saved JPEG fusion ownership:

- LONG remains the normal shadow/midtone/body source.
- SHORT proves itself from its own encoded signal and highlight headroom.
- LONG supplies only a bright/damage trigger; LONG-vs-SHORT radiometric agreement is **not** required once that trigger is active.
- When the gate proves SHORT, the complete exposure-normalized **SHORT RGB** owns the pixel; LONG chroma is not painted back over it.
- SHORT that is too dark or itself clipped fails closed to LONG.
- The same scalar ownership math is used by the GLES3-primary still pass and the explicit CPU emergency fallback.
- The rule remains pixel-local: no texture synthesis, sharpening, neighborhood fill, or cross-edge hallucination operator is introduced.

On the original supplied office SHORT/LONG pair, this raises full-strength SHORT ownership over the bright outdoor ground/window texture while leaving the dark office floor overwhelmingly LONG-owned.

## Preserved successful V2.3 behavior

- exact V2.3 immediate scene-cut AUTO response;
- 32x24 live LONG statistics every 100 ms;
- fixed ~8x / 3 EV SHORT↔LONG bracket;
- GLES3-primary full-resolution saved fusion with CPU fallback only on explicit GPU failure;
- Brightness **-16.0 EV through +1.0 EV** in 0.1 EV steps;
- Gamma 0.50..2.00, 1.00 neutral;
- V2.2/V2.3 live HDR ownership remains unchanged (`mode=2`); the correction is still-only (`mode=3`);
- side-by-side application ID `com.skyking0007.irishdrviewfinder.v1411v2`;
- producer-owned orientation/FIT geometry;
- fixed-height status-row bounce correction;
- shutter-time freeze of SHORT/LONG exposure/ISO/post-RAW boost/Brightness/Gamma;
- RAW DNG and individual SHORT/LONG JPEG saves.

Target branch: `experiment-v1.4.11-v2-brightness-4ev`.
