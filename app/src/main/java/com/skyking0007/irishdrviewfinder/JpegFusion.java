package com.skyking0007.irishdrviewfinder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class JpegFusion {
    private static final float[] SRGB_TO_LINEAR = buildLinearLut();
    private static final int[] LINEAR_TO_SRGB = buildEncodeLut();
    private static final double LOG_2 = Math.log(2.0);
    private static final float HDR_KNEE = 0.70f;
    private static final float HDR_CLIP_END = 0.995f;

    private JpegFusion() {}

    static final class Registration {
        final float sampleDx;
        final float sampleDy;
        final float score;
        final float margin;
        final float confidence;
        final float cycleError;

        Registration(
                float sampleDx,
                float sampleDy,
                float score,
                float margin,
                float confidence,
                float cycleError) {
            this.sampleDx = sampleDx;
            this.sampleDy = sampleDy;
            this.score = score;
            this.margin = margin;
            this.confidence = confidence;
            this.cycleError = cycleError;
        }
    }

    static final class AppearanceGain {
        final float r;
        final float g;
        final float b;

        AppearanceGain(float r, float g, float b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    // V2.14 strict still-registration residual field. The proven V2.13 global
    // translation remains the coarse anchor. This field carries only a small,
    // spatially-smooth residual displacement and an independently validated
    // confidence; it is never allowed to become unconstrained optical flow.
    static final class LocalRegistrationField {
        final int gridWidth;
        final int gridHeight;
        final byte[] rgba;
        final float meanConfidence;
        final float supportedFraction;
        final float maxResidualPixels;
        final float observedResidualPixels;

        LocalRegistrationField(
                int gridWidth,
                int gridHeight,
                byte[] rgba,
                float meanConfidence,
                float supportedFraction,
                float maxResidualPixels,
                float observedResidualPixels) {
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.rgba = rgba;
            this.meanConfidence = meanConfidence;
            this.supportedFraction = supportedFraction;
            this.maxResidualPixels = maxResidualPixels;
            this.observedResidualPixels = observedResidualPixels;
        }
    }

    private static final class LocalMatch {
        final float dx;
        final float dy;
        final float score;
        final float margin;
        final float confidence;

        LocalMatch(float dx, float dy, float score, float margin, float confidence) {
            this.dx = dx;
            this.dy = dy;
            this.score = score;
            this.margin = margin;
            this.confidence = confidence;
        }
    }

    private static final class OneWayRegistration {
        final float sampleDx;
        final float sampleDy;
        final float score;
        final float margin;
        final float confidence;

        OneWayRegistration(float sampleDx, float sampleDy, float score, float margin, float confidence) {
            this.sampleDx = sampleDx;
            this.sampleDy = sampleDy;
            this.score = score;
            this.margin = margin;
            this.confidence = confidence;
        }
    }

    static Registration estimateRegistration(Bitmap movingBitmap, Bitmap referenceBitmap) {
        if (movingBitmap == null || referenceBitmap == null
                || movingBitmap.getWidth() != referenceBitmap.getWidth()
                || movingBitmap.getHeight() != referenceBitmap.getHeight()) {
            return new Registration(0.0f, 0.0f, -1.0f, 0.0f, 0.0f, Float.POSITIVE_INFINITY);
        }
        OneWayRegistration forward = estimateOneWayRegistration(movingBitmap, referenceBitmap);
        OneWayRegistration backward = estimateOneWayRegistration(referenceBitmap, movingBitmap);
        float cycleError = (float) Math.hypot(
                forward.sampleDx + backward.sampleDx,
                forward.sampleDy + backward.sampleDy);
        float cycleConfidence = 1.0f - smoothstep(0.45f, 1.50f, cycleError);
        float bidirectional = (float) Math.sqrt(
                Math.max(0.0f, forward.confidence * backward.confidence));
        float confidence = bidirectional * cycleConfidence;
        return new Registration(
                forward.sampleDx,
                forward.sampleDy,
                forward.score,
                forward.margin,
                confidence,
                cycleError);
    }

    private static OneWayRegistration estimateOneWayRegistration(
            Bitmap movingBitmap, Bitmap referenceBitmap) {
        final int width = referenceBitmap.getWidth();
        final int height = referenceBitmap.getHeight();
        final int maxDimension = 384;
        float scale = Math.min(1.0f, maxDimension / (float) Math.max(width, height));
        int smallWidth = Math.max(48, Math.round(width * scale));
        int smallHeight = Math.max(36, Math.round(height * scale));
        Bitmap movingSmall = Bitmap.createScaledBitmap(movingBitmap, smallWidth, smallHeight, true);
        Bitmap referenceSmall = Bitmap.createScaledBitmap(referenceBitmap, smallWidth, smallHeight, true);
        try {
            int[] movingPixels = new int[smallWidth * smallHeight];
            int[] referencePixels = new int[smallWidth * smallHeight];
            movingSmall.getPixels(movingPixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
            referenceSmall.getPixels(referencePixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
            float[] movingLogY = logLuma(movingPixels);
            float[] referenceLogY = logLuma(referencePixels);
            float[] movingGx = new float[movingPixels.length];
            float[] movingGy = new float[movingPixels.length];
            float[] referenceGx = new float[referencePixels.length];
            float[] referenceGy = new float[referencePixels.length];
            computeGradients(movingLogY, smallWidth, smallHeight, movingGx, movingGy);
            computeGradients(referenceLogY, smallWidth, smallHeight, referenceGx, referenceGy);

            int radius = Math.min(8, Math.max(3, Math.round(48.0f * scale)));
            int side = 2 * radius + 1;
            float[] scores = new float[side * side];
            int bestX = 0;
            int bestY = 0;
            float bestScore = -1.0f;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    float score = gradientScore(
                            referenceGx, referenceGy, movingGx, movingGy,
                            smallWidth, smallHeight, dx, dy);
                    scores[(dy + radius) * side + (dx + radius)] = score;
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = dx;
                        bestY = dy;
                    }
                }
            }

            float secondBest = -1.0f;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.abs(dx - bestX) <= 1 && Math.abs(dy - bestY) <= 1) continue;
                    secondBest = Math.max(secondBest, scores[(dy + radius) * side + (dx + radius)]);
                }
            }
            float subX = bestX + parabolicOffset(scores, side, radius, bestX, bestY, true);
            float subY = bestY + parabolicOffset(scores, side, radius, bestX, bestY, false);
            float margin = Math.max(0.0f, bestScore - secondBest);
            float quality = smoothstep(0.22f, 0.52f, bestScore);
            float uniqueness = smoothstep(0.002f, 0.012f, margin);
            float boundary = (Math.abs(bestX) < radius && Math.abs(bestY) < radius) ? 1.0f : 0.0f;
            float confidence = quality * uniqueness * boundary;
            float invScale = 1.0f / scale;
            return new OneWayRegistration(
                    subX * invScale, subY * invScale, bestScore, margin, confidence);
        } finally {
            if (movingSmall != movingBitmap) recycle(movingSmall);
            if (referenceSmall != referenceBitmap) recycle(referenceSmall);
        }
    }

    static Bitmap alignLongToShort(Bitmap longBitmap, Registration registration) {
        Bitmap aligned = Bitmap.createBitmap(
                longBitmap.getWidth(), longBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(aligned);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Matrix matrix = new Matrix();
        // V2.15 invariant: SHORT is the immutable reference geometry. sampleDx/
        // sampleDy specify the moving LONG coordinate corresponding to a SHORT
        // output coordinate, so only LONG is resampled into SHORT coordinates.
        matrix.postTranslate(-registration.sampleDx, -registration.sampleDy);
        canvas.drawBitmap(longBitmap, matrix, paint);
        return aligned;
    }

    static LocalRegistrationField estimateLocalRegistration(
            Bitmap alignedMoving, Bitmap referenceBitmap) {
        final float maxResidualPixels = 4.0f;
        if (alignedMoving == null || referenceBitmap == null
                || alignedMoving.getWidth() != referenceBitmap.getWidth()
                || alignedMoving.getHeight() != referenceBitmap.getHeight()) {
            return neutralLocalRegistration(maxResidualPixels);
        }

        final int width = referenceBitmap.getWidth();
        final int height = referenceBitmap.getHeight();
        // 4096px stills analyze at 1024px max dimension. V2.15 estimates only
        // the residual displacement of moving LONG into immutable SHORT geometry.
        // The field is used only while deriving a low-frequency luminance envelope;
        // it is never applied to SHORT RGB/detail.
        final int maxDimension = 1024;
        float scale = Math.min(1.0f, maxDimension / (float) Math.max(width, height));
        int smallWidth = Math.max(96, Math.round(width * scale));
        int smallHeight = Math.max(72, Math.round(height * scale));
        Bitmap movingSmall = Bitmap.createScaledBitmap(
                alignedMoving, smallWidth, smallHeight, true);
        Bitmap referenceSmall = Bitmap.createScaledBitmap(
                referenceBitmap, smallWidth, smallHeight, true);
        try {
            int[] movingPixels = new int[smallWidth * smallHeight];
            int[] referencePixels = new int[smallWidth * smallHeight];
            movingSmall.getPixels(movingPixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
            referenceSmall.getPixels(referencePixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
            float[] movingLogY = logLuma(movingPixels);
            float[] referenceLogY = logLuma(referencePixels);
            float[] movingGx = new float[movingPixels.length];
            float[] movingGy = new float[movingPixels.length];
            float[] referenceGx = new float[referencePixels.length];
            float[] referenceGy = new float[referencePixels.length];
            computeGradients(movingLogY, smallWidth, smallHeight, movingGx, movingGy);
            computeGradients(referenceLogY, smallWidth, smallHeight, referenceGx, referenceGy);

            final int cell = 32;
            final int searchRadius = 2;
            final int windowRadius = 12;
            final int border = windowRadius + searchRadius + 3;
            int gridWidth = Math.max(2, (smallWidth + cell - 1) / cell);
            int gridHeight = Math.max(2, (smallHeight + cell - 1) / cell);
            int count = gridWidth * gridHeight;
            float[] rawDx = new float[count];
            float[] rawDy = new float[count];
            float[] rawConfidence = new float[count];
            float invScale = 1.0f / scale;

            for (int gy = 0; gy < gridHeight; gy++) {
                int cy = Math.round((gy + 0.5f) * smallHeight / gridHeight);
                cy = Math.max(border, Math.min(smallHeight - border - 1, cy));
                for (int gx = 0; gx < gridWidth; gx++) {
                    int cx = Math.round((gx + 0.5f) * smallWidth / gridWidth);
                    cx = Math.max(border, Math.min(smallWidth - border - 1, cx));
                    int i = gy * gridWidth + gx;

                    LocalMatch forward = localGradientMatch(
                            referenceGx, referenceGy, movingGx, movingGy,
                            smallWidth, smallHeight, cx, cy,
                            searchRadius, windowRadius);
                    int backCx = Math.max(border, Math.min(
                            smallWidth - border - 1, Math.round(cx + forward.dx)));
                    int backCy = Math.max(border, Math.min(
                            smallHeight - border - 1, Math.round(cy + forward.dy)));
                    LocalMatch backward = localGradientMatch(
                            movingGx, movingGy, referenceGx, referenceGy,
                            smallWidth, smallHeight, backCx, backCy,
                            searchRadius, windowRadius);
                    float cycleError = (float) Math.hypot(
                            forward.dx + backward.dx,
                            forward.dy + backward.dy);
                    float cycleConfidence = 1.0f - smoothstep(0.20f, 0.90f, cycleError);
                    float bidirectional = (float) Math.sqrt(Math.max(
                            0.0f, forward.confidence * backward.confidence));
                    float confidence = bidirectional * cycleConfidence;
                    float dx = clamp(forward.dx * invScale,
                            -maxResidualPixels, maxResidualPixels);
                    float dy = clamp(forward.dy * invScale,
                            -maxResidualPixels, maxResidualPixels);
                    if (confidence < 0.28f) {
                        dx = 0.0f;
                        dy = 0.0f;
                        confidence = 0.0f;
                    }
                    rawDx[i] = dx;
                    rawDy[i] = dy;
                    rawConfidence[i] = confidence;
                }
            }

            // First pass: smooth only neighbors that agree with a supported center.
            // Unsupported/clipped cells may be filled from a 5x5 coherent camera-
            // motion neighborhood, but only when that neighborhood has low residual
            // dispersion. This lets a clipped window inherit nearby camera motion
            // without propagating independently moving foliage or people.
            float[] fieldDx = new float[count];
            float[] fieldDy = new float[count];
            float[] fieldConfidence = new float[count];
            for (int gy = 0; gy < gridHeight; gy++) {
                for (int gx = 0; gx < gridWidth; gx++) {
                    int i = gy * gridWidth + gx;
                    float centerConfidence = rawConfidence[i];
                    if (centerConfidence > 0.0f) {
                        float dx = rawDx[i];
                        float dy = rawDy[i];
                        float weightSum = centerConfidence;
                        float dxSum = dx * centerConfidence;
                        float dySum = dy * centerConfidence;
                        int coherent = 1;
                        for (int oy = -1; oy <= 1; oy++) {
                            for (int ox = -1; ox <= 1; ox++) {
                                if (ox == 0 && oy == 0) continue;
                                int nx = gx + ox;
                                int ny = gy + oy;
                                if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue;
                                int ni = ny * gridWidth + nx;
                                float nc = rawConfidence[ni];
                                if (nc <= 0.0f) continue;
                                float disagreement = (float) Math.hypot(
                                        rawDx[ni] - dx, rawDy[ni] - dy);
                                if (disagreement > 1.50f) continue;
                                dxSum += rawDx[ni] * nc;
                                dySum += rawDy[ni] * nc;
                                weightSum += nc;
                                coherent++;
                            }
                        }
                        if (coherent >= 3 && weightSum > 0.0f) {
                            fieldDx[i] = dxSum / weightSum;
                            fieldDy[i] = dySum / weightSum;
                            fieldConfidence[i] = centerConfidence
                                    * smoothstep(2.0f, 5.0f, coherent);
                        }
                        continue;
                    }

                    float weightSum = 0.0f;
                    float dxSum = 0.0f;
                    float dySum = 0.0f;
                    int neighbors = 0;
                    for (int oy = -2; oy <= 2; oy++) {
                        for (int ox = -2; ox <= 2; ox++) {
                            if (ox == 0 && oy == 0) continue;
                            int nx = gx + ox;
                            int ny = gy + oy;
                            if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue;
                            int ni = ny * gridWidth + nx;
                            float nc = rawConfidence[ni];
                            if (nc <= 0.0f) continue;
                            float distanceWeight = 1.0f / (1.0f + ox * ox + oy * oy);
                            float w = nc * distanceWeight;
                            dxSum += rawDx[ni] * w;
                            dySum += rawDy[ni] * w;
                            weightSum += w;
                            neighbors++;
                        }
                    }
                    if (neighbors < 5 || weightSum <= 0.0f) continue;
                    float meanDx = dxSum / weightSum;
                    float meanDy = dySum / weightSum;
                    float varianceSum = 0.0f;
                    float confidenceSum = 0.0f;
                    int coherent = 0;
                    for (int oy = -2; oy <= 2; oy++) {
                        for (int ox = -2; ox <= 2; ox++) {
                            if (ox == 0 && oy == 0) continue;
                            int nx = gx + ox;
                            int ny = gy + oy;
                            if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue;
                            int ni = ny * gridWidth + nx;
                            float nc = rawConfidence[ni];
                            if (nc <= 0.0f) continue;
                            float disagreement = (float) Math.hypot(
                                    rawDx[ni] - meanDx, rawDy[ni] - meanDy);
                            if (disagreement > 1.25f) continue;
                            float distanceWeight = 1.0f / (1.0f + ox * ox + oy * oy);
                            float w = nc * distanceWeight;
                            varianceSum += disagreement * disagreement * w;
                            confidenceSum += nc;
                            coherent++;
                        }
                    }
                    float rms = (float) Math.sqrt(varianceSum / Math.max(weightSum, 0.0001f));
                    if (coherent >= 5 && rms <= 0.75f) {
                        fieldDx[i] = meanDx;
                        fieldDy[i] = meanDy;
                        float neighborhoodConfidence = confidenceSum / coherent;
                        fieldConfidence[i] = Math.min(0.65f, neighborhoodConfidence)
                                * smoothstep(4.0f, 9.0f, coherent)
                                * (1.0f - smoothstep(0.45f, 0.75f, rms));
                    }
                }
            }

            // Second pass: bounded 3x3 regularization keeps the residual field
            // continuous. A disagreeing cell never borrows from the opposite side
            // of a motion boundary; unsupported cells remain global-only.
            byte[] rgba = new byte[count * 4];
            float confidenceSum = 0.0f;
            int supported = 0;
            float observedMax = 0.0f;
            for (int gy = 0; gy < gridHeight; gy++) {
                for (int gx = 0; gx < gridWidth; gx++) {
                    int i = gy * gridWidth + gx;
                    float centerConfidence = fieldConfidence[i];
                    float dx = fieldDx[i];
                    float dy = fieldDy[i];
                    if (centerConfidence > 0.0f) {
                        float weightSum = centerConfidence;
                        float dxSum = dx * centerConfidence;
                        float dySum = dy * centerConfidence;
                        for (int oy = -1; oy <= 1; oy++) {
                            for (int ox = -1; ox <= 1; ox++) {
                                if (ox == 0 && oy == 0) continue;
                                int nx = gx + ox;
                                int ny = gy + oy;
                                if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue;
                                int ni = ny * gridWidth + nx;
                                float nc = fieldConfidence[ni];
                                if (nc <= 0.0f) continue;
                                float disagreement = (float) Math.hypot(
                                        fieldDx[ni] - dx, fieldDy[ni] - dy);
                                if (disagreement > 1.0f) continue;
                                dxSum += fieldDx[ni] * nc;
                                dySum += fieldDy[ni] * nc;
                                weightSum += nc;
                            }
                        }
                        dx = dxSum / Math.max(weightSum, 0.0001f);
                        dy = dySum / Math.max(weightSum, 0.0001f);
                    }
                    dx = clamp(dx, -maxResidualPixels, maxResidualPixels);
                    dy = clamp(dy, -maxResidualPixels, maxResidualPixels);
                    int o = i * 4;
                    rgba[o] = (byte) Math.round(
                            255.0f * (0.5f + 0.5f * dx / maxResidualPixels));
                    rgba[o + 1] = (byte) Math.round(
                            255.0f * (0.5f + 0.5f * dy / maxResidualPixels));
                    rgba[o + 2] = (byte) Math.round(
                            255.0f * clamp(centerConfidence, 0.0f, 1.0f));
                    rgba[o + 3] = (byte) 255;
                    confidenceSum += centerConfidence;
                    if (centerConfidence >= 0.30f) supported++;
                    observedMax = Math.max(observedMax, (float) Math.hypot(dx, dy));
                }
            }
            return new LocalRegistrationField(
                    gridWidth,
                    gridHeight,
                    rgba,
                    confidenceSum / Math.max(1, count),
                    supported / (float) Math.max(1, count),
                    maxResidualPixels,
                    Math.min(maxResidualPixels, observedMax));
        } finally {
            if (movingSmall != alignedMoving) recycle(movingSmall);
            if (referenceSmall != referenceBitmap) recycle(referenceSmall);
        }
    }

    private static LocalRegistrationField neutralLocalRegistration(float maxResidualPixels) {
        return new LocalRegistrationField(
                1, 1,
                new byte[] {(byte) 128, (byte) 128, 0, (byte) 255},
                0.0f, 0.0f, maxResidualPixels, 0.0f);
    }

    private static LocalMatch localGradientMatch(
            float[] referenceGx,
            float[] referenceGy,
            float[] movingGx,
            float[] movingGy,
            int width,
            int height,
            int centerX,
            int centerY,
            int searchRadius,
            int windowRadius) {
        int side = 2 * searchRadius + 1;
        float[] scores = new float[side * side];
        Arrays.fill(scores, -1.0f);
        int bestX = 0;
        int bestY = 0;
        float bestScore = -1.0f;
        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                float score = localGradientScore(
                        referenceGx, referenceGy, movingGx, movingGy,
                        width, height, centerX, centerY, dx, dy, windowRadius);
                scores[(dy + searchRadius) * side + (dx + searchRadius)] = score;
                if (score > bestScore) {
                    bestScore = score;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }

        float secondBest = -1.0f;
        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                if (Math.abs(dx - bestX) <= 1 && Math.abs(dy - bestY) <= 1) continue;
                secondBest = Math.max(
                        secondBest, scores[(dy + searchRadius) * side + (dx + searchRadius)]);
            }
        }
        float subX = bestX + parabolicOffset(
                scores, side, searchRadius, bestX, bestY, true);
        float subY = bestY + parabolicOffset(
                scores, side, searchRadius, bestX, bestY, false);
        float margin = Math.max(0.0f, bestScore - secondBest);
        float quality = smoothstep(0.24f, 0.60f, bestScore);
        float uniqueness = smoothstep(0.003f, 0.030f, margin);
        float boundary = (Math.abs(bestX) < searchRadius && Math.abs(bestY) < searchRadius)
                ? 1.0f : 0.0f;
        return new LocalMatch(
                subX, subY, bestScore, margin, quality * uniqueness * boundary);
    }

    private static float localGradientScore(
            float[] referenceGx,
            float[] referenceGy,
            float[] movingGx,
            float[] movingGy,
            int width,
            int height,
            int centerX,
            int centerY,
            int dx,
            int dy,
            int windowRadius) {
        double dot = 0.0;
        double referenceEnergy = 0.0;
        double movingEnergy = 0.0;
        int useful = 0;
        for (int oy = -windowRadius; oy <= windowRadius; oy += 2) {
            int ry = centerY + oy;
            int my = ry + dy;
            if (ry <= 0 || ry >= height - 1 || my <= 0 || my >= height - 1) continue;
            for (int ox = -windowRadius; ox <= windowRadius; ox += 2) {
                int rx = centerX + ox;
                int mx = rx + dx;
                if (rx <= 0 || rx >= width - 1 || mx <= 0 || mx >= width - 1) continue;
                int ri = ry * width + rx;
                int mi = my * width + mx;
                float rgx = referenceGx[ri];
                float rgy = referenceGy[ri];
                float mgx = movingGx[mi];
                float mgy = movingGy[mi];
                float re = rgx * rgx + rgy * rgy;
                float me = mgx * mgx + mgy * mgy;
                if (re < 0.000004f || me < 0.000004f) continue;
                dot += rgx * mgx + rgy * mgy;
                referenceEnergy += re;
                movingEnergy += me;
                useful++;
            }
        }
        if (useful < 24 || referenceEnergy <= 0.0 || movingEnergy <= 0.0) return -1.0f;
        return (float) (dot / Math.sqrt(referenceEnergy * movingEnergy));
    }

    static AppearanceGain estimateAppearanceGain(
            Bitmap alignedShort, Bitmap longBitmap, double exposureRatio) {
        if (alignedShort == null || longBitmap == null
                || alignedShort.getWidth() != longBitmap.getWidth()
                || alignedShort.getHeight() != longBitmap.getHeight()) {
            float fallback = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
            return new AppearanceGain(fallback, fallback, fallback);
        }
        final int width = longBitmap.getWidth();
        final int height = longBitmap.getHeight();
        final int stride = 4;
        int capacity = ((width + stride - 1) / stride) * ((height + stride - 1) / stride);
        float[] redRatios = new float[capacity];
        float[] greenRatios = new float[capacity];
        float[] blueRatios = new float[capacity];
        int redCount = 0;
        int greenCount = 0;
        int blueCount = 0;
        int[] shortRow = new int[width];
        int[] longRow = new int[width];
        for (int y = 0; y < height; y += stride) {
            alignedShort.getPixels(shortRow, 0, width, 0, y, width, 1);
            longBitmap.getPixels(longRow, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x += stride) {
                int s = shortRow[x];
                int l = longRow[x];
                int sr8 = (s >>> 16) & 0xFF;
                int sg8 = (s >>> 8) & 0xFF;
                int sb8 = s & 0xFF;
                int lr8 = (l >>> 16) & 0xFF;
                int lg8 = (l >>> 8) & 0xFF;
                int lb8 = l & 0xFF;
                float sr = sr8 / 255.0f;
                float sg = sg8 / 255.0f;
                float sb = sb8 / 255.0f;
                float lr = lr8 / 255.0f;
                float lg = lg8 / 255.0f;
                float lb = lb8 / 255.0f;
                if (sr >= 0.06f && sr <= 0.75f && lr >= 0.08f && lr <= 0.92f) {
                    redRatios[redCount++] = SRGB_TO_LINEAR[lr8]
                            / Math.max(SRGB_TO_LINEAR[sr8], 0.000001f);
                }
                if (sg >= 0.06f && sg <= 0.75f && lg >= 0.08f && lg <= 0.92f) {
                    greenRatios[greenCount++] = SRGB_TO_LINEAR[lg8]
                            / Math.max(SRGB_TO_LINEAR[sg8], 0.000001f);
                }
                if (sb >= 0.06f && sb <= 0.75f && lb >= 0.08f && lb <= 0.92f) {
                    blueRatios[blueCount++] = SRGB_TO_LINEAR[lb8]
                            / Math.max(SRGB_TO_LINEAR[sb8], 0.000001f);
                }
            }
        }
        float fallback = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
        return new AppearanceGain(
                medianOrFallback(redRatios, redCount, fallback),
                medianOrFallback(greenRatios, greenCount, fallback),
                medianOrFallback(blueRatios, blueCount, fallback));
    }

    private static float medianOrFallback(float[] values, int count, float fallback) {
        if (count < 256) return fallback;
        Arrays.sort(values, 0, count);
        int middle = count / 2;
        float median = (count & 1) == 0
                ? 0.5f * (values[middle - 1] + values[middle])
                : values[middle];
        if (Float.isNaN(median) || Float.isInfinite(median) || median <= 0.0f) return fallback;
        return median;
    }

    private static float[] logLuma(int[] pixels) {
        float[] result = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float r = ((pixel >>> 16) & 0xFF) / 255.0f;
            float g = ((pixel >>> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            result[i] = (float) Math.log(0.02f + linearLuma(r, g, b));
        }
        return result;
    }

    private static void computeGradients(
            float[] source, int width, int height, float[] gx, float[] gy) {
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                gx[i] = 0.5f * (source[i + 1] - source[i - 1]);
                gy[i] = 0.5f * (source[i + width] - source[i - width]);
            }
        }
    }

    private static float gradientScore(
            float[] referenceGx, float[] referenceGy, float[] movingGx, float[] movingGy,
            int width, int height, int dx, int dy) {
        int border = 3 + Math.max(Math.abs(dx), Math.abs(dy));
        double dot = 0.0;
        double referenceEnergy = 0.0;
        double movingEnergy = 0.0;
        int useful = 0;
        for (int y = border; y < height - border; y += 2) {
            int movingY = y + dy;
            int referenceRow = y * width;
            int movingRow = movingY * width;
            for (int x = border; x < width - border; x += 2) {
                int movingX = x + dx;
                int ri = referenceRow + x;
                int mi = movingRow + movingX;
                float rgx = referenceGx[ri];
                float rgy = referenceGy[ri];
                float mgx = movingGx[mi];
                float mgy = movingGy[mi];
                float re = rgx * rgx + rgy * rgy;
                float me = mgx * mgx + mgy * mgy;
                if (re < 0.000004f || me < 0.000004f) continue;
                dot += rgx * mgx + rgy * mgy;
                referenceEnergy += re;
                movingEnergy += me;
                useful++;
            }
        }
        if (useful < 96 || referenceEnergy <= 0.0 || movingEnergy <= 0.0) return -1.0f;
        return (float) (dot / Math.sqrt(referenceEnergy * movingEnergy));
    }

    private static float parabolicOffset(
            float[] scores, int side, int radius, int bestX, int bestY, boolean horizontal) {
        int x = bestX + radius;
        int y = bestY + radius;
        if (horizontal && (x <= 0 || x >= side - 1)) return 0.0f;
        if (!horizontal && (y <= 0 || y >= side - 1)) return 0.0f;
        float minus = horizontal ? scores[y * side + x - 1] : scores[(y - 1) * side + x];
        float center = scores[y * side + x];
        float plus = horizontal ? scores[y * side + x + 1] : scores[(y + 1) * side + x];
        float denominator = minus - 2.0f * center + plus;
        if (Math.abs(denominator) < 0.000001f) return 0.0f;
        return clamp(0.5f * (minus - plus) / denominator, -0.75f, 0.75f);
    }

    static byte[] fuse(
            byte[] shortJpeg,
            byte[] longJpeg,
            double exposureRatio,
            float displayBrightnessEv,
            float displayGamma) throws Exception {
        Bitmap shortBitmap = decodeUpright(shortJpeg);
        Bitmap longBitmap = decodeUpright(longJpeg);
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

        Registration registration = estimateRegistration(longBitmap, shortBitmap);
        Bitmap alignedLong = alignLongToShort(longBitmap, registration);
        recycle(longBitmap);
        longBitmap = alignedLong;
        AppearanceGain appearanceGain = estimateAppearanceGain(shortBitmap, longBitmap, exposureRatio);
        RuntimeLogger.event(
                "CPU_STILL_REGISTRATION",
                String.format(java.util.Locale.US,
                        "sampleDx=%+.3f sampleDy=%+.3f score=%.4f margin=%.4f cycle=%.3f confidence=%.3f gain=%.3f/%.3f/%.3f",
                        registration.sampleDx, registration.sampleDy, registration.score,
                        registration.margin, registration.cycleError, registration.confidence,
                        appearanceGain.r, appearanceGain.g, appearanceGain.b));

        int width = shortBitmap.getWidth();
        int height = shortBitmap.getHeight();
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final int rowsPerStrip = 32;
        int[] shortPixels = new int[width * rowsPerStrip];
        int[] outPixels = new int[width * rowsPerStrip];

        // The CPU path is fail-closed strict provenance: after registration is used
        // only to estimate one achromatic global exposure scale, LONG is discarded.
        // No LONG pixel, edge, hue, texture, mask or ownership decision can enter
        // the output raster.
        float scalarAppearanceGain = secondLargest3(
                appearanceGain.r, appearanceGain.g, appearanceGain.b);
        recycle(longBitmap);
        longBitmap = null;

        float clampedBrightnessEv = clamp(displayBrightnessEv, -16.0f, 1.0f);
        float brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);
        float clampedGamma = clamp(displayGamma, 0.50f, 2.00f);
        float[] gammaLut = Math.abs(clampedGamma - 1.0f) < 0.0001f
                ? null
                : buildGammaLut(clampedGamma);
        float ratio = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
        float bracketStops = clamp(log2(Math.max(ratio, 1.0001f)), 1.0f, 6.0f);
        float whiteAnchor = clamp(0.82f - 0.04f * (bracketStops - 1.0f), 0.68f, 0.82f);
        float displayCeiling = clamp(whiteAnchor + 0.14f, 0.84f, 0.96f);
        float headroomLog2 = Math.max(log2(Math.max(ratio, 1.0001f)), 0.0001f);

        for (int y = 0; y < height; y += rowsPerStrip) {
            int rows = Math.min(rowsPerStrip, height - y);
            shortBitmap.getPixels(shortPixels, 0, width, 0, y, width, rows);

            for (int row = 0; row < rows; row++) {
                for (int x = 0; x < width; x++) {
                    int centerIndex = row * width + x;
                    int outIndex = row * width + x;
                    int s = shortPixels[centerIndex];

                    int sr8 = (s >>> 16) & 0xFF;
                    int sg8 = (s >>> 8) & 0xFF;
                    int sb8 = s & 0xFF;

                    // V2.15 strict fallback: even the dormant CPU path must not
                    // synthesize an RGB sample between LONG and SHORT. SHORT is the
                    // spatial/chromatic truth; LONG contributes only the robust
                    // scalar exposure estimate calculated above.
                    float mr = SRGB_TO_LINEAR[sr8] * scalarAppearanceGain;
                    float mg = SRGB_TO_LINEAR[sg8] * scalarAppearanceGain;
                    float mb = SRGB_TO_LINEAR[sb8] * scalarAppearanceGain;

                    // Presentation Brightness stays post-fusion. Then a restrained
                    // global photographic body curve anchors black, lifts body/mids,
                    // and fades completely before the 0.70 recovered-highlight knee.
                    float tr = mr * brightnessGain;
                    float tg = mg * brightnessGain;
                    float tb = mb * brightnessGain;
                    float bodyY = linearLuma(tr, tg, tb);
                    if (bodyY > 0.000001f) {
                        float toe = smoothstep(0.015f, 0.090f, bodyY);
                        float highlightProtect = 1.0f - smoothstep(0.45f, 0.68f, bodyY);
                        float targetBodyY = bodyY + 0.45f * toe * highlightProtect
                                * bodyY * (1.0f - clamp(bodyY, 0.0f, 1.0f));
                        float bodyScale = targetBodyY / bodyY;
                        float bodyPeak = Math.max(tr, Math.max(tg, tb));
                        bodyScale = Math.min(bodyScale, 1.0f / Math.max(bodyPeak, 0.000001f));
                        tr *= bodyScale;
                        tg *= bodyScale;
                        tb *= bodyScale;
                    }

                    float scenePeak = Math.max(tr, Math.max(tg, tb));
                    float mappedPeak = scenePeak;
                    if (scenePeak > HDR_KNEE) {
                        if (scenePeak <= 1.0f) {
                            float t = clamp((scenePeak - HDR_KNEE) / (1.0f - HDR_KNEE), 0.0f, 1.0f);
                            mappedPeak = HDR_KNEE + (whiteAnchor - HDR_KNEE) * t;
                        } else {
                            float t = clamp(log2(scenePeak) / headroomLog2, 0.0f, 1.0f);
                            mappedPeak = whiteAnchor + (displayCeiling - whiteAnchor) * t;
                        }
                    }
                    float toneScale = scenePeak > 0.000001f ? mappedPeak / scenePeak : 1.0f;
                    tr *= toneScale;
                    tg *= toneScale;
                    tb *= toneScale;

                    if (gammaLut != null) {
                        float gammaY = linearLuma(tr, tg, tb);
                        if (gammaY > 0.000001f) {
                            float mappedGammaY = mapLut(gammaY, gammaLut);
                            float requestedGammaScale = mappedGammaY / gammaY;
                            float gammaPeak = Math.max(tr, Math.max(tg, tb));
                            float gammaGamutScale = 1.0f / Math.max(gammaPeak, 0.000001f);
                            float gammaScale = Math.min(requestedGammaScale, gammaGamutScale);
                            tr *= gammaScale;
                            tg *= gammaScale;
                            tb *= gammaScale;
                        }
                    }

                    int ro = encode(tr);
                    int go = encode(tg);
                    int bo = encode(tb);
                    outPixels[outIndex] = 0xFF000000 | (ro << 16) | (go << 8) | bo;
                }
            }
            output.setPixels(outPixels, 0, width, 0, y, width, rows);
        }

        recycle(shortBitmap);
        recycle(longBitmap);

        byte[] encoded = encodeJpeg(output);
        output.recycle();
        return encoded;
    }

    static Bitmap decodeUpright(byte[] jpeg) throws Exception {
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (decoded == null) {
            throw new IllegalStateException("Unable to decode capture JPEG");
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (ByteArrayInputStream input = new ByteArrayInputStream(jpeg)) {
            ExifInterface exif = new ExifInterface(input);
            orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            // A HAL is allowed to rotate JPEG pixel data directly and omit an EXIF rotation tag.
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

    private static float linearLuma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float secondLargest3(float r, float g, float b) {
        float maximum = Math.max(r, Math.max(g, b));
        float minimum = Math.min(r, Math.min(g, b));
        return r + g + b - maximum - minimum;
    }

    private static float mapLut(float value, float[] lut) {
        float scaled = clamp(value, 0.0f, 1.0f) * (lut.length - 1);
        int lo = (int) scaled;
        int hi = Math.min(lut.length - 1, lo + 1);
        float t = scaled - lo;
        return lut[lo] + (lut[hi] - lut[lo]) * t;
    }

    private static float[] buildGammaLut(float gamma) {
        float[] lut = new float[4096];
        double exponent = 1.0 / gamma;
        for (int i = 0; i < lut.length; i++) {
            double linear = i / (double) (lut.length - 1);
            lut[i] = (float) Math.pow(linear, exponent);
        }
        return lut;
    }

    private static int encode(float linear) {
        int index = Math.max(0, Math.min(LINEAR_TO_SRGB.length - 1,
                Math.round(linear * (LINEAR_TO_SRGB.length - 1))));
        return LINEAR_TO_SRGB[index];
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / LOG_2);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
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

    private static int[] buildEncodeLut() {
        int[] lut = new int[4096];
        for (int i = 0; i < lut.length; i++) {
            double linear = i / (double) (lut.length - 1);
            double encoded = linear <= 0.0031308
                    ? 12.92 * linear
                    : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
            lut[i] = (int) Math.round(255.0 * encoded);
        }
        return lut;
    }

    static byte[] encodeJpeg(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // V2.15 preserves SHORT high-frequency truth through the fused raster; use
        // maximum Android JPEG quality so the final required JPEG re-encode does
        // not unnecessarily discard that retained detail.
        boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
        if (!ok) {
            throw new IllegalStateException("JPEG encoder rejected fused bitmap");
        }
        return bytes.toByteArray();
    }

    static void recycleBitmap(Bitmap bitmap) {
        recycle(bitmap);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
