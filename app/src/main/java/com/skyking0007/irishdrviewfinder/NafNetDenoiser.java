package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.TensorBuffer;

import java.util.List;

/**
 * V2.24 post-fusion NAFNet-SIDD width32 residual-cleanup owner.
 *
 * Contract:
 * - consumes only the completed V2.22 FUSED presentation JPEG;
 * - never participates in SHORT/LONG alignment, recovery ownership, or tone decisions;
 * - preserves full output resolution via fixed 256x256 NCHW inference tiles;
 * - writes only the 192x192 center core (32px context halo) of each tile;
 * - removes broad/DC neural residual so NAFNet cannot relight/recolor smooth surfaces;
 * - grants neural authority only inside positively proven locally smooth source regions;
 * - coherent high-frequency structure is source-authoritative independent of semantics/adjacency;
 * - bounds the remaining learned residual and strongly tapers it in recovered/highlight pixels;
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
    private static final int RESIDUAL_MEAN_RADIUS = 12;
    private static final int SMOOTH_RADIUS = 2;
    private static final int BROAD_SMOOTH_RADIUS = RESIDUAL_MEAN_RADIUS;
    private static final float LUMA_STD_SOFT = 6.0f / 255.0f;
    private static final float LUMA_STD_HARD = 14.0f / 255.0f;
    private static final float CHROMA_STD_SOFT = 8.0f / 255.0f;
    private static final float CHROMA_STD_HARD = 22.0f / 255.0f;
    // The residual-mean window must itself be a smooth source interior. This is a
    // fail-closed guard against a textured object influencing NAFNet authority in
    // an adjacent flat wall through the wider low-frequency residual estimate.
    private static final float BROAD_LUMA_STD_SOFT = 12.0f / 255.0f;
    private static final float BROAD_LUMA_STD_HARD = 24.0f / 255.0f;
    private static final float BROAD_CHROMA_STD_SOFT = 14.0f / 255.0f;
    private static final float BROAD_CHROMA_STD_HARD = 28.0f / 255.0f;
    private static final float GRADIENT_SOFT = 4.0f / 255.0f;
    private static final float GRADIENT_HARD = 12.0f / 255.0f;
    private static final float COHERENT_ENERGY_SOFT = 2.0f / 255.0f;
    private static final float COHERENT_ENERGY_HARD = 8.0f / 255.0f;
    private static final float COHERENCE_SOFT = 0.20f;
    private static final float COHERENCE_HARD = 0.55f;
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
        float[] residualR = new float[TILE * TILE];
        float[] residualG = new float[TILE * TILE];
        float[] residualB = new float[TILE * TILE];
        float[] luma = new float[TILE * TILE];
        float[] chromaRg = new float[TILE * TILE];
        float[] chromaBg = new float[TILE * TILE];
        float[] integralResidualR = new float[(TILE + 1) * (TILE + 1)];
        float[] integralResidualG = new float[(TILE + 1) * (TILE + 1)];
        float[] integralResidualB = new float[(TILE + 1) * (TILE + 1)];
        float[] integralLuma = new float[(TILE + 1) * (TILE + 1)];
        float[] integralLumaSq = new float[(TILE + 1) * (TILE + 1)];
        float[] integralRg = new float[(TILE + 1) * (TILE + 1)];
        float[] integralRgSq = new float[(TILE + 1) * (TILE + 1)];
        float[] integralBg = new float[(TILE + 1) * (TILE + 1)];
        float[] integralBgSq = new float[(TILE + 1) * (TILE + 1)];
        float[] tensorXx = new float[TILE * TILE];
        float[] tensorYy = new float[TILE * TILE];
        float[] tensorXy = new float[TILE * TILE];
        float[] integralTensorXx = new float[(TILE + 1) * (TILE + 1)];
        float[] integralTensorYy = new float[(TILE + 1) * (TILE + 1)];
        float[] integralTensorXy = new float[(TILE + 1) * (TILE + 1)];
        long totalPixels = 0L;
        long denoisePixels = 0L;
        long protectedPixels = 0L;

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

                    prepareTileAnalysis(
                            input,
                            prediction,
                            residualR,
                            residualG,
                            residualB,
                            luma,
                            chromaRg,
                            chromaBg,
                            integralResidualR,
                            integralResidualG,
                            integralResidualB,
                            integralLuma,
                            integralLumaSq,
                            integralRg,
                            integralRgSq,
                            integralBg,
                            integralBgSq,
                            tensorXx, tensorYy, tensorXy,
                            integralTensorXx, integralTensorYy, integralTensorXy);

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
                            float sourceLuma = luma[tileIndex];
                            float smoothAuthority = smoothSourceAuthority(
                                    tileIndex,
                                    luma, chromaRg, chromaBg,
                                    integralLuma, integralLumaSq,
                                    integralRg, integralRgSq,
                                    integralBg, integralBgSq,
                                    integralTensorXx, integralTensorYy, integralTensorXy);
                            float strength = highlightSafeStrength(
                                    sourceLuma, Math.max(r, Math.max(g, b))) * smoothAuthority;

                            // V2.24 universal structure contract: NAFNet is never allowed to
                            // reinterpret coherent source structure. Its broad/DC residual is
                            // removed per channel, and only a locally-proven smooth source pixel
                            // can receive the remaining zero-mean noise-like correction.
                            float meanR = boxMean(integralResidualR, tileIndex, RESIDUAL_MEAN_RADIUS);
                            float meanG = boxMean(integralResidualG, tileIndex, RESIDUAL_MEAN_RADIUS);
                            float meanB = boxMean(integralResidualB, tileIndex, RESIDUAL_MEAN_RADIUS);
                            float dr = clamp(residualR[tileIndex] - meanR, -MAX_RESIDUAL, MAX_RESIDUAL);
                            float dg = clamp(residualG[tileIndex] - meanG, -MAX_RESIDUAL, MAX_RESIDUAL);
                            float db = clamp(residualB[tileIndex] - meanB, -MAX_RESIDUAL, MAX_RESIDUAL);
                            float nr = clamp01(r + strength * dr);
                            float ng = clamp01(g + strength * dg);
                            float nb = clamp01(b + strength * db);
                            totalPixels++;
                            if (smoothAuthority >= 0.50f) denoisePixels++;
                            if (smoothAuthority <= 0.02f) protectedPixels++;

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
                            + " core=" + CORE + " halo=" + HALO
                            + " residualMeanRadius=" + RESIDUAL_MEAN_RADIUS
                            + " smoothAccepted=" + percent(denoisePixels, totalPixels)
                            + " structureProtected=" + percent(protectedPixels, totalPixels));
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

    private static void prepareTileAnalysis(
            float[] input,
            float[] prediction,
            float[] residualR,
            float[] residualG,
            float[] residualB,
            float[] luma,
            float[] chromaRg,
            float[] chromaBg,
            float[] integralResidualR,
            float[] integralResidualG,
            float[] integralResidualB,
            float[] integralLuma,
            float[] integralLumaSq,
            float[] integralRg,
            float[] integralRgSq,
            float[] integralBg,
            float[] integralBgSq,
            float[] tensorXx,
            float[] tensorYy,
            float[] tensorXy,
            float[] integralTensorXx,
            float[] integralTensorYy,
            float[] integralTensorXy) {
        final int plane = TILE * TILE;
        for (int i = 0; i < plane; i++) {
            float r = input[i];
            float g = input[plane + i];
            float b = input[2 * plane + i];
            residualR[i] = clamp(clamp01(prediction[i]) - r, -MAX_RESIDUAL, MAX_RESIDUAL);
            residualG[i] = clamp(clamp01(prediction[plane + i]) - g, -MAX_RESIDUAL, MAX_RESIDUAL);
            residualB[i] = clamp(clamp01(prediction[2 * plane + i]) - b, -MAX_RESIDUAL, MAX_RESIDUAL);
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            chromaRg[i] = r - g;
            chromaBg[i] = b - g;
        }
        buildIntegral(residualR, integralResidualR, false);
        buildIntegral(residualG, integralResidualG, false);
        buildIntegral(residualB, integralResidualB, false);
        buildIntegral(luma, integralLuma, false);
        buildIntegral(luma, integralLumaSq, true);
        buildIntegral(chromaRg, integralRg, false);
        buildIntegral(chromaRg, integralRgSq, true);
        buildIntegral(chromaBg, integralBg, false);
        buildIntegral(chromaBg, integralBgSq, true);

        // Build the universal structure tensor once per tile, then query it through
        // integral images. This keeps per-pixel protection O(1) instead of scanning a
        // 5x5 window for every output pixel on an already expensive neural path.
        java.util.Arrays.fill(tensorXx, 0.0f);
        java.util.Arrays.fill(tensorYy, 0.0f);
        java.util.Arrays.fill(tensorXy, 0.0f);
        for (int y = 1; y < TILE - 1; y++) {
            int row = y * TILE;
            for (int x = 1; x < TILE - 1; x++) {
                int i = row + x;
                float gxY = 0.5f * (luma[i + 1] - luma[i - 1]);
                float gyY = 0.5f * (luma[i + TILE] - luma[i - TILE]);
                float gxRg = 0.35f * (chromaRg[i + 1] - chromaRg[i - 1]);
                float gyRg = 0.35f * (chromaRg[i + TILE] - chromaRg[i - TILE]);
                float gxBg = 0.35f * (chromaBg[i + 1] - chromaBg[i - 1]);
                float gyBg = 0.35f * (chromaBg[i + TILE] - chromaBg[i - TILE]);
                tensorXx[i] = gxY * gxY + gxRg * gxRg + gxBg * gxBg;
                tensorYy[i] = gyY * gyY + gyRg * gyRg + gyBg * gyBg;
                tensorXy[i] = gxY * gyY + gxRg * gyRg + gxBg * gyBg;
            }
        }
        buildIntegral(tensorXx, integralTensorXx, false);
        buildIntegral(tensorYy, integralTensorYy, false);
        buildIntegral(tensorXy, integralTensorXy, false);
    }

    private static float smoothSourceAuthority(
            int tileIndex,
            float[] luma,
            float[] chromaRg,
            float[] chromaBg,
            float[] integralLuma,
            float[] integralLumaSq,
            float[] integralRg,
            float[] integralRgSq,
            float[] integralBg,
            float[] integralBgSq,
            float[] integralTensorXx,
            float[] integralTensorYy,
            float[] integralTensorXy) {
        float meanY = boxMean(integralLuma, tileIndex, SMOOTH_RADIUS);
        float meanYSq = boxMean(integralLumaSq, tileIndex, SMOOTH_RADIUS);
        float meanRg = boxMean(integralRg, tileIndex, SMOOTH_RADIUS);
        float meanRgSq = boxMean(integralRgSq, tileIndex, SMOOTH_RADIUS);
        float meanBg = boxMean(integralBg, tileIndex, SMOOTH_RADIUS);
        float meanBgSq = boxMean(integralBgSq, tileIndex, SMOOTH_RADIUS);
        float lumaStd = (float) Math.sqrt(Math.max(0.0f, meanYSq - meanY * meanY));
        float rgStd = (float) Math.sqrt(Math.max(0.0f, meanRgSq - meanRg * meanRg));
        float bgStd = (float) Math.sqrt(Math.max(0.0f, meanBgSq - meanBg * meanBg));
        float chromaStd = Math.max(rgStd, bgStd);

        int x = tileIndex % TILE;
        int y = tileIndex / TILE;
        float left = boxMeanAt(integralLuma, x - 2, y, 1);
        float right = boxMeanAt(integralLuma, x + 2, y, 1);
        float up = boxMeanAt(integralLuma, x, y - 2, 1);
        float down = boxMeanAt(integralLuma, x, y + 2, 1);
        float gradient = 0.5f * Math.max(Math.abs(right - left), Math.abs(down - up));

        float broadMeanY = boxMean(integralLuma, tileIndex, BROAD_SMOOTH_RADIUS);
        float broadMeanYSq = boxMean(integralLumaSq, tileIndex, BROAD_SMOOTH_RADIUS);
        float broadMeanRg = boxMean(integralRg, tileIndex, BROAD_SMOOTH_RADIUS);
        float broadMeanRgSq = boxMean(integralRgSq, tileIndex, BROAD_SMOOTH_RADIUS);
        float broadMeanBg = boxMean(integralBg, tileIndex, BROAD_SMOOTH_RADIUS);
        float broadMeanBgSq = boxMean(integralBgSq, tileIndex, BROAD_SMOOTH_RADIUS);
        float broadLumaStd = (float) Math.sqrt(
                Math.max(0.0f, broadMeanYSq - broadMeanY * broadMeanY));
        float broadRgStd = (float) Math.sqrt(
                Math.max(0.0f, broadMeanRgSq - broadMeanRg * broadMeanRg));
        float broadBgStd = (float) Math.sqrt(
                Math.max(0.0f, broadMeanBgSq - broadMeanBg * broadMeanBg));
        float broadChromaStd = Math.max(broadRgStd, broadBgStd);

        float lumaAuthority = 1.0f - smoothStep(LUMA_STD_SOFT, LUMA_STD_HARD, lumaStd);
        float chromaAuthority = 1.0f - smoothStep(CHROMA_STD_SOFT, CHROMA_STD_HARD, chromaStd);
        float gradientAuthority = 1.0f - smoothStep(GRADIENT_SOFT, GRADIENT_HARD, gradient);
        float broadLumaAuthority = 1.0f - smoothStep(
                BROAD_LUMA_STD_SOFT, BROAD_LUMA_STD_HARD, broadLumaStd);
        float broadChromaAuthority = 1.0f - smoothStep(
                BROAD_CHROMA_STD_SOFT, BROAD_CHROMA_STD_HARD, broadChromaStd);
        float coherentProtection = coherentStructureProtection(
                tileIndex, integralTensorXx, integralTensorYy, integralTensorXy);
        float authority = Math.min(
                Math.min(lumaAuthority, chromaAuthority),
                Math.min(
                        gradientAuthority,
                        Math.min(
                                Math.min(broadLumaAuthority, broadChromaAuthority),
                                1.0f - coherentProtection)));
        // Positive proof only. Ambiguous texture/noise boundaries fail closed to source.
        return authority < 0.02f ? 0.0f : clamp01(authority);
    }

    private static float coherentStructureProtection(
            int tileIndex,
            float[] integralTensorXx,
            float[] integralTensorYy,
            float[] integralTensorXy) {
        float sxx = boxMean(integralTensorXx, tileIndex, 2);
        float syy = boxMean(integralTensorYy, tileIndex, 2);
        float sxy = boxMean(integralTensorXy, tileIndex, 2);
        float trace = sxx + syy;
        float discriminant = (float) Math.sqrt(
                Math.max(0.0f, (sxx - syy) * (sxx - syy) + 4.0f * sxy * sxy));
        float coherence = discriminant / Math.max(trace, 1.0e-8f);
        float rmsGradient = (float) Math.sqrt(Math.max(trace, 0.0f));
        float energy = smoothStep(COHERENT_ENERGY_SOFT, COHERENT_ENERGY_HARD, rmsGradient);
        float directional = smoothStep(COHERENCE_SOFT, COHERENCE_HARD, coherence);
        return clamp01(energy * directional);
    }

    private static void buildIntegral(float[] source, float[] integral, boolean square) {
        java.util.Arrays.fill(integral, 0.0f);
        int stride = TILE + 1;
        for (int y = 0; y < TILE; y++) {
            float row = 0.0f;
            int srcRow = y * TILE;
            int outRow = (y + 1) * stride;
            int prevRow = y * stride;
            for (int x = 0; x < TILE; x++) {
                float value = source[srcRow + x];
                if (square) value *= value;
                row += value;
                integral[outRow + x + 1] = integral[prevRow + x + 1] + row;
            }
        }
    }

    private static float boxMean(float[] integral, int tileIndex, int radius) {
        return boxMeanAt(integral, tileIndex % TILE, tileIndex / TILE, radius);
    }

    private static float boxMeanAt(float[] integral, int centerX, int centerY, int radius) {
        int x0 = Math.max(0, centerX - radius);
        int y0 = Math.max(0, centerY - radius);
        int x1 = Math.min(TILE - 1, centerX + radius);
        int y1 = Math.min(TILE - 1, centerY + radius);
        int stride = TILE + 1;
        float sum = integral[(y1 + 1) * stride + (x1 + 1)]
                - integral[y0 * stride + (x1 + 1)]
                - integral[(y1 + 1) * stride + x0]
                + integral[y0 * stride + x0];
        int count = (x1 - x0 + 1) * (y1 - y0 + 1);
        return count <= 0 ? 0.0f : sum / count;
    }

    private static float smoothStep(float lo, float hi, float value) {
        if (hi <= lo) return value >= hi ? 1.0f : 0.0f;
        float t = clamp01((value - lo) / (hi - lo));
        return t * t * (3.0f - 2.0f * t);
    }

    private static String percent(long numerator, long denominator) {
        if (denominator <= 0L) return "0.0%";
        return String.format(java.util.Locale.US, "%.1f%%", 100.0 * numerator / denominator);
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
