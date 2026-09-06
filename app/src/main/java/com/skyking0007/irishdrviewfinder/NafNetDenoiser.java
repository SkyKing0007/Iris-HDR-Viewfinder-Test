package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.TensorBuffer;

import java.util.List;

/**
 * V2.23 post-fusion NAFNet-SIDD width32 owner.
 *
 * Contract:
 * - consumes only the completed V2.22 FUSED presentation JPEG;
 * - never participates in SHORT/LONG alignment, recovery ownership, or tone decisions;
 * - preserves full output resolution via fixed 256x256 NCHW inference tiles;
 * - writes only the 192x192 center core (32px context halo) of each tile;
 * - bounds the learned residual and strongly tapers it in recovered/highlight pixels;
 * - serializes GPU use and closes all LiteRT resources after every fused capture.
 */
final class NafNetDenoiser {
    static final String MODEL_ASSET = "nafnet_sidd_width32_fp16.tflite";
    static final String EXPECTED_MODEL_SHA256 =
            "f8fbaa422411683c53e802cf7cc7cf9be0a0de00886ad4af057232e26b172a0c";

    private static final int TILE = 256;
    private static final int HALO = 32;
    private static final int CORE = TILE - 2 * HALO;
    private static final float MAX_RESIDUAL = 0.12f;
    private static final Object GPU_LOCK = new Object();

    private NafNetDenoiser() {}

    static byte[] denoiseFusedJpeg(Context context, byte[] fusedJpeg) throws Exception {
        if (context == null) throw new IllegalArgumentException("context == null");
        if (fusedJpeg == null || fusedJpeg.length == 0) {
            throw new IllegalArgumentException("empty fused JPEG");
        }
        synchronized (GPU_LOCK) {
            return denoiseLocked(context.getApplicationContext(), fusedJpeg);
        }
    }

