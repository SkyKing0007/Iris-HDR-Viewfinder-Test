package com.skyking0007.irishdrviewfinder;

import android.media.Image;

import java.nio.ByteBuffer;

final class YuvFrame {
    final int width;
    final int height;
    final long timestampNs;
    final ByteBuffer y;
    final ByteBuffer u;
    final ByteBuffer v;

    private YuvFrame(
            int width,
            int height,
            long timestampNs,
            ByteBuffer y,
            ByteBuffer u,
            ByteBuffer v) {
        this.width = width;
        this.height = height;
        this.timestampNs = timestampNs;
        this.y = y;
        this.u = u;
        this.v = v;
    }

    static YuvFrame fromImage(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            throw new IllegalArgumentException("Expected YUV_420_888 with three planes");
        }
        ByteBuffer y = packPlane(planes[0], width, height);
        ByteBuffer u = packPlane(planes[1], (width + 1) / 2, (height + 1) / 2);
        ByteBuffer v = packPlane(planes[2], (width + 1) / 2, (height + 1) / 2);
        return new YuvFrame(width, height, image.getTimestamp(), y, u, v);
    }

    private static ByteBuffer packPlane(Image.Plane plane, int width, int height) {
        ByteBuffer source = plane.getBuffer().duplicate();
        int base = source.position();
        int limit = source.limit();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        ByteBuffer out = ByteBuffer.allocateDirect(width * height);

        for (int row = 0; row < height; row++) {
            int rowBase = base + row * rowStride;
            for (int col = 0; col < width; col++) {
                int index = rowBase + col * pixelStride;
                out.put(index < limit ? source.get(index) : 0);
            }
        }
        out.flip();
        return out;
    }
}
