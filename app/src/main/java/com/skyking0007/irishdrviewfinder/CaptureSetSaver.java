package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;

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
    private final float displayBrightnessEv;
    private final byte[] shortReliabilityMap;
    private final Listener listener;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService fusion = Executors.newSingleThreadExecutor();
    private final Map<Long, String> labelByTimestamp = new HashMap<>();
    private final Map<Long, Image> pendingRaw = new HashMap<>();
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
            float displayBrightnessEv,
            byte[] shortReliabilityMap,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.characteristics = characteristics;
        this.cameraId = cameraId;
        this.captureId = captureId;
        this.dngOrientation = dngOrientationForDegrees(captureOrientationDegrees);
        this.displayBrightnessEv = Math.max(-5.0f, Math.min(2.0f, displayBrightnessEv));
        this.shortReliabilityMap = shortReliabilityMap == null
                ? null : shortReliabilityMap.clone();
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
        Image old = pendingRaw.put(timestamp, image);
        if (old != null) old.close();
        matchLocked(timestamp);
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

        Image raw = pendingRaw.remove(timestamp);
        if (raw != null && !data.rawSubmitted) {
            data.rawSubmitted = true;
            submitRaw(label, data, raw);
        } else if (raw != null) {
            raw.close();
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

    private void submitRaw(String label, CaptureData data, Image raw) {
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
                        output -> creator.writeImage(output, raw));
                RuntimeLogger.event(
                        "DNG_SAVE",
                        label + " ms=" + elapsedMs(startedNs));
                synchronized (CaptureSetSaver.this) {
                    data.rawSaved = true;
                    checkCompleteLocked();
                }
            } catch (Throwable t) {
                synchronized (CaptureSetSaver.this) {
                    failLocked(t);
                }
            } finally {
                raw.close();
            }
        });
    }

    private void submitJpeg(String label, CaptureData data, byte[] jpeg) {
        io.execute(() -> {
            long startedNs = System.nanoTime();
            try {
                MediaStoreWriter.writeBytes(context, captureId + "_" + label + ".jpg", "image/jpeg", jpeg);
                RuntimeLogger.event(
                        "SOURCE_JPEG_SAVE",
                        label + " ms=" + elapsedMs(startedNs));
                synchronized (CaptureSetSaver.this) {
                    data.jpegSaved = true;
                    maybeSubmitFusionLocked();
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
        if (shortData.jpegBytes == null || longData.jpegBytes == null
                || shortData.result == null || longData.result == null) {
            return;
        }
        fusionSubmitted = true;
        byte[] shortJpeg = shortData.jpegBytes;
        byte[] longJpeg = longData.jpegBytes;
        double ratio = exposureRatio(shortData.result, longData.result);
        fusion.execute(() -> {
            long startedNs = System.nanoTime();
            try {
                byte[] fused = JpegFusion.fuse(
                        context, shortJpeg, longJpeg, ratio, shortReliabilityMap);
                long writeStartedNs = System.nanoTime();
                MediaStoreWriter.writeBytes(
                        context,
                        captureId + "_FUSED_HDR.jpg",
                        "image/jpeg",
                        fused);
                RuntimeLogger.event(
                        "FUSION_WRITE",
                        "bytes=" + fused.length + " ms=" + elapsedMs(writeStartedNs));
                RuntimeLogger.event(
                        "FUSION_PIPELINE",
                        "ms=" + elapsedMs(startedNs));
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
                root.put("fusion", "V1.4.20 GPU-tiled shared-shader scene-learned edge-safe HDR fusion");
                root.put("short", resultJson(shortResult));
                root.put("long", resultJson(longResult));
                root.put("longToShortExposureProductRatio", exposureRatio(shortResult, longResult));
                root.put("brightnessEv", displayBrightnessEv);
                root.put("brightnessOwner", "LONG_APPEARANCE_SHORT_ADAPTIVE");
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (sensorOrientation != null) root.put("sensorOrientation", sensorOrientation);
                JSONArray physicalIds = new JSONArray();
                for (String id : characteristics.getPhysicalCameraIds()) physicalIds.put(id);
                root.put("physicalCameraIds", physicalIds);
                root.put("notes", "RAW/DNG files are diagnostic sensor references. FUSED_HDR.jpg is generated from the matched short/long HAL JPEG pair, not from DNG processing.");
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
        json.put("frameNumber", result.getFrameNumber());
        json.put("sensorTimestampNs", timestamp == null ? JSONObject.NULL : timestamp);
        json.put("exposureTimeNs", exposure == null ? JSONObject.NULL : exposure);
        json.put("iso", iso == null ? JSONObject.NULL : iso);
        json.put("frameDurationNs", frameDuration == null ? JSONObject.NULL : frameDuration);
        json.put("postRawSensitivityBoost", postRawBoost == null ? JSONObject.NULL : postRawBoost);
        return json;
    }

    private static double exposureRatio(TotalCaptureResult shortResult, TotalCaptureResult longResult) {
        Long se = shortResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer si = shortResult.get(CaptureResult.SENSOR_SENSITIVITY);
        Long le = longResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer li = longResult.get(CaptureResult.SENSOR_SENSITIVITY);
        Integer sb = shortResult.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        Integer lb = longResult.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        double shortProduct = Math.max(1.0,
                (se == null ? 1.0 : se) * (si == null ? 1.0 : si) * (sb == null ? 100.0 : sb) / 100.0);
        double longProduct = Math.max(1.0,
                (le == null ? 1.0 : le) * (li == null ? 1.0 : li) * (lb == null ? 100.0 : lb) / 100.0);
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
                    "Saved SHORT/LONG DNG + JPEG, fused HDR JPEG, and metadata to Downloads/IrisHDRViewfinder");
        }
    }

    private void failLocked(Throwable t) {
        if (terminal) return;
        terminal = true;
        for (Image image : pendingRaw.values()) image.close();
        pendingRaw.clear();
        pendingJpeg.clear();
        io.shutdown();
        fusion.shutdown();
        listener.onFinished(captureId, false, t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
