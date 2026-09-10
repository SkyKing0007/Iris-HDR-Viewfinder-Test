# Iris HDR Viewfinder Test V1.4.11 V2.37

V2.37 is a localized presentation-control correction on the exact successful V2.36 compiled candidate: commit `1268c56ae19bcff6a8c9bec42fdc9c911a8436d4`, tree `9665c112f2ab7c0aa0cc6d05cbce77894cacda24`, Actions run `34477638919`, artifact `10152261992`.

V2.36 remains the image-quality authority. Its CFA/highlight reconstruction, fusion/registration, acquisition, denoise and saved FUSED output are protected. V2.37 corrects only two device-proven presentation problems:

- In HDR MANUAL SAFE, Brightness/Gamma become the sole user-owned live controls. Their labels and renderer update immediately from the sliders; asynchronous automatic presentation callbacks may update Dehaze/Microcontrast but may not move or overwrite manual Brightness/Gamma.
- SPLIT gains a preview-only manual presentation branch: both halves consume the same user-owned Brightness/Gamma values. Automatic Dehaze/Micro continues to be solved and stored for FUSED output but is deliberately not applied in SPLIT, preserving SPLIT as a direct SHORT/LONG diagnostic comparison with no FUSED clarity/body-tone/HDR-shoulder logic.
- Live FUSED and saved FUSED shader bodies remain byte-identical to V2.36. CameraController continues to freeze the exact selected manual Brightness/Gamma/Dehaze/Micro values at shutter time, preserving the final FUSED JPEG behavior already validated on device.
- AUTO gains a narrowly scoped extreme-emitter presentation pressure. It requires a real ~3EV physical bracket, substantial LONG clipping, and strong highlight survival in SHORT. At full pressure it blends toward the user-proven direct-sun presentation (`-1.4 EV`, `gamma 1.15`) and reuses the existing MANUAL SAFE automatic Dehaze/Micro formula. Supplied Costco, restaurant and bright-car fixtures remain zero-pressure V2.36 AUTO.
- No sun/daylight/object classifier or cross-scene histogram normalization is used; ordinary scenes retain V2.36 AUTO unchanged.

Build mechanics retain the successful V2.36 sequence: exact Actions-artifact authority reconstruction, strict changed-file allowlist, deterministic full-index forward/rollback proof at `core.abbrev` 7/12/40 plus GNU `fuzz=0` text replay, pinned real GLSL, real project Java, full `:app:assembleDebug`, post-build candidate/protected invariance and final compiled-candidate artifact export.
