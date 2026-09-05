# Iris HDR Viewfinder Test V1.4.11 V2.18

V2.18 is the **MANUAL-calibrated AUTO HDR normalization correction** derived from exact successful V2.17 Actions authority (`e946baa2b8213d48263cdc0fdc1ed1436b5fdae2`, run `33942346596`, artifact `9962252229`).

## Device evidence

The supplied V2.17 shelf comparison isolates the remaining failure to AUTO policy rather than fusion geometry. MANUAL is clean at an actual 60-Hz-safe pair of approximately SHORT 1/120 ISO50 and LONG 1/120 ISO200 (4x / 2 EV), with user presentation near -2.4 EV / gamma 1.55. AUTO on the same scene drives LONG to about ISO1028 at the same shutter (about 20.6x / 4.36 EV), broadly clips the LONG body and forces much larger SHORT-owned regions, producing cyan/warm source-switch artifacts.

## V2.18 AUTO contract

- Successful V2.17 LONG-truth fusion is byte-protected and unchanged. LONG remains immutable output geometry/body; aligned SHORT still owns only coherent LONG-information-loss regions.
- AUTO uses the clean MANUAL result as a **behavioral calibration**, not as a fixed preset.
- SHORT P50/P90 body targets are reduced to a MANUAL-like operating point, while a lower signal floor preserves meaningful 4x..64x / up-to-6-EV capability in genuinely dark scenes.
- AUTO adds closed-loop LONG-body protection using observed LONG P95, P98 and near-clip fraction. AUTO may deepen the bracket only while LONG remains useful as the clean primary photograph.
- The supplied clean MANUAL histogram regresses to a 4.0x target; the supplied bad AUTO histogram is driven from ~20.6x back toward 4.0x.
- AUTO presentation now follows the successful MANUAL strategy: protect the upper body with negative display EV first, then recover midtones with gamma instead of physically overexposing LONG. The reference scene evaluates near -2.35 EV / gamma 1.55, but values remain scene-derived.
- AUTO brightness range is no longer trapped at -1.25 EV; it can reach -4 EV when required. Gamma may use the same 0.50..2.00 mathematical range available to MANUAL.

## MANUAL / flicker control truth

The physical 50/60-Hz safety solver is unchanged. When SAFE timing remaps a requested shutter/ISO, the MANUAL callback now reports the **effective realizable values**, and the UI sliders snap to those actual values. For example, a 60-Hz-safe SHORT request faster than one 1/120-s cycle no longer remains displayed as a hidden 1/240 request while Camera2 receives 1/120. MANUAL remains the user adjustment path.

## Runtime scope

Exactly two runtime files change relative to successful V2.17:

- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java`

`hdr_display.frag`, `HdrGlView.java`, `JpegFusion.java`, `CaptureSetSaver.java`, DNG ownership and all other runtime files are byte-protected from successful V2.17.

V2.18 is **PREPARED / UPLOAD-READY only after clean-extract replay**. GitHub Actions remains authoritative for real project javac and full `:app:assembleDebug`; unchanged GLSL inherits the exact successful V2.17 compiled bytes and is rechecked by the unchanged Actions procedure.
