package com.skyking0007.irishdrviewfinder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
        void onPreviewMeta(FrameMeta meta);
        void onPreviewSurfaceSizeRequired(Size previewSize);
        void onCameraConfigured(
                String cameraId,
                int sensorOrientation,
                Size previewSize,
                Size rawSize,
                Size jpegSize,
                Integer syncLatency,
                int targetPreviewFps,
                Range<Integer> aeFpsRange,
                boolean srgbTonemap);
        void onManualSettings(long shortExposureNs, long longExposureNs, int iso);
        void onAutoHdrSettings(
                long shortExposureNs,
                int shortIso,
                long longExposureNs,
                int longIso,
                String flickerLabel,
                double bracketEv);
        void onCaptureFinished(String captureId, boolean success, String message);
    }

    private static final String TAG_NORMAL = "P_NORMAL";
    private static final String TAG_SHORT = "P_SHORT";
    private static final String TAG_LONG = "P_LONG";
    private static final String TAG_CAPTURE_SHORT = "C_SHORT";
    private static final String TAG_CAPTURE_LONG = "C_LONG";
    private static final long ONE_SECOND_NS = 1_000_000_000L;
    private static final long SIXTY_FPS_DURATION_NS = 16_666_667L;
    private static final long THIRTY_FPS_DURATION_NS = 33_333_333L;
    private static final double HDR_BRACKET_RATIO = 8.0;
    private static final double AUTO_UPDATE_HYSTERESIS_EV = 0.35;
    private static final double AUTO_SHUTTER_HYSTERESIS_EV = 0.20;
    private static final long AUTO_SHORT_UPDATE_MIN_INTERVAL_NS = 500_000_000L;
    private static final long AUTO_UI_NOTIFY_INTERVAL_NS = 500_000_000L;
    private static final double FOV_MAX_REPORTED_SCALE = 1.03;
    private static final float FOV_MAX_REPORTED_ZOOM = 1.02f;
    private static final int FOV_UNSAFE_CONFIRM_FRAMES = 3;
    private static final int FOV_DECISION_FRAMES = 60;
    private static final int FLICKER_UNKNOWN = -1;

    private final Context context;
    private final CameraManager cameraManager;
    private final Listener listener;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader rawReader;
    private ImageReader jpegReader;
    private CameraCharacteristics characteristics;
    private CaptureSetSaver captureSaver;
    private Surface previewSurface;
    private String cameraId;
    private PreviewMode previewMode = PreviewMode.HDR;
    private boolean opening;
    private boolean capturing;
    private boolean stillSessionActive;
    private boolean previewSurfaceConfigured;
    private long shortExposureNs = ONE_SECOND_NS / 480;
    private long longExposureNs = ONE_SECOND_NS / 60;
    private int manualIso = 400;
    private long manualEffectiveShortExposureNs = ONE_SECOND_NS / 480;
    private long manualEffectiveLongExposureNs = ONE_SECOND_NS / 60;
    private int manualEffectiveShortIso = 400;
    private int manualEffectiveLongIso = 400;
    private boolean manualFlickerSafetyApplied;
    private double manualEffectiveBracketEv = 3.0;
    private boolean autoHdrExposure = true;
    private long autoShortExposureNs = ONE_SECOND_NS / 120;
    private long autoLongExposureNs = ONE_SECOND_NS / 60;
    private int autoShortIso = 100;
    private int autoLongIso = 400;
    private int sceneFlicker = FLICKER_UNKNOWN;
    private double lastAppliedAutoLongProduct = -1.0;
    private volatile int jpegOrientationDegrees;
    private long lastAeExposureNs = ONE_SECOND_NS / 60;
    private int lastAeIso = 400;
    private Size previewSize;
    private Size rawSize;
    private Size jpegSize;
    private long previewResultCount;
    private long resultFpsWindowStartNs;
    private int resultFpsWindowFrames;
    private double captureResultFps;
    private int sixtyFpsUnderDeliveryWindows;
    private boolean haveAeSample;
    private boolean lastAppliedAutoStableBright;
    private long lastAutoShortApplyNs;
    private long lastAutoUiNotifyNs;
    private long previewMinFrameDurationNs;
    private long manualFrameDurationNs = THIRTY_FPS_DURATION_NS;
    private int targetPreviewFps = 30;
    private Range<Integer> aeFpsRange;
    private boolean srgbTonemapSupported;
    private Rect activeArray;
    private int fovEvidenceFrames;
    private int fovUnsafeFrames;
    private boolean fovDecisionLogged;
    private boolean fovFallbackApplied;
    private Rect lastReportedCropRegion;
    private Rect lastPhysicalSensorCropRegion;
    private Rect lastPhysicalActiveArray;
    private String lastPhysicalActiveArrayId;
    private Float lastReportedZoomRatio;
    private String lastActivePhysicalId;

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

    void setPreviewSurface(Surface surface) {
        cameraHandler.post(() -> {
            if (previewSurface == surface) return;
            RuntimeLogger.event("PREVIEW_SURFACE", surface == null ? "cleared" : "received valid=" + surface.isValid());
            previewSurface = surface;
            previewSurfaceConfigured = false;
            if (captureSession != null) closeSessionLocked();
            if (cameraDevice != null && previewSize != null && surface != null) {
                listener.onPreviewSurfaceSizeRequired(previewSize);
            }
        });
    }

    void onPreviewSurfaceConfigured() {
        cameraHandler.post(() -> {
            if (previewSurface == null || !previewSurface.isValid()) return;
            previewSurfaceConfigured = true;
            RuntimeLogger.event("PREVIEW_SURFACE", "configured size=" + previewSize);
            startPreviewSessionLocked();
        });
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
        cameraHandler.post(() -> {
            RuntimeLogger.event("PREVIEW_MODE", String.valueOf(previewMode));
            if (previewMode != PreviewMode.NORMAL && autoHdrExposure && haveAeSample) {
                updateAutoHdrFromAeLocked(lastAeExposureNs, lastAeIso, sceneFlicker, true);
            }
            applyPreviewRepeatingLocked();
        });
    }

    void setAutoHdrExposure(boolean enabled) {
        cameraHandler.post(() -> {
            autoHdrExposure = enabled;
            if (enabled && haveAeSample) {
                updateAutoHdrFromAeLocked(lastAeExposureNs, lastAeIso, sceneFlicker, true);
            } else if (!enabled) {
                recomputeManualFlickerSafetyLocked();
                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
            }
            RuntimeLogger.event("HDR_MODE", enabled ? "AUTO" : manualSafetySummaryLocked());
            if (previewMode != PreviewMode.NORMAL) applyPreviewRepeatingLocked();
        });
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
            recomputeManualFlickerSafetyLocked();
            if (!autoHdrExposure) {
                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
                RuntimeLogger.event("MANUAL_SETTINGS", manualSafetySummaryLocked());
                applyPreviewRepeatingLocked();
            }
        });
    }

    void captureHdrSet() {
        cameraHandler.post(this::beginCaptureLocked);
    }

    void setJpegOrientationDegrees(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if ((normalized % 90) != 0) {
            throw new IllegalArgumentException("JPEG orientation must be a multiple of 90: " + degrees);
        }
        jpegOrientationDegrees = normalized;
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
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Range<Long> expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (expRange == null || isoRange == null) {
                listener.onStatus("Camera " + cameraId + " does not expose manual sensor ranges");
                return;
            }
            shortExposureNs = clampExposure(shortExposureNs);
            longExposureNs = clampExposure(longExposureNs);
            manualIso = clampIso(manualIso);
            autoShortExposureNs = clampExposure(autoShortExposureNs);
            autoLongExposureNs = clampExposure(autoLongExposureNs);
            autoShortIso = clampIso(autoShortIso);
            autoLongIso = clampIso(autoLongIso);
            resolveOutputSizesLocked();
            resetCaptureResultFpsLocked();
            resetFovEvidenceLocked();
            haveAeSample = false;
            sceneFlicker = FLICKER_UNKNOWN;
            recomputeManualFlickerSafetyLocked();
            lastAppliedAutoLongProduct = -1.0;
            lastAppliedAutoStableBright = false;
            lastAutoShortApplyNs = 0L;
            lastAutoUiNotifyNs = 0L;
            opening = true;
            RuntimeLogger.event(
                    "CAMERA_OPEN",
                    "id=" + cameraId
                            + " activeArray=" + rectText(activeArray)
                            + " preview=" + previewSize
                            + " raw=" + rawSize
                            + " jpeg=" + jpegSize
                            + " requestedTarget=" + targetPreviewFps
                            + " aeFps=" + rangeText(aeFpsRange));
            listener.onStatus("Opening camera " + cameraId + "…");
            cameraManager.openCamera(
                    cameraId,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice camera) {
                            opening = false;
                            cameraDevice = camera;
                            requestPreviewSurfaceConfigurationLocked();
                        }

                        @Override
                        public void onDisconnected(CameraDevice camera) {
                            opening = false;
                            RuntimeLogger.event("CAMERA_ERROR", "disconnected");
                            listener.onStatus("Camera disconnected");
                            camera.close();
                            cameraDevice = null;
                        }

                        @Override
                        public void onError(CameraDevice camera, int error) {
                            opening = false;
                            RuntimeLogger.event("CAMERA_ERROR", "device error=" + error);
                            listener.onStatus("Camera error " + error);
                            camera.close();
                            cameraDevice = null;
                        }
                    },
                    cameraHandler);
        } catch (Throwable t) {
            opening = false;
            RuntimeLogger.error("CAMERA_OPEN_FAIL", t);
            listener.onStatus("Open failed: " + t.getMessage());
        }
    }

    private void resolveOutputSizesLocked() {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("Camera has no StreamConfigurationMap");

        rawSize = chooseLargest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        if (rawSize == null) throw new IllegalStateException("Required RAW output size missing");
        double nativeAspect = landscapeAspect(rawSize);

        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean aeCanReach60 = maxAeFps(ranges) >= 60;
        previewSize = choosePrivatePreviewSize(map, nativeAspect, aeCanReach60);
        jpegSize = chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG), nativeAspect);
        if (previewSize == null || jpegSize == null) {
            throw new IllegalStateException("Required PRIVATE/JPEG output size missing");
        }

        previewMinFrameDurationNs = outputMinFrameDuration(map, previewSize);
        boolean streamCanReach60 = previewMinFrameDurationNs <= 0
                || previewMinFrameDurationNs <= SIXTY_FPS_DURATION_NS;
        targetPreviewFps = aeCanReach60 && streamCanReach60 ? 60 : 30;
        manualFrameDurationNs = targetPreviewFps >= 60
                ? SIXTY_FPS_DURATION_NS : THIRTY_FPS_DURATION_NS;
        if (previewMinFrameDurationNs > 0) {
            manualFrameDurationNs = Math.max(manualFrameDurationNs, previewMinFrameDurationNs);
        }
        aeFpsRange = chooseAeFpsRange(ranges, targetPreviewFps);
        srgbTonemapSupported = supportsSrgbTonemap(characteristics);
    }

    private void requestPreviewSurfaceConfigurationLocked() {
        if (cameraDevice == null || previewSize == null || previewSurface == null) return;
        previewSurfaceConfigured = false;
        listener.onPreviewSurfaceSizeRequired(previewSize);
    }

    private void startPreviewSessionLocked() {
        if (cameraDevice == null || previewSurface == null || !previewSurfaceConfigured
                || !previewSurface.isValid() || stillSessionActive) return;
        closeSessionLocked();
        try {
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null || stillSessionActive) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            resetFovEvidenceLocked();
                            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                            Integer sync = characteristics.get(CameraCharacteristics.SYNC_MAX_LATENCY);
                            listener.onCameraConfigured(
                                    cameraId,
                                    sensorOrientation == null ? 0 : sensorOrientation,
                                    previewSize,
                                    rawSize,
                                    jpegSize,
                                    sync,
                                    targetPreviewFps,
                                    aeFpsRange,
                                    srgbTonemapSupported);
                            resetCaptureResultFpsLocked();
                            if (autoHdrExposure && haveAeSample) {
                                updateAutoHdrFromAeLocked(lastAeExposureNs, lastAeIso, sceneFlicker, true);
                            } else if (!autoHdrExposure) {
                                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
                            }
                            RuntimeLogger.event(
                                    "SESSION_CONFIGURED",
                                    "preview-only target=" + targetPreviewFps
                                            + " aeFps=" + rangeText(aeFpsRange)
                                            + " preview=" + previewSize);
                            applyPreviewRepeatingLocked();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            RuntimeLogger.event("SESSION_ERROR", "PRIVATE preview-only configuration failed");
                            listener.onStatus("PRIVATE preview-only session configuration failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            RuntimeLogger.error("SESSION_START_FAIL", e);
            listener.onStatus("Preview session failed: " + e.getMessage());
        }
    }

    private void startStillCaptureSessionLocked() {
        if (cameraDevice == null || previewSurface == null || !previewSurface.isValid()) {
            if (captureSaver != null) captureSaver.abort("Camera preview surface unavailable for still capture");
            return;
        }
        closeSessionLocked();
        closeReader(rawReader);
        closeReader(jpegReader);
        rawReader = ImageReader.newInstance(
                rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 3);
        jpegReader = ImageReader.newInstance(
                jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 3);
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

        try {
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, jpegReader.getSurface(), rawReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null || !stillSessionActive) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            issueStillBurstLocked();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            if (captureSaver != null) {
                                captureSaver.abort("PRIVATE + JPEG + RAW still session configuration failed");
                            }
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            if (captureSaver != null) captureSaver.abort("Still session failed: " + e.getMessage());
        }
    }

    private void applyPreviewRepeatingLocked() {
        if (stillSessionActive || cameraDevice == null || captureSession == null || previewSurface == null) return;
        try {
            if (previewMode == PreviewMode.NORMAL) {
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewSurface);
                configureAutoExposureRequest(builder);
                builder.setTag(TAG_NORMAL);
                captureSession.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler);
                listener.onStatus("NORMAL AE preview  target=" + rangeText(aeFpsRange) + " fps");
            } else {
                long activeShortNs = activeShortExposureNs();
                long activeLongNs = activeLongExposureNs();
                int activeShortIso = activeShortIso();
                int activeLongIso = activeLongIso();
                CaptureRequest shortRequest = buildManualPreviewRequest(TAG_SHORT, activeShortNs, activeShortIso);
                CaptureRequest longRequest;
                if (autoHdrExposure) {
                    // The LONG member is the meter. Keeping AE inside the same two-request
                    // repeating burst avoids V1.4.2's disruptive out-of-band capture().
                    longRequest = buildAutoLongPreviewRequest();
                } else {
                    longRequest = buildManualPreviewRequest(TAG_LONG, activeLongNs, activeLongIso);
                }
                captureSession.setRepeatingBurst(
                        Arrays.asList(shortRequest, longRequest),
                        previewCaptureCallback,
                        cameraHandler);
                listener.onStatus(
                        (previewMode == PreviewMode.HDR ? "HDR" : "SPLIT")
                                + (autoHdrExposure
                                        ? " AUTO"
                                        : manualFlickerSafetyApplied ? " MANUAL SAFE" : " MANUAL")
                                + " paired preview  short=" + exposureText(activeShortNs) + " ISO" + activeShortIso
                                + (autoHdrExposure
                                        ? "  long=AE-live latest=" + exposureText(autoLongExposureNs) + " ISO" + autoLongIso
                                        : "  long=" + exposureText(activeLongNs) + " ISO" + activeLongIso
                                                + String.format(Locale.US, "  bracket=%.1fEV", manualEffectiveBracketEv))
                                + "  flicker=" + flickerLabel(sceneFlicker)
                                + "  target=" + targetPreviewFps + " sensor fps");
            }
        } catch (Throwable t) {
            RuntimeLogger.error("REPEATING_FAIL", t);
            listener.onStatus("Repeating request failed: " + t.getMessage());
        }
    }

    private CaptureRequest buildManualPreviewRequest(String tag, long exposureNs, int iso)
            throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        configureManualRequest(builder, exposureNs, iso);
        // CONTROL_AE_TARGET_FPS_RANGE is typically a session parameter. Keep it identical
        // on both members of the repeating pair even though AE is OFF for SHORT, so AUTO
        // never alternates this session-sensitive request key frame by frame.
        if (aeFpsRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeFpsRange);
        }
        configurePreviewRotateAndCrop(builder);
        builder.setTag(tag);
        return builder.build();
    }

    private CaptureRequest buildAutoLongPreviewRequest() throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        configureAutoExposureRequest(builder);
        builder.setTag(TAG_LONG);
        return builder.build();
    }

    private void configureAutoExposureRequest(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (aeFpsRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeFpsRange);
        }
        configurePreviewRotateAndCrop(builder);
        configureSrgbTonemap(builder);
    }

    private void configureManualRequest(CaptureRequest.Builder builder, long exposureNs, int iso) {
        long exposure = clampExposure(exposureNs);
        int sensitivity = clampIso(iso);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
        Long maxFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        long frameDuration = Math.max(manualFrameDurationNs, exposure);
        if (maxFrame != null) frameDuration = Math.min(frameDuration, maxFrame);
        frameDuration = Math.max(frameDuration, exposure);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
        configureSrgbTonemap(builder);
    }

    private void configurePreviewRotateAndCrop(CaptureRequest.Builder builder) {
        if (Build.VERSION.SDK_INT < 31 || characteristics == null) return;
        int[] modes = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
        if (contains(modes, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)) {
            builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
        }
    }

    private void configureSrgbTonemap(CaptureRequest.Builder builder) {
        if (!srgbTonemapSupported) return;
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE);
        builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB);
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

                    updateCaptureResultFpsLocked();
                    updateFovEvidenceLocked(result);
                    Integer observedFlicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
                    if (!autoHdrExposure
                            && previewMode != PreviewMode.NORMAL
                            && observedFlicker != null
                            && (observedFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ
                                    || observedFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ)
                            && observedFlicker != sceneFlicker) {
                        sceneFlicker = observedFlicker;
                        if (recomputeManualFlickerSafetyLocked()) {
                            RuntimeLogger.event("MANUAL_FLICKER", manualSafetySummaryLocked());
                            applyPreviewRepeatingLocked();
                        }
                    }

                    if (FrameMeta.NORMAL.equals(kind)
                            || FrameMeta.LONG.equals(kind) && autoHdrExposure) {
                        boolean firstAeSample = !haveAeSample;
                        if (observedFlicker != null) sceneFlicker = observedFlicker;
                        lastAeExposureNs = exposure;
                        lastAeIso = iso;
                        haveAeSample = true;
                        if (FrameMeta.LONG.equals(kind)
                                && previewMode != PreviewMode.NORMAL
                                && updateAutoHdrFromAeLocked(
                                        exposure, iso, sceneFlicker, firstAeSample)) {
                            // Rebuild only when the derived SHORT bracket changes materially.
                            // There is never a third capture request in the live AUTO schedule.
                            applyPreviewRepeatingLocked();
                        }
                    }

                    FrameMeta meta = new FrameMeta(kind, result.getFrameNumber(), timestamp, exposure, iso);
                    listener.onPreviewMeta(meta);
                    previewResultCount++;
                    Long frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                    if (previewResultCount % 300 == 0) {
                        RuntimeLogger.event(
                                "CAMERA_HEALTH",
                                kind + " frame=" + result.getFrameNumber()
                                        + " actual=" + exposureText(exposure)
                                        + " ISO=" + iso
                                        + " frameDuration=" + exposureText(frameDuration == null ? 0L : frameDuration)
                                        + " resultFps=" + String.format(Locale.US, "%.1f", captureResultFps)
                                        + " flicker=" + flickerLabel(sceneFlicker)
                                        + " target=" + targetPreviewFps
                                        + " crop=" + rectText(lastReportedCropRegion)
                                        + " physicalSensorCrop=" + rectText(lastPhysicalSensorCropRegion)
                                        + " physicalActiveArray=" + rectText(lastPhysicalActiveArray)
                                        + " zoom=" + (lastReportedZoomRatio == null ? "?" : lastReportedZoomRatio)
                                        + " physical=" + (lastActivePhysicalId == null ? "?" : lastActivePhysicalId));
                    }
                    if (previewResultCount % 60 == 0) {
                        listener.onStatus(
                                kind + " frame=" + result.getFrameNumber()
                                        + " actual=" + exposureText(exposure)
                                        + " ISO=" + iso
                                        + " frameDuration=" + exposureText(frameDuration == null ? 0L : frameDuration)
                                        + " resultFps=" + String.format(Locale.US, "%.1f", captureResultFps)
                                        + " flicker=" + flickerLabel(sceneFlicker)
                                        + " target=" + targetPreviewFps + " sensor fps");
                    }
                }
            };

    private void beginCaptureLocked() {
        if (capturing || cameraDevice == null || characteristics == null
                || captureSession == null || previewSurface == null || !previewSurface.isValid()) {
            listener.onStatus(capturing ? "Capture already in progress" : "Camera not ready");
            return;
        }
        capturing = true;
        String captureId = "IrisHDR_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        RuntimeLogger.event(
                "CAPTURE_BEGIN",
                captureId
                        + " short=" + exposureText(activeShortExposureNs()) + " ISO" + activeShortIso()
                        + " long=" + exposureText(activeLongExposureNs()) + " ISO" + activeLongIso()
                        + " mode=" + (autoHdrExposure ? "AUTO" : manualSafetySummaryLocked()));
        listener.onStatus("Capturing matched SHORT/LONG RAW + JPEG set…");
        captureSaver = new CaptureSetSaver(
                context,
                characteristics,
                cameraId,
                captureId,
                jpegOrientationDegrees,
                new CaptureSetSaver.Listener() {
                    @Override
                    public void onInputsAcquired(String id) {
                        cameraHandler.post(() -> resumePreviewAfterStillInputsLocked(id));
                    }

                    @Override
                    public void onFinished(String id, boolean success, String message) {
                        cameraHandler.post(() -> finishCaptureLocked(id, success, message));
                    }
                });
        stillSessionActive = true;
        startStillCaptureSessionLocked();
    }

    private void issueStillBurstLocked() {
        try {
            CaptureRequest.Builder shortBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            shortBuilder.addTarget(rawReader.getSurface());
            shortBuilder.addTarget(jpegReader.getSurface());
            configureManualRequest(shortBuilder, activeShortExposureNs(), activeShortIso());
            shortBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            shortBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees);
            shortBuilder.setTag(TAG_CAPTURE_SHORT);

            CaptureRequest.Builder longBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            longBuilder.addTarget(rawReader.getSurface());
            longBuilder.addTarget(jpegReader.getSurface());
            configureManualRequest(longBuilder, activeLongExposureNs(), activeLongIso());
            longBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            longBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees);
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
                        RuntimeLogger.event("STILL_FOV", "SHORT " + fovResultSummary(result));
                        captureSaver.onResult(CaptureSetSaver.CAPTURE_SHORT, result);
                    } else if (TAG_CAPTURE_LONG.equals(tag)) {
                        RuntimeLogger.event("STILL_FOV", "LONG " + fovResultSummary(result));
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

    private void resumePreviewAfterStillInputsLocked(String captureId) {
        if (!capturing || !stillSessionActive) return;
        stillSessionActive = false;
        closeSessionLocked();
        listener.onStatus("HDR inputs acquired; preview resumed while files save: " + captureId);
        startPreviewSessionLocked();
    }

    private void finishCaptureLocked(String captureId, boolean success, String message) {
        captureSaver = null;
        capturing = false;
        if (stillSessionActive) {
            stillSessionActive = false;
            closeSessionLocked();
            startPreviewSessionLocked();
        }
        closeReader(rawReader);
        closeReader(jpegReader);
        rawReader = null;
        jpegReader = null;
        RuntimeLogger.event(
                success ? "CAPTURE_DONE" : "CAPTURE_FAIL",
                captureId + " " + message);
        listener.onCaptureFinished(captureId, success, message);
        applyPreviewRepeatingLocked();
    }

    private void closeDeviceLocked() {
        capturing = false;
        stillSessionActive = false;
        if (captureSaver != null) captureSaver.abort("Camera closed");
        captureSaver = null;
        closeSessionLocked();
        closeReader(rawReader);
        closeReader(jpegReader);
        rawReader = null;
        jpegReader = null;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        characteristics = null;
        activeArray = null;
        previewSurfaceConfigured = false;
        RuntimeLogger.event("CAMERA_CLOSE", "camera device closed");
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

    private void resetFovEvidenceLocked() {
        fovEvidenceFrames = 0;
        fovUnsafeFrames = 0;
        fovDecisionLogged = false;
        if (targetPreviewFps >= 60) fovFallbackApplied = false;
        lastReportedCropRegion = null;
        lastPhysicalSensorCropRegion = null;
        lastPhysicalActiveArray = null;
        lastPhysicalActiveArrayId = null;
        lastReportedZoomRatio = null;
        lastActivePhysicalId = null;
    }

    private void updateFovEvidenceLocked(TotalCaptureResult result) {
        // SCALER_CROP_REGION is useful diagnostics, but Android explicitly warns that
        // in-sensor crops caused by output stream combinations / AE FPS can still report
        // the same full active-array crop. Never use it alone as FOV-parity proof.
        lastReportedCropRegion = result.get(CaptureResult.SCALER_CROP_REGION);
        if (Build.VERSION.SDK_INT >= 30) {
            lastReportedZoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
        }
        lastActivePhysicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        // API 35 added the public result that explicitly describes the active
        // physical sensor readout region. This is the decisive 60-fps FOV evidence.
        lastPhysicalSensorCropRegion = Build.VERSION.SDK_INT >= 35
                ? result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION)
                : null;
        updatePhysicalActiveArrayLocked();

        if (targetPreviewFps < 60 || fovFallbackApplied || fovDecisionLogged) return;
        fovEvidenceFrames++;

        double reportedScale = 1.0;
        boolean haveSensorReadoutEvidence = false;
        Rect referenceArray = lastPhysicalActiveArray != null ? lastPhysicalActiveArray : activeArray;
        if (referenceArray != null && referenceArray.width() > 0 && referenceArray.height() > 0
                && lastPhysicalSensorCropRegion != null
                && lastPhysicalSensorCropRegion.width() > 0
                && lastPhysicalSensorCropRegion.height() > 0) {
            double scaleX = referenceArray.width() / (double) lastPhysicalSensorCropRegion.width();
            double scaleY = referenceArray.height() / (double) lastPhysicalSensorCropRegion.height();
            reportedScale = Math.max(reportedScale, Math.max(scaleX, scaleY));
            haveSensorReadoutEvidence = true;
        }

        boolean zoomUnsafe = lastReportedZoomRatio != null
                && lastReportedZoomRatio > FOV_MAX_REPORTED_ZOOM;
        boolean sensorCropUnsafe = haveSensorReadoutEvidence
                && reportedScale > FOV_MAX_REPORTED_SCALE;
        if (zoomUnsafe || sensorCropUnsafe) fovUnsafeFrames++;

        if (fovUnsafeFrames >= FOV_UNSAFE_CONFIRM_FRAMES) {
            switchToFovSafe30Locked(
                    "60fps sensor-readout scale=" + String.format(Locale.US, "%.3f", reportedScale)
                            + " physicalSensorCrop=" + rectText(lastPhysicalSensorCropRegion)
                            + " physicalActiveArray=" + rectText(referenceArray)
                            + " crop=" + rectText(lastReportedCropRegion)
                            + " zoom=" + (lastReportedZoomRatio == null ? "?" : lastReportedZoomRatio)
                            + " physical=" + (lastActivePhysicalId == null ? "?" : lastActivePhysicalId));
            return;
        }

        if (fovEvidenceFrames >= FOV_DECISION_FRAMES) {
            fovDecisionLogged = true;
            if (haveSensorReadoutEvidence && !zoomUnsafe) {
                RuntimeLogger.event(
                        "FOV_PARITY",
                        "60fps physical-sensor readout reports full FOV; keeping 60fps"
                                + " physicalSensorCrop=" + rectText(lastPhysicalSensorCropRegion)
                                + " physicalActiveArray=" + rectText(referenceArray)
                                + " crop=" + rectText(lastReportedCropRegion)
                                + " zoom=" + (lastReportedZoomRatio == null ? "?" : lastReportedZoomRatio)
                                + " physical=" + (lastActivePhysicalId == null ? "?" : lastActivePhysicalId));
            } else {
                switchToFovSafe30Locked(
                        "60fps physical-sensor FOV parity unavailable after "
                                + fovEvidenceFrames + " frames"
                                + "; cropRegion alone cannot prove in-sensor FOV parity"
                                + "; physicalSensorCrop=" + rectText(lastPhysicalSensorCropRegion)
                                + "; crop=" + rectText(lastReportedCropRegion)
                                + "; zoom=" + (lastReportedZoomRatio == null ? "?" : lastReportedZoomRatio)
                                + "; physical=" + (lastActivePhysicalId == null ? "?" : lastActivePhysicalId));
            }
        }
    }

    private void updatePhysicalActiveArrayLocked() {
        String id = lastActivePhysicalId;
        if (id == null || id.isEmpty() || id.equals(cameraId)) {
            lastPhysicalActiveArray = activeArray == null ? null : new Rect(activeArray);
            lastPhysicalActiveArrayId = cameraId;
            return;
        }
        if (id.equals(lastPhysicalActiveArrayId) && lastPhysicalActiveArray != null) return;
        try {
            CameraCharacteristics physical = cameraManager.getCameraCharacteristics(id);
            Rect physicalArray = physical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            lastPhysicalActiveArray = physicalArray == null ? null : new Rect(physicalArray);
            lastPhysicalActiveArrayId = id;
        } catch (Throwable t) {
            lastPhysicalActiveArray = null;
            lastPhysicalActiveArrayId = id;
            RuntimeLogger.error("FOV_PHYSICAL_METADATA_FAIL", t);
        }
    }

    private void switchToFovSafe30Locked(String reason) {
        if (targetPreviewFps < 60 || fovFallbackApplied) return;
        fovFallbackApplied = true;
        targetPreviewFps = 30;
        manualFrameDurationNs = Math.max(THIRTY_FPS_DURATION_NS, previewMinFrameDurationNs);
        Range<Integer>[] ranges = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        aeFpsRange = chooseAeFpsRange(ranges, 30);
        recomputeManualFlickerSafetyLocked();
        RuntimeLogger.event("FOV_FALLBACK", reason + "; recreating preview session at 30fps");
        listener.onStatus("60fps FOV parity unavailable/cropped; switching to full-FOV 30fps");
        if (captureSession != null && !stillSessionActive) {
            closeSessionLocked();
            cameraHandler.postDelayed(this::startPreviewSessionLocked, 50);
        }
    }

    private String fovResultSummary(TotalCaptureResult result) {
        Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
        Rect physicalCrop = Build.VERSION.SDK_INT >= 35
                ? result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION)
                : null;
        Float zoom = Build.VERSION.SDK_INT >= 30
                ? result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                : null;
        String physical = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        return "crop=" + rectText(crop)
                + " physicalSensorCrop=" + rectText(physicalCrop)
                + " zoom=" + (zoom == null ? "?" : zoom)
                + " physical=" + (physical == null ? "?" : physical);
    }

    private static String rectText(Rect rect) {
        if (rect == null) return "?";
        return rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom
                + "(" + rect.width() + "x" + rect.height() + ")";
    }

    private void resetCaptureResultFpsLocked() {
        resultFpsWindowStartNs = 0L;
        resultFpsWindowFrames = 0;
        captureResultFps = 0.0;
        sixtyFpsUnderDeliveryWindows = 0;
    }

    private void updateCaptureResultFpsLocked() {
        long now = System.nanoTime();
        if (resultFpsWindowStartNs == 0L) resultFpsWindowStartNs = now;
        resultFpsWindowFrames++;
        long elapsed = now - resultFpsWindowStartNs;
        if (elapsed >= ONE_SECOND_NS) {
            captureResultFps = resultFpsWindowFrames * ONE_SECOND_NS / (double) elapsed;
            resultFpsWindowFrames = 0;
            resultFpsWindowStartNs = now;
            if (targetPreviewFps >= 60) {
                if (captureResultFps < 45.0) sixtyFpsUnderDeliveryWindows++;
                else sixtyFpsUnderDeliveryWindows = 0;
                if (sixtyFpsUnderDeliveryWindows >= 2) {
                    targetPreviewFps = 30;
                    manualFrameDurationNs = Math.max(THIRTY_FPS_DURATION_NS, previewMinFrameDurationNs);
                    Range<Integer>[] ranges = characteristics == null ? null
                            : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    aeFpsRange = chooseAeFpsRange(ranges, 30);
                    sixtyFpsUnderDeliveryWindows = 0;
                    listener.onStatus(
                            "60 fps capability under-delivered at "
                                    + String.format(Locale.US, "%.1f", captureResultFps)
                                    + " CaptureResult fps; switching live target to 30 fps");
                    if (captureSession != null && !stillSessionActive) applyPreviewRepeatingLocked();
                }
            }
        }
    }

    private boolean updateAutoHdrFromAeLocked(long aeExposureNs, int aeIso, int flicker, boolean force) {
        if (characteristics == null) return false;
        Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (exposureRange == null || isoRange == null) return false;

        long minExposure = exposureRange.getLower();
        long meteredLongExposure = clampExposure(aeExposureNs);
        int meteredLongIso = clampIso(aeIso);
        double targetLongProduct = Math.max(1.0, (double) meteredLongExposure * meteredLongIso);

        boolean stableBrightNoFlicker = flicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE
                && meteredLongExposure <= ONE_SECOND_NS / 200
                && meteredLongIso <= Math.max(
                        isoRange.getLower() + 50,
                        (int) Math.round(isoRange.getLower() * 1.5));

        long desiredShortExposure;
        int desiredShortIso;
        if (stableBrightNoFlicker) {
            // Stable daylight may spend the 3 EV bracket in shutter time. The LONG
            // image itself remains the live AE result from the paired repeating burst.
            desiredShortExposure = Math.max(
                    minExposure, meteredLongExposure / (long) HDR_BRACKET_RATIO);
            desiredShortIso = solveIsoForProduct(
                    targetLongProduct / HDR_BRACKET_RATIO, desiredShortExposure);
        } else {
            // 50/60-Hz and unknown/PWM lighting use exactly the AE LONG integration
            // window for SHORT as well; only gain is reduced. This preserves temporal
            // phase while letting Camera2 AUTO antibanding choose the real safe shutter.
            desiredShortExposure = meteredLongExposure;
            desiredShortIso = solveIsoForProduct(
                    targetLongProduct / HDR_BRACKET_RATIO, desiredShortExposure);
        }
        desiredShortExposure = clampExposure(desiredShortExposure);
        desiredShortIso = clampIso(desiredShortIso);

        long now = System.nanoTime();
        double changeEv = lastAppliedAutoLongProduct > 0.0
                ? Math.abs(Math.log(targetLongProduct / lastAppliedAutoLongProduct) / Math.log(2.0))
                : Double.POSITIVE_INFINITY;
        double shutterChangeEv = autoShortExposureNs > 0
                ? Math.abs(Math.log(desiredShortExposure / (double) autoShortExposureNs) / Math.log(2.0))
                : Double.POSITIVE_INFINITY;
        boolean strategyChanged = stableBrightNoFlicker != lastAppliedAutoStableBright;
        boolean desiredShortChanged = desiredShortExposure != autoShortExposureNs
                || desiredShortIso != autoShortIso;
        boolean intervalReady = force || lastAutoShortApplyNs == 0L
                || now - lastAutoShortApplyNs >= AUTO_SHORT_UPDATE_MIN_INTERVAL_NS;
        boolean meaningfulChange = changeEv >= AUTO_UPDATE_HYSTERESIS_EV
                || shutterChangeEv >= AUTO_SHUTTER_HYSTERESIS_EV
                || strategyChanged;
        boolean applyShort = desiredShortChanged && intervalReady && (force || meaningfulChange);

        autoLongExposureNs = meteredLongExposure;
        autoLongIso = meteredLongIso;
        sceneFlicker = flicker;
        if (applyShort) {
            autoShortExposureNs = desiredShortExposure;
            autoShortIso = desiredShortIso;
            lastAppliedAutoLongProduct = targetLongProduct;
            lastAppliedAutoStableBright = stableBrightNoFlicker;
            lastAutoShortApplyNs = now;
        } else if (lastAppliedAutoLongProduct < 0.0) {
            lastAppliedAutoLongProduct = targetLongProduct;
            lastAppliedAutoStableBright = stableBrightNoFlicker;
        }

        boolean notifyUi = force || applyShort || lastAutoUiNotifyNs == 0L
                || now - lastAutoUiNotifyNs >= AUTO_UI_NOTIFY_INTERVAL_NS;
        if (notifyUi) {
            double actualLongProduct = Math.max(1.0, (double) autoLongExposureNs * autoLongIso);
            double actualShortProduct = Math.max(1.0, (double) autoShortExposureNs * autoShortIso);
            double bracketEv = Math.log(actualLongProduct / actualShortProduct) / Math.log(2.0);
            listener.onAutoHdrSettings(
                    autoShortExposureNs,
                    autoShortIso,
                    autoLongExposureNs,
                    autoLongIso,
                    flickerLabel(sceneFlicker),
                    bracketEv);
            lastAutoUiNotifyNs = now;
        }
        return applyShort;
    }

    private int solveIsoForProduct(double exposureProduct, long exposureNs) {
        return clampIso((int) Math.round(exposureProduct / Math.max(1.0, exposureNs)));
    }

    private boolean recomputeManualFlickerSafetyLocked() {
        long oldShortExposure = manualEffectiveShortExposureNs;
        long oldLongExposure = manualEffectiveLongExposureNs;
        int oldShortIso = manualEffectiveShortIso;
        int oldLongIso = manualEffectiveLongIso;
        boolean oldSafety = manualFlickerSafetyApplied;

        boolean safeTiming = sceneFlicker != CaptureResult.STATISTICS_SCENE_FLICKER_NONE;
        if (!safeTiming) {
            manualEffectiveShortExposureNs = shortExposureNs;
            manualEffectiveLongExposureNs = longExposureNs;
            manualEffectiveShortIso = manualIso;
            manualEffectiveLongIso = manualIso;
            manualFlickerSafetyApplied = false;
        } else {
            double requestedRatio = Math.max(
                    1.0,
                    Math.min(32.0, longExposureNs / (double) Math.max(1L, shortExposureNs)));
            double targetLongProduct = Math.max(1.0, (double) longExposureNs * manualIso);
            long commonExposure = chooseManualFlickerSafeExposureLocked(longExposureNs, sceneFlicker);
            manualEffectiveShortExposureNs = commonExposure;
            manualEffectiveLongExposureNs = commonExposure;
            manualEffectiveLongIso = solveIsoForProduct(targetLongProduct, commonExposure);
            manualEffectiveShortIso = solveIsoForProduct(
                    targetLongProduct / requestedRatio, commonExposure);
            manualFlickerSafetyApplied = true;
        }

        double longProduct = Math.max(
                1.0, (double) manualEffectiveLongExposureNs * manualEffectiveLongIso);
        double shortProduct = Math.max(
                1.0, (double) manualEffectiveShortExposureNs * manualEffectiveShortIso);
        manualEffectiveBracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);

        return oldShortExposure != manualEffectiveShortExposureNs
                || oldLongExposure != manualEffectiveLongExposureNs
                || oldShortIso != manualEffectiveShortIso
                || oldLongIso != manualEffectiveLongIso
                || oldSafety != manualFlickerSafetyApplied;
    }

    private long chooseManualFlickerSafeExposureLocked(long requestedLongNs, int flicker) {
        long requested = clampExposure(requestedLongNs);
        long period = 0L;
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) {
            period = 10_000_000L;
        } else if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) {
            period = 8_333_333L;
        }
        if (period == 0L) {
            // Unknown/PWM lighting cannot be phase-solved from Camera2. Matching the
            // SHORT/LONG integration window still removes the rejected mixed-shutter
            // schedule that made the two HDR members sample different temporal windows.
            return requested;
        }

        long maxWindow = Math.max(manualFrameDurationNs, requested);
        long maxPeriods = Math.max(1L, maxWindow / period);
        long desiredPeriods = Math.max(1L, Math.round(requested / (double) period));
        long periods = Math.min(maxPeriods, desiredPeriods);
        return clampExposure(periods * period);
    }

    private String manualSafetySummaryLocked() {
        return (manualFlickerSafetyApplied ? "MANUAL_SAFE" : "MANUAL_EXACT")
                + " requested=" + exposureText(shortExposureNs) + "/" + exposureText(longExposureNs)
                + " ISO" + manualIso
                + " actual=" + exposureText(manualEffectiveShortExposureNs) + " ISO" + manualEffectiveShortIso
                + "/" + exposureText(manualEffectiveLongExposureNs) + " ISO" + manualEffectiveLongIso
                + String.format(Locale.US, " %.1fEV", manualEffectiveBracketEv)
                + " flicker=" + flickerLabel(sceneFlicker);
    }

    private long activeShortExposureNs() {
        return autoHdrExposure ? autoShortExposureNs : manualEffectiveShortExposureNs;
    }

    private long activeLongExposureNs() {
        return autoHdrExposure ? autoLongExposureNs : manualEffectiveLongExposureNs;
    }

    private int activeShortIso() {
        return autoHdrExposure ? autoShortIso : manualEffectiveShortIso;
    }

    private int activeLongIso() {
        return autoHdrExposure ? autoLongIso : manualEffectiveLongIso;
    }

    private static String flickerLabel(int flicker) {
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) return "50Hz";
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) return "60Hz";
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE) return "none";
        return "unknown/PWM-safe";
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

    private static Size choosePrivatePreviewSize(
            StreamConfigurationMap map,
            double nativeAspect,
            boolean aeCanReach60) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return null;
        List<Size> nativeCandidates = new ArrayList<>();
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            double aspectError = Math.abs(landscapeAspect(size) / nativeAspect - 1.0);
            if (area <= 1920L * 1440L && aspectError <= 0.015) {
                nativeCandidates.add(size);
            }
        }
        if (nativeCandidates.isEmpty()) {
            nativeCandidates.addAll(Arrays.asList(sizes));
        }

        boolean anySixty = false;
        if (aeCanReach60) {
            for (Size size : nativeCandidates) {
                long minDuration = outputMinFrameDuration(map, size);
                if (minDuration <= 0 || minDuration <= SIXTY_FPS_DURATION_NS) {
                    anySixty = true;
                    break;
                }
            }
        }

        final boolean preferSixty = anySixty;
        final double targetArea = 1440.0 * 1080.0;
        return nativeCandidates.stream()
                .min(Comparator.comparingDouble(size -> {
                    double aspectError = Math.abs(landscapeAspect(size) / nativeAspect - 1.0);
                    long minDuration = outputMinFrameDuration(map, size);
                    boolean canSixty = aeCanReach60
                            && (minDuration <= 0 || minDuration <= SIXTY_FPS_DURATION_NS);
                    double fpsPenalty = preferSixty && !canSixty ? 100.0 : 0.0;
                    double area = (double) size.getWidth() * size.getHeight();
                    double areaPenalty = Math.abs(Math.log(Math.max(1.0, area) / targetArea));
                    return fpsPenalty + aspectError * 1000.0 + areaPenalty;
                }))
                .orElse(sizes[0]);
    }

    private static Size chooseJpegSize(Size[] sizes, double nativeAspect) {
        if (sizes == null || sizes.length == 0) return null;
        final long maxPreferredArea = 13_500_000L;
        Size best = null;
        long bestArea = -1;
        for (Size size : sizes) {
            double aspectError = Math.abs(landscapeAspect(size) / nativeAspect - 1.0);
            long area = (long) size.getWidth() * size.getHeight();
            if (aspectError <= 0.015 && area <= maxPreferredArea && area > bestArea) {
                best = size;
                bestArea = area;
            }
        }
        if (best != null) return best;
        final double targetArea = 12_600_000.0;
        return Arrays.stream(sizes)
                .min(Comparator.comparingDouble(size -> {
                    double aspectError = Math.abs(landscapeAspect(size) / nativeAspect - 1.0);
                    double area = (double) size.getWidth() * size.getHeight();
                    return aspectError * 1000.0
                            + Math.abs(Math.log(Math.max(1.0, area) / targetArea));
                }))
                .orElse(sizes[0]);
    }

    private static Size chooseLargest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Arrays.stream(sizes)
                .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                .orElse(sizes[0]);
    }

    private static long outputMinFrameDuration(StreamConfigurationMap map, Size size) {
        try {
            return map.getOutputMinFrameDuration(SurfaceTexture.class, size);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static double landscapeAspect(Size size) {
        int wide = Math.max(size.getWidth(), size.getHeight());
        int tall = Math.min(size.getWidth(), size.getHeight());
        return wide / (double) Math.max(1, tall);
    }

    private static int maxAeFps(Range<Integer>[] ranges) {
        int max = 0;
        if (ranges == null) return max;
        for (Range<Integer> range : ranges) {
            max = Math.max(max, range.getUpper());
        }
        return max;
    }

    private static Range<Integer> chooseAeFpsRange(Range<Integer>[] ranges, int target) {
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> exact = null;
        Range<Integer> containing = null;
        for (Range<Integer> range : ranges) {
            if (range.getLower() == target && range.getUpper() == target) {
                exact = range;
                break;
            }
            if (range.contains(target)) {
                if (containing == null
                        || range.getLower() > containing.getLower()
                        || range.getLower().equals(containing.getLower())
                        && range.getUpper() < containing.getUpper()) {
                    containing = range;
                }
            }
        }
        if (exact != null) return exact;
        if (containing != null) return containing;
        return Arrays.stream(ranges)
                .max(Comparator.comparingInt(r -> r.getUpper()))
                .orElse(ranges[0]);
    }

    private static boolean supportsSrgbTonemap(CameraCharacteristics c) {
        int[] modes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        return contains(modes, CaptureRequest.TONEMAP_MODE_PRESET_CURVE);
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

    private static String rangeText(Range<Integer> range) {
        if (range == null) return "auto";
        return range.getLower().equals(range.getUpper())
                ? range.getUpper().toString()
                : range.getLower() + "-" + range.getUpper();
    }

    static String exposureText(long ns) {
        if (ns <= 0) return "?";
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 0.5) return String.format(Locale.US, "%.2fs", seconds);
        double reciprocal = 1.0 / seconds;
        if (reciprocal >= 2.0) return String.format(Locale.US, "1/%.0fs", reciprocal);
        return String.format(Locale.US, "%.3fs", seconds);
    }
}