    private static byte[] denoiseLocked(Context context, byte[] fusedJpeg) throws Exception {
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(
                fusedJpeg, 0, fusedJpeg.length, decodeOptions);
        if (decoded == null) throw new IllegalArgumentException("cannot decode fused JPEG");

        final int width = decoded.getWidth();
        final int height = decoded.getHeight();
        if (width <= 0 || height <= 0) {
            decoded.recycle();
            throw new IllegalArgumentException("invalid fused dimensions");
        }

        // Keep one packed source plane instead of two full-size Bitmaps while the model is live.
        final int[] source = new int[Math.multiplyExact(width, height)];
        decoded.getPixels(source, 0, width, 0, 0, width, height);
        decoded.recycle();

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        float[] input = new float[3 * TILE * TILE];
        int[] corePixels = new int[CORE * CORE];

        CompiledModel model = null;
        List<TensorBuffer> inputBuffers = null;
        List<TensorBuffer> outputBuffers = null;
        try {
            CompiledModel.Options options = new CompiledModel.Options(Accelerator.GPU);
            model = CompiledModel.create(
                    context.getAssets(), MODEL_ASSET, options, null);
            inputBuffers = model.createInputBuffers();
            outputBuffers = model.createOutputBuffers();
            if (inputBuffers.size() != 1 || outputBuffers.size() != 1) {
                throw new IllegalStateException(
                        "NAFNet tensor count mismatch in=" + inputBuffers.size()
                                + " out=" + outputBuffers.size());
            }

            TensorBuffer inputBuffer = inputBuffers.get(0);
            TensorBuffer outputBuffer = outputBuffers.get(0);
            final int plane = TILE * TILE;

            for (int coreY = 0; coreY < height; coreY += CORE) {
                final int copyH = Math.min(CORE, height - coreY);
                for (int coreX = 0; coreX < width; coreX += CORE) {
                    final int copyW = Math.min(CORE, width - coreX);
                    fillInputTile(source, width, height, coreX - HALO, coreY - HALO, input);
                    inputBuffer.writeFloat(input);
                    model.run(inputBuffers, outputBuffers);
                    float[] prediction = outputBuffer.readFloat();
                    if (prediction.length != 3 * plane) {
                        throw new IllegalStateException(
                                "NAFNet output length " + prediction.length
                                        + " != " + (3 * plane));
                    }

                    for (int y = 0; y < copyH; y++) {
                        int srcRow = (coreY + y) * width;
                        int outRow = y * CORE;
                        int tileRow = (HALO + y) * TILE;
                        for (int x = 0; x < copyW; x++) {
                            int original = source[srcRow + coreX + x];
                            float r = ((original >>> 16) & 0xff) / 255.0f;
                            float g = ((original >>> 8) & 0xff) / 255.0f;
                            float b = (original & 0xff) / 255.0f;
                            int tileIndex = tileRow + HALO + x;
                            float pr = clamp01(prediction[tileIndex]);
                            float pg = clamp01(prediction[plane + tileIndex]);
                            float pb = clamp01(prediction[2 * plane + tileIndex]);

                            // V2.23 never gives the network unrestricted ownership of the image.
                            // Dark/body pixels receive full SIDD denoising; bright recovery regions
                            // increasingly preserve the exact V2.22 source structure.
                            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                            float strength = highlightSafeStrength(luma, Math.max(r, Math.max(g, b)));
                            float nr = clamp01(r + strength * clamp(pr - r, -MAX_RESIDUAL, MAX_RESIDUAL));
                            float ng = clamp01(g + strength * clamp(pg - g, -MAX_RESIDUAL, MAX_RESIDUAL));
                            float nb = clamp01(b + strength * clamp(pb - b, -MAX_RESIDUAL, MAX_RESIDUAL));

                            corePixels[outRow + x] = 0xff000000
                                    | (toByte(nr) << 16)
                                    | (toByte(ng) << 8)
                                    | toByte(nb);
                        }
                    }
                    output.setPixels(corePixels, 0, CORE, coreX, coreY, copyW, copyH);
                }
            }

            byte[] encoded = JpegFusion.encodeJpeg(output);
            RuntimeLogger.event(
                    "NAFNET_DENOISE",
                    "applied model=NAFNet-SIDD-width32 fp16 GPU fullRes="
                            + width + "x" + height + " tile=" + TILE
                            + " core=" + CORE + " halo=" + HALO);
            return encoded;
        } finally {
            if (outputBuffers != null) {
                for (TensorBuffer buffer : outputBuffers) {
                    try { buffer.close(); } catch (Throwable ignored) {}
                }
            }
            if (inputBuffers != null) {
                for (TensorBuffer buffer : inputBuffers) {
                    try { buffer.close(); } catch (Throwable ignored) {}
                }
            }
            if (model != null) {
                try { model.close(); } catch (Throwable ignored) {}
            }
            output.recycle();
        }
    }

    private static void fillInputTile(
            int[] source,
            int width,
            int height,
            int originX,
            int originY,
            float[] input) {
        final int plane = TILE * TILE;
        for (int y = 0; y < TILE; y++) {
            int sy = reflect101(originY + y, height);
            int srcRow = sy * width;
            int tileRow = y * TILE;
            for (int x = 0; x < TILE; x++) {
                int sx = reflect101(originX + x, width);
                int pixel = source[srcRow + sx];
                int i = tileRow + x;
                input[i] = ((pixel >>> 16) & 0xff) / 255.0f;
                input[plane + i] = ((pixel >>> 8) & 0xff) / 255.0f;
                input[2 * plane + i] = (pixel & 0xff) / 255.0f;
            }
        }
    }

    private static int reflect101(int value, int size) {
        if (size <= 1) return 0;
        int v = value;
        while (v < 0 || v >= size) {
            if (v < 0) v = -v;
            if (v >= size) v = 2 * size - 2 - v;
        }
        return v;
    }

    private static float highlightSafeStrength(float luma, float maxChannel) {
        float strength;
        if (luma <= 0.70f) {
            strength = 1.0f;
        } else if (luma >= 0.92f) {
            strength = 0.20f;
        } else {
            float t = (luma - 0.70f) / 0.22f;
            t = t * t * (3.0f - 2.0f * t);
            strength = 1.0f + (0.20f - 1.0f) * t;
        }
        if (maxChannel >= 0.985f) strength = Math.min(strength, 0.10f);
        return strength;
    }

    private static int toByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
