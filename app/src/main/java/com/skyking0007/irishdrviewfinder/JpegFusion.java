package com.skyking0007.irishdrviewfinder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

final class JpegFusion {
    private static final float[] SRGB_TO_LINEAR = buildLinearLut();
    private static final int[] LINEAR_TO_SRGB = buildEncodeLut();
    private static final double LOG_2 = Math.log(2.0);
    private static final float HDR_KNEE = 0.70f;
    private static final float HDR_CLIP_END = 0.995f;

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
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int rowsPerStrip = 32;
        int[] shortPixels = new int[width * rowsPerStrip];
        int[] longPixels = new int[width * rowsPerStrip];
        int[] outPixels = new int[width * rowsPerStrip];
        float[] colorSafe = new float[3];

        float ratio = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
        float bracketStops = clamp(log2(Math.max(ratio, 1.0001f)), 1.0f, 6.0f);
        float clipStart = clamp(0.90f + 0.01f * (bracketStops - 1.0f), 0.90f, 0.95f);
        float whiteAnchor = clamp(0.82f - 0.04f * (bracketStops - 1.0f), 0.68f, 0.82f);
        float displayCeiling = clamp(whiteAnchor + 0.14f, 0.84f, 0.96f);
        float headroomLog2 = Math.max(log2(Math.max(ratio, 1.0001f)), 0.0001f);

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

                float sr = SRGB_TO_LINEAR[sr8] * ratio;
                float sg = SRGB_TO_LINEAR[sg8] * ratio;
                float sb = SRGB_TO_LINEAR[sb8] * ratio;
                float lr = SRGB_TO_LINEAR[lr8];
                float lg = SRGB_TO_LINEAR[lg8];
                float lb = SRGB_TO_LINEAR[lb8];

                float longEncodedPeak = Math.max(lr8, Math.max(lg8, lb8)) / 255.0f;
                float longScenePeak = Math.max(0.000001f, Math.max(lr, Math.max(lg, lb)));
                float shortScenePeak = Math.max(sr, Math.max(sg, sb));
                float shortConfidence = smoothstep(
                        0.35f,
                        0.65f,
                        shortScenePeak / longScenePeak);
                float highlightWeight = smoothstep(
                        clipStart,
                        HDR_CLIP_END,
                        longEncodedPeak) * shortConfidence;

                // Full-RGB handoff: LONG remains the clean shadow/midtone owner and
                // normalized SHORT enters only near LONG saturation.
                float mr = lr + (sr - lr) * highlightWeight;
                float mg = lg + (sg - lg) * highlightWeight;
                float mb = lb + (sb - lb) * highlightWeight;

                float scenePeak = Math.max(mr, Math.max(mg, mb));
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

                // One scale for all channels preserves recovered highlight hue.
                float toneScale = scenePeak > 0.000001f ? mappedPeak / scenePeak : 1.0f;
                float tr = mr * toneScale;
                float tg = mg * toneScale;
                float tb = mb * toneScale;
                float appearanceScale = appearanceLiftScale(tr, tg, tb);
                float fusedR = encode(tr * appearanceScale) / 255.0f;
                float fusedG = encode(tg * appearanceScale) / 255.0f;
                float fusedB = encode(tb * appearanceScale) / 255.0f;
                colorSafeFromSources(
                        fusedR, fusedG, fusedB,
                        sr8 / 255.0f, sg8 / 255.0f, sb8 / 255.0f,
                        lr8 / 255.0f, lg8 / 255.0f, lb8 / 255.0f,
                        colorSafe);
                int ro = encodeSrgb(colorSafe[0]);
                int go = encodeSrgb(colorSafe[1]);
                int bo = encodeSrgb(colorSafe[2]);
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

    private static float appearanceLiftScale(float r, float g, float b) {
        // Mathematically matches the live shader. Fusion/highlight recovery is
        // already finished; this only lifts lower/mid-tone appearance while deep
        // shadows and recovered highlights converge toward the V1.4.7 result.
        float linearY = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        if (linearY <= 0.000001f) return 1.0f;
        float perceptualY = linearToSrgbFloat(clamp(linearY, 0.0f, 1.0f));
        float centered = (perceptualY - 0.20f) / 0.11f;
        float targetY = perceptualY
                + 0.70f * perceptualY * (1.0f - perceptualY)
                        * (float) Math.exp(-(centered * centered));
        targetY = clamp(targetY, 0.0f, 1.0f);
        float targetLinearY = srgbToLinearFloat(targetY);
        float scale = targetLinearY / linearY;
        float peak = Math.max(r, Math.max(g, b));
        if (peak > 0.000001f) scale = Math.min(scale, 0.98f / peak);
        return scale;
    }

