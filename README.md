# Iris HDR Viewfinder Test V1.4.11 V2.42

V2.42 is a narrow RAW highlight-color correction on the exact successful V2.41 R2 compiled candidate: commit `866665c5add2af78c75b8d159f28e2fca23dd0d3`, tree `552cd9ee43ce1c2fafaa12140798054fd2092e1c`, Actions run `34557568126`, artifact `10183167176`.

The device/DNG regression is square green/cyan/magenta color contamination around saturated chandelier/light housings. Strict SHORT+LONG DNG fusion replay isolated the sufficient correction to `raw_reconstruct.frag`: the existing per-channel `sensorValid` result becomes authoritative. Invalid/censored R/B opponent information falls back only to reconstructed green in calculation-WB coordinates; valid channels and the green/luminance guide remain unchanged.

The V2.35/V2.36 broad whole-RGB neutral fallback (`smoothCensoredFractionAt` / `neutralFallbackBalanced` / `neutralMix`) is removed so a saturated Bayer neighborhood cannot repaint all RGB into a visible grey/yellow border. Existing common-quad opponent rejection and the V2.38 `raw_chroma_dealias.frag` saturation-transition cleanup remain protected unchanged.

Runtime change is exactly one file: `app/src/main/assets/shaders/raw_reconstruct.frag`. V2.41 R2 fusion topology, motion rejection, registration, appearance-gain normalization, exposure acquisition, presentation, DNG, NAFNet, Java owners and all other runtime files are protected byte-for-byte.

Before GitHub Actions succeeds, this package is prepared/upload-ready only. GitHub Actions remains authoritative for the pinned real GLSL compiler, real project Java compiler and full `:app:assembleDebug`.
