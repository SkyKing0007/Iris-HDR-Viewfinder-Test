# Iris HDR Viewfinder Test V1.4.11 V2.17

V2.17 is the **reversed-V2.15 LONG-truth + aligned-SHORT coherent recovery correction** derived from exact successful V2.16 Actions authority (`ce514f3fd6d1b75092c4e3bb2fa395dadb359950`, run `33939811219`, artifact `9961436910`).

## What V2.16 proved

The supplied V2.16 SHORT/LONG/FUSED shelf capture and zoom crops prove that the LONG-body direction is correct for overall SNR, but V2.16's full-resolution per-pixel recovery re-proof is not. The FUSED image contains gray/lavender holes, incomplete bright-wall pieces and blotchy missing SHORT structure even though the aligned SHORT source contains continuous valid highlight information.

V2.15 had the opposite quality tradeoff: its immutable SHORT body gave continuous source topology, but the whole saved image inherited lifted SHORT noise. V2.17 keeps V2.16's clean LONG body while reversing the V2.15 geometry/source-truth principle around LONG.

## V2.17 fusion contract

- **LONG is immutable output geometry and the default complete clean body.** LONG is never globally or locally moved in saved fusion.
- **SHORT is the only aligned auxiliary.** SHORT is globally registered to LONG, then receives the same bounded, bidirectional, cycle-consistent local residual field already proven by the V2.15/V2.16 implementation.
- The 1/16 GPU atlas establishes **coherent LONG-information-loss ownership regions**. It carries evidence/confidence only, never RGB or fine texture.
- Literal LONG clipping no longer requires every SHORT pixel to contain band-pass texture. Smooth valid SHORT wall/lamp/cloud/skin highlight shading is legitimate recoverable information.
- Effective pre-clip loss still requires bright-LONG context, aligned SHORT response superiority, radiometric agreement and trustworthy registration.
- Mode 4 performs seeded 5x5 atlas-region closure so weak smooth pixels cannot punch internal LONG holes through one valid SHORT recovery region.
- **Mode 5 does not re-prove ownership per pixel.** The V2.16 `step(0.58, recoveryProof)` path and `shortCoherentDetailAt` requirement are removed.
- Outside a coherent recovery region, exact LONG RGB/detail owns the output. Inside a recovery region, complete **aligned SHORT RGB/detail** owns the output.
- High-frequency source ownership remains binary. Fractional `mix(longScene, shortScene, ...)`, synthetic radiance fill and third-source RGB are forbidden.
- LONG texture is nearest-sampled as immutable output detail; aligned SHORT keeps linear sampling for bounded subpixel residual registration.
- Saved mode 6 remains pointwise and cannot synthesize spatial topology after source selection.

Live preview remains the successful V2.15/V2.16 behavior. V2.17 changes saved-still fusion geometry/ownership only.

## Exposure/capture contract is frozen

Successful V2.16 `CameraController.java` and `CaptureSetSaver.java` are byte-protected. AUTO retains meaningful bracket targeting up to **64× / 6 EV**, MANUAL retains at least **64× / 6 EV** legal control separation, Long ISO/shutter cannot re-solve SHORT, and requested/frozen/actual metadata must preserve **SHORT effective exposure ≤ LONG**.

## Runtime scope

Exactly two runtime files change relative to successful V2.16:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`

The other ten `app/src/**` runtime files are byte-protected, including `CameraController.java`, `CaptureSetSaver.java` and `JpegFusion.java`.

V2.17 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real glslang, real project javac, full `:app:assembleDebug`, exactly-one-APK proof and post-build invariance.
