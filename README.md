# Iris HDR Viewfinder Test V1.4.4

Standalone Camera2 HDR viewfinder/capture experiment.

## What V1.4.4 changes

V1.4.3 proved the live AUTO architecture can sustain about 60 camera fps and about 30 complete HDR pairs/s on the Xiaomi 15 Ultra. V1.4.4 preserves that path, but keeps 60 fps only when Camera2 can prove that the active physical-sensor readout is full FOV. API-35 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION` is the decisive evidence; `SCALER_CROP_REGION` alone is not treated as proof because high-FPS sensor modes may crop before the normal scaler stage. If 60-fps FOV parity is cropped or cannot be proven, Iris recreates the preview at 30 fps. RAW/JPEG stills are never cropped merely to imitate a cropped high-FPS preview.

MANUAL becomes **MANUAL SAFE**. The sliders still define the requested short/long bracket and ISO, but under detected 50/60-Hz or unknown/PWM lighting the effective pair uses flicker-compatible integration timing and gain separation where possible. This prevents the rejected mixed `1/480 + 1/60` temporal schedule from sampling different LED phases while preserving the requested exposure-product separation as sensor limits allow. AUTO HDR remains the existing uninterrupted two-request `SHORT manual -> LONG AE` repeating burst.

## Runtime logger

V1.4.4 adds a low-duty production logger for device-specific freezes, black preview, Camera2 session errors, FOV decisions, flicker decisions, capture failures, GPU failures and uncaught crashes. It is deliberately rate-limited and does not log every frame.

Logs are written to:

`Downloads/IrisHDRViewfinder/Logs/IrisHDR_Runtime_YYYYMMDD_HHMMSS_mmm.txt`

A tester can send that `.txt` file directly. The log records device/build identity, camera/session configuration, periodic camera/GL health, active crop/physical-camera evidence, capture lifecycle and crash stacks.

## Preserved V1.4.3 behavior

- producer-owned live orientation and portrait-correct presentation;
- explicit valid DNG TIFF orientation without rotating Bayer bytes;
- uninterrupted AUTO HDR cadence;
- atomic complete SHORT/LONG publication;
- native-aspect FIT presentation;
- existing sRGB/HDR shader and JPEG fusion math;
- full-resolution RAW/JPEG capture path.

All four GLSL assets, `CaptureSetSaver.java`, `FrameMeta.java`, `JpegFusion.java`, `MediaStoreWriter.java`, and the manifest remain byte-identical to successful V1.4.3.
