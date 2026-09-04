# Iris HDR Viewfinder Test V1.4.11 V2.14

V2.14 is a **strict source-proven HDR fusion correction** derived from exact successful V2.13 V1.2 Actions authority (`5e3f6bbc05b0ec1aeb70cce5acc1d719f760858d`, run `33904657511`, artifact `9949016091`).

## Primary evidence

The implementation is driven primarily by the supplied SHORT/LONG/FUSED office capture and 600–800% crops. The FUSED image contained disconnected gray/blue lines, broken contour fragments and false micro-edges over the ground/road/shrubs/signs that did not exist in the source imagery; earlier captures also showed unsupported peach/orange fill. Major scene geometry was already aligned, but the crops showed that fine local residual alignment plus fragmented ownership could still create a third false edge.

## Hard exposure-order contract

SHORT may never have a greater effective exposure product than LONG in AUTO or MANUAL. Long ISO remains LONG-only. Every solved/frozen request pair is rechecked and an inversion is repaired by raising LONG only; actual Camera2 result exposure/ISO metadata is checked again before fusion, and an inverted or invalid pair is rejected instead of being hidden as a 1x ratio.

AUTO retains the V2.13 scene-driven 4x..64x HDR target and existing 50/60-Hz/UNSAFE behavior.

## Registration and strict source provenance

The successful V2.13 global registration remains the coarse anchor and is byte-protected. V2.14 adds a bounded local residual field only after that transform: analysis is exposure-invariant, bidirectional, cycle-consistent and spatially regularized, with a hard 4-source-pixel bound. Unsupported cells fall back to the proven global alignment rather than inventing flow.

For saved mode 5, high-frequency texture and chroma have one source owner whenever SHORT/LONG disagree: locally registered SHORT or LONG. The coarse 1/8 support atlas is only a broad recovery-region prior and cannot own individual grass, foliage, road, sign or border edges. Fractional transition is permitted only where full-resolution registered gradients and radiometry already agree, so blending cannot manufacture a third displaced contour.

LONG may surrender detail below literal JPEG white when registered SHORT retains materially stronger local structure and source radiometry supports effective information loss. V2.13's synthetic radiance-floor extrapolation is removed. Saved mode 6 is pointwise only, so no post-fusion spatial clarity stage can turn small provenance errors into disconnected gray/blue borders.

The saved-output rule is strict: **no edge, streak, contour, hue or texture may be created unless it is supported by a registered source sample.**

## Runtime scope

Exactly five runtime files change relative to successful V2.13 V1.2:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java`

`MainActivity.java`, `FrameMeta.java`, `MediaStoreWriter.java`, AndroidManifest, DNG/orientation and the remaining shader assets are byte-protected. The successful global registration and appearance-calibration slices inside `JpegFusion.java` are separately hash-protected.

V2.14 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real glslang, real project javac, full `:app:assembleDebug`, exactly-one-APK proof and post-build invariance.
