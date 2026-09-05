# Iris HDR Viewfinder Test V1.4.11 V2.16

V2.16 is the **LONG-owned body + immutable-SHORT HDR recovery correction** derived from exact successful V2.15 Actions authority (`b8996c649450ae1a19025d856e183aee609f16af`, run `33918963537`, artifact `9954318138`).

## What V2.15 proved

The supplied SHORT/LONG/FUSED captures show that V2.15's reversed registration direction finally behaves much better: SHORT remains fixed and LONG is aligned into SHORT coordinates. But V2.15 then made SHORT the entire saved spatial/RGB image, so ordinary walls, shadows, carpet and other body regions inherited the much noisier lifted SHORT exposure.

Earlier LONG-owned builds were cleaner, but their fusion could warp SHORT, fragment the ownership mask and fractionally mix LONG/SHORT RGB, producing disconnected gray/blue borders, false contours and peach/orange fill.

## V2.16 source contract

V2.16 preserves the V2.15 registration direction but separates geometry from image ownership:

- **SHORT is the immutable geometric reference.** It is never globally or locally warped.
- **LONG is aligned into SHORT coordinates.** Aligned LONG is the default saved body/SNR/RGB/detail owner.
- **SHORT owns only proven LONG information-loss regions.** Hard clipping or strict effective pre-clip detail collapse can qualify only when SHORT contains coherent recoverable detail, SHORT is valid, and registration is trustworthy. Smooth clipped illumination without recoverable detail remains LONG-owned.
- **Source ownership is binary.** There is no high-frequency `mix(longScene, shortScene, ...)` or fractional RGB crossfade.
- A SHORT-owned region uses complete exact SHORT RGB/detail. A LONG-owned region uses aligned LONG RGB/detail.
- The 1/16 analysis atlas contains evidence/confidence only, never source RGB or fine texture.
- Effective-loss proof uses fine and broad SHORT-vs-LONG structure dominance plus bright-LONG/radiometric/geometry checks so lifted SHORT noise cannot steal ordinary walls.
- Synthetic radiance-floor fill and unsupported peach/orange/gray reconstruction remain forbidden.
- Saved mode 6 remains pointwise; it cannot create spatial topology after source selection.

Live preview remains the successful V2.15 behavior; this build changes saved-still source ownership only.

## Exposure/capture contract is frozen

Successful V2.15 `CameraController.java` and `CaptureSetSaver.java` are byte-protected. AUTO retains meaningful bracket targeting up to **64× / 6 EV**, MANUAL retains at least **64× / 6 EV** legal control separation, Long ISO/shutter cannot re-solve SHORT, and requested/frozen/actual metadata must preserve **SHORT effective exposure ≤ LONG**.

## Runtime scope

Exactly two runtime files change relative to successful V2.15:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`

The other ten `app/src/**` runtime files are byte-protected, including `CameraController.java`, `CaptureSetSaver.java` and `JpegFusion.java`.

V2.16 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real glslang, real project javac, full `:app:assembleDebug`, exactly-one-APK proof and post-build invariance.
