# Iris HDR Viewfinder Test V1.4.14

V1.4.14 is a root-cause reset built from the exact successful V1.4.13 Actions compiled candidate. It intentionally restores the successful V1.4.7 HDR image algorithm byte-for-byte while keeping the later proven capture-freeze, anti-banding, Manual Safe, cadence/FOV, orientation, logging and build-verification mechanics.

## Exact V1.4.7 HDR IQ reset

The live `hdr_display.frag` and saved `JpegFusion.java` are byte-exact copies of the successful V1.4.7 Actions candidate.

That deliberately removes the V1.4.8-V1.4.13 experimental HDR/tone changes from the image algorithm, including adaptive aperture-driven bracket widening, post-fusion Brightness, global appearance/color compensation, V1.4.13 true-multi-channel clipping reconstruction and the edge-disagreement recovery path.

At **Brightness 0.0 EV**, the HDR pair is reconstructed using the V1.4.7 zero-Brightness capture relationship and then processed by the exact V1.4.7 live/saved HDR algorithm.

The known V1.4.7 red/orange highlight-color defect is **not claimed fixed in this build**. V1.4.14 is intentionally isolating overall exposure/brightness from HDR algorithm changes so that defect is not mixed with another broad color experiment.

## Brightness moves the complete HDR pair

**Brightness** remains `-5.0 EV` through `+2.0 EV` in `0.1 EV` steps and remains available in HDR AUTO and HDR MANUAL SAFE.

Unlike V1.4.12/V1.4.13, Brightness no longer holds SHORT fixed while moving LONG farther away. It applies one exposure-product gain to the entire baseline HDR set:

`requested gain = 2 ^ BrightnessEV`

SHORT is solved first. LONG then follows the **actually achieved SHORT gain**, so sensor/ISO/timing limits do not deliberately widen or narrow the baseline HDR separation.

For a representative 60-Hz baseline of approximately `SHORT 1/120 ISO50 / LONG 1/120 ISO245`:

- `+0.5 EV` remains near the same HDR separation at approximately `SHORT 1/120 ISO71 / LONG 1/120 ISO348` because the next longer anti-banding period would overshoot SHORT minimum-ISO headroom.
- `+1.0 EV` can move both members to approximately `1/60` at their baseline ISOs, gaining real integration time while preserving the same HDR separation.

Known 50 Hz uses the existing 10 ms period family; known 60 Hz uses the existing 8.333333 ms family. Unknown/PWM retains baseline integration rather than inventing a banding-prone shutter. Forced 60 fps still caps live integration at 16.666666 ms. If a requested negative EV cannot be achieved without violating the anti-banding/minimum-ISO contract, the achieved shift may be limited rather than breaking the guard.

## Periodic viewfinder bounce correction

The 5-second clean-AE remeter cadence itself is unchanged. V1.4.13's visible vertical jump was caused by the changing debug/status message resizing the bottom `wrap_content` panel and therefore resizing the weighted viewfinder.

V1.4.14 gives the status/debug row invariant one-line 20dp geometry with ellipsis. The remeter message can change text without changing control-panel or viewfinder height.

## Compact navigation-safe controls retained

The compact control sizing and Android bottom navigation/gesture inset from V1.4.13 are retained. Brightness remains usable above the system gesture pill in portrait and remains available in landscape.

## Preserved mechanics

The exact successful V1.4.13 immutable shutter-time pair, hidden AE remetering cadence, 50/60-Hz guards, post-RAW boost ownership, producer-owned orientation, FOV-safe 30 fps path, explicit cropped 60 fps path, RAW/JPEG capture set, DNG/JPEG saving, logging, deterministic full-index patch proof, pinned real GLSL/Java compiler gates and full Android assemble order are inherited unchanged except for V1.4.14 authority/version/hash/regression pins.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
