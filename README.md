# Iris HDR Viewfinder Test V1.4.11 V2.1

V1.4.11 V2.1 is a narrow correction built from the exact successful V1.4.11 V2 Actions compiled candidate. It preserves V1.4.11 capture, fixed ~3 EV HDR bracket, SHORT/LONG fusion, LONG-first highlight color ownership, FOV/orientation, cadence, DNG/JPEG and logging behavior.

## Brightness EV

**Brightness** spans `-4.0 EV` to `+4.0 EV` in `0.1 EV` steps. `0.0 EV` is neutral. SHORT/LONG fusion completes first; Brightness is then a scene-linear gain before the existing V1.4.7 HDR highlight display fit. It does not change physical SHORT/LONG exposure, bracket width, highlight admission or fusion weights.

## Gamma

**Gamma** spans `0.50` to `2.00` in `0.05` steps with `1.00` neutral. Values above 1.00 brighten midtones; values below 1.00 darken midtones. Gamma is presentation-only after HDR exposure/tone fitting. It remaps luminance and applies one common RGB scale, backing off at gamut limits rather than clipping channels independently. The exact Gamma value is frozen at shutter and shared by live HDR and the saved FUSED JPEG.

## Screen fit and Android system bars

The control panel explicitly reserves Android system-bar and gesture-pill insets. Controls remain above the bottom navigation/gesture area. The live viewfinder remains FIT/aspect-ratio preserving and is the flexible-height region that shrinks before controls are allowed to overlap system UI. Portrait uses separate Brightness and Gamma rows; landscape shares one compact tone row.

## Side-by-side isolation

The V2 APK uses application ID `com.skyking0007.irishdrviewfinder.v1411v2` and label `Iris HDR 1.4.11 V2`, allowing installation alongside the normal Iris HDR Viewfinder. Its Actions push trigger is isolated to branch `experiment-v1.4.11-v2-brightness-4ev`; `main` is not a trigger.

Runtime logs remain under `Downloads/IrisHDRViewfinder/Logs/`.

## Periodic viewfinder bounce correction

V2.1 preserves the existing 5-second clean-AE remeter cadence and camera/focus behavior. The visible periodic zoom/bounce was the weighted viewfinder being resized when the changing status/remeter message changed the `wrap_content` panel height. The status/debug row is now invariant one-line 20dp geometry with ellipsis, matching the later validated V1.4.14 correction. Metering text can change without changing viewfinder size.
