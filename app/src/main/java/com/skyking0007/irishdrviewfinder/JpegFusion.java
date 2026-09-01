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

    static byte[] fuse(
            byte[] shortJpeg,
            byte[] longJpeg,
            double exposureRatio,
            float displayBrightnessEv) throws Exception {
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
        float[] colorOwned = new float[3];

        float clampedBrightnessEv = clamp(displayBrightnessEv, -1.0f, 1.0f);
        float brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);
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

                // Brightness is a presentation exposure applied only after SHORT/LONG
                // fusion has finished. It cannot change capture, exposure ratio, or
                // highlight admission. V1.4.7 HDR mapping then spends only the
                // highlight headroom needed to keep that requested lift displayable.
                float boostedPeak = scenePeak * brightnessGain;
                float mappedPeak = boostedPeak;
                if (boostedPeak > HDR_KNEE) {
                    if (boostedPeak <= 1.0f) {
                        float t = clamp((boostedPeak - HDR_KNEE) / (1.0f - HDR_KNEE), 0.0f, 1.0f);
                        mappedPeak = HDR_KNEE + (whiteAnchor - HDR_KNEE) * t;
                    } else {
                        float t = clamp(log2(boostedPeak) / headroomLog2, 0.0f, 1.0f);
                        mappedPeak = whiteAnchor + (displayCeiling - whiteAnchor) * t;
                    }
                }

                float toneScale = boostedPeak > 0.000001f ? mappedPeak / boostedPeak : 1.0f;
                float tr = mr * brightnessGain * toneScale;
                float tg = mg * brightnessGain * toneScale;
                float tb = mb * brightnessGain * toneScale;

                // Preserve V1.4.7 HDR luminance while preventing normalized SHORT JPEG
                // chroma errors from creating red/orange/pink speckles. LONG owns color
                // throughout the handoff unless LONG has lost at least two highlight
                // channels and SHORT has usable signal. This is strictly pixel-local.
                if (highlightWeight > 0.0005f) {
                    applyHighlightColorOwnership(
                            tr, tg, tb,
                            lr, lg, lb,
                            sr, sg, sb,
                            lr8 / 255.0f, lg8 / 255.0f, lb8 / 255.0f,
                            sr8 / 255.0f, sg8 / 255.0f, sb8 / 255.0f,
                            highlightWeight, colorOwned);
                    tr = colorOwned[0];
                    tg = colorOwned[1];
                    tb = colorOwned[2];
                }

                int ro = encode(tr);
                int go = encode(tg);
                int bo = encode(tb);
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
