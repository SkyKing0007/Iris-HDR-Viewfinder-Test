package com.skyking0007.irishdrviewfinder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

final class JpegFusion {
    private static final float[] SRGB_TO_LINEAR = buildLinearLut();
    private static final float[] ENCODED_TO_LINEAR = buildEncodedLinearLut();
    private static final int[] LINEAR_TO_SRGB = buildEncodeLut();
    private static final double LOG_2 = Math.log(2.0);
    private static final float HDR_KNEE = 0.70f;
    private static final float HDR_CLIP_END = 0.995f;

    private JpegFusion() {}

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

        int width = shortBitmap.getWidth();
        int height = shortBitmap.getHeight();
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final int rowsPerStrip = 32;
        final int ownershipRadius = 6;
        int maxReadRows = rowsPerStrip + 2 * ownershipRadius;
        int[] shortPixels = new int[width * maxReadRows];
        int[] longPixels = new int[width * maxReadRows];
        float[] ownershipEvidence = new float[width * maxReadRows];
        int[] outPixels = new int[width * rowsPerStrip];

        float clampedBrightnessEv = clamp(displayBrightnessEv, -16.0f, 1.0f);
        float brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);
        float clampedGamma = clamp(displayGamma, 0.50f, 2.00f);
        float[] gammaLut = Math.abs(clampedGamma - 1.0f) < 0.0001f
                ? null
                : buildGammaLut(clampedGamma);
        float ratio = (float) Math.max(1.0, Math.min(65_536.0, exposureRatio));
        float bracketStops = clamp(log2(Math.max(ratio, 1.0001f)), 1.0f, 6.0f);
        float[] shortLiftLut = buildShortProvenanceLiftLut(bracketStops);
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

            // V2.6 neighborhood values are scalar source-ownership evidence only.
            // RGB is never spatially filtered or filled: the final pixel always uses
            // the center captured LONG or center captured SHORT RGB sample.
            for (int i = 0; i < readCount; i++) {
                ownershipEvidence[i] = shortOwnershipEvidence(shortPixels[i], longPixels[i]);
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

                    float srEncoded = sr8 / 255.0f;
                    float sgEncoded = sg8 / 255.0f;
                    float sbEncoded = sb8 / 255.0f;
                    float shortEncodedY = linearLuma(srEncoded, sgEncoded, sbEncoded);
                    float shortEncodedPeak = Math.max(srEncoded, Math.max(sgEncoded, sbEncoded));
                    float centerSignal = smoothstep(0.08f, 0.14f, shortEncodedY);
                    float centerHeadroom = 1.0f - smoothstep(0.985f, 0.998f, shortEncodedPeak);
                    float neighborhoodEvidence = ownershipNeighborhood(
                            ownershipEvidence, width, height, readY, readRows,
                            x, globalY, ownershipRadius);
                    float shortOwnership = centerSignal * centerHeadroom
                            * smoothstep(0.28f, 0.58f, neighborhoodEvidence);

                    float liftedShortY = mapLut(shortEncodedY, shortLiftLut);
                    float shortScale = shortEncodedY > 0.000001f
                            ? liftedShortY / shortEncodedY
                            : 1.0f;
                    shortScale = Math.min(shortScale, 1.0f / Math.max(shortEncodedPeak, 0.000001f));
                    float sr = decodeEncoded(srEncoded * shortScale);
                    float sg = decodeEncoded(sgEncoded * shortScale);
                    float sb = decodeEncoded(sbEncoded * shortScale);
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

    private static float shortOwnershipEvidence(int shortPixel, int longPixel) {
        float sr = ((shortPixel >>> 16) & 0xFF) / 255.0f;
        float sg = ((shortPixel >>> 8) & 0xFF) / 255.0f;
        float sb = (shortPixel & 0xFF) / 255.0f;
        float lr = ((longPixel >>> 16) & 0xFF) / 255.0f;
        float lg = ((longPixel >>> 8) & 0xFF) / 255.0f;
        float lb = (longPixel & 0xFF) / 255.0f;
        float shortY = linearLuma(sr, sg, sb);
        float longY = linearLuma(lr, lg, lb);
        float shortPeak = Math.max(sr, Math.max(sg, sb));
        float longPeak = Math.max(lr, Math.max(lg, lb));
        float shortSignal = smoothstep(0.14f, 0.24f, shortY);
        float shortHeadroom = 1.0f - smoothstep(0.985f, 0.998f, shortPeak);
        float longDamage = Math.max(
                smoothstep(0.62f, 0.78f, longY),
                smoothstep(0.90f, 0.98f, longPeak));
        return shortSignal * shortHeadroom * longDamage;
    }

    private static float ownershipNeighborhood(
            float[] evidence,
            int width,
            int height,
            int readY,
            int readRows,
            int x,
            int globalY,
            int radius) {
        int x0 = Math.max(0, x - radius);
        int x1 = x;
        int x2 = Math.min(width - 1, x + radius);
        int y0 = Math.max(0, globalY - radius) - readY;
        int y1 = globalY - readY;
        int y2 = Math.min(height - 1, globalY + radius) - readY;
        y0 = Math.max(0, Math.min(readRows - 1, y0));
        y1 = Math.max(0, Math.min(readRows - 1, y1));
        y2 = Math.max(0, Math.min(readRows - 1, y2));
        return (
                evidence[y0 * width + x0] + evidence[y0 * width + x1] + evidence[y0 * width + x2]
                + evidence[y1 * width + x0] + evidence[y1 * width + x1] + evidence[y1 * width + x2]
                + evidence[y2 * width + x0] + evidence[y2 * width + x1] + evidence[y2 * width + x2]) / 9.0f;
    }

    private static float[] buildShortProvenanceLiftLut(float bracketStops) {
        float[] lut = new float[4096];
        double exponent = clamp(0.82f - 0.075f * (bracketStops - 1.0f), 0.58f, 0.82f);
        for (int i = 0; i < lut.length; i++) {
            double encoded = i / (double) (lut.length - 1);
            lut[i] = (float) Math.pow(encoded, exponent);
        }
        return lut;
    }

    private static float decodeEncoded(float encoded) {
        return mapLut(encoded, ENCODED_TO_LINEAR);
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

    private static float[] buildEncodedLinearLut() {
        float[] lut = new float[4096];
        for (int i = 0; i < lut.length; i++) {
            double encoded = i / (double) (lut.length - 1);
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
