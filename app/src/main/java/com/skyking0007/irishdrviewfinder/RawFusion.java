package com.skyking0007.irishdrviewfinder;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.media.Image;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * V2.30 immutable RAW_SENSOR carrier for saved SHORT/LONG fusion.
 *
 * The Image is copied while timestamp-matched metadata is still owned by the capture set.
 * DNG writing may then release the Image independently; production fusion consumes only
 * this compact sensor mosaic plus the matched RAW metadata. HAL JPEG bytes are never a
 * fusion input.
 */
final class RawFusion {
    private RawFusion() {}

    static final class RawFrame {
        final int width;
        final int height;
        final short[] pixels;
        final float[] blackPattern;
        final float whiteLevel;
        final int cfaArrangement;
        final float[] wbGains;
        final float[] colorTransformRows;
        final int shadingMapWidth;
        final int shadingMapHeight;
        final float[] shadingMapRgba;
        final long sensorTimestampNs;
        final long exposureTimeNs;
        final int sensitivityIso;
        final float noiseSlope;
        final float noiseOffset;

        RawFrame(
                int width,
                int height,
                short[] pixels,
                float[] blackPattern,
                float whiteLevel,
                int cfaArrangement,
                float[] wbGains,
                float[] colorTransformRows,
                int shadingMapWidth,
                int shadingMapHeight,
                float[] shadingMapRgba,
                long sensorTimestampNs,
                long exposureTimeNs,
                int sensitivityIso,
                float noiseSlope,
                float noiseOffset) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.blackPattern = blackPattern;
            this.whiteLevel = whiteLevel;
            this.cfaArrangement = cfaArrangement;
            this.wbGains = wbGains;
            this.colorTransformRows = colorTransformRows;
            this.shadingMapWidth = shadingMapWidth;
            this.shadingMapHeight = shadingMapHeight;
            this.shadingMapRgba = shadingMapRgba;
            this.sensorTimestampNs = sensorTimestampNs;
            this.exposureTimeNs = exposureTimeNs;
            this.sensitivityIso = sensitivityIso;
            this.noiseSlope = noiseSlope;
            this.noiseOffset = noiseOffset;
        }

