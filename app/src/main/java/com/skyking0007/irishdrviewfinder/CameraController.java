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
import android.hardware.camera2.params.TonemapCurve;
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
    private static final String TAG_METER = "P_METER";
    private static final String TAG_CAPTURE_SHORT = "C_SHORT";
    private static final String TAG_CAPTURE_LONG = "C_LONG";
    private static final long ONE_SECOND_NS = 1_000_000_000L;
    private static final long SIXTY_FPS_DURATION_NS = 16_666_666L;
    private static final long THIRTY_FPS_DURATION_NS = 33_333_333L;
    private static final float DISPLAY_BRIGHTNESS_MIN_EV = -5.0f;
    private static final float DISPLAY_BRIGHTNESS_MAX_EV = 2.0f;
    private static final int AUTO_METER_MIN_FRAMES = 4;
    private static final int AUTO_METER_MAX_FRAMES = 12;
    private static final int AUTO_METER_STABLE_FRAMES = 3;
    private static final double AUTO_METER_STABLE_EV = 0.18;
    private static final double AUTO_BRACKET_DEFAULT_EV = 3.0;
    private static final double AUTO_BRACKET_MIN_EV = 2.0;
    private static final double AUTO_BRACKET_MAX_EV = 5.0;
    private static final double MANUAL_BRACKET_MAX_EV = 6.0;
    private static final double MANUAL_EXTRA_HEADROOM_EV = 0.25;
    private static final double LONG_CLIP_TRIGGER_FRACTION = 0.005;
    private static final double AUTO_SHORT_CLIP_TARGET = 0.0025;
    private static final double MANUAL_SHORT_CLIP_TARGET = 0.0015;
    private static final double SHORT_CLIP_RELEASE_FRACTION = 0.0005;
    private static final double BRACKET_STEP_UP_EV = 0.35;
    private static final double BRACKET_STEP_DOWN_EV = 0.15;
    private static final double AUTO_MID_HYSTERESIS_EV = 0.10;
    private static final double AUTO_MID_MAX_STEP_EV = 0.30;
    private static final long ADAPTIVE_PAIR_UPDATE_MIN_NS = 180_000_000L;
    private static final int DEFAULT_POST_RAW_BOOST = 100;
    private static final int MAX_SRGB_CURVE_POINTS = 64;
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
    // Immutable per-shutter still controls. Live adaptive scene statistics may continue
    // after the still inputs are acquired, but they must never mutate an in-flight HDR set.
    private long captureShortExposureNs;
    private long captureLongExposureNs;
    private int captureShortIso;
    private int captureLongIso;
    private int capturePostRawBoost = DEFAULT_POST_RAW_BOOST;
    private long captureBeginRealtimeNs;
    private float displayBrightnessEv;
    private float captureDisplayBrightnessEv;
    private long shortExposureNs = ONE_SECOND_NS / 480;
    private long longExposureNs = ONE_SECOND_NS / 60;
    private int manualIso = 400;
    private long manualEffectiveShortExposureNs = ONE_SECOND_NS / 480;
    private long manualEffectiveLongExposureNs = ONE_SECOND_NS / 60;
    private int manualEffectiveShortIso = 100;
    private int manualEffectiveLongIso = 400;
    private boolean manualFlickerSafetyApplied;
    private double manualEffectiveBracketEv = 3.0;
    private double manualAchievedBrightnessEv;
    private boolean autoHdrExposure = true;
    private long autoShortExposureNs = ONE_SECOND_NS / 120;
    private long autoLongExposureNs = ONE_SECOND_NS / 60;
    private int autoShortIso = 100;
    private int autoLongIso = 400;
    private int autoPostRawBoost = DEFAULT_POST_RAW_BOOST;
    private int sceneFlicker = FLICKER_UNKNOWN;
    private boolean autoMetering;
    private int autoMeterFrames;
    private int autoMeterStableFrames;
    private double autoMeterLastProduct = -1.0;
    private long lastAutoAnchorNs;
    private double autoSceneBaseLongProduct = -1.0;
    private double autoTargetBaseMidLuma = -1.0;
    private double autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
    private double manualAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
    private double manualBracketFloorEv = AUTO_BRACKET_MIN_EV;
    private long lastAdaptiveStatsFrame = -1L;
    private long lastAdaptivePairUpdateNs;
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
    private long previewMinFrameDurationNs;
    private long manualFrameDurationNs = THIRTY_FPS_DURATION_NS;
    private int targetPreviewFps = 30;
    private Range<Integer> aeFpsRange;
    private boolean srgbTonemapSupported;
    private TonemapCurve srgbTonemapCurve;
    private boolean noiseReductionOffSupported;
    private boolean edgeOffSupported;
    private Range<Integer> postRawBoostRange;
    private boolean sixtyFpsCapable;
    private boolean allowCropped60Fps;
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
            if (previewMode != PreviewMode.NORMAL && autoHdrExposure && !haveAeSample) {
                startAutoMeteringLocked();
            } else {
                applyPreviewRepeatingLocked();
            }
        });
    }

    void setDisplayBrightnessEv(float ev) {
        final float requestedEv = clampBrightnessEv(ev);
        cameraHandler.post(() -> {
            displayBrightnessEv = requestedEv;
            RuntimeLogger.event(
                    "BRIGHTNESS_EV",
                    String.format(
                            Locale.US,
                            "%+.1fEV owner=LONG_APPEARANCE_SHORT_ADAPTIVE mode=%s",
                            displayBrightnessEv,
                            autoHdrExposure ? "AUTO" : "MANUAL_SAFE"));
            if (characteristics == null) return;
            if (autoHdrExposure) {
                if (!haveAeSample) return;
                deriveAdaptiveAutoPairLocked();
                publishAutoHdrSettingsLocked("BRIGHTNESS_UPDATE", true);
            } else {
                recomputeManualAdaptivePairLocked();
                RuntimeLogger.event("MANUAL_BRIGHTNESS_UPDATE", manualSafetySummaryLocked());
                listener.onStatus(manualSafetySummaryLocked());
            }
            if (previewMode != PreviewMode.NORMAL && captureSession != null
                    && !stillSessionActive && !autoMetering) {
                applyPreviewRepeatingLocked();
            }
        });
    }

    void setAutoHdrExposure(boolean enabled) {
        cameraHandler.post(() -> {
            autoHdrExposure = enabled;
            autoMetering = false;
            lastAdaptiveStatsFrame = -1L;
            lastAdaptivePairUpdateNs = 0L;
            if (enabled) {
                autoTargetBaseMidLuma = -1.0;
                if (haveAeSample && autoSceneBaseLongProduct <= 0.0) {
                    autoSceneBaseLongProduct = Math.max(1.0, (double) lastAeExposureNs * lastAeIso);
                }
            } else {
                resetManualAdaptiveBracketLocked();
                recomputeManualAdaptivePairLocked();
                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
            }
            RuntimeLogger.event("HDR_MODE", enabled ? "AUTO_ADAPTIVE_HDR" : manualSafetySummaryLocked());
            if (previewMode != PreviewMode.NORMAL) {
                if (enabled && !haveAeSample) startAutoMeteringLocked();
                else applyPreviewRepeatingLocked();
            }
        });
    }

    void setAllowCropped60Fps(boolean enabled) {
        cameraHandler.post(() -> {
            if (allowCropped60Fps == enabled && characteristics == null) return;
            allowCropped60Fps = enabled;
            RuntimeLogger.event("FPS_CROP_POLICY", enabled ? "ALLOW_CROPPED_60" : "FOV_SAFE");
            if (characteristics == null) return;
            applyFpsPolicyLocked(true);
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
            resetManualAdaptiveBracketLocked();
            recomputeManualAdaptivePairLocked();
            if (!autoHdrExposure) {
                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
                RuntimeLogger.event("MANUAL_SETTINGS", manualSafetySummaryLocked());
                applyPreviewRepeatingLocked();
            }
        });
    }

    void onHdrSceneStats(HdrGlView.SceneStats stats) {
        if (stats == null) return;
        cameraHandler.post(() -> processHdrSceneStatsLocked(stats));
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
            autoMetering = false;
            autoMeterFrames = 0;
            autoMeterStableFrames = 0;
            autoMeterLastProduct = -1.0;
            lastAutoAnchorNs = 0L;
            autoSceneBaseLongProduct = -1.0;
            autoTargetBaseMidLuma = -1.0;
            autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
            lastAdaptiveStatsFrame = -1L;
            lastAdaptivePairUpdateNs = 0L;
            autoPostRawBoost = DEFAULT_POST_RAW_BOOST;
            sceneFlicker = FLICKER_UNKNOWN;
            resetManualAdaptiveBracketLocked();
            recomputeManualAdaptivePairLocked();
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
        boolean exactSixtyAe = hasExactAeFpsRange(ranges, 60);
        previewSize = choosePrivatePreviewSize(map, nativeAspect, exactSixtyAe);
        jpegSize = chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG), nativeAspect);
        if (previewSize == null || jpegSize == null) {
            throw new IllegalStateException("Required PRIVATE/JPEG output size missing");
        }

        previewMinFrameDurationNs = outputMinFrameDuration(map, previewSize);
        boolean streamCanReach60 = previewMinFrameDurationNs <= 0
                || previewMinFrameDurationNs <= 16_666_667L;
        sixtyFpsCapable = exactSixtyAe && streamCanReach60;
        // FOV SAFE is deterministic 30 fps. The previous optimistic 60->30
        // transition visibly changed sensor readout/FOV on-device. Only the explicit
        // 60 FPS CROP toggle is allowed to request the cropped 60-fps sensor mode.
        targetPreviewFps = allowCropped60Fps && sixtyFpsCapable ? 60 : 30;
        manualFrameDurationNs = targetPreviewFps >= 60
                ? SIXTY_FPS_DURATION_NS : THIRTY_FPS_DURATION_NS;
        if (targetPreviewFps < 60 && previewMinFrameDurationNs > 0) {
            manualFrameDurationNs = Math.max(manualFrameDurationNs, previewMinFrameDurationNs);
        }
        aeFpsRange = chooseAeFpsRange(ranges, targetPreviewFps);
        srgbTonemapSupported = supportsSrgbTonemap(characteristics);
        srgbTonemapCurve = srgbTonemapSupported ? buildSrgbTonemapCurve(characteristics) : null;
        noiseReductionOffSupported = contains(
                characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        edgeOffSupported = contains(
                characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                CaptureRequest.EDGE_MODE_OFF);
        postRawBoostRange = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE);
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
                            if (!autoHdrExposure) {
                                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
                            }
                            RuntimeLogger.event(
                                    "SESSION_CONFIGURED",
                                    "preview-only target=" + targetPreviewFps
                                            + " aeFps=" + rangeText(aeFpsRange)
                                            + " preview=" + previewSize
                                            + " crop60=" + allowCropped60Fps
                                            + " nrOff=" + noiseReductionOffSupported
                                            + " edgeOff=" + edgeOffSupported
                                            + " sRgbContrast=" + srgbTonemapSupported);
                            if (autoHdrExposure && previewMode != PreviewMode.NORMAL
                                    && !haveAeSample) {
                                startAutoMeteringLocked();
                            } else {
                                applyPreviewRepeatingLocked();
                            }
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
                if (autoHdrExposure && (!haveAeSample || autoMetering)) {
                    startAutoMeteringLocked();
                    return;
                }
                long activeShortNs = activeShortExposureNs();
                long activeLongNs = activeLongExposureNs();
                int activeShortIso = activeShortIso();
                int activeLongIso = activeLongIso();
                int postRawBoost = autoHdrExposure ? autoPostRawBoost : DEFAULT_POST_RAW_BOOST;
                CaptureRequest shortRequest = buildManualPreviewRequest(
                        TAG_SHORT, activeShortNs, activeShortIso, postRawBoost);
                CaptureRequest longRequest = buildManualPreviewRequest(
                        TAG_LONG, activeLongNs, activeLongIso, postRawBoost);
                captureSession.setRepeatingBurst(
                        Arrays.asList(shortRequest, longRequest),
                        previewCaptureCallback,
                        cameraHandler);
                listener.onStatus(
                        (previewMode == PreviewMode.HDR ? "HDR" : "SPLIT")
                                + (autoHdrExposure
                                        ? " AUTO ANCHORED"
                                        : manualFlickerSafetyApplied ? " MANUAL SAFE" : " MANUAL")
                                + " paired preview  short=" + exposureText(activeShortNs) + " ISO" + activeShortIso
                                + "  long=" + exposureText(activeLongNs) + " ISO" + activeLongIso
                                + (autoHdrExposure ? " boost=" + postRawBoost + "%"
                                        : String.format(Locale.US, "  bracket=%.1fEV", manualEffectiveBracketEv))
                                + "  flicker=" + flickerLabel(sceneFlicker)
                                + "  target=" + targetPreviewFps + " sensor fps");
            }
        } catch (Throwable t) {
            RuntimeLogger.error("REPEATING_FAIL", t);
            listener.onStatus("Repeating request failed: " + t.getMessage());
        }
    }

    private CaptureRequest buildManualPreviewRequest(
            String tag, long exposureNs, int iso, int postRawBoost) throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        configureManualRequest(builder, exposureNs, iso, postRawBoost, true);
        // Keep the exact same FPS range on SHORT and LONG. In forced-60 mode this
        // is explicitly [60,60], and SENSOR_FRAME_DURATION is 16,666,666 ns.
        if (aeFpsRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeFpsRange);
        }
        configurePreviewRotateAndCrop(builder);
        builder.setTag(tag);
        return builder.build();
    }

    private CaptureRequest buildMeterPreviewRequest() throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        configureAutoExposureRequest(builder);
        builder.setTag(TAG_METER);
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
        configureProcessingControls(builder);
        configureSrgbTonemap(builder);
    }

    private void configureManualRequest(
            CaptureRequest.Builder builder,
            long exposureNs,
            int iso,
            int postRawBoost,
            boolean enforcePreviewCadence) {
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
        if (postRawBoostRange != null) {
            builder.set(
                    CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                    clampPostRawBoost(postRawBoost));
        }
        Long maxFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        long frameDuration;
        if (enforcePreviewCadence && targetPreviewFps >= 60) {
            // True-60 belongs only to the live PRIVATE preview session. Effective
            // preview exposures are already capped to this duration before request build.
            exposure = Math.min(exposure, SIXTY_FPS_DURATION_NS);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            frameDuration = SIXTY_FPS_DURATION_NS;
        } else if (enforcePreviewCadence) {
            frameDuration = Math.max(manualFrameDurationNs, exposure);
        } else {
            // Full RAW/JPEG still capture is a separate session and must never inherit
            // the optional cropped-60 preview cadence. Preserve V1.4.4's >=30-fps
            // floor while always allowing the selected exposure itself.
            frameDuration = Math.max(THIRTY_FPS_DURATION_NS, exposure);
        }
        if (maxFrame != null) frameDuration = Math.min(frameDuration, maxFrame);
        frameDuration = Math.max(frameDuration, exposure);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
        configureProcessingControls(builder);
        configureSrgbTonemap(builder);
    }

    private void configurePreviewRotateAndCrop(CaptureRequest.Builder builder) {
        if (Build.VERSION.SDK_INT < 31 || characteristics == null) return;
        int[] modes = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
        if (contains(modes, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)) {
            builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
        }
    }

    private void configureProcessingControls(CaptureRequest.Builder builder) {
        if (noiseReductionOffSupported) {
            builder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        }
        if (edgeOffSupported) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        }
    }

    private void configureSrgbTonemap(CaptureRequest.Builder builder) {
        if (!srgbTonemapSupported || srgbTonemapCurve == null) return;
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
        builder.set(CaptureRequest.TONEMAP_CURVE, srgbTonemapCurve);
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
                    else if (TAG_METER.equals(tag)) kind = FrameMeta.METER;
                    else return;

                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (timestamp == null || exposure == null || iso == null) return;

                    // Hidden AE meter frames are transient control probes, not live
                    // steady-preview cadence/FOV evidence. Counting them poisoned the
                    // 60-fps watchdog and could cause a visible 60->30 crop transition.
                    if (!FrameMeta.METER.equals(kind)) {
                        updateCaptureResultFpsLocked();
                        updateFovEvidenceLocked(result);
                    }
                    Integer observedFlicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
                    if (!autoHdrExposure
                            && previewMode != PreviewMode.NORMAL
                            && observedFlicker != null
                            && (observedFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ
                                    || observedFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ)
                            && observedFlicker != sceneFlicker) {
                        sceneFlicker = observedFlicker;
                        if (recomputeManualAdaptivePairLocked()) {
                            RuntimeLogger.event("MANUAL_FLICKER", manualSafetySummaryLocked());
                            applyPreviewRepeatingLocked();
                        }
                    }

                    if (FrameMeta.NORMAL.equals(kind)) {
                        if (observedFlicker != null) sceneFlicker = observedFlicker;
                        commitAutoAnchorFromResultLocked(result, exposure, iso, sceneFlicker, false);
                    } else if (FrameMeta.METER.equals(kind)) {
                        if (observedFlicker != null) sceneFlicker = observedFlicker;
                        processAutoMeterResultLocked(result, exposure, iso, sceneFlicker);
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
        // Freeze the exact adaptive pair before closing the preview session. Live scene
        // statistics may continue to arrive while the still session is configured, but
        // from this point forward they cannot mutate this in-flight HDR set.
                autoMetering = false;
        captureShortExposureNs = activeShortExposureNs();
        captureLongExposureNs = activeLongExposureNs();
        captureShortIso = activeShortIso();
        captureLongIso = activeLongIso();
        capturePostRawBoost = autoHdrExposure ? autoPostRawBoost : DEFAULT_POST_RAW_BOOST;
        captureDisplayBrightnessEv = displayBrightnessEv;
        captureBeginRealtimeNs = System.nanoTime();
        String captureId = "IrisHDR_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        RuntimeLogger.event(
                "CAPTURE_BEGIN",
                captureId
                        + " frozen short=" + exposureText(captureShortExposureNs) + " ISO" + captureShortIso
                        + " long=" + exposureText(captureLongExposureNs) + " ISO" + captureLongIso
                        + " boost=" + capturePostRawBoost + "%"
                        + String.format(Locale.US, " brightnessIntent=%+.1fEV", captureDisplayBrightnessEv)
                        + " mode=" + (autoHdrExposure ? "AUTO" : manualSafetySummaryLocked()));
        listener.onStatus("Capturing matched SHORT/LONG RAW + JPEG set…");
        captureSaver = new CaptureSetSaver(
                context,
                characteristics,
                cameraId,
                captureId,
                jpegOrientationDegrees,
                captureDisplayBrightnessEv,
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
            configureManualRequest(
                    shortBuilder, captureShortExposureNs, captureShortIso,
                    capturePostRawBoost, false);
            shortBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            shortBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees);
            shortBuilder.setTag(TAG_CAPTURE_SHORT);

            CaptureRequest.Builder longBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            longBuilder.addTarget(rawReader.getSurface());
            longBuilder.addTarget(jpegReader.getSurface());
            configureManualRequest(
                    longBuilder, captureLongExposureNs, captureLongIso,
                    capturePostRawBoost, false);
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
        long acquiredMs = captureBeginRealtimeNs > 0L
                ? Math.max(0L, (System.nanoTime() - captureBeginRealtimeNs) / 1_000_000L) : -1L;
        RuntimeLogger.event("CAPTURE_INPUTS", captureId + " acquiredMs=" + acquiredMs);
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
        long totalMs = captureBeginRealtimeNs > 0L
                ? Math.max(0L, (System.nanoTime() - captureBeginRealtimeNs) / 1_000_000L) : -1L;
        RuntimeLogger.event(
                success ? "CAPTURE_DONE" : "CAPTURE_FAIL",
                captureId + " totalMs=" + totalMs + " " + message);
        captureBeginRealtimeNs = 0L;
        listener.onCaptureFinished(captureId, success, message);
        applyPreviewRepeatingLocked();
    }

    private void closeDeviceLocked() {
                autoMetering = false;
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

        if (targetPreviewFps < 60 || fovFallbackApplied) return;
        if (allowCropped60Fps) {
            if (!fovDecisionLogged) {
                fovDecisionLogged = true;
                RuntimeLogger.event(
                        "FOV_OVERRIDE",
                        "user allows cropped 60fps; preview may be tighter than full RAW/JPEG capture"
                                + " physicalSensorCrop=" + rectText(lastPhysicalSensorCropRegion)
                                + " crop=" + rectText(lastReportedCropRegion)
                                + " zoom=" + (lastReportedZoomRatio == null ? "?" : lastReportedZoomRatio));
            }
            return;
        }
        if (fovDecisionLogged) return;
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
        if (targetPreviewFps < 60 || fovFallbackApplied || allowCropped60Fps) return;
        fovFallbackApplied = true;
        targetPreviewFps = 30;
        manualFrameDurationNs = Math.max(THIRTY_FPS_DURATION_NS, previewMinFrameDurationNs);
        Range<Integer>[] ranges = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        aeFpsRange = chooseAeFpsRange(ranges, 30);
        haveAeSample = false;
        lastAutoAnchorNs = 0L;
        autoSceneBaseLongProduct = -1.0;
        autoTargetBaseMidLuma = -1.0;
        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
        lastAdaptiveStatsFrame = -1L;
        lastAdaptivePairUpdateNs = 0L;
        autoMetering = false;
        resetManualAdaptiveBracketLocked();
        recomputeManualAdaptivePairLocked();
        RuntimeLogger.event("FOV_FALLBACK", reason + "; recreating preview session at 30fps");
        listener.onStatus("60fps FOV parity unavailable/cropped; switching to full-FOV 30fps");
        if (captureSession != null && !stillSessionActive) {
            closeSessionLocked();
            cameraHandler.postDelayed(this::startPreviewSessionLocked, 50);
        }
    }

    private void applyFpsPolicyLocked(boolean recreateSession) {
        Range<Integer>[] ranges = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (allowCropped60Fps && sixtyFpsCapable) {
            targetPreviewFps = 60;
            manualFrameDurationNs = SIXTY_FPS_DURATION_NS;
            aeFpsRange = chooseAeFpsRange(ranges, 60);
            fovFallbackApplied = false;
            fovDecisionLogged = true;
        } else {
            // OFF means stable full-FOV 30 fps from the first request. Do not enter a
            // cropped 60-fps mode and later fall back, because that transition is
            // itself visible in the viewfinder.
            targetPreviewFps = 30;
            manualFrameDurationNs = Math.max(THIRTY_FPS_DURATION_NS, previewMinFrameDurationNs);
            aeFpsRange = chooseAeFpsRange(ranges, 30);
            resetFovEvidenceLocked();
            if (allowCropped60Fps && !sixtyFpsCapable) {
                listener.onStatus("Exact 60/60 fps is unavailable for this camera/PRIVATE stream; using stable 30 fps");
            }
        }
        haveAeSample = false;
        lastAutoAnchorNs = 0L;
        autoSceneBaseLongProduct = -1.0;
        autoTargetBaseMidLuma = -1.0;
        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
        lastAdaptiveStatsFrame = -1L;
        lastAdaptivePairUpdateNs = 0L;
        autoMetering = false;
        resetManualAdaptiveBracketLocked();
        recomputeManualAdaptivePairLocked();
        RuntimeLogger.event(
                "FPS_POLICY",
                (allowCropped60Fps ? "ALLOW_CROPPED_60" : "FOV_SAFE")
                        + " target=" + targetPreviewFps
                        + " ae=" + rangeText(aeFpsRange)
                        + " frameDuration=" + manualFrameDurationNs);
        if (recreateSession && cameraDevice != null && previewSurfaceConfigured && !stillSessionActive) {
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
                    if (allowCropped60Fps) {
                        // The user explicitly selected the cropped 60-fps sensor mode.
                        // Report real delivery, but never mutate the requested cadence/FOV
                        // behind their back. Meter transitions are excluded above.
                        RuntimeLogger.event(
                                "FPS_FORCE60_UNDERDELIVERY",
                                "steady CaptureResult fps="
                                        + String.format(Locale.US, "%.1f", captureResultFps)
                                        + "; keeping explicit target=60 [60,60]");
                        sixtyFpsUnderDeliveryWindows = 0;
                    } else {
                        targetPreviewFps = 30;
                        manualFrameDurationNs = Math.max(THIRTY_FPS_DURATION_NS, previewMinFrameDurationNs);
                        Range<Integer>[] ranges = characteristics == null ? null
                                : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        aeFpsRange = chooseAeFpsRange(ranges, 30);
                        sixtyFpsUnderDeliveryWindows = 0;
                        haveAeSample = false;
                        lastAutoAnchorNs = 0L;
                        autoSceneBaseLongProduct = -1.0;
                        autoTargetBaseMidLuma = -1.0;
                        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
                        lastAdaptiveStatsFrame = -1L;
                        lastAdaptivePairUpdateNs = 0L;
                        autoMetering = false;
                        resetManualAdaptiveBracketLocked();
                        recomputeManualAdaptivePairLocked();
                        listener.onStatus(
                                "60 fps capability under-delivered at "
                                        + String.format(Locale.US, "%.1f", captureResultFps)
                                        + " CaptureResult fps; switching live target to 30 fps");
                        if (captureSession != null && !stillSessionActive) {
                            if (autoHdrExposure && previewMode != PreviewMode.NORMAL) startAutoMeteringLocked();
                            else applyPreviewRepeatingLocked();
                        }
                    }
                }
            }
        }
    }

    private void startAutoMeteringLocked() {
        if (!autoHdrExposure || previewMode == PreviewMode.NORMAL || stillSessionActive
                || cameraDevice == null || captureSession == null || previewSurface == null) return;
        if (haveAeSample) {
            applyPreviewRepeatingLocked();
            return;
        }
        try {
            autoMetering = true;
            // Bootstrap only. Once one clean AE anchor is established, the HDR repeating
            // burst stays live continuously and scene adaptation comes from the displayed
            // SHORT/LONG pair statistics instead of periodic AE request takeovers.
            resetCaptureResultFpsLocked();
            autoMeterFrames = 0;
            autoMeterStableFrames = 0;
            autoMeterLastProduct = -1.0;
            captureSession.setRepeatingRequest(
                    buildMeterPreviewRequest(), previewCaptureCallback, cameraHandler);
            RuntimeLogger.event(
                    "AUTO_METER_BOOTSTRAP",
                    "initial clean AE only target=" + targetPreviewFps + " fps=" + rangeText(aeFpsRange));
            listener.onStatus("AUTO HDR initial scene meter…");
        } catch (Throwable t) {
            autoMetering = false;
            RuntimeLogger.error("AUTO_METER_START_FAIL", t);
            listener.onStatus("AUTO meter failed: " + t.getMessage());
        }
    }

    private void processAutoMeterResultLocked(
            TotalCaptureResult result, long exposureNs, int iso, int flicker) {
        if (!autoMetering) return;
        int boost = resultPostRawBoost(result);
        double totalProduct = Math.max(1.0,
                (double) exposureNs * Math.max(1, iso) * Math.max(1, boost) / 100.0);
        autoMeterFrames++;
        if (autoMeterLastProduct > 0.0) {
            double deltaEv = Math.abs(Math.log(totalProduct / autoMeterLastProduct) / Math.log(2.0));
            if (deltaEv <= AUTO_METER_STABLE_EV) autoMeterStableFrames++;
            else autoMeterStableFrames = 0;
        }
        autoMeterLastProduct = totalProduct;

        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        boolean aeSettled = aeState != null
                && (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                        || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED);
        boolean enough = autoMeterFrames >= AUTO_METER_MIN_FRAMES
                && (autoMeterStableFrames >= AUTO_METER_STABLE_FRAMES || aeSettled);
        if (enough || autoMeterFrames >= AUTO_METER_MAX_FRAMES) {
            commitAutoAnchorFromResultLocked(result, exposureNs, iso, flicker, true);
        }
    }

    private void commitAutoAnchorFromResultLocked(
            TotalCaptureResult result, long exposureNs, int iso, int flicker, boolean finishMeter) {
        boolean firstAnchor = !haveAeSample;
        lastAeExposureNs = clampExposure(exposureNs);
        lastAeIso = clampIso(iso);
        autoPostRawBoost = resultPostRawBoost(result);
        sceneFlicker = flicker;
        haveAeSample = true;
        lastAutoAnchorNs = System.nanoTime();
        autoSceneBaseLongProduct = Math.max(1.0, (double) lastAeExposureNs * lastAeIso);
        autoTargetBaseMidLuma = -1.0;
        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
        deriveAdaptiveAutoPairLocked();
        publishAutoHdrSettingsLocked("AUTO_ANCHOR", finishMeter || firstAnchor);

        if (finishMeter) {
            autoMetering = false;
            resetCaptureResultFpsLocked();
            applyPreviewRepeatingLocked();
        }
    }

    private void processHdrSceneStatsLocked(HdrGlView.SceneStats stats) {
        if (previewMode == PreviewMode.NORMAL || stillSessionActive || autoMetering
                || characteristics == null || captureSession == null) return;
        if (stats.longFrameNumber <= lastAdaptiveStatsFrame) return;
        lastAdaptiveStatsFrame = stats.longFrameNumber;

        double expectedLongProduct = Math.max(1.0,
                (double) activeLongExposureNs() * activeLongIso());
        double staleEv = Math.abs(Math.log(
                Math.max(1.0, stats.longExposureProduct) / expectedLongProduct) / Math.log(2.0));
        if (staleEv > 0.20) return;

        long now = System.nanoTime();
        boolean pairNeedsUpdate = false;
        if (autoHdrExposure) {
            if (!haveAeSample) return;
            double brightnessGain = Math.pow(2.0, clampBrightnessEv(displayBrightnessEv));
            if (autoTargetBaseMidLuma <= 0.0 && stats.longMedianLinear > 0.003f) {
                autoTargetBaseMidLuma = clampDouble(
                        stats.longMedianLinear / Math.max(0.03125, brightnessGain),
                        0.015, 0.45);
                RuntimeLogger.event(
                        "AUTO_LIVE_TARGET",
                        String.format(
                                Locale.US,
                                "baseMid=%.4f currentMid=%.4f brightness=%+.1fEV",
                                autoTargetBaseMidLuma, stats.longMedianLinear, displayBrightnessEv));
            } else if (autoTargetBaseMidLuma > 0.0 && stats.longMedianLinear > 0.002f) {
                double desiredMid = clampDouble(autoTargetBaseMidLuma * brightnessGain, 0.012, 0.80);
                double errorEv = Math.log(desiredMid / stats.longMedianLinear) / Math.log(2.0);
                if (Math.abs(errorEv) > AUTO_MID_HYSTERESIS_EV) {
                    double stepEv = clampDouble(
                            errorEv, -AUTO_MID_MAX_STEP_EV, AUTO_MID_MAX_STEP_EV);
                    autoSceneBaseLongProduct = Math.max(1.0,
                            autoSceneBaseLongProduct * Math.pow(2.0, stepEv));
                    pairNeedsUpdate = true;
                }
            }

            double nextBracket = adaptBracketEvLocked(
                    autoAdaptiveBracketEv, stats, false);
            if (Math.abs(nextBracket - autoAdaptiveBracketEv) >= 0.05) {
                autoAdaptiveBracketEv = nextBracket;
                pairNeedsUpdate = true;
            }

            if (pairNeedsUpdate && (lastAdaptivePairUpdateNs == 0L
                    || now - lastAdaptivePairUpdateNs >= ADAPTIVE_PAIR_UPDATE_MIN_NS)) {
                long oldShortNs = autoShortExposureNs;
                long oldLongNs = autoLongExposureNs;
                int oldShortIso = autoShortIso;
                int oldLongIso = autoLongIso;
                deriveAdaptiveAutoPairLocked();
                if (oldShortNs != autoShortExposureNs || oldLongNs != autoLongExposureNs
                        || oldShortIso != autoShortIso || oldLongIso != autoLongIso) {
                    lastAdaptivePairUpdateNs = now;
                    publishAutoHdrSettingsLocked("AUTO_LIVE_ADAPT", true);
                    applyPreviewRepeatingLocked();
                }
            }
        } else {
            double nextBracket = adaptBracketEvLocked(
                    manualAdaptiveBracketEv, stats, true);
            nextBracket = Math.max(manualBracketFloorEv, nextBracket);
            if (Math.abs(nextBracket - manualAdaptiveBracketEv) >= 0.05
                    && (lastAdaptivePairUpdateNs == 0L
                            || now - lastAdaptivePairUpdateNs >= ADAPTIVE_PAIR_UPDATE_MIN_NS)) {
                manualAdaptiveBracketEv = nextBracket;
                if (recomputeManualAdaptivePairLocked()) {
                    lastAdaptivePairUpdateNs = now;
                    RuntimeLogger.event("MANUAL_LIVE_ADAPT", manualSafetySummaryLocked());
                    applyPreviewRepeatingLocked();
                }
            }
        }
    }

    private double adaptBracketEvLocked(
            double currentEv, HdrGlView.SceneStats stats, boolean manual) {
        double minEv = manual
                ? Math.max(manualBracketFloorEv, AUTO_BRACKET_DEFAULT_EV + MANUAL_EXTRA_HEADROOM_EV)
                : AUTO_BRACKET_DEFAULT_EV;
        double maxEv = manual ? MANUAL_BRACKET_MAX_EV : AUTO_BRACKET_MAX_EV;
        double shortTarget = manual ? MANUAL_SHORT_CLIP_TARGET : AUTO_SHORT_CLIP_TARGET;
        boolean meaningfulLongClip = stats.longMeaningfulClipFraction >= LONG_CLIP_TRIGGER_FRACTION;
        boolean shortFragile = stats.shortDarkFraction > 0.94f
                || stats.overlapSamples < 12
                || stats.overlapErrorEv > 0.50f;

        double next = currentEv;
        if (meaningfulLongClip && stats.shortMeaningfulClipFraction > shortTarget) {
            double qualityMax = shortFragile && stats.longMeaningfulClipFraction < 0.02f
                    ? Math.min(maxEv, 4.5) : maxEv;
            next = Math.min(qualityMax, currentEv + BRACKET_STEP_UP_EV);
        } else if (!meaningfulLongClip
                && stats.shortMeaningfulClipFraction <= SHORT_CLIP_RELEASE_FRACTION
                && currentEv > minEv) {
            next = Math.max(minEv, currentEv - BRACKET_STEP_DOWN_EV);
        }
        return clampDouble(next, minEv, maxEv);
    }

    private void deriveAdaptiveAutoPairLocked() {
        if (characteristics == null || !haveAeSample) return;
        double anchorProduct = Math.max(1.0, (double) lastAeExposureNs * lastAeIso);
        if (autoSceneBaseLongProduct <= 0.0) autoSceneBaseLongProduct = anchorProduct;
        double targetLongProduct = Math.max(1.0,
                autoSceneBaseLongProduct * Math.pow(2.0, clampBrightnessEv(displayBrightnessEv)));
        ExposureSetting longSetting = solveLongSettingForProductLocked(
                targetLongProduct, lastAeExposureNs, anchorProduct);
        autoLongExposureNs = longSetting.exposureNs;
        autoLongIso = longSetting.iso;

        double achievedLongProduct = Math.max(1.0,
                (double) autoLongExposureNs * autoLongIso);
        double targetShortProduct = Math.max(1.0,
                achievedLongProduct / Math.pow(2.0, autoAdaptiveBracketEv));
        ExposureSetting shortSetting = solveShortSettingForProductLocked(
                targetShortProduct, autoLongExposureNs);
        autoShortExposureNs = shortSetting.exposureNs;
        autoShortIso = shortSetting.iso;
    }

    private ExposureSetting solveLongSettingForProductLocked(
            double targetProduct, long preferredExposureNs, double preferredProduct) {
        long preferred = clampExposure(preferredExposureNs);
        long maxAllowed = targetPreviewFps >= 60
                ? SIXTY_FPS_DURATION_NS
                : Math.max(manualFrameDurationNs, preferred);
        double gain = targetProduct / Math.max(1.0, preferredProduct);
        long desired = clampExposure(Math.round(preferred * gain));
        desired = Math.min(desired, maxAllowed);

        long period = flickerPeriodNs(sceneFlicker);
        if (period > 0L && desired >= period) {
            long periods = Math.max(1L, Math.round(desired / (double) period));
            desired = Math.min(maxAllowed, clampExposure(periods * period));
        } else if (sceneFlicker != CaptureResult.STATISTICS_SCENE_FLICKER_NONE
                && period == 0L && gain >= 1.0) {
            // Unknown/PWM: preserve the clean-AE integration and use gain rather than
            // inventing a new potentially banding-prone shutter.
            desired = Math.min(maxAllowed, preferred);
        }
        int iso = solveIsoForProduct(targetProduct, desired);
        return new ExposureSetting(desired, iso);
    }

    private ExposureSetting solveShortSettingForProductLocked(
            double targetProduct, long longExposureNs) {
        int minIso = sensorMinIsoLocked();
        long maxAllowed = Math.min(
                clampExposure(longExposureNs),
                targetPreviewFps >= 60 ? SIXTY_FPS_DURATION_NS : Long.MAX_VALUE);
        maxAllowed = clampExposure(maxAllowed);

        if (targetProduct >= (double) maxAllowed * minIso) {
            return new ExposureSetting(
                    maxAllowed, solveIsoForProduct(targetProduct, maxAllowed));
        }

        long desired = clampExposure(Math.round(targetProduct / Math.max(1, minIso)));
        desired = Math.min(desired, maxAllowed);
        long period = flickerPeriodNs(sceneFlicker);
        if (period > 0L && desired >= period) {
            long periods = Math.max(1L, desired / period);
            long safe = clampExposure(periods * period);
            safe = Math.min(safe, maxAllowed);
            return new ExposureSetting(safe, solveIsoForProduct(targetProduct, safe));
        }
        // If the scene genuinely needs more headroom than one full anti-banding period
        // at sensor-minimum ISO can provide, use the required shorter SHORT exposure.
        // Only clipped LONG highlight channels can consume this frame during fusion.
        return new ExposureSetting(desired, minIso);
    }

    private static long flickerPeriodNs(int flicker) {
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) return 10_000_000L;
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) return 8_333_333L;
        return 0L;
    }

    private void publishAutoHdrSettingsLocked(String event, boolean logEvent) {
        double baseProduct = Math.max(1.0, (double) lastAeExposureNs * lastAeIso);
        double longProduct = Math.max(1.0, (double) autoLongExposureNs * autoLongIso);
        double shortProduct = Math.max(1.0, (double) autoShortExposureNs * autoShortIso);
        double achievedBrightnessEv = Math.log(longProduct / baseProduct) / Math.log(2.0);
        double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);
        listener.onAutoHdrSettings(
                autoShortExposureNs, autoShortIso, autoLongExposureNs, autoLongIso,
                flickerLabel(sceneFlicker), bracketEv);
        if (logEvent) {
            RuntimeLogger.event(
                    event,
                    "bootstrap=" + exposureText(lastAeExposureNs) + " ISO" + lastAeIso
                            + " boost=" + autoPostRawBoost + "%"
                            + String.format(
                                    Locale.US,
                                    " requestedBrightness=%+.1fEV achievedFromBootstrap=%+.2fEV adaptiveTarget=%.2fEV",
                                    displayBrightnessEv, achievedBrightnessEv, autoAdaptiveBracketEv)
                            + " -> short=" + exposureText(autoShortExposureNs) + " ISO" + autoShortIso
                            + " long=" + exposureText(autoLongExposureNs) + " ISO" + autoLongIso
                            + String.format(Locale.US, " actualBracket=%.2fEV", bracketEv)
                            + " flicker=" + flickerLabel(sceneFlicker));
        }
    }

    private static float clampBrightnessEv(float ev) {
        return Math.max(DISPLAY_BRIGHTNESS_MIN_EV, Math.min(DISPLAY_BRIGHTNESS_MAX_EV, ev));
    }

    private static double clampDouble(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private int resultPostRawBoost(TotalCaptureResult result) {
        Integer value = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        return clampPostRawBoost(value == null ? DEFAULT_POST_RAW_BOOST : value);
    }

    private int solveIsoForProduct(double exposureProduct, long exposureNs) {
        return clampIso((int) Math.round(exposureProduct / Math.max(1.0, exposureNs)));
    }

    private void resetManualAdaptiveBracketLocked() {
        if (characteristics == null) return;
        int minIso = sensorMinIsoLocked();
        double requestedLongProduct = Math.max(1.0, (double) longExposureNs * manualIso);
        double requestedShortProduct = Math.max(1.0, (double) shortExposureNs * minIso);
        double requestedBracket = Math.log(requestedLongProduct / requestedShortProduct) / Math.log(2.0);
        manualBracketFloorEv = clampDouble(
                requestedBracket, AUTO_BRACKET_MIN_EV, MANUAL_BRACKET_MAX_EV);
        manualAdaptiveBracketEv = clampDouble(
                Math.max(manualBracketFloorEv, AUTO_BRACKET_DEFAULT_EV + MANUAL_EXTRA_HEADROOM_EV),
                AUTO_BRACKET_MIN_EV, MANUAL_BRACKET_MAX_EV);
    }

    private boolean recomputeManualAdaptivePairLocked() {
        long oldShortExposure = manualEffectiveShortExposureNs;
        long oldLongExposure = manualEffectiveLongExposureNs;
        int oldShortIso = manualEffectiveShortIso;
        int oldLongIso = manualEffectiveLongIso;
        boolean oldSafety = manualFlickerSafetyApplied;

        int minIso = sensorMinIsoLocked();
        double requestedBaseLongProduct = Math.max(1.0, (double) longExposureNs * manualIso);
        double targetLongProduct = Math.max(1.0,
                requestedBaseLongProduct * Math.pow(2.0, clampBrightnessEv(displayBrightnessEv)));
        ExposureSetting longSetting = solveLongSettingForProductLocked(
                targetLongProduct, longExposureNs, requestedBaseLongProduct);
        manualEffectiveLongExposureNs = longSetting.exposureNs;
        manualEffectiveLongIso = longSetting.iso;

        double achievedLongProduct = Math.max(1.0,
                (double) manualEffectiveLongExposureNs * manualEffectiveLongIso);
        double targetShortProduct = Math.max(1.0,
                achievedLongProduct / Math.pow(2.0, manualAdaptiveBracketEv));
        ExposureSetting shortSetting = solveShortSettingForProductLocked(
                targetShortProduct, manualEffectiveLongExposureNs);

        // In MANUAL SAFE the Short slider is a headroom ceiling, not a lock. The
        // adaptive engine may go darker/shorter when highlights require it, but never
        // make SHORT longer than the user's requested integration.
        long shortExposure = Math.min(shortSetting.exposureNs, clampExposure(shortExposureNs));
        int shortIso = solveIsoForProduct(targetShortProduct, shortExposure);
        if (shortIso < minIso) shortIso = minIso;
        manualEffectiveShortExposureNs = shortExposure;
        manualEffectiveShortIso = shortIso;

        double longProduct = Math.max(
                1.0, (double) manualEffectiveLongExposureNs * manualEffectiveLongIso);
        double shortProduct = Math.max(
                1.0, (double) manualEffectiveShortExposureNs * manualEffectiveShortIso);
        manualEffectiveBracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);
        manualAchievedBrightnessEv = Math.log(longProduct / requestedBaseLongProduct) / Math.log(2.0);
        manualFlickerSafetyApplied = sceneFlicker != CaptureResult.STATISTICS_SCENE_FLICKER_NONE
                || targetPreviewFps >= 60;

        return oldShortExposure != manualEffectiveShortExposureNs
                || oldLongExposure != manualEffectiveLongExposureNs
                || oldShortIso != manualEffectiveShortIso
                || oldLongIso != manualEffectiveLongIso
                || oldSafety != manualFlickerSafetyApplied;
    }

    private int sensorMinIsoLocked() {
        Range<Integer> range = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        return range == null ? 100 : range.getLower();
    }

    private String manualSafetySummaryLocked() {
        return (manualFlickerSafetyApplied ? "MANUAL_SAFE" : "MANUAL_EXACT")
                + " requested=" + exposureText(shortExposureNs) + "/" + exposureText(longExposureNs)
                + " longISO" + manualIso + " shortISO=min"
                + " actual=" + exposureText(manualEffectiveShortExposureNs) + " ISO" + manualEffectiveShortIso
                + "/" + exposureText(manualEffectiveLongExposureNs) + " ISO" + manualEffectiveLongIso
                + String.format(Locale.US, " %.1fEV", manualEffectiveBracketEv)
                + String.format(
                        Locale.US,
                        " brightness=%+.1fEV achieved=%+.2fEV",
                        displayBrightnessEv,
                        manualAchievedBrightnessEv)
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

    private int clampPostRawBoost(int value) {
        if (postRawBoostRange == null) return DEFAULT_POST_RAW_BOOST;
        return Math.max(postRawBoostRange.getLower(), Math.min(postRawBoostRange.getUpper(), value));
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
        Integer points = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        return contains(modes, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                && points != null && points >= 2;
    }

    private static TonemapCurve buildSrgbTonemapCurve(CameraCharacteristics c) {
        Integer maxPoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        int count = Math.max(2, Math.min(MAX_SRGB_CURVE_POINTS, maxPoints == null ? 2 : maxPoints));
        float[] curve = new float[count * 2];
        for (int i = 0; i < count; i++) {
            float x = i / (float) (count - 1);
            float y = x <= 0.0031308f
                    ? 12.92f * x
                    : 1.055f * (float) Math.pow(x, 1.0 / 2.4) - 0.055f;
            curve[i * 2] = x;
            curve[i * 2 + 1] = Math.max(0.0f, Math.min(1.0f, y));
        }
        return new TonemapCurve(curve, curve.clone(), curve.clone());
    }

    private static boolean hasExactAeFpsRange(Range<Integer>[] ranges, int target) {
        if (ranges == null) return false;
        for (Range<Integer> range : ranges) {
            if (range.getLower() == target && range.getUpper() == target) return true;
        }
        return false;
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

    private static final class ExposureSetting {
        final long exposureNs;
        final int iso;

        ExposureSetting(long exposureNs, int iso) {
            this.exposureNs = exposureNs;
            this.iso = iso;
        }
    }

    static String exposureText(long ns) {
        if (ns <= 0) return "?";
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 0.5) return String.format(Locale.US, "%.2fs", seconds);
        double reciprocal = 1.0 / seconds;
        if (Math.abs(reciprocal - Math.rint(reciprocal)) >= 0.05 && reciprocal < 100.0) {
            return String.format(Locale.US, "1/%.1fs", reciprocal);
        }
        if (reciprocal >= 2.0) return String.format(Locale.US, "1/%.0fs", reciprocal);
        return String.format(Locale.US, "%.3fs", seconds);
    }
}
