package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class HdrGlView extends GLSurfaceView {
    enum Mode { NORMAL, SPLIT, HDR }

    interface InputSurfaceListener {
        void onInputSurfaceReady(Surface surface);
    }

    interface SceneStatsListener {
        void onSceneStats(SceneStats stats);
    }

    static final class PublishedPairSnapshot {
        final FrameMeta shortMeta;
        final FrameMeta longMeta;

        PublishedPairSnapshot(FrameMeta shortMeta, FrameMeta longMeta) {
            this.shortMeta = shortMeta;
            this.longMeta = longMeta;
        }
    }

    static final class SceneStats {
        final long shortFrameNumber;
        final long longFrameNumber;
        final double shortExposureProduct;
        final double longExposureProduct;
        final double exposureRatio;
        final long exposureGeneration;
        final float longP25Linear;
        final float longP35Linear;
        final float longMedianLinear;
        final float longP90Linear;
        final float longP98Linear;
        final float longMeaningfulClipFraction;
        final float shortMeaningfulClipFraction;
        // V1.4.22: localized recoverability evidence. AUTO SHORT must protect the
        // exact regions where LONG has lost highlight information; a tiny bulb or
        // reflection must not be diluted by the full-frame clipping fraction.
        final int longRecoveryCells;
        final int shortRecoveryNearClipCells;
        final float shortRecoveryNearClipFraction;
        final float shortRecoveryPeak;
        final float shortRecoverySignalFraction;
        // V1.4.23: information-gain and current-pair flicker evidence. A darker
        // SHORT tier is useful only when it increases valid recovery and can be
        // normalized against stable LONG overlap.
        final float shortRecoveryUsableFraction;
        final float shortRowModulationEv;
        final float shortRowCorrectionConfidence;
        final float shortFlickerEvidenceCoverage;
        final float shortPairChromaTrust;
        final float shortDarkFraction;
        final float calibration;
        final float overlapErrorEv;
        final int overlapSamples;
        final boolean shortTemporalReliable;
        final float shortTemporalUnstableFraction;

        SceneStats(
                long shortFrameNumber,
                long longFrameNumber,
                double shortExposureProduct,
                double longExposureProduct,
                double exposureRatio,
                long exposureGeneration,
                float longP25Linear,
                float longP35Linear,
                float longMedianLinear,
                float longP90Linear,
                float longP98Linear,
                float longMeaningfulClipFraction,
                float shortMeaningfulClipFraction,
                int longRecoveryCells,
                int shortRecoveryNearClipCells,
                float shortRecoveryNearClipFraction,
                float shortRecoveryPeak,
                float shortRecoverySignalFraction,
                float shortRecoveryUsableFraction,
                float shortRowModulationEv,
                float shortRowCorrectionConfidence,
                float shortFlickerEvidenceCoverage,
                float shortPairChromaTrust,
                float shortDarkFraction,
                float calibration,
                float overlapErrorEv,
                int overlapSamples,
                boolean shortTemporalReliable,
                float shortTemporalUnstableFraction) {
            this.shortFrameNumber = shortFrameNumber;
            this.longFrameNumber = longFrameNumber;
            this.shortExposureProduct = shortExposureProduct;
            this.longExposureProduct = longExposureProduct;
            this.exposureRatio = exposureRatio;
            this.exposureGeneration = exposureGeneration;
            this.longP25Linear = longP25Linear;
            this.longP35Linear = longP35Linear;
            this.longMedianLinear = longMedianLinear;
            this.longP90Linear = longP90Linear;
            this.longP98Linear = longP98Linear;
            this.longMeaningfulClipFraction = longMeaningfulClipFraction;
            this.shortMeaningfulClipFraction = shortMeaningfulClipFraction;
            this.longRecoveryCells = longRecoveryCells;
            this.shortRecoveryNearClipCells = shortRecoveryNearClipCells;
            this.shortRecoveryNearClipFraction = shortRecoveryNearClipFraction;
            this.shortRecoveryPeak = shortRecoveryPeak;
            this.shortRecoverySignalFraction = shortRecoverySignalFraction;
            this.shortRecoveryUsableFraction = shortRecoveryUsableFraction;
            this.shortRowModulationEv = shortRowModulationEv;
            this.shortRowCorrectionConfidence = shortRowCorrectionConfidence;
            this.shortFlickerEvidenceCoverage = shortFlickerEvidenceCoverage;
            this.shortPairChromaTrust = shortPairChromaTrust;
            this.shortDarkFraction = shortDarkFraction;
            this.calibration = calibration;
            this.overlapErrorEv = overlapErrorEv;
            this.overlapSamples = overlapSamples;
            this.shortTemporalReliable = shortTemporalReliable;
            this.shortTemporalUnstableFraction = shortTemporalUnstableFraction;
        }
    }

    private final HdrRenderer renderer;
    private volatile InputSurfaceListener inputSurfaceListener;
    private volatile SceneStatsListener sceneStatsListener;
    private volatile Surface currentInputSurface;
    private volatile PublishedPairSnapshot publishedPairSnapshot;

    HdrGlView(Context context) {
        this(context, null);
    }

    HdrGlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        renderer = new HdrRenderer(context.getApplicationContext());
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    void setInputSurfaceListener(InputSurfaceListener listener) {
        inputSurfaceListener = listener;
        Surface surface = currentInputSurface;
        if (listener != null && surface != null && surface.isValid()) {
            post(() -> listener.onInputSurfaceReady(surface));
        }
    }

    void setSceneStatsListener(SceneStatsListener listener) {
        sceneStatsListener = listener;
    }

    void republishInputSurface() {
        InputSurfaceListener listener = inputSurfaceListener;
        Surface surface = currentInputSurface;
        if (listener != null && surface != null && surface.isValid()) {
            post(() -> listener.onInputSurfaceReady(surface));
        }
    }

    void configureInputBufferSize(int width, int height, Runnable ready) {
        queueEvent(() -> {
            renderer.configureInputBufferSize(width, height);
            if (ready != null) post(ready);
        });
    }

    void enqueueMeta(FrameMeta meta) {
        renderer.enqueueMeta(meta);
        requestRender();
    }

    void setMode(Mode mode) {
        renderer.mode = mode;
        requestRender();
    }

    void setProducerOwnedOrientationDegrees(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        // SurfaceTexture.getTransformMatrix() is consumed in the OES pass and the
        // V1.4.2 device result proved that adding a second display quarter-turn leaves
        // portrait preview sideways. Keep display sampling unrotated and retain only
        // the axis-swap information required for correct FIT geometry.
        renderer.rotationQuarterTurns = 0;
        renderer.producerAxisSwap = ((normalized / 90) & 1) != 0;
        requestRender();
    }

    long getDroppedRenderFrames() {
        return renderer.droppedFrames;
    }

    double getInputFps() {
        return renderer.inputFps;
    }

    double getHdrPairFps() {
        return renderer.hdrPairFps;
    }

    byte[] snapshotShortReliabilityMap() {
        return renderer.snapshotShortReliabilityMap();
    }

    PublishedPairSnapshot snapshotPublishedPair() {
        return publishedPairSnapshot;
    }

    private void publishInputSurface(Surface surface) {
        currentInputSurface = surface;
        InputSurfaceListener listener = inputSurfaceListener;
        if (listener != null) {
            post(() -> listener.onInputSurfaceReady(surface));
        }
    }

    private final class HdrRenderer implements GLSurfaceView.Renderer {
        private static final int PENDING_SLOTS = 6;
        private static final int STATS_WIDTH = 32;
        private static final int STATS_HEIGHT = 24;
        private static final int STATS_PIXELS = STATS_WIDTH * STATS_HEIGHT;
        private static final int FLICKER_FIELD_WIDTH = 16;
        private static final int FLICKER_ROW_HEIGHT = 64;
        private static final long STATS_INTERVAL_NS = 200_000_000L;
        private static final float MEANINGFUL_CLIP_CHANNEL = 0.992f;
        private static final float MEANINGFUL_CLIP_LUMA = 0.72f;
        // V1.4.22 localized SHORT-headroom meter. These are encoded-signal
        // validity thresholds, not scene/exposure presets. LONG-damaged cells are
        // evaluated separately so small emitters/reflections can own SHORT headroom.
        private static final float RECOVERY_LONG_PEAK = 0.990f;
        private static final float RECOVERY_LONG_SECOND = 0.975f;
        private static final float RECOVERY_LONG_LUMA = 0.60f;
        private static final float RECOVERY_SHORT_NEAR_CLIP = 0.900f;
        private static final float RECOVERY_SHORT_SIGNAL = 0.020f;
        private static final float RECOVERY_SHORT_USABLE_SECOND = 0.940f;
        private static final float ROW_MODULATION_REPORT_EV = 0.10f;
        // V1.4.18 temporal continuity: luma/detail trust releases gradually so one
        // marginal SHORT sample cannot make a recoverable highlight blink off. Chroma
        // releases faster so unstable processed-ISP tint is rejected before detail.
        private static final int RELIABILITY_MAX = 255;
        private static final int LUMA_RELIABILITY_INITIAL = 224;
        private static final int CHROMA_RELIABILITY_INITIAL = 128;
        private static final int LUMA_RELIABILITY_ATTACK = 48;
        private static final int LUMA_RELIABILITY_RELEASE = 64;
        private static final int CHROMA_RELIABILITY_ATTACK = 48;
        private static final int CHROMA_RELIABILITY_RELEASE = 96;
        // V1.4.19 scene-learned processed-response compensation. The exposure ratio
        // remains physical authority; these five scale knots learn only the smooth
        // residual ISP/tone response from mutually valid SHORT/LONG overlap.
        private static final int PHOTO_KNOT_COUNT = 5;
        private static final float[] PHOTO_LUMA_KNOTS = {0.020f, 0.060f, 0.150f, 0.350f, 0.700f};
        private static final float PHOTO_SCALE_MIN = 0.60f;
        private static final float PHOTO_SCALE_MAX = 1.50f;
        private static final float PHOTO_MAX_UPDATE_EV = 0.06f;
        private static final float PHOTO_VISIBLE_RATE_EV_PER_SECOND = 0.0f;
        private static final float PHOTO_VISIBLE_FAST_RATE_EV_PER_SECOND = 1.20f;
        private static final long PHOTO_VISIBLE_FAST_NS = 650_000_000L;
        private static final float PHOTO_SHORT_ONLY_MODULATION_EV = 0.12f;
        private static final float PHOTO_LONG_STABLE_EV = 0.08f;
        private static final int PHOTO_MIN_BIN_SAMPLES = 12;

        private final Context context;
        private final FloatBuffer vertexBuffer;
        private final FloatBuffer displayUvBuffer;
        private final Map<Long, FrameMeta> metaByTimestamp = new ConcurrentHashMap<>();
        private final AtomicInteger frameSignals = new AtomicInteger();
        private final PendingFrame[] pendingFrames = new PendingFrame[PENDING_SLOTS];

        volatile Mode mode = Mode.HDR;
        volatile int rotationQuarterTurns = 0;
        volatile boolean producerAxisSwap;
        volatile long droppedFrames = 0;
        volatile double inputFps = 0.0;
        volatile double hdrPairFps = 0.0;

        private int oesProgram;
        private int copyProgram;
        private int flickerFieldProgram;
        private int displayProgram;
        private int externalTexture;
        private int normalTexture;
        private int shortTexture;
        private int longTexture;
        private int stagingShortTexture;
        private int stagingLongTexture;
        private int statsTexture;
        private int shortReliabilityTexture;
        private int flickerFieldTexture;
        private int framebuffer;
        private SurfaceTexture surfaceTexture;
        private final ByteBuffer longStatsBuffer = ByteBuffer.allocateDirect(STATS_PIXELS * 4)
                .order(ByteOrder.nativeOrder());
        private final ByteBuffer shortStatsBuffer = ByteBuffer.allocateDirect(STATS_PIXELS * 4)
                .order(ByteOrder.nativeOrder());
        private final ByteBuffer shortReliabilityBuffer = ByteBuffer.allocateDirect(STATS_PIXELS * 2)
                .order(ByteOrder.nativeOrder());
        private long lastStatsNs;
        private float shortCalibration = 1.0f;
        private final float[] shortPhotoScale = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
        private final float[] shortPhotoTargetScale = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
        private long shortPhotoVisibleGeneration = Long.MIN_VALUE;
        private long shortPhotoVisibleLastNs;
        private long shortPhotoVisibleFastUntilNs;
        private boolean havePreviousStats;
        private final int[] shortLumaReliability = new int[STATS_PIXELS];
        private final int[] shortChromaReliability = new int[STATS_PIXELS];
        private volatile byte[] latestShortReliabilitySnapshot = defaultReliabilitySnapshot();
        private final float[] previousLongLuma = new float[STATS_PIXELS];
        private final float[] previousShortSceneR = new float[STATS_PIXELS];
        private final float[] previousShortSceneG = new float[STATS_PIXELS];
        private final float[] previousShortSceneB = new float[STATS_PIXELS];
        private final float[] previousShortRawLuma = new float[STATS_PIXELS];
        private long highestExposureGenerationSeen;
        private Surface inputSurface;
        private int frameWidth;
        private int frameHeight;
        private int surfaceWidth;
        private int surfaceHeight;
        private boolean haveNormal;
        private boolean haveShort;
        private boolean haveLong;
        private boolean haveStagingShort;
        private FrameMeta stagingShortMeta;
        private FrameMeta lastShortMeta;
        private FrameMeta lastLongMeta;
        private long fpsWindowStartNs;
        private long lastFrameErrorLogNs;
        private int fpsWindowInputFrames;
        private int fpsWindowPairs;
        private final float[] textureTransform = new float[16];

        HdrRenderer(Context context) {
            this.context = context;
            float[] vertices = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
            float[] displayUvs = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
            vertexBuffer = directFloatBuffer(vertices);
            displayUvBuffer = directFloatBuffer(displayUvs);
            for (int i = 0; i < pendingFrames.length; i++) {
                pendingFrames[i] = new PendingFrame();
            }
        }

        byte[] snapshotShortReliabilityMap() {
            return latestShortReliabilitySnapshot.clone();
        }

        void enqueueMeta(FrameMeta meta) {
            metaByTimestamp.put(meta.sensorTimestampNs, meta);
            if (metaByTimestamp.size() > 64) {
                Long oldest = null;
                for (Long timestamp : metaByTimestamp.keySet()) {
                    if (oldest == null || timestamp < oldest) oldest = timestamp;
                }
                if (oldest != null) metaByTimestamp.remove(oldest);
            }
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            String vertexShader = loadAsset(context, "shaders/fullscreen.vert");
            String oesShader = loadAsset(context, "shaders/oes_to_rgb.frag");
            String copyShader = loadAsset(context, "shaders/copy_2d.frag");
            String flickerFieldShader = loadAsset(context, "shaders/flicker_field.frag");
            String displayShader = loadAsset(context, "shaders/hdr_display.frag");
            oesProgram = buildProgram(vertexShader, oesShader);
            copyProgram = buildProgram(vertexShader, copyShader);
            flickerFieldProgram = buildProgram(vertexShader, flickerFieldShader);
            displayProgram = buildProgram(vertexShader, displayShader);

            externalTexture = createExternalTexture();
            normalTexture = createTexture2d();
            shortTexture = createTexture2d();
            longTexture = createTexture2d();
            stagingShortTexture = createTexture2d();
            stagingLongTexture = createTexture2d();
            statsTexture = createTexture2d();
            allocateRgbTexture(statsTexture, STATS_WIDTH, STATS_HEIGHT);
            shortReliabilityTexture = createTexture2d();
            allocateReliabilityTexture(shortReliabilityTexture);
            flickerFieldTexture = createTexture2d();
            allocateRgbTexture(flickerFieldTexture, FLICKER_FIELD_WIDTH, FLICKER_ROW_HEIGHT);
            for (PendingFrame pending : pendingFrames) {
                pending.texture = createTexture2d();
                pending.occupied = false;
            }

            int[] fb = new int[1];
            GLES30.glGenFramebuffers(1, fb, 0);
            framebuffer = fb[0];
            GLES30.glClearColor(0f, 0f, 0f, 1f);

            if (inputSurface != null) inputSurface.release();
            if (surfaceTexture != null) surfaceTexture.release();
            surfaceTexture = new SurfaceTexture(externalTexture);
            if (frameWidth > 0 && frameHeight > 0) {
                surfaceTexture.setDefaultBufferSize(frameWidth, frameHeight);
            }
            surfaceTexture.setOnFrameAvailableListener(texture -> {
                frameSignals.incrementAndGet();
                requestRender();
            });
            inputSurface = new Surface(surfaceTexture);
            metaByTimestamp.clear();
            haveNormal = false;
            haveShort = false;
            haveLong = false;
            haveStagingShort = false;
            stagingShortMeta = null;
            lastShortMeta = null;
            lastLongMeta = null;
            publishedPairSnapshot = null;
            lastStatsNs = 0L;
            shortCalibration = 1.0f;
            Arrays.fill(shortPhotoScale, 1.0f);
            Arrays.fill(shortPhotoTargetScale, 1.0f);
            shortPhotoVisibleGeneration = Long.MIN_VALUE;
            shortPhotoVisibleLastNs = 0L;
            shortPhotoVisibleFastUntilNs = 0L;
            Arrays.fill(previousShortRawLuma, 0.0f);
            resetShortReliabilityHistory();
            havePreviousStats = false;
            highestExposureGenerationSeen = 0L;
            fpsWindowStartNs = System.nanoTime();
            fpsWindowInputFrames = 0;
            fpsWindowPairs = 0;
            RuntimeLogger.event(
                    "GL_READY",
                    "vendor=" + GLES30.glGetString(GLES30.GL_VENDOR)
                            + " renderer=" + GLES30.glGetString(GLES30.GL_RENDERER)
                            + " version=" + GLES30.glGetString(GLES30.GL_VERSION));
            publishInputSurface(inputSurface);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceWidth = width;
            surfaceHeight = height;
            GLES30.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            int signals = frameSignals.getAndSet(0);
            if (signals > 0 && surfaceTexture != null) {
                if (signals > 1) droppedFrames += signals - 1L;
                processLatestCameraFrame();
            }
            reconcilePendingFrames();
            maybePublishSceneStats();
            drawDisplay();
            updateFps();
        }

        void configureInputBufferSize(int width, int height) {
            if (width <= 0 || height <= 0) return;
            frameWidth = width;
            frameHeight = height;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(width, height);
            }
            allocateRgbTexture(normalTexture, width, height);
            allocateRgbTexture(shortTexture, width, height);
            allocateRgbTexture(longTexture, width, height);
            allocateRgbTexture(stagingShortTexture, width, height);
            allocateRgbTexture(stagingLongTexture, width, height);
            for (PendingFrame pending : pendingFrames) {
                allocateRgbTexture(pending.texture, width, height);
                pending.occupied = false;
            }
            haveNormal = false;
            haveShort = false;
            haveLong = false;
            haveStagingShort = false;
            stagingShortMeta = null;
            lastShortMeta = null;
            lastLongMeta = null;
            publishedPairSnapshot = null;
            lastStatsNs = 0L;
            shortCalibration = 1.0f;
            Arrays.fill(shortPhotoScale, 1.0f);
            Arrays.fill(shortPhotoTargetScale, 1.0f);
            shortPhotoVisibleGeneration = Long.MIN_VALUE;
            shortPhotoVisibleLastNs = 0L;
            shortPhotoVisibleFastUntilNs = 0L;
            Arrays.fill(previousShortRawLuma, 0.0f);
            resetShortReliabilityHistory();
            havePreviousStats = false;
            highestExposureGenerationSeen = 0L;
            metaByTimestamp.clear();
        }

        private void processLatestCameraFrame() {
            if (frameWidth <= 0 || frameHeight <= 0) return;
            try {
                surfaceTexture.updateTexImage();
                long timestamp = surfaceTexture.getTimestamp();
                surfaceTexture.getTransformMatrix(textureTransform);
                FrameMeta meta = metaByTimestamp.remove(timestamp);
                if (meta != null) {
                    if (FrameMeta.METER.equals(meta.kind)) {
                        // AE metering probes are intentionally not displayed and never
                        // become one side of an HDR pair. updateTexImage() above still
                        // releases the producer buffer promptly.
                        acceptMeta(meta);
                    } else {
                        int target = targetTextureFor(meta);
                        renderExternalToTexture(target);
                        acceptMeta(meta);
                    }
                } else {
                    PendingFrame pending = acquirePendingSlot();
                    pending.timestampNs = timestamp;
                    pending.occupied = true;
                    renderExternalToTexture(pending.texture);
                }
                fpsWindowInputFrames++;
            } catch (RuntimeException e) {
                droppedFrames++;
                long now = System.nanoTime();
                if (lastFrameErrorLogNs == 0L || now - lastFrameErrorLogNs >= 5_000_000_000L) {
                    lastFrameErrorLogNs = now;
                    RuntimeLogger.error("GL_FRAME_FAIL", e);
                }
            }
        }

        private PendingFrame acquirePendingSlot() {
            for (PendingFrame pending : pendingFrames) {
                if (!pending.occupied) return pending;
            }
            PendingFrame oldest = pendingFrames[0];
            for (PendingFrame pending : pendingFrames) {
                if (pending.timestampNs < oldest.timestampNs) oldest = pending;
            }
            metaByTimestamp.remove(oldest.timestampNs);
            droppedFrames++;
            return oldest;
        }

        private void reconcilePendingFrames() {
            for (PendingFrame pending : pendingFrames) {
                if (!pending.occupied) continue;
                FrameMeta meta = metaByTimestamp.remove(pending.timestampNs);
                if (meta == null) continue;
                if (!FrameMeta.METER.equals(meta.kind)) {
                    copyTexture(pending.texture, targetTextureFor(meta));
                }
                pending.occupied = false;
                acceptMeta(meta);
            }
        }

        private int targetTextureFor(FrameMeta meta) {
            if (FrameMeta.SHORT.equals(meta.kind)) return stagingShortTexture;
            if (FrameMeta.LONG.equals(meta.kind)) return stagingLongTexture;
            return normalTexture;
        }

        private void acceptMeta(FrameMeta meta) {
            if (FrameMeta.METER.equals(meta.kind)) {
                return;
            }
            if (FrameMeta.SHORT.equals(meta.kind) || FrameMeta.LONG.equals(meta.kind)) {
                if (meta.exposureGeneration < highestExposureGenerationSeen) return;
                if (meta.exposureGeneration > highestExposureGenerationSeen) {
                    highestExposureGenerationSeen = meta.exposureGeneration;
                    haveStagingShort = false;
                    stagingShortMeta = null;
                }
            }
            if (FrameMeta.SHORT.equals(meta.kind)) {
                haveStagingShort = true;
                stagingShortMeta = meta;
                return;
            }
            if (FrameMeta.LONG.equals(meta.kind)) {
                // Publish only an exact exposure-generation pair. Old requests can
                // remain in the Camera2 pipeline after an adaptive update; they may
                // never be mixed with the new generation. Until a complete new pair
                // arrives, the previous complete HDR image remains on screen.
                if (haveStagingShort && stagingShortMeta != null
                        && stagingShortMeta.exposureGeneration == meta.exposureGeneration
                        && meta.frameNumber > stagingShortMeta.frameNumber
                        && meta.frameNumber - stagingShortMeta.frameNumber <= 3) {
                    int oldShort = shortTexture;
                    shortTexture = stagingShortTexture;
                    stagingShortTexture = oldShort;
                    int oldLong = longTexture;
                    longTexture = stagingLongTexture;
                    stagingLongTexture = oldLong;
                    lastShortMeta = stagingShortMeta;
                    lastLongMeta = meta;
                    publishedPairSnapshot = new PublishedPairSnapshot(lastShortMeta, lastLongMeta);
                    haveShort = true;
                    haveLong = true;
                    haveStagingShort = false;
                    stagingShortMeta = null;
                    fpsWindowPairs++;
                }
                return;
            }
            haveNormal = true;
        }

        private void renderExternalToTexture(int targetTexture) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            GLES30.glViewport(0, 0, frameWidth, frameHeight);
            GLES30.glUseProgram(oesProgram);
            bindQuad();
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram, "cameraTex"), 0);
            GLES30.glUniformMatrix4fv(
                    GLES30.glGetUniformLocation(oesProgram, "texTransform"),
                    1,
                    false,
                    textureTransform,
                    0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        private void copyTexture(int sourceTexture, int targetTexture) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            GLES30.glViewport(0, 0, frameWidth, frameHeight);
            GLES30.glUseProgram(copyProgram);
            bindQuad();
            bindSampler2d(copyProgram, "sourceTex", sourceTexture, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        private void renderFlickerRowField(float ratio) {
            if (!haveShort || !haveLong || flickerFieldTexture == 0) return;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D,
                    flickerFieldTexture,
                    0);
            GLES30.glViewport(0, 0, FLICKER_FIELD_WIDTH, FLICKER_ROW_HEIGHT);
            GLES30.glUseProgram(flickerFieldProgram);
            bindQuad();
            bindSampler2d(flickerFieldProgram, "shortTex", shortTexture, 0);
            bindSampler2d(flickerFieldProgram, "longTex", longTexture, 1);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(flickerFieldProgram, "exposureRatio"), ratio);
            GLES30.glUniform4f(
                    GLES30.glGetUniformLocation(flickerFieldProgram, "shortPhotoScaleA"),
                    shortPhotoScale[0], shortPhotoScale[1],
                    shortPhotoScale[2], shortPhotoScale[3]);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(flickerFieldProgram, "shortPhotoScaleB"),
                    shortPhotoScale[4]);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        private void drawDisplay() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return;
            float ratio = 1.0f;
            if (lastShortMeta != null && lastLongMeta != null) {
                double r = lastLongMeta.exposureProduct() / lastShortMeta.exposureProduct();
                ratio = (float) Math.max(1.0, Math.min(65_536.0, r));
            }
            advanceVisiblePhotoCurve();
            renderFlickerRowField(ratio);

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glUseProgram(displayProgram);
            bindQuad();
            bindSampler2d(displayProgram, "normalTex", normalTexture, 0);
            bindSampler2d(displayProgram, "shortTex", shortTexture, 1);
            bindSampler2d(displayProgram, "longTex", longTexture, 2);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "mode"), mode.ordinal());
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "rotationQuarterTurns"),
                    rotationQuarterTurns);
            setFitScaleUniform(displayProgram, "fullFitScale", surfaceWidth, surfaceHeight);
            setFitScaleUniform(displayProgram, "splitFitScale", surfaceWidth * 0.5f, surfaceHeight);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveNormal"), haveNormal ? 1 : 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveShort"), haveShort ? 1 : 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveLong"), haveLong ? 1 : 0);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(displayProgram, "exposureRatio"), ratio);
            GLES30.glUniform4f(
                    GLES30.glGetUniformLocation(displayProgram, "shortPhotoScaleA"),
                    shortPhotoScale[0], shortPhotoScale[1],
                    shortPhotoScale[2], shortPhotoScale[3]);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "shortPhotoScaleB"),
                    shortPhotoScale[4]);
            int minDimension = Math.max(1, Math.min(frameWidth, frameHeight));
            float fusionRadiusPixels = Math.max(1.0f, Math.min(4.0f, Math.round(minDimension / 720.0f)));
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "fusionTexelStep"),
                    fusionRadiusPixels / Math.max(1.0f, frameWidth),
                    fusionRadiusPixels / Math.max(1.0f, frameHeight));
            bindSampler2d(displayProgram, "shortReliabilityTex", shortReliabilityTexture, 3);
            bindSampler2d(displayProgram, "flickerFieldTex", flickerFieldTexture, 4);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "flickerGuardRequired"),
                    lastShortMeta != null && lastShortMeta.flickerGuardRequired ? 1 : 0);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "reliabilityUvScale"),
                    1.0f, 1.0f);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "reliabilityUvOffset"),
                    0.0f, 0.0f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void maybePublishSceneStats() {
            SceneStatsListener listener = sceneStatsListener;
            if (listener == null || !haveShort || !haveLong
                    || lastShortMeta == null || lastLongMeta == null) return;
            long now = System.nanoTime();
            if (lastStatsNs != 0L && now - lastStatsNs < STATS_INTERVAL_NS) return;
            lastStatsNs = now;

            double shortProduct = lastShortMeta.exposureProduct();
            double longProduct = lastLongMeta.exposureProduct();
            double ratio = Math.max(1.0, Math.min(65_536.0, longProduct / shortProduct));
            readTextureStats(longTexture, longStatsBuffer);
            readTextureStats(shortTexture, shortStatsBuffer);
            SceneStats stats = calculateSceneStats(
                    longStatsBuffer, shortStatsBuffer, ratio,
                    lastShortMeta, lastLongMeta);
            shortCalibration = stats.calibration;
            uploadShortReliabilityTexture();
            listener.onSceneStats(stats);
        }

        private void readTextureStats(int sourceTexture, ByteBuffer target) {
            target.clear();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, statsTexture, 0);
            GLES30.glViewport(0, 0, STATS_WIDTH, STATS_HEIGHT);
            GLES30.glUseProgram(copyProgram);
            bindQuad();
            bindSampler2d(copyProgram, "sourceTex", sourceTexture, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            GLES30.glReadPixels(
                    0, 0, STATS_WIDTH, STATS_HEIGHT,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, target);
            target.rewind();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        private SceneStats calculateSceneStats(
                ByteBuffer longPixels, ByteBuffer shortPixels, double ratio,
                FrameMeta shortMeta, FrameMeta longMeta) {
            float[] longLumas = new float[STATS_PIXELS];
            float[] calibrationRatios = new float[STATS_PIXELS];
            float[] photoRatios = new float[STATS_PIXELS];
            float[] overlapShortLumas = new float[STATS_PIXELS];
            int overlapCount = 0;
            int longClipped = 0;
            int shortClipped = 0;
            int longRecoveryCells = 0;
            int shortRecoveryNearClipCells = 0;
            int shortRecoverySignalCells = 0;
            int shortRecoveryUsableCells = 0;
            float shortRecoveryPeak = 0.0f;
            int shortDark = 0;
            float[] rowLogGainSum = new float[STATS_HEIGHT];
            float[] rowLogGainSqSum = new float[STATS_HEIGHT];
            float[] rowChromaSum = new float[STATS_HEIGHT];
            int[] rowOverlapCount = new int[STATS_HEIGHT];

            for (int i = 0; i < STATS_PIXELS; i++) {
                int o = i * 4;
                float lr8 = (longPixels.get(o) & 0xFF) / 255.0f;
                float lg8 = (longPixels.get(o + 1) & 0xFF) / 255.0f;
                float lb8 = (longPixels.get(o + 2) & 0xFF) / 255.0f;
                float sr8 = (shortPixels.get(o) & 0xFF) / 255.0f;
                float sg8 = (shortPixels.get(o + 1) & 0xFF) / 255.0f;
                float sb8 = (shortPixels.get(o + 2) & 0xFF) / 255.0f;

                float lr = srgbToLinear(lr8);
                float lg = srgbToLinear(lg8);
                float lb = srgbToLinear(lb8);
                float sr = srgbToLinear(sr8);
                float sg = srgbToLinear(sg8);
                float sb = srgbToLinear(sb8);
                float longLuma = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                float shortLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;
                longLumas[i] = longLuma;

                float longMax = Math.max(lr8, Math.max(lg8, lb8));
                float longSecond = secondLargest(lr8, lg8, lb8);
                if (longMax >= MEANINGFUL_CLIP_CHANNEL
                        && (longLuma >= MEANINGFUL_CLIP_LUMA || longSecond >= 0.985f)) {
                    longClipped++;
                }
                float shortMax = Math.max(sr8, Math.max(sg8, sb8));
                float shortSecond = secondLargest(sr8, sg8, sb8);
                if (shortMax >= MEANINGFUL_CLIP_CHANNEL
                        && (shortLuma >= MEANINGFUL_CLIP_LUMA || shortSecond >= 0.985f)) {
                    shortClipped++;
                }

                // Local recoverability contract: only ask SHORT for more headroom
                // where LONG has actually lost highlight information. This avoids
                // making the entire SHORT frame chase an arbitrary EV bracket while
                // still allowing a small bulb/TV/reflection to demand protection.
                boolean longRecoveryCell = longMax >= RECOVERY_LONG_PEAK
                        && (longSecond >= RECOVERY_LONG_SECOND
                                || longLuma >= RECOVERY_LONG_LUMA);
                if (longRecoveryCell) {
                    longRecoveryCells++;
                    float recoveryPeak = Math.max(shortSecond, shortLuma);
                    shortRecoveryPeak = Math.max(shortRecoveryPeak, recoveryPeak);
                    if (recoveryPeak >= RECOVERY_SHORT_NEAR_CLIP) {
                        shortRecoveryNearClipCells++;
                    }
                    if (shortLuma >= RECOVERY_SHORT_SIGNAL) {
                        shortRecoverySignalCells++;
                        if (shortSecond < RECOVERY_SHORT_USABLE_SECOND) {
                            shortRecoveryUsableCells++;
                        }
                    }
                }
                if (shortLuma < 0.008f) shortDark++;

                boolean validLong = lr8 > 0.08f && lg8 > 0.08f && lb8 > 0.08f
                        && lr8 < 0.90f && lg8 < 0.90f && lb8 < 0.90f;
                boolean validShort = sr8 > 0.015f && sg8 > 0.015f && sb8 > 0.015f
                        && sr8 < 0.90f && sg8 < 0.90f && sb8 < 0.90f;
                float normalizedShortLuma = (float) (shortLuma * ratio);
                boolean shortOnlyModulated = false;
                if (havePreviousStats && previousLongLuma[i] > 0.01f
                        && previousShortRawLuma[i] > 0.01f
                        && longLuma > 0.01f && normalizedShortLuma > 0.01f) {
                    float longDeltaEv = (float) Math.abs(
                            Math.log(longLuma / previousLongLuma[i]) / Math.log(2.0));
                    float shortDeltaEv = (float) Math.abs(
                            Math.log(normalizedShortLuma / previousShortRawLuma[i]) / Math.log(2.0));
                    shortOnlyModulated = longDeltaEv <= PHOTO_LONG_STABLE_EV
                            && shortDeltaEv >= PHOTO_SHORT_ONLY_MODULATION_EV;
                }
                if (validLong && validShort && normalizedShortLuma > 0.015f
                        && !shortOnlyModulated) {
                    float responseRatio = longLuma / normalizedShortLuma;
                    calibrationRatios[overlapCount] = clampCalibration(responseRatio);
                    photoRatios[overlapCount] = clampFloat(
                            responseRatio, PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
                    overlapShortLumas[overlapCount] = normalizedShortLuma;
                    overlapCount++;
                }
            }

            Arrays.sort(longLumas);
            float p25 = percentileSorted(longLumas, 0.25f);
            float p35 = percentileSorted(longLumas, 0.35f);
            float median = percentileSorted(longLumas, 0.50f);
            float p90 = percentileSorted(longLumas, 0.90f);
            float p98 = percentileSorted(longLumas, 0.98f);
            float[] calibrationCopy = Arrays.copyOf(calibrationRatios, overlapCount);
            float calibration = medianPrefix(calibrationCopy, overlapCount, 1.0f);
            updateShortPhotoCurve(
                    overlapShortLumas, photoRatios, overlapCount, calibration);

            float[] errors = new float[Math.max(1, overlapCount)];
            int errorCount = 0;
            int temporalHighlightCells = 0;
            int unstableHighlightCells = 0;
            shortReliabilityBuffer.clear();
            for (int i = 0; i < STATS_PIXELS; i++) {
                int o = i * 4;
                float lr8 = (longPixels.get(o) & 0xFF) / 255.0f;
                float lg8 = (longPixels.get(o + 1) & 0xFF) / 255.0f;
                float lb8 = (longPixels.get(o + 2) & 0xFF) / 255.0f;
                float sr8 = (shortPixels.get(o) & 0xFF) / 255.0f;
                float sg8 = (shortPixels.get(o + 1) & 0xFF) / 255.0f;
                float sb8 = (shortPixels.get(o + 2) & 0xFF) / 255.0f;
                float lr = srgbToLinear(lr8);
                float lg = srgbToLinear(lg8);
                float lb = srgbToLinear(lb8);
                float srLinear = srgbToLinear(sr8);
                float sgLinear = srgbToLinear(sg8);
                float sbLinear = srgbToLinear(sb8);
                float shortRawLuma = (float) ((0.2126f * srLinear + 0.7152f * sgLinear
                        + 0.0722f * sbLinear) * ratio);
                float photoScale = shortPhotoScaleForLuma(shortRawLuma);
                float sr = (float) (srLinear * ratio * photoScale);
                float sg = (float) (sgLinear * ratio * photoScale);
                float sb = (float) (sbLinear * ratio * photoScale);
                float ll = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                float sl = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;

                boolean validLong = lr8 > 0.08f && lg8 > 0.08f && lb8 > 0.08f
                        && lr8 < 0.90f && lg8 < 0.90f && lb8 < 0.90f;
                boolean validShort = sr8 > 0.015f && sg8 > 0.015f && sb8 > 0.015f
                        && sr8 < 0.90f && sg8 < 0.90f && sb8 < 0.90f;
                if (validLong && validShort && ll > 0.015f && sl > 0.015f) {
                    if (errorCount < errors.length) {
                        errors[errorCount++] = (float) Math.abs(
                                Math.log(sl / ll) / Math.log(2.0));
                    }
                    int row = i / STATS_WIDTH;
                    float logGain = clampFloat(
                            (float) (Math.log(ll / sl) / Math.log(2.0)), -1.5f, 1.5f);
                    float invLl = 1.0f / Math.max(ll, 0.0005f);
                    float invSl = 1.0f / Math.max(sl, 0.0005f);
                    float chromaDelta = Math.max(
                            Math.abs(lr * invLl - sr * invSl),
                            Math.max(
                                    Math.abs(lg * invLl - sg * invSl),
                                    Math.abs(lb * invLl - sb * invSl)));
                    rowLogGainSum[row] += logGain;
                    rowLogGainSqSum[row] += logGain * logGain;
                    rowChromaSum[row] += chromaDelta;
                    rowOverlapCount[row]++;
                }

                float longMax = Math.max(lr8, Math.max(lg8, lb8));
                boolean highlight = longMax >= 0.94f || ll >= 0.45f;
                boolean lumaStable = !highlight;
                boolean chromaStable = !highlight;
                if (highlight) {
                    temporalHighlightCells++;
                    if (havePreviousStats && ll > 0.01f && sl > 0.01f) {
                        float previousLl = previousLongLuma[i];
                        float previousSl = 0.2126f * previousShortSceneR[i]
                                + 0.7152f * previousShortSceneG[i]
                                + 0.0722f * previousShortSceneB[i];
                        boolean longStable = previousLl > 0.01f
                                && Math.abs(Math.log(ll / previousLl) / Math.log(2.0)) <= 0.18;
                        if (longStable && previousSl > 0.01f) {
                            float shortDeltaEv = (float) Math.abs(
                                    Math.log(sl / previousSl) / Math.log(2.0));
                            float invSl = 1.0f / Math.max(sl, 0.0005f);
                            float invPrevSl = 1.0f / Math.max(previousSl, 0.0005f);
                            float chromaDelta = Math.max(
                                    Math.abs(sr * invSl - previousShortSceneR[i] * invPrevSl),
                                    Math.max(
                                            Math.abs(sg * invSl - previousShortSceneG[i] * invPrevSl),
                                            Math.abs(sb * invSl - previousShortSceneB[i] * invPrevSl)));
                            lumaStable = shortDeltaEv <= 0.18f;
                            chromaStable = lumaStable && chromaDelta <= 0.06f;
                        }
                    }

                    shortLumaReliability[i] = lumaStable
                            ? Math.min(RELIABILITY_MAX,
                                    shortLumaReliability[i] + LUMA_RELIABILITY_ATTACK)
                            : Math.max(0,
                                    shortLumaReliability[i] - LUMA_RELIABILITY_RELEASE);
                    shortChromaReliability[i] = chromaStable
                            ? Math.min(RELIABILITY_MAX,
                                    shortChromaReliability[i] + CHROMA_RELIABILITY_ATTACK)
                            : Math.max(0,
                                    shortChromaReliability[i] - CHROMA_RELIABILITY_RELEASE);
                    if (!lumaStable) unstableHighlightCells++;
                } else {
                    shortLumaReliability[i] = RELIABILITY_MAX;
                    shortChromaReliability[i] = RELIABILITY_MAX;
                }

                shortReliabilityBuffer.put((byte) shortLumaReliability[i]);
                shortReliabilityBuffer.put((byte) shortChromaReliability[i]);
                previousLongLuma[i] = ll;
                previousShortSceneR[i] = sr;
                previousShortSceneG[i] = sg;
                previousShortSceneB[i] = sb;
                previousShortRawLuma[i] = shortRawLuma;
            }
            havePreviousStats = true;

            float overlapError = medianPrefix(errors, errorCount, 0.0f);
            float unstableFraction = temporalHighlightCells == 0 ? 0.0f
                    : unstableHighlightCells / (float) temporalHighlightCells;
            shortReliabilityBuffer.flip();
            latestShortReliabilitySnapshot = snapshotReliabilityBuffer();
            // Bracket adaptation only asks whether instability is widespread. The
            // renderer consumes the local R/G reliability texture, so one bad lamp
            // cannot disable a stable window or other recoverable highlight.
            boolean shortTemporalReliable = temporalHighlightCells == 0
                    || unstableFraction <= 0.25f;

            float recoveryNearClipFraction = longRecoveryCells == 0 ? 0.0f
                    : shortRecoveryNearClipCells / (float) longRecoveryCells;
            float recoverySignalFraction = longRecoveryCells == 0 ? 0.0f
                    : shortRecoverySignalCells / (float) longRecoveryCells;
            float recoveryUsableFraction = longRecoveryCells == 0 ? 0.0f
                    : shortRecoveryUsableCells / (float) longRecoveryCells;

            float rowMin = Float.POSITIVE_INFINITY;
            float rowMax = Float.NEGATIVE_INFINITY;
            float rowConfidenceSum = 0.0f;
            float rowChromaTrustSum = 0.0f;
            int rowEvidence = 0;
            for (int row = 0; row < STATS_HEIGHT; row++) {
                int n = rowOverlapCount[row];
                if (n < 3) continue;
                float mean = rowLogGainSum[row] / n;
                float variance = Math.max(0.0f, rowLogGainSqSum[row] / n - mean * mean);
                float sigma = (float) Math.sqrt(variance);
                float confidence = 1.0f - smoothstepFloat(0.20f, 0.60f, sigma);
                float chromaMean = rowChromaSum[row] / n;
                float chromaTrust = confidence
                        * (1.0f - smoothstepFloat(0.060f, 0.200f, chromaMean));
                rowMin = Math.min(rowMin, mean);
                rowMax = Math.max(rowMax, mean);
                rowConfidenceSum += confidence;
                rowChromaTrustSum += chromaTrust;
                rowEvidence++;
            }
            float rowModulationEv = rowEvidence >= 4 ? Math.max(0.0f, rowMax - rowMin) : 0.0f;
            float rowCorrectionConfidence = rowEvidence == 0 ? 0.0f
                    : rowConfidenceSum / rowEvidence;
            float flickerEvidenceCoverage = rowEvidence / (float) STATS_HEIGHT;
            float pairChromaTrust = rowEvidence == 0 ? 0.0f
                    : rowChromaTrustSum / rowEvidence;
            if (rowModulationEv >= ROW_MODULATION_REPORT_EV) {
                RuntimeLogger.event(
                        "FLICKER_FIELD",
                        String.format(
                                java.util.Locale.US,
                                "rowMod=%.3fEV confidence=%.3f coverage=%.3f chroma=%.3f rows=%d",
                                rowModulationEv, rowCorrectionConfidence, flickerEvidenceCoverage,
                                pairChromaTrust, rowEvidence));
            }

            return new SceneStats(
                    shortMeta.frameNumber, longMeta.frameNumber,
                    shortMeta.exposureProduct(), longMeta.exposureProduct(), ratio,
                    longMeta.exposureGeneration, p25, p35, median, p90, p98,
                    longClipped / (float) STATS_PIXELS,
                    shortClipped / (float) STATS_PIXELS,
                    longRecoveryCells, shortRecoveryNearClipCells,
                    recoveryNearClipFraction, shortRecoveryPeak, recoverySignalFraction,
                    recoveryUsableFraction, rowModulationEv, rowCorrectionConfidence,
                    flickerEvidenceCoverage, pairChromaTrust, shortDark / (float) STATS_PIXELS,
                    calibration, overlapError, overlapCount,
                    shortTemporalReliable, unstableFraction);
        }

        private void updateShortPhotoCurve(
                float[] normalizedShortLumas, float[] absoluteRatios,
                int count, float calibration) {
            if (count < 24 || calibration <= 0.0001f) return;
            float[][] bins = new float[PHOTO_KNOT_COUNT][STATS_PIXELS];
            int[] binCounts = new int[PHOTO_KNOT_COUNT];
            for (int i = 0; i < count; i++) {
                int bin = photoBinForLuma(normalizedShortLumas[i]);
                bins[bin][binCounts[bin]++] = absoluteRatios[i];
            }
            float[] target = new float[PHOTO_KNOT_COUNT];
            for (int bin = 0; bin < PHOTO_KNOT_COUNT; bin++) {
                if (binCounts[bin] >= PHOTO_MIN_BIN_SAMPLES) {
                    float absolute = medianPrefix(
                            bins[bin], binCounts[bin], calibration);
                    target[bin] = clampFloat(
                            absolute, PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
                } else {
                    target[bin] = clampFloat(calibration, PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
                }
            }
            float[] smoothTarget = target.clone();
            smoothTarget[0] = clampFloat(
                    0.75f * target[0] + 0.25f * target[1],
                    PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            for (int bin = 1; bin < PHOTO_KNOT_COUNT - 1; bin++) {
                smoothTarget[bin] = clampFloat(
                        0.25f * target[bin - 1] + 0.50f * target[bin]
                                + 0.25f * target[bin + 1],
                        PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            }
            smoothTarget[PHOTO_KNOT_COUNT - 1] = clampFloat(
                    0.25f * target[PHOTO_KNOT_COUNT - 2]
                            + 0.75f * target[PHOTO_KNOT_COUNT - 1],
                    PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            enforceMonotonicPhotoCurve(smoothTarget);
            for (int bin = 0; bin < PHOTO_KNOT_COUNT; bin++) {
                float current = Math.max(0.0001f, shortPhotoTargetScale[bin]);
                float desired = Math.max(0.0001f, smoothTarget[bin]);
                float deltaEv = (float) (Math.log(desired / current) / Math.log(2.0));
                deltaEv = clampFloat(deltaEv, -PHOTO_MAX_UPDATE_EV, PHOTO_MAX_UPDATE_EV);
                shortPhotoTargetScale[bin] = clampFloat(
                        (float) (current * Math.pow(2.0, deltaEv)),
                        PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            }
            enforceMonotonicPhotoCurve(shortPhotoTargetScale);
        }

        private void advanceVisiblePhotoCurve() {
            long now = System.nanoTime();
            long generation = lastLongMeta == null ? Long.MIN_VALUE : lastLongMeta.exposureGeneration;
            if (generation != shortPhotoVisibleGeneration) {
                shortPhotoVisibleGeneration = generation;
                shortPhotoVisibleFastUntilNs = now + PHOTO_VISIBLE_FAST_NS;
            }
            if (shortPhotoVisibleLastNs == 0L) {
                shortPhotoVisibleLastNs = now;
                return;
            }
            float dt = Math.min(0.100f, Math.max(0.0f, (now - shortPhotoVisibleLastNs) * 1.0e-9f));
            shortPhotoVisibleLastNs = now;
            float rate = now <= shortPhotoVisibleFastUntilNs
                    ? PHOTO_VISIBLE_FAST_RATE_EV_PER_SECOND
                    : PHOTO_VISIBLE_RATE_EV_PER_SECOND;
            float maxDeltaEv = rate * dt;
            for (int i = 0; i < PHOTO_KNOT_COUNT; i++) {
                float current = Math.max(0.0001f, shortPhotoScale[i]);
                float target = Math.max(0.0001f, shortPhotoTargetScale[i]);
                float deltaEv = (float) (Math.log(target / current) / Math.log(2.0));
                deltaEv = clampFloat(deltaEv, -maxDeltaEv, maxDeltaEv);
                shortPhotoScale[i] = clampFloat(
                        (float) (current * Math.pow(2.0, deltaEv)),
                        PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            }
            enforceMonotonicPhotoCurve(shortPhotoScale);
        }

        private static void enforceMonotonicPhotoCurve(float[] scale) {
            for (int bin = 1; bin < PHOTO_KNOT_COUNT; bin++) {
                float previousOutput = PHOTO_LUMA_KNOTS[bin - 1] * scale[bin - 1];
                float minimumScale = previousOutput * 1.01f / PHOTO_LUMA_KNOTS[bin];
                scale[bin] = clampFloat(
                        Math.max(scale[bin], minimumScale), PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            }
        }

        private static int photoBinForLuma(float luma) {
            for (int bin = 0; bin < PHOTO_KNOT_COUNT - 1; bin++) {
                float boundary = (float) Math.sqrt(
                        PHOTO_LUMA_KNOTS[bin] * PHOTO_LUMA_KNOTS[bin + 1]);
                if (luma < boundary) return bin;
            }
            return PHOTO_KNOT_COUNT - 1;
        }

        private float shortPhotoScaleForLuma(float normalizedShortLuma) {
            float luma = Math.max(0.00001f, normalizedShortLuma);
            if (luma <= PHOTO_LUMA_KNOTS[0]) return shortPhotoScale[0];
            for (int i = 0; i < PHOTO_KNOT_COUNT - 1; i++) {
                float low = PHOTO_LUMA_KNOTS[i];
                float high = PHOTO_LUMA_KNOTS[i + 1];
                if (luma <= high) {
                    float t = (float) (Math.log(luma / low) / Math.log(high / low));
                    return shortPhotoScale[i]
                            + (shortPhotoScale[i + 1] - shortPhotoScale[i])
                                    * clampFloat(t, 0.0f, 1.0f);
                }
            }
            return shortPhotoScale[PHOTO_KNOT_COUNT - 1];
        }

        private static float clampFloat(float value, float low, float high) {
            return Math.max(low, Math.min(high, value));
        }

        private static float smoothstepFloat(float edge0, float edge1, float value) {
            float t = clampFloat((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
            return t * t * (3.0f - 2.0f * t);
        }

        private static float srgbToLinear(float value) {
            return value <= 0.04045f
                    ? value / 12.92f
                    : (float) Math.pow((value + 0.055f) / 1.055f, 2.4);
        }

        private static float secondLargest(float a, float b, float c) {
            return a + b + c - Math.min(a, Math.min(b, c)) - Math.max(a, Math.max(b, c));
        }

        private static float clampCalibration(float value) {
            return Math.max(0.75f, Math.min(1.33f, value));
        }

        private static float percentileSorted(float[] values, float percentile) {
            if (values.length == 0) return 0.0f;
            int index = Math.max(0, Math.min(values.length - 1,
                    Math.round(percentile * (values.length - 1))));
            return values[index];
        }

        private static float medianPrefix(float[] values, int count, float fallback) {
            if (count <= 0) return fallback;
            Arrays.sort(values, 0, count);
            int mid = count / 2;
            if ((count & 1) != 0) return values[mid];
            return 0.5f * (values[mid - 1] + values[mid]);
        }

        private void bindQuad() {
            vertexBuffer.position(0);
            displayUvBuffer.position(0);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, displayUvBuffer);
        }

        private void setFitScaleUniform(int program, String name, float viewportWidth, float viewportHeight) {
            float scaleX = 1.0f;
            float scaleY = 1.0f;
            if (frameWidth > 0 && frameHeight > 0 && viewportWidth > 0.0f && viewportHeight > 0.0f) {
                float rotatedWidth = producerAxisSwap ? frameHeight : frameWidth;
                float rotatedHeight = producerAxisSwap ? frameWidth : frameHeight;
                float imageAspect = rotatedWidth / rotatedHeight;
                float viewportAspect = viewportWidth / viewportHeight;
                if (viewportAspect > imageAspect) {
                    scaleX = viewportAspect / imageAspect;
                } else if (viewportAspect < imageAspect) {
                    scaleY = imageAspect / viewportAspect;
                }
            }
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(program, name),
                    scaleX,
                    scaleY);
        }

        private static void bindSampler2d(int program, String name, int texture, int unit) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit);
        }

        private void resetShortReliabilityHistory() {
            Arrays.fill(shortLumaReliability, LUMA_RELIABILITY_INITIAL);
            Arrays.fill(shortChromaReliability, CHROMA_RELIABILITY_INITIAL);
            shortReliabilityBuffer.clear();
            for (int i = 0; i < STATS_PIXELS; i++) {
                shortReliabilityBuffer.put((byte) LUMA_RELIABILITY_INITIAL);
                shortReliabilityBuffer.put((byte) CHROMA_RELIABILITY_INITIAL);
            }
            shortReliabilityBuffer.flip();
            latestShortReliabilitySnapshot = snapshotReliabilityBuffer();
            if (shortReliabilityTexture != 0) uploadShortReliabilityTexture();
        }

        private byte[] snapshotReliabilityBuffer() {
            ByteBuffer copy = shortReliabilityBuffer.asReadOnlyBuffer();
            copy.rewind();
            byte[] out = new byte[STATS_PIXELS * 2];
            copy.get(out);
            return out;
        }

        private static byte[] defaultReliabilitySnapshot() {
            byte[] out = new byte[STATS_PIXELS * 2];
            for (int i = 0; i < STATS_PIXELS; i++) {
                out[i * 2] = (byte) LUMA_RELIABILITY_INITIAL;
                out[i * 2 + 1] = (byte) CHROMA_RELIABILITY_INITIAL;
            }
            return out;
        }

        private void uploadShortReliabilityTexture() {
            if (shortReliabilityTexture == 0 || shortReliabilityBuffer.remaining() == 0) return;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shortReliabilityTexture);
            GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, STATS_WIDTH, STATS_HEIGHT,
                    GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, shortReliabilityBuffer);
            shortReliabilityBuffer.rewind();
        }

        private static void allocateReliabilityTexture(int texture) {
            if (texture == 0) return;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8,
                    STATS_WIDTH, STATS_HEIGHT, 0,
                    GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, null);
        }

        private static void allocateRgbTexture(int texture, int width, int height) {
            if (texture == 0 || width <= 0 || height <= 0) return;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_RGBA8,
                    width,
                    height,
                    0,
                    GLES30.GL_RGBA,
                    GLES30.GL_UNSIGNED_BYTE,
                    null);
        }

        private static int createTexture2d() {
            int[] textures = new int[1];
            GLES30.glGenTextures(1, textures, 0);
            int texture = textures[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            return texture;
        }

        private static int createExternalTexture() {
            int[] textures = new int[1];
            GLES30.glGenTextures(1, textures, 0);
            int texture = textures[0];
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            return texture;
        }

        private static FloatBuffer directFloatBuffer(float[] values) {
            ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
            FloatBuffer floats = bytes.asFloatBuffer();
            floats.put(values).flip();
            return floats;
        }

        private static String loadAsset(Context context, String path) {
            try (InputStream input = context.getAssets().open(path);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to load shader asset " + path, e);
            }
        }

        private static int buildProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vertex);
            GLES30.glAttachShader(program, fragment);
            GLES30.glLinkProgram(program);
            int[] status = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(program);
                GLES30.glDeleteProgram(program);
                throw new IllegalStateException("GL program link failed: " + log);
            }
            GLES30.glDeleteShader(vertex);
            GLES30.glDeleteShader(fragment);
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader, source);
            GLES30.glCompileShader(shader);
            int[] status = new int[1];
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES30.glGetShaderInfoLog(shader);
                GLES30.glDeleteShader(shader);
                throw new IllegalStateException("GL shader compile failed: " + log);
            }
            return shader;
        }

        private void updateFps() {
            long now = System.nanoTime();
            long elapsed = now - fpsWindowStartNs;
            if (elapsed >= 1_000_000_000L) {
                inputFps = fpsWindowInputFrames * 1_000_000_000.0 / elapsed;
                hdrPairFps = fpsWindowPairs * 1_000_000_000.0 / elapsed;
                fpsWindowInputFrames = 0;
                fpsWindowPairs = 0;
                fpsWindowStartNs = now;
            }
        }
    }

    private static final class PendingFrame {
        int texture;
        long timestampNs;
        boolean occupied;
    }
}
