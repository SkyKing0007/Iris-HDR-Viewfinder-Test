# Iris HDR Viewfinder Test V1.5.3 V1.2

V1.5.3 V1.2 is an **infrastructure-only lineage correction on top of the V1.5.3 V1.1 compiler-only correction**. No runtime bytes differ from V1.1. It is still reconstructed and verified against the exact successful V1.5.2 GitHub Actions candidate; failed V1.5.3 commit `33e93e528a69750da79f5847cb033d810f2d7251` is evidence only, never runtime authority.

The failed Actions run `33690475046` passed authority reconstruction, Java/Android/Gradle/signing setup and the static shader scan, then failed the real pinned GLSL compile because `hdr_display.frag` used `longSecond` inside `fusionSample()` without a declaration in that function scope. `longPeak` and `longLuma` used by the same expression were also not locally declared.

V1.5.3 V1.1 corrects only that runtime defect:

- `float longPeak = max3(longRgb);`
- `float longSecond = second3(longRgb);`
- `float longLuma = longSceneLuma;`

These values are mathematically identical to the descriptors already used by the existing helper functions. **No V1.5.3 image-processing math is retuned or reordered.** The unconditional clipped-LONG → SHORT authority, quad coherence, preserved SHORT color, 8-EV global no-LTM tone map, V1.5.1 broad-pink protection, V1.5.2 anti-peach demosaic and stable live calibration are unchanged.

Permanent regression 129 now asserts that all three descriptors are locally declared inside `fusionSample()` before `hardClippedCore` uses them, preserving the exact real compiler failure as a future guard.

## Authority

Successful runtime + verification authority remains V1.5.2:

- commit `2ed7072212bc1e9571163a914be6497c6254b702`
- Actions run `33682632400`
- artifact `9866894122`

Failed V1.5.3 evidence:

- commit `33e93e528a69750da79f5847cb033d810f2d7251`
- Actions run `33690475046`
- job `100447831412`
- failure: real GLSL compiler stage only

Version remains `1.0-v1.5.3 / 32`; the failed candidate never became a successful runtime authority.

The packaged Actions workflow preserves the successful V1.5.2 procedure: same 16-stage authority reconstruction, Java 17, Android 37, Gradle 9.6.0, stable signing, pinned `glslang-tools=15.1.0-2~ubuntu0.24.04.2`, exact runtime shader compilation, real Java compilation, deterministic patch/PRE-BUILD proof, full `:app:assembleDebug`, post-build invariance and final candidate export.

Current status: **PREPARED / UPLOAD-READY, NOT BUILD-PROVEN** until Actions passes.


## V1.5.3 V1.2 exact-lineage correction

Actions run `33691567856` for V1.5.3 V1.1 stopped before compilers because the authority guard still required `HEAD^` to equal successful V1.5.2. V1.2 keeps successful V1.5.2 as sole runtime authority, uses the minimal `fetch-depth: 4`, and proves the exact chain `V1.5.2 -> failed V1.5.3 -> failed V1.5.3 V1.1 -> V1.2`. Candidate reconstruction remains seeded from the exact V1.5.2 Actions artifact.
