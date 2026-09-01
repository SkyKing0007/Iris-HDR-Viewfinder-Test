# Iris HDR Viewfinder Test V1.4.15

V1.4.15 is an architectural HDR correction built from the exact successful V1.4.14 GitHub Actions compiled candidate. It preserves the successful V1.4.14 build/compiler/invariance mechanics, orientation/FOV/cadence/DNG protections, compact navigation-safe UI, and Brightness range while replacing the periodic metering takeover and fixed whole-pair HDR exposure policy.

## Continuous live AUTO metering

Clean HAL AE is now used only once as the initial natural-scene bootstrap. After that first anchor, the live HDR preview remains a continuous repeating SHORT/LONG burst; the old periodic five-second AE request takeover is removed.

`HdrGlView` samples a small 32x24 representation of the already displayed SHORT/LONG pair every 200 ms. Those statistics are passed directly to `CameraController`, so scene changes can update exposure without replacing the repeating HDR stream or freezing the displayed pair.

AUTO LONG exposure is adjusted in bounded steps of at most 0.30 EV per live update. This is intended to respond much faster than the old five-second remeter while avoiding abrupt multi-stop jumps.

## LONG appearance and adaptive SHORT are separate owners

Brightness remains `-5.0 EV` through `+2.0 EV` in 0.1 EV steps. It now expresses desired LONG/scene appearance, not HDR bracket size.

AUTO begins near 3 EV of SHORT headroom, then uses measured highlight evidence to choose more headroom only when needed. AUTO is bounded to 5 EV. MANUAL SAFE uses the same adaptive engine, retains the user's LONG/Brightness intent and requested SHORT headroom floor, adds 0.25 EV of safety margin, and can reach 6 EV when justified.

The policy deliberately targets a clean ~95% HDR result rather than forcing every tiny specular to recover. A meaningful clipped region must occupy at least about 0.5% of the sampled image before it can force SHORT darker. AUTO targets roughly 0.25% residual clipped SHORT area; MANUAL targets roughly 0.15%. Tiny isolated highlights may remain clipped instead of forcing SHORT into a noisy/quantized regime.

SHORT quality is also guarded by dark-pixel fraction, usable SHORT/LONG overlap count, and overlap radiometric error. Extreme headroom is not preferred when SHORT no longer contains trustworthy information.

## Color-safe fusion

V1.4.7's full-RGB handoff is removed. LONG remains the complete RGB/color owner wherever its data is still valid.

SHORT is first exposure-normalized, then calibrated back to LONG from pixels where both exposures are valid. Calibration is per channel and bounded. A SHORT channel can replace a LONG channel only when:

- that LONG channel is genuinely at the processed-JPEG ceiling;
- calibrated SHORT proves additional radiance;
- SHORT itself is not clipped;
- the other valid channels agree between exposures; and
- the pixel is a real bright highlight or has a second near-clipped LONG channel.

That last requirement specifically protects saturated single-channel colors such as reddish/peach skin from pulling SHORT into otherwise valid faces. No neighborhood chroma blur or broad desaturation/color repair is added.

Tone mapping leaves normal LONG mids alone and compresses only the upper/recovered highlight range, using one RGB scale so recovered highlight hue is preserved.

## MANUAL SAFE remains adaptive

HDR MANUAL is not locked to a fixed bracket. The user retains manual LONG/brightness intent and a SHORT headroom floor, while the same live highlight detector can make SHORT darker when the scene requires more protection. MANUAL carries slightly more headroom than AUTO rather than becoming a separate fixed-bracket algorithm.

## Stable APK updates

V1.4.15 introduces a stable test signing identity for GitHub Actions. The private PKCS12 key is not committed to this repository or included in the upload ZIP; Actions reconstructs it from the repository secret `IRIS_TEST_SIGNING_KEY_B64` and verifies both the keystore hash and final APK signing certificate before artifact export.

Because V1.4.14 and earlier successful GitHub debug APKs used ephemeral runner debug keys, installing V1.4.15 may require one final uninstall. After V1.4.15 is installed, future builds using the same secret and higher `versionCode` should install through Android's normal update path.

Stable signing certificate SHA-256:

`531aeed9ead79d28c424ad8f71a459b4ced8aff37e95c11bd295083fbb25c4e8`

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
