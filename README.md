# Iris HDR Viewfinder Test V1.4.11 V2.2

V1.4.11 V2.2 is a fast AUTO-exposure transition correction built from the exact successful V1.4.11 V2.1 GitHub Actions compiled candidate. It preserves V1.4.11 V2.1 HDR rendering, fixed ~3 EV AUTO bracket, Brightness/Gamma controls, side-by-side identity, fixed-height status row, FOV/orientation, capture, DNG/JPEG and logging behavior.

## Fast dark ↔ bright AUTO settling

V2.1 physically updated HDR AUTO exposure only when the clean-AE remeter took over the preview about every five seconds. That is why moving from a dark scene to a bright scene, or bright to dark, could sit at the old exposure before beginning to settle.

V2.2 ports the validated V1.4.15 continuous-metering mechanism without importing V1.4.15's later HDR-fusion/bracket experiments:

- clean HAL AE is used only for the initial converged exposure/flicker anchor;
- after that bootstrap, the camera remains in the normal repeating SHORT/LONG HDR burst;
- `HdrGlView` samples the actual published LONG at 32x24 every 200 ms;
- valid LONG median changes are sent directly to `CameraController`;
- physical AUTO correction begins from the next valid sample instead of waiting five seconds;
- each applied correction is bounded to ±0.30 EV with 0.10 EV hysteresis and at least 180 ms between updates;
- stale statistics from an older exposure pair are rejected.

This gives rapid response without a new unvalidated multi-stop scene-cut jump.

## Preserved V2.1 ownership

- AUTO bracket remains the V1.4.11 fixed ~8x / 3 EV policy when flicker is NONE.
- Under 50/60-Hz or unknown/PWM lighting, V1.4.11's same-integration safety remains.
- Brightness remains `-4.0..+4.0 EV` in 0.1-EV steps and is presentation-only after fusion.
- Gamma remains `0.50..2.00` in 0.05 steps, `1.00` neutral, presentation-only and RGB-ratio preserving.
- Live exposure statistics are sampled before Brightness/Gamma, so those controls cannot silently change physical sensor exposure.
- Shutter press freezes the current SHORT/LONG exposure/ISO/post-RAW boost plus Brightness/Gamma; later live-stat updates affect only future captures.
- The V2.1 one-line fixed 20dp status row remains, so status changes cannot resize the viewfinder.
- Application ID remains `com.skyking0007.irishdrviewfinder.v1411v2` for side-by-side installation.

## Backup / branch

No new backup branch is required for V2.2. Exact successful V2.1 commit `6057b8e22dbe98a6f386d9026d6ab9dbec65884c` remains the rollback/runtime authority.

Target branch: `experiment-v1.4.11-v2-brightness-4ev`.
