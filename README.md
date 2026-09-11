# Iris HDR Viewfinder Test V1.4.11 V2.41

V2.41 is a focused correction on the exact successful V2.40 compiled candidate: commit `5f178aa746c7ccffe1e0148a82df2b8018ceb737`, tree `696d34bf26725694faf5c177832a9b256e75e734`, Actions run `34549873022`, artifact `10180424289`.

The device-proven V2.40 regression was a static HDR source-ownership seam: the final per-pixel hard-highlight motion safety belt could expose LONG around a SHORT-owned clipped component, creating white borders and grey outlines. V2.41 removes that final per-pixel ring and restores V2.39 coherent connected-highlight ownership after topology formation.

Motion protection is retained at the correct earlier owner. `JpegFusion.java` now distinguishes only decisive high-confidence distributed-residual outliers as explicit scene motion; weak or repetitive static matches are not motion barriers. `hdr_display.frag` prevents those explicit motion cells from seeding/accepting SHORT topology or equal-exposure temporal averaging, so incompatible temporal content keeps one coherent LONG source/natural blur.

V2.40 `CameraController.java` is byte-identical, preserving stable LONG physical-SNR acquisition and prevention of low-photon 1x collapse. The V2.40 static smooth exact/near-1x 50/50 averaging path also remains unchanged. V2.39 RAW denoise, direct microdetail, CFA/highlight reconstruction, color, presentation, DNG, WhiteLevel/BlackLevel, NAFNet and HdrGlView remain protected.

Runtime changes are exactly two files: `app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java` and `app/src/main/assets/shaders/hdr_display.frag`.

Before GitHub Actions succeeds, this package is prepared/upload-ready only. GitHub Actions remains authoritative for the pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug`.
