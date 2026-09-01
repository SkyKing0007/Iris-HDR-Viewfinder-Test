# Iris HDR Viewfinder Test V1.4.18

V1.4.18 keeps V1.4.17's successful adaptive LONG exposure and adaptive SHORT headroom unchanged, and corrects the remaining fusion-specific defect: live SHORT recovery could pulse locally and a genuinely clipped LONG region could receive only partial SHORT authority.

## Scene-general fusion ownership

The fused image still begins as LONG. LONG remains literal through shadows, midtones, ordinary color and every location where it retains usable highlight information.

Fusion decisions are not keyed to the office sample or to a fixed scene brightness. They use normalized processed-output clipping, linear luminance, exposure-normalized SHORT signal, SHORT saturation safety and local temporal trust. The same rules therefore apply to indoor, outdoor, daylight, low light, neutral highlights and saturated real color.

## Full recoverable clipped core

V1.4.18 separates the highlight region into two domains:

- **True clipped core:** when LONG has actually lost information and SHORT still contains usable signal, SHORT-derived luminance/detail authority reaches 1.0. Recovery is no longer weakened by multiplying several soft masks together.
- **Highlight shoulder:** only the outer transition around the clipped core is softly blended back into LONG to prevent a hard boundary.

SHORT that is itself clipped or too dark is still rejected in the true core. Temporal luma history shapes only the recoverable shoulder, while chroma history continues to reject unstable color. If both exposures lack the scene information, V1.4.18 leaves a clean LONG clip instead of inventing data.

## Stable live FUSED preview

The current SHORT/LONG textures continue to publish only as complete, generation-matched pairs. At a 60-fps alternating input stream this permits roughly 30 newly fused complete pairs per second; the 200-ms statistics pass is not the display frame-rate owner.

The 32x24 local reliability field is now graded rather than binary. Luma/detail history shapes the soft highlight shoulder but cannot scale down a genuinely clipped core when the current SHORT itself is usable. Chroma trust releases faster, so questionable SHORT tint is rejected before valid SHORT detail is discarded.

One marginal statistics sample can therefore no longer switch a valid recovery region from full SHORT authority to zero and then make it wait for two more samples to return.

## Live-to-JPEG trust parity

At shutter press, the current 32x24 two-channel reliability field is frozen together with the already-frozen SHORT/LONG exposure/ISO pair. That exact luma/chroma trust field is passed into full-resolution `JpegFusion` and bilinearly sampled over the captured image. Both live and saved fusion use current SHORT safety—not the slower history map—to grant complete luma/detail authority inside a true clipped core; the frozen history field shapes the shoulder and color confidence.

The saved FUSED JPEG therefore uses the same decision contract and the same shutter-time temporal prior as the live FUSED preview instead of independently inventing temporal reliability from a single still pair.

## Luminance and color remain separate authorities

Full SHORT detail authority never means blind SHORT RGB replacement. V1.4.18 preserves the V1.4.17 anti-pink design:

- trustworthy SHORT luminance/detail may fully recover a clipped LONG core;
- questionable SHORT chroma is replaced by LONG chromaticity at the recovered luminance;
- neutral LONG clips keep an additional white/neutral lock;
- only locally coherent SHORT color receives chroma authority.

## Preserved V1.4.17 behavior

- adaptive P25/P35/P50 LONG scene-body appearance;
- P90/P98 bright-tail evidence assigned to SHORT rather than globally darkening LONG;
- maximum 0.18-EV LONG update step with consecutive evidence;
- adaptive AUTO and MANUAL SAFE SHORT headroom;
- exact exposure-generation pair publication;
- known 50/60-Hz and unknown/PWM safety;
- scalar SHORT/LONG overlap calibration;
- frozen still exposure/ISO/post-RAW controls;
- Brightness remains LONG-appearance intent, not a post-fusion gain;
- FOV/cadence, orientation/DNG, explicit sRGB and stable-signing protections.

## Architectural backup

Before committing V1.4.18, create this branch from the exact successful V1.4.17 authority commit:

`backup-v1.4.17-pre-full-core-fusion` -> `b2c783c09e8e0a9050141e00c3bf23ee1617119e`

The V1.4.18 Actions gate verifies that branch before any compiler runs.

## Stable APK updates

V1.4.18 reuses the existing `IRIS_TEST_SIGNING_KEY_B64` repository secret. Do not regenerate the signing key.

Signing certificate SHA-256:

`531aeed9ead79d28c424ad8f71a459b4ced8aff37e95c11bd295083fbb25c4e8`

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
