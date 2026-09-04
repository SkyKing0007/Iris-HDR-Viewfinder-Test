package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
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

    interface StillFusionCallback {
        void onComplete(byte[] jpegBytes, Throwable error);
    }

    static final class SceneStats {
        final long shortFrameNumber;
        final long longFrameNumber;
        final double shortExposureProduct;
        final double longExposureProduct;
        final float shortP50Linear;
        final float shortP90Linear;
        final float shortP95Linear;
        final float shortP98Linear;
        final float shortP99Linear;
        final float shortNearClipFraction;
        final float longP50Linear;
        final float longP95Linear;
        final float longP98Linear;
        final float longNearClipFraction;
        final float fusedP05Linear;
        final float fusedP10Linear;
        final float fusedP25Linear;
        final float fusedP50Linear;
        final float fusedP75Linear;
        final float fusedP90Linear;
        final float fusedP95Linear;
        final float shadowLocalContrast;
        final float midLocalContrast;

        SceneStats(
                long shortFrameNumber,
                long longFrameNumber,
                double shortExposureProduct,
                double longExposureProduct,
                float shortP50Linear,
                float shortP90Linear,
                float shortP95Linear,
                float shortP98Linear,
                float shortP99Linear,
                float shortNearClipFraction,
                float longP50Linear,
                float longP95Linear,
                float longP98Linear,
                float longNearClipFraction,
                float fusedP05Linear,
                float fusedP10Linear,
                float fusedP25Linear,
                float fusedP50Linear,
                float fusedP75Linear,
                float fusedP90Linear,
                float fusedP95Linear,
                float shadowLocalContrast,
                float midLocalContrast) {
            this.shortFrameNumber = shortFrameNumber;
            this.longFrameNumber = longFrameNumber;
            this.shortExposureProduct = shortExposureProduct;
            this.longExposureProduct = longExposureProduct;
            this.shortP50Linear = shortP50Linear;
            this.shortP90Linear = shortP90Linear;
            this.shortP95Linear = shortP95Linear;
            this.shortP98Linear = shortP98Linear;
            this.shortP99Linear = shortP99Linear;
            this.shortNearClipFraction = shortNearClipFraction;
            this.longP50Linear = longP50Linear;
            this.longP95Linear = longP95Linear;
            this.longP98Linear = longP98Linear;
            this.longNearClipFraction = longNearClipFraction;
            this.fusedP05Linear = fusedP05Linear;
            this.fusedP10Linear = fusedP10Linear;
            this.fusedP25Linear = fusedP25Linear;
            this.fusedP50Linear = fusedP50Linear;
            this.fusedP75Linear = fusedP75Linear;
            this.fusedP90Linear = fusedP90Linear;
            this.fusedP95Linear = fusedP95Linear;
            this.shadowLocalContrast = shadowLocalContrast;
            this.midLocalContrast = midLocalContrast;
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

    void setDisplayBrightnessEv(float ev) {
        renderer.displayBrightnessEv = Math.max(-16.0f, Math.min(1.0f, ev));
        requestRender();
    }

    void setDisplayGamma(float gamma) {
        renderer.displayGamma = Math.max(0.50f, Math.min(2.00f, gamma));
        requestRender();
    }

    void setDisplayEnhancement(float dehaze, float microContrast) {
        renderer.displayDehaze = Math.max(0.0f, Math.min(1.0f, dehaze));
        renderer.displayMicroContrast = Math.max(0.0f, Math.min(1.0f, microContrast));
        requestRender();
    }

    void fuseStillJpegs(
            byte[] shortJpeg,
            byte[] longJpeg,
            double exposureRatio,
            float brightnessEv,
            float gamma,
            float dehaze,
            float microContrast,
            StillFusionCallback callback) {
        if (callback == null) return;
        queueEvent(() -> {
            try {
                byte[] fused = renderer.fuseStillJpegs(
                        shortJpeg, longJpeg, exposureRatio, brightnessEv, gamma,
                        dehaze, microContrast);
                callback.onComplete(fused, null);
            } catch (Throwable t) {
                callback.onComplete(null, t);
            }
        });
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
        private static final long STATS_INTERVAL_NS = 100_000_000L;

        private final Context context;
        private final FloatBuffer vertexBuffer;
        private final FloatBuffer displayUvBuffer;
        private final Map<Long, FrameMeta> metaByTimestamp = new ConcurrentHashMap<>();
        private final AtomicInteger frameSignals = new AtomicInteger();
        private final PendingFrame[] pendingFrames = new PendingFrame[PENDING_SLOTS];

        volatile Mode mode = Mode.HDR;
        volatile float displayBrightnessEv = 0.0f;
        volatile float displayGamma = 1.0f;
        volatile float displayDehaze = 0.28f;
        volatile float displayMicroContrast = 0.20f;
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
        private int framebuffer;
        private final ByteBuffer shortStatsBuffer = ByteBuffer.allocateDirect(STATS_PIXELS * 4)
                .order(ByteOrder.nativeOrder());
        private final ByteBuffer longStatsBuffer = ByteBuffer.allocateDirect(STATS_PIXELS * 4)
                .order(ByteOrder.nativeOrder());
        private long lastStatsNs;
        private SurfaceTexture surfaceTexture;
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
            if (FrameMeta.SHORT.equals(meta.kind)) {
                haveStagingShort = true;
                stagingShortMeta = meta;
                return;
            }
            if (FrameMeta.LONG.equals(meta.kind)) {
                // Only publish a complete temporal pair. A LONG result without a
                // preceding SHORT leaves the previous complete pair on screen.
                if (haveStagingShort && stagingShortMeta != null
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

        private void maybePublishSceneStats() {
            SceneStatsListener listener = sceneStatsListener;
            if (listener == null || !haveShort || !haveLong
                    || lastShortMeta == null || lastLongMeta == null) return;
            long now = System.nanoTime();
            if (lastStatsNs != 0L && now - lastStatsNs < STATS_INTERVAL_NS) return;
            lastStatsNs = now;

            readTextureStats(shortTexture, shortStatsBuffer);
            readTextureStats(longTexture, longStatsBuffer);
            listener.onSceneStats(buildSceneStats(shortStatsBuffer, longStatsBuffer));
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

        private SceneStats buildSceneStats(ByteBuffer shortPixels, ByteBuffer longPixels) {
            float[] shortLuma = new float[STATS_PIXELS];
            float[] longLuma = new float[STATS_PIXELS];
            float[] fusedLuma = new float[STATS_PIXELS];
            int shortNearClip = 0;
            int longNearClip = 0;
            double ratio = Math.max(1.0,
                    lastLongMeta.exposureProduct() / Math.max(1.0, lastShortMeta.exposureProduct()));
            float bracketStops = (float) Math.max(1.0, Math.min(6.0, Math.log(ratio) / Math.log(2.0)));
            float clipStart = Math.max(0.90f, Math.min(0.95f, 0.90f + 0.01f * (bracketStops - 1.0f)));

            for (int i = 0; i < STATS_PIXELS; i++) {
                int o = i * 4;
                float sr = (shortPixels.get(o) & 0xFF) / 255.0f;
                float sg = (shortPixels.get(o + 1) & 0xFF) / 255.0f;
                float sb = (shortPixels.get(o + 2) & 0xFF) / 255.0f;
                float lr = (longPixels.get(o) & 0xFF) / 255.0f;
                float lg = (longPixels.get(o + 1) & 0xFF) / 255.0f;
                float lb = (longPixels.get(o + 2) & 0xFF) / 255.0f;

                float slr = srgbToLinear(sr);
                float slg = srgbToLinear(sg);
                float slb = srgbToLinear(sb);
                float llr = srgbToLinear(lr);
                float llg = srgbToLinear(lg);
                float llb = srgbToLinear(lb);
                shortLuma[i] = 0.2126f * slr + 0.7152f * slg + 0.0722f * slb;
                longLuma[i] = 0.2126f * llr + 0.7152f * llg + 0.0722f * llb;
                if (Math.max(sr, Math.max(sg, sb)) >= 0.985f) shortNearClip++;
                if (Math.max(lr, Math.max(lg, lb)) >= 0.985f) longNearClip++;

                float longPeak = Math.max(lr, Math.max(lg, lb));
                float longScenePeak = Math.max(0.000001f, Math.max(llr, Math.max(llg, llb)));
                float shortScenePeak = (float) ratio * Math.max(slr, Math.max(slg, slb));
                float shortConfidence = smoothstep(0.35f, 0.65f, shortScenePeak / longScenePeak);
                float highlightWeight = smoothstep(clipStart, 0.995f, longPeak) * shortConfidence;
                float mergedR = lerp(llr, (float) ratio * slr, highlightWeight);
                float mergedG = lerp(llg, (float) ratio * slg, highlightWeight);
                float mergedB = lerp(llb, (float) ratio * slb, highlightWeight);
                fusedLuma[i] = 0.2126f * mergedR + 0.7152f * mergedG + 0.0722f * mergedB;
            }

            float[] shortSorted = shortLuma.clone();
            float[] longSorted = longLuma.clone();
            float[] fusedSorted = fusedLuma.clone();
            java.util.Arrays.sort(shortSorted);
            java.util.Arrays.sort(longSorted);
            java.util.Arrays.sort(fusedSorted);
            return new SceneStats(
                    lastShortMeta.frameNumber,
                    lastLongMeta.frameNumber,
                    lastShortMeta.exposureProduct(),
                    lastLongMeta.exposureProduct(),
                    percentileSorted(shortSorted, 0.50f),
                    percentileSorted(shortSorted, 0.90f),
                    percentileSorted(shortSorted, 0.95f),
                    percentileSorted(shortSorted, 0.98f),
                    percentileSorted(shortSorted, 0.99f),
                    shortNearClip / (float) STATS_PIXELS,
                    percentileSorted(longSorted, 0.50f),
                    percentileSorted(longSorted, 0.95f),
                    percentileSorted(longSorted, 0.98f),
                    longNearClip / (float) STATS_PIXELS,
                    percentileSorted(fusedSorted, 0.05f),
                    percentileSorted(fusedSorted, 0.10f),
                    percentileSorted(fusedSorted, 0.25f),
                    percentileSorted(fusedSorted, 0.50f),
                    percentileSorted(fusedSorted, 0.75f),
                    percentileSorted(fusedSorted, 0.90f),
                    percentileSorted(fusedSorted, 0.95f),
                    localContrastMedian(fusedLuma, 0.005f, 0.080f),
                    localContrastMedian(fusedLuma, 0.030f, 0.350f));
        }

        private float localContrastMedian(float[] luma, float low, float high) {
            float[] values = new float[STATS_PIXELS * 2];
            int count = 0;
            for (int y = 0; y < STATS_HEIGHT; y++) {
                for (int x = 0; x < STATS_WIDTH; x++) {
                    int i = y * STATS_WIDTH + x;
                    if (x + 1 < STATS_WIDTH) {
                        count = appendLocalContrast(values, count, luma[i], luma[i + 1], low, high);
                    }
                    if (y + 1 < STATS_HEIGHT) {
                        count = appendLocalContrast(
                                values, count, luma[i], luma[i + STATS_WIDTH], low, high);
                    }
                }
            }
            if (count == 0) return 0.0f;
            java.util.Arrays.sort(values, 0, count);
            return values[Math.max(0, Math.min(count - 1, Math.round(0.50f * (count - 1))))];
        }

        private static int appendLocalContrast(
                float[] values, int count, float a, float b, float low, float high) {
            float mean = 0.5f * (a + b);
            if (mean < low || mean >= high) return count;
            values[count] = Math.abs(a - b) / Math.max(mean, 0.01f);
            return count + 1;
        }

        private static float percentileSorted(float[] sorted, float fraction) {
            int index = Math.max(0, Math.min(sorted.length - 1,
                    Math.round(fraction * (sorted.length - 1))));
            return sorted[index];
        }

        private static float smoothstep(float edge0, float edge1, float value) {
            if (edge1 <= edge0) return value >= edge1 ? 1.0f : 0.0f;
            float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
            return t * t * (3.0f - 2.0f * t);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float srgbToLinear(float value) {
            return value <= 0.04045f
                    ? value / 12.92f
                    : (float) Math.pow((value + 0.055f) / 1.055f, 2.4);
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
                    GLES30.glGetUniformLocation(displayProgram, "displayBrightnessEv"),
                    displayBrightnessEv);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayGamma"),
                    displayGamma);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayDehaze"),
                    displayDehaze);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayMicroContrast"),
                    displayMicroContrast);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "stillRegistrationConfidence"),
                    0.0f);
            GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(displayProgram, "stillShortLinearGain"),
                    1.0f, 1.0f, 1.0f);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "stillShortScalarGain"),
                    1.0f);
            // Local residual flow is saved-still-only. Live preview remains the
            // proven V2.13 path and receives a neutral/disabled local field.
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "haveLocalFlow"), 0);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "stillImageSize"),
                    Math.max(1, frameWidth), Math.max(1, frameHeight));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "localFlowMaxPixels"), 0.0f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        private byte[] fuseStillJpegs(
                byte[] shortJpeg,
                byte[] longJpeg,
                double exposureRatio,
                float brightnessEv,
                float gamma,
                float dehaze,
                float microContrast) throws Exception {
            long startedNs = System.nanoTime();
            Bitmap shortBitmap = JpegFusion.decodeUpright(shortJpeg);
            Bitmap longBitmap = JpegFusion.decodeUpright(longJpeg);
            if (shortBitmap == null || longBitmap == null) {
                JpegFusion.recycleBitmap(shortBitmap);
                JpegFusion.recycleBitmap(longBitmap);
                throw new IllegalStateException("Unable to decode capture JPEGs for GPU fusion");
            }
            if (shortBitmap.getWidth() != longBitmap.getWidth()
                    || shortBitmap.getHeight() != longBitmap.getHeight()) {
                JpegFusion.recycleBitmap(shortBitmap);
                JpegFusion.recycleBitmap(longBitmap);
                throw new IllegalStateException("Short/long JPEG dimensions do not match for GPU fusion");
            }

            JpegFusion.Registration registration = JpegFusion.estimateRegistration(shortBitmap, longBitmap);
            Bitmap alignedShort = JpegFusion.alignShortToLong(shortBitmap, registration);
            JpegFusion.recycleBitmap(shortBitmap);
            shortBitmap = alignedShort;
            // V2.14 visual-evidence correction: preserve the proven global
            // translation as the coarse anchor, then estimate a bounded,
            // bidirectional/cycle-consistent local residual field. The field is
            // tiny and is used only to sample the real SHORT source more accurately.
            JpegFusion.LocalRegistrationField localRegistration =
                    JpegFusion.estimateLocalRegistration(shortBitmap, longBitmap);
            JpegFusion.AppearanceGain appearanceGain =
                    JpegFusion.estimateAppearanceGain(shortBitmap, longBitmap, exposureRatio);
            float scalarGain = median3(appearanceGain.r, appearanceGain.g, appearanceGain.b);
            scalarGain = Math.max(1.0f, Math.min(65_536.0f, scalarGain));
            RuntimeLogger.event(
                    "GPU_STILL_REGISTRATION",
                    String.format(java.util.Locale.US,
                            "sampleDx=%+.3f sampleDy=%+.3f score=%.4f margin=%.4f cycle=%.3f confidence=%.3f gain=%.3f/%.3f/%.3f scalar=%.3f",
                            registration.sampleDx, registration.sampleDy, registration.score,
                            registration.margin, registration.cycleError, registration.confidence,
                            appearanceGain.r, appearanceGain.g, appearanceGain.b, scalarGain));
            RuntimeLogger.event(
                    "GPU_STILL_LOCAL_REGISTRATION",
                    String.format(java.util.Locale.US,
                            "grid=%dx%d meanConfidence=%.3f supported=%.3f observedResidual=%.2fpx bound=%.2fpx",
                            localRegistration.gridWidth, localRegistration.gridHeight,
                            localRegistration.meanConfidence, localRegistration.supportedFraction,
                            localRegistration.observedResidualPixels,
                            localRegistration.maxResidualPixels));

            int width = shortBitmap.getWidth();
            int height = shortBitmap.getHeight();
            int[] maxTexture = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0);
            if (width > maxTexture[0] || height > maxTexture[0]) {
                JpegFusion.recycleBitmap(shortBitmap);
                JpegFusion.recycleBitmap(longBitmap);
                throw new IllegalStateException(
                        "Still dimensions exceed GL_MAX_TEXTURE_SIZE " + width + "x" + height
                                + " max=" + maxTexture[0]);
            }

            RuntimeLogger.event(
                    "GPU_STILL_FUSION",
                    String.format(java.util.Locale.US,
                            "V2.12 GPU-only multipass start %dx%d ratio=%.3f scalar=%.3f brightness=%+.2fEV gamma=%.2f dehaze=%.2f micro=%.2f",
                            width, height, exposureRatio, scalarGain, brightnessEv, gamma,
                            dehaze, microContrast));

            int shortTexture = 0;
            int longTexture = 0;
            int localFlowTexture = 0;
            int evidenceTexture = 0;
            int supportTexture = 0;
            int presentationTexture = 0;
            int outputTexture = 0;
            Bitmap output = null;
            try {
                shortTexture = createTexture2d();
                longTexture = createTexture2d();
                localFlowTexture = createTexture2d();
                evidenceTexture = createTexture2d();
                supportTexture = createTexture2d();
                presentationTexture = createTexture2d();
                outputTexture = createTexture2d();

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shortTexture);
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, shortBitmap, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, longTexture);
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, longBitmap, 0);
                uploadRgba8Texture(
                        localFlowTexture,
                        localRegistration.gridWidth,
                        localRegistration.gridHeight,
                        localRegistration.rgba);
                int analysisWidth = Math.max(1, (width + 7) / 8);
                int analysisHeight = Math.max(1, (height + 7) / 8);
                allocateRgbTexture(evidenceTexture, analysisWidth, analysisHeight);
                allocateRgbTexture(supportTexture, analysisWidth, analysisHeight);
                allocateRgbTexture(presentationTexture, width, height);
                allocateRgbTexture(outputTexture, width, height);
                JpegFusion.recycleBitmap(shortBitmap);
                shortBitmap = null;
                JpegFusion.recycleBitmap(longBitmap);
                longBitmap = null;

                // V2.14 keeps the proven four-pass saved-still topology. Mode 3
                // measures source loss, mode 4 forms only a broad coherent region
                // prior, mode 5 chooses full-resolution source provenance, and mode
                // 6 is pointwise so it cannot draw new post-fusion borders.
                renderStillPass(
                        evidenceTexture, analysisWidth, analysisHeight,
                        3, longTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);
                renderStillPass(
                        supportTexture, analysisWidth, analysisHeight,
                        4, evidenceTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);
                renderStillPass(
                        presentationTexture, width, height,
                        5, supportTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);
                renderStillPass(
                        outputTexture, width, height,
                        6, presentationTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);

                ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4)
                        .order(ByteOrder.nativeOrder());
                GLES30.glReadPixels(
                        0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba);
                rgba.rewind();
                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) {
                    int r = rgba.get() & 0xFF;
                    int g = rgba.get() & 0xFF;
                    int b = rgba.get() & 0xFF;
                    rgba.get();
                    pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                // GL texture upload and glReadPixels have opposite vertical origins,
                // so the two inversions cancel and the sequential rows are upright.
                output.setPixels(pixels, 0, width, 0, 0, width, height);
                byte[] encoded = JpegFusion.encodeJpeg(output);
                long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
                RuntimeLogger.event(
                        "GPU_STILL_FUSION",
                        "V2.9 GPU-only multipass complete ms=" + elapsedMs
                                + " outputBytes=" + encoded.length);
                return encoded;
            } finally {
                JpegFusion.recycleBitmap(shortBitmap);
                JpegFusion.recycleBitmap(longBitmap);
                JpegFusion.recycleBitmap(output);
                int[] textures = {
                        shortTexture, longTexture, localFlowTexture, evidenceTexture,
                        supportTexture, presentationTexture, outputTexture};
                for (int texture : textures) {
                    if (texture != 0) {
                        int[] one = {texture};
                        GLES30.glDeleteTextures(1, one, 0);
                    }
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                if (surfaceWidth > 0 && surfaceHeight > 0) {
                    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
                }
            }
        }

        private void renderStillPass(
                int targetTexture,
                int targetWidth,
                int targetHeight,
                int stillMode,
                int normalSourceTexture,
                int shortSourceTexture,
                int longSourceTexture,
                double exposureRatio,
                float brightnessEv,
                float gamma,
                float dehaze,
                float microContrast,
                float registrationConfidence,
                float scalarGain,
                int localFlowTexture,
                int stillWidth,
                int stillHeight,
                float localFlowMaxPixels) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, targetTexture, 0);
            int fbStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (fbStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "V2.9 GPU still framebuffer incomplete mode=" + stillMode + ": 0x"
                                + Integer.toHexString(fbStatus));
            }
            GLES30.glViewport(0, 0, targetWidth, targetHeight);
            GLES30.glUseProgram(displayProgram);
            bindQuad();
            bindSampler2d(displayProgram, "normalTex", normalSourceTexture, 0);
            bindSampler2d(displayProgram, "shortTex", shortSourceTexture, 1);
            bindSampler2d(displayProgram, "longTex", longSourceTexture, 2);
            bindSampler2d(displayProgram, "localFlowTex", localFlowTexture, 3);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "mode"), stillMode);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "rotationQuarterTurns"), 0);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "fullFitScale"), 1.0f, 1.0f);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "splitFitScale"), 1.0f, 1.0f);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveNormal"), 1);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveShort"), 1);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveLong"), 1);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "exposureRatio"),
                    (float) Math.max(1.0, Math.min(65_536.0, exposureRatio)));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayBrightnessEv"),
                    Math.max(-16.0f, Math.min(1.0f, brightnessEv)));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayGamma"),
                    Math.max(0.50f, Math.min(2.00f, gamma)));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayDehaze"),
                    Math.max(0.0f, Math.min(1.0f, dehaze)));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "displayMicroContrast"),
                    Math.max(0.0f, Math.min(1.0f, microContrast)));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "stillRegistrationConfidence"),
                    registrationConfidence);
            // V2.9 production composition never applies independent RGB appearance gains.
            GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(displayProgram, "stillShortLinearGain"),
                    1.0f, 1.0f, 1.0f);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "stillShortScalarGain"),
                    scalarGain);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "haveLocalFlow"),
                    localFlowTexture != 0 ? 1 : 0);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "stillImageSize"),
                    Math.max(1, stillWidth), Math.max(1, stillHeight));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "localFlowMaxPixels"),
                    Math.max(0.0f, localFlowMaxPixels));
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        private static float median3(float a, float b, float c) {
            return a + b + c - Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c));
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

        private static void uploadRgba8Texture(
                int texture, int width, int height, byte[] rgba) {
            if (texture == 0 || width <= 0 || height <= 0 || rgba == null
                    || rgba.length != width * height * 4) {
                throw new IllegalArgumentException("Invalid local registration texture payload");
            }
            ByteBuffer data = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
            data.put(rgba).flip();
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
                    data);
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