    private static float encodedLuma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static void colorSafeFromSources(
            float fusedR, float fusedG, float fusedB,
            float shortR, float shortG, float shortB,
            float longR, float longG, float longB,
            float[] out) {
        // Mathematically matches the live shader. HDR luminance is already final;
        // only chroma is reconstructed from source-supported colors. No semantic
        // skin/white/device classification is used.
        float targetY = encodedLuma(fusedR, fusedG, fusedB);
        float shortY = encodedLuma(shortR, shortG, shortB);
        float longY = encodedLuma(longR, longG, longB);

        float fusedCr = fusedR - targetY;
        float fusedCg = fusedG - targetY;
        float fusedCb = fusedB - targetY;
        float shortCr = shortR - shortY;
        float shortCg = shortG - shortY;
        float shortCb = shortB - shortY;
        float longCr = longR - longY;
        float longCg = longG - longY;
        float longCb = longB - longY;

        float shortPeak = Math.max(shortR, Math.max(shortG, shortB));
        float shortSignal = smoothstep(0.03f, 0.12f, shortPeak);
        float shortMag = length3(shortCr, shortCg, shortCb);
        float longMag = length3(longCr, longCg, longCb);
        float strongColor = smoothstep(0.025f, 0.085f, shortMag);
        float displayGain = Math.max(targetY, 0.0001f) / Math.max(shortY, 0.0001f);
        float chromaExponent = 0.35f * (1.0f - strongColor);
        float shortChromaScale = (float) Math.pow(
                Math.max(displayGain, 1.0f),
                -chromaExponent);

        float supportedShortCr = shortCr * shortChromaScale;
        float supportedShortCg = shortCg * shortChromaScale;
        float supportedShortCb = shortCb * shortChromaScale;
        float supportedCr = longCr + (supportedShortCr - longCr) * shortSignal;
        float supportedCg = longCg + (supportedShortCg - longCg) * shortSignal;
        float supportedCb = longCb + (supportedShortCb - longCb) * shortSignal;

        float colorApply = smoothstep(0.18f, 0.48f, targetY);
        float outCr = fusedCr + (supportedCr - fusedCr) * colorApply;
        float outCg = fusedCg + (supportedCg - fusedCg) * colorApply;
        float outCb = fusedCb + (supportedCb - fusedCb) * colorApply;

        float outputMag = length3(outCr, outCg, outCb);
        float sourceMaxMag = Math.max(shortMag, longMag);
        if (outputMag > sourceMaxMag && outputMag > 0.000001f) {
            float sourceScale = sourceMaxMag / outputMag;
            outCr *= sourceScale;
            outCg *= sourceScale;
            outCb *= sourceScale;
        }

        float gamutScale = 1.0f;
        gamutScale = gamutScaleForChannel(gamutScale, targetY, outCr);
        gamutScale = gamutScaleForChannel(gamutScale, targetY, outCg);
        gamutScale = gamutScaleForChannel(gamutScale, targetY, outCb);
        gamutScale = clamp(gamutScale, 0.0f, 1.0f);

        out[0] = clamp(targetY + outCr * gamutScale, 0.0f, 1.0f);
        out[1] = clamp(targetY + outCg * gamutScale, 0.0f, 1.0f);
        out[2] = clamp(targetY + outCb * gamutScale, 0.0f, 1.0f);
    }

    private static float gamutScaleForChannel(float current, float targetY, float chroma) {
        if (chroma > 0.000001f) {
            return Math.min(current, (1.0f - targetY) / chroma);
        }
        if (chroma < -0.000001f) {
            return Math.min(current, targetY / (-chroma));
        }
        return current;
    }

    private static float length3(float r, float g, float b) {
        return (float) Math.sqrt(r * r + g * g + b * b);
    }

    private static int encodeSrgb(float encoded) {
        return Math.round(255.0f * clamp(encoded, 0.0f, 1.0f));
    }

    private static float srgbToLinearFloat(float encoded) {
        return encoded <= 0.04045f
                ? encoded / 12.92f
                : (float) Math.pow((encoded + 0.055f) / 1.055f, 2.4);
    }

    private static float linearToSrgbFloat(float linear) {
        float value = Math.max(0.0f, linear);
        return value <= 0.0031308f
                ? 12.92f * value
                : 1.055f * (float) Math.pow(value, 1.0 / 2.4) - 0.055f;
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

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
