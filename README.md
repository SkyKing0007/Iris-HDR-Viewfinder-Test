# Iris HDR Viewfinder Test V1.4.11 V2.22

V2.22 is the **information-relative SHORT validity + independent AUTO SHORT highlight-protection correction** derived from exact successful V2.21 Actions authority (`320548b3af7b2989bac75b9c987218bc5d3defe5`, run `34000719226`, artifact `9979410043`).

## Exact device evidence

The V2.21 chandelier tests isolate two remaining failures. First, SHORT JPEG still visibly contains filament structure, yet V2.21 rejects parts of that SHORT region whenever `max(R,G,B)` approaches clipping. Those rejected pixels fall back to clipped LONG and become flat gray blocks after HDR tone mapping. Second, AUTO captured roughly SHORT 1/50 ISO50 / LONG 1/50 ISO197 (~4x), while the much cleaner MANUAL reference used SHORT 1/120 ISO50 / LONG 1/120 ISO200. The old AUTO SHORT policy allowed P99~0.73 and ~0.8% near-clipping, so it did not protect the filament aggressively enough.

## V2.22 fusion contract

- LONG remains the primary clean saved body/default RGB/detail source.
- V2.20/V2.21 connected-region convergence and measured-flow motion/disocclusion barriers remain unchanged.
- SHORT strict seed validity becomes **channel-aware and information-relative** instead of using one max-channel headroom veto.
- A SHORT pixel may remain useful when one channel is near/clipped if other channels or local structure retain more information than LONG.
- Once a connected LONG-loss component is admitted, near-clipped SHORT interiors remain in the propagation domain as long as real SHORT signal exists; max-channel clipping may not punch gray LONG holes into the component.
- Mode 5 remains binary real-source ownership. No LONG/SHORT RGB mixing, synthetic highlight texture, CPU fusion or third-source RGB is introduced.
- Existing 4x / exact 20x / 64x topology invariance remains mandatory.

## V2.22 AUTO exposure contract

- Preserve the inherited V2.18/V2.21 LONG-body target first.
- Independently protect SHORT's absolute highlight tail using P99 and near-clip pressure.
- SHORT remains at minimum sensor ISO and may shorten to the shortest appropriate flicker-safe integration when highlight information demands it.
- Shortening SHORT widens the bracket naturally instead of automatically dragging LONG body exposure down.
- Only the hard 4x..64x bracket contract may bound the independent SHORT/LONG targets.
- Exact chandelier regression: the old AUTO ~1/50 ISO50 SHORT condition must request materially less SHORT exposure, while a MANUAL-like protected 1/120 ISO50 tail must not be overreacted to.
- V2.21 P10/P25/P50/P90 final-tone framework remains unchanged.

## Runtime scope

Exactly two runtime files change relative to successful V2.21:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`

`HdrGlView.java`, `JpegFusion.java`, `CaptureSetSaver.java`, `MainActivity.java`, DNG/capture ownership, existing convergence mechanics and all other runtime files remain byte-protected from successful V2.21.

V2.22 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for pinned real GLSL, real project javac and full `:app:assembleDebug`.
