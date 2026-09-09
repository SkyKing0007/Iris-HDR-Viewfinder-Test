#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform highp usampler2D rawTex;
uniform highp sampler2D shadingTex;
uniform vec4 blackPatternCode;
uniform float whiteLevelCode;
uniform int cfaArrangement;
uniform float noiseSlope;
uniform float noiseOffset;

// V2.32 keeps the lens-shaded CFA signal and its physical standard deviation in a
// 32-bit RGBA8 packed carrier without imposing a scene-domain 1.0 ceiling. R/G hold
// a 16-bit positive-companded signal; B/A hold 15-bit sigma plus one saturation bit.
const float SIGNAL_COMPAND_K = 1.0;
const float SIGMA_COMPAND_K = 0.01;
const float PACK_DENOM = 65535.0;
const float PACK_MAX_CODE = 65534.0;
const float SIGMA_MAX_CODE = 32767.0;

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
    ivec2 size = textureSize(rawTex, 0);
    return clamp(p, ivec2(0), size - ivec2(1));
}

float blackAt(ivec2 p) {
    int i = patternIndex(p);
    if (i == 0) return blackPatternCode.x;
    if (i == 1) return blackPatternCode.y;
    if (i == 2) return blackPatternCode.z;
    return blackPatternCode.w;
}

vec4 shadingMapAt(ivec2 p) {
    ivec2 rawSize = textureSize(rawTex, 0);
    ivec2 mapSize = textureSize(shadingTex, 0);
    vec2 rawDenom = max(vec2(rawSize - ivec2(1)), vec2(1.0));
    vec2 mapMax = max(vec2(mapSize - ivec2(1)), vec2(0.0));
    vec2 mapPos = vec2(clampPixel(p)) / rawDenom * mapMax;
    ivec2 p0 = ivec2(floor(mapPos));
    ivec2 p1 = min(p0 + ivec2(1), mapSize - ivec2(1));
    vec2 f = fract(mapPos);
    vec4 a = mix(texelFetch(shadingTex, p0, 0),
                 texelFetch(shadingTex, ivec2(p1.x, p0.y), 0), f.x);
    vec4 b = mix(texelFetch(shadingTex, ivec2(p0.x, p1.y), 0),
                 texelFetch(shadingTex, p1, 0), f.x);
    return mix(a, b, f.y);
}

float shadingGainAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    vec4 gains = shadingMapAt(q);
    int color = colorAt(q);
    if (color == 0) return gains.x;
    if (color == 2) return gains.w;
    return (q.y & 1) == 0 ? gains.y : gains.z;
}

vec3 rawMeasurementAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    float code = float(texelFetch(rawTex, q, 0).r);
    float black = blackAt(q);
    float normalized = max(code - black, 0.0)
        / max(whiteLevelCode - black, 0.000001);
    float shadingGain = shadingGainAt(q);
    float signal = normalized * shadingGain;
    float variance = max(noiseSlope * normalized + noiseOffset, 0.0);
    float sigma = sqrt(variance) * shadingGain;
    float saturation = step(0.985, normalized);
    return vec3(signal, sigma, saturation);
}

float median5(float a, float b, float c, float d, float e) {
    float values[5] = float[5](a, b, c, d, e);
    for (int i = 0; i < 4; ++i) {
        for (int j = i + 1; j < 5; ++j) {
            float lowValue = min(values[i], values[j]);
            float highValue = max(values[i], values[j]);
            values[i] = lowValue;
            values[j] = highValue;
        }
    }
    return values[2];
}

// Correct only an isolated same-CFA excursion whose four same-color neighbors are
// statistically coherent. This is a pre-demosaic hot/CFA outlier guard, not spatial
// denoise: real edges/texture enlarge the neighbor span and fail closed to center.
vec3 cleanedMeasurementAt(ivec2 p) {
    vec3 centerValue = rawMeasurementAt(p);
    vec3 left2 = rawMeasurementAt(p + ivec2(-2, 0));
    vec3 right2 = rawMeasurementAt(p + ivec2(2, 0));
    vec3 up2 = rawMeasurementAt(p + ivec2(0, -2));
    vec3 down2 = rawMeasurementAt(p + ivec2(0, 2));

    float localMedian = median5(
        centerValue.x, left2.x, right2.x, up2.x, down2.x);
    float neighborMinimum = min(min(left2.x, right2.x), min(up2.x, down2.x));
    float neighborMaximum = max(max(left2.x, right2.x), max(up2.x, down2.x));
    float sigmaBound = max(centerValue.y,
        max(max(left2.y, right2.y), max(up2.y, down2.y)));
    sigmaBound = max(sigmaBound, 0.00005);

    float neighborSpan = neighborMaximum - neighborMinimum;
    float isolatedDistance = abs(centerValue.x - localMedian);
    float coherentNeighbors = 1.0 - smoothstep(
        3.0 * sigmaBound + 0.0005,
        8.0 * sigmaBound + 0.0020,
        neighborSpan);
    float isolatedOutlier = smoothstep(
        5.0 * sigmaBound + 0.0007,
        10.0 * sigmaBound + 0.0030,
        isolatedDistance) * coherentNeighbors * (1.0 - centerValue.z);

    float corrected = mix(centerValue.x, localMedian, 0.92 * isolatedOutlier);
    float correctedSigma = mix(centerValue.y, sigmaBound, 0.50 * isolatedOutlier);
    return vec3(max(corrected, 0.0), max(correctedSigma, 0.000001), centerValue.z);
}

float compandPositive(float value, float k) {
    float x = max(value, 0.0);
    return sqrt(x / max(x + k, 0.0000001));
}

vec2 pack16(float encoded) {
    float code = min(PACK_MAX_CODE, floor(clamp(encoded, 0.0, 1.0) * PACK_DENOM + 0.5));
    float highByte = floor(code / 256.0);
    float lowByte = code - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

vec2 packSigmaAndSaturation(float encodedSigma, float saturation) {
    float sigmaCode = min(SIGMA_MAX_CODE,
        floor(clamp(encodedSigma, 0.0, 1.0) * SIGMA_MAX_CODE + 0.5));
    float code = sigmaCode + 32768.0 * step(0.5, saturation);
    float highByte = floor(code / 256.0);
    float lowByte = code - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 measurement = cleanedMeasurementAt(p);
    vec2 signalPacked = pack16(compandPositive(measurement.x, SIGNAL_COMPAND_K));
    vec2 sigmaPacked = packSigmaAndSaturation(
        compandPositive(measurement.y, SIGMA_COMPAND_K), measurement.z);
    outColor = vec4(signalPacked, sigmaPacked);
}
