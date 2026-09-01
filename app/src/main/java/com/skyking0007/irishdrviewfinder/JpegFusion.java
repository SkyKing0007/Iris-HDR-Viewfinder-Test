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
    private static final float LONG_CLIP_START = 0.985f;
    private static final float LONG_CLIP_END = 0.999f;
    private static final float SHORT_CLIP_START = 0.985f;
    private static final float SHORT_CLIP_END = 0.999f;
    private static final float HDR_KNEE = 0.90f;
    private static final float HDR_WHITE_ANCHOR = 0.965f;
    private static final float HDR_DISPLAY_CEILING = 0.995f;

    private JpegFusion() {}

    static byte[] fuse(byte[] shortJpeg, byte[] longJpeg, double exposureRatio) throws Exception {
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
        float calibration = calibrateShortToLong(shortBitmap, longBitmap, ratio);

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int rowsPerStrip = 32;
        int[] shortPixels = new int[width * rowsPerStrip];
        int[] longPixels = new int[width * rowsPerStrip];
        int[] outPixels = new int[width * rowsPerStrip];

        for (int y = 0; y < height; y += rowsPerStrip) {
            int rows = Math.min(rowsPerStrip, height - y);
            int count = width * rows;
            shortBitmap.getPixels(shortPixels, 0, width, 0, y, width, rows);
            longBitmap.getPixels(longPixels, 0, width, 0, y, width, rows);

            for (int i = 0; i < count; i++) {
                int s = shortPixels[i];
                int l = longPixels[i];

                int sr8 = (s >>> 16) & 0xFF;
                int sg8 = (s >>> 8) & 0xFF;
                int sb8 = s & 0xFF;
                int lr8 = (l >>> 16) & 0xFF;
                int lg8 = (l >>> 8) & 0xFF;
                int lb8 = l & 0xFF;

                float sr = SRGB_TO_LINEAR[sr8] * ratio * calibration;
                float sg = SRGB_TO_LINEAR[sg8] * ratio * calibration;
                float sb = SRGB_TO_LINEAR[sb8] * ratio * calibration;
                float lr = SRGB_TO_LINEAR[lr8];
                float lg = SRGB_TO_LINEAR[lg8];
                float lb = SRGB_TO_LINEAR[lb8];

                float longEncodedR = lr8 / 255.0f;
                float longEncodedG = lg8 / 255.0f;
                float longEncodedB = lb8 / 255.0f;
                float shortEncodedR = sr8 / 255.0f;
                float shortEncodedG = sg8 / 255.0f;
                float shortEncodedB = sb8 / 255.0f;
                float longLuma = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                float shortLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;
                float longSecond = secondLargest(longEncodedR, longEncodedG, longEncodedB);
                float longPeak = Math.max(longEncodedR, Math.max(longEncodedG, longEncodedB));
                float longHighlight = Math.max(
                        smoothstep(0.965f, 0.992f, longSecond),
                        smoothstep(0.62f, 0.78f, longLuma)
                                * smoothstep(0.985f, 0.998f, longPeak));
                float shortPeak = Math.max(shortEncodedR, Math.max(shortEncodedG, shortEncodedB));
                float shortSafe = 1.0f - smoothstep(0.965f, 0.985f, shortPeak);
                float shortScenePeak = Math.max(sr, Math.max(sg, sb));
                float longScenePeak = Math.max(lr, Math.max(lg, lb));
                float radianceEvidence = smoothstep(
                        1.04f, 1.14f, shortScenePeak / Math.max(longScenePeak, 0.0005f));
                float agreement = validChannelAgreement(
                        longEncodedR, longEncodedG, longEncodedB,
                        lr, lg, lb, sr, sg, sb);
                float weight = clamp(
                        longHighlight * shortSafe * radianceEvidence * agreement, 0.0f, 1.0f);

                float longSpread = Math.max(longEncodedR, Math.max(longEncodedG, longEncodedB))
                        - Math.min(longEncodedR, Math.min(longEncodedG, longEncodedB));
                float shortSpread = Math.max(shortEncodedR, Math.max(shortEncodedG, shortEncodedB))
                        - Math.min(shortEncodedR, Math.min(shortEncodedG, shortEncodedB));
                float neutralLongClip = (1.0f - smoothstep(0.015f, 0.060f, longSpread))
                        * smoothstep(0.985f, 0.999f, longSecond);
                float mildShortTint = 1.0f - smoothstep(0.10f, 0.25f, shortSpread);
                float neutralLock = neutralLongClip * mildShortTint;
                float trustedSr = sr + (shortLuma - sr) * neutralLock;
                float trustedSg = sg + (shortLuma - sg) * neutralLock;
                float trustedSb = sb + (shortLuma - sb) * neutralLock;
                float mr = lr + (trustedSr - lr) * weight;
                float mg = lg + (trustedSg - lg) * weight;
                float mb = lb + (trustedSb - lb) * weight;

                float scenePeak = Math.max(mr, Math.max(mg, mb));
                float mappedPeak = mappedPeak(scenePeak, ratio);
                float toneScale = scenePeak > 0.000001f ? mappedPeak / scenePeak : 1.0f;
                int ro = encode(mr * toneScale);
                int go = encode(mg * toneScale);
                int bo = encode(mb * toneScale);
                outPixels[i] = 0xFF000000 | (ro << 16) | (go << 8) | bo;
            }
            output.setPixels(outPixels, 0, width, 0, y, width, rows);
        }

        recycle(shortBitmap);
        recycle(longBitmap);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ok = output.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
        output.recycle();
        if (!ok) {
            throw new IllegalStateException("JPEG encoder rejected fused bitmap");
        }
        return bytes.toByteArray();
    }

    private static float calibrateShortToLong(
            Bitmap shortBitmap, Bitmap longBitmap, float ratio) {
        int width = shortBitmap.getWidth();
        int height = shortBitmap.getHeight();
        int step = Math.max(4, Math.min(width, height) / 192);
        int maxSamples = ((width + step - 1) / step) * ((height + step - 1) / step);
        float[] values = new float[maxSamples];
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
                float sl = (0.2126f * SRGB_TO_LINEAR[sr8]
                        + 0.7152f * SRGB_TO_LINEAR[sg8]
                        + 0.0722f * SRGB_TO_LINEAR[sb8]) * ratio;
                float ll = 0.2126f * SRGB_TO_LINEAR[lr8]
                        + 0.7152f * SRGB_TO_LINEAR[lg8]
                        + 0.0722f * SRGB_TO_LINEAR[lb8];
                if (sl <= 0.015f || ll <= 0.015f) continue;
                values[count++] = clamp(ll / sl, 0.75f, 1.33f);
            }
        }
        if (count < 24) return 1.0f;
        return medianPrefix(values, count);
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

    private static float mappedPeak(float scenePeak, float ratio) {
        if (scenePeak <= HDR_KNEE || scenePeak <= 0.000001f) return scenePeak;
        if (scenePeak <= 1.0f) {
            float t = clamp((scenePeak - HDR_KNEE) / (1.0f - HDR_KNEE), 0.0f, 1.0f);
            return HDR_KNEE + (HDR_WHITE_ANCHOR - HDR_KNEE) * t;
        }
        float headroomLog2 = Math.max(log2(Math.max(ratio, 1.0001f)), 0.0001f);
        float t = clamp(log2(scenePeak) / headroomLog2, 0.0f, 1.0f);
        return HDR_WHITE_ANCHOR + (HDR_DISPLAY_CEILING - HDR_WHITE_ANCHOR) * t;
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
