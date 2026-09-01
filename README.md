# Iris HDR Viewfinder Test V1.4.17

V1.4.17 keeps the successful V1.4.16 anti-pink/temporal foundation but changes the HDR ownership model in two focused ways: **LONG learns scene-body appearance adaptively**, and **SHORT is a masked highlight-recovery layer rather than a broad blend partner**.

## LONG is the master image

System/HAL AE is bootstrap-only. After startup, AUTO LONG is driven by matched-pair scene statistics rather than by highlight-protecting AE or a fixed office-derived brightness correction.

The controller measures the robust P25/P35/P50 scene body and uses P90/P50 plus P98/P50 histogram shape to estimate whether a meaningful bright tail exists. Broad windows/lights are therefore treated as SHORT's responsibility instead of forcing the whole LONG exposure darker. The target also adapts downward for genuinely high-demand low-light scenes, so the office correction is not a global +EV lock.

LONG changes require consecutive evidence and are limited to 0.18 EV per update. The user Brightness control remains -5.0..+2.0 EV and biases this learned LONG appearance target.

## SHORT is a masked highlight layer

The fused image begins as LONG. SHORT has no authority outside regions where LONG's highlight shoulder/clip genuinely needs recovery.

Inside that mask, SHORT is evaluated locally for:

- usable signal and unclipped highlight information;
- normalized radiance evidence beyond LONG;
- temporal luminance stability;
- temporal chroma stability;
- processed-RGB channel agreement;
- neutral-highlight color safety.

The live 32x24 reliability map stores luminance/detail trust separately from chroma trust. This means a stable window can recover even if a different ceiling lamp is unstable, and a SHORT frame can contribute highlight structure without being allowed to import pink/orange tint.

Neutral LONG highlights retain LONG chromaticity unless SHORT color is genuinely trustworthy. A locally unreliable SHORT region simply stays LONG/clean clipped.

Recovered highlights use a wider masked-only tone range so useful SHORT structure is visible rather than compressed almost entirely into near-white. LONG shadows/midtones are not globally tone-mapped by this recovery curve.

## Still capture

HDR Capture Set still freezes exactly one SHORT and one LONG exposure/ISO pair. Saved JPEG fusion uses the same LONG-base masked philosophy with separate brightness/detail versus color trust. Previous preview pairs are never accumulated into the still image.

## Preserved V1.4.16 protections

- exact exposure-generation SHORT/LONG matching;
- anti-pink/anti-pulsation temporal reliability;
- scalar SHORT-to-LONG overlap calibration;
- known 50/60-Hz and unknown/PWM safety policy;
- adaptive AUTO and MANUAL SAFE SHORT headroom;
- frozen still controls;
- FOV/cadence, orientation/DNG and explicit sRGB protections;
- stable GitHub Actions signing identity.

## Stable APK updates

V1.4.17 reuses the existing `IRIS_TEST_SIGNING_KEY_B64` repository secret. Do not regenerate the signing key.

Signing certificate SHA-256:

`531aeed9ead79d28c424ad8f71a459b4ced8aff37e95c11bd295083fbb25c4e8`

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
