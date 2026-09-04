# Iris HDR Viewfinder Test V1.4.11 V2.12

V2.12 is the **scene-adaptive AUTO exposure + adaptive presentation** build derived from the exact successful V2.11 Actions compiled candidate (`7e5a295d01748da0255e831ff19d3d31a2da0e3b`, run `33872977271`, artifact `9936710832`). V2.11 remains final Actions/runtime authority until V2.12 passes the same authoritative build procedure.

## Why AUTO was wrong

The supplied AUTO/MANUAL plant-room pair is nearly controlled: both SHORT captures are about `1/120s ISO50`, but AUTO used LONG about `1/120s ISO1556` (~4.96 EV separation) while the visually successful MANUAL used LONG about `1/120s ISO200` (2.00 EV). Fusion was therefore receiving an unnecessarily overexposed LONG in AUTO.

The active V2.11 controller preserved the initial HAL-derived scene brightness and could let the bracket expand when SHORT hit its physical/flicker/minimum-ISO floor. V2.12 removes that ownership error.

## V2.12 exposure controller

AUTO now treats SHORT as the highlight-information anchor. The existing 32x24 paired live-statistics path measures the SHORT highlight tail and chooses a feasible LONG/SHORT separation from actual scene content. On the supplied good scene, the runtime-domain SHORT statistics infer about 4x / 2 EV, matching the successful manual bracket without hard-coding ISO200.

If SHORT cannot become darker because of sensor/ISO/flicker constraints, LONG is reduced to the achieved SHORT times the learned bracket instead of silently increasing bracket depth. The successful V2.10/V2.11 50/60-Hz timing authority and live hysteresis/update/scene-cut constants are preserved.

## Adaptive presentation

Capture and presentation are separate owners:

`scene statistics -> feasible SHORT/LONG capture -> V2.11 GPU fusion -> adaptive global presentation`

AUTO learns global Brightness/Gamma from fused-scene statistics. MANUAL keeps the user's Brightness/Gamma sliders authoritative. In both modes, a bounded scene-adaptive clarity controller recalculates dehaze/microcontrast around the current scene and slider presentation.

The clarity stage is intentionally conservative: luminance-only, RGB-ratio preserving, a symmetric five-tap range-weighted guide, and explicit noise-floor/strong-edge/highlight protection. It does not use CLAHE, unsharp mask, sharpening, independent RGB dehaze or local-HDR texture synthesis.

Brightness, Gamma, dehaze and microcontrast freeze together at shutter and are used by saved GPU fusion and written to capture metadata, preventing live/saved presentation drift.

## Protected V2.11 behavior

V2.11 scene-domain provenance remains intact. Evidence mode 3 and support mode 4 are byte-pinned; JpegFusion, registration geometry, DNG/orientation, capture temporal ownership and GPU-only saved fusion remain protected. V2.10/V2.11 AUTO/50Hz/60Hz/OFF flicker behavior is unchanged.

## Runtime scope

Exactly five runtime files change relative to successful V2.11:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java`

V2.12 is **PREPARED / UPLOAD-READY only after the final clean-extract replay**, and is not build-proven until GitHub Actions passes real glslang, real project javac, full `:app:assembleDebug`, one-APK proof and post-build frozen-candidate invariance.
