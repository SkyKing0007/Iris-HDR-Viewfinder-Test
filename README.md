# Iris HDR Viewfinder Test V1.5.0

V1.5.0 is the first sensor-domain SHORT+LONG still-HDR candidate. It keeps the existing finished live HDR viewfinder but replaces the saved fused-JPEG authority with the matched full-resolution RAW_SENSOR SHORT/LONG pair already captured by Iris.

The principal goal is WYSIWYG HDR: LONG remains the natural scene/body appearance owner; aligned SHORT supplies recoverable highlight measurements; one CFA-aware fusion occurs before a single demosaic/WB/color transform; then the RAW still and live reconstructed HDR use the same global tone-mapping function. No local tone mapping is introduced.

The implementation is designed specifically to prevent the earlier hard-white shelf, fusion-boundary, row/PWM, and pink/green/blue highlight failure classes. SHORT is not broadly mixed into healthy LONG midtones. Saturated or invalid LONG CFA phases can be replaced from valid same-CFA SHORT evidence before demosaic; real motion/radiometry disagreement fails closed.

Real Xiaomi fixture evidence for this architecture measured 311044 of 311119 physically clipped LONG CFA samples (99.975893%) with usable aligned SHORT headroom, well above the 95% recoverable-highlight target for coherent regions.

Current status: **PREPARED / UPLOAD-READY, NOT BUILD-PROVEN**. The package has passed local static/semantic/patch checks, but the pinned real GLSL compiler, real Android Java compiler and full `:app:assembleDebug` must run successfully in GitHub Actions before V1.5.0 is called build-proven.

Runtime authority is successful V1.4.23 V1.1 commit `f340d8de62d41e9c505b3936b2b0af543deb9c53`, Actions run `33591832342`, artifact `9832029897`. Safety backup `backup-v1.4.23-v1.1-before-raw-short-long-hdr` is verified at that same commit.
