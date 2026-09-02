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
    private static final double AUTO_BRACKET_MAX_EV = 7.0;
    private static final double MANUAL_BRACKET_MAX_EV = 6.0;
    private static final double MANUAL_EXTRA_HEADROOM_EV = 0.25;
    private static final double LONG_CLIP_TRIGGER_FRACTION = 0.005;
    private static final double AUTO_SHORT_CLIP_TARGET = 0.0025;
    private static final double MANUAL_SHORT_CLIP_TARGET = 0.0015;
    private static final double SHORT_CLIP_RELEASE_FRACTION = 0.0005;
    // V1.4.22 AUTO SHORT is a recoverability exposure, not a fixed bracket target.
    // The controller drives local LONG-damaged cells away from SHORT saturation.
    private static final double AUTO_SHORT_RECOVERY_TARGET_PEAK = 0.90;
    private static final double AUTO_SHORT_RECOVERY_RELEASE_PEAK = 0.72;
    private static final double AUTO_SHORT_RECOVERY_MIN_SIGNAL_FRACTION = 0.50;
    // V1.4.23: AUTO SHORT is an information-gain search, not peak chasing.
    private static final double AUTO_SHORT_INFO_GAIN_MIN = 0.08;
    private static final double AUTO_SHORT_PROBE_STEP_EV = 1.0;
    private static final int AUTO_SHORT_PROBE_CONFIRM_SAMPLES = 2;
    private static final double AUTO_SHORT_FLICKER_MODULATION_EV = 0.12;
    private static final double AUTO_SHORT_FLICKER_MIN_CONFIDENCE = 0.65;
    private static final double AUTO_SHORT_FLICKER_MIN_COVERAGE = 0.25;
    private static final double AUTO_SHORT_SCENE_RESET_EV = 0.50;
    private static final double AUTO_SHORT_SCENE_RESET_CELL_FRACTION = 0.60;
    private static final double BRACKET_STEP_UP_EV = 0.50;
    private static final double BRACKET_STEP_DOWN_EV = 0.15;
    private static final int BRACKET_CONFIRM_UP_SAMPLES = 2;
    private static final int BRACKET_CONFIRM_DOWN_SAMPLES = 3;
    private static final double AUTO_BODY_HYSTERESIS_EV = 0.10;
    private static final double AUTO_BODY_MAX_STEP_EV = 0.18;
    private static final int AUTO_BODY_CONFIRM_SAMPLES = 2;
    // LONG is a scene-appearance exposure, not a highlight-protecting AE exposure.
    // Meter the robust P25-P50 scene body. Broad/high histogram tails raise the
    // appearance target because SHORT owns them; genuinely low-light scenes lower
    // it using clean AE only as a one-time scene-key cue, never as brightness authority.
    private static final double AUTO_BODY_TARGET_NORMAL_LINEAR = 0.070;
    private static final double AUTO_BODY_TARGET_HDR_LINEAR = 0.115;
    private static final double AUTO_BODY_TARGET_MIN_LINEAR = 0.040;
    private static final double AUTO_BODY_TARGET_MAX_LINEAR = 0.135;
    private static final double AUTO_BODY_LOW_LIGHT_START_EV = 5.0;
    private static final double AUTO_BODY_LOW_LIGHT_FULL_EV = 8.0;
    private static final double AUTO_BODY_REFERENCE_PRODUCT = (ONE_SECOND_NS / 60.0) * 100.0;
    private static final long ADAPTIVE_PAIR_UPDATE_MIN_NS = 200_000_000L;
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
    private double autoAdaptiveBodyTargetLinear = -1.0;
    private double autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
    private double manualAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
    private double manualBracketFloorEv = AUTO_BRACKET_MIN_EV;
    private long lastAdaptiveStatsFrame = -1L;
    private long lastAdaptivePairUpdateNs;
    private int bracketIncreaseEvidence;
    private int bracketDecreaseEvidence;
    private boolean autoFastShortRecovery;
    private boolean autoShortProbePending;
    private boolean autoShortSearchExhausted;
    private double autoShortProbeBaselineEv = AUTO_BRACKET_DEFAULT_EV;
    private float autoShortProbeBaselineUsable;
    private float autoShortProbeBaselineNearClip = 1.0f;
    private boolean autoShortProbeBaselineFast;
    private int autoShortProbeEvidence;
    private int autoShortSearchLongCells;
    private float autoShortSearchP98 = -1.0f;
    private double autoShortSearchLongProduct = -1.0;
    private int bodyRaiseEvidence;
    private int bodyLowerEvidence;
    private long previewExposureGeneration;
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
            resetAutoShortSearchLocked();
            bodyRaiseEvidence = 0;
            bodyLowerEvidence = 0;
            if (enabled) {
                autoAdaptiveBodyTargetLinear = -1.0;
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

    void captureHdrSet(byte[] shortReliabilityMap) {
        final byte[] frozenReliability = shortReliabilityMap == null
                ? null : shortReliabilityMap.clone();
        cameraHandler.post(() -> beginCaptureLocked(frozenReliability));
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
            autoAdaptiveBodyTargetLinear = -1.0;
            bodyRaiseEvidence = 0;
            bodyLowerEvidence = 0;
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
                long generation = ++previewExposureGeneration;
                CaptureRequest shortRequest = buildManualPreviewRequest(
                        FrameMeta.SHORT, generation, activeShortNs, activeShortIso, postRawBoost);
                CaptureRequest longRequest = buildManualPreviewRequest(
                        FrameMeta.LONG, generation, activeLongNs, activeLongIso, postRawBoost);
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
                                + "  generation=" + generation
                                + "  target=" + targetPreviewFps + " sensor fps");
            }
        } catch (Throwable t) {
            RuntimeLogger.error("REPEATING_FAIL", t);
            listener.onStatus("Repeating request failed: " + t.getMessage());
        }
    }

    private CaptureRequest buildManualPreviewRequest(
            String kind, long generation, long exposureNs, int iso, int postRawBoost)
            throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        configureManualRequest(builder, exposureNs, iso, postRawBoost, true);
        // Keep the exact same FPS range on SHORT and LONG. In forced-60 mode this
        // is explicitly [60,60], and SENSOR_FRAME_DURATION is 16,666,666 ns.
        if (aeFpsRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeFpsRange);
        }
        configurePreviewRotateAndCrop(builder);
        builder.setTag(new PreviewRequestTag(kind, generation));
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
                    String kind;
                    long generation = 0L;
                    if (tagObject instanceof PreviewRequestTag) {
                        PreviewRequestTag previewTag = (PreviewRequestTag) tagObject;
                        kind = previewTag.kind;
                        generation = previewTag.generation;
                    } else if (tagObject instanceof String) {
                        String tag = (String) tagObject;
                        if (TAG_NORMAL.equals(tag)) kind = FrameMeta.NORMAL;
                        else if (TAG_METER.equals(tag)) kind = FrameMeta.METER;
                        else return;
                    } else {
                        return;
                    }

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

                    boolean flickerGuardRequired = FrameMeta.SHORT.equals(kind)
                            && flickerGuardRequiredForShortLocked(exposure, activeLongExposureNs());
                    FrameMeta meta = new FrameMeta(
                            kind, result.getFrameNumber(), timestamp, exposure, iso, generation,
                            flickerGuardRequired);
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

    private void beginCaptureLocked(byte[] frozenShortReliabilityMap) {
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
        captureLongExposureNs = activeLongExposureNs();
        captureLongIso = activeLongIso();
        if (autoHdrExposure && autoShortProbePending) {
            // A darker AUTO tier is never committed to a still capture until the
            // information-gain/flicker test accepts it. Capture from the last accepted
            // tier while a probe is pending so one transient PWM phase can never
            // become the saved HDR source.
            double longProduct = Math.max(
                    1.0, (double) captureLongExposureNs * captureLongIso);
            double baselineProduct = Math.max(
                    1.0, longProduct / Math.pow(2.0, autoShortProbeBaselineEv));
            ExposureSetting acceptedShort = solveShortSettingForProductLocked(
                    baselineProduct, captureLongExposureNs, longProduct,
                    AUTO_BRACKET_MAX_EV, autoShortProbeBaselineFast);
            captureShortExposureNs = acceptedShort.exposureNs;
            captureShortIso = acceptedShort.iso;
            RuntimeLogger.event(
                    "SHORT_TIER",
                    String.format(
                            Locale.US,
                            "capture uses accepted %.2fEV while %.2fEV probe pending",
                            autoShortProbeBaselineEv, autoAdaptiveBracketEv));
        } else {
            captureShortExposureNs = activeShortExposureNs();
            captureShortIso = activeShortIso();
        }
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
                flickerGuardRequiredForShortLocked(
                        captureShortExposureNs, captureLongExposureNs),
                frozenShortReliabilityMap,
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
        autoAdaptiveBodyTargetLinear = -1.0;
        bodyRaiseEvidence = 0;
        bodyLowerEvidence = 0;
        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
        resetAutoShortSearchLocked();
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
        autoAdaptiveBodyTargetLinear = -1.0;
        bodyRaiseEvidence = 0;
        bodyLowerEvidence = 0;
        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
        resetAutoShortSearchLocked();
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
                        autoAdaptiveBodyTargetLinear = -1.0;
                        bodyRaiseEvidence = 0;
                        bodyLowerEvidence = 0;
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
        autoAdaptiveBodyTargetLinear = -1.0;
        bodyRaiseEvidence = 0;
        bodyLowerEvidence = 0;
        autoAdaptiveBracketEv = AUTO_BRACKET_DEFAULT_EV;
        resetAutoShortSearchLocked();
        deriveAdaptiveAutoPairLocked();
        publishAutoHdrSettingsLocked("AUTO_ANCHOR", finishMeter || firstAnchor);

        if (finishMeter) {
            autoMetering = false;
            resetCaptureResultFpsLocked();
            applyPreviewRepeatingLocked();
        }
    }

    private void resetAutoShortSearchLocked() {
        bracketIncreaseEvidence = 0;
        bracketDecreaseEvidence = 0;
        autoFastShortRecovery = false;
        autoShortProbePending = false;
        autoShortSearchExhausted = false;
        autoShortProbeBaselineEv = AUTO_BRACKET_DEFAULT_EV;
        autoShortProbeBaselineUsable = 0.0f;
        autoShortProbeBaselineNearClip = 1.0f;
        autoShortProbeBaselineFast = false;
        autoShortProbeEvidence = 0;
        autoShortSearchLongCells = 0;
        autoShortSearchP98 = -1.0f;
        autoShortSearchLongProduct = -1.0;
    }

    private boolean autoShortSearchSceneChangedLocked(HdrGlView.SceneStats stats) {
        if (!autoShortSearchExhausted) return false;
        if (stats.longRecoveryCells == 0) return true;
        int baselineCells = Math.max(1, autoShortSearchLongCells);
        int cellDelta = Math.abs(stats.longRecoveryCells - autoShortSearchLongCells);
        boolean largeCellChange = cellDelta > Math.max(3, Math.round(
                baselineCells * (float) AUTO_SHORT_SCENE_RESET_CELL_FRACTION));
        boolean veryLargeCellChange = cellDelta > Math.max(5, baselineCells);
        double p98DeltaEv = 0.0;
        if (autoShortSearchP98 > 0.001f && stats.longP98Linear > 0.001f) {
            p98DeltaEv = Math.abs(Math.log(
                    stats.longP98Linear / autoShortSearchP98) / Math.log(2.0));
        }
        double productDeltaEv = 0.0;
        if (autoShortSearchLongProduct > 1.0 && stats.longExposureProduct > 1.0) {
            productDeltaEv = Math.abs(Math.log(
                    stats.longExposureProduct / autoShortSearchLongProduct) / Math.log(2.0));
        }
        // Do not reopen an exhausted tier search because a few clipped cells jitter
        // around a threshold. A new search requires a real body-exposure change, a
        // very large topology change, or both a substantial highlight-topology and
        // P98 change. This permanently prevents the 1/240 <-> 1/480 table oscillation.
        return productDeltaEv >= AUTO_SHORT_SCENE_RESET_EV
                || veryLargeCellChange
                || (largeCellChange && p98DeltaEv >= 0.30);
    }

    private void processHdrSceneStatsLocked(HdrGlView.SceneStats stats) {
        if (previewMode == PreviewMode.NORMAL || stillSessionActive || autoMetering
                || characteristics == null || captureSession == null) return;
        // Exposure updates are asynchronous. Never feed statistics from a retired
        // generation back into the controller after a new SHORT/LONG pair was issued.
        if (stats.exposureGeneration != previewExposureGeneration) return;
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
            double bodyMid = robustSceneBodyMid(stats);
            if (bodyMid > 0.002) {
                double baseBodyTarget = adaptiveSceneBodyTargetLocked(stats);
                autoAdaptiveBodyTargetLinear = baseBodyTarget;
                double desiredBody = clampDouble(baseBodyTarget * brightnessGain, 0.018, 0.70);
                double errorEv = Math.log(desiredBody / bodyMid) / Math.log(2.0);

                if (errorEv > AUTO_BODY_HYSTERESIS_EV) {
                    bodyRaiseEvidence++;
                    bodyLowerEvidence = 0;
                    if (bodyRaiseEvidence >= AUTO_BODY_CONFIRM_SAMPLES) {
                        bodyRaiseEvidence = 0;
                        double stepEv = Math.min(AUTO_BODY_MAX_STEP_EV, errorEv);
                        autoSceneBaseLongProduct = Math.max(1.0,
                                autoSceneBaseLongProduct * Math.pow(2.0, stepEv));
                        pairNeedsUpdate = true;
                    }
                } else if (errorEv < -AUTO_BODY_HYSTERESIS_EV) {
                    bodyLowerEvidence++;
                    bodyRaiseEvidence = 0;
                    if (bodyLowerEvidence >= AUTO_BODY_CONFIRM_SAMPLES) {
                        bodyLowerEvidence = 0;
                        double stepEv = Math.max(-AUTO_BODY_MAX_STEP_EV, errorEv);
                        autoSceneBaseLongProduct = Math.max(1.0,
                                autoSceneBaseLongProduct * Math.pow(2.0, stepEv));
                        pairNeedsUpdate = true;
                    }
                } else {
                    bodyRaiseEvidence = 0;
                    bodyLowerEvidence = 0;
                }

                RuntimeLogger.event(
                        "AUTO_BODY_METER",
                        String.format(
                                Locale.US,
                                "body=%.4f target=%.4f p25=%.4f p50=%.4f p90=%.4f p98=%.4f err=%+.2fEV brightness=%+.1fEV",
                                bodyMid, desiredBody, stats.longP25Linear, stats.longMedianLinear,
                                stats.longP90Linear, stats.longP98Linear, errorEv, displayBrightnessEv));
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

    private double robustSceneBodyMid(HdrGlView.SceneStats stats) {
        double p25 = Math.max(0.0005, stats.longP25Linear);
        double p35 = Math.max(0.0005, stats.longP35Linear);
        double p50 = Math.max(0.0005, stats.longMedianLinear);
        return Math.exp((Math.log(p25) + Math.log(p35) + Math.log(p50)) / 3.0);
    }

    private double adaptiveSceneBodyTargetLocked(HdrGlView.SceneStats stats) {
        double p25 = Math.max(0.0005, stats.longP25Linear);
        double p50 = Math.max(0.0005, stats.longMedianLinear);
        double p90 = Math.max(p50, stats.longP90Linear);
        double p98 = Math.max(p90, stats.longP98Linear);

        // Percentile ratios are exposure-invariant until clipping. A broad p90 tail
        // means a meaningful area is brighter than the body; p98 adds only limited
        // sensitivity to smaller highlights. SHORT owns that tail, not LONG metering.
        double tail90Ev = Math.log(p90 / p50) / Math.log(2.0);
        double tail98Ev = Math.log(p98 / p50) / Math.log(2.0);
        double broadTail = smoothstepDouble(0.60, 1.60, tail90Ev);
        double extremeTail = smoothstepDouble(1.50, 3.50, tail98Ev);
        double hdrStrength = clampDouble(0.70 * broadTail + 0.30 * extremeTail, 0.0, 1.0);
        double target = AUTO_BODY_TARGET_NORMAL_LINEAR
                + (AUTO_BODY_TARGET_HDR_LINEAR - AUTO_BODY_TARGET_NORMAL_LINEAR) * hdrStrength;

        // Scene key comes from our own matched LONG measurement, not the bootstrap AE
        // product. current exposure * target/bodyMid estimates the exposure product
        // this scene would require to place its robust body at the candidate target;
        // that estimate is approximately invariant as our LONG exposure changes.
        double bodyMid = robustSceneBodyMid(stats);
        double requiredProduct = Math.max(1.0,
                stats.longExposureProduct * target / Math.max(bodyMid, 0.0005));
        double sceneDemandEv = Math.log(requiredProduct / AUTO_BODY_REFERENCE_PRODUCT) / Math.log(2.0);
        double lowLight = smoothstepDouble(
                AUTO_BODY_LOW_LIGHT_START_EV, AUTO_BODY_LOW_LIGHT_FULL_EV, sceneDemandEv);
        target *= 1.0 - 0.38 * lowLight;

        // Preserve intentionally low-key scenes without using their highlights as the
        // brightness authority. This term depends on lower-body contrast, not p98.
        double bodySpreadEv = Math.log(p50 / p25) / Math.log(2.0);
        double lowKeyStructure = smoothstepDouble(2.2, 4.0, bodySpreadEv);
        target *= 1.0 - 0.10 * lowKeyStructure;
        return clampDouble(target, AUTO_BODY_TARGET_MIN_LINEAR, AUTO_BODY_TARGET_MAX_LINEAR);
    }

    private static double smoothstepDouble(double edge0, double edge1, double value) {
        double t = clampDouble((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private double adaptBracketEvLocked(
            double currentEv, HdrGlView.SceneStats stats, boolean manual) {
        if (!manual) return adaptAutoShortHeadroomEvLocked(currentEv, stats);

        double minEv = Math.max(
                manualBracketFloorEv, AUTO_BRACKET_DEFAULT_EV + MANUAL_EXTRA_HEADROOM_EV);
        double maxEv = MANUAL_BRACKET_MAX_EV;
        boolean meaningfulLongClip = stats.longMeaningfulClipFraction >= LONG_CLIP_TRIGGER_FRACTION;
        boolean manualShortFragile = stats.shortDarkFraction > 0.94f
                || stats.overlapSamples < 12
                || stats.overlapErrorEv > 0.50f
                || !stats.shortTemporalReliable;
        boolean wantsMore = meaningfulLongClip
                && stats.shortMeaningfulClipFraction > MANUAL_SHORT_CLIP_TARGET
                && !manualShortFragile;
        boolean wantsLess = !meaningfulLongClip
                && stats.shortMeaningfulClipFraction <= SHORT_CLIP_RELEASE_FRACTION
                && currentEv > minEv;

        if (wantsMore) {
            bracketIncreaseEvidence++;
            bracketDecreaseEvidence = 0;
            if (bracketIncreaseEvidence >= BRACKET_CONFIRM_UP_SAMPLES) {
                bracketIncreaseEvidence = 0;
                return clampDouble(
                        Math.min(maxEv, currentEv + BRACKET_STEP_UP_EV), minEv, maxEv);
            }
        } else if (wantsLess) {
            bracketDecreaseEvidence++;
            bracketIncreaseEvidence = 0;
            if (bracketDecreaseEvidence >= BRACKET_CONFIRM_DOWN_SAMPLES) {
                bracketDecreaseEvidence = 0;
                return clampDouble(
                        Math.max(minEv, currentEv - BRACKET_STEP_DOWN_EV), minEv, maxEv);
            }
        } else {
            bracketIncreaseEvidence = 0;
            bracketDecreaseEvidence = 0;
        }
        return clampDouble(currentEv, minEv, maxEv);
    }

    private double adaptAutoShortHeadroomEvLocked(
            double currentEv, HdrGlView.SceneStats stats) {
        double minEv = AUTO_BRACKET_DEFAULT_EV;
        double maxEv = AUTO_BRACKET_MAX_EV;
        boolean localLongDamage = stats.longRecoveryCells > 0;

        if (autoShortSearchSceneChangedLocked(stats)) {
            autoShortSearchExhausted = false;
            autoShortProbePending = false;
            autoShortProbeEvidence = 0;
            bracketIncreaseEvidence = 0;
            RuntimeLogger.event(
                    "SHORT_TIER",
                    "scene-change reset current=" + String.format(Locale.US, "%.2fEV", currentEv));
        }

        if (!localLongDamage) {
            autoShortProbePending = false;
            autoShortSearchExhausted = false;
            autoShortProbeEvidence = 0;
            bracketIncreaseEvidence = 0;
            if (currentEv > minEv) {
                bracketDecreaseEvidence++;
                if (bracketDecreaseEvidence >= BRACKET_CONFIRM_DOWN_SAMPLES) {
                    bracketDecreaseEvidence = 0;
                    double next = Math.max(minEv, currentEv - BRACKET_STEP_DOWN_EV);
                    if (next <= minEv + 0.05) autoFastShortRecovery = false;
                    return next;
                }
            } else {
                bracketDecreaseEvidence = 0;
                autoFastShortRecovery = false;
            }
            return clampDouble(currentEv, minEv, maxEv);
        }
        bracketDecreaseEvidence = 0;

        if (autoShortProbePending) {
            boolean modulationUnsafe = stats.shortFlickerEvidenceCoverage
                    < AUTO_SHORT_FLICKER_MIN_COVERAGE
                    || (stats.shortRowModulationEv >= AUTO_SHORT_FLICKER_MODULATION_EV
                            && stats.shortRowCorrectionConfidence
                                    < AUTO_SHORT_FLICKER_MIN_CONFIDENCE);
            double usableGain = stats.shortRecoveryUsableFraction - autoShortProbeBaselineUsable;
            double clipRelief = autoShortProbeBaselineNearClip
                    - stats.shortRecoveryNearClipFraction;
            double informationGain = Math.max(usableGain, clipRelief);
            RuntimeLogger.event(
                    "SHORT_GAIN_TEST",
                    String.format(
                            Locale.US,
                            "baseline=%.2fEV probe=%.2fEV gain=%+.3f usable=%.3f near=%.3f rowMod=%.3fEV corr=%.3f coverage=%.3f",
                            autoShortProbeBaselineEv, currentEv, informationGain,
                            stats.shortRecoveryUsableFraction, stats.shortRecoveryNearClipFraction,
                            stats.shortRowModulationEv, stats.shortRowCorrectionConfidence,
                            stats.shortFlickerEvidenceCoverage));

            if (modulationUnsafe) {
                autoShortProbePending = false;
                autoShortSearchExhausted = true;
                autoFastShortRecovery = autoShortProbeBaselineFast;
                autoShortSearchLongCells = stats.longRecoveryCells;
                autoShortSearchP98 = stats.longP98Linear;
                autoShortSearchLongProduct = stats.longExposureProduct;
                RuntimeLogger.event(
                        "FAST_SHORT_REJECT",
                        String.format(
                                Locale.US,
                                "uncorrectable-field probe=%.2fEV rollback=%.2fEV rowMod=%.3f corr=%.3f coverage=%.3f",
                                currentEv, autoShortProbeBaselineEv,
                                stats.shortRowModulationEv, stats.shortRowCorrectionConfidence,
                                stats.shortFlickerEvidenceCoverage));
                return autoShortProbeBaselineEv;
            }

            if (informationGain >= AUTO_SHORT_INFO_GAIN_MIN
                    || (stats.shortRecoveryNearClipFraction <= 0.05f
                            && stats.shortRecoveryUsableFraction
                                    >= autoShortProbeBaselineUsable)) {
                autoShortProbePending = false;
                autoShortProbeEvidence = 0;
                RuntimeLogger.event(
                        "FAST_SHORT_ACCEPT",
                        String.format(
                                Locale.US,
                                "probe=%.2fEV gain=%+.3f usable=%.3f near=%.3f rowMod=%.3f corr=%.3f coverage=%.3f",
                                currentEv, informationGain, stats.shortRecoveryUsableFraction,
                                stats.shortRecoveryNearClipFraction, stats.shortRowModulationEv,
                                stats.shortRowCorrectionConfidence,
                                stats.shortFlickerEvidenceCoverage));
            } else {
                autoShortProbeEvidence++;
                if (autoShortProbeEvidence >= AUTO_SHORT_PROBE_CONFIRM_SAMPLES) {
                    autoShortProbePending = false;
                    autoShortProbeEvidence = 0;
                    autoShortSearchExhausted = true;
                    autoFastShortRecovery = autoShortProbeBaselineFast;
                    autoShortSearchLongCells = stats.longRecoveryCells;
                    autoShortSearchP98 = stats.longP98Linear;
                    autoShortSearchLongProduct = stats.longExposureProduct;
                    RuntimeLogger.event(
                            "FAST_SHORT_REJECT",
                            String.format(
                                    Locale.US,
                                    "no-information-gain probe=%.2fEV rollback=%.2fEV gain=%+.3f",
                                    currentEv, autoShortProbeBaselineEv, informationGain));
                    return autoShortProbeBaselineEv;
                }
                return clampDouble(currentEv, minEv, maxEv);
            }
        }

        if (autoShortSearchExhausted) {
            return clampDouble(currentEv, minEv, maxEv);
        }

        boolean localShortHasSignal = stats.shortRecoverySignalFraction
                >= AUTO_SHORT_RECOVERY_MIN_SIGNAL_FRACTION;
        // Any SHORT-near-clipped cell inside a genuinely LONG-damaged region may
        // request one darker probe. The probe is accepted only by measured information
        // gain below, so small emitters are not ignored by a full-frame fraction and
        // bright-but-uninformative emitters cannot drive an endless peak chase.
        boolean unresolved = localShortHasSignal
                && stats.shortRecoveryNearClipCells > 0;
        if (!unresolved || currentEv >= maxEv - 0.01) {
            bracketIncreaseEvidence = 0;
            return clampDouble(currentEv, minEv, maxEv);
        }

        bracketIncreaseEvidence++;
        if (bracketIncreaseEvidence < BRACKET_CONFIRM_UP_SAMPLES) {
            return clampDouble(currentEv, minEv, maxEv);
        }
        bracketIncreaseEvidence = 0;
        autoShortProbeBaselineEv = currentEv;
        autoShortProbeBaselineUsable = stats.shortRecoveryUsableFraction;
        autoShortProbeBaselineNearClip = stats.shortRecoveryNearClipFraction;
        autoShortProbeBaselineFast = autoFastShortRecovery;
        autoShortProbePending = true;
        autoShortProbeEvidence = 0;
        autoFastShortRecovery = true;
        double next = Math.min(maxEv, currentEv + AUTO_SHORT_PROBE_STEP_EV);
        RuntimeLogger.event(
                "SHORT_TIER",
                String.format(
                        Locale.US,
                        "probe %.2f->%.2fEV usable=%.3f near=%.3f cells=%d",
                        currentEv, next, stats.shortRecoveryUsableFraction,
                        stats.shortRecoveryNearClipFraction, stats.longRecoveryCells));
        return next;
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
                targetShortProduct, autoLongExposureNs, achievedLongProduct,
                AUTO_BRACKET_MAX_EV, autoFastShortRecovery);
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
        if (period > 0L && maxAllowed >= period) {
            // Known mains flicker: never cross below a full 50/60-Hz integration
            // period merely to hit the appearance target. Use ISO for the residual.
            long periods = Math.max(1L, Math.round(Math.max(desired, period) / (double) period));
            desired = Math.min(maxAllowed, clampExposure(periods * period));
        } else if (sceneFlicker != CaptureResult.STATISTICS_SCENE_FLICKER_NONE
                && period == 0L) {
            // Unknown/PWM: preserve the clean-AE integration in both directions and
            // move gain first. Changing shutter can expose a modulation phase that
            // was absent from the clean bootstrap frame.
            desired = Math.min(maxAllowed, preferred);
        }
        int iso = solveIsoForProduct(targetProduct, desired);
        return new ExposureSetting(desired, iso);
    }

    private ExposureSetting solveShortSettingForProductLocked(
            double targetProduct, long longExposureNs, double longProduct,
            double maxBracketEv, boolean allowFastRecovery) {
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
        if (period > 0L) {
            if (desired >= period) {
                // Stay on whole flicker periods whenever the required headroom is
                // achievable there.
                long periods = Math.max(1L, desired / period);
                long safe = Math.min(maxAllowed, clampExposure(periods * period));
                return new ExposureSetting(safe, solveIsoForProduct(targetProduct, safe));
            }
            if (allowFastRecovery) {
                // V1.4.22: once localized LONG-damage evidence proves the 1-period
                // minimum-ISO SHORT is still saturated, preserving information wins.
                // Use exact binary subdivisions of the measured mains period and keep
                // sensor ISO at minimum; do not brighten the fast probe back up with ISO.
                double minimumShortProduct = Math.max(
                        1.0, longProduct / Math.pow(2.0, maxBracketEv));
                long fastestAllowed = clampExposure((long) Math.ceil(
                        minimumShortProduct / Math.max(1, minIso)));
                long fast = period;
                while (fast > desired && fast > 1L) {
                    long candidate = Math.max(1L, fast / 2L);
                    if (candidate < fastestAllowed) break;
                    fast = candidate;
                }
                fast = Math.min(maxAllowed, clampExposure(fast));
                return new ExposureSetting(fast, minIso);
            }
            // No proven local need: keep the stable full-period SHORT.
            long safe = Math.min(maxAllowed, clampExposure(period));
            return new ExposureSetting(safe, solveIsoForProduct(targetProduct, safe));
        }
        if (sceneFlicker != CaptureResult.STATISTICS_SCENE_FLICKER_NONE) {
            if (allowFastRecovery) {
                return new ExposureSetting(desired, minIso);
            }
            // Unknown/PWM without localized unresolved clipping: preserve LONG timing
            // and obtain headroom from lower ISO only.
            return new ExposureSetting(maxAllowed, solveIsoForProduct(targetProduct, maxAllowed));
        }
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
                targetShortProduct, manualEffectiveLongExposureNs, achievedLongProduct,
                MANUAL_BRACKET_MAX_EV, false);

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

    private boolean flickerGuardRequiredForShortLocked(
            long shortExposure, long longExposure) {
        if (sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE) return false;
        long period = flickerPeriodNs(sceneFlicker);
        if (period > 0L) {
            return shortExposure + 50_000L < period;
        }
        // Unknown/PWM: any SHORT integration materially faster than LONG is treated
        // as potentially phase-sensitive until the pair-rate field proves otherwise.
        return shortExposure * 5L < Math.max(1L, longExposure) * 4L;
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

    private static final class PreviewRequestTag {
        final String kind;
        final long generation;

        PreviewRequestTag(String kind, long generation) {
            this.kind = kind;
            this.generation = generation;
        }
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
