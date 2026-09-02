package com.skyking0007.irishdrviewfinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.media.Image;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.util.Rational;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * V1.5.0 physical SHORT/LONG RAW HDR owner.
 *
 * The merger keeps LONG on its native sensor grid, estimates continuous SHORT->LONG
 * motion from exposure-normalized green RAW evidence, replaces only physically damaged
 * LONG CFA samples with trustworthy same-CFA SHORT measurements, then demosaics once.
 * Display tone mapping is deliberately delegated to hdr_display.frag mode 3 so the
 * saved JPEG and live HDR viewfinder share one GTM implementation.
 */
final class RawHdrFusion {
    static final class RawBuffer {
        final int width;
        final int height;
        final byte[] packed16;

        RawBuffer(int width, int height, byte[] packed16) {
            this.width = width;
            this.height = height;
            this.packed16 = packed16;
        }

        int sample(int x, int y) {
            int clampedX = Math.max(0, Math.min(width - 1, x));
            int clampedY = Math.max(0, Math.min(height - 1, y));
            int index = (clampedY * width + clampedX) * 2;
            return (packed16[index] & 0xFF) | ((packed16[index + 1] & 0xFF) << 8);
        }
    }

    private static final int GUIDE_FACTOR = 4;
    private static final int FLOW_WIDTH = 64;
    private static final int FLOW_HEIGHT = 48;
    private static final int PHOTO_FIELD_HEIGHT = 64;
    private static final int GLOBAL_SEARCH_RADIUS = 8; // guide pixels = 32 RAW px
    private static final int LOCAL_SEARCH_RADIUS = 2;  // guide pixels around global
    private static final int LOCAL_PATCH_RADIUS = 4;
    private static final int TILE_ROWS = 512;
    private static final int DEMOSAIC_HALO = 4;
    private static final int FLOW_MARGIN_RAW = 48;

    private RawHdrFusion() {}

    static RawBuffer copyRaw(Image image) {
        if (image == null || image.getFormat() != ImageFormat.RAW_SENSOR) {
            throw new IllegalArgumentException("Expected RAW_SENSOR Image");
        }
        if (image.getPlanes().length != 1) {
            throw new IllegalStateException("RAW_SENSOR plane count != 1");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (pixelStride < 2) {
            throw new IllegalStateException("RAW_SENSOR pixel stride < 2: " + pixelStride);
        }
        ByteBuffer source = plane.getBuffer().duplicate();
        final int sourceBase = source.position();
        byte[] packed = new byte[width * height * 2];
        if (pixelStride == 2) {
            byte[] row = new byte[width * 2];
            for (int y = 0; y < height; y++) {
                int position = sourceBase + y * rowStride;
                if (position < 0 || position + row.length > source.limit()) {
                    throw new IllegalStateException("RAW_SENSOR row exceeds buffer at y=" + y);
                }
                source.position(position);
                source.get(row);
                System.arraycopy(row, 0, packed, y * row.length, row.length);
            }
        } else {
            for (int y = 0; y < height; y++) {
                int rowBase = sourceBase + y * rowStride;
                for (int x = 0; x < width; x++) {
                    int sourceIndex = rowBase + x * pixelStride;
                    int targetIndex = (y * width + x) * 2;
                    if (sourceIndex + 1 >= source.limit()) {
                        throw new IllegalStateException("RAW_SENSOR pixel exceeds buffer");
                    }
                    packed[targetIndex] = source.get(sourceIndex);
                    packed[targetIndex + 1] = source.get(sourceIndex + 1);
                }
            }
        }
        return new RawBuffer(width, height, packed);
    }

    static byte[] fuse(
            Context context,
            RawBuffer shortRaw,
            RawBuffer longRaw,
            TotalCaptureResult shortResult,
            TotalCaptureResult longResult,
            CameraCharacteristics characteristics,
            Size jpegOutputSize,
            int captureOrientationDegrees,
            String expectedPhysicalId,
            Rect viewfinderSensorCrop) throws Exception {
        long allStart = System.nanoTime();
        if (shortRaw.width != longRaw.width || shortRaw.height != longRaw.height) {
            throw new IllegalStateException("SHORT/LONG RAW dimensions differ");
        }
        String shortPhysical = shortResult.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        String longPhysical = longResult.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        if (shortPhysical != null && longPhysical != null && !shortPhysical.equals(longPhysical)) {
            throw new IllegalStateException(
                    "SHORT/LONG active physical sensor mismatch: "
                            + shortPhysical + " vs " + longPhysical);
        }
        String activePhysical = longPhysical != null ? longPhysical : shortPhysical;
        if (expectedPhysicalId != null && !expectedPhysicalId.isEmpty()) {
            if (activePhysical == null || activePhysical.isEmpty()) {
                throw new IllegalStateException(
                        "Still RAW physical sensor cannot be proven against displayed sensor "
                                + expectedPhysicalId);
            }
            if (!expectedPhysicalId.equals(activePhysical)) {
                throw new IllegalStateException(
                        "Displayed/still physical sensor mismatch: "
                                + expectedPhysicalId + " vs " + activePhysical);
            }
        }
        CameraCharacteristics rawCharacteristics = characteristics;
        if (activePhysical != null && !activePhysical.isEmpty()) {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("CameraManager unavailable for physical RAW metadata");
            }
            rawCharacteristics = manager.getCameraCharacteristics(activePhysical);
            RuntimeLogger.event("RAW_HDR_PHYSICAL_AUTHORITY", "physical=" + activePhysical);
        }

        SensorModel shortModel = SensorModel.create(
                shortRaw, shortResult, rawCharacteristics);
        SensorModel longModel = SensorModel.create(
                longRaw, longResult, rawCharacteristics);
        if (shortModel.cfaPattern != longModel.cfaPattern) {
            throw new IllegalStateException("SHORT/LONG CFA mismatch");
        }
        Rect active = commonActiveArray(shortModel, longModel);
        if (viewfinderSensorCrop != null) {
            Rect crop = new Rect(viewfinderSensorCrop);
            if (!crop.intersect(active) || crop.width() < 16 || crop.height() < 16) {
                throw new IllegalStateException(
                        "Displayed physical-sensor crop is incompatible with still RAW active array");
            }
            active = crop;
            RuntimeLogger.event("RAW_HDR_WYSIWYG_CROP",
                    active.left + "," + active.top + "-" + active.right + "," + active.bottom);
        }
        double metadataRatio = exposureRatio(shortResult, longResult);

        long stageStart = System.nanoTime();
        Guide shortGuide = Guide.build(shortModel, active, (float) metadataRatio);
        Guide longGuide = Guide.build(longModel, active, 1.0f);
        AlignmentField alignment = AlignmentField.estimate(longGuide, shortGuide);
        RuntimeLogger.event(
                "RAW_HDR_ALIGN",
                String.format(java.util.Locale.US,
                        "globalDx=%.3f globalDy=%.3f meanConfidence=%.3f ms=%d",
                        alignment.globalDxRaw, alignment.globalDyRaw,
                        alignment.meanConfidence, elapsedMs(stageStart)));

        stageStart = System.nanoTime();
        PhotometricField photometric = PhotometricField.estimate(
                longGuide, shortGuide, alignment);
        RuntimeLogger.event(
                "RAW_HDR_RADIOMETRY",
                String.format(java.util.Locale.US,
                        "metadataRatio=%.5f residual=%.5f effective=%.5f rowConfidence=%.3f ms=%d",
                        metadataRatio, photometric.globalScale,
                        metadataRatio * photometric.globalScale,
                        photometric.meanConfidence, elapsedMs(stageStart)));

        stageStart = System.nanoTime();
        Bitmap activeBitmap;
        try (GpuRawRenderer renderer = new GpuRawRenderer(context)) {
            activeBitmap = renderer.render(
                    shortModel, longModel, active, alignment,
                    (float) metadataRatio, photometric);
        }
        RuntimeLogger.event(
                "RAW_HDR_GPU",
                "active=" + activeBitmap.getWidth() + "x" + activeBitmap.getHeight()
                        + " ms=" + elapsedMs(stageStart));

        if (jpegOutputSize == null || jpegOutputSize.getWidth() <= 0 || jpegOutputSize.getHeight() <= 0) {
            throw new IllegalArgumentException("Configured JPEG output size missing");
        }
        Bitmap displaySize = activeBitmap;
        if (activeBitmap.getWidth() != jpegOutputSize.getWidth()
                || activeBitmap.getHeight() != jpegOutputSize.getHeight()) {
            stageStart = System.nanoTime();
            displaySize = Bitmap.createScaledBitmap(
                    activeBitmap, jpegOutputSize.getWidth(), jpegOutputSize.getHeight(), true);
            activeBitmap.recycle();
            RuntimeLogger.event(
                    "RAW_HDR_OUTPUT_SCALE",
                    "target=" + jpegOutputSize + " ms=" + elapsedMs(stageStart));
        }

        Bitmap upright = rotateBitmap(displaySize, captureOrientationDegrees);
        if (upright != displaySize) displaySize.recycle();

        stageStart = System.nanoTime();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ok = upright.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
        upright.recycle();
        if (!ok) throw new IllegalStateException("JPEG encoder rejected RAW HDR bitmap");
        RuntimeLogger.event(
                "RAW_HDR_ENCODE",
                "bytes=" + bytes.size() + " ms=" + elapsedMs(stageStart));
        RuntimeLogger.event("RAW_HDR_TOTAL", "ms=" + elapsedMs(allStart));
        return bytes.toByteArray();
    }

