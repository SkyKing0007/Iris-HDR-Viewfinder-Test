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

    private final HdrRenderer renderer;
    private volatile InputSurfaceListener inputSurfaceListener;
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
        renderer.displayBrightnessEv = Math.max(-4.0f, Math.min(4.0f, ev));
        requestRender();
    }

    void setDisplayGamma(float gamma) {
        renderer.displayGamma = Math.max(0.50f, Math.min(2.00f, gamma));
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

        private final Context context;
        private final FloatBuffer vertexBuffer;
        private final FloatBuffer displayUvBuffer;
        private final Map<Long, FrameMeta> metaByTimestamp = new ConcurrentHashMap<>();
        private final AtomicInteger frameSignals = new AtomicInteger();
        private final PendingFrame[] pendingFrames = new PendingFrame[PENDING_SLOTS];

        volatile Mode mode = Mode.HDR;
        volatile float displayBrightnessEv = 0.0f;
        volatile float displayGamma = 1.0f;
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
        private int framebuffer;
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
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
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
