#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D packedRawTex;
uniform sampler2D greenTex;
uniform int cfaArrangement;
uniform vec4 wbGains;
uniform vec3 colorRow0;
uniform vec3 colorRow1;
uniform vec3 colorRow2;

const float SIGNAL_COMPAND_K = 1.0;
const float SIGMA_COMPAND_K = 0.01;
const float CARRIER_MAX = 254.0 / 255.0;

int patternIndex(ivec2 p) {
    return ((p.y & 1) << 1) | (p.x & 1);
}

int colorAt(ivec2 p) {
    int i = patternIndex(p);
    if (cfaArrangement == 0) {
        if (i == 0) return 0;
        if (i == 3) return 2;
        return 1;
    }
    if (cfaArrangement == 1) {
        if (i == 1) return 0;
        if (i == 2) return 2;
        return 1;
    }
    if (cfaArrangement == 2) {
        if (i == 2) return 0;
        if (i == 1) return 2;
        return 1;
    }
    if (i == 3) return 0;
    if (i == 0) return 2;
    return 1;
}

ivec2 clampPixel(ivec2 p) {
    ivec2 size = textureSize(packedRawTex, 0);
    return clamp(p, ivec2(0), size - ivec2(1));
}

float expandPositive(float encoded, float k) {
    float e = min(max(encoded, 0.0), 0.9999847);
    float e2 = e * e;
    return k * e2 / max(1.0 - e2, 0.0000001);
}

float compandPositive(float value, float k) {
    float x = max(value, 0.0);
    return min(CARRIER_MAX, sqrt(x / max(x + k, 0.0000001)));
}

float unpack16Code(vec2 packed) {
    float highByte = floor(packed.x * 255.0 + 0.5);
    float lowByte = floor(packed.y * 255.0 + 0.5);
    return highByte * 256.0 + lowByte;
}

vec2 rawMeasurementAt(ivec2 p) {
    vec4 packed = texelFetch(packedRawTex, clampPixel(p), 0);
    float sigmaAndSat = unpack16Code(packed.ba);
    float saturation = step(32767.5, sigmaAndSat);
    float sigmaCode = sigmaAndSat - 32768.0 * saturation;
    return vec2(
        expandPositive(unpack16Code(packed.rg) / 65535.0, SIGNAL_COMPAND_K),
        expandPositive(sigmaCode / 32767.0, SIGMA_COMPAND_K));
}

float rawSaturationAt(ivec2 p) {
    vec4 packed = texelFetch(packedRawTex, clampPixel(p), 0);
    return step(32767.5, unpack16Code(packed.ba));
}

vec2 greenAt(ivec2 p) {
    vec4 packed = texelFetch(greenTex, clampPixel(p), 0);
    return vec2(
        expandPositive(unpack16Code(packed.rg) / 65535.0, SIGNAL_COMPAND_K),
        expandPositive(unpack16Code(packed.ba) / 65535.0, SIGMA_COMPAND_K));
}

float localRawSaturationAt(ivec2 p) {
    float saturation = 0.0;
    for (int oy = -1; oy <= 1; ++oy) {
        for (int ox = -1; ox <= 1; ++ox) {
            saturation = max(saturation, rawSaturationAt(p + ivec2(ox, oy)));
        }
    }
    return saturation;
}

vec2 colorDifferenceAt(ivec2 p) {
    vec2 colorValue = rawMeasurementAt(p);
    vec2 greenValue = greenAt(p);
    return vec2(
        colorValue.x - greenValue.x,
        sqrt(colorValue.y * colorValue.y + greenValue.y * greenValue.y));
}

float median4(float a, float b, float c, float d) {
    float values[4] = float[4](a, b, c, d);
    for (int i = 0; i < 3; ++i) {
        for (int j = i + 1; j < 4; ++j) {
            float lowValue = min(values[i], values[j]);
            float highValue = max(values[i], values[j]);
            values[i] = lowValue;
            values[j] = highValue;
        }
    }
    return 0.5 * (values[1] + values[2]);
}

vec2 robustFourDifferences(
        vec2 d0, vec2 d1, vec2 d2, vec2 d3,
        float spatial0, float spatial1, float spatial2, float spatial3) {
    float centerDifference = median4(d0.x, d1.x, d2.x, d3.x);
    vec2 values[4] = vec2[4](d0, d1, d2, d3);
    float spatial[4] = float[4](spatial0, spatial1, spatial2, spatial3);
    float weightedValue = 0.0;
    float weightSum = 0.0;
    float varianceSum = 0.0;
    for (int i = 0; i < 4; ++i) {
        float sigmaValue = max(values[i].y, 0.00005);
        float deviation = abs(values[i].x - centerDifference);
        float normalizedDeviation = deviation / max(2.5 * sigmaValue, 0.00020);
        float consistency = 1.0 / (1.0 + normalizedDeviation * normalizedDeviation);
        float inverseVariance = 1.0 / (sigmaValue * sigmaValue + 0.00000002);
        float weightValue = spatial[i] * consistency * inverseVariance;
        weightedValue += values[i].x * weightValue;
        weightSum += weightValue;
        varianceSum += sigmaValue * sigmaValue * weightValue * weightValue;
    }
    float resultValue = weightSum > 0.0 ? weightedValue / weightSum : centerDifference;
    float resultSigma = weightSum > 0.0
        ? sqrt(varianceSum) / weightSum
        : max(max(d0.y, d1.y), max(d2.y, d3.y));
    return vec2(resultValue, max(resultSigma, 0.000001));
}