    private static Bitmap rotateBitmap(Bitmap source, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) return source;
        if ((normalized % 90) != 0) {
            throw new IllegalArgumentException("RAW HDR orientation must be multiple of 90");
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(normalized);
        return Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static Rect commonActiveArray(SensorModel shortModel, SensorModel longModel) {
        Rect s = shortModel.activeArray;
        Rect l = longModel.activeArray;
        Rect intersection = new Rect(
                Math.max(s.left, l.left), Math.max(s.top, l.top),
                Math.min(s.right, l.right), Math.min(s.bottom, l.bottom));
        if (intersection.width() < 16 || intersection.height() < 16) {
            throw new IllegalStateException("Invalid SHORT/LONG active-array intersection");
        }
        return intersection;
    }

    private static double exposureRatio(
            TotalCaptureResult shortResult, TotalCaptureResult longResult) {
        Long se = shortResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer si = shortResult.get(CaptureResult.SENSOR_SENSITIVITY);
        Long le = longResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer li = longResult.get(CaptureResult.SENSOR_SENSITIVITY);
        if (se == null || si == null || le == null || li == null) {
            throw new IllegalStateException("RAW HDR exposure metadata missing");
        }
        // POST_RAW_SENSITIVITY_BOOST is intentionally excluded: RAW_SENSOR is the
        // physical radiometric authority and post-RAW processing is display-domain.
        double shortProduct = Math.max(1.0, (double) se * Math.max(1, si));
        double longProduct = Math.max(1.0, (double) le * Math.max(1, li));
        return Math.max(1.0, Math.min(65_536.0, longProduct / shortProduct));
    }

    private static float estimateResidualScale(
            Guide longGuide, Guide shortGuide, AlignmentField alignment) {
        float[] ratios = new float[(longGuide.width / 4 + 1) * (longGuide.height / 4 + 1)];
        int count = 0;
        float[] flow = new float[3];
        for (int y = 4; y < longGuide.height - 4; y += 4) {
            for (int x = 4; x < longGuide.width - 4; x += 4) {
                int index = y * longGuide.width + x;
                if (!longGuide.valid[index]) continue;
                float longValue = longGuide.linear[index];
                if (longValue < 0.025f || longValue > 0.72f) continue;
                alignment.sampleRaw(
                        x / (float) Math.max(1, longGuide.width - 1),
                        y / (float) Math.max(1, longGuide.height - 1), flow);
                if (flow[2] < 0.55f) continue;
                float sx = x + flow[0] / GUIDE_FACTOR;
                float sy = y + flow[1] / GUIDE_FACTOR;
                float shortValue = shortGuide.sampleLinear(sx, sy);
                if (!(shortValue > 0.015f)) continue;
                float ratio = longValue / shortValue;
                if (ratio >= 0.65f && ratio <= 1.45f) ratios[count++] = ratio;
            }
        }
        if (count < 64) return 1.0f;
        Arrays.sort(ratios, 0, count);
        float median = (count & 1) != 0
                ? ratios[count / 2]
                : 0.5f * (ratios[count / 2 - 1] + ratios[count / 2]);
        return clamp(median, 0.80f, 1.20f);
    }

    private static final class PhotometricField {
        final float globalScale;
        final float[] rgba;
        final float meanConfidence;

        PhotometricField(float globalScale, float[] rgba, float meanConfidence) {
            this.globalScale = globalScale;
            this.rgba = rgba;
            this.meanConfidence = meanConfidence;
        }

        static PhotometricField estimate(
                Guide longGuide, Guide shortGuide, AlignmentField alignment) {
            float global = estimateResidualScale(longGuide, shortGuide, alignment);
            float[] field = new float[PHOTO_FIELD_HEIGHT * 4];
            boolean[] proven = new boolean[PHOTO_FIELD_HEIGHT];
            float confidenceSum = 0.0f;

            int approximateRowsPerBin = Math.max(1,
                    (longGuide.height + PHOTO_FIELD_HEIGHT - 1) / PHOTO_FIELD_HEIGHT);
            int maxSamplesPerBin = Math.max(256,
                    longGuide.width * (approximateRowsPerBin + 3) / 2);
            float[][] ratiosBySourceRow = new float[PHOTO_FIELD_HEIGHT][maxSamplesPerBin];
            int[] counts = new int[PHOTO_FIELD_HEIGHT];

            // The correction belongs to the SHORT sensor row after geometric warp,
            // not the target LONG row. This matters for rolling-shutter/PWM because
            // local flow can move a sample across row-modulation phase.
            float[] flow = new float[3];
            for (int y = 2; y < longGuide.height - 2; y++) {
                for (int x = 2; x < longGuide.width - 2; x += 2) {
                    int index = y * longGuide.width + x;
                    if (!longGuide.valid[index]) continue;
                    float longValue = longGuide.linear[index];
                    if (longValue < 0.025f || longValue > 0.72f) continue;
                    alignment.sampleRaw(
                            x / (float) Math.max(1, longGuide.width - 1),
                            y / (float) Math.max(1, longGuide.height - 1), flow);
                    if (flow[2] < 0.55f) continue;
                    float sx = x + flow[0] / GUIDE_FACTOR;
                    float sy = y + flow[1] / GUIDE_FACTOR;
                    float shortValue = shortGuide.sampleLinear(sx, sy);
                    if (!(shortValue > 0.015f) || !Float.isFinite(shortValue)) continue;
                    float ratio = longValue / shortValue;
                    if (ratio < 0.65f || ratio > 1.45f) continue;
                    int sourceBin = clampInt(
                            (int) Math.floor(clamp(sy / Math.max(1.0f, shortGuide.height - 1.0f),
                                    0.0f, 0.999999f) * PHOTO_FIELD_HEIGHT),
                            0, PHOTO_FIELD_HEIGHT - 1);
                    int count = counts[sourceBin];
                    if (count < maxSamplesPerBin) {
                        ratiosBySourceRow[sourceBin][count] = ratio;
                        counts[sourceBin] = count + 1;
                    }
                }
            }

            for (int bin = 0; bin < PHOTO_FIELD_HEIGHT; bin++) {
                int count = counts[bin];
                int base = bin * 4;
                if (count >= 24) {
                    float[] ratios = ratiosBySourceRow[bin];
                    Arrays.sort(ratios, 0, count);
                    float median = medianSorted(ratios, count);
                    float[] deviations = new float[count];
                    for (int i = 0; i < count; i++) {
                        deviations[i] = Math.abs(log2(Math.max(0.0001f, ratios[i] / median)));
                    }
                    Arrays.sort(deviations);
                    float madEv = medianSorted(deviations, count);
                    float sampleConfidence = clamp(count / 128.0f, 0.0f, 1.0f);
                    float consistencyConfidence = 1.0f - smoothstep(0.035f, 0.16f, madEv);
                    float confidence = sampleConfidence * consistencyConfidence;
                    field[base] = clamp(median / Math.max(0.0001f, global), 0.75f, 1.25f);
                    field[base + 1] = confidence;
                    field[base + 2] = madEv;
                    field[base + 3] = clamp(count / 256.0f, 0.0f, 1.0f);
                    proven[bin] = confidence >= 0.30f;
                } else {
                    field[base] = 1.0f;
                    field[base + 1] = 0.0f;
                    field[base + 2] = 1.0f;
                    field[base + 3] = clamp(count / 256.0f, 0.0f, 1.0f);
                }
            }

            // PWM/rolling-shutter variation is row-structured. A row with no usable
            // overlap may inherit only nearby proven rows; a contradictory/noisy row
            // never receives high confidence from a global brightness fit.
            float[] filled = field.clone();
            for (int bin = 0; bin < PHOTO_FIELD_HEIGHT; bin++) {
                if (proven[bin]) continue;
                int above = -1;
                int below = -1;
                for (int d = 1; d <= 4; d++) {
                    if (above < 0 && bin - d >= 0 && proven[bin - d]) above = bin - d;
                    if (below < 0 && bin + d < PHOTO_FIELD_HEIGHT && proven[bin + d]) below = bin + d;
                }
                int base = bin * 4;
                if (above >= 0 && below >= 0) {
                    float t = (bin - above) / (float) (below - above);
                    filled[base] = lerp(field[above * 4], field[below * 4], t);
                    filled[base + 1] = 0.65f * Math.min(
                            field[above * 4 + 1], field[below * 4 + 1]);
                    filled[base + 2] = Math.max(
                            field[above * 4 + 2], field[below * 4 + 2]);
                } else if (above >= 0 || below >= 0) {
                    int source = above >= 0 ? above : below;
                    filled[base] = field[source * 4];
                    filled[base + 1] = 0.45f * field[source * 4 + 1];
                    filled[base + 2] = field[source * 4 + 2];
                }
            }

            for (int bin = 0; bin < PHOTO_FIELD_HEIGHT; bin++) {
                confidenceSum += filled[bin * 4 + 1];
            }
            return new PhotometricField(
                    global, filled, confidenceSum / PHOTO_FIELD_HEIGHT);
        }
    }

    private static final class SensorModel {
        final RawBuffer raw;
        final int cfaPattern;
        final float[] blackByChannel;
        final float whiteLevel;
        final LensShadingMap lensShadingMap;
        final float[] lensShading;
        final int shadingCols;
        final int shadingRows;
        final Rect activeArray;
        final float[] wbGains;
        final float[] colorMatrix;

        private SensorModel(
                RawBuffer raw, int cfaPattern, float[] blackByChannel,
                float whiteLevel, LensShadingMap lensShadingMap, float[] lensShading,
                int shadingCols, int shadingRows, Rect activeArray,
                float[] wbGains, float[] colorMatrix) {
            this.raw = raw;
            this.cfaPattern = cfaPattern;
            this.blackByChannel = blackByChannel;
            this.whiteLevel = whiteLevel;
            this.lensShadingMap = lensShadingMap;
            this.lensShading = lensShading;
            this.shadingCols = shadingCols;
            this.shadingRows = shadingRows;
            this.activeArray = activeArray;
            this.wbGains = wbGains;
            this.colorMatrix = colorMatrix;
        }

        static SensorModel create(
                RawBuffer raw,
                TotalCaptureResult result,
                CameraCharacteristics characteristics) {
            Integer cfa = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            if (cfa == null || cfa < 0 || cfa > 3) {
                throw new IllegalStateException("Unsupported RAW CFA arrangement: " + cfa);
            }

            float[] black = new float[4];
            float[] dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            if (dynamicBlack != null && dynamicBlack.length >= 4) {
                // Camera2 reports dynamic black in the CFA's top-left 2x2 readout
                // order. Remap that phase order into our semantic [R, Ge, Go, B]
                // channel order so non-RGGB physical lenses stay correct.
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        int channel = channelAt(cfa, x, y);
                        black[channel] = dynamicBlack[y * 2 + x];
                    }
                }
            } else {
                BlackLevelPattern pattern = characteristics.get(
                        CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
                if (pattern == null) throw new IllegalStateException("RAW black level missing");
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        int channel = channelAt(cfa, x, y);
                        black[channel] = pattern.getOffsetForIndex(x, y);
                    }
                }
            }