        ByteBuffer directUnsigned16Buffer() {
            ByteBuffer bytes = ByteBuffer.allocateDirect(pixels.length * 2)
                    .order(ByteOrder.nativeOrder());
            bytes.asShortBuffer().put(pixels);
            bytes.position(0);
            return bytes;
        }
    }

    static RawFrame copyFromImage(
            Image image,
            CameraCharacteristics characteristics,
            TotalCaptureResult result) {
        if (image == null || characteristics == null || result == null) {
            throw new IllegalArgumentException("RAW copy requires image, characteristics, and result");
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length != 1) {
            throw new IllegalStateException("RAW_SENSOR must expose exactly one plane");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Invalid RAW dimensions " + width + "x" + height);
        }

        Image.Plane plane = planes[0];
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (rowStride <= 0 || pixelStride < 2) {
            throw new IllegalStateException(
                    "Unsupported RAW strides row=" + rowStride + " pixel=" + pixelStride);
        }
        ByteBuffer source = plane.getBuffer().duplicate().order(ByteOrder.nativeOrder());
        int base = source.position();
        int limit = source.limit();
        long lastIndex = (long) base + (long) (height - 1) * rowStride
                + (long) (width - 1) * pixelStride + 2L;
        if (lastIndex > limit) {
            throw new IllegalStateException(
                    "RAW plane buffer too small for declared strides: need=" + lastIndex
                            + " limit=" + limit);
        }
        short[] pixels = new short[width * height];
        for (int y = 0; y < height; y++) {
            int row = base + y * rowStride;
            int out = y * width;
            for (int x = 0; x < width; x++) {
                pixels[out + x] = source.getShort(row + x * pixelStride);
            }
        }

        Integer cfa = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa == null || cfa < 0 || cfa > 3) {
            throw new IllegalStateException("V2.30 RAW fusion requires Bayer RGGB/GRBG/GBRG/BGGR CFA");
        }

        float[] black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        float[] blackPattern = new float[4];
        if (black != null && black.length == 4) {
            System.arraycopy(black, 0, blackPattern, 0, 4);
        } else {
            BlackLevelPattern fixed = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            if (fixed == null) {
                throw new IllegalStateException("RAW fusion missing dynamic and fixed black level");
            }
            blackPattern[0] = fixed.getOffsetForIndex(0, 0);
            blackPattern[1] = fixed.getOffsetForIndex(1, 0);
            blackPattern[2] = fixed.getOffsetForIndex(0, 1);
            blackPattern[3] = fixed.getOffsetForIndex(1, 1);
        }

        Integer dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        Integer fixedWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        int white = dynamicWhite != null ? dynamicWhite : (fixedWhite == null ? 0 : fixedWhite);
        float maxBlack = Math.max(Math.max(blackPattern[0], blackPattern[1]),
                Math.max(blackPattern[2], blackPattern[3]));
        if (white <= maxBlack + 1.0f || white > 65535) {
            throw new IllegalStateException(
                    "Invalid RAW white/black levels white=" + white + " maxBlack=" + maxBlack);
        }

        RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (gains == null || transform == null) {
            throw new IllegalStateException(
                    "RAW fusion requires timestamp-matched WB gains and sensor->linear-sRGB transform");
        }
        float[] wb = {
                gains.getRed(), gains.getGreenEven(), gains.getGreenOdd(), gains.getBlue()};
        for (float value : wb) {
            if (!(value > 0.0f) || !Float.isFinite(value)) {
                throw new IllegalStateException("Invalid RAW white-balance gain " + value);
            }
        }
        float[] rows = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float value = transform.getElement(col, row).floatValue();
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("Invalid RAW color transform element");
                }
                rows[row * 3 + col] = value;
            }
        }

        LensShadingMap shading = result.get(
                CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (shading == null) {
            throw new IllegalStateException(
                    "V2.30 RAW fusion requires timestamp-matched lens shading map");
        }
        int shadingWidth = shading.getColumnCount();
        int shadingHeight = shading.getRowCount();
        if (shadingWidth <= 0 || shadingHeight <= 0) {
            throw new IllegalStateException(
                    "Invalid RAW lens shading map " + shadingWidth + "x" + shadingHeight);
        }
        float[] shadingRgba = new float[shadingWidth * shadingHeight * 4];
        shading.copyGainFactors(shadingRgba, 0);
        for (float value : shadingRgba) {
            if (!(value >= 1.0f) || !Float.isFinite(value)) {
                throw new IllegalStateException("Invalid RAW lens shading gain " + value);
            }
        }

        // V2.32 physical RAW-noise authority. Camera2 exposes the per-capture
        // variance model as pairs (S,O) with variance = S * signal + O. Iris uses
        // the conservative envelope across reported color planes so CFA reconstruction
        // never depends on a guessed channel order and never treats sensor uncertainty
        // as scene structure. The exact per-pixel lens-shading gain is applied to this
        // model later in raw_preprocess.frag.
        Pair<Double, Double>[] noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (noiseProfile == null || noiseProfile.length == 0) {
            throw new IllegalStateException("V2.32 RAW fusion requires SENSOR_NOISE_PROFILE");
        }
        double maxNoiseSlope = 0.0;
        double maxNoiseOffset = 0.0;
        for (Pair<Double, Double> pair : noiseProfile) {
            if (pair == null || pair.first == null || pair.second == null
                    || !Double.isFinite(pair.first) || !Double.isFinite(pair.second)
                    || pair.first < 0.0 || pair.second < 0.0) {
                throw new IllegalStateException("Invalid RAW SENSOR_NOISE_PROFILE entry");
            }
            maxNoiseSlope = Math.max(maxNoiseSlope, pair.first);
            maxNoiseOffset = Math.max(maxNoiseOffset, pair.second);
        }
        if (!(maxNoiseSlope > 0.0 || maxNoiseOffset > 0.0)) {
            throw new IllegalStateException("RAW SENSOR_NOISE_PROFILE is all zero");
        }

        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (timestamp == null || exposure == null || iso == null
                || exposure <= 0L || iso <= 0) {
            throw new IllegalStateException("RAW fusion missing physical exposure metadata");
        }
        if (timestamp != image.getTimestamp()) {
            throw new IllegalStateException(
                    "RAW/result timestamp mismatch image=" + image.getTimestamp()
                            + " result=" + timestamp);
        }

        float noiseSlope = (float) maxNoiseSlope;
        float noiseOffset = (float) maxNoiseOffset;
        if (!Float.isFinite(noiseSlope) || !Float.isFinite(noiseOffset)
                || noiseSlope < 0.0f || noiseOffset < 0.0f
                || !(noiseSlope > 0.0f || noiseOffset > 0.0f)) {
            throw new IllegalStateException("RAW SENSOR_NOISE_PROFILE cannot be represented as finite floats");
        }
        RuntimeLogger.event(
                "RAW_NOISE_PROFILE",
                String.format(Locale.US,
                        "iso=%d planes=%d slopeMax=%.9g offsetMax=%.9g",
                        iso, noiseProfile.length, noiseSlope, noiseOffset));

        return new RawFrame(
                width, height, pixels, blackPattern, white, cfa, wb, rows,
                shadingWidth, shadingHeight, shadingRgba,
                timestamp, exposure, iso, noiseSlope, noiseOffset);
    }

    static double rawExposureRatio(RawFrame shortFrame, RawFrame longFrame) {
        if (shortFrame == null || longFrame == null) return Double.NaN;
        double shortProduct = (double) shortFrame.exposureTimeNs * shortFrame.sensitivityIso;
        double longProduct = (double) longFrame.exposureTimeNs * longFrame.sensitivityIso;
        if (!(shortProduct > 0.0) || !(longProduct > 0.0)) return Double.NaN;
        return longProduct / shortProduct;
    }
}
