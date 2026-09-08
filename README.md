# Iris HDR Viewfinder Test V1.4.11 V2.30

V2.30 continues directly from the exact successful V2.29 compiled candidate: commit `ddcefd30a4f203f83c6b67d131f63b1533cc4d66`, Actions run `34183494359`, artifact `10039754784`.

## What V2.30 changes

V2.29 still generated saved HDR from the matched SHORT/LONG **HAL JPEG** pair while simultaneously saving RAW/DNG only as diagnostic sensor references. V2.30 changes the saved-still fusion source authority to the actual timestamp-matched **SHORT/LONG RAW_SENSOR mosaics and RAW metadata**.

The production saved path is now:

`SHORT RAW_SENSOR + LONG RAW_SENSOR -> RAW normalization/reconstruction -> RAW-derived SHORT-to-LONG registration -> inherited V2.29 source selection/HDR tone -> FUSED_HDR.jpg`

HAL JPEGs may still be saved as independent references, but they are not fusion inputs.

### RAW lifetime and metadata ownership

- `CaptureSetSaver` copies each timestamp-matched RAW sensor plane before DNG writing is allowed to close the `Image`.
- Each copied RAW observation retains its own sensor timestamp, dimensions/CFA arrangement, dynamic black/white information and physical exposure metadata.
- Fusion exposure ratio is based on `SENSOR_EXPOSURE_TIME * SENSOR_SENSITIVITY`; post-RAW JPEG sensitivity boost is deliberately excluded.
- Per-frame black/white levels remain local to each RAW observation.
- The matched LONG white-balance gains and sensor-to-linear-sRGB transform are used as the common color owner for both reconstructed observations, preventing a source boundary from acquiring a different color transform.
- `ColorSpaceTransform.getElement(column,row)` ordering is explicitly preserved.
- Both still requests explicitly request the Camera2 lens-shading correction map. The matched `[R, G-even, G-odd, B]` map travels with each RAW and is applied in Bayer space before WB/demosaic.

### RAW reconstruction and registration

A new active `raw_reconstruct.frag` performs the sensor-domain handoff needed by saved fusion: black subtraction, white normalization, CFA reconstruction, common LONG-owned WB/color transform, and an RGB carrier for the existing saved-still HDR pipeline.

Registration is estimated from those RAW-derived reconstructions. The successful V2.29 `JpegFusion.java` registration mathematics remain byte-identical; only their input evidence changes from decoded HAL JPEGs to RAW-derived proxies.

LONG remains immutable output geometry. SHORT remains the only aligned auxiliary, with global and bounded local displacement applied only when SHORT is sampled.

## Protected V2.29 architecture

V2.30 does not reopen capture exposure policy, live preview ownership, NAFNet, DNG publication, JpegFusion registration math, or the successful V2.29 connected-region/full-resolution source-selection and HDR tone topology. CameraController changes only by the two explicit still-request lens-shading metadata requests; the verifier removes those additions and requires the remaining successful V2.29 still-burst bytes to match exactly.

The V2.30 runtime change is exactly six files:

- `app/src/main/assets/shaders/hdr_display.frag`
- `app/src/main/assets/shaders/raw_reconstruct.frag` **(new)**
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java`
- `app/src/main/java/com/skyking0007/irishdrviewfinder/RawFusion.java` **(new)**

The runtime universe is exactly 18 `app/src` files with manifest digest:

`9e09b637209eba35e9d45a9ea0f6406ac8ae6e841d45200d795f04e6fb4e6d40`

## Verification status

The successful V2.29 procedure remains the verification-mechanics authority. Its 15-step ordering is preserved. The new active RAW reconstruction shader is added to the same pinned real GLSL compiler gate before the real Java compiler and full `:app:assembleDebug`.

Current local replay:

- complete reserved-identifier scan: **PASS** on all 5 active shaders;
- V2.30 RAW/JPEG ownership, radiometry, color-domain, geometry and inherited-regression suite: **PASS**;
- deterministic full-index forward/rollback proof at `core.abbrev` 7/12/40 with fuzz=0 replay: **PASS**;
- real pinned GLSL compile: **NOT RUN locally**;
- real project Java compile: **NOT RUN locally**;
- full Android assemble: **NOT RUN locally**.

V2.30 is not build-proven until its own GitHub Actions run succeeds.
