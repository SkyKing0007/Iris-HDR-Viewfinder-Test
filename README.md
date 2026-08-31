# Iris HDR Viewfinder Test V1.4.8

V1.4.8 is built directly on the build-proven V1.4.7 candidate. V1.4.7 remains the golden image-quality baseline: its SHORT/LONG fusion, recovered highlight structure, LONG-owned clean shadows, noise behavior, color path, FOV/cadence policy and still-capture topology are preserved.

## Adaptive brightness parity

The V1.4.7 HDR reconstruction completes first. V1.4.8 then applies one monotonic, hue-preserving pointwise appearance lift to the reconstructed linear RGB result before the existing sRGB output transform.

- Darker lower/mid tones receive the strongest lift, targeting the stock-camera-like brightness gap seen in the darker office sample.
- Already-brighter lower midtones receive a smaller lift, preventing a generally well-exposed scene from being pushed too far.
- Deep shadows move only slightly so the V1.4.7 clean/noise-controlled shadow appearance is retained.
- Recovered highlights rapidly converge back to the V1.4.7 output, so bright-window and ceiling-light highlight structure is not traded for overall brightness.
- Live HDR FUSED and saved FUSED JPEG use equivalent appearance math.

The mapping is scene-value driven rather than tuned to a specific phone model and adds no extra GPU pass, texture, framebuffer, neighborhood sampling or full-frame CPU float buffer.

## Source-supported fused color

The FUSED luminance/HDR result remains authoritative, but its final encoded chroma is now constrained by the actual SHORT/LONG source colors. This is a universal signal-domain rule, not a semantic skin/white detector.

- SHORT is the exposure-safe color reference when it has usable signal; LONG remains the fallback where SHORT approaches its noise floor.
- Strong source-supported saturation passes unchanged.
- Only low-amplitude SHORT chroma is attenuated as display gain rises, preventing dark-frame chroma noise from becoming orange/pink/green specks after HDR brightening.
- FUSED encoded chroma may never exceed the stronger chroma present in SHORT or LONG, and gamut fitting scales chroma around fixed luma rather than clipping RGB channels independently.
- No extra texture samples, GPU passes, neighborhood filter, device IDs, semantic classifier, or universal sensor color matrix are added.

## Adaptive AUTO SHORT highlight headroom

A second real-device condition showed a compact bright ceiling lamp already clipped in the SHORT input on a wide-aperture camera. V1.4.8 therefore changes only AUTO SHORT headroom policy while keeping LONG as the clean absolute exposure anchor.

- The base AUTO target remains 3 EV.
- Measured `LENS_APERTURE` is used when available; `LENS_INFO_AVAILABLE_APERTURES` is the fallback.
- Wider-than-f/2 optics gain physically-derived SHORT headroom from the `1/N²` light-gathering relationship, capped at 4.25 EV.
- f/2 and slower optics retain the proven 3-EV baseline.
- Under known 50/60-Hz lighting, only a material wide-aperture headroom need may shorten SHORT to a closer power-of-two submultiple of the flicker period. Ordinary apertures keep the existing same-integration behavior.
- Unknown/PWM lighting retains the conservative V1.4.7 same-integration policy rather than guessing at a banding-prone cadence.
- All shutter/ISO values remain clamped to the selected camera's actual Camera2 ranges.

No device IDs, vendor models, focal-length tables or SoC/GPU classes participate in this policy.

## Preserved V1.4.7 camera/HDR pipeline

The V1.4.7 full-RGB near-clipping SHORT handoff, exposure-ratio normalization, bracket-aware hue-preserving HDR compression, explicit Camera2 sRGB curve, `NOISE_REDUCTION_MODE_OFF`, `EDGE_MODE_OFF`, FOV SAFE fixed-30 mode, optional explicit cropped 60 mode, isolated AUTO meter phase, full RAW/JPEG still session, orientation contract and runtime logger remain unchanged except for the bounded AUTO SHORT derivation described above.

Runtime logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`
