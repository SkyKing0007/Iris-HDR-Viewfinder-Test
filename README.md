# Iris HDR Viewfinder Test V1.4.11 V2.9

V2.9 is the universal saved-HDR fusion correction derived only from the exact successful V2.8 GitHub Actions compiled candidate (`6fe30dc1a516edd17a5dce70f20c5f28ce620b11`, run `33825853402`, artifact `9919936639`). V2.8 remains runtime and final Actions authority until V2.9 itself passes Actions.

## What changes

Production saved fusion is now one GPU authority. `CaptureSetSaver` no longer silently substitutes the independent `JpegFusion.fuse()` CPU HDR algorithm if GPU fusion is unavailable or fails. SHORT/LONG JPEG and DNG capture/saving remain protected; a failed GPU fusion reports failure rather than producing a different HDR algorithm's result.

The existing `hdr_display.frag` is reused for a three-pass saved path: conservative source evidence, isotropic/topology support, and final provenance composition. No new shader file is introduced. HdrGlView preserves the proven V2.8 JPEG decode, forward/backward registration, SHORT-to-LONG alignment and calibration prelude, then performs the V2.9 saved passes in the existing GLES3 context.

The universal fusion contract is source-supported rather than scene-specific: valid LONG remains LONG; genuinely lost LONG may use registered SHORT only when SHORT signal/headroom, scalar radiometric proof, registration, spatial support and temporal/static evidence agree. Broad smooth highlights and compact legitimate emitters use separate conservative evidence paths. Nearby SHORT saturation vetoes broad/feather recovery to suppress colored rings. Proven cores choose one source exactly; only a conservative boundary may feather.

Production SHORT recovery uses one scalar radiometric gain so fusion does not create RGB-channel amplification fringes. Recovered highlight display is LONG-anchored while SHORT supplies supported local structure/color, preserving source-supported detail without the prior near-white flattening or fused-only bright geometry.

## Universal artifact/motion scope

The retained cloud/window sets plus the chandelier and plant-shelf sets were used as stress cases, not object-specific tuning targets. The contract is intended to apply to TVs changing frames, moving people/cars/foliage, LEDs, street lights, headlights, reflections and future scenes: temporal/structural disagreement fails closed rather than creating a blended third state. The spinning fan/shadow in the chandelier set is specifically used to ensure dark moving transient structure is not painted into a clipped ceiling recovery.

## Runtime scope

Exactly three runtime files change relative to successful V2.8:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`

`JpegFusion.java`, CameraController, DNG ownership, exposure policy, orientation, unrelated runtime files, and the live mode=2 physical-ratio HDR equations remain protected from V2.8.

## Verification

The successful V2.8 verification/build mechanics are inherited unchanged in order, action versions and compiler/build commands. V2.9 advances only the exact V2.8 authority pins, V2.9 version/hash/allowlist/artifact naming and permanent applicable regressions.

V2.9 is **PREPARED / UPLOAD-READY** until GitHub Actions runs the exact pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug` successfully.
