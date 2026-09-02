# Iris HDR Viewfinder Test V1.4.19

V1.4.19 preserves V1.4.18's successful adaptive LONG exposure, adaptive SHORT headroom, exact-generation pairing and full recoverable clipped-core authority. It corrects the remaining fusion-boundary defects seen on-device: residual 5-Hz luma pulsing, nonlinear processed SHORT/LONG photometric mismatch, and foliage/edge ringing where the two processed exposures met.

## Scene-learned photometric matching

A single SHORT calibration scalar is no longer allowed to own visible fusion. Both live GLSL and saved `JpegFusion` use the same five-knot monotonic response model learned from valid unsaturated SHORT/LONG overlap after exposure normalization. This lets the current scene teach the merger how the processed ISP response differs through the tonal range instead of assuming one multiplier fits every brightness.

The live five-knot response is temporally bounded to 0.06 EV per statistics update. The knots describe normalized scene luminance, not the office test scene, so the same mechanism adapts to indoor, outdoor, low light, mixed light and direct sun.

## Current-pair visible luma ownership

The current complete, exposure-generation-matched SHORT/LONG pair now owns visible luma recovery for both true clipped cores and recoverable highlight shoulders. The 32x24 200-ms reliability map no longer gates visible luma. This removes the remaining mechanism that could make a valid highlight shoulder brighten/dim at the slower statistics cadence.

Temporal history is retained where it remains useful: chroma confidence and controller-wide quality evidence.

## Full clipped core + edge-safe multiscale boundary

The image still begins as LONG. A true recoverable LONG-clipped core keeps full current-SHORT luminance/detail authority.

The outer transition is no longer a direct high-frequency LONG/SHORT source switch. V1.4.19 uses an edge-guided one-level Gaussian-mask/Laplacian-image construction:

- low-frequency exposure/color differences use the smoother transition mask;
- fine detail uses a tighter mask closer to the current-pixel decision;
- one-sided LONG damage support prevents SHORT authority from crossing intact dark edges;
- current-pair disagreement suppresses non-core mixing when the two processed exposures do not represent the same local detail.

This is specifically intended to prevent bright/dark/green contours on foliage, branches, text, wires and other high-frequency boundaries around clipped regions without weakening true clipped-core recovery.

## Chroma protection remains separate

Full SHORT detail authority still does not mean blind SHORT RGB replacement. If SHORT color is questionable, recovery keeps SHORT luminance/detail while retaining LONG chromaticity. The neutral highlight lock and anti-pink/anti-orange protections remain intact.

## Preserved V1.4.18 behavior

- adaptive P25/P35/P50 LONG scene-body appearance;
- P90/P98 bright-tail evidence assigned to SHORT;
- max 0.18-EV LONG update with consecutive evidence;
- adaptive AUTO and MANUAL SAFE SHORT headroom;
- exact exposure-generation pair publication;
- known 50/60-Hz and unknown/PWM safety;
- frozen still exposure/ISO/post-RAW controls and live-to-still chroma trust snapshot;
- Brightness remains LONG-appearance intent, never post-fusion gain;
- FOV/cadence, orientation/DNG, explicit sRGB and stable-signing protections.

## Rollback protection

No new backup branch is required for V1.4.19. The exact successful V1.4.18 compiled candidate is the base, with deterministic full-index forward/rollback patches and strict 3-runtime-file allowlist proof.

## Stable APK updates

V1.4.19 reuses the existing `IRIS_TEST_SIGNING_KEY_B64` repository secret. Do not regenerate the signing key.

Signing certificate SHA-256:

`531aeed9ead79d28c424ad8f71a459b4ced8aff37e95c11bd295083fbb25c4e8`

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
