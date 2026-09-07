# Iris HDR Viewfinder Test V1.4.11 V2.26

V2.26 is based on the exact successful V2.25 compiled candidate: commit `66b689922267396afa8b8cedf1ca848d1eabffcb`, Actions run `34057462644`, artifact `9996424736`.

## What V2.26 corrects

The V2.25 window sample isolated two remaining architectural failures. SHORT exposure was finally correct (about 3.8 EV below LONG with useful exterior detail), but global LONG P95 still acted like a hidden window veto and left the body underexposed/noisy. At the same time, SHORT ownership/tone remained too restrictive to preserve all recoverable shutters, exterior structure and highlight separation. The live HDR viewfinder also still used a globally lifted SHORT body, explaining its visible noise.

V2.26 applies the intended two-exposure principle directly:

- **LONG owns body/SNR.** AUTO now measures a highlight-excluded LONG body population and solves from body P50/P75. Bright windows/bulbs may clip in LONG rather than starving the room of photons.
- **SHORT owns true lost highlight information.** Saved fusion keeps the successful V2.25 LONG-immutable geometry, strict registration, binary source ownership and connected geodesic reconstruction, while the loss detector now recognizes pre-clipping flattening when SHORT demonstrably retains stronger real structure.
- **No RGB blending or local color invention.** Each saved output coordinate is exact LONG or aligned SHORT RGB, preserving the artifact protections that eliminated gray islands, peach/orange blocks and disconnected blotches.
- **HDR preview parity.** Live HDR is now LONG-body by default and admits SHORT only through a conservative information-loss/radiometric proof. The old whole-frame SHORT × exposure-ratio preview is removed.
- **Stop-domain highlight reproduction.** Recovered SHORT energy is mapped in exposure stops above the 0.70 knee so several stops of valid detail remain visibly ordered instead of collapsing near white.
- **Noise is fixed at the source.** V2.26 gives LONG more physical body exposure and uses LONG for live/saved body information. V2.25 NAFNet remains byte-identical constrained residual cleanup; its strength/ownership is not expanded.

## What stays protected

The V2.25 saved-still registration/render orchestration, mode-4 geodesic topology propagation and mode-5 binary LONG/SHORT source selection are byte-pinned. NAFNet/model, touch AF, background-safe processing, DNG handling, media writing, capture ownership and non-HDR shaders remain unchanged.

The runtime change is exactly four files: `CameraController.java`, `HdrGlView.java`, `JpegFusion.java`, and `hdr_display.frag`.

## Build proof

The successful V2.25 GitHub Actions procedure is inherited without reordering or substitution. Java 17, Android SDK/API 37, Gradle 9.6.0, pinned glslang installation, reserved scan, exact GLSL compile, real project Java compile and full `:app:assembleDebug` step bodies are byte-identical to V2.25. Only authority/version/hash/allowlist/regression/output payloads change.

V2.26 is **PREPARED / UPLOAD-READY only after final clean-extract replay**. It is not build-proven until its own GitHub Actions run succeeds.
