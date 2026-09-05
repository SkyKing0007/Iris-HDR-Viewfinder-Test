# Iris HDR Viewfinder Test V1.4.11 V2.19

V2.19 is the **Photon-normalized post-fusion exposure correction** derived from exact successful V2.18 Actions authority (`f70e85bc3ca8a5ce0fcf0e0c4634ec786e141d73`, run `33945509036`, artifact `9963206632`).

## Device evidence

The supplied V2.18 AUTO shelf, chandelier and kitchen FUSED JPEGs preserve HDR information but render approximately 2.3–2.8 EV darker than the supplied Photon Camera references at the median/P90. V2.18 therefore solved the physical bracket correctly but calibrated final presentation to the intentionally dark MANUAL test.

## V2.19 contract

- V2.18 physical AUTO exposure remains byte-exact. The shelf stays around 4x / 2 EV rather than returning to the V2.17 ~20.6x failure.
- V2.17 LONG-truth fusion remains byte-protected: immutable LONG body/geometry, aligned SHORT recovery only for coherent LONG-information-loss regions.
- AUTO no longer targets the V2.18 pre-presentation P90 of only 0.020–0.024.
- Instead, AUTO derives a photographic scene key from fused P50/P90 and LONG highlight pressure, then predicts the existing shader's final P50/P90 over candidate Brightness/Gamma values and chooses the closest safe pair.
- High-contrast scenes keep darker medians but bright upper mids; isolated specular scenes lower key enough to preserve bulbs; ordinary scenes use a brighter kitchen-like key.
- The supplied shelf calibration predicts about P50 0.107 / P90 0.428 versus Photon 0.106 / 0.399.
- The supplied chandelier calibration predicts about P50 0.134 / P90 0.276 versus Photon 0.134 / 0.268.
- AUTO Dehaze/Micro are neutralized because saved mode 6 is a second global darkening exponent. MANUAL retains its existing adaptive presentation behavior.

## Runtime scope

Exactly one runtime file changes relative to successful V2.18:

- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`

`hdr_display.frag`, `HdrGlView.java`, `JpegFusion.java`, `CaptureSetSaver.java`, `MainActivity.java`, capture/DNG/flicker ownership and all other runtime files are byte-protected from successful V2.18.

V2.19 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for real project javac and full `:app:assembleDebug`; unchanged GLSL is rechecked by the unchanged successful V2.18 procedure.
