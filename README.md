# Iris HDR Viewfinder Test V1.4.11 V2.10

V2.10 is the universal **visual/effective clipping + real 50/60-Hz flicker-authority** correction derived only from the exact successful V2.9 GitHub Actions compiled candidate (`dcf49e339b3ecb89b032884b258fd2586e4b8b32`, run `33834010078`, artifact `9922681173`). V2.9 remains runtime and final Actions authority until V2.10 itself passes Actions.

## Real flicker authority

V2.10 intentionally supersedes the inherited behavior that could use arbitrary AUTO shutters when Camera2 reported flicker `NONE`, and that kept MANUAL SHORT shutter exact while snapping only LONG. The user now has explicit `AUTO / 60Hz / 50Hz / OFF` authority.

When a real 50/60-Hz period is authoritative and legal sensor bounds allow it, **both SHORT and LONG** use integer mains-cycle integration windows. LONG ISO is used preferentially to preserve requested exposure energy; SHORT retains minimum-ISO preference. AUTO claims safety only when Camera2 explicitly proves 50Hz or 60Hz. `NONE` and unknown/PWM are explicitly reported unsafe rather than silently labeled protected. Initial AUTO anchor and later live solving share the same safe-period projection. Existing 60-FPS exposure limits remain intact.

The existing AUTO metering convergence constants are unchanged. V2.10 fixes timing authority without using one slow-settling sample as justification for unrelated exposure-control retuning.

## Visual/effective clipping

V2.9's GPU-only production architecture is preserved: the existing `hdr_display.frag` still runs evidence, isotropic/topology support and final provenance passes in the existing HdrGlView GLES3 context. `HdrGlView.java`, `CaptureSetSaver.java` and `JpegFusion.java` remain byte-identical to successful V2.9, so SHORT→LONG registration/alignment, DNG/orientation ownership and GPU-only output routing are not redesigned.

V2.10 no longer defines recoverable clipping only as numerical near-white saturation. A bright LONG region may also be **effectively/visually clipped** when registered SHORT proves local response that LONG flattened: source-corresponding tonal range/gradient detail, shading, or coherent real chroma. Valid LONG remains LONG.

Below hard clipping the detector is intentionally fail-closed against motion and display/flicker phase changes. A changed TV/LED frame cannot qualify from brightness or color difference alone: recovered range must have corresponding gradient structure, while chroma-only recovery requires supporting color topology from less-damaged LONG surroundings. If evidence is ambiguous, one source wins rather than blending a third temporal state.

V2.9's compact near-neutral correction is intentionally superseded. Once SHORT legitimately owns a highlight, complete source-supported SHORT RGB/chromaticity survives; it is no longer collapsed to 15% chroma. A saturated center SHORT still cannot own. Nearby SHORT saturation is contextual rather than an unconditional poison radius, so a saturated filament can coexist with valid surrounding glass, brass, metal or surface evidence.

Recovered source luma is mapped from the trustworthy SHORT response and gamut-bounded, preserving source-supported local highlight ranking/contrast rather than driving different highlights toward one narrow bright plateau.

## Universal scope

The contract is intended for chandeliers/bulbs/brass, clouds/sky, windows, plant shelves/white pots, street lights/headlights, TVs/LED signs, reflections/chrome/speculars, faces/skin highlights, foliage and moving subjects. It uses no scene coordinates, object labels or scene-specific colors. Retained outdoor cloud/window, chandelier, plant-shelf and fan/shadow cases remain device regressions and require post-build 600–1000% visual inspection.

## Runtime scope

Exactly three runtime files change relative to successful V2.9:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java`

`HdrGlView.java`, `CaptureSetSaver.java`, `JpegFusion.java`, DNG/orientation, SHORT→LONG registration geometry, capture adjacency/immutability and the protected live mode=2 shader remainder stay inherited from successful V2.9.

## Verification

The successful V2.9/V2.8 verification/build mechanics are inherited unchanged in step order, action versions and compiler/build commands. V2.10 advances only the exact V2.9 authority pins, version/hash/allowlist/artifact naming and applicable permanent regressions.

V2.10 is **PREPARED / UPLOAD-READY** until GitHub Actions runs the exact pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug` successfully.
