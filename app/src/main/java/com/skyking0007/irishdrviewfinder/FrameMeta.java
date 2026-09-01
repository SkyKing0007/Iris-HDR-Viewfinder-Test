package com.skyking0007.irishdrviewfinder;

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

    FrameMeta(
            String kind, long frameNumber, long sensorTimestampNs,
            long exposureTimeNs, int iso, long exposureGeneration) {
        this.kind = kind;
        this.frameNumber = frameNumber;
        this.sensorTimestampNs = sensorTimestampNs;
        this.exposureTimeNs = exposureTimeNs;
        this.iso = iso;
        this.exposureGeneration = exposureGeneration;
    }

    double exposureProduct() {
        return Math.max(1.0, (double) exposureTimeNs * Math.max(1, iso));
    }
}
