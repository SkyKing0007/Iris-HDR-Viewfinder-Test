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

    static Registration estimateRegistration(Bitmap shortBitmap, Bitmap longBitmap) {
        if (shortBitmap == null || longBitmap == null
                || shortBitmap.getWidth() != longBitmap.getWidth()
                || shortBitmap.getHeight() != longBitmap.getHeight()) {
            return new Registration(0.0f, 0.0f, -1.0f, 0.0f, 0.0f, Float.POSITIVE_INFINITY);
        }
        OneWayRegistration forward = estimateOneWayRegistration(shortBitmap, longBitmap);
        OneWayRegistration backward = estimateOneWayRegistration(longBitmap, shortBitmap);
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

    static Bitmap alignShortToLong(Bitmap shortBitmap, Registration registration) {
        Bitmap aligned = Bitmap.createBitmap(
                shortBitmap.getWidth(), shortBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(aligned);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Matrix matrix = new Matrix();
        // sampleDx/sampleDy specify the moving SHORT coordinate corresponding to a
        // LONG output coordinate. Draw by the opposite translation to register SHORT
        // into the unchanged LONG output geometry.
        matrix.postTranslate(-registration.sampleDx, -registration.sampleDy);
        canvas.drawBitmap(shortBitmap, matrix, paint);
        return aligned;
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

        Registration registration = estimateRegistration(shortBitmap, longBitmap);
        Bitmap alignedShort = alignShortToLong(shortBitmap, registration);
        recycle(shortBitmap);
        shortBitmap = alignedShort;
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
        final int ownershipRadius = 6;
        int maxReadRows = rowsPerStrip + 2 * ownershipRadius;
        int[] shortPixels = new int[width * maxReadRows];
        int[] longPixels = new int[width * maxReadRows];
        float[] supportEvidence = new float[width * maxReadRows];
        int[] outPixels = new int[width * rowsPerStrip];

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
            int readY = Math.max(0, y - ownershipRadius);
            int readBottom = Math.min(height, y + rows + ownershipRadius);
            int readRows = readBottom - readY;
            int readCount = width * readRows;
            shortBitmap.getPixels(shortPixels, 0, width, 0, readY, width, readRows);
            longBitmap.getPixels(longPixels, 0, width, 0, readY, width, readRows);

            // V2.7 support is scalar radiometric-coherence evidence only. RGB is
            // never spatially averaged or filled: the final pixel mixes only the
            // registered center SHORT and center LONG samples.
            for (int i = 0; i < readCount; i++) {
                supportEvidence[i] = shortSupportEvidence(
                        shortPixels[i], longPixels[i], appearanceGain);
            }

            for (int row = 0; row < rows; row++) {
                int globalY = y + row;
                int centerRow = globalY - readY;
                for (int x = 0; x < width; x++) {
                    int centerIndex = centerRow * width + x;
                    int outIndex = row * width + x;
                    int s = shortPixels[centerIndex];
                    int l = longPixels[centerIndex];

                    int sr8 = (s >>> 16) & 0xFF;
                    int sg8 = (s >>> 8) & 0xFF;
                    int sb8 = s & 0xFF;
                    int lr8 = (l >>> 16) & 0xFF;
                    int lg8 = (l >>> 8) & 0xFF;
                    int lb8 = l & 0xFF;

                    float context2 = cardinalSupportAverage(
                            supportEvidence, width, height, readY, readRows,
                            x, globalY, 2);
                    float context6 = cardinalSupportAverage(
                            supportEvidence, width, height, readY, readRows,
                            x, globalY, 6);
                    float shortOwnership = computeShortOwnership(
                            s, l, appearanceGain, context2, context6,
                            registration.confidence);
                    float shortCoreOwnership = computeShortCoreOwnership(
                            s, l, appearanceGain, context2, context6,
                            registration.confidence);
                    shortOwnership = Math.max(shortOwnership, shortCoreOwnership);

                    float scalarAppearanceGain = secondLargest3(
                            appearanceGain.r, appearanceGain.g, appearanceGain.b);
                    float srPerChannel = SRGB_TO_LINEAR[sr8] * appearanceGain.r;
                    float sgPerChannel = SRGB_TO_LINEAR[sg8] * appearanceGain.g;
                    float sbPerChannel = SRGB_TO_LINEAR[sb8] * appearanceGain.b;
                    float srScalar = SRGB_TO_LINEAR[sr8] * scalarAppearanceGain;
                    float sgScalar = SRGB_TO_LINEAR[sg8] * scalarAppearanceGain;
                    float sbScalar = SRGB_TO_LINEAR[sb8] * scalarAppearanceGain;
                    float sr = srPerChannel + (srScalar - srPerChannel) * shortCoreOwnership;
                    float sg = sgPerChannel + (sgScalar - sgPerChannel) * shortCoreOwnership;
                    float sb = sbPerChannel + (sbScalar - sbPerChannel) * shortCoreOwnership;
                    float lr = SRGB_TO_LINEAR[lr8];
                    float lg = SRGB_TO_LINEAR[lg8];
                    float lb = SRGB_TO_LINEAR[lb8];

                    float mr = lr + (sr - lr) * shortOwnership;
                    float mg = lg + (sg - lg) * shortOwnership;
                    float mb = lb + (sb - lb) * shortOwnership;

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

    private static void applyHighlightColorOwnership(
            float targetR, float targetG, float targetB,
            float longSceneR, float longSceneG, float longSceneB,
            float shortSceneR, float shortSceneG, float shortSceneB,
            float longEncodedR, float longEncodedG, float longEncodedB,
            float shortEncodedR, float shortEncodedG, float shortEncodedB,
            float highlightWeight,
            float[] out) {
        float targetY = linearLuma(targetR, targetG, targetB);
        float longY = linearLuma(longSceneR, longSceneG, longSceneB);
        if (targetY <= 0.000001f || longY <= 0.000001f) {
            out[0] = targetR; out[1] = targetG; out[2] = targetB;
            return;
        }

        float longScale = targetY / longY;
        float ownedR = longSceneR * longScale;
        float ownedG = longSceneG * longScale;
        float ownedB = longSceneB * longScale;

        float secondLong = secondLargest3(longEncodedR, longEncodedG, longEncodedB);
        float multiChannelClip = smoothstep(0.985f, 0.998f, secondLong);
        float shortPeak = Math.max(shortEncodedR, Math.max(shortEncodedG, shortEncodedB));
        float shortSignal = smoothstep(0.025f, 0.10f, shortPeak);
        float shortColorNeed = clamp(highlightWeight * multiChannelClip * shortSignal, 0.0f, 1.0f);

        float shortY = linearLuma(shortSceneR, shortSceneG, shortSceneB);
        if (shortColorNeed > 0.0005f && shortY > 0.000001f) {
            float shortScale = targetY / shortY;
            float shortR = shortSceneR * shortScale;
            float shortG = shortSceneG * shortScale;
            float shortB = shortSceneB * shortScale;
            ownedR += (shortR - ownedR) * shortColorNeed;
            ownedG += (shortG - ownedG) * shortColorNeed;
            ownedB += (shortB - ownedB) * shortColorNeed;
        }

        float ownedY = linearLuma(ownedR, ownedG, ownedB);
        float chromaR = ownedR - ownedY;
        float chromaG = ownedG - ownedY;
        float chromaB = ownedB - ownedY;
        float gamutScale = 1.0f;
        gamutScale = gamutScaleForChannel(gamutScale, targetY, chromaR);
        gamutScale = gamutScaleForChannel(gamutScale, targetY, chromaG);
        gamutScale = gamutScaleForChannel(gamutScale, targetY, chromaB);
        gamutScale = clamp(gamutScale, 0.0f, 1.0f);
        out[0] = clamp(targetY + chromaR * gamutScale, 0.0f, 1.0f);
        out[1] = clamp(targetY + chromaG * gamutScale, 0.0f, 1.0f);
        out[2] = clamp(targetY + chromaB * gamutScale, 0.0f, 1.0f);
    }

    private static float linearLuma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float secondLargest3(float r, float g, float b) {
        float maximum = Math.max(r, Math.max(g, b));
        float minimum = Math.min(r, Math.min(g, b));
        return r + g + b - maximum - minimum;
    }

    private static float gamutScaleForChannel(float current, float targetY, float chroma) {
        if (chroma > 0.000001f) return Math.min(current, (1.0f - targetY) / chroma);
        if (chroma < -0.000001f) return Math.min(current, targetY / (-chroma));
        return current;
    }

    private static float shortSupportEvidence(
            int shortPixel, int longPixel, AppearanceGain gain) {
        int sr8 = (shortPixel >>> 16) & 0xFF;
        int sg8 = (shortPixel >>> 8) & 0xFF;
        int sb8 = shortPixel & 0xFF;
        int lr8 = (longPixel >>> 16) & 0xFF;
        int lg8 = (longPixel >>> 8) & 0xFF;
        int lb8 = longPixel & 0xFF;
        float sr = sr8 / 255.0f;
        float sg = sg8 / 255.0f;
        float sb = sb8 / 255.0f;
        float lr = lr8 / 255.0f;
        float lg = lg8 / 255.0f;
        float lb = lb8 / 255.0f;
        float shortEncodedY = linearLuma(sr, sg, sb);
        float longEncodedY = linearLuma(lr, lg, lb);
        float shortPeak = Math.max(sr, Math.max(sg, sb));
        float secondLong = secondLargest3(lr, lg, lb);
        float longLinearY = linearLuma(
                SRGB_TO_LINEAR[lr8], SRGB_TO_LINEAR[lg8], SRGB_TO_LINEAR[lb8]);
        float mappedShortY = linearLuma(
                SRGB_TO_LINEAR[sr8] * gain.r,
                SRGB_TO_LINEAR[sg8] * gain.g,
                SRGB_TO_LINEAR[sb8] * gain.b);
        float radiometricRatio = mappedShortY / Math.max(longLinearY, 0.00001f);
        float shortSignal = smoothstep(0.06f, 0.14f, shortEncodedY);
        float shortHeadroom = 1.0f - smoothstep(0.965f, 0.995f, shortPeak);
        return shortSignal * shortHeadroom
                * smoothstep(0.45f, 0.68f, longEncodedY)
                * smoothstep(0.72f, 0.90f, secondLong)
                * smoothstep(1.10f, 1.34f, radiometricRatio);
    }

    private static float computeShortOwnership(
            int shortPixel,
            int longPixel,
            AppearanceGain gain,
            float context2,
            float context6,
            float registrationConfidence) {
        int sr8 = (shortPixel >>> 16) & 0xFF;
        int sg8 = (shortPixel >>> 8) & 0xFF;
        int sb8 = shortPixel & 0xFF;
        int lr8 = (longPixel >>> 16) & 0xFF;
        int lg8 = (longPixel >>> 8) & 0xFF;
        int lb8 = longPixel & 0xFF;
        float sr = sr8 / 255.0f;
        float sg = sg8 / 255.0f;
        float sb = sb8 / 255.0f;
        float lr = lr8 / 255.0f;
        float lg = lg8 / 255.0f;
        float lb = lb8 / 255.0f;
        float shortEncodedY = linearLuma(sr, sg, sb);
        float longEncodedY = linearLuma(lr, lg, lb);
        float shortPeak = Math.max(sr, Math.max(sg, sb));
        float secondLong = secondLargest3(lr, lg, lb);
        float longLinearY = linearLuma(
                SRGB_TO_LINEAR[lr8], SRGB_TO_LINEAR[lg8], SRGB_TO_LINEAR[lb8]);
        float mappedShortY = linearLuma(
                SRGB_TO_LINEAR[sr8] * gain.r,
                SRGB_TO_LINEAR[sg8] * gain.g,
                SRGB_TO_LINEAR[sb8] * gain.b);
        float radiometricRatio = mappedShortY / Math.max(longLinearY, 0.00001f);

        float shortSignal = smoothstep(0.06f, 0.14f, shortEncodedY);
        float shortHeadroom = 1.0f - smoothstep(0.965f, 0.995f, shortPeak);
        float twoChannel = smoothstep(0.78f, 0.94f, secondLong);
        float radiometric = smoothstep(0.50f, 0.74f, longEncodedY)
                * twoChannel
                * smoothstep(1.30f, 1.75f, radiometricRatio);
        float hard = smoothstep(0.975f, 0.997f, secondLong)
                * smoothstep(1.14f, 1.42f, radiometricRatio);
        float primary = Math.max(radiometric, hard);
        float coherence = (float) Math.sqrt(
                smoothstep(0.12f, 0.45f, context2)
                * smoothstep(0.10f, 0.40f, context6));
        float strong = hard * smoothstep(1.65f, 2.20f, radiometricRatio);
        float registrationGate = smoothstep(0.58f, 0.78f, registrationConfidence);
        return shortSignal * shortHeadroom * primary
                * Math.max(coherence, strong) * registrationGate;
    }

    private static float computeShortCoreOwnership(
            int shortPixel,
            int longPixel,
            AppearanceGain gain,
            float context2,
            float context6,
            float registrationConfidence) {
        int sr8 = (shortPixel >>> 16) & 0xFF;
        int sg8 = (shortPixel >>> 8) & 0xFF;
        int sb8 = shortPixel & 0xFF;
        int lr8 = (longPixel >>> 16) & 0xFF;
        int lg8 = (longPixel >>> 8) & 0xFF;
        int lb8 = longPixel & 0xFF;
        float sr = sr8 / 255.0f;
        float sg = sg8 / 255.0f;
        float sb = sb8 / 255.0f;
        float lr = lr8 / 255.0f;
        float lg = lg8 / 255.0f;
        float lb = lb8 / 255.0f;
        float shortEncodedY = linearLuma(sr, sg, sb);
        float longEncodedY = linearLuma(lr, lg, lb);
        float shortPeak = Math.max(sr, Math.max(sg, sb));
        float secondLong = secondLargest3(lr, lg, lb);
        float longLinearY = linearLuma(
                SRGB_TO_LINEAR[lr8], SRGB_TO_LINEAR[lg8], SRGB_TO_LINEAR[lb8]);
        float mappedShortY = linearLuma(
                SRGB_TO_LINEAR[sr8] * gain.r,
                SRGB_TO_LINEAR[sg8] * gain.g,
                SRGB_TO_LINEAR[sb8] * gain.b);
        float radiometricRatio = mappedShortY / Math.max(longLinearY, 0.00001f);

        float registrationGate = smoothstep(0.58f, 0.78f, registrationConfidence);
        float neighborhood = (float) Math.sqrt(
                smoothstep(0.08f, 0.30f, context2)
                * smoothstep(0.08f, 0.28f, context6));
        float veryStrongRadiometry = smoothstep(1.65f, 2.00f, radiometricRatio);
        float clippedCoreProof = smoothstep(0.985f, 0.996f, secondLong)
                * smoothstep(0.68f, 0.76f, longEncodedY)
                * smoothstep(0.08f, 0.14f, shortEncodedY)
                * (1.0f - smoothstep(0.94f, 0.975f, shortPeak))
                * smoothstep(1.14f, 1.28f, radiometricRatio)
                * Math.max(neighborhood, veryStrongRadiometry)
                * registrationGate;
        float featheredCore = smoothstep(0.45f, 0.82f, clippedCoreProof);

        boolean strictCore = registrationConfidence >= 0.78f
                && longEncodedY >= 0.70f
                && secondLong >= 0.992f
                && shortEncodedY >= 0.10f
                && shortPeak <= 0.94f
                && radiometricRatio >= 1.18f
                && ((context2 >= 0.10f && context6 >= 0.10f)
                || radiometricRatio >= 1.65f);
        return strictCore ? 1.0f : featheredCore;
    }

    private static float cardinalSupportAverage(
            float[] evidence,
            int width,
            int height,
            int readY,
            int readRows,
            int x,
            int globalY,
            int radius) {
        int xMinus = Math.max(0, x - radius);
        int xPlus = Math.min(width - 1, x + radius);
        int yMinus = Math.max(0, globalY - radius) - readY;
        int yPlus = Math.min(height - 1, globalY + radius) - readY;
        int yCenter = globalY - readY;
        yMinus = Math.max(0, Math.min(readRows - 1, yMinus));
        yPlus = Math.max(0, Math.min(readRows - 1, yPlus));
        yCenter = Math.max(0, Math.min(readRows - 1, yCenter));
        return 0.25f * (
                evidence[yCenter * width + xMinus]
                + evidence[yCenter * width + xPlus]
                + evidence[yMinus * width + x]
                + evidence[yPlus * width + x]);
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
        boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
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
