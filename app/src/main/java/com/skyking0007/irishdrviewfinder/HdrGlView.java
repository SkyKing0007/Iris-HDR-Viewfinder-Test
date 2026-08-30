package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class HdrGlView extends GLSurfaceView {
    enum Mode { NORMAL, SPLIT, HDR }

    private final HdrRenderer renderer;

    HdrGlView(Context context) {
        this(context, null);
    }

    HdrGlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);
        renderer = new HdrRenderer(context.getApplicationContext());
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    void enqueueFrame(YuvFrame frame, FrameMeta meta) {
        renderer.enqueue(frame, meta);
        requestRender();
    }

    void setMode(Mode mode) {
        renderer.mode = mode;
        requestRender();
    }

    void setRelativeRotationDegrees(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        renderer.rotationQuarterTurns = (normalized / 90) % 4;
        requestRender();
    }

    long getDroppedRenderFrames() {
        return renderer.droppedFrames;
    }

    double getFusionFps() {
        return renderer.fusionFps;
    }

    private static final class Packet {
        final YuvFrame frame;
        final FrameMeta meta;

        Packet(YuvFrame frame, FrameMeta meta) {
            this.frame = frame;
            this.meta = meta;
        }
    }

    private static final class HdrRenderer implements GLSurfaceView.Renderer {
        private final Context context;
        private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(6);
        private final FloatBuffer vertexBuffer;
        private final FloatBuffer rawUvBuffer;
        private final FloatBuffer displayUvBuffer;

        volatile Mode mode = Mode.HDR;
        volatile int rotationQuarterTurns = 0;
        volatile long droppedFrames = 0;
        volatile double fusionFps = 0.0;

        private int yuvProgram;
        private int displayProgram;
        private int yTexture;
        private int uTexture;
        private int vTexture;
        private int normalTexture;
        private int shortTexture;
        private int longTexture;
        private int framebuffer;
        private int frameWidth;
        private int frameHeight;
        private int surfaceWidth;
        private int surfaceHeight;
        private boolean haveNormal;
        private boolean haveShort;
        private boolean haveLong;
        private FrameMeta lastShortMeta;
        private FrameMeta lastLongMeta;
        private long fpsWindowStartNs;
        private int fpsWindowFrames;

        HdrRenderer(Context context) {
            this.context = context;
            float[] vertices = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
            float[] rawUvs = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};
            float[] displayUvs = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
            vertexBuffer = directFloatBuffer(vertices);
            rawUvBuffer = directFloatBuffer(rawUvs);
            displayUvBuffer = directFloatBuffer(displayUvs);
        }

        void enqueue(YuvFrame frame, FrameMeta meta) {
            Packet packet = new Packet(frame, meta);
            while (!queue.offer(packet)) {
                queue.poll();
                droppedFrames++;
            }
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            String vertexShader = loadAsset(context, "shaders/fullscreen.vert");
            String yuvShader = loadAsset(context, "shaders/yuv_to_rgb.frag");
            String displayShader = loadAsset(context, "shaders/hdr_display.frag");
            yuvProgram = buildProgram(vertexShader, yuvShader);
            displayProgram = buildProgram(vertexShader, displayShader);
            yTexture = createTexture();
            uTexture = createTexture();
            vTexture = createTexture();
            normalTexture = createTexture();
            shortTexture = createTexture();
            longTexture = createTexture();
            int[] fb = new int[1];
            GLES30.glGenFramebuffers(1, fb, 0);
            framebuffer = fb[0];
            GLES30.glClearColor(0f, 0f, 0f, 1f);
            fpsWindowStartNs = System.nanoTime();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceWidth = width;
            surfaceHeight = height;
            GLES30.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            Packet packet;
            while ((packet = queue.poll()) != null) {
                processPacket(packet);
            }
            drawDisplay();
            updateFps();
        }

        private void processPacket(Packet packet) {
            YuvFrame frame = packet.frame;
            if (frame.width != frameWidth || frame.height != frameHeight) {
                frameWidth = frame.width;
                frameHeight = frame.height;
                allocateRgbTexture(normalTexture, frameWidth, frameHeight);
                allocateRgbTexture(shortTexture, frameWidth, frameHeight);
                allocateRgbTexture(longTexture, frameWidth, frameHeight);
                haveNormal = false;
                haveShort = false;
                haveLong = false;
            }

            uploadPlane(yTexture, frame.width, frame.height, frame.y);
            uploadPlane(uTexture, (frame.width + 1) / 2, (frame.height + 1) / 2, frame.u);
            uploadPlane(vTexture, (frame.width + 1) / 2, (frame.height + 1) / 2, frame.v);

            int target;
            if (FrameMeta.SHORT.equals(packet.meta.kind)) {
                target = shortTexture;
                haveShort = true;
                lastShortMeta = packet.meta;
            } else if (FrameMeta.LONG.equals(packet.meta.kind)) {
                target = longTexture;
                haveLong = true;
                lastLongMeta = packet.meta;
            } else {
                target = normalTexture;
                haveNormal = true;
            }
            renderYuvToTexture(target);
            fpsWindowFrames++;
        }

        private void renderYuvToTexture(int targetTexture) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            GLES30.glViewport(0, 0, frameWidth, frameHeight);
            GLES30.glUseProgram(yuvProgram);
            bindQuad(rawUvBuffer);
            bindSampler(yuvProgram, "yTex", yTexture, 0);
            bindSampler(yuvProgram, "uTex", uTexture, 1);
            bindSampler(yuvProgram, "vTex", vTexture, 2);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        private void drawDisplay() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glUseProgram(displayProgram);
            bindQuad(displayUvBuffer);
            bindSampler(displayProgram, "normalTex", normalTexture, 0);
            bindSampler(displayProgram, "shortTex", shortTexture, 1);
            bindSampler(displayProgram, "longTex", longTexture, 2);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "mode"), mode.ordinal());
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(displayProgram, "rotationQuarterTurns"),
                    rotationQuarterTurns);
            setCropScaleUniform(displayProgram, "fullCropScale", surfaceWidth, surfaceHeight);
            setCropScaleUniform(displayProgram, "splitCropScale", surfaceWidth * 0.5f, surfaceHeight);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveNormal"), haveNormal ? 1 : 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveShort"), haveShort ? 1 : 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "haveLong"), haveLong ? 1 : 0);
            float ratio = 1.0f;
            if (lastShortMeta != null && lastLongMeta != null) {
                double r = lastLongMeta.exposureProduct() / lastShortMeta.exposureProduct();
                ratio = (float) Math.max(1.0, Math.min(16.0, r));
            }
            GLES30.glUniform1f(GLES30.glGetUniformLocation(displayProgram, "exposureRatio"), ratio);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void bindQuad(FloatBuffer textureCoordinates) {
            vertexBuffer.position(0);
            textureCoordinates.position(0);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, textureCoordinates);
        }

        private void setCropScaleUniform(int program, String name, float viewportWidth, float viewportHeight) {
            float scaleX = 1.0f;
            float scaleY = 1.0f;
            if (frameWidth > 0 && frameHeight > 0 && viewportWidth > 0.0f && viewportHeight > 0.0f) {
                boolean quarterTurn = (rotationQuarterTurns & 1) != 0;
                float rotatedWidth = quarterTurn ? frameHeight : frameWidth;
                float rotatedHeight = quarterTurn ? frameWidth : frameHeight;
                float imageAspect = rotatedWidth / rotatedHeight;
                float viewportAspect = viewportWidth / viewportHeight;
                if (viewportAspect > imageAspect) {
                    scaleY = imageAspect / viewportAspect;
                } else if (viewportAspect < imageAspect) {
                    scaleX = viewportAspect / imageAspect;
                }
            }
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(program, name),
                    scaleX,
                    scaleY);
        }

        private static void bindSampler(int program, String name, int texture, int unit) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit);
        }

        private static void uploadPlane(int texture, int width, int height, ByteBuffer data) {
            data.position(0);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_R8,
                    width,
                    height,
                    0,
                    GLES30.GL_RED,
                    GLES30.GL_UNSIGNED_BYTE,
                    data);
        }

        private static void allocateRgbTexture(int texture, int width, int height) {
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

        private static int createTexture() {
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
                fusionFps = fpsWindowFrames * 1_000_000_000.0 / elapsed;
                fpsWindowFrames = 0;
                fpsWindowStartNs = now;
            }
        }
    }
}
