#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D packedRawTex;
uniform int cfaArrangement;

const float SIGNAL_COMPAND_K = 1.0;
const float SIGMA_COMPAND_K = 0.01;
const float PACK_DENOM = 65535.0;
const float PACK_MAX_CODE = 65534.0;

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
    return sqrt(x / max(x + k, 0.0000001));
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

vec2 greenAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    if (colorAt(q) == 1) return rawMeasurementAt(q);

    vec2 leftValue = rawMeasurementAt(q + ivec2(-1, 0));
    vec2 rightValue = rawMeasurementAt(q + ivec2(1, 0));
    vec2 upValue = rawMeasurementAt(q + ivec2(0, -1));
    vec2 downValue = rawMeasurementAt(q + ivec2(0, 1));

    float horizontal = 0.5 * (leftValue.x + rightValue.x);
    float vertical = 0.5 * (upValue.x + downValue.x);
    float horizontalSigma = 0.5 * sqrt(
        leftValue.y * leftValue.y + rightValue.y * rightValue.y);
    float verticalSigma = 0.5 * sqrt(
        upValue.y * upValue.y + downValue.y * downValue.y);

    float horizontalNoise = sqrt(
        leftValue.y * leftValue.y + rightValue.y * rightValue.y + 0.00000001);
    float verticalNoise = sqrt(
        upValue.y * upValue.y + downValue.y * downValue.y + 0.00000001);
    float horizontalGradient = abs(leftValue.x - rightValue.x)
        / max(horizontalNoise, 0.00010);
    float verticalGradient = abs(upValue.x - downValue.x)
        / max(verticalNoise, 0.00010);

    float horizontalWeight = 1.0 / (1.0 + horizontalGradient * horizontalGradient);
    float verticalWeight = 1.0 / (1.0 + verticalGradient * verticalGradient);
    float weightSum = horizontalWeight + verticalWeight;
    float green = (horizontal * horizontalWeight + vertical * verticalWeight)
        / max(weightSum, 0.000001);
    float greenSigma = sqrt(
        horizontalWeight * horizontalWeight * horizontalSigma * horizontalSigma
        + verticalWeight * verticalWeight * verticalSigma * verticalSigma)
        / max(weightSum, 0.000001);
    return vec2(max(green, 0.0), max(greenSigma, 0.000001));
}

vec2 pack16(float encoded) {
    float code = min(PACK_MAX_CODE, floor(clamp(encoded, 0.0, 1.0) * PACK_DENOM + 0.5));
    float highByte = floor(code / 256.0);
    float lowByte = code - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

void main() {
    vec2 greenValue = greenAt(ivec2(gl_FragCoord.xy));
    vec2 greenPacked = pack16(compandPositive(greenValue.x, SIGNAL_COMPAND_K));
    vec2 sigmaPacked = pack16(compandPositive(greenValue.y, SIGMA_COMPAND_K));
    outColor = vec4(greenPacked, sigmaPacked);
}
