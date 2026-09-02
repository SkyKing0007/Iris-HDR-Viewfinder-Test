package com.skyking0007.irishdrviewfinder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class JpegFusion {
    private static final float[] SRGB_TO_LINEAR = buildLinearLut();
    private static final int[] LINEAR_TO_SRGB = buildEncodeLut();
    private static final double LOG_2 = Math.log(2.0);
    private static final float RECOVERY_KNEE = 0.72f;
    private static final float RECOVERY_WHITE_ANCHOR = 0.90f;
    private static final float RECOVERY_DISPLAY_CEILING = 0.995f;
    static final int RELIABILITY_WIDTH = 32;
    static final int RELIABILITY_HEIGHT = 24;
    static final int RELIABILITY_CHANNELS = 2;
    static final int RELIABILITY_MAP_BYTES = RELIABILITY_WIDTH * RELIABILITY_HEIGHT * RELIABILITY_CHANNELS;
    private static final float DEFAULT_LUMA_RELIABILITY = 224.0f / 255.0f;
    private static final float DEFAULT_CHROMA_RELIABILITY = 128.0f / 255.0f;
    private static final int PHOTO_KNOT_COUNT = 5;
    private static final float[] PHOTO_LUMA_KNOTS = {0.020f, 0.060f, 0.150f, 0.350f, 0.700f};
    private static final float PHOTO_SCALE_MIN = 0.60f;
    private static final float PHOTO_SCALE_MAX = 1.50f;
    private static final int PHOTO_MIN_BIN_SAMPLES = 24;
    private static final int[] FUSION_DX = {1, -1, 0, 0};
    private static final int[] FUSION_DY = {0, 0, 1, -1};

    private JpegFusion() {}

    static byte[] fuse(
            byte[] shortJpeg, byte[] longJpeg, double exposureRatio,
            byte[] shortReliabilityMap) throws Exception {
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

        int width = shortBitmap.getWidth();
        int height = shortBitmap.getHeight();
        float ratio = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
        PhotoCurve photoCurve = learnPhotoCurve(shortBitmap, longBitmap, ratio);
        int fusionRadius = Math.max(1, Math.min(4, Math.round(Math.min(width, height) / 720.0f)));
        int halo = fusionRadius;

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int rowsPerStrip = 32;
        int maxExpandedRows = rowsPerStrip + halo * 2;
        int maxExpandedPixels = width * maxExpandedRows;
        int[] shortPixels = new int[maxExpandedPixels];
        int[] longPixels = new int[maxExpandedPixels];
        int[] outPixels = new int[width * rowsPerStrip];
        float[] longR = new float[maxExpandedPixels];
        float[] longG = new float[maxExpandedPixels];
        float[] longB = new float[maxExpandedPixels];
        float[] shortR = new float[maxExpandedPixels];
        float[] shortG = new float[maxExpandedPixels];
        float[] shortB = new float[maxExpandedPixels];
        float[] rawMask = new float[maxExpandedPixels];
        float[] coreMask = new float[maxExpandedPixels];
        float[] damageSupport = new float[maxExpandedPixels];
        float[] guideLuma = new float[maxExpandedPixels];

        for (int y = 0; y < height; y += rowsPerStrip) {
            int rows = Math.min(rowsPerStrip, height - y);
            int expandedStart = Math.max(0, y - halo);
            int expandedEnd = Math.min(height, y + rows + halo);
            int expandedRows = expandedEnd - expandedStart;
            int expandedCount = width * expandedRows;
            shortBitmap.getPixels(shortPixels, 0, width, 0, expandedStart, width, expandedRows);
            longBitmap.getPixels(longPixels, 0, width, 0, expandedStart, width, expandedRows);

            for (int localY = 0; localY < expandedRows; localY++) {
                int imageY = expandedStart + localY;
                int rowBase = localY * width;
                for (int imageX = 0; imageX < width; imageX++) {
                    int i = rowBase + imageX;
                    int sp = shortPixels[i];
                    int lp = longPixels[i];
                    int sr8 = (sp >>> 16) & 0xFF;
                    int sg8 = (sp >>> 8) & 0xFF;
                    int sb8 = sp & 0xFF;
                    int lr8 = (lp >>> 16) & 0xFF;
                    int lg8 = (lp >>> 8) & 0xFF;
                    int lb8 = lp & 0xFF;

                    float lr = SRGB_TO_LINEAR[lr8];
                    float lg = SRGB_TO_LINEAR[lg8];
                    float lb = SRGB_TO_LINEAR[lb8];
                    float srBase = SRGB_TO_LINEAR[sr8] * ratio;
                    float sgBase = SRGB_TO_LINEAR[sg8] * ratio;
                    float sbBase = SRGB_TO_LINEAR[sb8] * ratio;
                    float shortBaseLuma = 0.2126f * srBase + 0.7152f * sgBase + 0.0722f * sbBase;
                    float photoScale = photoScaleForLuma(photoCurve.relativeScale, shortBaseLuma);
                    float sr = srBase * photoScale;
                    float sg = sgBase * photoScale;
                    float sb = sbBase * photoScale;
                    float ll = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                    float sl = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;

                    float longEncodedR = lr8 / 255.0f;
                    float longEncodedG = lg8 / 255.0f;
                    float longEncodedB = lb8 / 255.0f;
                    float shortEncodedR = sr8 / 255.0f;
                    float shortEncodedG = sg8 / 255.0f;
                    float shortEncodedB = sb8 / 255.0f;
                    float longSecond = secondLargest(longEncodedR, longEncodedG, longEncodedB);
                    float longPeak = Math.max(longEncodedR, Math.max(longEncodedG, longEncodedB));
                    float highlightNeed = Math.max(
                            smoothstep(0.925f, 0.985f, longSecond),
                            smoothstep(0.970f, 0.997f, longPeak) * smoothstep(0.55f, 0.82f, ll));
                    float clippedCore = Math.max(
                            smoothstep(0.980f, 0.990f, longSecond),
                            smoothstep(0.992f, 0.998f, longPeak) * smoothstep(0.50f, 0.78f, ll));

                    float shortSecond = secondLargest(shortEncodedR, shortEncodedG, shortEncodedB);
                    float shortPeak = Math.max(shortEncodedR, Math.max(shortEncodedG, shortEncodedB));
                    float shortEncodedLuma = 0.2126f * shortEncodedR
                            + 0.7152f * shortEncodedG + 0.0722f * shortEncodedB;
                    float lumaSafe = 1.0f - smoothstep(0.975f, 0.997f, shortSecond);
                    float signalSafe = smoothstep(0.008f, 0.025f, shortEncodedLuma);
                    float shortUsable = Math.min(lumaSafe, signalSafe);
                    float currentCoreMask = clippedCore * smoothstep(0.25f, 0.55f, shortUsable);
                    float shortScenePeak = Math.max(sr, Math.max(sg, sb));
                    float longScenePeak = Math.max(lr, Math.max(lg, lb));
                    float radianceEvidence = smoothstep(
                            1.01f, 1.10f, shortScenePeak / Math.max(longScenePeak, 0.0005f));
                    float agreement = validChannelAgreement(
                            longEncodedR, longEncodedG, longEncodedB,
                            lr, lg, lb, sr, sg, sb);
                    // V1.4.19: current captured pair owns all visible luma fusion.
                    // Frozen preview history cannot create a 5-Hz luma seam/pulse.
                    float shoulderRaw = highlightNeed * lumaSafe * signalSafe
                            * radianceEvidence * agreement;
                    float currentShoulderMask = smoothstep(0.04f, 0.58f, shoulderRaw)
                            * (1.0f - clippedCore);
                    rawMask[i] = Math.max(currentCoreMask, currentShoulderMask);
                    coreMask[i] = currentCoreMask;
                    damageSupport[i] = Math.max(
                            currentCoreMask,
                            smoothstep(0.02f, 0.50f, highlightNeed * lumaSafe * signalSafe));
                    guideLuma[i] = ll;

                    // Temporal history remains chroma-only. This preserves the proven
                    // anti-pink protection without modulating recovered brightness.
                    float chromaReliability = sampleReliability(
                            shortReliabilityMap, imageX, imageY, width, height, 1);
                    float rgbSafe = 1.0f - smoothstep(0.955f, 0.985f, shortPeak);
                    float colorTrust = clamp(chromaReliability * rgbSafe * agreement, 0.0f, 1.0f);
                    float longSpread = Math.max(longEncodedR, Math.max(longEncodedG, longEncodedB))
                            - Math.min(longEncodedR, Math.min(longEncodedG, longEncodedB));
                    float shortSpread = Math.max(shortEncodedR, Math.max(shortEncodedG, shortEncodedB))
                            - Math.min(shortEncodedR, Math.min(shortEncodedG, shortEncodedB));
                    float neutralLongClip = (1.0f - smoothstep(0.015f, 0.060f, longSpread))
                            * smoothstep(0.975f, 0.998f, longSecond);
                    float mildShortTint = 1.0f - smoothstep(0.10f, 0.25f, shortSpread);
                    colorTrust *= 1.0f - neutralLongClip * mildShortTint;

                    float chromaScale = sl / Math.max(ll, 0.0005f);
                    float trustedSr = lr * chromaScale + (sr - lr * chromaScale) * colorTrust;
                    float trustedSg = lg * chromaScale + (sg - lg * chromaScale) * colorTrust;
                    float trustedSb = lb * chromaScale + (sb - lb * chromaScale) * colorTrust;
                    float trustedPeak = Math.max(trustedSr, Math.max(trustedSg, trustedSb));
                    float mappedPeak = mappedRecoveryPeak(trustedPeak, ratio);
                    float toneScale = trustedPeak > 0.000001f ? mappedPeak / trustedPeak : 1.0f;
                    longR[i] = lr;
                    longG[i] = lg;
                    longB[i] = lb;
                    shortR[i] = trustedSr * toneScale;
                    shortG[i] = trustedSg * toneScale;
                    shortB[i] = trustedSb * toneScale;
                }
            }

            for (int row = 0; row < rows; row++) {
                int imageY = y + row;
                int localY = imageY - expandedStart;
                int outBase = row * width;
                for (int imageX = 0; imageX < width; imageX++) {
                    int center = localY * width + imageX;
                    float centerGuide = guideLuma[center];
                    float longAccR = longR[center] * 4.0f;
                    float longAccG = longG[center] * 4.0f;
                    float longAccB = longB[center] * 4.0f;
                    float shortAccR = shortR[center] * 4.0f;
                    float shortAccG = shortG[center] * 4.0f;
                    float shortAccB = shortB[center] * 4.0f;
                    float fineMaskAcc = rawMask[center] * 4.0f;
                    float fineWeight = 4.0f;
                    for (int n = 0; n < 4; n++) {
                        int nx = clampInt(imageX + FUSION_DX[n] * fusionRadius, 0, width - 1);
                        int ny = clampInt(imageY + FUSION_DY[n] * fusionRadius, 0, height - 1);
                        int ni = (ny - expandedStart) * width + nx;
                        float weight = edgeWeight(centerGuide, guideLuma[ni]);
                        longAccR += longR[ni] * weight;
                        longAccG += longG[ni] * weight;
                        longAccB += longB[ni] * weight;
                        shortAccR += shortR[ni] * weight;
                        shortAccG += shortG[ni] * weight;
                        shortAccB += shortB[ni] * weight;
                        fineMaskAcc += rawMask[ni] * weight;
                        fineWeight += weight;
                    }
                    float longLowR = longAccR / fineWeight;
                    float longLowG = longAccG / fineWeight;
                    float longLowB = longAccB / fineWeight;
                    float shortLowR = shortAccR / fineWeight;
                    float shortLowG = shortAccG / fineWeight;
                    float shortLowB = shortAccB / fineWeight;
                    float blurredMask = Math.min(
                            damageSupport[center], fineMaskAcc / fineWeight);
                    float coarseMask = Math.max(coreMask[center], blurredMask);
                    float fineMask = Math.max(
                            coreMask[center],
                            Math.min(
                                    damageSupport[center],
                                    rawMask[center] + (blurredMask - rawMask[center]) * 0.50f));

                    // One-level Laplacian fusion. Smooth exposure/color transitions use
                    // the broader mask; fine structure follows the tighter edge-guided
                    // mask, avoiding source-switch ringing at foliage/branches/text.
                    float mr = longLowR + (shortLowR - longLowR) * coarseMask
                            + (longR[center] - longLowR) * (1.0f - fineMask)
                            + (shortR[center] - shortLowR) * fineMask;
                    float mg = longLowG + (shortLowG - longLowG) * coarseMask
                            + (longG[center] - longLowG) * (1.0f - fineMask)
                            + (shortG[center] - shortLowG) * fineMask;
                    float mb = longLowB + (shortLowB - longLowB) * coarseMask
                            + (longB[center] - longLowB) * (1.0f - fineMask)
                            + (shortB[center] - shortLowB) * fineMask;
                    int ro = encode(Math.max(0.0f, mr));
                    int go = encode(Math.max(0.0f, mg));
                    int bo = encode(Math.max(0.0f, mb));
                    outPixels[outBase + imageX] = 0xFF000000 | (ro << 16) | (go << 8) | bo;
                }
            }
            output.setPixels(outPixels, 0, width, 0, y, width, rows);
        }

        recycle(shortBitmap);
        recycle(longBitmap);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ok = output.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
        output.recycle();
        if (!ok) throw new IllegalStateException("JPEG encoder rejected fused bitmap");
        return bytes.toByteArray();
    }

    private static float sampleReliability(
            byte[] map, int imageX, int imageY, int imageWidth, int imageHeight, int channel) {
        if (map == null || map.length != RELIABILITY_MAP_BYTES
                || imageWidth <= 0 || imageHeight <= 0) {
            return channel == 0 ? DEFAULT_LUMA_RELIABILITY : DEFAULT_CHROMA_RELIABILITY;
        }
        // Match normalized GL_LINEAR sampling at full-resolution pixel centers:
        // texel coordinate = uv * textureSize - 0.5, clamped at the texture edge.
        float gx = imageWidth <= 1 ? 0.0f
                : ((imageX + 0.5f) * RELIABILITY_WIDTH / imageWidth) - 0.5f;
        float gy = imageHeight <= 1 ? 0.0f
                : ((imageY + 0.5f) * RELIABILITY_HEIGHT / imageHeight) - 0.5f;
        gx = clamp(gx, 0.0f, RELIABILITY_WIDTH - 1.0f);
        gy = clamp(gy, 0.0f, RELIABILITY_HEIGHT - 1.0f);
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        int x1 = Math.min(RELIABILITY_WIDTH - 1, x0 + 1);
        int y1 = Math.min(RELIABILITY_HEIGHT - 1, y0 + 1);
        float tx = gx - x0;
        float ty = gy - y0;
        float a = reliabilityAt(map, x0, y0, channel);
        float b = reliabilityAt(map, x1, y0, channel);
        float c = reliabilityAt(map, x0, y1, channel);
        float d = reliabilityAt(map, x1, y1, channel);
        float top = a + (b - a) * tx;
        float bottom = c + (d - c) * tx;
        return top + (bottom - top) * ty;
    }

    private static float reliabilityAt(byte[] map, int x, int y, int channel) {
        int index = (y * RELIABILITY_WIDTH + x) * RELIABILITY_CHANNELS + channel;
        return (map[index] & 0xFF) / 255.0f;
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
        if (count < 24) return new PhotoCurve(1.0f, new float[]{1f, 1f, 1f, 1f, 1f});
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
        smooth[0] = clamp(0.75f * target[0] + 0.25f * target[1],
                PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        for (int bin = 1; bin < PHOTO_KNOT_COUNT - 1; bin++) {
            smooth[bin] = clamp(
                    0.25f * target[bin - 1] + 0.50f * target[bin] + 0.25f * target[bin + 1],
                    PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        }
        smooth[PHOTO_KNOT_COUNT - 1] = clamp(
                0.25f * target[PHOTO_KNOT_COUNT - 2]
                        + 0.75f * target[PHOTO_KNOT_COUNT - 1],
                PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        enforceMonotonicPhotoCurve(smooth);
        return new PhotoCurve(calibration, smooth);
    }

    private static void enforceMonotonicPhotoCurve(float[] scale) {
        for (int bin = 1; bin < PHOTO_KNOT_COUNT; bin++) {
            float previousOutput = PHOTO_LUMA_KNOTS[bin - 1] * scale[bin - 1];
            float minimumScale = previousOutput * 1.01f / PHOTO_LUMA_KNOTS[bin];
            scale[bin] = clamp(Math.max(scale[bin], minimumScale), PHOTO_SCALE_MIN, PHOTO_SCALE_MAX);
        }
    }

    private static int photoBinForLuma(float luma) {
        for (int bin = 0; bin < PHOTO_KNOT_COUNT - 1; bin++) {
            float boundary = (float) Math.sqrt(PHOTO_LUMA_KNOTS[bin] * PHOTO_LUMA_KNOTS[bin + 1]);
            if (luma < boundary) return bin;
        }
        return PHOTO_KNOT_COUNT - 1;
    }

    private static float photoScaleForLuma(float[] scale, float normalizedShortLuma) {
        float luma = Math.max(0.00001f, normalizedShortLuma);
        if (luma <= PHOTO_LUMA_KNOTS[0]) return scale[0];
        for (int i = 0; i < PHOTO_KNOT_COUNT - 1; i++) {
            float low = PHOTO_LUMA_KNOTS[i];
            float high = PHOTO_LUMA_KNOTS[i + 1];
            if (luma <= high) {
                float t = (float) (Math.log(luma / low) / Math.log(high / low));
                return scale[i] + (scale[i + 1] - scale[i]) * clamp(t, 0.0f, 1.0f);
            }
        }
        return scale[PHOTO_KNOT_COUNT - 1];
    }

    private static float edgeWeight(float centerLuma, float neighborLuma) {
        float deltaEv = Math.abs(log2(Math.max(neighborLuma, 0.0001f)
                / Math.max(centerLuma, 0.0001f)));
        return 1.0f - smoothstep(0.18f, 0.70f, deltaEv);
    }

    private static int clampInt(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final class PhotoCurve {
        final float calibration;
        final float[] relativeScale;

        PhotoCurve(float calibration, float[] relativeScale) {
            this.calibration = calibration;
            this.relativeScale = relativeScale;
        }
    }

    private static boolean overlapEncoded(
            int sr, int sg, int sb, int lr, int lg, int lb) {
        return sr >= 4 && sg >= 4 && sb >= 4 && sr <= 230 && sg <= 230 && sb <= 230
                && lr >= 20 && lg >= 20 && lb >= 20 && lr <= 230 && lg <= 230 && lb <= 230;
    }

    private static float validChannelAgreement(
            float lr8, float lg8, float lb8,
            float lr, float lg, float lb,
            float sr, float sg, float sb) {
        float sum = 0.0f;
        int count = 0;
        if (lr8 < 0.94f && lr > 0.0005f && sr > 0.0005f) {
            sum += Math.abs(log2(sr / lr));
            count++;
        }
        if (lg8 < 0.94f && lg > 0.0005f && sg > 0.0005f) {
            sum += Math.abs(log2(sg / lg));
            count++;
        }
        if (lb8 < 0.94f && lb > 0.0005f && sb > 0.0005f) {
            sum += Math.abs(log2(sb / lb));
            count++;
        }
        if (count == 0) return 1.0f;
        float disagreement = sum / count;
        return 1.0f - smoothstep(0.16f, 0.55f, disagreement);
    }

    private static float secondLargest(float a, float b, float c) {
        return a + b + c - Math.min(a, Math.min(b, c)) - Math.max(a, Math.max(b, c));
    }

    private static float mappedRecoveryPeak(float scenePeak, float ratio) {
        if (scenePeak <= RECOVERY_KNEE || scenePeak <= 0.000001f) return scenePeak;
        if (scenePeak <= 1.0f) {
            float t = clamp((scenePeak - RECOVERY_KNEE) / (1.0f - RECOVERY_KNEE), 0.0f, 1.0f);
            return RECOVERY_KNEE + (RECOVERY_WHITE_ANCHOR - RECOVERY_KNEE) * t;
        }
        float headroomLog2 = Math.max(log2(Math.max(ratio, 1.0001f)), 0.0001f);
        float t = clamp(log2(scenePeak) / headroomLog2, 0.0f, 1.0f);
        return RECOVERY_WHITE_ANCHOR
                + (RECOVERY_DISPLAY_CEILING - RECOVERY_WHITE_ANCHOR) * t;
    }

    private static Bitmap decodeUpright(byte[] jpeg) throws Exception {
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

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

}