vec2 diagonalDifference(ivec2 q) {
    vec2 d0 = colorDifferenceAt(q + ivec2(-1, -1));
    vec2 d1 = colorDifferenceAt(q + ivec2(1, -1));
    vec2 d2 = colorDifferenceAt(q + ivec2(-1, 1));
    vec2 d3 = colorDifferenceAt(q + ivec2(1, 1));
    return robustFourDifferences(d0, d1, d2, d3, 1.0, 1.0, 1.0, 1.0);
}

vec2 axisDifference(ivec2 q, ivec2 axis) {
    vec2 nearNegative = colorDifferenceAt(q - axis);
    vec2 nearPositive = colorDifferenceAt(q + axis);
    vec2 farNegative = colorDifferenceAt(q - 3 * axis);
    vec2 farPositive = colorDifferenceAt(q + 3 * axis);
    return robustFourDifferences(
        nearNegative, nearPositive, farNegative, farPositive,
        1.0, 1.0, 0.35, 0.35);
}

void demosaicSensor(ivec2 p, out vec3 sensorRgb, out vec3 sensorSigma) {
    ivec2 q = clampPixel(p);
    int centerColor = colorAt(q);
    vec2 greenValue = greenAt(q);

    if (centerColor == 0) {
        vec2 redValue = rawMeasurementAt(q);
        vec2 blueDifference = diagonalDifference(q);
        float blue = greenValue.x + blueDifference.x;
        sensorRgb = vec3(redValue.x, greenValue.x, blue);
        sensorSigma = vec3(
            redValue.y,
            greenValue.y,
            sqrt(greenValue.y * greenValue.y + blueDifference.y * blueDifference.y));
        return;
    }
    if (centerColor == 2) {
        vec2 blueValue = rawMeasurementAt(q);
        vec2 redDifference = diagonalDifference(q);
        float red = greenValue.x + redDifference.x;
        sensorRgb = vec3(red, greenValue.x, blueValue.x);
        sensorSigma = vec3(
            sqrt(greenValue.y * greenValue.y + redDifference.y * redDifference.y),
            greenValue.y,
            blueValue.y);
        return;
    }

    bool redHorizontal = colorAt(q + ivec2(1, 0)) == 0
        || colorAt(q + ivec2(-1, 0)) == 0;
    vec2 redDifference = axisDifference(q, redHorizontal ? ivec2(1, 0) : ivec2(0, 1));
    vec2 blueDifference = axisDifference(q, redHorizontal ? ivec2(0, 1) : ivec2(1, 0));
    float red = greenValue.x + redDifference.x;
    float blue = greenValue.x + blueDifference.x;
    sensorRgb = vec3(red, greenValue.x, blue);
    sensorSigma = vec3(
        sqrt(greenValue.y * greenValue.y + redDifference.y * redDifference.y),
        greenValue.y,
        sqrt(greenValue.y * greenValue.y + blueDifference.y * blueDifference.y));
}

float linearLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float min3(vec3 value) {
    return min(value.r, min(value.g, value.b));
}

vec3 projectNonNegativeAtFixedLuma(vec3 rgb) {
    float y = max(linearLuma(rgb), 0.0);
    vec3 neutral = vec3(y);
    float lowValue = min3(rgb);
    if (lowValue < 0.0) {
        float scale = clamp(y / max(y - lowValue, 0.000001), 0.0, 1.0);
        rgb = mix(neutral, rgb, scale);
    }
    // No upper gamut projection here. >1 scene energy stays available to the final HDR
    // owner instead of becoming a pre-fusion display clamp/false-color boundary.
    return max(rgb, vec3(0.0));
}

float transformedSigma(vec3 rowValue, vec3 sigmaValue) {
    vec3 weighted = rowValue * sigmaValue;
    return sqrt(dot(weighted, weighted));
}

float encodeSigmaAndSaturation(float sigma, float saturation) {
    float sigmaEncoded = compandPositive(sigma, SIGMA_COMPAND_K);
    float sigmaCode = min(127.0,
        floor(clamp(sigmaEncoded, 0.0, 1.0) * 127.0 + 0.5));
    return (sigmaCode + 128.0 * step(0.5, saturation)) / 255.0;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 sensorRgb;
    vec3 sensorSigma;
    demosaicSensor(p, sensorRgb, sensorSigma);
    sensorRgb = max(sensorRgb, vec3(0.0));

    float greenGain = 0.5 * (wbGains.y + wbGains.z);
    vec3 balanceGains = vec3(wbGains.x, greenGain, wbGains.w);
    vec3 balancedRgb = sensorRgb * balanceGains;
    vec3 balancedSigma = sensorSigma * abs(balanceGains);

    vec3 linearRgb = vec3(
        dot(colorRow0, balancedRgb),
        dot(colorRow1, balancedRgb),
        dot(colorRow2, balancedRgb));
    vec3 transformedNoise = vec3(
        transformedSigma(colorRow0, balancedSigma),
        transformedSigma(colorRow1, balancedSigma),
        transformedSigma(colorRow2, balancedSigma));
    linearRgb = projectNonNegativeAtFixedLuma(linearRgb);
    float sceneSigma = max(transformedNoise.r, max(transformedNoise.g, transformedNoise.b));
    float saturationEvidence = localRawSaturationAt(p);

    vec3 encodedScene = vec3(
        compandPositive(linearRgb.r, SIGNAL_COMPAND_K),
        compandPositive(linearRgb.g, SIGNAL_COMPAND_K),
        compandPositive(linearRgb.b, SIGNAL_COMPAND_K));
    outColor = vec4(encodedScene,
        encodeSigmaAndSaturation(sceneSigma, saturationEvidence));
}
