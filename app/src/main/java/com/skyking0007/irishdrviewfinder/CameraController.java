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
        void onPresentationSettings(
                float brightnessEv,
                float gamma,
                float dehaze,
                float microContrast,
                boolean automatic);
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
    private static final double HDR_BRACKET_RATIO = 8.0;
    private static final int AUTO_METER_MIN_FRAMES = 4;
    private static final int AUTO_METER_MAX_FRAMES = 12;
    private static final int AUTO_METER_STABLE_FRAMES = 3;
    private static final double AUTO_METER_STABLE_EV = 0.18;
    // V2.2 live-stat ownership: clean HAL AE is bootstrap-only, then a 32x24
    // LONG statistic updates AUTO without replacing the HDR burst.
    private static final double AUTO_LIVE_HYSTERESIS_EV = 0.10;
    private static final double AUTO_LIVE_MAX_STEP_EV = 0.30;
    private static final double AUTO_LIVE_SCENE_CUT_EV = 0.70;
    private static final double AUTO_LIVE_SCENE_CUT_MAX_STEP_EV = 6.0;
    private static final long AUTO_LIVE_UPDATE_MIN_NS = 80_000_000L;
    private static final double AUTO_BRACKET_MIN_RATIO = 4.0;
    private static final double AUTO_BRACKET_MAX_RATIO = 64.0;
    private static final double AUTO_SHORT_P50_LONG_TARGET = 0.12;
    private static final double AUTO_SHORT_P90_LONG_TARGET = 0.42;
    private static final double AUTO_SHORT_P98_LONG_HEADROOM = 0.85;
    private static final float AUTO_PRESENT_BRIGHTNESS_MIN_EV = -1.25f;
    private static final float AUTO_PRESENT_BRIGHTNESS_MAX_EV = 0.75f;
    private static final float AUTO_PRESENT_GAMMA_MIN = 0.85f;
    private static final float AUTO_PRESENT_GAMMA_MAX = 1.60f;
    private static final float AUTO_PRESENT_BRIGHTNESS_STEP_EV = 0.18f;
    private static final float AUTO_PRESENT_GAMMA_STEP = 0.05f;
    private static final float PRESENT_ENHANCEMENT_STEP = 0.06f;
    private static final int DEFAULT_POST_RAW_BOOST = 100;
    private static final int MAX_SRGB_CURVE_POINTS = 64;
    private static final double FOV_MAX_REPORTED_SCALE = 1.03;
    private static final float FOV_MAX_REPORTED_ZOOM = 1.02f;
    private static final int FOV_UNSAFE_CONFIRM_FRAMES = 3;
    private static final int FOV_DECISION_FRAMES = 60;
    private static final int FLICKER_UNKNOWN = -1;
    static final int FLICKER_MODE_AUTO = 0;
    static final int FLICKER_MODE_50HZ = 1;
    static final int FLICKER_MODE_60HZ = 2;
    static final int FLICKER_MODE_OFF = 3;
    private static final long FLICKER_50_PERIOD_NS = 10_000_000L;
    private static final long FLICKER_60_PERIOD_NS = 8_333_333L;

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
    private HdrGlView stillFusionView;
    private Surface previewSurface;
    private String cameraId;
    private PreviewMode previewMode = PreviewMode.HDR;
    private boolean opening;
    private boolean capturing;
    private boolean stillSessionActive;
    private boolean previewSurfaceConfigured;
    // Immutable per-shutter still controls. AUTO remetering may continue after the
    // still inputs are acquired, but it must never mutate an in-flight HDR set.
    private long captureShortExposureNs;
    private long captureLongExposureNs;
    private int captureShortIso;
    private int captureLongIso;
    private int capturePostRawBoost = DEFAULT_POST_RAW_BOOST;
    private long captureBeginRealtimeNs;
    private float displayBrightnessEv;
    private float captureDisplayBrightnessEv;
    private float displayGamma = 1.0f;
    private float captureDisplayGamma = 1.0f;
    private float displayDehaze = 0.28f;
    private float captureDisplayDehaze = 0.28f;
    private float displayMicroContrast = 0.20f;
    private float captureDisplayMicroContrast = 0.20f;
    private long shortExposureNs = ONE_SECOND_NS / 480;
    private long longExposureNs = ONE_SECOND_NS / 60;
    private int manualIso = 400;
    private long manualEffectiveShortExposureNs = ONE_SECOND_NS / 480;
    private long manualEffectiveLongExposureNs = ONE_SECOND_NS / 60;
    private int manualEffectiveShortIso = 100;
    private int manualEffectiveLongIso = 400;
    private boolean manualFlickerSafetyApplied;
    private double manualEffectiveBracketEv = 3.0;
    private boolean autoHdrExposure = true;
    private long autoShortExposureNs = ONE_SECOND_NS / 120;
    private long autoLongExposureNs = ONE_SECOND_NS / 60;
    private int autoShortIso = 100;
    private int autoLongIso = 400;
    private int autoPostRawBoost = DEFAULT_POST_RAW_BOOST;
    private int sceneFlicker = FLICKER_UNKNOWN;
    private int flickerMode = FLICKER_MODE_AUTO;
    private boolean autoFlickerSafetySatisfied;
    private boolean autoMetering;
    private int autoMeterFrames;
    private int autoMeterStableFrames;
    private double autoMeterLastProduct = -1.0;
    private long lastAutoAnchorNs;
    private double autoLiveLongProduct = -1.0;
    private double autoLiveShortProduct = -1.0;
    private double autoDesiredBracketRatio = HDR_BRACKET_RATIO;
    private long lastAutoLiveStatsFrame = -1L;
    private HdrGlView.SceneStats latestSceneStats;
    private long lastAutoLiveUpdateNs;
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

    void setStillFusionView(HdrGlView view) {
        stillFusionView = view;
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
        final float requested = Math.max(-16.0f, Math.min(1.0f, ev));
        cameraHandler.post(() -> {
            displayBrightnessEv = requested;
            RuntimeLogger.event("DISPLAY_BRIGHTNESS", String.format(Locale.US, "%.1fEV", displayBrightnessEv));
            updateAdaptivePresentationLocked(latestSceneStats, autoHdrExposure, true);
        });
    }

    void setDisplayGamma(float gamma) {
        final float requested = Math.max(0.50f, Math.min(2.00f, gamma));
        cameraHandler.post(() -> {
            displayGamma = requested;
            RuntimeLogger.event("DISPLAY_GAMMA", String.format(Locale.US, "%.2f", displayGamma));
            updateAdaptivePresentationLocked(latestSceneStats, autoHdrExposure, true);
        });
    }

    void setAutoHdrExposure(boolean enabled) {
        cameraHandler.post(() -> {
            autoHdrExposure = enabled;
            autoMetering = false;
            lastAutoLiveStatsFrame = -1L;
            lastAutoLiveUpdateNs = 0L;
            autoLiveShortProduct = -1.0;
            autoDesiredBracketRatio = HDR_BRACKET_RATIO;
            if (!enabled) {
                recomputeManualFlickerSafetyLocked();
                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
                updateAdaptivePresentationLocked(latestSceneStats, false, true);
            } else {
                updateAdaptivePresentationLocked(latestSceneStats, true, true);
            }
            RuntimeLogger.event("HDR_MODE", enabled ? "AUTO_ANCHORED" : manualSafetySummaryLocked());
            if (previewMode != PreviewMode.NORMAL) {
                if (enabled && !haveAeSample) startAutoMeteringLocked();
                else applyPreviewRepeatingLocked();
            }
        });
    }

    void setFlickerMode(int mode) {
        final int requested = clampFlickerMode(mode);
        cameraHandler.post(() -> {
            flickerMode = requested;
            autoFlickerSafetySatisfied = false;
            RuntimeLogger.event("FLICKER_MODE", flickerModeLabel(flickerMode));
            if (characteristics == null) return;

            if (autoHdrExposure) {
                if (haveAeSample) {
                    if (autoLiveLongProduct > 0.0) deriveAutoPairFromSceneTargetsLocked();
                    else deriveAutoPairFromAnchorLocked();
                }
            } else {
                recomputeManualFlickerSafetyLocked();
                listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);
            }

            if (previewMode != PreviewMode.NORMAL) {
                if (autoHdrExposure && !haveAeSample) startAutoMeteringLocked();
                else applyPreviewRepeatingLocked();
            } else {
                applyPreviewRepeatingLocked();
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
            // V2.5 manual contract: the SHORT control owns the physical SHORT
            // integration. If a requested SHORT crosses LONG, clamp SHORT at LONG
            // instead of silently swapping the two controls and changing LONG.
            if (shortExposureNs > longExposureNs) {
                shortExposureNs = longExposureNs;
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
            autoLiveLongProduct = -1.0;
            autoLiveShortProduct = -1.0;
            autoDesiredBracketRatio = HDR_BRACKET_RATIO;
            latestSceneStats = null;
            lastAutoLiveStatsFrame = -1L;
            lastAutoLiveUpdateNs = 0L;
            autoMeterFrames = 0;
            autoMeterStableFrames = 0;
            autoMeterLastProduct = -1.0;
            lastAutoAnchorNs = 0L;
            autoPostRawBoost = DEFAULT_POST_RAW_BOOST;
            sceneFlicker = FLICKER_UNKNOWN;
            recomputeManualFlickerSafetyLocked();
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
                                + "  flicker=" + flickerStatusLocked()
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
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, aeAntibandingModeLocked());
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
                            && flickerMode == FLICKER_MODE_AUTO
                            && previewMode != PreviewMode.NORMAL
                            && observedFlicker != null
                            && observedFlicker != sceneFlicker) {
                        sceneFlicker = observedFlicker;
                        if (recomputeManualFlickerSafetyLocked()) {
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
                                        + " flicker=" + flickerStatusLocked()
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
                                        + " flicker=" + flickerStatusLocked()
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
        // Freeze the exact pair before closing the preview session. Continuous live
        // statistics may continue to arrive, but the still inputs below are immutable.
        autoMetering = false;
        captureShortExposureNs = activeShortExposureNs();
        captureLongExposureNs = activeLongExposureNs();
        captureShortIso = activeShortIso();
        captureLongIso = activeLongIso();
        capturePostRawBoost = autoHdrExposure ? autoPostRawBoost : DEFAULT_POST_RAW_BOOST;
        captureDisplayBrightnessEv = displayBrightnessEv;
        captureDisplayGamma = displayGamma;
        captureDisplayDehaze = displayDehaze;
        captureDisplayMicroContrast = displayMicroContrast;
        captureBeginRealtimeNs = System.nanoTime();
        String captureId = "IrisHDR_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        RuntimeLogger.event(
                "CAPTURE_BEGIN",
                captureId
                        + " frozen short=" + exposureText(captureShortExposureNs) + " ISO" + captureShortIso
                        + " long=" + exposureText(captureLongExposureNs) + " ISO" + captureLongIso
                        + " boost=" + capturePostRawBoost + "%"
                        + String.format(Locale.US, " brightness=%+.2fEV gamma=%.2f dehaze=%.2f micro=%.2f",
                                captureDisplayBrightnessEv, captureDisplayGamma,
                                captureDisplayDehaze, captureDisplayMicroContrast)
                        + " mode=" + (autoHdrExposure ? "AUTO " + flickerStatusLocked() : manualSafetySummaryLocked()));
        listener.onStatus("Capturing matched SHORT/LONG RAW + JPEG set…");
        captureSaver = new CaptureSetSaver(
                context,
                characteristics,
                cameraId,
                captureId,
                jpegOrientationDegrees,
                captureDisplayBrightnessEv,
                captureDisplayGamma,
                captureDisplayDehaze,
                captureDisplayMicroContrast,
                stillFusionView,
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
        autoLiveLongProduct = -1.0;
        autoLiveShortProduct = -1.0;
        autoDesiredBracketRatio = HDR_BRACKET_RATIO;
        latestSceneStats = null;
        lastAutoLiveStatsFrame = -1L;
        lastAutoLiveUpdateNs = 0L;
        autoMetering = false;
        recomputeManualFlickerSafetyLocked();
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
        autoLiveLongProduct = -1.0;
        autoLiveShortProduct = -1.0;
        autoDesiredBracketRatio = HDR_BRACKET_RATIO;
        latestSceneStats = null;
        lastAutoLiveStatsFrame = -1L;
        lastAutoLiveUpdateNs = 0L;
        autoMetering = false;
        recomputeManualFlickerSafetyLocked();
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
                        autoLiveLongProduct = -1.0;
                        autoLiveShortProduct = -1.0;
                        autoDesiredBracketRatio = HDR_BRACKET_RATIO;
                        latestSceneStats = null;
                        lastAutoLiveStatsFrame = -1L;
                        lastAutoLiveUpdateNs = 0L;
                        autoMetering = false;
                        recomputeManualFlickerSafetyLocked();
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
        // Bootstrap only. Once AUTO has one clean HAL anchor, never take over the
        // running HDR burst again; live 32x24 statistics own subsequent adaptation.
        if (haveAeSample) {
            applyPreviewRepeatingLocked();
            return;
        }
        try {
            autoMetering = true;
            // Metering temporarily replaces the pair request. Start a fresh cadence
            // window so that this bounded hidden phase cannot look like 60-fps failure.
            resetCaptureResultFpsLocked();
            autoMeterFrames = 0;
            autoMeterStableFrames = 0;
            autoMeterLastProduct = -1.0;
            captureSession.setRepeatingRequest(
                    buildMeterPreviewRequest(), previewCaptureCallback, cameraHandler);
            RuntimeLogger.event(
                    "AUTO_METER_BEGIN",
                    "clean AE phase target=" + targetPreviewFps + " fps=" + rangeText(aeFpsRange));
            listener.onStatus("AUTO HDR metering scene brightness…");
        } catch (Throwable t) {
            autoMetering = false;
            RuntimeLogger.error("AUTO_METER_START_FAIL", t);
            listener.onStatus("AUTO meter failed: " + t.getMessage());
            if (haveAeSample) applyPreviewRepeatingLocked();
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
        long now = System.nanoTime();
        boolean firstAnchor = !haveAeSample;
        lastAeExposureNs = clampExposure(exposureNs);
        lastAeIso = clampIso(iso);
        autoPostRawBoost = resultPostRawBoost(result);
        sceneFlicker = flicker;
        haveAeSample = true;
        lastAutoAnchorNs = now;
        deriveAutoPairFromAnchorLocked();
        autoLiveLongProduct = Math.max(1.0, (double) autoLongExposureNs * autoLongIso);
        autoLiveShortProduct = Math.max(1.0, (double) autoShortExposureNs * autoShortIso);
        autoDesiredBracketRatio = HDR_BRACKET_RATIO;
        lastAutoLiveStatsFrame = -1L;
        lastAutoLiveUpdateNs = 0L;

        double longProduct = Math.max(1.0, (double) autoLongExposureNs * autoLongIso);
        double shortProduct = Math.max(1.0, (double) autoShortExposureNs * autoShortIso);
        double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);
        listener.onAutoHdrSettings(
                autoShortExposureNs, autoShortIso, autoLongExposureNs, autoLongIso,
                flickerStatusLocked(), bracketEv);
        if (finishMeter || firstAnchor) {
            RuntimeLogger.event(
                    "AUTO_ANCHOR",
                    "meter=" + exposureText(lastAeExposureNs) + " ISO" + lastAeIso
                            + " boost=" + autoPostRawBoost + "%"
                            + " -> short=" + exposureText(autoShortExposureNs) + " ISO" + autoShortIso
                            + " long=" + exposureText(autoLongExposureNs) + " ISO" + autoLongIso
                            + String.format(Locale.US, " bracket=%.2fEV", bracketEv)
                            + " flicker=" + flickerStatusLocked());
        }

        if (finishMeter) {
            autoMetering = false;
            // The next FPS window must contain only steady SHORT/LONG results.
            resetCaptureResultFpsLocked();
            applyPreviewRepeatingLocked();
        }
    }

    private void deriveAutoPairFromAnchorLocked() {
        Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange == null) return;
        int minIso = isoRange.getLower();
        long longExposure = clampExposure(lastAeExposureNs);
        int longIso = clampIso(lastAeIso);
        double targetLongProduct = Math.max(1.0, (double) longExposure * longIso);

        // Preserve the clean AE anchor while respecting a true 60-fps sensor cadence.
        if (targetPreviewFps >= 60 && longExposure > SIXTY_FPS_DURATION_NS) {
            longExposure = clampExposure(SIXTY_FPS_DURATION_NS);
            longIso = solveIsoForProduct(targetLongProduct, longExposure);
        }

        long period = effectiveFlickerPeriodNsLocked();
        if (period > 0L) {
            long maxAllowed = targetPreviewFps >= 60
                    ? SIXTY_FPS_DURATION_NS : Math.max(manualFrameDurationNs, longExposure);
            ExposureSetting safeLong = solveFlickerSafeSettingForProductLocked(
                    targetLongProduct, longExposure, period, maxAllowed);
            if (safeLong != null) {
                ExposureSetting safeShort = solveMinimumIsoFlickerSettingLocked(
                        Math.max(1.0, ((double) safeLong.exposureNs * safeLong.iso) / HDR_BRACKET_RATIO),
                        Math.min(safeLong.exposureNs, longExposure),
                        period,
                        Math.min(maxAllowed, safeLong.exposureNs));
                if (safeShort != null) {
                    double safeShortProduct = Math.max(1.0,
                            (double) safeShort.exposureNs * safeShort.iso);
                    double feasibleLongProduct = Math.min(
                            Math.max(1.0, (double) safeLong.exposureNs * safeLong.iso),
                            safeShortProduct * HDR_BRACKET_RATIO);
                    ExposureSetting boundedLong = solveFlickerSafeSettingForProductLocked(
                            feasibleLongProduct, safeLong.exposureNs, period, maxAllowed);
                    if (boundedLong != null) safeLong = boundedLong;
                    autoLongExposureNs = safeLong.exposureNs;
                    autoLongIso = safeLong.iso;
                    autoShortExposureNs = safeShort.exposureNs;
                    autoShortIso = safeShort.iso;
                    autoFlickerSafetySatisfied = true;
                    return;
                }
            }
        }

        // AUTO without a proven 50/60-Hz authority remains best-effort and is
        // explicitly reported unsafe rather than treating Camera2 NONE/UNKNOWN as
        // proof that arbitrary integration windows cannot band.
        autoFlickerSafetySatisfied = false;
        autoLongExposureNs = longExposure;
        autoLongIso = longIso;
        autoShortIso = minIso;
        // V2.13: an unknown/PWM flicker report is a safety-status problem, never
        // permission to collapse HDR. Preserve an independent SHORT even when no
        // integer-cycle authority exists, and continue to report FLICKER UNSAFE.
        double targetShortProduct = Math.max(1.0,
                ((double) autoLongExposureNs * autoLongIso) / HDR_BRACKET_RATIO);
        long desiredShort = Math.round(targetShortProduct / Math.max(1, minIso));
        autoShortExposureNs = Math.min(autoLongExposureNs, clampExposure(desiredShort));
        double actualShortProduct = Math.max(1.0,
                (double) autoShortExposureNs * autoShortIso);
        double actualLongProduct = Math.max(1.0,
                (double) autoLongExposureNs * autoLongIso);
        if (actualLongProduct > actualShortProduct * HDR_BRACKET_RATIO) {
            double bounded = actualShortProduct * HDR_BRACKET_RATIO;
            ExposureSetting boundedLong = solveLiveLongSettingForProductLocked(
                    bounded, autoLongExposureNs, actualLongProduct);
            if (boundedLong.exposureNs < autoShortExposureNs) {
                autoLongExposureNs = autoShortExposureNs;
                autoLongIso = solveIsoForProduct(bounded, autoLongExposureNs);
            } else {
                autoLongExposureNs = boundedLong.exposureNs;
                autoLongIso = boundedLong.iso;
            }
        }
    }

    private void processHdrSceneStatsLocked(HdrGlView.SceneStats stats) {
        latestSceneStats = stats;
        updateAdaptivePresentationLocked(stats, autoHdrExposure, false);

        if (!autoHdrExposure || previewMode == PreviewMode.NORMAL || stillSessionActive
                || autoMetering || characteristics == null || captureSession == null
                || !haveAeSample) return;
        if (stats.longFrameNumber <= lastAutoLiveStatsFrame) return;
        lastAutoLiveStatsFrame = stats.longFrameNumber;

        double expectedLongProduct = Math.max(1.0, (double) autoLongExposureNs * autoLongIso);
        double expectedShortProduct = Math.max(1.0, (double) autoShortExposureNs * autoShortIso);
        double staleLongEv = Math.abs(Math.log(
                Math.max(1.0, stats.longExposureProduct) / expectedLongProduct) / Math.log(2.0));
        double staleShortEv = Math.abs(Math.log(
                Math.max(1.0, stats.shortExposureProduct) / expectedShortProduct) / Math.log(2.0));
        if (staleLongEv > 0.30 || staleShortEv > 0.30 || stats.shortP98Linear <= 0.0005f) return;

        // IRIS_V213_INDEPENDENT_HDR_EXPOSURE_BEGIN
        // SHORT owns highlight protection; LONG independently owns body/shadow SNR.
        // Do not let the brightest 1% veto HDR depth: P99 is allowed to trigger a
        // SHORT reduction, but bracket learning comes from robust body + bright-tail
        // evidence.  The requested ratio is scene-driven, never less than 4x in HDR,
        // and can grow to 64x when the captured scene and sensor bounds support it.
        double ratioBody = Math.sqrt(
                (AUTO_SHORT_P50_LONG_TARGET / Math.max(0.002, stats.shortP50Linear))
                        * (AUTO_SHORT_P90_LONG_TARGET / Math.max(0.002, stats.shortP90Linear)));
        double ratioHeadroom = AUTO_SHORT_P98_LONG_HEADROOM
                / Math.max(0.002, stats.shortP98Linear);
        double desiredRatio = Math.max(AUTO_BRACKET_MIN_RATIO,
                Math.min(AUTO_BRACKET_MAX_RATIO,
                        Math.min(ratioBody, 2.0 * ratioHeadroom)));

        // P99/clip pressure changes SHORT itself, never collapses LONG onto SHORT.
        double shortScale = Math.min(1.0,
                0.78 / Math.max(0.010, stats.shortP99Linear));
        if (stats.shortNearClipFraction > 0.010f) shortScale = Math.min(shortScale, 0.80);
        shortScale = Math.max(0.25, shortScale);
        double targetShortProduct = Math.max(1.0, stats.shortExposureProduct * shortScale);
        double targetLongProduct = Math.max(targetShortProduct * AUTO_BRACKET_MIN_RATIO,
                targetShortProduct * desiredRatio);

        double errorEv = Math.log(targetLongProduct / expectedLongProduct) / Math.log(2.0);
        double ratioErrorEv = Math.log(desiredRatio / Math.max(1.0, autoDesiredBracketRatio)) / Math.log(2.0);
        if (Math.abs(errorEv) <= AUTO_LIVE_HYSTERESIS_EV
                && Math.abs(ratioErrorEv) <= 0.08) return;
        boolean sceneCut = Math.abs(errorEv) >= AUTO_LIVE_SCENE_CUT_EV;
        long now = System.nanoTime();
        if (!sceneCut && lastAutoLiveUpdateNs != 0L
                && now - lastAutoLiveUpdateNs < AUTO_LIVE_UPDATE_MIN_NS) return;

        double maxStep = sceneCut ? AUTO_LIVE_SCENE_CUT_MAX_STEP_EV : AUTO_LIVE_MAX_STEP_EV;
        double stepEv = Math.max(-maxStep, Math.min(maxStep, errorEv));
        double ratioStepEv = sceneCut
                ? ratioErrorEv
                : Math.max(-AUTO_LIVE_MAX_STEP_EV, Math.min(AUTO_LIVE_MAX_STEP_EV, ratioErrorEv));
        autoLiveLongProduct = Math.max(1.0, expectedLongProduct * Math.pow(2.0, stepEv));
        autoLiveShortProduct = Math.max(1.0, targetShortProduct);
        autoDesiredBracketRatio = Math.max(AUTO_BRACKET_MIN_RATIO,
                Math.min(AUTO_BRACKET_MAX_RATIO,
                        autoDesiredBracketRatio * Math.pow(2.0, ratioStepEv)));

        long oldLongNs = autoLongExposureNs;
        int oldLongIso = autoLongIso;
        long oldShortNs = autoShortExposureNs;
        int oldShortIso = autoShortIso;
        deriveAutoPairFromSceneTargetsLocked();
        if (oldLongNs == autoLongExposureNs && oldLongIso == autoLongIso
                && oldShortNs == autoShortExposureNs && oldShortIso == autoShortIso) return;

        lastAutoLiveUpdateNs = now;
        double longProduct = Math.max(1.0, (double) autoLongExposureNs * autoLongIso);
        double shortProduct = Math.max(1.0, (double) autoShortExposureNs * autoShortIso);
        double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);
        RuntimeLogger.event(
                "AUTO_SCENE_ADAPT",
                String.format(Locale.US,
                        "shortP50=%.4f shortP90=%.4f shortP98=%.4f shortP99=%.4f shortClip=%.3f longClip=%.3f targetRatio=%.2fx err=%+.2fEV step=%+.2fEV short=%s ISO%d long=%s ISO%d bracket=%.2fEV flicker=%s",
                        stats.shortP50Linear, stats.shortP90Linear, stats.shortP98Linear,
                        stats.shortP99Linear, stats.shortNearClipFraction,
                        stats.longNearClipFraction, desiredRatio, errorEv, stepEv,
                        exposureText(autoShortExposureNs), autoShortIso,
                        exposureText(autoLongExposureNs), autoLongIso, bracketEv, flickerStatusLocked()));
        listener.onAutoHdrSettings(
                autoShortExposureNs, autoShortIso, autoLongExposureNs, autoLongIso,
                flickerStatusLocked(), bracketEv);
        applyPreviewRepeatingLocked();
        // IRIS_V213_INDEPENDENT_HDR_EXPOSURE_END
    }

    private void deriveAutoPairFromSceneTargetsLocked() {
        if (characteristics == null || !haveAeSample) return;
        double anchorProduct = Math.max(1.0, (double) lastAeExposureNs * lastAeIso);
        if (autoLiveLongProduct <= 0.0) autoLiveLongProduct = anchorProduct;
        if (autoLiveShortProduct <= 0.0) {
            autoLiveShortProduct = Math.max(1.0, autoLiveLongProduct / autoDesiredBracketRatio);
        }

        long period = effectiveFlickerPeriodNsLocked();
        long maxAllowed = targetPreviewFps >= 60
                ? SIXTY_FPS_DURATION_NS : Math.max(manualFrameDurationNs, lastAeExposureNs);
        ExposureSetting longSetting;
        ExposureSetting shortSetting = null;
        if (period > 0L) {
            shortSetting = solveMinimumIsoFlickerSettingLocked(
                    autoLiveShortProduct,
                    Math.min(autoShortExposureNs, lastAeExposureNs),
                    period,
                    maxAllowed);
            if (shortSetting != null) {
                double shortProduct = Math.max(1.0,
                        (double) shortSetting.exposureNs * shortSetting.iso);
                double feasibleLongProduct = Math.max(shortProduct,
                        Math.min(autoLiveLongProduct, shortProduct * autoDesiredBracketRatio));
                longSetting = solveFlickerSafeSettingForProductLocked(
                        feasibleLongProduct,
                        autoLongExposureNs,
                        period,
                        maxAllowed);
                if (longSetting != null) {
                    autoShortExposureNs = shortSetting.exposureNs;
                    autoShortIso = shortSetting.iso;
                    autoLongExposureNs = longSetting.exposureNs;
                    autoLongIso = longSetting.iso;
                    autoFlickerSafetySatisfied = true;
                    return;
                }
            }
        }

        autoFlickerSafetySatisfied = false;
        int minIso = sensorMinIsoLocked();
        long shortExposure = clampExposure(Math.round(autoLiveShortProduct / Math.max(1, minIso)));
        autoShortExposureNs = Math.min(shortExposure, maxAllowed);
        autoShortIso = minIso;
        double achievedShortProduct = Math.max(1.0,
                (double) autoShortExposureNs * autoShortIso);
        double feasibleLongProduct = Math.max(achievedShortProduct,
                Math.min(autoLiveLongProduct, achievedShortProduct * autoDesiredBracketRatio));
        longSetting = solveLiveLongSettingForProductLocked(
                feasibleLongProduct, lastAeExposureNs, anchorProduct);
        if (longSetting.exposureNs < autoShortExposureNs) {
            autoLongExposureNs = autoShortExposureNs;
            autoLongIso = solveIsoForProduct(feasibleLongProduct, autoLongExposureNs);
        } else {
            autoLongExposureNs = longSetting.exposureNs;
            autoLongIso = longSetting.iso;
        }
    }

    private void updateAdaptivePresentationLocked(
            HdrGlView.SceneStats stats, boolean automatic, boolean immediate) {
        if (stats == null) {
            publishPresentationLocked(automatic);
            return;
        }

        float oldBrightness = displayBrightnessEv;
        float oldGamma = displayGamma;
        float oldDehaze = displayDehaze;
        float oldMicroContrast = displayMicroContrast;
        float targetBrightness = displayBrightnessEv;
        float targetGamma = displayGamma;
        double physicalRatio = Math.max(1.0, stats.longExposureProduct)
                / Math.max(1.0, stats.shortExposureProduct);
        boolean collapsedBracket = physicalRatio < 2.0;
        if (automatic) {
            float highlightPressure = smoothstepFloat(0.45f, 0.75f, stats.fusedP95Linear);
            float targetP90 = lerpFloat(0.18f, 0.16f, highlightPressure);
            targetBrightness = clampFloat(
                    (float) (Math.log(targetP90 / Math.max(0.010f, stats.fusedP90Linear)) / Math.log(2.0)),
                    AUTO_PRESENT_BRIGHTNESS_MIN_EV,
                    AUTO_PRESENT_BRIGHTNESS_MAX_EV);

            float brightMedian = stats.fusedP50Linear * (float) Math.pow(2.0, targetBrightness);
            float contrastStops = (float) (Math.log(
                    Math.max(0.001f, stats.fusedP90Linear)
                            / Math.max(0.001f, stats.fusedP50Linear)) / Math.log(2.0));
            float targetMedian = lerpFloat(
                    0.075f, 0.055f, smoothstepFloat(2.0f, 4.0f, contrastStops));
            if (brightMedian > 0.0005f && brightMedian < 0.98f && targetMedian < 0.98f) {
                targetGamma = clampFloat(
                        (float) (Math.log(brightMedian) / Math.log(targetMedian)),
                        AUTO_PRESENT_GAMMA_MIN,
                        AUTO_PRESENT_GAMMA_MAX);
            } else {
                targetGamma = 1.0f;
            }
            if (collapsedBracket) {
                // A failed physical bracket may not be disguised with aggressive tone.
                targetBrightness = Math.min(targetBrightness, 0.15f);
                targetGamma = Math.min(targetGamma, 1.20f);
            }

            displayBrightnessEv = stepToward(
                    displayBrightnessEv, targetBrightness,
                    immediate ? 8.0f : AUTO_PRESENT_BRIGHTNESS_STEP_EV);
            displayGamma = stepToward(
                    displayGamma, targetGamma,
                    immediate ? 2.0f : AUTO_PRESENT_GAMMA_STEP);
        }

        float shadowDeficit = 1.0f - smoothstepFloat(
                0.18f, 0.34f, stats.shadowLocalContrast);
        float midDeficit = 1.0f - smoothstepFloat(
                0.20f, 0.36f, stats.midLocalContrast);
        float shadowSpreadStops = (float) (Math.log(
                Math.max(0.001f, stats.fusedP50Linear)
                        / Math.max(0.001f, stats.fusedP10Linear)) / Math.log(2.0));
        float compressedShadows = 1.0f - smoothstepFloat(2.5f, 4.2f, shadowSpreadStops);
        float sliderLift = 0.55f * smoothstepFloat(1.05f, 1.55f, displayGamma)
                + 0.45f * smoothstepFloat(0.05f, 0.70f, displayBrightnessEv);
        float targetDehaze = clampFloat(
                0.24f + 0.24f * shadowDeficit + 0.12f * midDeficit
                        + 0.10f * compressedShadows + 0.10f * sliderLift,
                0.12f, 0.68f);
        if (collapsedBracket) targetDehaze = Math.min(targetDehaze, 0.30f);
        float usefulShadowSignal = smoothstepFloat(0.006f, 0.030f, stats.fusedP25Linear);
        float targetMicro = clampFloat(
                0.18f + 0.16f * midDeficit + 0.09f * shadowDeficit * usefulShadowSignal
                        + 0.07f * sliderLift,
                0.10f, 0.48f);
        if (collapsedBracket) targetMicro = Math.min(targetMicro, 0.22f);
        displayDehaze = stepToward(
                displayDehaze, targetDehaze, immediate ? 1.0f : PRESENT_ENHANCEMENT_STEP);
        displayMicroContrast = stepToward(
                displayMicroContrast, targetMicro, immediate ? 1.0f : PRESENT_ENHANCEMENT_STEP);

        boolean changed = Math.abs(displayBrightnessEv - oldBrightness) >= 0.005f
                || Math.abs(displayGamma - oldGamma) >= 0.005f
                || Math.abs(displayDehaze - oldDehaze) >= 0.005f
                || Math.abs(displayMicroContrast - oldMicroContrast) >= 0.005f;
        if (changed || immediate) {
            RuntimeLogger.event(
                    "PRESENTATION_ADAPT",
                    String.format(Locale.US,
                            "auto=%s p10=%.4f p50=%.4f p90=%.4f p95=%.4f shadowC=%.3f midC=%.3f -> brightness=%+.2fEV gamma=%.2f dehaze=%.2f micro=%.2f",
                            automatic, stats.fusedP10Linear, stats.fusedP50Linear,
                            stats.fusedP90Linear, stats.fusedP95Linear,
                            stats.shadowLocalContrast, stats.midLocalContrast,
                            displayBrightnessEv, displayGamma, displayDehaze, displayMicroContrast));
            publishPresentationLocked(automatic);
        }
    }

    private void publishPresentationLocked(boolean automatic) {
        if (stillFusionView != null) {
            stillFusionView.setDisplayBrightnessEv(displayBrightnessEv);
            stillFusionView.setDisplayGamma(displayGamma);
            stillFusionView.setDisplayEnhancement(displayDehaze, displayMicroContrast);
        }
        listener.onPresentationSettings(
                displayBrightnessEv, displayGamma, displayDehaze, displayMicroContrast, automatic);
    }

    private static float clampFloat(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static float smoothstepFloat(float edge0, float edge1, float value) {
        if (edge1 <= edge0) return value >= edge1 ? 1.0f : 0.0f;
        float t = clampFloat((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerpFloat(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float stepToward(float current, float target, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) return target;
        return current + Math.copySign(maxStep, delta);
    }

    private ExposureSetting solveLiveLongSettingForProductLocked(
            double targetProduct, long preferredExposureNs, double preferredProduct) {
        long preferred = clampExposure(preferredExposureNs);
        long maxAllowed = targetPreviewFps >= 60
                ? SIXTY_FPS_DURATION_NS
                : Math.max(manualFrameDurationNs, preferred);
        long period = effectiveFlickerPeriodNsLocked();
        if (period > 0L) {
            ExposureSetting safe = solveFlickerSafeSettingForProductLocked(
                    targetProduct, preferred, period, maxAllowed);
            if (safe != null) return safe;
        }

        double gain = targetProduct / Math.max(1.0, preferredProduct);
        long desired = clampExposure(Math.round(preferred * gain));
        desired = Math.min(desired, maxAllowed);
        return new ExposureSetting(desired, solveIsoForProduct(targetProduct, desired));
    }

    private ExposureSetting solveFlickerSafeSettingForProductLocked(
            double targetProduct, long preferredExposureNs, long periodNs, long maxAllowedNs) {
        if (periodNs <= 0L || characteristics == null) return null;
        Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (exposureRange == null || isoRange == null) return null;

        long lower = Math.max(exposureRange.getLower(), periodNs);
        long upper = Math.min(exposureRange.getUpper(), maxAllowedNs);
        long firstPeriods = Math.max(1L, (lower + periodNs - 1L) / periodNs);
        long lastPeriods = upper / periodNs;
        if (lastPeriods < firstPeriods) return null;

        ExposureSetting best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        long preferred = Math.max(periodNs, Math.min(upper, preferredExposureNs));
        for (long periods = firstPeriods; periods <= lastPeriods; periods++) {
            long exposure = periods * periodNs;
            double idealIso = targetProduct / Math.max(1.0, exposure);
            if (idealIso < isoRange.getLower() - 0.5 || idealIso > isoRange.getUpper() + 0.5) continue;
            int iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), (int) Math.round(idealIso)));
            double achieved = Math.max(1.0, (double) exposure * iso);
            double productErrorEv = Math.abs(Math.log(achieved / Math.max(1.0, targetProduct)) / Math.log(2.0));
            double shutterErrorEv = Math.abs(Math.log(exposure / (double) preferred) / Math.log(2.0));
            double score = productErrorEv + 0.01 * shutterErrorEv;
            if (score < bestScore) {
                bestScore = score;
                best = new ExposureSetting(exposure, iso);
            }
        }
        return best;
    }

    private ExposureSetting solveMinimumIsoFlickerSettingLocked(
            double targetProduct, long preferredExposureNs, long periodNs, long maxAllowedNs) {
        if (periodNs <= 0L || characteristics == null) return null;
        Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exposureRange == null) return null;
        int minIso = sensorMinIsoLocked();
        long lower = Math.max(exposureRange.getLower(), periodNs);
        long upper = Math.min(exposureRange.getUpper(), maxAllowedNs);
        long firstPeriods = Math.max(1L, (lower + periodNs - 1L) / periodNs);
        long lastPeriods = upper / periodNs;
        if (lastPeriods < firstPeriods) return null;

        long bestExposure = -1L;
        double bestScore = Double.POSITIVE_INFINITY;
        long preferred = Math.max(periodNs, Math.min(upper, preferredExposureNs));
        for (long periods = firstPeriods; periods <= lastPeriods; periods++) {
            long exposure = periods * periodNs;
            double achieved = Math.max(1.0, (double) exposure * minIso);
            double productErrorEv = Math.abs(Math.log(achieved / Math.max(1.0, targetProduct)) / Math.log(2.0));
            double shutterErrorEv = Math.abs(Math.log(exposure / (double) preferred) / Math.log(2.0));
            double score = productErrorEv + 0.01 * shutterErrorEv;
            if (score < bestScore) {
                bestScore = score;
                bestExposure = exposure;
            }
        }
        return bestExposure < 0L ? null : new ExposureSetting(bestExposure, minIso);
    }

    private static final class ExposureSetting {
        final long exposureNs;
        final int iso;

        ExposureSetting(long exposureNs, int iso) {
            this.exposureNs = exposureNs;
            this.iso = iso;
        }
    }

    private int resultPostRawBoost(TotalCaptureResult result) {
        Integer value = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        return clampPostRawBoost(value == null ? DEFAULT_POST_RAW_BOOST : value);
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

        int minIso = sensorMinIsoLocked();
        double targetLongProduct = Math.max(1.0, (double) longExposureNs * manualIso);
        long period = effectiveFlickerPeriodNsLocked();
        manualFlickerSafetyApplied = false;

        if (period > 0L) {
            // V2.13 MANUAL ownership: solve SHORT from SHORT controls only.  LONG ISO
            // must never feed back into SHORT shutter/ISO or the left SPLIT frame.
            long maxShortAllowed = targetPreviewFps >= 60
                    ? SIXTY_FPS_DURATION_NS : Math.max(manualFrameDurationNs, shortExposureNs);
            long maxLongAllowed = targetPreviewFps >= 60
                    ? SIXTY_FPS_DURATION_NS : Math.max(manualFrameDurationNs, longExposureNs);
            ExposureSetting safeShort = solveMinimumIsoFlickerSettingLocked(
                    Math.max(1.0, (double) shortExposureNs * minIso),
                    shortExposureNs, period, maxShortAllowed);
            ExposureSetting safeLong = solveFlickerSafeSettingForProductLocked(
                    targetLongProduct, longExposureNs, period, maxLongAllowed);

            if (safeShort != null) {
                manualEffectiveShortExposureNs = safeShort.exposureNs;
                manualEffectiveShortIso = safeShort.iso;
            } else {
                manualEffectiveShortExposureNs = shortExposureNs;
                manualEffectiveShortIso = minIso;
            }
            if (safeLong != null) {
                manualEffectiveLongExposureNs = safeLong.exposureNs;
                manualEffectiveLongIso = safeLong.iso;
            } else {
                manualEffectiveLongExposureNs = longExposureNs;
                manualEffectiveLongIso = manualIso;
            }
            manualFlickerSafetyApplied = safeShort != null && safeLong != null;
        } else {
            manualEffectiveShortExposureNs = shortExposureNs;
            manualEffectiveShortIso = minIso;
            manualEffectiveLongExposureNs = longExposureNs;
            manualEffectiveLongIso = manualIso;
        }

        if (targetPreviewFps >= 60 && !manualFlickerSafetyApplied) {
            long cappedShort = Math.min(manualEffectiveShortExposureNs, SIXTY_FPS_DURATION_NS);
            long cappedLong = Math.min(manualEffectiveLongExposureNs, SIXTY_FPS_DURATION_NS);
            manualEffectiveShortExposureNs = clampExposure(cappedShort);
            manualEffectiveLongExposureNs = clampExposure(cappedLong);
            manualEffectiveShortIso = minIso;
            manualEffectiveLongIso = solveIsoForProduct(targetLongProduct, manualEffectiveLongExposureNs);
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

    private int effectiveFlickerLocked() {
        if (flickerMode == FLICKER_MODE_50HZ) return CaptureResult.STATISTICS_SCENE_FLICKER_50HZ;
        if (flickerMode == FLICKER_MODE_60HZ) return CaptureResult.STATISTICS_SCENE_FLICKER_60HZ;
        if (flickerMode == FLICKER_MODE_OFF) return CaptureResult.STATISTICS_SCENE_FLICKER_NONE;
        if (sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ
                || sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) return sceneFlicker;
        return FLICKER_UNKNOWN;
    }

    private long effectiveFlickerPeriodNsLocked() {
        int flicker = effectiveFlickerLocked();
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) return FLICKER_50_PERIOD_NS;
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) return FLICKER_60_PERIOD_NS;
        return 0L;
    }

    private int aeAntibandingModeLocked() {
        if (flickerMode == FLICKER_MODE_50HZ) return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ;
        if (flickerMode == FLICKER_MODE_60HZ) return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ;
        if (flickerMode == FLICKER_MODE_OFF) return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF;
        return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO;
    }

    private static int clampFlickerMode(int mode) {
        return mode >= FLICKER_MODE_AUTO && mode <= FLICKER_MODE_OFF ? mode : FLICKER_MODE_AUTO;
    }

    private static boolean isFlickerSafeExposure(long exposureNs, long periodNs) {
        return periodNs > 0L && exposureNs >= periodNs && exposureNs % periodNs == 0L;
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
                + " flicker=" + flickerStatusLocked();
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

    private String flickerStatusLocked() {
        if (flickerMode == FLICKER_MODE_OFF) return "OFF";
        int effective = effectiveFlickerLocked();
        boolean safe = autoHdrExposure ? autoFlickerSafetySatisfied : manualFlickerSafetyApplied;
        if (effective == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) {
            return (flickerMode == FLICKER_MODE_AUTO ? "AUTO 50Hz " : "50Hz ")
                    + (safe ? "SAFE" : "UNSAFE");
        }
        if (effective == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) {
            return (flickerMode == FLICKER_MODE_AUTO ? "AUTO 60Hz " : "60Hz ")
                    + (safe ? "SAFE" : "UNSAFE");
        }
        if (sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE) return "AUTO UNSAFE(none)";
        return "AUTO UNSAFE(unknown/PWM)";
    }

    private static String flickerModeLabel(int mode) {
        if (mode == FLICKER_MODE_50HZ) return "50Hz";
        if (mode == FLICKER_MODE_60HZ) return "60Hz";
        if (mode == FLICKER_MODE_OFF) return "OFF";
        return "AUTO";
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
