package com.skyking0007.irishdrviewfinder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CameraController {
    enum PreviewMode { NORMAL, SPLIT, HDR }

    static final class CameraDescriptor {
        final String id;
        final String label;

        CameraDescriptor(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    interface Listener {
        void onStatus(String text);
        void onPreviewFrame(YuvFrame frame, FrameMeta meta);
        void onCameraConfigured(
                String cameraId,
                int sensorOrientation,
                Size previewSize,
                Size rawSize,
                Size jpegSize,
                Integer syncLatency);
        void onManualSettings(long shortExposureNs, long longExposureNs, int iso);
        void onCaptureFinished(String captureId, boolean success, String message);
    }

    private static final String TAG_NORMAL = "P_NORMAL";
    private static final String TAG_SHORT = "P_SHORT";
    private static final String TAG_LONG = "P_LONG";
    private static final String TAG_CAPTURE_SHORT = "C_SHORT";
    private static final String TAG_CAPTURE_LONG = "C_LONG";

    private final Context context;
    private final CameraManager cameraManager;
    private final Listener listener;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;
    private final Map<Long, FrameMeta> previewMetaByTimestamp = new HashMap<>();
    private final Map<Long, YuvFrame> previewFramesByTimestamp = new HashMap<>();

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader previewReader;
    private ImageReader rawReader;
    private ImageReader jpegReader;
    private CameraCharacteristics characteristics;
    private CaptureSetSaver captureSaver;
    private String cameraId;
    private PreviewMode previewMode = PreviewMode.HDR;
    private boolean opening;
    private boolean capturing;
    private long shortExposureNs = 1_000_000_000L / 120;
    private long longExposureNs = 1_000_000_000L / 30;
    private int manualIso = 400;
    private long lastAeExposureNs = 1_000_000_000L / 60;
    private int lastAeIso = 400;
    private Size previewSize;
    private Size rawSize;
    private Size jpegSize;
    private long previewResultCount;
    private long unmatchedFrames;

    CameraController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("IrisHdrCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    List<CameraDescriptor> getCompatibleCameras() throws CameraAccessException {
        List<CameraDescriptor> out = new ArrayList<>();
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (!contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                    || !contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                continue;
            }
            float focal = 0.0f;
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals != null && focals.length > 0) focal = focals[0];
            Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            Integer sync = c.get(CameraCharacteristics.SYNC_MAX_LATENCY);
            Set<String> physical = c.getPhysicalCameraIds();
            String label = String.format(
                    Locale.US,
                    "ID %s  %.1fmm  RAW+Manual  level=%s  sync=%s%s",
                    id,
                    focal,
                    hardwareLevelName(level),
                    sync == null ? "?" : sync.toString(),
                    physical.isEmpty() ? "" : "  physical=" + physical);
            out.add(new CameraDescriptor(id, label));
        }
        return out;
    }

    void openCamera(String id) {
        cameraHandler.post(() -> {
            if (id == null || id.equals(cameraId) && cameraDevice != null) return;
            closeDeviceLocked();
            cameraId = id;
            cameraHandler.postDelayed(this::openCameraLocked, 150);
        });
    }

    void setPreviewMode(PreviewMode mode) {
        previewMode = mode;
        cameraHandler.post(this::applyPreviewRepeatingLocked);
    }

    void setManualSettings(long shortNs, long longNs, int iso) {
        cameraHandler.post(() -> {
            if (characteristics == null) return;
            shortExposureNs = clampExposure(shortNs);
            longExposureNs = clampExposure(longNs);
            if (longExposureNs < shortExposureNs) {
                long tmp = shortExposureNs;
                shortExposureNs = longExposureNs;
                longExposureNs = tmp;
            }
            manualIso = clampIso(iso);
            listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
            applyPreviewRepeatingLocked();
        });
    }

    void autoBracketFromLastAe() {
        cameraHandler.post(() -> {
            if (characteristics == null) return;
            long base = clampExposure(lastAeExposureNs);
            shortExposureNs = clampExposure(Math.max(100_000L, base / 2));
            longExposureNs = clampExposure(Math.max(shortExposureNs, base * 2));
            manualIso = clampIso(lastAeIso);
            listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
            if (previewMode != PreviewMode.NORMAL) applyPreviewRepeatingLocked();
        });
    }

    void captureHdrSet() {
        cameraHandler.post(this::beginCaptureLocked);
    }

    void stopCamera() {
        cameraHandler.post(this::closeDeviceLocked);
    }

    void close() {
        cameraHandler.post(() -> {
            closeDeviceLocked();
            cameraThread.quitSafely();
        });
    }

    @SuppressLint("MissingPermission")
    private void openCameraLocked() {
        if (opening || cameraDevice != null || cameraId == null) return;
        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Range<Long> expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (expRange == null || isoRange == null) {
                listener.onStatus("Camera " + cameraId + " does not expose manual sensor ranges");
                return;
            }
            shortExposureNs = clampExposure(shortExposureNs);
            longExposureNs = clampExposure(longExposureNs);
            manualIso = clampIso(manualIso);
            resolveOutputSizesLocked();
            opening = true;
            listener.onStatus("Opening camera " + cameraId + "…");
            cameraManager.openCamera(
                    cameraId,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice camera) {
                            opening = false;
                            cameraDevice = camera;
                            startPreviewSessionLocked();
                        }

                        @Override
                        public void onDisconnected(CameraDevice camera) {
                            opening = false;
                            listener.onStatus("Camera disconnected");
                            camera.close();
                            cameraDevice = null;
                        }

                        @Override
                        public void onError(CameraDevice camera, int error) {
                            opening = false;
                            listener.onStatus("Camera error " + error);
                            camera.close();
                            cameraDevice = null;
                        }
                    },
                    cameraHandler);
        } catch (Throwable t) {
            opening = false;
            listener.onStatus("Open failed: " + t.getMessage());
        }
    }

    private void resolveOutputSizesLocked() {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("Camera has no StreamConfigurationMap");
        previewSize = choosePreviewSize(map.getOutputSizes(ImageFormat.YUV_420_888));
        rawSize = chooseLargest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        jpegSize = chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG));
        if (previewSize == null || rawSize == null || jpegSize == null) {
            throw new IllegalStateException("Required YUV/RAW/JPEG output size missing");
        }
    }

    private void startPreviewSessionLocked() {
        if (cameraDevice == null) return;
        closeSessionLocked();
        closeReader(previewReader);
        closeReader(rawReader);
        closeReader(jpegReader);

        previewReader = ImageReader.newInstance(
                previewSize.getWidth(),
                previewSize.getHeight(),
                ImageFormat.YUV_420_888,
                4);
        rawReader = ImageReader.newInstance(
                rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 3);
        jpegReader = ImageReader.newInstance(
                jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 3);

        previewReader.setOnImageAvailableListener(this::onPreviewImageAvailable, cameraHandler);
        rawReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireNextImage();
            CaptureSetSaver saver = captureSaver;
            if (image != null && saver != null) saver.onRawImage(image);
            else if (image != null) image.close();
        }, cameraHandler);
        jpegReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireNextImage();
            CaptureSetSaver saver = captureSaver;
            if (image != null && saver != null) saver.onJpegImage(image);
            else if (image != null) image.close();
        }, cameraHandler);

        previewMetaByTimestamp.clear();
        previewFramesByTimestamp.clear();
        try {
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewReader.getSurface(), jpegReader.getSurface(), rawReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                            Integer sync = characteristics.get(CameraCharacteristics.SYNC_MAX_LATENCY);
                            listener.onCameraConfigured(
                                    cameraId,
                                    sensorOrientation == null ? 0 : sensorOrientation,
                                    previewSize,
                                    rawSize,
                                    jpegSize,
                                    sync);
                            listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
                            applyPreviewRepeatingLocked();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            listener.onStatus("YUV + JPEG + RAW session configuration failed");
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException e) {
            listener.onStatus("Preview session failed: " + e.getMessage());
        }
    }

    private void onPreviewImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            YuvFrame frame = YuvFrame.fromImage(image);
            FrameMeta meta = previewMetaByTimestamp.remove(frame.timestampNs);
            if (meta != null) {
                listener.onPreviewFrame(frame, meta);
            } else {
                previewFramesByTimestamp.put(frame.timestampNs, frame);
                unmatchedFrames++;
                trimPreviewMaps();
            }
        } catch (Throwable t) {
            listener.onStatus("Preview frame decode failed: " + t.getMessage());
        } finally {
            image.close();
        }
    }

    private void applyPreviewRepeatingLocked() {
        if (capturing || cameraDevice == null || captureSession == null || previewReader == null) return;
        try {
            if (previewMode == PreviewMode.NORMAL) {
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewReader.getSurface());
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                builder.setTag(TAG_NORMAL);
                captureSession.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler);
                listener.onStatus("NORMAL AE preview");
            } else {
                CaptureRequest shortRequest = buildManualPreviewRequest(TAG_SHORT, shortExposureNs);
                CaptureRequest longRequest = buildManualPreviewRequest(TAG_LONG, longExposureNs);
                captureSession.setRepeatingBurst(
                        Arrays.asList(shortRequest, longRequest),
                        previewCaptureCallback,
                        cameraHandler);
                listener.onStatus(
                        (previewMode == PreviewMode.HDR ? "HDR" : "SPLIT")
                                + " alternating preview  short=" + exposureText(shortExposureNs)
                                + "  long=" + exposureText(longExposureNs)
                                + "  ISO " + manualIso);
            }
        } catch (Throwable t) {
            listener.onStatus("Repeating request failed: " + t.getMessage());
        }
    }

    private CaptureRequest buildManualPreviewRequest(String tag, long exposureNs)
            throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewReader.getSurface());
        configureManualRequest(builder, exposureNs, manualIso);
        builder.setTag(tag);
        return builder.build();
    }

    private void configureManualRequest(CaptureRequest.Builder builder, long exposureNs, int iso) {
        long exposure = clampExposure(exposureNs);
        int sensitivity = clampIso(iso);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
        Long maxFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        long frameDuration = Math.max(33_333_333L, exposure + 2_000_000L);
        if (maxFrame != null) frameDuration = Math.min(frameDuration, maxFrame);
        frameDuration = Math.max(frameDuration, exposure);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
    }

    private final CameraCaptureSession.CaptureCallback previewCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
                    Object tagObject = request.getTag();
                    if (!(tagObject instanceof String)) return;
                    String tag = (String) tagObject;
                    String kind;
                    if (TAG_SHORT.equals(tag)) kind = FrameMeta.SHORT;
                    else if (TAG_LONG.equals(tag)) kind = FrameMeta.LONG;
                    else if (TAG_NORMAL.equals(tag)) kind = FrameMeta.NORMAL;
                    else return;

                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (timestamp == null || exposure == null || iso == null) return;
                    if (FrameMeta.NORMAL.equals(kind)) {
                        lastAeExposureNs = exposure;
                        lastAeIso = iso;
                    }
                    FrameMeta meta = new FrameMeta(kind, result.getFrameNumber(), timestamp, exposure, iso);
                    YuvFrame frame = previewFramesByTimestamp.remove(timestamp);
                    if (frame != null) {
                        listener.onPreviewFrame(frame, meta);
                    } else {
                        previewMetaByTimestamp.put(timestamp, meta);
                        trimPreviewMaps();
                    }
                    previewResultCount++;
                    if (previewResultCount % 20 == 0) {
                        listener.onStatus(
                                kind + " frame=" + result.getFrameNumber()
                                        + " actual=" + exposureText(exposure)
                                        + " ISO=" + iso
                                        + " unmatched=" + unmatchedFrames);
                    }
                }
            };

    private void beginCaptureLocked() {
        if (capturing || cameraDevice == null || characteristics == null
                || captureSession == null || rawReader == null || jpegReader == null) {
            listener.onStatus(capturing ? "Capture already in progress" : "Camera not ready");
            return;
        }
        capturing = true;
        String captureId = "IrisHDR_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        listener.onStatus("Capturing matched SHORT/LONG RAW + JPEG set…");
        captureSaver = new CaptureSetSaver(
                context,
                characteristics,
                cameraId,
                captureId,
                (id, success, message) -> cameraHandler.post(() -> finishCaptureLocked(id, success, message)));
        issueStillBurstLocked();
    }

    private void issueStillBurstLocked() {
        try {
            CaptureRequest.Builder shortBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            shortBuilder.addTarget(rawReader.getSurface());
            shortBuilder.addTarget(jpegReader.getSurface());
            configureManualRequest(shortBuilder, shortExposureNs, manualIso);
            shortBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            shortBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0);
            shortBuilder.setTag(TAG_CAPTURE_SHORT);

            CaptureRequest.Builder longBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            longBuilder.addTarget(rawReader.getSurface());
            longBuilder.addTarget(jpegReader.getSurface());
            configureManualRequest(longBuilder, longExposureNs, manualIso);
            longBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            longBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0);
            longBuilder.setTag(TAG_CAPTURE_LONG);

            captureSession.captureBurst(
                    Arrays.asList(shortBuilder.build(), longBuilder.build()),
                    stillCaptureCallback,
                    cameraHandler);
        } catch (Throwable t) {
            if (captureSaver != null) captureSaver.abort("Still burst failed: " + t.getMessage());
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
                    if (captureSaver == null) return;
                    Object tag = request.getTag();
                    if (TAG_CAPTURE_SHORT.equals(tag)) {
                        captureSaver.onResult(CaptureSetSaver.CAPTURE_SHORT, result);
                    } else if (TAG_CAPTURE_LONG.equals(tag)) {
                        captureSaver.onResult(CaptureSetSaver.CAPTURE_LONG, result);
                    }
                }

                @Override
                public void onCaptureFailed(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        CaptureFailure failure) {
                    if (captureSaver != null) {
                        captureSaver.abort("Still capture failed, reason=" + failure.getReason());
                    }
                }
            };

    private void finishCaptureLocked(String captureId, boolean success, String message) {
        captureSaver = null;
        capturing = false;
        listener.onCaptureFinished(captureId, success, message);
        applyPreviewRepeatingLocked();
    }

    private void closeDeviceLocked() {
        capturing = false;
        if (captureSaver != null) captureSaver.abort("Camera closed");
        captureSaver = null;
        closeSessionLocked();
        closeReader(previewReader);
        closeReader(rawReader);
        closeReader(jpegReader);
        previewReader = null;
        rawReader = null;
        jpegReader = null;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        characteristics = null;
        previewMetaByTimestamp.clear();
        previewFramesByTimestamp.clear();
    }

    private void closeSessionLocked() {
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Throwable ignored) {
            }
            captureSession = null;
        }
    }

    private static void closeReader(ImageReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private long clampExposure(long value) {
        Range<Long> range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (range == null) return value;
        return Math.max(range.getLower(), Math.min(range.getUpper(), value));
    }

    private int clampIso(int value) {
        Range<Integer> range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (range == null) return value;
        return Math.max(range.getLower(), Math.min(range.getUpper(), value));
    }

    private void trimPreviewMaps() {
        while (previewMetaByTimestamp.size() > 12) {
            Long oldest = previewMetaByTimestamp.keySet().stream().min(Long::compareTo).orElse(null);
            if (oldest == null) break;
            previewMetaByTimestamp.remove(oldest);
        }
        while (previewFramesByTimestamp.size() > 8) {
            Long oldest = previewFramesByTimestamp.keySet().stream().min(Long::compareTo).orElse(null);
            if (oldest == null) break;
            previewFramesByTimestamp.remove(oldest);
        }
    }

    private static Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size target = new Size(1280, 720);
        return Arrays.stream(sizes)
                .filter(s -> (long) s.getWidth() * s.getHeight() <= 1920L * 1080L)
                .min(Comparator.comparingDouble(s -> sizeScore(s, target)))
                .orElseGet(() -> Arrays.stream(sizes)
                        .min(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                        .orElse(sizes[0]));
    }

    private static Size chooseJpegSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        long targetArea = 8_000_000L;
        Size bestUnder = null;
        long bestArea = -1;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (area <= 9_000_000L && area > bestArea) {
                bestUnder = size;
                bestArea = area;
            }
        }
        if (bestUnder != null) return bestUnder;
        return Arrays.stream(sizes)
                .min(Comparator.comparingLong(s -> Math.abs((long) s.getWidth() * s.getHeight() - targetArea)))
                .orElse(sizes[0]);
    }

    private static Size chooseLargest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Arrays.stream(sizes)
                .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                .orElse(sizes[0]);
    }

    private static double sizeScore(Size size, Size target) {
        double areaRatio = Math.log(((double) size.getWidth() * size.getHeight())
                / ((double) target.getWidth() * target.getHeight()));
        double aspect = size.getWidth() / (double) size.getHeight();
        double targetAspect = target.getWidth() / (double) target.getHeight();
        return Math.abs(areaRatio) + 4.0 * Math.abs(aspect - targetAspect);
    }

    private static boolean contains(int[] values, int needle) {
        if (values == null) return false;
        for (int value : values) if (value == needle) return true;
        return false;
    }

    private static String hardwareLevelName(Integer level) {
        if (level == null) return "?";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "EXTERNAL";
        return level.toString();
    }

    static String exposureText(long ns) {
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 1.0) return String.format(Locale.US, "%.2fs", seconds);
        double reciprocal = 1.0 / Math.max(1e-9, seconds);
        if (reciprocal >= 2.0) return "1/" + Math.round(reciprocal) + "s";
        return String.format(Locale.US, "%.1fms", ns / 1_000_000.0);
    }
}
