package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CaptureSetSaver {
    static final String CAPTURE_SHORT = "SHORT";
    static final String CAPTURE_LONG = "LONG";

    interface Listener {
        void onInputsAcquired(String captureId);
        void onFinished(String captureId, boolean success, String message);
    }

    private static final class CaptureData {
        TotalCaptureResult result;
        RawHdrFusion.RawBuffer rawBuffer;
        byte[] jpegBytes;
        boolean rawSubmitted;
        boolean jpegSubmitted;
        boolean rawSaved;
        boolean jpegSaved;
    }

    private final Context context;
    private final CameraCharacteristics characteristics;
    private final String cameraId;
    private final String captureId;
    private final int dngOrientation;
    private final int captureOrientationDegrees;
    private final Size jpegOutputSize;
    private final float displayBrightnessEv;
    private final String expectedPhysicalId;
    private final Rect viewfinderSensorCrop;
    private final Listener listener;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService fusion = Executors.newSingleThreadExecutor();
    private final Map<Long, String> labelByTimestamp = new HashMap<>();
    private final Map<Long, RawHdrFusion.RawBuffer> pendingRaw = new HashMap<>();
    private final Map<Long, byte[]> pendingJpeg = new HashMap<>();
    private final CaptureData shortData = new CaptureData();
    private final CaptureData longData = new CaptureData();

    private boolean fusionSubmitted;
    private boolean fusionSaved;
    private boolean metadataSubmitted;
    private boolean metadataSaved;
    private boolean inputsAcquiredNotified;
    private boolean terminal;

    CaptureSetSaver(
            Context context,
            CameraCharacteristics characteristics,
            String cameraId,
            String captureId,
            int captureOrientationDegrees,
            Size jpegOutputSize,
            float displayBrightnessEv,
            String expectedPhysicalId,
            Rect viewfinderSensorCrop,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.characteristics = characteristics;
        this.cameraId = cameraId;
        this.captureId = captureId;
        this.captureOrientationDegrees = ((captureOrientationDegrees % 360) + 360) % 360;
        this.jpegOutputSize = jpegOutputSize;
        this.dngOrientation = dngOrientationForDegrees(captureOrientationDegrees);
        this.displayBrightnessEv = Math.max(-16.0f, Math.min(1.0f, displayBrightnessEv));
        this.expectedPhysicalId = expectedPhysicalId;
        this.viewfinderSensorCrop = viewfinderSensorCrop == null
                ? null : new Rect(viewfinderSensorCrop);
        this.listener = listener;
    }

    synchronized void onResult(String label, TotalCaptureResult result) {
        if (terminal) return;
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp == null) {
            failLocked(new IllegalStateException("CaptureResult missing SENSOR_TIMESTAMP"));
            return;
        }
        CaptureData data = dataFor(label);
        if (data == null) {
            failLocked(new IllegalArgumentException("Unknown capture label " + label));
            return;
        }
        data.result = result;
        labelByTimestamp.put(timestamp, label);
        matchLocked(timestamp);
        maybeSubmitFusionLocked();
        maybeSubmitMetadataLocked();
    }

    synchronized void abort(String reason) {
        failLocked(new IllegalStateException(reason));
    }

    synchronized void onRawImage(Image image) {
        if (terminal) {
            image.close();
            return;
        }
        long timestamp = image.getTimestamp();
        try {
            // Detach physical RAW bytes immediately from ImageReader ownership. DNG I/O
            // and HDR processing then share immutable bytes without holding Camera2 Image.
            RawHdrFusion.RawBuffer raw = RawHdrFusion.copyRaw(image);
            pendingRaw.put(timestamp, raw);
            matchLocked(timestamp);
        } catch (Throwable t) {
            failLocked(t);
        } finally {
            image.close();
        }
    }

    synchronized void onJpegImage(Image image) {
        if (terminal) {
            image.close();
            return;
        }
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer().duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            pendingJpeg.put(image.getTimestamp(), bytes);
            matchLocked(image.getTimestamp());
        } catch (Throwable t) {
            failLocked(t);
        } finally {
            image.close();
        }
    }

    private void matchLocked(long timestamp) {
        String label = labelByTimestamp.get(timestamp);
        if (label == null) return;
        CaptureData data = dataFor(label);
        if (data == null || data.result == null) return;

        RawHdrFusion.RawBuffer raw = pendingRaw.remove(timestamp);
        if (raw != null && !data.rawSubmitted) {
            data.rawSubmitted = true;
            data.rawBuffer = raw;
            submitRaw(label, data, raw);
        }

        byte[] jpeg = pendingJpeg.remove(timestamp);
        if (jpeg != null && !data.jpegSubmitted) {
            data.jpegSubmitted = true;
            data.jpegBytes = jpeg;
            submitJpeg(label, data, jpeg);
        }
        maybeSubmitFusionLocked();
        maybeSubmitMetadataLocked();
        maybeNotifyInputsAcquiredLocked();
    }

    private void maybeNotifyInputsAcquiredLocked() {
        if (terminal || inputsAcquiredNotified) return;
        boolean acquired = shortData.rawSubmitted
                && shortData.jpegSubmitted
                && longData.rawSubmitted
                && longData.jpegSubmitted;
        if (acquired) {
            inputsAcquiredNotified = true;
            listener.onInputsAcquired(captureId);
        }
    }

    private void submitRaw(String label, CaptureData data, RawHdrFusion.RawBuffer raw) {
        TotalCaptureResult result = data.result;
        io.execute(() -> {
            long startedNs = System.nanoTime();
            try (DngCreator creator = new DngCreator(characteristics, result)) {
                creator.setOrientation(dngOrientation);
                String name = captureId + "_" + label + ".dng";
                MediaStoreWriter.write(
                        context,
                        name,
                        "image/x-adobe-dng",
                        output -> creator.writeByteBuffer(
                                output,
                                new Size(raw.width, raw.height),
                                ByteBuffer.wrap(raw.packed16),
                                0L));
                RuntimeLogger.event(
                        "DNG_SAVE",
                        label + " copiedRaw=true ms=" + elapsedMs(startedNs));
                synchronized (CaptureSetSaver.this) {
                    data.rawSaved = true;
                    checkCompleteLocked();
                }
            } catch (Throwable t) {
                synchronized (CaptureSetSaver.this) {
                    failLocked(t);
                }
            }
        });
    }

    private void submitJpeg(String label, CaptureData data, byte[] jpeg) {
        io.execute(() -> {
            long startedNs = System.nanoTime();
            try {
                MediaStoreWriter.writeBytes(
                        context, captureId + "_" + label + ".jpg", "image/jpeg", jpeg);
                RuntimeLogger.event(
                        "SOURCE_JPEG_SAVE",
                        label + " referenceOnly=true ms=" + elapsedMs(startedNs));
                synchronized (CaptureSetSaver.this) {
                    data.jpegSaved = true;
                    checkCompleteLocked();
                }
            } catch (Throwable t) {
                synchronized (CaptureSetSaver.this) {
                    failLocked(t);
                }
            }
        });
    }

    private void maybeSubmitFusionLocked() {
        if (terminal || fusionSubmitted) return;
        if (shortData.rawBuffer == null || longData.rawBuffer == null
                || shortData.result == null || longData.result == null) {
            return;
        }
        fusionSubmitted = true;
        RawHdrFusion.RawBuffer shortRaw = shortData.rawBuffer;
        RawHdrFusion.RawBuffer longRaw = longData.rawBuffer;
        TotalCaptureResult shortResult = shortData.result;
        TotalCaptureResult longResult = longData.result;
        fusion.execute(() -> {
            long startedNs = System.nanoTime();
            try {
                // V1.5.0 sole FUSED_HDR authority. HAL JPEGs are reference outputs only;
                // there is deliberately no JpegFusion or single-exposure fallback.
                byte[] fused = RawHdrFusion.fuse(
                        context,
                        shortRaw,
                        longRaw,
                        shortResult,
                        longResult,
                        characteristics,
                        jpegOutputSize,
                        captureOrientationDegrees,
                        expectedPhysicalId,
                        viewfinderSensorCrop);
                long writeStartedNs = System.nanoTime();
                MediaStoreWriter.writeBytes(
                        context,
                        captureId + "_FUSED_HDR.jpg",
                        "image/jpeg",
                        fused);
                RuntimeLogger.event(
                        "FUSION_WRITE",
                        "authority=RAW_SENSOR bytes=" + fused.length
                                + " ms=" + elapsedMs(writeStartedNs));
                RuntimeLogger.event(
                        "FUSION_PIPELINE",
                        "authority=RAW_SENSOR ms=" + elapsedMs(startedNs));
                synchronized (CaptureSetSaver.this) {
                    fusionSaved = true;
                    checkCompleteLocked();
                }
            } catch (Throwable t) {
                synchronized (CaptureSetSaver.this) {
                    failLocked(t);
                }
            }
        });
    }

    private void maybeSubmitMetadataLocked() {
        if (terminal || metadataSubmitted) return;
        if (shortData.result == null || longData.result == null) return;
        metadataSubmitted = true;
        TotalCaptureResult shortResult = shortData.result;
        TotalCaptureResult longResult = longData.result;
        io.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                root.put("captureId", captureId);
                root.put("cameraId", cameraId);
                root.put(
                        "fusion",
                        "V1.5.0 matched RAW_SENSOR CFA-aware registered HDR + shared GTM");
                root.put("fusionAuthority", "MATCHED_RAW_SENSOR_PAIR");
                root.put("short", resultJson(shortResult));
                root.put("long", resultJson(longResult));
                root.put("longToShortRawExposureRatio", rawExposureRatio(shortResult, longResult));
                root.put("brightnessEv", displayBrightnessEv);
                root.put("brightnessOwner", "LONG_APPEARANCE_SHORT_HIGHLIGHT_EVIDENCE");
                root.put("localToneMapping", false);
                root.put("jpegFusionFallback", false);
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (sensorOrientation != null) root.put("sensorOrientation", sensorOrientation);
                JSONArray physicalIds = new JSONArray();
                for (String id : characteristics.getPhysicalCameraIds()) physicalIds.put(id);
                root.put("physicalCameraIds", physicalIds);
                root.put(
                        "notes",
                        "SHORT/LONG DNG and JPEG files remain original source references. "
                                + "FUSED_HDR.jpg is generated only from the matched RAW_SENSOR pair. "
                                + "Post-RAW sensitivity boost is excluded from RAW radiometric normalization.");
                byte[] json = root.toString(2).getBytes(StandardCharsets.UTF_8);
                MediaStoreWriter.writeBytes(
                        context,
                        captureId + "_metadata.json",
                        "application/json",
                        json);
                synchronized (CaptureSetSaver.this) {
                    metadataSaved = true;
                    checkCompleteLocked();
                }
            } catch (Throwable t) {
                synchronized (CaptureSetSaver.this) {
                    failLocked(t);
                }
            }
        });
    }

    private static JSONObject resultJson(TotalCaptureResult result) throws Exception {
        JSONObject json = new JSONObject();
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        Integer postRawBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        Integer dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        float[] dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        String physical = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        json.put("frameNumber", result.getFrameNumber());
        json.put("sensorTimestampNs", timestamp == null ? JSONObject.NULL : timestamp);
        json.put("exposureTimeNs", exposure == null ? JSONObject.NULL : exposure);
        json.put("iso", iso == null ? JSONObject.NULL : iso);
        json.put("frameDurationNs", frameDuration == null ? JSONObject.NULL : frameDuration);
        json.put("postRawSensitivityBoost", postRawBoost == null ? JSONObject.NULL : postRawBoost);
        json.put("dynamicWhiteLevel", dynamicWhite == null ? JSONObject.NULL : dynamicWhite);
        json.put("activePhysicalId", physical == null ? JSONObject.NULL : physical);
        if (dynamicBlack != null) {
            JSONArray black = new JSONArray();
            for (float value : dynamicBlack) black.put(value);
            json.put("dynamicBlackLevel", black);
        }
        return json;
    }

    private static double rawExposureRatio(
            TotalCaptureResult shortResult, TotalCaptureResult longResult) {
        Long se = shortResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer si = shortResult.get(CaptureResult.SENSOR_SENSITIVITY);
        Long le = longResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer li = longResult.get(CaptureResult.SENSOR_SENSITIVITY);
        double shortProduct = Math.max(1.0,
                (se == null ? 1.0 : se) * (si == null ? 1.0 : si));
        double longProduct = Math.max(1.0,
                (le == null ? 1.0 : le) * (li == null ? 1.0 : li));
        return Math.max(1.0, Math.min(65_536.0, longProduct / shortProduct));
    }

    private static long elapsedMs(long startNs) {
        return Math.round((System.nanoTime() - startNs) / 1_000_000.0);
    }

    private static int dngOrientationForDegrees(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) return ExifInterface.ORIENTATION_NORMAL;
        if (normalized == 90) return ExifInterface.ORIENTATION_ROTATE_90;
        if (normalized == 180) return ExifInterface.ORIENTATION_ROTATE_180;
        if (normalized == 270) return ExifInterface.ORIENTATION_ROTATE_270;
        throw new IllegalArgumentException(
                "DNG orientation must be a multiple of 90 degrees: " + degrees);
    }

    private CaptureData dataFor(String label) {
        if (CAPTURE_SHORT.equals(label)) return shortData;
        if (CAPTURE_LONG.equals(label)) return longData;
        return null;
    }

    private void checkCompleteLocked() {
        if (terminal) return;
        boolean done = shortData.rawSaved
                && shortData.jpegSaved
                && longData.rawSaved
                && longData.jpegSaved
                && fusionSaved
                && metadataSaved;
        if (done) {
            terminal = true;
            io.shutdown();
            fusion.shutdown();
            listener.onFinished(
                    captureId,
                    true,
                    "Saved original SHORT/LONG DNG + JPEG, RAW-fused HDR JPEG, and metadata to Downloads/IrisHDRViewfinder");
        }
    }

    private void failLocked(Throwable t) {
        if (terminal) return;
        terminal = true;
        pendingRaw.clear();
        pendingJpeg.clear();
        io.shutdown();
        fusion.shutdown();
        listener.onFinished(captureId, false, t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
