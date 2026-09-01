# Iris HDR Viewfinder Test V1.4.16

V1.4.16 is a live two-exposure HDR stability/appearance correction built from the exact successful V1.4.15 GitHub Actions compiled candidate. The HDR image is still exactly **one current SHORT + one current LONG**; previous pairs are used only for controller/reliability hysteresis, never image stacking.

## LONG represents the scene

Clean HAL AE remains the bootstrap seed, but V1.4.16 no longer lets a highlight-protecting initial exposure permanently define a dark room as correct `0 EV` appearance.

The live 32x24 statistics now include the LONG median and p98 highlight tail. LONG targets the main scene body, while bright windows/lights are allowed to clip into SHORT ownership. A strong HDR tail raises the `0 EV` scene-body floor to `0.045` linear. LONG convergence is limited to `0.20 EV` per live update so scene adaptation remains smooth.

Brightness stays `-5.0 EV` to `+2.0 EV` in 0.1-EV steps and continues to bias LONG appearance. It does not directly widen the HDR bracket.

## SHORT is highlight insurance, not a second appearance image

SHORT adapts only to recover meaningful highlight loss from LONG. Bracket widening requires two consecutive stable clipping samples; release requires three, so one noisy threshold crossing cannot make successive fused pairs alternate.

Every live exposure configuration has an immutable generation ID. Only SHORT and LONG from the exact same generation can become a displayed pair, and statistics from an older generation are discarded after new settings are issued.

## No pink/white/pink pulsation

V1.4.16 treats SHORT reliability as part of HDR fusion:

- two consecutive 32x24 samples must show stable normalized SHORT highlight brightness/chroma before live SHORT gains highlight authority;
- if LONG stays stable while SHORT varies, the viewfinder falls back to LONG until SHORT stabilizes again;
- overlap calibration is one scalar exposure/luma correction, never independent R/G/B gains;
- if any SHORT channel is near the processed ceiling, SHORT is rejected for that highlight;
- neutral multi-channel LONG clips suppress weak/moderate SHORT ISP tint;
- highlight recovery is coherent RGB rather than independent per-channel fill-in.

The intended failure mode is therefore a **stable neutral white clip**, not a pink/orange/green/pulsating recovered highlight.

## Flicker/PWM safety

Known 50/60-Hz illumination keeps the full `10 ms` / `8.333333 ms` safe integration boundary instead of shortening SHORT simply to chase the last highlight stop. Unknown/PWM preserves the clean-AE LONG integration and uses ISO separation for SHORT before accepting residual clipping.

This is deliberately conservative: clean stable clipping is preferred to revealing illumination modulation on ceiling lights, white surfaces, reflections or other bright areas.

## Still capture

Pressing HDR Capture Set still freezes exactly one SHORT and one LONG exposure/ISO pair. Saved JPEG fusion uses the same LONG-first philosophy, scalar SHORT calibration, clipped-SHORT veto and neutral-highlight protection. RAW/DNG, JPEG orientation, FOV, cadence, sRGB and capture-session ownership are unchanged.

## Stable APK updates

V1.4.16 reuses the stable V1.4.15 GitHub Actions signing identity. Do not regenerate `IRIS_TEST_SIGNING_KEY_B64`.

Signing certificate SHA-256:

`531aeed9ead79d28c424ad8f71a459b4ced8aff37e95c11bd295083fbb25c4e8`

## Runtime logs

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
