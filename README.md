# Iris HDR Viewfinder Test V1.4.11 V2.40

V2.40 is a focused motion-safety and acquisition-stability correction on the exact successful V2.39 compiled candidate: commit `092b1659d0e28560ea355d0e1f85e36f241802b7`, tree `68a8674486940ce9b067758770364cdb36233f7a`, Actions run `34532782246`, artifact `10174233979`.

V2.39 remains the RAW-quality, direct-microdetail, CFA/highlight and presentation authority. V2.40 changes exactly three runtime files:

- `CameraController.java`: keeps LONG-body and SHORT-highlight acquisition independent when a scene still spans at least ~3 EV, even if the currently dark pair no longer reaches digital clipping. This prevents the device-proven noisy 1x collapse (`~1/3100s ISO50` SHORT==LONG) while retaining genuine high-SNR low-DR 1x operation.
- `JpegFusion.java`: adds a robust distributed affine residual-flow consensus after the proven V2.38 global registration. A local match may retain direct SHORT-warp authority only when it is cycle-valid **and** agrees with the distributed static-background residual model; locally repeatable moving objects can no longer become static source truth merely by matching themselves forward/backward.
- `hdr_display.frag`: (1) equal/near-equal static smooth regions may use strong global-registration evidence when local texture is insufficient, giving a true 50/50 two-frame average at ratio 1; (2) connected clipped HDR recovery is finally checked against the transform actually used by mode 5 and against unsaturated static boundary witnesses, preventing topology-only SHORT blocks/trails at motion/disocclusion boundaries.

V2.39 RAW noise cleanup, direct coherent microdetail evidence, bounded Catmull-Rom detail sampling, physical RAW sigma/saturation carrier, common-quad highlight reconstruction, saturation-transition chroma repair, WhiteLevel/BlackLevel handling, DNG ownership, NAFNet, Manual Safe/SPLIT/extreme-emitter presentation and GPU lifetime remain protected. `HdrGlView.java` was audited and is not modified because the existing statistics and uniforms already carry all V2.40 evidence.

Build mechanics inherit the exact successful V2.39 procedure unchanged: exact Actions-artifact authority reconstruction, strict authority-seeded allowlist, complete GLSL reserved-identifier scan, pinned real GLSL compile, real project Java compile, deterministic full-index forward/rollback proof at core.abbrev 7/12/40 plus GNU fuzz=0 replay, PRE-BUILD proof, full `:app:assembleDebug`, exactly-one-APK/model proof, post-build candidate/protected invariance and final compiled-candidate artifact export.
