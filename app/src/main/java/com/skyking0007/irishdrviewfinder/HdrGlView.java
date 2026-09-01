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
        private static final long STATS_INTERVAL_NS = 200_000_000L;
        private static final float MEANINGFUL_CLIP_CHANNEL = 0.992f;
        private static final float MEANINGFUL_CLIP_LUMA = 0.72f;

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
        private int displayProgram;
        private int externalTexture;
        private int normalTexture;
        private int shortTexture;
        private int longTexture;
        private int stagingShortTexture;
        private int stagingLongTexture;
        private int statsTexture;
        private int shortReliabilityTexture;
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
        private boolean havePreviousStats;
        private final int[] shortLumaStableCounts = new int[STATS_PIXELS];
        private final int[] shortChromaStableCounts = new int[STATS_PIXELS];
        private final float[] previousLongLuma = new float[STATS_PIXELS];
        private final float[] previousShortSceneR = new float[STATS_PIXELS];
        private final float[] previousShortSceneG = new float[STATS_PIXELS];
        private final float[] previousShortSceneB = new float[STATS_PIXELS];
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
            String displayShader = loadAsset(context, "shaders/hdr_display.frag");
            oesProgram = buildProgram(vertexShader, oesShader);
            copyProgram = buildProgram(vertexShader, copyShader);
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
            lastStatsNs = 0L;
            shortCalibration = 1.0f;
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
            lastStatsNs = 0L;
            shortCalibration = 1.0f;
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

        private void drawDisplay() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return;

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
            float ratio = 1.0f;
            if (lastShortMeta != null && lastLongMeta != null) {
                double r = lastLongMeta.exposureProduct() / lastShortMeta.exposureProduct();
                ratio = (float) Math.max(1.0, Math.min(65_536.0, r));
            }
            GLES30.glUniform1f(GLES30.glGetUniformLocation(displayProgram, "exposureRatio"), ratio);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "shortCalibration"),
                    shortCalibration);
            bindSampler2d(displayProgram, "shortReliabilityTex", shortReliabilityTexture, 3);
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
            int overlapCount = 0;
            int longClipped = 0;
            int shortClipped = 0;
            int shortDark = 0;

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
                if (shortLuma < 0.008f) shortDark++;

                boolean validLong = lr8 > 0.08f && lg8 > 0.08f && lb8 > 0.08f
                        && lr8 < 0.90f && lg8 < 0.90f && lb8 < 0.90f;
                boolean validShort = sr8 > 0.015f && sg8 > 0.015f && sb8 > 0.015f
                        && sr8 < 0.90f && sg8 < 0.90f && sb8 < 0.90f;
                float normalizedShortLuma = (float) (shortLuma * ratio);
                if (validLong && validShort && normalizedShortLuma > 0.015f) {
                    calibrationRatios[overlapCount++] = clampCalibration(
                            longLuma / normalizedShortLuma);
                }
            }

            Arrays.sort(longLumas);
            float p25 = percentileSorted(longLumas, 0.25f);
            float p35 = percentileSorted(longLumas, 0.35f);
            float median = percentileSorted(longLumas, 0.50f);
            float p90 = percentileSorted(longLumas, 0.90f);
            float p98 = percentileSorted(longLumas, 0.98f);
            float calibration = medianPrefix(calibrationRatios, overlapCount, 1.0f);

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
                float sr = (float) (srgbToLinear(sr8) * ratio * calibration);
                float sg = (float) (srgbToLinear(sg8) * ratio * calibration);
                float sb = (float) (srgbToLinear(sb8) * ratio * calibration);
                float ll = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                float sl = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;

                boolean validLong = lr8 > 0.08f && lg8 > 0.08f && lb8 > 0.08f
                        && lr8 < 0.90f && lg8 < 0.90f && lb8 < 0.90f;
                boolean validShort = sr8 > 0.015f && sg8 > 0.015f && sb8 > 0.015f
                        && sr8 < 0.90f && sg8 < 0.90f && sb8 < 0.90f;
                if (validLong && validShort && ll > 0.015f && sl > 0.015f
                        && errorCount < errors.length) {
                    errors[errorCount++] = (float) Math.abs(Math.log(sl / ll) / Math.log(2.0));
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

                    shortLumaStableCounts[i] = lumaStable
                            ? Math.min(3, shortLumaStableCounts[i] + 1) : 0;
                    shortChromaStableCounts[i] = chromaStable
                            ? Math.min(3, shortChromaStableCounts[i] + 1) : 0;
                    if (!lumaStable) unstableHighlightCells++;
                } else {
                    shortLumaStableCounts[i] = 3;
                    shortChromaStableCounts[i] = 3;
                }

                int lumaReliability = shortLumaStableCounts[i] >= 2 ? 255 : 0;
                int chromaReliability = shortChromaStableCounts[i] >= 2 ? 255 : 0;
                shortReliabilityBuffer.put((byte) lumaReliability);
                shortReliabilityBuffer.put((byte) chromaReliability);
                previousLongLuma[i] = ll;
                previousShortSceneR[i] = sr;
                previousShortSceneG[i] = sg;
                previousShortSceneB[i] = sb;
            }
            havePreviousStats = true;

            float overlapError = medianPrefix(errors, errorCount, 0.0f);
            float unstableFraction = temporalHighlightCells == 0 ? 0.0f
                    : unstableHighlightCells / (float) temporalHighlightCells;
            shortReliabilityBuffer.flip();
            // Bracket adaptation only asks whether instability is widespread. The
            // renderer consumes the local R/G reliability texture, so one bad lamp
            // cannot disable a stable window or other recoverable highlight.
            boolean shortTemporalReliable = temporalHighlightCells == 0
                    || unstableFraction <= 0.25f;

            return new SceneStats(
                    shortMeta.frameNumber, longMeta.frameNumber,
                    shortMeta.exposureProduct(), longMeta.exposureProduct(), ratio,
                    longMeta.exposureGeneration, p25, p35, median, p90, p98,
                    longClipped / (float) STATS_PIXELS,
                    shortClipped / (float) STATS_PIXELS,
                    shortDark / (float) STATS_PIXELS,
                    calibration, overlapError, overlapCount,
                    shortTemporalReliable, unstableFraction);
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
            Arrays.fill(shortLumaStableCounts, 0);
            Arrays.fill(shortChromaStableCounts, 0);
            shortReliabilityBuffer.clear();
            for (int i = 0; i < STATS_PIXELS; i++) {
                shortReliabilityBuffer.put((byte) 0);
                shortReliabilityBuffer.put((byte) 0);
            }
            shortReliabilityBuffer.flip();
            if (shortReliabilityTexture != 0) uploadShortReliabilityTexture();
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
