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
    private static final float HDR_TRUE_CLIP_START = 0.985f;
    private static final float HDR_TRUE_CLIP_END = 0.998f;
    private static final float HDR_WHITE_ANCHOR = 0.74f;
    private static final float HDR_DISPLAY_CEILING = 0.88f;
    private static final float HDR_TONE_REFERENCE_STOPS = 3.0f;

    private JpegFusion() {}

    static byte[] fuse(
            byte[] shortJpeg,
            byte[] longJpeg,
            double exposureRatio) throws Exception {
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

        float ratio = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));

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

                float longEncodedR = lr8 / 255.0f;
                float longEncodedG = lg8 / 255.0f;
                float longEncodedB = lb8 / 255.0f;
                float shortEncodedR = sr8 / 255.0f;
                float shortEncodedG = sg8 / 255.0f;
                float shortEncodedB = sb8 / 255.0f;

                // V1.4.13: SHORT no longer full-RGB blends into a valid LONG pixel.
                // LONG owns scene color and ordinary luminance. SHORT supplies only
                // genuinely missing highlight radiance after at least two LONG channels
                // approach clipping, and only when the normalized SHORT sample agrees
                // with the same bright side of the edge. This rejects the displaced
                // dark-leaf/bright-wall case without any neighborhood blur.
                recoverHighlightScene(
                        lr, lg, lb,
                        sr, sg, sb,
                        longEncodedR, longEncodedG, longEncodedB,
                        shortEncodedR, shortEncodedG, shortEncodedB,
                        colorOwned);
                float mr = colorOwned[0];
                float mg = colorOwned[1];
                float mb = colorOwned[2];

                float scenePeak = Math.max(mr, Math.max(mg, mb));

                // Tone ownership is fixed to the proven ~3 EV V1.4.7 display shape.
                // The physical exposure ratio remains necessary for SHORT normalization,
                // but changing Brightness must not move the tone knee/white/ceiling.
                float mappedPeak = scenePeak;
                if (scenePeak > HDR_KNEE) {
                    if (scenePeak <= 1.0f) {
                        float t = clamp((scenePeak - HDR_KNEE) / (1.0f - HDR_KNEE), 0.0f, 1.0f);
                        mappedPeak = HDR_KNEE + (HDR_WHITE_ANCHOR - HDR_KNEE) * t;
                    } else {
                        float t = clamp(log2(scenePeak) / HDR_TONE_REFERENCE_STOPS, 0.0f, 1.0f);
                        mappedPeak = HDR_WHITE_ANCHOR
                                + (HDR_DISPLAY_CEILING - HDR_WHITE_ANCHOR) * t;
                    }
                }

                float toneScale = scenePeak > 0.000001f ? mappedPeak / scenePeak : 1.0f;
                float tr = mr * toneScale;
                float tg = mg * toneScale;
                float tb = mb * toneScale;

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

    private static void recoverHighlightScene(
            float longSceneR, float longSceneG, float longSceneB,
            float shortSceneR, float shortSceneG, float shortSceneB,
            float longEncodedR, float longEncodedG, float longEncodedB,
            float shortEncodedR, float shortEncodedG, float shortEncodedB,
            float[] out) {
        float longY = linearLuma(longSceneR, longSceneG, longSceneB);
        float shortY = linearLuma(shortSceneR, shortSceneG, shortSceneB);
        if (longY <= 0.000001f || shortY <= 0.000001f) {
            out[0] = longSceneR;
            out[1] = longSceneG;
            out[2] = longSceneB;
            return;
        }

        float secondLong = secondLargest3(longEncodedR, longEncodedG, longEncodedB);
        float trueClip = smoothstep(HDR_TRUE_CLIP_START, HDR_TRUE_CLIP_END, secondLong);

        // After exposure normalization, the same bright scene point should not become
        // materially darker in SHORT. A low ratio is strong one-sided evidence that the
        // two JPEGs sampled opposite sides of an edge, so SHORT is rejected there.
        float shortAgreement = smoothstep(0.80f, 0.98f, shortY / longY);
        float recoveryWeight = trueClip * shortAgreement;
        float recoveredY = longY
                + (Math.max(longY, shortY) - longY) * recoveryWeight;

        float longScale = recoveredY / longY;
        float ownedR = longSceneR * longScale;
        float ownedG = longSceneG * longScale;
        float ownedB = longSceneB * longScale;

        // SHORT chromaticity is an emergency-only source after virtually complete
        // multi-channel LONG clipping. This keeps the V1.4.7 red/orange speckle source
        // out of ordinary walls, skin, shelf lighting and foliage boundaries.
        float extremeClip = smoothstep(0.997f, 0.9995f, secondLong);
        float shortSignal = smoothstep(0.025f, 0.10f,
                Math.max(shortEncodedR, Math.max(shortEncodedG, shortEncodedB)));
        float shortColorNeed = recoveryWeight * extremeClip * shortSignal;
        if (shortColorNeed > 0.0005f) {
            float shortScale = recoveredY / shortY;
            float shortOwnedR = shortSceneR * shortScale;
            float shortOwnedG = shortSceneG * shortScale;
            float shortOwnedB = shortSceneB * shortScale;
            ownedR += (shortOwnedR - ownedR) * shortColorNeed;
            ownedG += (shortOwnedG - ownedG) * shortColorNeed;
            ownedB += (shortOwnedB - ownedB) * shortColorNeed;
        }

        out[0] = Math.max(0.0f, ownedR);
        out[1] = Math.max(0.0f, ownedG);
        out[2] = Math.max(0.0f, ownedB);
    }

    private static float linearLuma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float secondLargest3(float r, float g, float b) {
        float maximum = Math.max(r, Math.max(g, b));
        float minimum = Math.min(r, Math.min(g, b));
        return r + g + b - maximum - minimum;
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
