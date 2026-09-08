#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D packedRawTex;
uniform int cfaArrangement;
uniform vec4 wbGains;
uniform vec3 colorRow0;
uniform vec3 colorRow1;
uniform vec3 colorRow2;

int patternIndex(ivec2 p) {
    return ((p.y & 1) << 1) | (p.x & 1);
}

int colorAt(ivec2 p) {
    int i = patternIndex(p);
    if (cfaArrangement == 0) { // RGGB
        if (i == 0) return 0;
        if (i == 3) return 2;
        return 1;
    }
    if (cfaArrangement == 1) { // GRBG
        if (i == 1) return 0;
        if (i == 2) return 2;
        return 1;
    }
    if (cfaArrangement == 2) { // GBRG
        if (i == 2) return 0;
        if (i == 1) return 2;
        return 1;
    }
    // BGGR
    if (i == 3) return 0;
    if (i == 0) return 2;
    return 1;
}

ivec2 clampPixel(ivec2 p) {
    ivec2 size = textureSize(packedRawTex, 0);
    return clamp(p, ivec2(0), size - ivec2(1));
}

float rawSignalAt(ivec2 p) {
    vec2 packed = texelFetch(packedRawTex, clampPixel(p), 0).rg;
    float highByte = floor(packed.r * 255.0 + 0.5);
    float lowByte = floor(packed.g * 255.0 + 0.5);
    return (highByte * 256.0 + lowByte) / 65535.0;
}

vec3 demosaicSensor(ivec2 p) {
    ivec2 q = clampPixel(p);
    int centerColor = colorAt(q);
    float center = rawSignalAt(q);

    if (centerColor == 0 || centerColor == 2) {
        float left = rawSignalAt(q + ivec2(-1, 0));
        float right = rawSignalAt(q + ivec2(1, 0));
        float up = rawSignalAt(q + ivec2(0, -1));
        float down = rawSignalAt(q + ivec2(0, 1));
        float gradH = abs(left - right);
        float gradV = abs(up - down);
        float weightH = 1.0 / (0.00002 + gradH * gradH);
        float weightV = 1.0 / (0.00002 + gradV * gradV);
        float green = clamp(
            (0.5 * (left + right) * weightH + 0.5 * (up + down) * weightV)
                / (weightH + weightV),
            0.0, 1.0);

        float nw = rawSignalAt(q + ivec2(-1, -1));
        float ne = rawSignalAt(q + ivec2(1, -1));
        float sw = rawSignalAt(q + ivec2(-1, 1));
        float se = rawSignalAt(q + ivec2(1, 1));
        float gradD1 = abs(nw - se);
        float gradD2 = abs(ne - sw);
        float weightD1 = 1.0 / (0.00002 + gradD1 * gradD1);
        float weightD2 = 1.0 / (0.00002 + gradD2 * gradD2);
        float opposite = clamp(
            (0.5 * (nw + se) * weightD1 + 0.5 * (ne + sw) * weightD2)
                / (weightD1 + weightD2),
            0.0, 1.0);
        return centerColor == 0
            ? vec3(center, green, opposite)
            : vec3(opposite, green, center);
    }

    // At a native green sample, reconstruct R/B as color differences against nearby
    // green support rather than plain RGB bilinear interpolation. The required
    // direction is fixed by CFA phase; the +/-2 green samples stabilize real edges.
    bool redHorizontal = colorAt(q + ivec2(1, 0)) == 0
        || colorAt(q + ivec2(-1, 0)) == 0;
    float red;
    float blue;
    if (redHorizontal) {
        float redL = rawSignalAt(q + ivec2(-1, 0));
        float redR = rawSignalAt(q + ivec2(1, 0));
        float greenL2 = rawSignalAt(q + ivec2(-2, 0));
        float greenR2 = rawSignalAt(q + ivec2(2, 0));
        float blueU = rawSignalAt(q + ivec2(0, -1));
        float blueD = rawSignalAt(q + ivec2(0, 1));
        float greenU2 = rawSignalAt(q + ivec2(0, -2));
        float greenD2 = rawSignalAt(q + ivec2(0, 2));
        red = center + 0.5 * (
            redL - 0.5 * (center + greenL2)
            + redR - 0.5 * (center + greenR2));
        blue = center + 0.5 * (
            blueU - 0.5 * (center + greenU2)
            + blueD - 0.5 * (center + greenD2));
    } else {
        float redU = rawSignalAt(q + ivec2(0, -1));
        float redD = rawSignalAt(q + ivec2(0, 1));
        float greenU2 = rawSignalAt(q + ivec2(0, -2));
        float greenD2 = rawSignalAt(q + ivec2(0, 2));
        float blueL = rawSignalAt(q + ivec2(-1, 0));
        float blueR = rawSignalAt(q + ivec2(1, 0));
        float greenL2 = rawSignalAt(q + ivec2(-2, 0));
        float greenR2 = rawSignalAt(q + ivec2(2, 0));
        red = center + 0.5 * (
            redU - 0.5 * (center + greenU2)
            + redD - 0.5 * (center + greenD2));
        blue = center + 0.5 * (
            blueL - 0.5 * (center + greenL2)
            + blueR - 0.5 * (center + greenR2));
    }
    return clamp(vec3(red, center, blue), vec3(0.0), vec3(1.0));
}

float linearLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float min3(vec3 value) {
    return min(value.r, min(value.g, value.b));
}

float max3(vec3 value) {
    return max(value.r, max(value.g, value.b));
}

vec3 projectToUnitGamut(vec3 rgb) {
    // Whole-RGB, same-luminance projection prevents a single transformed channel
    // from clipping first and creating pink/green rims at bright high-contrast edges.
    float y = clamp(linearLuma(rgb), 0.0, 1.0);
    vec3 neutral = vec3(y);
    float low = min3(rgb);
    if (low < 0.0) {
        float t = clamp(y / max(y - low, 0.000001), 0.0, 1.0);
        rgb = mix(neutral, rgb, t);
    }
    float high = max3(rgb);
    if (high > 1.0) {
        float t = clamp((1.0 - y) / max(high - y, 0.000001), 0.0, 1.0);
        rgb = mix(neutral, rgb, t);
    }
    return clamp(rgb, vec3(0.0), vec3(1.0));
}

float linearToSrgbChannel(float value) {
    float x = max(value, 0.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 sensorRgb = demosaicSensor(p);
    float greenGain = 0.5 * (wbGains.y + wbGains.z);
    // WB follows structure reconstruction. LONG's matched gains/matrix remain the
    // common color owner for both SHORT/LONG observations.
    vec3 balancedRgb = sensorRgb * vec3(wbGains.x, greenGain, wbGains.w);
    vec3 linearSrgb = vec3(
        dot(colorRow0, balancedRgb),
        dot(colorRow1, balancedRgb),
        dot(colorRow2, balancedRgb));
    linearSrgb = projectToUnitGamut(linearSrgb);
    vec3 encoded = vec3(
        linearToSrgbChannel(linearSrgb.r),
        linearToSrgbChannel(linearSrgb.g),
        linearToSrgbChannel(linearSrgb.b));
    outColor = vec4(clamp(encoded, vec3(0.0), vec3(1.0)), 1.0);
}
