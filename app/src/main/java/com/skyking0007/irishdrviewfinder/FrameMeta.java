package com.skyking0007.irishdrviewfinder;

import android.graphics.Rect;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;

final class FrameMeta {
    static final String NORMAL = "NORMAL";
    static final String SHORT = "SHORT";
    static final String LONG = "LONG";
    static final String METER = "METER";

    final String kind;
    final long frameNumber;
    final long sensorTimestampNs;
    final long exposureTimeNs;
    final int iso;
    final long exposureGeneration;
    final boolean flickerGuardRequired;
    final boolean provisionalShortProbe;
    final String activePhysicalId;
    final Rect physicalSensorCropRegion;
    final RggbChannelVector colorGains;
    final ColorSpaceTransform colorTransform;

    FrameMeta(
            String kind, long frameNumber, long sensorTimestampNs,
            long exposureTimeNs, int iso, long exposureGeneration,
            boolean flickerGuardRequired, boolean provisionalShortProbe,
            String activePhysicalId, Rect physicalSensorCropRegion,
            RggbChannelVector colorGains, ColorSpaceTransform colorTransform) {
        this.kind = kind;
        this.frameNumber = frameNumber;
        this.sensorTimestampNs = sensorTimestampNs;
        this.exposureTimeNs = exposureTimeNs;
        this.iso = iso;
        this.exposureGeneration = exposureGeneration;
        this.flickerGuardRequired = flickerGuardRequired;
        this.provisionalShortProbe = provisionalShortProbe;
        this.activePhysicalId = activePhysicalId;
        this.physicalSensorCropRegion = physicalSensorCropRegion == null
                ? null : new Rect(physicalSensorCropRegion);
        this.colorGains = colorGains;
        this.colorTransform = colorTransform;
    }

    double exposureProduct() {
        return Math.max(1.0, (double) exposureTimeNs * Math.max(1, iso));
    }
}
