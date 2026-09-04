# Iris HDR Viewfinder Test V1.4.11 V2.11

V2.11 is a localized **saved-HDR scene-domain provenance correction** derived from the exact successful V2.10 Actions compiled candidate (`7dcf9b7c3ef68455c05202cfd637ced85cd7f32b`, run `33837947958`, artifact `9923932300`). V2.10 remains final Actions/runtime authority until V2.11 passes the same authoritative build.

## Why V2.10 failed visually

The new chandelier capture provided an exact visual failure rather than a threshold guess. The live HDR viewfinder looked reasonable, but the saved FUSED JPEG turned jointly saturated lamp pixels into a flat gray field and created stippled transition texture absent from both SHORT and LONG.

For the jointly near-white lamp population, roughly 96% of saved FUSED pixels landed near `107/255`. That value is reproduced by the V2.10 saved-mode math when a clipped LONG pixel fails SHORT ownership and the `-4.5 EV / gamma 1.55` presentation is applied to LONG. The same registered SHORT/LONG pair proves about `16.905x` SHORT-to-LONG linear scene radiance, which should remain visually bright under the same presentation.

This is therefore a rendering/provenance-order failure, not an alignment failure.

## V2.11 correction

V2.11 changes only `hdr_display.frag` at runtime.

Successful V2.10 evidence/support modes 3/4 and the entire live mode=2 remainder are byte-identical. Saved mode=5 now composes provenance in scene-linear space before presentation:

`LONG + registered SHORT -> source/radiance provenance -> one common brightness/body/HDR/gamma presentation -> JPEG`

Complete SHORT RGB ownership still requires valid V2.10 registration/source/support evidence. A SHORT sample that is itself too clipped cannot own complete RGB, but when LONG is genuinely multi-channel clipped and the registered/static SHORT proves greater radiance, it may raise only the scene-radiance lower bound. It cannot inject unsupported SHORT texture or hue.

Full-resolution SHORT validity is continuous rather than a binary post-tone switch, while the existing low-resolution support atlas remains the spatially coherent ownership context. This removes the failure mode where neighboring pixels alternate between darkened LONG and recovered SHORT and produce stipple not present in either source.

## Protected V2.10 behavior

- Real AUTO / 50Hz / 60Hz / OFF flicker authority is unchanged.
- Both actual SHORT and LONG 60Hz-safe timing behavior is unchanged.
- `CameraController.java` and `MainActivity.java` are byte-identical to successful V2.10.
- `HdrGlView.java`, `CaptureSetSaver.java`, `JpegFusion.java`, registration/alignment, DNG/orientation, capture temporal ownership and GPU-only production routing are unchanged.
- No new shader asset, CPU HDR fallback, local sharpening, RGB neighborhood fill or OpenCV dependency is introduced.

## Permanent visual regressions

V2.11 permanently adds the exact chandelier failure: jointly saturated source-supported lamp pixels must not collapse to ~107/255 gray at `-4.5 EV / gamma 1.55`, and stronger source-supported SHORT scene radiance must never render darker at the same clipped LONG location. Recovered boundaries may not create stippled/high-frequency structure absent from both sources.

Retained cloud/window, plant shelf, chandelier, fan/shadow, TV/LED, street-light/headlight, reflection, foliage, skin-highlight and motion cases remain universal post-build visual regressions. Acceptance remains visual at 600–1000% as well as numeric.

## Runtime scope

Exactly one runtime file changes relative to successful V2.10:

- `app/src/main/assets/shaders/hdr_display.frag`

V2.11 is **PREPARED / UPLOAD-READY**, not build-proven, until GitHub Actions passes the exact successful V2.10 pinned real GLSL compile, real project Java compile, full `:app:assembleDebug`, one-APK proof and post-build frozen-candidate invariance.
