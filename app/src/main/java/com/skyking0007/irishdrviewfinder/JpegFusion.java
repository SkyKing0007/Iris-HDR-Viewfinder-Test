package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class JpegFusion {
    static final int RELIABILITY_WIDTH = 32;
    static final int RELIABILITY_HEIGHT = 24;
    static final int RELIABILITY_CHANNELS = 2;
    static final int RELIABILITY_MAP_BYTES =
            RELIABILITY_WIDTH * RELIABILITY_HEIGHT * RELIABILITY_CHANNELS;

    private static final float[] SRGB_TO_LINEAR = buildLinearLut();
    private static final int PHOTO_KNOT_COUNT = 5;
    private static final float[] PHOTO_LUMA_KNOTS = {0.020f, 0.060f, 0.150f, 0.350f, 0.700f};
    private static final float PHOTO_SCALE_MIN = 0.60f;
    private static final float PHOTO_SCALE_MAX = 1.50f;
    private static final int PHOTO_MIN_BIN_SAMPLES = 24;
    private static final int TILE_ROWS = 512;
    private static final int FLICKER_FIELD_WIDTH = 16;
    private static final int FLICKER_ROW_HEIGHT = 64;
    private static final int FLICKER_FIELD_CHANNELS = 3;
    private static final int DEFAULT_LUMA_RELIABILITY = 224;
    private static final int DEFAULT_CHROMA_RELIABILITY = 128;

    private JpegFusion() {}

    static byte[] fuse(
            Context context,
            byte[] shortJpeg,
            byte[] longJpeg,
            double exposureRatio,
            byte[] shortReliabilityMap,
            boolean flickerGuardRequired) throws Exception {
        long allStart = System.nanoTime();
        long stageStart = allStart;
        Bitmap shortBitmap = decodeUpright(shortJpeg);
        Bitmap longBitmap = decodeUpright(longJpeg);
        RuntimeLogger.event("FUSION_DECODE", "ms=" + elapsedMs(stageStart));
        if (shortBitmap == null || longBitmap == null) {
            recycle(shortBitmap);
            recycle(longBitmap);
            throw new IllegalStateException("Unable to decode capture JPEGs");
        }
        if (shortBitmap.getWidth() != longBitmap.getWidth()
                || shortBitmap.getHeight() != longBitmap.getHeight()) {
            recycle(shortBitmap);
            recycle(longBitmap);
            throw new IllegalStateException("Short/long JPEG dimensions do not match");
        }

        float ratio = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
        stageStart = System.nanoTime();
        PhotoCurve photoCurve = learnPhotoCurve(shortBitmap, longBitmap, ratio);
        RuntimeLogger.event(
                "FUSION_CURVE",
                "ms=" + elapsedMs(stageStart)
                        + " scale=" + Arrays.toString(photoCurve.relativeScale));

        stageStart = System.nanoTime();
        byte[] flickerField = learnFlickerField(shortBitmap, longBitmap, ratio, photoCurve);
        RuntimeLogger.event("FUSION_FLICKER_FIELD", "ms=" + elapsedMs(stageStart));

        Bitmap output;
        stageStart = System.nanoTime();
        try (GpuStillFusion gpu = new GpuStillFusion(context)) {
            output = gpu.fuse(
                    shortBitmap, longBitmap, ratio, photoCurve,
                    shortReliabilityMap, flickerField, flickerGuardRequired);
        }
        RuntimeLogger.event(
                "FUSION_GPU",
                "size=" + output.getWidth() + "x" + output.getHeight()
                        + " ms=" + elapsedMs(stageStart));
        recycle(shortBitmap);
        recycle(longBitmap);

        stageStart = System.nanoTime();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ok = output.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
        output.recycle();
        if (!ok) throw new IllegalStateException("JPEG encoder rejected fused bitmap");
        RuntimeLogger.event(
                "FUSION_ENCODE",
                "bytes=" + bytes.size() + " ms=" + elapsedMs(stageStart));
        RuntimeLogger.event("FUSION_TOTAL", "ms=" + elapsedMs(allStart));
        return bytes.toByteArray();
    }

    private static final class GpuStillFusion implements AutoCloseable {
        private static final float[] POSITIONS = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };

        private final Context context;
        private final FloatBuffer positionBuffer = directFloatBuffer(POSITIONS);
        private final FloatBuffer uvBuffer = directFloatBuffer(new float[8]);
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private int program;
        private int shortTexture;
        private int longTexture;
        private int reliabilityTexture;
        private int flickerFieldTexture;
        private int outputTexture;
        private int framebuffer;

        GpuStillFusion(Context context) {
            this.context = context.getApplicationContext();
            createEgl();
            createGlObjects();
        }

        Bitmap fuse(
                Bitmap shortBitmap,
                Bitmap longBitmap,
                float ratio,
                PhotoCurve photoCurve,
                byte[] reliabilityMap,
                byte[] flickerField,
                boolean flickerGuardRequired) {
            int width = shortBitmap.getWidth();
            int height = shortBitmap.getHeight();
            int minDimension = Math.max(1, Math.min(width, height));
            int fusionRadius = Math.max(1, Math.min(4, Math.round(minDimension / 720.0f)));
            int maxExpandedRows = Math.min(height, TILE_ROWS + fusionRadius * 2);
            int[] maxTextureSize = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
            if (width > maxTextureSize[0] || maxExpandedRows > maxTextureSize[0]) {
                throw new IllegalStateException(
                        "Capture exceeds GPU texture limit " + width + "x" + maxExpandedRows
                                + " max=" + maxTextureSize[0]);
            }

            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Bitmap shortTile = Bitmap.createBitmap(width, maxExpandedRows, Bitmap.Config.ARGB_8888);
            Bitmap longTile = Bitmap.createBitmap(width, maxExpandedRows, Bitmap.Config.ARGB_8888);
            int[] uploadPixels = new int[width * maxExpandedRows];
            int[] outputPixels = new int[width * TILE_ROWS];
            ByteBuffer readback = ByteBuffer.allocateDirect(width * TILE_ROWS * 4)
                    .order(ByteOrder.nativeOrder());

            allocateRgbaTexture(shortTexture, width, maxExpandedRows);
            allocateRgbaTexture(longTexture, width, maxExpandedRows);
            allocateRgbaTexture(outputTexture, width, TILE_ROWS);
            allocateRgbTexture(
                    flickerFieldTexture, FLICKER_FIELD_WIDTH, FLICKER_ROW_HEIGHT);
            uploadReliability(reliabilityMap);
            uploadFlickerField(flickerField);
            configureStaticUniforms(ratio, photoCurve, fusionRadius, width, maxExpandedRows);
            GLES30.glUseProgram(program);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(program, "flickerGuardRequired"),
                    flickerGuardRequired ? 1 : 0);

            int tiles = 0;
            for (int y = 0; y < height; y += TILE_ROWS) {
                int rows = Math.min(TILE_ROWS, height - y);
                int expandedStart = Math.max(0, y - fusionRadius);
                int expandedEnd = Math.min(height, y + rows + fusionRadius);
                int expandedRows = expandedEnd - expandedStart;
                int coreOffset = y - expandedStart;

                shortBitmap.getPixels(
                        uploadPixels, 0, width, 0, expandedStart, width, expandedRows);
                int uploadRows = padBottomEdgeIfNeeded(
                        uploadPixels, width, expandedRows, maxExpandedRows, expandedEnd == height);
                shortTile.setPixels(uploadPixels, 0, width, 0, 0, width, uploadRows);
                uploadBitmap(shortTexture, shortTile);
                longBitmap.getPixels(
                        uploadPixels, 0, width, 0, expandedStart, width, expandedRows);
                uploadRows = padBottomEdgeIfNeeded(
                        uploadPixels, width, expandedRows, maxExpandedRows, expandedEnd == height);
                longTile.setPixels(uploadPixels, 0, width, 0, 0, width, uploadRows);
                uploadBitmap(longTexture, longTile);

                attachOutputTexture();
                GLES30.glUseProgram(program);
                float v0 = coreOffset / (float) maxExpandedRows;
                float v1 = (coreOffset + rows) / (float) maxExpandedRows;
                setTileUvs(v0, v1);
                GLES30.glUniform2f(
                        GLES30.glGetUniformLocation(program, "reliabilityUvScale"),
                        1.0f, maxExpandedRows / (float) height);
                GLES30.glUniform2f(
                        GLES30.glGetUniformLocation(program, "reliabilityUvOffset"),
                        0.0f, expandedStart / (float) height);
                GLES30.glViewport(0, 0, width, rows);
                bindQuad();
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                checkGl("draw tile " + tiles);

                int count = width * rows;
                readback.clear();
                GLES30.glReadPixels(
                        0, 0, width, rows,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readback);
                checkGl("read tile " + tiles);
                readback.rewind();
                IntBuffer ints = readback.asIntBuffer();
                ints.get(outputPixels, 0, count);
                rgbaNativeToArgb(outputPixels, count);
                output.setPixels(outputPixels, 0, width, 0, y, width, rows);
                tiles++;
            }

            shortTile.recycle();
            longTile.recycle();
            RuntimeLogger.event(
                    "FUSION_GPU_TILES",
                    "tiles=" + tiles + " tileRows=" + TILE_ROWS + " halo=" + fusionRadius
                            + " glMaxTexture=" + maxTextureSize[0]);
            return output;
        }

        private void createEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new IllegalStateException("eglGetDisplay failed");
            }
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
                throw new IllegalStateException("eglInitialize failed 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
            }
            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                throw new IllegalStateException("eglBindAPI failed");
            }
            int[] configAttributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(
                    eglDisplay, configAttributes, 0,
                    configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] < 1) {
                throw new IllegalStateException("No GLES3 pbuffer EGL config");
            }
            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(
                    eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    contextAttributes, 0);
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new IllegalStateException("eglCreateContext failed 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
            }
            int[] surfaceAttributes = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay, configs[0], surfaceAttributes, 0);
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreatePbufferSurface failed 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
            }
            if (!EGL14.eglMakeCurrent(
                    eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new IllegalStateException("eglMakeCurrent failed 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
            }
        }

        private void createGlObjects() {
            String vertexShader = loadAsset(context, "shaders/fullscreen.vert");
            String fragmentShader = loadAsset(context, "shaders/hdr_display.frag");
            program = buildProgram(vertexShader, fragmentShader);
            shortTexture = createTexture2d();
            longTexture = createTexture2d();
            reliabilityTexture = createTexture2d();
            flickerFieldTexture = createTexture2d();
            outputTexture = createTexture2d();
            int[] fb = new int[1];
            GLES30.glGenFramebuffers(1, fb, 0);
            framebuffer = fb[0];
            GLES30.glUseProgram(program);
            bindSampler(program, "normalTex", longTexture, 0);
            bindSampler(program, "shortTex", shortTexture, 1);
            bindSampler(program, "longTex", longTexture, 2);
            bindSampler(program, "shortReliabilityTex", reliabilityTexture, 3);
            bindSampler(program, "flickerFieldTex", flickerFieldTexture, 4);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "mode"), 2);
            GLES30.glUniform1i(
                    GLES30.glGetUniformLocation(program, "rotationQuarterTurns"), 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "haveNormal"), 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "haveShort"), 1);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "haveLong"), 1);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(program, "fullFitScale"), 1.0f, 1.0f);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(program, "splitFitScale"), 1.0f, 1.0f);
        }

        private void configureStaticUniforms(
                float ratio,
                PhotoCurve photoCurve,
                int fusionRadius,
                int width,
                int textureRows) {
            GLES30.glUseProgram(program);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(program, "exposureRatio"), ratio);
            GLES30.glUniform4f(
                    GLES30.glGetUniformLocation(program, "shortPhotoScaleA"),
                    photoCurve.relativeScale[0], photoCurve.relativeScale[1],
                    photoCurve.relativeScale[2], photoCurve.relativeScale[3]);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(program, "shortPhotoScaleB"),
                    photoCurve.relativeScale[4]);
            GLES30.glUniform2f(
                    GLES30.glGetUniformLocation(program, "fusionTexelStep"),
                    fusionRadius / (float) width,
                    fusionRadius / (float) textureRows);
        }

        private void uploadReliability(byte[] reliabilityMap) {
            byte[] bytes = reliabilityMap;
            if (bytes == null || bytes.length != RELIABILITY_MAP_BYTES) {
                bytes = new byte[RELIABILITY_MAP_BYTES];
                for (int i = 0; i < RELIABILITY_WIDTH * RELIABILITY_HEIGHT; i++) {
                    bytes[i * 2] = (byte) DEFAULT_LUMA_RELIABILITY;
                    bytes[i * 2 + 1] = (byte) DEFAULT_CHROMA_RELIABILITY;
                }
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder());
            buffer.put(bytes).flip();
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, reliabilityTexture);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8,
                    RELIABILITY_WIDTH, RELIABILITY_HEIGHT, 0,
                    GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, buffer);
            checkGl("upload reliability");
        }

        private void uploadFlickerField(byte[] fieldBytes) {
            int expected = FLICKER_FIELD_WIDTH * FLICKER_ROW_HEIGHT * FLICKER_FIELD_CHANNELS;
            byte[] bytes = fieldBytes;
            if (bytes == null || bytes.length != expected) {
                bytes = defaultFlickerField();
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder());
            buffer.put(bytes).flip();
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flickerFieldTexture);
            GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0,
                    FLICKER_FIELD_WIDTH, FLICKER_ROW_HEIGHT,
                    GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer);
            checkGl("upload flicker field");
        }

        private void uploadBitmap(int texture, Bitmap bitmap) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            checkGl("upload bitmap");
        }

        private void attachOutputTexture() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D,
                    outputTexture,
                    0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Still fusion framebuffer incomplete 0x"
                        + Integer.toHexString(status));
            }
        }

        private void setTileUvs(float v0, float v1) {
            uvBuffer.clear();
            uvBuffer.put(new float[]{
                    0f, v0,
                    1f, v0,
                    0f, v1,
                    1f, v1
            }).flip();
        }

        private void bindQuad() {
            GLES30.glUseProgram(program);
            positionBuffer.position(0);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(
                    0, 2, GLES30.GL_FLOAT, false, 0, positionBuffer);
            uvBuffer.position(0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(
                    1, 2, GLES30.GL_FLOAT, false, 0, uvBuffer);
            bindSampler(program, "normalTex", longTexture, 0);
            bindSampler(program, "shortTex", shortTexture, 1);
            bindSampler(program, "longTex", longTexture, 2);
            bindSampler(program, "shortReliabilityTex", reliabilityTexture, 3);
            bindSampler(program, "flickerFieldTex", flickerFieldTexture, 4);
        }

        @Override
        public void close() {
            // Do not eglTerminate(EGL_DEFAULT_DISPLAY): the live GLSurfaceView owns a
            // separate context on the same process display. Destroy only this worker's
            // objects/context so still fusion can never tear down the live viewfinder.
            if (eglDisplay != EGL14.EGL_NO_DISPLAY
                    && eglContext != EGL14.EGL_NO_CONTEXT) {
                int[] textures = {
                        shortTexture, longTexture, reliabilityTexture, flickerFieldTexture, outputTexture};
                GLES30.glDeleteTextures(textures.length, textures, 0);
                if (framebuffer != 0) {
                    int[] framebuffers = {framebuffer};
                    GLES30.glDeleteFramebuffers(1, framebuffers, 0);
                }
                if (program != 0) GLES30.glDeleteProgram(program);
                EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglReleaseThread();
            eglSurface = EGL14.EGL_NO_SURFACE;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        private static int createTexture2d() {
            int[] textures = new int[1];
            GLES30.glGenTextures(1, textures, 0);
            int texture = textures[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_MAG_FILTER,
                    GLES30.GL_LINEAR);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_WRAP_S,
                    GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_WRAP_T,
                    GLES30.GL_CLAMP_TO_EDGE);
            return texture;
        }

        private static void allocateRgbaTexture(int texture, int width, int height) {
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
            checkGl("allocate " + width + "x" + height);
        }

        private static void allocateRgbTexture(int texture, int width, int height) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB8, width, height, 0,
                    GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, null);
            checkGl("allocate RGB " + width + "x" + height);
        }

        private static void bindSampler(int program, String name, int texture, int unit) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit);
        }

        private static FloatBuffer directFloatBuffer(float[] values) {
            ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer floats = bytes.asFloatBuffer();
            floats.put(values).flip();
            return floats;
        }

        private static int padBottomEdgeIfNeeded(
                int[] pixels, int width, int rows, int maxRows, boolean atImageBottom) {
            if (!atImageBottom || rows <= 0 || rows >= maxRows) return rows;
            int lastRow = (rows - 1) * width;
            for (int row = rows; row < maxRows; row++) {
                System.arraycopy(pixels, lastRow, pixels, row * width, width);
            }
            return maxRows;
        }

        private static void rgbaNativeToArgb(int[] pixels, int count) {
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                for (int i = 0; i < count; i++) {
                    int rgba = pixels[i];
                    pixels[i] = (rgba & 0xFF00FF00)
                            | ((rgba & 0x00FF0000) >>> 16)
                            | ((rgba & 0x000000FF) << 16);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    int rgba = pixels[i];
                    pixels[i] = ((rgba & 0x000000FF) << 24)
                            | ((rgba & 0xFF000000) >>> 8)
                            | ((rgba & 0x00FF0000) >>> 8)
                            | ((rgba & 0x0000FF00) >>> 8);
                }
            }
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

        private static void checkGl(String stage) {
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(stage + " GL error 0x"
                        + Integer.toHexString(error));
            }
        }
    }

    private static byte[] learnFlickerField(
            Bitmap shortBitmap, Bitmap longBitmap, float ratio, PhotoCurve photoCurve) {
        byte[] field = defaultFlickerField();
        int width = shortBitmap.getWidth();
        int height = shortBitmap.getHeight();
        for (int fy = 0; fy < FLICKER_ROW_HEIGHT; fy++) {
            for (int fx = 0; fx < FLICKER_FIELD_WIDTH; fx++) {
                float logGainSum = 0.0f;
                float logGainSqSum = 0.0f;
                float chromaDeltaSum = 0.0f;
                float weightSum = 0.0f;
                for (int sx = 0; sx < 8; sx++) {
                        float ux = (fx + (sx + 0.5f) / 8.0f) / FLICKER_FIELD_WIDTH;
                        float uy = (fy + 0.5f) / FLICKER_ROW_HEIGHT;
                        int x = Math.max(0, Math.min(width - 1, Math.round(ux * (width - 1))));
                        int y = Math.max(0, Math.min(height - 1, Math.round(uy * (height - 1))));
                        int sp = shortBitmap.getPixel(x, y);
                        int lp = longBitmap.getPixel(x, y);
                        int sr = (sp >>> 16) & 0xFF;
                        int sg = (sp >>> 8) & 0xFF;
                        int sb = sp & 0xFF;
                        int lr = (lp >>> 16) & 0xFF;
                        int lg = (lp >>> 8) & 0xFF;
                        int lb = lp & 0xFF;
                        float longMin = Math.min(lr, Math.min(lg, lb)) / 255.0f;
                        float longMax = Math.max(lr, Math.max(lg, lb)) / 255.0f;
                        float shortMin = Math.min(sr, Math.min(sg, sb)) / 255.0f;
                        float shortMax = Math.max(sr, Math.max(sg, sb)) / 255.0f;
                        float longValid = smoothstep(0.025f, 0.070f, longMin)
                                * (1.0f - smoothstep(0.86f, 0.93f, longMax));
                        float shortValid = smoothstep(0.008f, 0.020f, shortMin)
                                * (1.0f - smoothstep(0.86f, 0.93f, shortMax));
                        float weight = longValid * shortValid;
                        if (weight <= 0.02f) continue;

                        float longR = SRGB_TO_LINEAR[lr];
                        float longG = SRGB_TO_LINEAR[lg];
                        float longB = SRGB_TO_LINEAR[lb];
                        float longLuma = 0.2126f * longR + 0.7152f * longG + 0.0722f * longB;
                        float shortR = SRGB_TO_LINEAR[sr] * ratio;
                        float shortG = SRGB_TO_LINEAR[sg] * ratio;
                        float shortB = SRGB_TO_LINEAR[sb] * ratio;
                        float normalizedLuma = 0.2126f * shortR + 0.7152f * shortG + 0.0722f * shortB;
                        float photoScale = shortPhotoScaleForLuma(normalizedLuma, photoCurve);
                        shortR *= photoScale;
                        shortG *= photoScale;
                        shortB *= photoScale;
                        float shortLuma = normalizedLuma * photoScale;
                        if (longLuma <= 0.002f || shortLuma <= 0.002f) continue;

                        float gainEv = clamp(
                                (float) (Math.log(longLuma / shortLuma) / Math.log(2.0)),
                                -1.5f, 1.5f);
                        float invLong = 1.0f / Math.max(longLuma, 0.0005f);
                        float invShort = 1.0f / Math.max(shortLuma, 0.0005f);
                        float chromaDelta = Math.max(
                                Math.abs(longR * invLong - shortR * invShort),
                                Math.max(
                                        Math.abs(longG * invLong - shortG * invShort),
                                        Math.abs(longB * invLong - shortB * invShort)));
                        logGainSum += gainEv * weight;
                        logGainSqSum += gainEv * gainEv * weight;
                        chromaDeltaSum += chromaDelta * weight;
                        weightSum += weight;
                    }

                int index = (fy * FLICKER_FIELD_WIDTH + fx) * FLICKER_FIELD_CHANNELS;
                if (weightSum < 1.20f) continue;
                float meanEv = logGainSum / weightSum;
                float varianceEv = Math.max(0.0f, logGainSqSum / weightSum - meanEv * meanEv);
                float sigmaEv = (float) Math.sqrt(varianceEv);
                float evidence = smoothstep(1.20f, 4.00f, weightSum);
                float consistency = 1.0f - smoothstep(0.12f, 0.38f, sigmaEv);
                float amplitudeTrust = 1.0f - smoothstep(1.35f, 1.50f, Math.abs(meanEv));
                float lumaTrust = evidence * consistency * amplitudeTrust;
                float chromaMean = chromaDeltaSum / weightSum;
                float chromaTrust = lumaTrust
                        * (1.0f - smoothstep(0.045f, 0.140f, chromaMean));
                float correctedEv = clamp(meanEv, -1.5f, 1.5f) * lumaTrust;
                field[index] = encodeUnit(correctedEv / 3.0f + 0.5f);
                field[index + 1] = encodeUnit(lumaTrust);
                field[index + 2] = encodeUnit(chromaTrust);
            }
        }
        return field;
    }

    private static byte[] defaultFlickerField() {
        byte[] field = new byte[
                FLICKER_FIELD_WIDTH * FLICKER_ROW_HEIGHT * FLICKER_FIELD_CHANNELS];
        for (int i = 0; i < FLICKER_FIELD_WIDTH * FLICKER_ROW_HEIGHT; i++) {
            field[i * FLICKER_FIELD_CHANNELS] = (byte) 128;
            field[i * FLICKER_FIELD_CHANNELS + 1] = 0;
            field[i * FLICKER_FIELD_CHANNELS + 2] = 0;
        }
        return field;
    }

    private static byte encodeUnit(float value) {
        int encoded = Math.round(clamp(value, 0.0f, 1.0f) * 255.0f);
        return (byte) (encoded & 0xFF);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float shortPhotoScaleForLuma(float normalizedLuma, PhotoCurve photoCurve) {
        float value = Math.max(normalizedLuma, 0.00001f);
        if (value <= PHOTO_LUMA_KNOTS[0]) return photoCurve.relativeScale[0];
        for (int i = 1; i < PHOTO_KNOT_COUNT; i++) {
            if (value <= PHOTO_LUMA_KNOTS[i]) {
                float low = PHOTO_LUMA_KNOTS[i - 1];
                float high = PHOTO_LUMA_KNOTS[i];
                float t = clamp(
                        (float) (Math.log(value / low) / Math.log(high / low)),
                        0.0f, 1.0f);
                return photoCurve.relativeScale[i - 1]
                        + (photoCurve.relativeScale[i] - photoCurve.relativeScale[i - 1]) * t;
            }
        }
        return photoCurve.relativeScale[PHOTO_KNOT_COUNT - 1];
    }

    private static PhotoCurve learnPhotoCurve(
            Bitmap shortBitmap, Bitmap longBitmap, float ratio) {
        int width = shortBitmap.getWidth();
        int height = shortBitmap.getHeight();
        int step = Math.max(4, Math.min(width, height) / 192);
        int maxSamples = ((width + step - 1) / step) * ((height + step - 1) / step);
        float[] calibrationRatios = new float[maxSamples];
        float[] photoRatios = new float[maxSamples];
        float[] normalizedShortLumas = new float[maxSamples];
        int count = 0;
        int[] shortRow = new int[width];
        int[] longRow = new int[width];
        for (int y = step / 2; y < height; y += step) {
            shortBitmap.getPixels(shortRow, 0, width, 0, y, width, 1);
            longBitmap.getPixels(longRow, 0, width, 0, y, width, 1);
            for (int x = step / 2; x < width; x += step) {
                int sp = shortRow[x];
                int lp = longRow[x];
                int sr8 = (sp >>> 16) & 0xFF;
                int sg8 = (sp >>> 8) & 0xFF;
                int sb8 = sp & 0xFF;
                int lr8 = (lp >>> 16) & 0xFF;
                int lg8 = (lp >>> 8) & 0xFF;
                int lb8 = lp & 0xFF;
                if (!overlapEncoded(sr8, sg8, sb8, lr8, lg8, lb8)) continue;
                float shortLuma = (0.2126f * SRGB_TO_LINEAR[sr8]
                        + 0.7152f * SRGB_TO_LINEAR[sg8]
                        + 0.0722f * SRGB_TO_LINEAR[sb8]) * ratio;
                float longLuma = 0.2126f * SRGB_TO_LINEAR[lr8]
                        + 0.7152f * SRGB_TO_LINEAR[lg8]
                        + 0.0722f * SRGB_TO_LINEAR[lb8];
                if (shortLuma <= 0.015f || longLuma <= 0.015f) continue;
                float responseRatio = longLuma / shortLuma;
                normalizedShortLumas[count] = shortLuma;
                calibrationRatios[count] = clamp(responseRatio, 0.75f, 1.33f);
                photoRatios[count] = clamp(responseRatio, PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
                count++;
            }
        }
        if (count < 24) {
            return new PhotoCurve(new float[]{1f, 1f, 1f, 1f, 1f});
        }
        float[] copy = Arrays.copyOf(calibrationRatios, count);
        float calibration = medianPrefix(copy, count);
        float[][] bins = new float[PHOTO_KNOT_COUNT][count];
        int[] binCounts = new int[PHOTO_KNOT_COUNT];
        for (int i = 0; i < count; i++) {
            int bin = photoBinForLuma(normalizedShortLumas[i]);
            bins[bin][binCounts[bin]++] = photoRatios[i];
        }
        float[] target = new float[PHOTO_KNOT_COUNT];
        for (int bin = 0; bin < PHOTO_KNOT_COUNT; bin++) {
            if (binCounts[bin] >= PHOTO_MIN_BIN_SAMPLES) {
                target[bin] = clamp(
                        medianPrefix(bins[bin], binCounts[bin]),
                        PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            } else {
                target[bin] = clamp(calibration, PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
            }
        }
        float[] smooth = target.clone();
        smooth[0] = clamp(
                0.75f * target[0] + 0.25f * target[1],
                PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        for (int bin = 1; bin < PHOTO_KNOT_COUNT - 1; bin++) {
            smooth[bin] = clamp(
                    0.25f * target[bin - 1]
                            + 0.50f * target[bin]
                            + 0.25f * target[bin + 1],
                    PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        }
        smooth[PHOTO_KNOT_COUNT - 1] = clamp(
                0.25f * target[PHOTO_KNOT_COUNT - 2]
                        + 0.75f * target[PHOTO_KNOT_COUNT - 1],
                PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        enforceMonotonicPhotoCurve(smooth);
        return new PhotoCurve(smooth);
    }

    private static void enforceMonotonicPhotoCurve(float[] scale) {
        for (int bin = 1; bin < PHOTO_KNOT_COUNT; bin++) {
            float previousOutput = PHOTO_LUMA_KNOTS[bin - 1] * scale[bin - 1];
            float minimumScale = previousOutput * 1.01f / PHOTO_LUMA_KNOTS[bin];
            scale[bin] = clamp(
                    Math.max(scale[bin], minimumScale),
                    PHOTO_SCALE_MIN,
                    PHOTO_SCALE_MAX);
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

    private static final class PhotoCurve {
        final float[] relativeScale;

        PhotoCurve(float[] relativeScale) {
            this.relativeScale = relativeScale;
        }
    }

    private static boolean overlapEncoded(
            int sr, int sg, int sb, int lr, int lg, int lb) {
        return sr >= 4 && sg >= 4 && sb >= 4
                && sr <= 230 && sg <= 230 && sb <= 230
                && lr >= 20 && lg >= 20 && lb >= 20
                && lr <= 230 && lg <= 230 && lb <= 230;
    }

    private static Bitmap decodeUpright(byte[] jpeg) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (decoded == null) return null;

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (ByteArrayInputStream input = new ByteArrayInputStream(jpeg)) {
            ExifInterface exif = new ExifInterface(input);
            orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        }

        Matrix matrix = new Matrix();
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
            matrix.postRotate(90.0f);
        } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
            matrix.postRotate(180.0f);
        } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
            matrix.postRotate(270.0f);
        } else {
            return decoded;
        }

        Bitmap upright = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.getWidth(),
                decoded.getHeight(),
                matrix,
                true);
        if (upright != decoded) decoded.recycle();
        return upright;
    }

    private static float medianPrefix(float[] values, int count) {
        Arrays.sort(values, 0, count);
        int mid = count / 2;
        if ((count & 1) != 0) return values[mid];
        return 0.5f * (values[mid - 1] + values[mid]);
    }

    private static float[] buildLinearLut() {
        float[] lut = new float[256];
        for (int i = 0; i < lut.length; i++) {
            double encoded = i / 255.0;
            lut[i] = (float) (encoded <= 0.04045
                    ? encoded / 12.92
                    : Math.pow((encoded + 0.055) / 1.055, 2.4));
        }
        return lut;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static long elapsedMs(long startNs) {
        return Math.round((System.nanoTime() - startNs) / 1_000_000.0);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
