package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
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
        final int shortIso;
        final int longIso;
        final float shortP50Linear;
        final float shortP90Linear;
        final float shortP95Linear;
        final float shortP98Linear;
        final float shortP99Linear;
        final float shortNearClipFraction;
        final float longP50Linear;
        final float longBodyP50Linear;
        final float longBodyP75Linear;
        final float longBodyFraction;
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
                int shortIso,
                int longIso,
                float shortP50Linear,
                float shortP90Linear,
                float shortP95Linear,
                float shortP98Linear,
                float shortP99Linear,
                float shortNearClipFraction,
                float longP50Linear,
                float longBodyP50Linear,
                float longBodyP75Linear,
                float longBodyFraction,
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
            this.shortIso = shortIso;
            this.longIso = longIso;
            this.shortP50Linear = shortP50Linear;
            this.shortP90Linear = shortP90Linear;
            this.shortP95Linear = shortP95Linear;
            this.shortP98Linear = shortP98Linear;
            this.shortP99Linear = shortP99Linear;
            this.shortNearClipFraction = shortNearClipFraction;
            this.longP50Linear = longP50Linear;
            this.longBodyP50Linear = longBodyP50Linear;
            this.longBodyP75Linear = longBodyP75Linear;
            this.longBodyFraction = longBodyFraction;
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

    void fuseStillRaws(
            RawFusion.RawFrame shortRaw,
            RawFusion.RawFrame longRaw,
            double exposureRatio,
            int captureOrientationDegrees,
            float brightnessEv,
            float gamma,
            float dehaze,
            float microContrast,
            StillFusionCallback callback) {
        if (callback == null) return;
        queueEvent(() -> {
            try {
                byte[] fused = renderer.fuseStillRaws(
                        shortRaw, longRaw, exposureRatio, captureOrientationDegrees,
                        brightnessEv, gamma, dehaze, microContrast);
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
        private int rawPreprocessProgram;
        private int rawReconstructProgram;
        private int rawChromaDealiasProgram;
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
            String rawPreprocessShader = loadAsset(context, "shaders/raw_preprocess.frag");
            String rawReconstructShader = loadAsset(context, "shaders/raw_reconstruct.frag");
            String rawChromaDealiasShader = loadAsset(context, "shaders/raw_chroma_dealias.frag");
            oesProgram = buildProgram(vertexShader, oesShader);
            copyProgram = buildProgram(vertexShader, copyShader);
            displayProgram = buildProgram(vertexShader, displayShader);
            rawPreprocessProgram = buildProgram(vertexShader, rawPreprocessShader);
            rawReconstructProgram = buildProgram(vertexShader, rawReconstructShader);
            rawChromaDealiasProgram = buildProgram(vertexShader, rawChromaDealiasShader);

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
            float[] longBodyLuma = new float[STATS_PIXELS];
            float[] fusedLuma = new float[STATS_PIXELS];
            int shortNearClip = 0;
            int longNearClip = 0;
            int longBodyCount = 0;
            double ratio = Math.max(1.0,
                    lastLongMeta.exposureProduct() / Math.max(1.0, lastShortMeta.exposureProduct()));
            float bracketStops = (float) Math.max(0.0, Math.min(6.0, Math.log(ratio) / Math.log(2.0)));
            float clipStart = Math.max(0.89f, Math.min(0.95f, 0.89f + 0.01f * bracketStops));

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
                // V2.26 body-SNR authority deliberately excludes the bright/highlight
                // population before computing LONG feedback. A window, bulb, sky or
                // specular island may occupy far more than the top 5% of a scene, so
                // global P95 is not a safe body statistic. The 0.70 encoded peak cut
                // keeps ordinary shadows/midtones while leaving highlight protection
                // exclusively to SHORT. If too little body evidence exists we fall
                // back to conservative global lower percentiles below.
                if (longPeak < 0.70f && longLuma[i] >= 0.00025f) {
                    longBodyLuma[longBodyCount++] = longLuma[i];
                }
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
            float globalLongP50 = percentileSorted(longSorted, 0.50f);
            float globalLongP75 = percentileSorted(longSorted, 0.75f);
            float longBodyP50;
            float longBodyP75;
            // Require at least 20% of the 32x24 grid before the highlight-excluded
            // population becomes exposure authority. Extremely bright/high-key
            // scenes therefore fail closed to global lower percentiles.
            if (longBodyCount >= STATS_PIXELS / 5) {
                java.util.Arrays.sort(longBodyLuma, 0, longBodyCount);
                longBodyP50 = percentileSorted(longBodyLuma, longBodyCount, 0.50f);
                longBodyP75 = percentileSorted(longBodyLuma, longBodyCount, 0.75f);
            } else {
                longBodyP50 = globalLongP50;
                longBodyP75 = globalLongP75;
            }
            float longBodyFraction = longBodyCount / (float) STATS_PIXELS;
            return new SceneStats(
                    lastShortMeta.frameNumber,
                    lastLongMeta.frameNumber,
                    lastShortMeta.exposureProduct(),
                    lastLongMeta.exposureProduct(),
                    lastShortMeta.iso,
                    lastLongMeta.iso,
                    percentileSorted(shortSorted, 0.50f),
                    percentileSorted(shortSorted, 0.90f),
                    percentileSorted(shortSorted, 0.95f),
                    percentileSorted(shortSorted, 0.98f),
                    percentileSorted(shortSorted, 0.99f),
                    shortNearClip / (float) STATS_PIXELS,
                    globalLongP50,
                    longBodyP50,
                    longBodyP75,
                    longBodyFraction,
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
            return percentileSorted(sorted, sorted.length, fraction);
        }

        private static float percentileSorted(float[] sorted, int count, float fraction) {
            if (count <= 0) return 0.0f;
            int index = Math.max(0, Math.min(count - 1,
                    Math.round(fraction * (count - 1))));
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
            // V2.27 live parity uses the physical SHORT->LONG exposure ratio as one
            // achromatic radiometric scale. Saved stills continue to use the robust
            // overlap-derived scalar computed after registration.
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "stillShortScalarGain"),
                    ratio);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "stillGlobalShortOffsetPixels"),
                    0.0f, 0.0f);
            // Local residual flow remains saved-still-only. Live HDR uses a
            // conservative direct-coordinate information-loss selector and fails
            // closed to LONG whenever temporal mismatch makes SHORT uncertain.
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "haveLocalFlow"), 0);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "stillImageSize"),
                    Math.max(1, frameWidth), Math.max(1, frameHeight));
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "localFlowMaxPixels"), 0.0f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        private byte[] fuseStillRaws(
                RawFusion.RawFrame shortRaw,
                RawFusion.RawFrame longRaw,
                double exposureRatio,
                int captureOrientationDegrees,
                float brightnessEv,
                float gamma,
                float dehaze,
                float microContrast) throws Exception {
            long startedNs = System.nanoTime();
            if (shortRaw == null || longRaw == null) {
                throw new IllegalStateException("V2.30 RAW fusion requires both sensor frames");
            }
            if (shortRaw.width != longRaw.width || shortRaw.height != longRaw.height) {
                throw new IllegalStateException(
                        "SHORT/LONG RAW dimensions differ: "
                                + shortRaw.width + "x" + shortRaw.height + " vs "
                                + longRaw.width + "x" + longRaw.height);
            }
            if (shortRaw.cfaArrangement != longRaw.cfaArrangement) {
                throw new IllegalStateException("SHORT/LONG RAW CFA arrangement changed within pair");
            }
            if (!(exposureRatio >= 1.0) || !Double.isFinite(exposureRatio)) {
                throw new IllegalStateException("Invalid physical RAW LONG/SHORT ratio " + exposureRatio);
            }

            int width = longRaw.width;
            int height = longRaw.height;
            int[] maxTexture = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0);
            if (width > maxTexture[0] || height > maxTexture[0]) {
                throw new IllegalStateException(
                        "RAW dimensions exceed GL_MAX_TEXTURE_SIZE " + width + "x" + height
                                + " max=" + maxTexture[0]);
            }

            RuntimeLogger.event(
                    "GPU_STILL_RAW_FUSION",
                    String.format(java.util.Locale.US,
                            "V2.30 RAW_SENSOR fusion start %dx%d ratio=%.3f shortTs=%d longTs=%d brightness=%+.2fEV gamma=%.2f dehaze=%.2f micro=%.2f",
                            width, height, exposureRatio,
                            shortRaw.sensorTimestampNs, longRaw.sensorTimestampNs,
                            brightnessEv, gamma, dehaze, microContrast));

            int rawInputTexture = 0;
            int shadingTexture = 0;
            int shortTexture = 0;
            int longTexture = 0;
            int localFlowTexture = 0;
            int evidenceTexture = 0;
            int supportTexture = 0;
            int presentationTexture = 0;
            int outputTexture = 0;
            Bitmap shortProxy = null;
            Bitmap longProxy = null;
            Bitmap alignedShortProxy = null;
            Bitmap output = null;
            Bitmap orientedOutput = null;
            try {
                // One RAW input and one shading texture are reused sequentially for
                // SHORT then LONG. The two full-resolution RGB source textures already
                // present in V2.29 double as the packed normalized Bayer carriers during
                // reconstruction, avoiding any new persistent full-resolution allocation.
                rawInputTexture = createTexture2d();
                shadingTexture = createTexture2d();
                shortTexture = createTexture2d();
                longTexture = createTexture2d();
                localFlowTexture = createTexture2d();
                evidenceTexture = createTexture2d();
                supportTexture = createTexture2d();
                presentationTexture = createTexture2d();
                outputTexture = createTexture2d();

                allocateRgbTexture(shortTexture, width, height);
                allocateRgbTexture(longTexture, width, height);
                // Reuse the later mode-5 presentation texture as a temporary full-res
                // reconstruction carrier. This keeps the V2.29 full-resolution texture
                // budget unchanged while adding a chroma-only RAW de-alias pass.
                allocateRgbTexture(presentationTexture, width, height);
                setTextureFilter(presentationTexture, GLES30.GL_NEAREST);

                // One common color owner prevents a source boundary from acquiring a
                // different WB/matrix. Per-frame black/white/shading remain physical RAW
                // owners, while LONG's matched WB + sensor->linear-sRGB transform owns
                // both reconstructed observations. Each source texture first carries a
                // 16-bit fixed-point normalized Bayer signal, then is overwritten by its
                // final RGB after the separate reconstruction + chroma-dealias passes.
                uploadRaw16Texture(rawInputTexture, shortRaw);
                uploadShadingTexture(shadingTexture, shortRaw);
                renderRawPreprocess(
                        shortTexture, rawInputTexture, shadingTexture, shortRaw);
                renderRawReconstruction(
                        presentationTexture, shortTexture, shortRaw, longRaw);
                renderRawChromaDealias(shortTexture, presentationTexture, width, height);

                uploadRaw16Texture(rawInputTexture, longRaw);
                uploadShadingTexture(shadingTexture, longRaw);
                renderRawPreprocess(
                        longTexture, rawInputTexture, shadingTexture, longRaw);
                renderRawReconstruction(
                        presentationTexture, longTexture, longRaw, longRaw);
                renderRawChromaDealias(longTexture, presentationTexture, width, height);
                deleteTexture(rawInputTexture);
                rawInputTexture = 0;
                deleteTexture(shadingTexture);
                shadingTexture = 0;

                // Registration evidence is read from the RAW-derived reconstructions.
                // It never decodes or samples the saved HAL JPEGs.
                shortProxy = readTextureBitmap(shortTexture, width, height);
                longProxy = readTextureBitmap(longTexture, width, height);
                JpegFusion.Registration registration =
                        JpegFusion.estimateRegistration(shortProxy, longProxy);
                alignedShortProxy = JpegFusion.alignLongToShort(shortProxy, registration);
                JpegFusion.LocalRegistrationField localRegistration =
                        JpegFusion.estimateLocalRegistration(alignedShortProxy, longProxy);
                // V2.31 restores the successful V2.28/V2.29 radiometric owner using
                // RAW-derived reconstructions only. Physical exposure*ISO remains the
                // fallback, while robust registered overlap measures the actual common
                // scene-light scale seen by the fusion shader. No HAL JPEG participates.
                JpegFusion.AppearanceGain appearanceGain =
                        JpegFusion.estimateAppearanceGain(
                                alignedShortProxy, longProxy, exposureRatio);
                float scalarGain = median3(
                        appearanceGain.r, appearanceGain.g, appearanceGain.b);
                scalarGain = Math.max(1.0f, Math.min(65_536.0f, scalarGain));
                RuntimeLogger.event(
                        "GPU_STILL_RAW_REGISTRATION",
                        String.format(java.util.Locale.US,
                                "sampleDx=%+.3f sampleDy=%+.3f score=%.4f margin=%.4f cycleFull=%.3f cycleAnalysis=%.3f coarseCycleAnalysis=%.3f refine=%.3f confidence=%.3f gain=%.3f/%.3f/%.3f scalar=%.3f physicalRatio=%.3f",
                                registration.sampleDx, registration.sampleDy, registration.score,
                                registration.margin, registration.cycleError,
                                registration.analysisCycleError,
                                registration.coarseCycleErrorAnalysis,
                                registration.refinementConfidence,
                                registration.confidence, appearanceGain.r, appearanceGain.g,
                                appearanceGain.b, scalarGain, exposureRatio));
                RuntimeLogger.event(
                        "GPU_STILL_RAW_LOCAL_REGISTRATION",
                        String.format(java.util.Locale.US,
                                "grid=%dx%d meanConfidence=%.3f supported=%.3f observedResidual=%.2fpx bound=%.2fpx",
                                localRegistration.gridWidth, localRegistration.gridHeight,
                                localRegistration.meanConfidence,
                                localRegistration.supportedFraction,
                                localRegistration.observedResidualPixels,
                                localRegistration.maxResidualPixels));
                JpegFusion.recycleBitmap(shortProxy);
                shortProxy = null;
                JpegFusion.recycleBitmap(longProxy);
                longProxy = null;
                JpegFusion.recycleBitmap(alignedShortProxy);
                alignedShortProxy = null;

                // SHORT is the only aligned auxiliary. The raw-derived SHORT texture is
                // left in native sensor geometry; global + bounded local displacement is
                // applied only while sampling it. LONG remains exact output geometry.
                setTextureFilter(shortTexture, GLES30.GL_LINEAR);
                setTextureFilter(longTexture, GLES30.GL_NEAREST);
                uploadRgba8Texture(
                        localFlowTexture,
                        localRegistration.gridWidth,
                        localRegistration.gridHeight,
                        localRegistration.rgba);

                int analysisWidth = Math.max(1, (width + 15) / 16);
                int analysisHeight = Math.max(1, (height + 15) / 16);
                allocateRgbTexture(evidenceTexture, analysisWidth, analysisHeight);
                allocateRgbTexture(supportTexture, analysisWidth, analysisHeight);
                setTextureFilter(evidenceTexture, GLES30.GL_NEAREST);
                setTextureFilter(supportTexture, GLES30.GL_NEAREST);
                // presentationTexture was allocated once above and is now free to be
                // overwritten by the inherited V2.29 mode-5 fused raster.
                setTextureFilter(presentationTexture, GLES30.GL_NEAREST);
                allocateRgbTexture(outputTexture, width, height);

                renderStillPass(
                        evidenceTexture, analysisWidth, analysisHeight,
                        3, longTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        registration.sampleDx, registration.sampleDy,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);
                int[] initialCounts = countAtlasMasks(evidenceTexture, analysisWidth, analysisHeight);
                int previousOwned = initialCounts[0];
                int domainCells = initialCounts[1];
                int readTopologyTexture = evidenceTexture;
                int writeTopologyTexture = supportTexture;
                int propagationPasses = 0;
                boolean propagationConverged = false;
                int maxPropagationPasses = Math.min(1024, Math.max(64, analysisWidth * analysisHeight));
                final int convergenceBatch = 8;
                while (propagationPasses < maxPropagationPasses) {
                    int batchEnd = Math.min(maxPropagationPasses, propagationPasses + convergenceBatch);
                    while (propagationPasses < batchEnd) {
                        renderStillPass(
                                writeTopologyTexture, analysisWidth, analysisHeight,
                                4, readTopologyTexture, shortTexture, longTexture,
                                exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                                registration.confidence, scalarGain,
                                registration.sampleDx, registration.sampleDy,
                                localFlowTexture, width, height,
                                localRegistration.maxResidualPixels);
                        int swap = readTopologyTexture;
                        readTopologyTexture = writeTopologyTexture;
                        writeTopologyTexture = swap;
                        propagationPasses++;
                    }
                    int[] counts = countAtlasMasks(readTopologyTexture, analysisWidth, analysisHeight);
                    if (counts[0] == previousOwned) {
                        propagationConverged = true;
                        break;
                    }
                    previousOwned = counts[0];
                }
                if (!propagationConverged) {
                    RuntimeLogger.event(
                            "GPU_STILL_TOPOLOGY_LIMIT",
                            "passes=" + propagationPasses
                                    + " owned=" + previousOwned
                                    + " domain=" + domainCells
                                    + " atlas=" + analysisWidth + "x" + analysisHeight);
                }
                RuntimeLogger.event(
                        "GPU_STILL_TOPOLOGY",
                        "V2.30 RAW seed=" + initialCounts[0]
                                + " owned=" + previousOwned
                                + " domain=" + domainCells
                                + " passes=" + propagationPasses
                                + " converged=" + propagationConverged
                                + " atlas=" + analysisWidth + "x" + analysisHeight);

                setTextureFilter(readTopologyTexture, GLES30.GL_LINEAR);
                renderStillPass(
                        presentationTexture, width, height,
                        5, readTopologyTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        registration.sampleDx, registration.sampleDy,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);
                renderStillPass(
                        outputTexture, width, height,
                        6, presentationTexture, shortTexture, longTexture,
                        exposureRatio, brightnessEv, gamma, dehaze, microContrast,
                        registration.confidence, scalarGain,
                        registration.sampleDx, registration.sampleDy,
                        localFlowTexture, width, height,
                        localRegistration.maxResidualPixels);

                output = readTextureBitmap(outputTexture, width, height);
                orientedOutput = rotateBitmap(output, captureOrientationDegrees);
                byte[] encoded = JpegFusion.encodeJpeg(orientedOutput);
                long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
                RuntimeLogger.event(
                        "GPU_STILL_RAW_FUSION",
                        "V2.30 RAW_SENSOR fusion complete ms=" + elapsedMs
                                + " outputBytes=" + encoded.length
                                + " orientation=" + captureOrientationDegrees);
                return encoded;
            } finally {
                JpegFusion.recycleBitmap(shortProxy);
                JpegFusion.recycleBitmap(longProxy);
                JpegFusion.recycleBitmap(alignedShortProxy);
                if (orientedOutput != output) JpegFusion.recycleBitmap(orientedOutput);
                JpegFusion.recycleBitmap(output);
                int[] textures = {
                        rawInputTexture, shadingTexture, shortTexture, longTexture,
                        localFlowTexture, evidenceTexture, supportTexture,
                        presentationTexture, outputTexture};
                for (int texture : textures) deleteTexture(texture);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                if (surfaceWidth > 0 && surfaceHeight > 0) {
                    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
                }
            }
        }

        private void uploadRaw16Texture(int texture, RawFusion.RawFrame frame) {
            if (texture == 0 || frame == null || frame.width <= 0 || frame.height <= 0
                    || frame.pixels == null || frame.pixels.length != frame.width * frame.height) {
                throw new IllegalArgumentException("Invalid V2.30 RAW texture payload");
            }
            ByteBuffer pixels = frame.directUnsigned16Buffer();
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_R16UI,
                    frame.width,
                    frame.height,
                    0,
                    GLES30.GL_RED_INTEGER,
                    GLES30.GL_UNSIGNED_SHORT,
                    pixels);
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(
                        "V2.30 RAW GL_R16UI upload failed: 0x" + Integer.toHexString(error));
            }
        }

        private void uploadShadingTexture(int texture, RawFusion.RawFrame frame) {
            if (texture == 0 || frame == null || frame.shadingMapWidth <= 0
                    || frame.shadingMapHeight <= 0 || frame.shadingMapRgba == null
                    || frame.shadingMapRgba.length
                            != frame.shadingMapWidth * frame.shadingMapHeight * 4) {
                throw new IllegalArgumentException("Invalid V2.30 RAW lens shading map");
            }
            ByteBuffer bytes = ByteBuffer.allocateDirect(frame.shadingMapRgba.length * 4)
                    .order(ByteOrder.nativeOrder());
            bytes.asFloatBuffer().put(frame.shadingMapRgba);
            bytes.position(0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_RGBA32F,
                    frame.shadingMapWidth,
                    frame.shadingMapHeight,
                    0,
                    GLES30.GL_RGBA,
                    GLES30.GL_FLOAT,
                    bytes);
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(
                        "V2.30 RAW lens shading upload failed: 0x"
                                + Integer.toHexString(error));
            }
        }

        private void renderRawPreprocess(
                int targetPackedTexture,
                int rawTexture,
                int shadingTexture,
                RawFusion.RawFrame sourceFrame) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, targetPackedTexture, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "V2.31 RAW preprocess framebuffer incomplete: 0x"
                                + Integer.toHexString(status));
            }
            GLES30.glViewport(0, 0, sourceFrame.width, sourceFrame.height);
            GLES30.glUseProgram(rawPreprocessProgram);
            bindQuad();
            bindSampler2d(rawPreprocessProgram, "rawTex", rawTexture, 0);
            bindSampler2d(rawPreprocessProgram, "shadingTex", shadingTexture, 1);

            // GL_R16UI/usampler2D returns integer sensor codes. Black and white remain
            // in those same units; 1/65535 scaling here is permanently forbidden.
            float[] black = sourceFrame.blackPattern;
            GLES30.glUniform4f(
                    GLES30.glGetUniformLocation(rawPreprocessProgram, "blackPatternCode"),
                    black[0], black[1], black[2], black[3]);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(rawPreprocessProgram, "whiteLevelCode"),
                    sourceFrame.whiteLevel);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(rawPreprocessProgram, "cfaArrangement"),
                    sourceFrame.cfaArrangement);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(
                        "V2.31 RAW preprocess GL failure: 0x"
                                + Integer.toHexString(error));
            }
        }

        private void renderRawReconstruction(
                int targetTexture,
                int packedRawTexture,
                RawFusion.RawFrame sourceFrame,
                RawFusion.RawFrame colorOwner) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, targetTexture, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "V2.31 RAW reconstruction framebuffer incomplete: 0x"
                                + Integer.toHexString(status));
            }
            GLES30.glViewport(0, 0, sourceFrame.width, sourceFrame.height);
            GLES30.glUseProgram(rawReconstructProgram);
            bindQuad();
            bindSampler2d(rawReconstructProgram, "packedRawTex", packedRawTexture, 0);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(rawReconstructProgram, "cfaArrangement"),
                    sourceFrame.cfaArrangement);
            float[] wb = colorOwner.wbGains;
            GLES30.glUniform4f(
                    GLES30.glGetUniformLocation(rawReconstructProgram, "wbGains"),
                    wb[0], wb[1], wb[2], wb[3]);
            float[] m = colorOwner.colorTransformRows;
            GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(rawReconstructProgram, "colorRow0"),
                    m[0], m[1], m[2]);
            GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(rawReconstructProgram, "colorRow1"),
                    m[3], m[4], m[5]);
            GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(rawReconstructProgram, "colorRow2"),
                    m[6], m[7], m[8]);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(
                        "V2.31 RAW reconstruction GL failure: 0x"
                                + Integer.toHexString(error));
            }
        }

        private void renderRawChromaDealias(
                int targetTexture, int sourceTexture, int width, int height) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, targetTexture, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "V2.31 RAW chroma de-alias framebuffer incomplete: 0x"
                                + Integer.toHexString(status));
            }
            GLES30.glViewport(0, 0, width, height);
            GLES30.glUseProgram(rawChromaDealiasProgram);
            bindQuad();
            bindSampler2d(rawChromaDealiasProgram, "sourceTex", sourceTexture, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(
                        "V2.31 RAW chroma de-alias GL failure: 0x"
                                + Integer.toHexString(error));
            }
        }

        private Bitmap readTextureBitmap(int texture, int width, int height) {
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder());
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, texture, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "V2.30 readback framebuffer incomplete: 0x"
                                + Integer.toHexString(status));
            }
            GLES30.glViewport(0, 0, width, height);
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
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        }

        private static Bitmap rotateBitmap(Bitmap source, int degrees) {
            if (source == null) return null;
            int normalized = ((degrees % 360) + 360) % 360;
            if (normalized == 0) return source;
            if (normalized != 90 && normalized != 180 && normalized != 270) {
                throw new IllegalArgumentException("RAW output orientation must be multiple of 90");
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(normalized);
            return Bitmap.createBitmap(
                    source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        }

        private static void deleteTexture(int texture) {
            if (texture == 0) return;
            int[] one = {texture};
            GLES30.glDeleteTextures(1, one, 0);
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
                float globalShortDx,
                float globalShortDy,
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
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(displayProgram, "stillShortScalarGain"),
                    scalarGain);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(displayProgram, "stillGlobalShortOffsetPixels"),
                    globalShortDx, globalShortDy);
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

        private int[] countAtlasMasks(int texture, int width, int height) {
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder());
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, texture, 0);
            int fbStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (fbStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "V2.20 topology readback framebuffer incomplete: 0x"
                                + Integer.toHexString(fbStatus));
            }
            GLES30.glReadPixels(
                    0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba);
            rgba.rewind();
            int owned = 0;
            int domain = 0;
            for (int i = 0; i < width * height; i++) {
                int r = rgba.get() & 0xFF;
                int g = rgba.get() & 0xFF;
                rgba.get();
                rgba.get();
                if (r >= 128) owned++;
                if (g >= 128) domain++;
            }
            return new int[] {owned, domain};
        }

        private static void setTextureFilter(int texture, int filter) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
        }

        private static float median3(float a, float b, float c) {
            return a + b + c
                    - Math.max(a, Math.max(b, c))
                    - Math.min(a, Math.min(b, c));
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