            Integer dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
            Integer staticWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            float white = dynamicWhite != null
                    ? dynamicWhite
                    : (staticWhite == null ? 0 : staticWhite);
            if (white <= max(black) + 1.0f) {
                throw new IllegalStateException("RAW white level invalid: " + white);
            }

            LensShadingMap shading = result.get(
                    CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
            if (shading == null) {
                throw new IllegalStateException("RAW lens shading map missing");
            }
            float[] shadingFactors = new float[shading.getGainFactorCount()];
            shading.copyGainFactors(shadingFactors, 0);

            Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null) active = new Rect(0, 0, raw.width, raw.height);
            active = new Rect(
                    clampInt(active.left, 0, raw.width - 1),
                    clampInt(active.top, 0, raw.height - 1),
                    clampInt(active.right, 1, raw.width),
                    clampInt(active.bottom, 1, raw.height));
            if (active.right <= active.left || active.bottom <= active.top) {
                active = new Rect(0, 0, raw.width, raw.height);
            }

            RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            if (gains == null || transform == null) {
                throw new IllegalStateException("RAW WB/color transform metadata missing");
            }
            float[] wb = {
                    gains.getRed(), gains.getGreenEven(), gains.getGreenOdd(), gains.getBlue()};
            float[] matrix = new float[9];
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    Rational element = transform.getElement(col, row);
                    matrix[row * 3 + col] = element.floatValue();
                }
            }
            return new SensorModel(
                    raw, cfa, black, white, shading, shadingFactors,
                    shading.getColumnCount(), shading.getRowCount(), active, wb, matrix);
        }

        float sensorNormalized(int x, int y) {
            int channel = channelAt(cfaPattern, x, y);
            float black = blackByChannel[channel];
            return clamp((raw.sample(x, y) - black) / Math.max(1.0f, whiteLevel - black),
                    0.0f, 1.25f);
        }

        float sceneSample(int x, int y, float exposureScale) {
            int channel = channelAt(cfaPattern, x, y);
            return sensorNormalized(x, y) * shadingGain(x, y, channel) * exposureScale;
        }

        float shadingGain(int x, int y, int channel) {
            float u = (x - activeArray.left)
                    / (float) Math.max(1, activeArray.width() - 1);
            float v = (y - activeArray.top)
                    / (float) Math.max(1, activeArray.height() - 1);
            u = clamp(u, 0.0f, 1.0f);
            v = clamp(v, 0.0f, 1.0f);
            float fx = u * Math.max(0, shadingCols - 1);
            float fy = v * Math.max(0, shadingRows - 1);
            int x0 = Math.min(shadingCols - 1, (int) Math.floor(fx));
            int y0 = Math.min(shadingRows - 1, (int) Math.floor(fy));
            int x1 = Math.min(shadingCols - 1, x0 + 1);
            int y1 = Math.min(shadingRows - 1, y0 + 1);
            float tx = fx - x0;
            float ty = fy - y0;
            float a = shadingAt(x0, y0, channel);
            float b = shadingAt(x1, y0, channel);
            float c = shadingAt(x0, y1, channel);
            float d = shadingAt(x1, y1, channel);
            return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
        }

        private float shadingAt(int x, int y, int channel) {
            return lensShading[(y * shadingCols + x) * 4 + channel];
        }
    }

    private static final class Guide {
        final int width;
        final int height;
        final float[] linear;
        final float[] log2;
        final boolean[] valid;

        Guide(int width, int height, float[] linear, float[] log2, boolean[] valid) {
            this.width = width;
            this.height = height;
            this.linear = linear;
            this.log2 = log2;
            this.valid = valid;
        }

        static Guide build(SensorModel model, Rect active, float exposureScale) {
            int width = Math.max(1, active.width() / GUIDE_FACTOR);
            int height = Math.max(1, active.height() / GUIDE_FACTOR);
            float[] linear = new float[width * height];
            float[] log2 = new float[width * height];
            boolean[] valid = new boolean[width * height];
            for (int gy = 0; gy < height; gy++) {
                int y0 = active.top + gy * GUIDE_FACTOR;
                for (int gx = 0; gx < width; gx++) {
                    int x0 = active.left + gx * GUIDE_FACTOR;
                    float sum = 0.0f;
                    int count = 0;
                    for (int dy = 0; dy < GUIDE_FACTOR; dy++) {
                        for (int dx = 0; dx < GUIDE_FACTOR; dx++) {
                            int x = Math.min(active.right - 1, x0 + dx);
                            int y = Math.min(active.bottom - 1, y0 + dy);
                            int channel = channelAt(model.cfaPattern, x, y);
                            if (channel != 1 && channel != 2) continue;
                            float sensor = model.sensorNormalized(x, y);
                            if (sensor < 0.006f || sensor > 0.92f) continue;
                            sum += model.sceneSample(x, y, exposureScale);
                            count++;
                        }
                    }
                    int index = gy * width + gx;
                    if (count >= 2) {
                        float value = sum / count;
                        linear[index] = value;
                        log2[index] = log2(Math.max(value, 0.00001f));
                        valid[index] = true;
                    }
                }
            }
            return new Guide(width, height, linear, log2, valid);
        }

        float sampleLinear(float x, float y) {
            return sample(linear, x, y, false);
        }

        float sampleLog(float x, float y) {
            return sample(log2, x, y, true);
        }

        private float sample(float[] values, float x, float y, boolean requireValid) {
            if (x < 0.0f || y < 0.0f || x > width - 1.001f || y > height - 1.001f) {
                return Float.NaN;
            }
            int x0 = (int) Math.floor(x);
            int y0 = (int) Math.floor(y);
            int x1 = Math.min(width - 1, x0 + 1);
            int y1 = Math.min(height - 1, y0 + 1);
            int i00 = y0 * width + x0;
            int i10 = y0 * width + x1;
            int i01 = y1 * width + x0;
            int i11 = y1 * width + x1;
            if (requireValid && !(valid[i00] && valid[i10] && valid[i01] && valid[i11])) {
                return Float.NaN;
            }
            if (!requireValid && !(valid[i00] && valid[i10] && valid[i01] && valid[i11])) {
                return Float.NaN;
            }
            float tx = x - x0;
            float ty = y - y0;
            float a = lerp(values[i00], values[i10], tx);
            float b = lerp(values[i01], values[i11], tx);
            return lerp(a, b, ty);
        }
    }

    private static final class SearchResult {
        final float dx;
        final float dy;
        final float cost;
        final int samples;
        final float texture;

        SearchResult(float dx, float dy, float cost, int samples, float texture) {
            this.dx = dx;
            this.dy = dy;
            this.cost = cost;
            this.samples = samples;
            this.texture = texture;
        }
    }

    private static final class AlignmentField {
        final float[] rgba;
        final float globalDxRaw;
        final float globalDyRaw;
        final float meanConfidence;

        AlignmentField(float[] rgba, float globalDxRaw, float globalDyRaw, float meanConfidence) {
            this.rgba = rgba;
            this.globalDxRaw = globalDxRaw;
            this.globalDyRaw = globalDyRaw;
            this.meanConfidence = meanConfidence;
        }

        static AlignmentField estimate(Guide longGuide, Guide shortGuide) {
            SearchResult global = searchGlobal(longGuide, shortGuide);
            float globalCostConfidence = clamp((0.38f - global.cost) / 0.28f, 0.0f, 1.0f);
            float globalSampleConfidence = clamp(global.samples / 256.0f, 0.0f, 1.0f);
            float globalTextureConfidence = clamp((global.texture - 0.008f) / 0.045f, 0.0f, 1.0f);
            float globalConfidence = globalCostConfidence * globalSampleConfidence
                    * Math.max(0.35f, globalTextureConfidence);
            float[] raw = new float[FLOW_WIDTH * FLOW_HEIGHT * 4];
            for (int gy = 0; gy < FLOW_HEIGHT; gy++) {
                float cy = (gy + 0.5f) * longGuide.height / FLOW_HEIGHT;
                for (int gx = 0; gx < FLOW_WIDTH; gx++) {
                    float cx = (gx + 0.5f) * longGuide.width / FLOW_WIDTH;
                    SearchResult forward = searchPatch(
                            longGuide, shortGuide, cx, cy, global.dx, global.dy);
                    SearchResult backward = searchPatch(
                            shortGuide, longGuide,
                            cx + forward.dx, cy + forward.dy,
                            -forward.dx, -forward.dy);
                    float consistency = (float) Math.hypot(
                            forward.dx + backward.dx, forward.dy + backward.dy);
                    float costConfidence = clamp((0.38f - forward.cost) / 0.28f, 0.0f, 1.0f);
                    float sampleConfidence = clamp(forward.samples / 36.0f, 0.0f, 1.0f);
                    float textureConfidence = clamp((forward.texture - 0.010f) / 0.055f, 0.0f, 1.0f);
                    float consistencyConfidence = 1.0f - smoothstep(0.30f, 1.15f, consistency);
                    float confidence = costConfidence * sampleConfidence
                            * Math.max(0.20f, textureConfidence) * consistencyConfidence;
                    int index = (gy * FLOW_WIDTH + gx) * 4;
                    raw[index] = clamp(forward.dx * GUIDE_FACTOR, -40.0f, 40.0f);
                    raw[index + 1] = clamp(forward.dy * GUIDE_FACTOR, -40.0f, 40.0f);
                    raw[index + 2] = confidence;
                    raw[index + 3] = clamp(forward.samples / 81.0f, 0.0f, 1.0f);
                }
            }

            float[] smooth = raw.clone();
            float confidenceSum = 0.0f;
            for (int gy = 0; gy < FLOW_HEIGHT; gy++) {
                for (int gx = 0; gx < FLOW_WIDTH; gx++) {
                    float sumX = 0.0f;
                    float sumY = 0.0f;
                    float sumW = 0.0f;
                    int strongNeighbors = 0;
                    for (int oy = -1; oy <= 1; oy++) {
                        int ny = clampInt(gy + oy, 0, FLOW_HEIGHT - 1);
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = clampInt(gx + ox, 0, FLOW_WIDTH - 1);
                            int ni = (ny * FLOW_WIDTH + nx) * 4;
                            float w = raw[ni + 2];
                            if (w >= 0.35f) strongNeighbors++;
                            sumX += raw[ni] * w;
                            sumY += raw[ni + 1] * w;
                            sumW += w;
                        }
                    }
                    int index = (gy * FLOW_WIDTH + gx) * 4;
                    float localConfidence = raw[index + 2];
                    float localEvidence = raw[index + 3];
                    float meanX = sumW > 0.0001f ? sumX / sumW : global.dx * GUIDE_FACTOR;
                    float meanY = sumW > 0.0001f ? sumY / sumW : global.dy * GUIDE_FACTOR;
                    float spread = 0.0f;
                    float spreadW = 0.0f;
                    for (int oy = -1; oy <= 1; oy++) {
                        int ny = clampInt(gy + oy, 0, FLOW_HEIGHT - 1);
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = clampInt(gx + ox, 0, FLOW_WIDTH - 1);
                            int ni = (ny * FLOW_WIDTH + nx) * 4;
                            float w = raw[ni + 2];
                            if (w <= 0.0f) continue;
                            float dx = raw[ni] - meanX;
                            float dy = raw[ni + 1] - meanY;
                            spread += (dx * dx + dy * dy) * w;
                            spreadW += w;
                        }
                    }
                    float rmsSpread = spreadW > 0.0001f
                            ? (float) Math.sqrt(spread / spreadW) : 99.0f;

                    if (localConfidence >= 0.35f && sumW > 0.0001f) {
                        // Smooth only a locally proven vector; keep its own confidence.
                        smooth[index] = meanX;
                        smooth[index + 1] = meanY;
                    } else if (localEvidence < 0.25f) {
                        // Saturated/textureless interiors have no contradictory local
                        // evidence. They may inherit coherent surrounding/global geometry.
                        if (strongNeighbors >= 3 && rmsSpread <= 1.75f) {
                            smooth[index] = meanX;
                            smooth[index + 1] = meanY;
                            float neighborConfidence = clamp(sumW / 4.0f, 0.0f, 1.0f);
                            smooth[index + 2] = Math.max(
                                    localConfidence,
                                    Math.min(0.85f, globalConfidence * neighborConfidence));
                        } else if (globalConfidence >= 0.70f) {
                            smooth[index] = global.dx * GUIDE_FACTOR;
                            smooth[index + 1] = global.dy * GUIDE_FACTOR;
                            smooth[index + 2] = Math.max(localConfidence, 0.60f * globalConfidence);
                        }
                    }
                    // High-evidence but inconsistent local motion is never rescued by
                    // neighbors: it remains low confidence and SHORT fails closed there.
                    confidenceSum += smooth[index + 2];
                }
            }
            return new AlignmentField(
                    smooth, global.dx * GUIDE_FACTOR, global.dy * GUIDE_FACTOR,
                    confidenceSum / (FLOW_WIDTH * FLOW_HEIGHT));
        }

        void sampleRaw(float u, float v, float[] out) {
            if (out == null || out.length < 3) {
                throw new IllegalArgumentException("flow output must contain 3 floats");
            }
            float x = clamp(u, 0.0f, 1.0f) * (FLOW_WIDTH - 1);
            float y = clamp(v, 0.0f, 1.0f) * (FLOW_HEIGHT - 1);
            int x0 = (int) Math.floor(x);
            int y0 = (int) Math.floor(y);
            int x1 = Math.min(FLOW_WIDTH - 1, x0 + 1);
            int y1 = Math.min(FLOW_HEIGHT - 1, y0 + 1);
            float tx = x - x0;
            float ty = y - y0;
            for (int channel = 0; channel < 3; channel++) {
                float a = lerp(
                        rgba[(y0 * FLOW_WIDTH + x0) * 4 + channel],
                        rgba[(y0 * FLOW_WIDTH + x1) * 4 + channel], tx);
                float b = lerp(
                        rgba[(y1 * FLOW_WIDTH + x0) * 4 + channel],
                        rgba[(y1 * FLOW_WIDTH + x1) * 4 + channel], tx);
                out[channel] = lerp(a, b, ty);
            }
        }
    }

    private static SearchResult searchGlobal(Guide target, Guide source) {
        SearchResult best = new SearchResult(0.0f, 0.0f, Float.POSITIVE_INFINITY, 0, 0.0f);
        for (int dy = -GLOBAL_SEARCH_RADIUS; dy <= GLOBAL_SEARCH_RADIUS; dy++) {
            for (int dx = -GLOBAL_SEARCH_RADIUS; dx <= GLOBAL_SEARCH_RADIUS; dx++) {
                SearchResult candidate = costGlobal(target, source, dx, dy);
                if (candidate.cost < best.cost) best = candidate;
            }
        }
        for (float step : new float[]{0.5f, 0.25f}) {
            SearchResult refined = best;
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    SearchResult candidate = costGlobal(
                            target, source, best.dx + ox * step, best.dy + oy * step);
                    if (candidate.cost < refined.cost) refined = candidate;
                }
            }
            best = refined;
        }
        return best;
    }

    private static SearchResult costGlobal(Guide target, Guide source, float dx, float dy) {
        float cost = 0.0f;
        float texture = 0.0f;
        int samples = 0;
        for (int y = 8; y < target.height - 8; y += 8) {
            for (int x = 8; x < target.width - 8; x += 8) {
                int index = y * target.width + x;
                if (!target.valid[index]) continue;
                float sourceValue = source.sampleLog(x + dx, y + dy);
                if (!Float.isFinite(sourceValue)) continue;
                cost += Math.min(2.0f, Math.abs(target.log2[index] - sourceValue));
                float gx = Math.abs(target.log2[index + 1] - target.log2[index - 1]);
                float gy = Math.abs(target.log2[index + target.width] - target.log2[index - target.width]);
                texture += 0.5f * (gx + gy);
                samples++;
            }
        }
        if (samples < 32) return new SearchResult(dx, dy, 9.0f, samples, 0.0f);
        return new SearchResult(dx, dy, cost / samples, samples, texture / samples);
    }

    private static SearchResult searchPatch(
            Guide target, Guide source, float cx, float cy, float initialDx, float initialDy) {
        SearchResult best = new SearchResult(initialDx, initialDy, Float.POSITIVE_INFINITY, 0, 0.0f);
        for (int oy = -LOCAL_SEARCH_RADIUS; oy <= LOCAL_SEARCH_RADIUS; oy++) {
            for (int ox = -LOCAL_SEARCH_RADIUS; ox <= LOCAL_SEARCH_RADIUS; ox++) {
                SearchResult candidate = costPatch(
                        target, source, cx, cy, initialDx + ox, initialDy + oy);
                if (candidate.cost < best.cost) best = candidate;
            }
        }
        for (float step : new float[]{0.5f, 0.25f}) {
            SearchResult refined = best;
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    SearchResult candidate = costPatch(
                            target, source, cx, cy,
                            best.dx + ox * step, best.dy + oy * step);
                    if (candidate.cost < refined.cost) refined = candidate;
                }
            }
            best = refined;
        }
        return best;
    }

    private static SearchResult costPatch(
            Guide target, Guide source, float cx, float cy, float dx, float dy) {
        int centerX = Math.round(cx);
        int centerY = Math.round(cy);
        float cost = 0.0f;
        float texture = 0.0f;
        int samples = 0;
        for (int oy = -LOCAL_PATCH_RADIUS; oy <= LOCAL_PATCH_RADIUS; oy++) {
            int y = centerY + oy;
            if (y <= 1 || y >= target.height - 2) continue;
            for (int ox = -LOCAL_PATCH_RADIUS; ox <= LOCAL_PATCH_RADIUS; ox++) {
                int x = centerX + ox;
                if (x <= 1 || x >= target.width - 2) continue;
                int index = y * target.width + x;
                if (!target.valid[index]) continue;
                float sourceValue = source.sampleLog(x + dx, y + dy);
                if (!Float.isFinite(sourceValue)) continue;
                cost += Math.min(2.0f, Math.abs(target.log2[index] - sourceValue));
                float gx = Math.abs(target.log2[index + 1] - target.log2[index - 1]);
                float gy = Math.abs(target.log2[index + target.width] - target.log2[index - target.width]);
                texture += 0.5f * (gx + gy);
                samples++;
            }
        }
        if (samples < 12) return new SearchResult(dx, dy, 9.0f, samples, 0.0f);
        return new SearchResult(dx, dy, cost / samples, samples, texture / samples);
    }

    private static final class GpuRawRenderer implements AutoCloseable {
        private static final float[] POSITIONS = {
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        private static final float[] FULL_UVS = {
                0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

        private final Context context;
        private final FloatBuffer positionBuffer = directFloatBuffer(POSITIONS);
        private final FloatBuffer uvBuffer = directFloatBuffer(FULL_UVS);
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private int fusionProgram;
        private int demosaicProgram;
        private int displayProgram;
        private int shortRawTexture;
        private int longRawTexture;
        private int shortShadingTexture;
        private int longShadingTexture;
        private int flowTexture;
        private int photometricTexture;
        private int fusedCfaTexture;
        private int linearRgbTexture;
        private int displayTexture;
        private int framebuffer;

        GpuRawRenderer(Context context) {
            this.context = context.getApplicationContext();
            createEgl();
            createGlObjects();
        }

        Bitmap render(
                SensorModel shortModel,
                SensorModel longModel,
                Rect active,
                AlignmentField alignment,
                float exposureRatio,
                PhotometricField photometric) {
            int width = active.width();
            int height = active.height();
            int maxSourceRows = Math.min(
                    longModel.raw.height,
                    TILE_ROWS + 2 * (FLOW_MARGIN_RAW + DEMOSAIC_HALO));
            int maxFusedRows = Math.min(height, TILE_ROWS + 2 * DEMOSAIC_HALO);
            int[] maxTexture = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0);
            if (Math.max(longModel.raw.width, width) > maxTexture[0]
                    || maxSourceRows > maxTexture[0]
                    || maxFusedRows > maxTexture[0]) {
                throw new IllegalStateException(
                        "RAW HDR exceeds GPU texture limit max=" + maxTexture[0]);
            }

            allocateR16Ui(shortRawTexture, longModel.raw.width, maxSourceRows);
            allocateR16Ui(longRawTexture, longModel.raw.width, maxSourceRows);
            // V1.5.1 keeps physical highlight provenance next to each fused CFA sample:
            // R=fused CFA, G=physical color trust, B=LONG clip risk, A=SHORT ownership.
            allocateRgba16f(fusedCfaTexture, width, maxFusedRows);
            allocateRgba16f(linearRgbTexture, width, TILE_ROWS);
            allocateRgba8(displayTexture, width, TILE_ROWS);
            uploadRgba32f(shortShadingTexture,
                    shortModel.shadingCols, shortModel.shadingRows, shortModel.lensShading);
            uploadRgba32f(longShadingTexture,
                    longModel.shadingCols, longModel.shadingRows, longModel.lensShading);
            uploadRgba32f(flowTexture, FLOW_WIDTH, FLOW_HEIGHT, alignment.rgba);
            uploadRgba32f(photometricTexture, 1, PHOTO_FIELD_HEIGHT, photometric.rgba);
            checkGl("RAW HDR texture allocation");
            // Fail closed before processing if this GLES implementation cannot render
            // the required half-float intermediates. There is deliberately no JPEG
            // or reduced-precision fallback because either would change HDR authority.
            attachTexture(fusedCfaTexture);
            attachTexture(linearRgbTexture);
            attachTexture(displayTexture);
            String glVersion = GLES30.glGetString(GLES30.GL_VERSION);
            RuntimeLogger.event(
                    "RAW_HDR_GL_CAPS",
                    "version=" + (glVersion == null ? "?" : glVersion)
                            + " halfFloatFbo=PASS");

            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] outputPixels = new int[width * TILE_ROWS];
            ByteBuffer readback = ByteBuffer.allocateDirect(width * TILE_ROWS * 4)
                    .order(ByteOrder.nativeOrder());
            ByteBuffer shortTile = ByteBuffer.allocateDirect(
                    longModel.raw.width * maxSourceRows * 2).order(ByteOrder.nativeOrder());
            ByteBuffer longTile = ByteBuffer.allocateDirect(
                    longModel.raw.width * maxSourceRows * 2).order(ByteOrder.nativeOrder());

            int tiles = 0;
            for (int y = active.top; y < active.bottom; y += TILE_ROWS) {
                int rows = Math.min(TILE_ROWS, active.bottom - y);
                int fusedStart = Math.max(active.top, y - DEMOSAIC_HALO);
                int fusedEnd = Math.min(active.bottom, y + rows + DEMOSAIC_HALO);
                int fusedRows = fusedEnd - fusedStart;
                int sourceStart = Math.max(0, fusedStart - FLOW_MARGIN_RAW);
                int sourceEnd = Math.min(longModel.raw.height, fusedEnd + FLOW_MARGIN_RAW);
                int sourceRows = sourceEnd - sourceStart;

                fillRawTile(shortModel.raw, sourceStart, sourceRows, maxSourceRows, shortTile);
                fillRawTile(longModel.raw, sourceStart, sourceRows, maxSourceRows, longTile);
                uploadR16Ui(shortRawTexture, longModel.raw.width, maxSourceRows, shortTile);
                uploadR16Ui(longRawTexture, longModel.raw.width, maxSourceRows, longTile);

                attachTexture(fusedCfaTexture);
                GLES30.glViewport(0, 0, width, fusedRows);
                GLES30.glUseProgram(fusionProgram);
                bindQuad(fusionProgram);
                bindUnsignedSampler(fusionProgram, "shortRawTex", shortRawTexture, 0);
                bindUnsignedSampler(fusionProgram, "longRawTex", longRawTexture, 1);
                bindSampler(fusionProgram, "shortShadingTex", shortShadingTexture, 2);
                bindSampler(fusionProgram, "longShadingTex", longShadingTexture, 3);
                bindSampler(fusionProgram, "flowTex", flowTexture, 4);
                bindSampler(fusionProgram, "photometricTex", photometricTexture, 5);
                GLES30.glUniform1i(location(fusionProgram, "cfaPattern"), longModel.cfaPattern);
                GLES30.glUniform1i(location(fusionProgram, "rawTileOriginY"), sourceStart);
                GLES30.glUniform1i(location(fusionProgram, "fusedGlobalStartY"), fusedStart);
                GLES30.glUniform2i(location(fusionProgram, "rawSize"),
                        longModel.raw.width, longModel.raw.height);
                GLES30.glUniform4i(location(fusionProgram, "activeArray"),
                        active.left, active.top, active.right, active.bottom);
                GLES30.glUniform4i(location(fusionProgram, "sensorActiveArray"),
                        longModel.activeArray.left, longModel.activeArray.top,
                        longModel.activeArray.right, longModel.activeArray.bottom);
                uniformVec4(fusionProgram, "shortBlackPhase", shortModel.blackByChannel);
                uniformVec4(fusionProgram, "longBlackPhase", longModel.blackByChannel);
                GLES30.glUniform1f(location(fusionProgram, "shortWhiteLevel"), shortModel.whiteLevel);
                GLES30.glUniform1f(location(fusionProgram, "longWhiteLevel"), longModel.whiteLevel);
                GLES30.glUniform1f(location(fusionProgram, "exposureRatio"), exposureRatio);
                GLES30.glUniform1f(
                        location(fusionProgram, "shortResidualScale"), photometric.globalScale);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                checkGl("RAW fusion tile " + tiles);

                attachTexture(linearRgbTexture);
                GLES30.glViewport(0, 0, width, rows);
                GLES30.glUseProgram(demosaicProgram);
                bindQuad(demosaicProgram);
                bindSampler(demosaicProgram, "fusedCfaTex", fusedCfaTexture, 0);
                GLES30.glUniform1i(location(demosaicProgram, "cfaPattern"), longModel.cfaPattern);
                GLES30.glUniform2i(location(demosaicProgram, "fusedTextureSize"), width, fusedRows);
                GLES30.glUniform2i(location(demosaicProgram, "fusedGlobalOrigin"), active.left, fusedStart);
                GLES30.glUniform2i(location(demosaicProgram, "outputGlobalOrigin"), active.left, y);
                GLES30.glUniform4i(location(demosaicProgram, "activeArray"),
                        active.left, active.top, active.right, active.bottom);
                uniformVec4(demosaicProgram, "whiteBalanceGains", longModel.wbGains);
                GLES30.glUniform3f(location(demosaicProgram, "colorRow0"),
                        longModel.colorMatrix[0], longModel.colorMatrix[1], longModel.colorMatrix[2]);
                GLES30.glUniform3f(location(demosaicProgram, "colorRow1"),
                        longModel.colorMatrix[3], longModel.colorMatrix[4], longModel.colorMatrix[5]);
                GLES30.glUniform3f(location(demosaicProgram, "colorRow2"),
                        longModel.colorMatrix[6], longModel.colorMatrix[7], longModel.colorMatrix[8]);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                checkGl("RAW demosaic tile " + tiles);

                attachTexture(displayTexture);
                GLES30.glViewport(0, 0, width, rows);
                GLES30.glUseProgram(displayProgram);
                setTileUvs(0.0f, rows / (float) TILE_ROWS);
                bindQuad(displayProgram);
                bindSampler(displayProgram, "normalTex", linearRgbTexture, 0);
                GLES30.glUniform1i(location(displayProgram, "mode"), 3);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                checkGl("RAW GTM tile " + tiles);

                int count = width * rows;
                readback.clear();
                GLES30.glReadPixels(
                        0, 0, width, rows,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readback);
                checkGl("RAW readback tile " + tiles);
                readback.rewind();
                IntBuffer ints = readback.asIntBuffer();
                ints.get(outputPixels, 0, count);
                rgbaNativeToArgb(outputPixels, count);
                output.setPixels(outputPixels, 0, width, 0, y - active.top, width, rows);
                tiles++;
            }
            RuntimeLogger.event(
                    "RAW_HDR_GPU_TILES",
                    "tiles=" + tiles + " tileRows=" + TILE_ROWS
                            + " flow=" + FLOW_WIDTH + "x" + FLOW_HEIGHT
                            + " glMaxTexture=" + maxTexture[0]);
            return output;
        }

        private void createEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("eglGetDisplay failed");
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
                throw new IllegalStateException("eglInitialize failed 0x" + Integer.toHexString(EGL14.eglGetError()));
            }
            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                throw new IllegalStateException("eglBindAPI failed");
            }
            int[] configAttributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(
                    eglDisplay, configAttributes, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] < 1) {
                throw new IllegalStateException("No GLES3 pbuffer EGL config");
            }
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(
                    eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new IllegalStateException("eglCreateContext failed");
            }
            int[] surfaceAttributes = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay, configs[0], surfaceAttributes, 0);
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreatePbufferSurface failed");
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new IllegalStateException("eglMakeCurrent failed");
            }
        }

        private void createGlObjects() {
            String vertex = loadAsset(context, "shaders/fullscreen.vert");
            fusionProgram = buildProgram(vertex, loadAsset(context, "shaders/raw_hdr_fusion.frag"));
            demosaicProgram = buildProgram(vertex, loadAsset(context, "shaders/raw_hdr_demosaic.frag"));
            displayProgram = buildProgram(vertex, loadAsset(context, "shaders/hdr_display.frag"));
            int[] textures = new int[9];
            GLES30.glGenTextures(textures.length, textures, 0);
            shortRawTexture = textures[0];
            longRawTexture = textures[1];
            shortShadingTexture = textures[2];
            longShadingTexture = textures[3];
            flowTexture = textures[4];
            photometricTexture = textures[5];
            fusedCfaTexture = textures[6];
            linearRgbTexture = textures[7];
            displayTexture = textures[8];
            int[] framebuffers = new int[1];
            GLES30.glGenFramebuffers(1, framebuffers, 0);
            framebuffer = framebuffers[0];
        }

        private void allocateR16Ui(int texture, int width, int height) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            textureParameters(GLES30.GL_NEAREST);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI,
                    width, height, 0, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, null);
        }

        private void uploadR16Ui(int texture, int width, int height, ByteBuffer pixels) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            pixels.rewind();
            GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, pixels);
        }

        private void allocateR16f(int texture, int width, int height) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            textureParameters(GLES30.GL_NEAREST);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F,
                    width, height, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null);
        }

        private void allocateRgba16f(int texture, int width, int height) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            textureParameters(GLES30.GL_NEAREST);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                    width, height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null);
        }

        private void allocateRgba8(int texture, int width, int height) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            textureParameters(GLES30.GL_LINEAR);
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                    width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        }

        private void uploadRgba32f(int texture, int width, int height, float[] values) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            // Explicit shader bilinear interpolation avoids requiring float-linear
            // texture-filtering extensions on otherwise GLES3-capable devices.
            textureParameters(GLES30.GL_NEAREST);
            FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(values).flip();
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
                    width, height, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer);
        }

        private static void fillRawTile(
                RawBuffer raw, int startRow, int rows, int maxRows, ByteBuffer destination) {
            destination.clear();
            int rowBytes = raw.width * 2;
            for (int row = 0; row < rows; row++) {
                int offset = (startRow + row) * rowBytes;
                destination.put(raw.packed16, offset, rowBytes);
            }
            int lastOffset = Math.max(0, (startRow + Math.max(0, rows - 1)) * rowBytes);
            for (int row = rows; row < maxRows; row++) {
                destination.put(raw.packed16, lastOffset, rowBytes);
            }
            destination.flip();
        }

        private void attachTexture(int texture) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, texture, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("RAW HDR framebuffer incomplete 0x"
                        + Integer.toHexString(status));
            }
        }

        private void setTileUvs(float v0, float v1) {
            uvBuffer.clear();
            uvBuffer.put(new float[]{0f, v0, 1f, v0, 0f, v1, 1f, v1}).flip();
        }

        private void bindQuad(int program) {
            GLES30.glUseProgram(program);
            positionBuffer.position(0);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, positionBuffer);
            uvBuffer.position(0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, uvBuffer);
        }

        private static void bindSampler(int program, String name, int texture, int unit) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glUniform1i(location(program, name), unit);
        }

        private static void bindUnsignedSampler(int program, String name, int texture, int unit) {
            bindSampler(program, name, texture, unit);
        }

        private static void uniformVec4(int program, String name, float[] values) {
            GLES30.glUniform4f(location(program, name),
                    values[0], values[1], values[2], values[3]);
        }

        private static void textureParameters(int filter) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        }

        private static int location(int program, String name) {
            return GLES30.glGetUniformLocation(program, name);
        }

        private static int buildProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vertex);
            GLES30.glAttachShader(program, fragment);
            GLES30.glLinkProgram(program);
            int[] status = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
            GLES30.glDeleteShader(vertex);
            GLES30.glDeleteShader(fragment);
            if (status[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(program);
                GLES30.glDeleteProgram(program);
                throw new IllegalStateException("RAW HDR GL link failed: " + log);
            }
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader, source);
            GLES30.glCompileShader(shader);
            int[] status = new int[1];
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES30.glGetShaderInfoLog(shader);
                GLES30.glDeleteShader(shader);
                throw new IllegalStateException("RAW HDR GL compile failed: " + log);
            }
            return shader;
        }

        private static String loadAsset(Context context, String path) {
            try (InputStream input = context.getAssets().open(path);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                throw new IllegalStateException("Unable to load shader asset " + path, e);
            }
        }

        private static FloatBuffer directFloatBuffer(float[] values) {
            FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(values).flip();
            return buffer;
        }

        private static void rgbaNativeToArgb(int[] pixels, int count) {
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                for (int i = 0; i < count; i++) {
                    int rgba = pixels[i];
                    pixels[i] = (rgba & 0xFF00FF00)
                            | ((rgba & 0x00FF0000) >>> 16)
                            | ((rgba & 0x000000FF) << 16);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    int rgba = pixels[i];
                    pixels[i] = ((rgba & 0x000000FF) << 24)
                            | ((rgba & 0xFF000000) >>> 8)
                            | ((rgba & 0x00FF0000) >>> 8)
                            | ((rgba & 0x0000FF00) >>> 8);
                }
            }
        }

        private static void checkGl(String stage) {
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                throw new IllegalStateException(stage + " GL error 0x" + Integer.toHexString(error));
            }
        }

        @Override
        public void close() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                int[] textures = {
                        shortRawTexture, longRawTexture, shortShadingTexture, longShadingTexture,
                        flowTexture, photometricTexture, fusedCfaTexture, linearRgbTexture, displayTexture};
                GLES30.glDeleteTextures(textures.length, textures, 0);
                if (framebuffer != 0) {
                    int[] fb = {framebuffer};
                    GLES30.glDeleteFramebuffers(1, fb, 0);
                }
                if (fusionProgram != 0) GLES30.glDeleteProgram(fusionProgram);
                if (demosaicProgram != 0) GLES30.glDeleteProgram(demosaicProgram);
                if (displayProgram != 0) GLES30.glDeleteProgram(displayProgram);
                EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglReleaseThread();
            eglSurface = EGL14.EGL_NO_SURFACE;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private static int channelAt(int cfaPattern, int x, int y) {
        int px = x & 1;
        int py = y & 1;
        if (cfaPattern == 0) {
            return py == 0 ? (px == 0 ? 0 : 1) : (px == 0 ? 2 : 3);
        }
        if (cfaPattern == 1) {
            return py == 0 ? (px == 0 ? 1 : 0) : (px == 0 ? 3 : 2);
        }
        if (cfaPattern == 2) {
            return py == 0 ? (px == 0 ? 1 : 3) : (px == 0 ? 0 : 2);
        }
        return py == 0 ? (px == 0 ? 3 : 1) : (px == 0 ? 2 : 0);
    }

    private static float medianSorted(float[] values, int count) {
        int mid = count / 2;
        if ((count & 1) != 0) return values[mid];
        return 0.5f * (values[mid - 1] + values[mid]);
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2.0));
    }

    private static float smoothstep(float low, float high, float value) {
        float t = clamp((value - low) / (high - low), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float max(float[] values) {
        float result = -Float.MAX_VALUE;
        for (float value : values) result = Math.max(result, value);
        return result;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static int clampInt(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static long elapsedMs(long startNs) {
        return Math.round((System.nanoTime() - startNs) / 1_000_000.0);
    }
}
