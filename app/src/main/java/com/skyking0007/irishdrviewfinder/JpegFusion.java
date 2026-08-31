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
    private static final float[] APPEARANCE_TARGET_LINEAR = buildAppearanceTargetLinearLut();
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
        float[] highlightColor = new float[3];

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
                // Color ownership follows the same HDR reliability decision. SHORT is
                // evaluated for every pixel, but ordinary LONG-owned shadows/midtones
                // remain on the proven fused path. Only where normalized SHORT actually
                // participates in highlight recovery do we transition toward unscaled
                // source chromaticity.
                if (highlightWeight > 0.0005f) {
                    highlightColorFromSources(
                            fusedR, fusedG, fusedB,
                            sr8 / 255.0f, sg8 / 255.0f, sb8 / 255.0f,
                            lr8 / 255.0f, lg8 / 255.0f, lb8 / 255.0f,
                            longEncodedPeak, clipStart, highlightColor);
                    fusedR = highlightColor[0];
                    fusedG = highlightColor[1];
                    fusedB = highlightColor[2];
                }
                int ro = encodeSrgb(fusedR);
                int go = encodeSrgb(fusedG);
                int bo = encodeSrgb(fusedB);
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
        // Same V1.4.8 appearance curve as the live shader, with expensive exp/pow
        // precomputed once so full-resolution JPEG fusion only interpolates a LUT.
        float linearY = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        if (linearY <= 0.000001f) return 1.0f;
        float position = clamp(linearY, 0.0f, 1.0f) * (APPEARANCE_TARGET_LINEAR.length - 1);
        int lower = Math.max(0, Math.min(APPEARANCE_TARGET_LINEAR.length - 1, (int) position));
        int upper = Math.min(APPEARANCE_TARGET_LINEAR.length - 1, lower + 1);
        float fraction = position - lower;
        float targetLinearY = APPEARANCE_TARGET_LINEAR[lower]
                + (APPEARANCE_TARGET_LINEAR[upper] - APPEARANCE_TARGET_LINEAR[lower]) * fraction;
        float scale = targetLinearY / linearY;
        float peak = Math.max(r, Math.max(g, b));
        if (peak > 0.000001f) scale = Math.min(scale, 0.98f / peak);
        return scale;
    }

    private static float encodedLuma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static void highlightColorFromSources(
            float fusedR, float fusedG, float fusedB,
            float shortR, float shortG, float shortB,
            float longR, float longG, float longB,
            float longEncodedPeak,
            float clipStart,
            float[] out) {
        // HDR luminance has already been solved from both exposures. LONG retains its
        // proven color outside the highlight handoff. As LONG approaches saturation,
        // unscaled SHORT source RGB becomes the color authority before normalized RGB
        // can magnify tiny ISP chroma errors into orange/pink/green specks.
        float targetY = encodedLuma(fusedR, fusedG, fusedB);
        float shortPeak = Math.max(shortR, Math.max(shortG, shortB));
        float shortColorSignal = smoothstep(0.025f, 0.10f, shortPeak);
        float colorStart = Math.max(0.78f, clipStart - 0.15f);
        float colorNeed = smoothstep(colorStart, HDR_CLIP_END, longEncodedPeak)
                * shortColorSignal;
        if (colorNeed <= 0.0005f) {
            out[0] = fusedR; out[1] = fusedG; out[2] = fusedB;
            return;
        }

        float sourceR = longR + (shortR - longR) * colorNeed;
        float sourceG = longG + (shortG - longG) * colorNeed;
        float sourceB = longB + (shortB - longB) * colorNeed;
        float sourceY = encodedLuma(sourceR, sourceG, sourceB);
        if (sourceY <= 0.0001f) {
            out[0] = fusedR; out[1] = fusedG; out[2] = fusedB;
            return;
        }

        float sourceCr = sourceR - sourceY;
        float sourceCg = sourceG - sourceY;
        float sourceCb = sourceB - sourceY;
        float chromaSq = sourceCr * sourceCr + sourceCg * sourceCg + sourceCb * sourceCb;
        // Low/moderate source chroma stays at its actual encoded amplitude rather than
        // being multiplied by display gain. Strong genuine source color progressively
        // receives the full gain so vivid saturation is not globally suppressed.
        float strongColor = smoothstep(0.0144f, 0.0576f, chromaSq);
        float displayGain = Math.max(targetY / sourceY, 1.0f);
        float chromaGain = 1.0f + (displayGain - 1.0f) * strongColor;
        float chromaR = sourceCr * chromaGain;
        float chromaG = sourceCg * chromaGain;
        float chromaB = sourceCb * chromaGain;

        float gamutScale = 1.0f;
        gamutScale = gamutScaleForChannel(gamutScale, targetY, chromaR);
        gamutScale = gamutScaleForChannel(gamutScale, targetY, chromaG);
        gamutScale = gamutScaleForChannel(gamutScale, targetY, chromaB);
        gamutScale = clamp(gamutScale, 0.0f, 1.0f);

        out[0] = clamp(targetY + chromaR * gamutScale, 0.0f, 1.0f);
        out[1] = clamp(targetY + chromaG * gamutScale, 0.0f, 1.0f);
        out[2] = clamp(targetY + chromaB * gamutScale, 0.0f, 1.0f);
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

    private static float[] buildAppearanceTargetLinearLut() {
        float[] lut = new float[4096];
        for (int i = 0; i < lut.length; i++) {
            float linearY = i / (float) (lut.length - 1);
            float perceptualY = linearToSrgbFloat(linearY);
            float centered = (perceptualY - 0.20f) / 0.11f;
            float targetY = perceptualY
                    + 0.70f * perceptualY * (1.0f - perceptualY)
                            * (float) Math.exp(-(centered * centered));
            lut[i] = srgbToLinearFloat(clamp(targetY, 0.0f, 1.0f));
        }
        return lut;
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
