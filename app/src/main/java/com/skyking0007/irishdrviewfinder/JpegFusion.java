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
        float ratio = (float) Math.max(1.0, Math.min(16.0, exposureRatio));

        for (int y = 0; y < height; y += rowsPerStrip) {
            int rows = Math.min(rowsPerStrip, height - y);
            int count = width * rows;
            shortBitmap.getPixels(shortPixels, 0, width, 0, y, width, rows);
            longBitmap.getPixels(longPixels, 0, width, 0, y, width, rows);

            for (int i = 0; i < count; i++) {
                int s = shortPixels[i];
                int l = longPixels[i];
                float lr8 = (l >>> 16) & 0xFF;
                float lg8 = (l >>> 8) & 0xFF;
                float lb8 = l & 0xFF;
                float clip = Math.max(lr8, Math.max(lg8, lb8)) / 255.0f;
                float w = smoothstep(0.62f, 0.93f, clip);

                float r = fuseChannel((s >>> 16) & 0xFF, (l >>> 16) & 0xFF, ratio, w);
                float g = fuseChannel((s >>> 8) & 0xFF, (l >>> 8) & 0xFF, ratio, w);
                float b = fuseChannel(s & 0xFF, l & 0xFF, ratio, w);

                int ro = encode(r);
                int go = encode(g);
                int bo = encode(b);
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

    private static float fuseChannel(int short8, int long8, float ratio, float highlightWeight) {
        float shortLinear = SRGB_TO_LINEAR[short8] * ratio;
        float longLinear = SRGB_TO_LINEAR[long8];
        float merged = longLinear * (1.0f - highlightWeight) + shortLinear * highlightWeight;
        float tone = (1.15f * merged) / (1.0f + 0.15f * merged);
        return Math.max(0.0f, Math.min(1.0f, tone));
    }

    private static int encode(float linear) {
        int index = Math.max(0, Math.min(LINEAR_TO_SRGB.length - 1,
                Math.round(linear * (LINEAR_TO_SRGB.length - 1))));
        return LINEAR_TO_SRGB[index];
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float[] buildLinearLut() {
        float[] lut = new float[256];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = (float) Math.pow(i / 255.0, 2.2);
        }
        return lut;
    }

    private static int[] buildEncodeLut() {
        int[] lut = new int[4096];
        for (int i = 0; i < lut.length; i++) {
            double linear = i / (double) (lut.length - 1);
            lut[i] = (int) Math.round(255.0 * Math.pow(linear, 1.0 / 2.2));
        }
        return lut;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
