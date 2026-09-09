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
const float CARRIER_BODY_END = CARRIER_MAX * 0.42;
const float CARRIER_DETAIL_END = CARRIER_MAX * 0.92;
const float CARRIER_DETAIL_TOP = 8.0;
const float CARRIER_DETAIL_STOPS = 3.0;
const float CARRIER_TAIL_TOP = 32.0;
const float CARRIER_TAIL_STOPS = 2.0;

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

// V2.34 keeps the existing RGBA8 allocation/lifetime but spends code space where
// this 3-EV camera actually needs it: shadows retain square-root precision, scene
// values 1..8 receive >130 monotonic code levels, and only >8 specular energy enters
// the short asymptotic tail.  This removes the coarse V2.32/V2.33 x/(x+1) highlight
// spacing without increasing GPU memory or changing fusion ownership mechanics.
float encodeSceneChannel(float value) {
    float x = max(value, 0.0);
    if (x <= 1.0) {
        return CARRIER_BODY_END * sqrt(x);
    }
    if (x <= CARRIER_DETAIL_TOP) {
        float stops = log2(x);
        return CARRIER_BODY_END
            + (CARRIER_DETAIL_END - CARRIER_BODY_END)
                * (stops / CARRIER_DETAIL_STOPS);
    }
    float tailStops = clamp(log2(x / CARRIER_DETAIL_TOP), 0.0, CARRIER_TAIL_STOPS);
    float tailT = tailStops / CARRIER_TAIL_STOPS;
    return min(CARRIER_MAX,
        CARRIER_DETAIL_END + (CARRIER_MAX - CARRIER_DETAIL_END) * tailT);
}

float unpack16Code(vec2 packed) {
    float highByte = floor(packed.x * 255.0 + 0.5);
    float lowByte = floor(packed.y * 255.0 + 0.5);
    return highByte * 256.0 + lowByte;
}

// x=signal, y=sigma, z=literal physical-saturation flag.
vec3 rawMeasurementAt(ivec2 p) {
    vec4 packed = texelFetch(packedRawTex, clampPixel(p), 0);
    float sigmaAndSat = unpack16Code(packed.ba);
    float saturation = step(32767.5, sigmaAndSat);
    float sigmaCode = sigmaAndSat - 32768.0 * saturation;
    return vec3(
        expandPositive(unpack16Code(packed.rg) / 65535.0, SIGNAL_COMPAND_K),
        expandPositive(sigmaCode / 32767.0, SIGMA_COMPAND_K),
        saturation);
}

float rawSaturationAt(ivec2 p) {
    vec4 packed = texelFetch(packedRawTex, clampPixel(p), 0);
    return step(32767.5, unpack16Code(packed.ba));
}

// x=green estimate, y=sigma, z=censored/unreliable-opponent flag.
vec3 greenAt(ivec2 p) {
    vec4 packed = texelFetch(greenTex, clampPixel(p), 0);
    float sigmaAndCensor = unpack16Code(packed.ba);
    float censored = step(32767.5, sigmaAndCensor);
    float sigmaCode = sigmaAndCensor - 32768.0 * censored;
    return vec3(
        expandPositive(unpack16Code(packed.rg) / 65535.0, SIGNAL_COMPAND_K),
        expandPositive(sigmaCode / 32767.0, SIGMA_COMPAND_K),
        censored);
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

// IRIS_V235_CLAUDE_COMMON_QUAD_CLIP_AUTHORITY_BEGIN
// Claude correction item 1: the smallest opponent-color authority is the physical
// 2x2 Bayer quad.  If ANY CFA member is at/near sensor saturation, no member of that
// quad may form an ordinary R-G/B-G observation.  All four phases therefore make the
// same permission decision instead of alternating by Bayer phase.
float quadHighlightAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    ivec2 base = ivec2(q.x & ~1, q.y & ~1);
    float highlighted = 0.0;
    for (int oy = 0; oy < 2; ++oy) {
        for (int ox = 0; ox < 2; ++ox) {
            highlighted = max(highlighted,
                rawSaturationAt(base + ivec2(ox, oy)));
        }
    }
    return highlighted;
}

// x=opponent difference, y=sigma, z=absolute validity.  There is deliberately no
// support/green ratio here: Claude correction item 2 forbids confidence increasing
// when green support collapses.  Later interpolation requires an absolute count of
// at least two independently valid observations.
vec3 colorDifferenceAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    vec3 colorValue = rawMeasurementAt(q);
    vec3 greenValue = greenAt(q);
    float commonQuadValid = 1.0 - quadHighlightAt(q);
    float valid = (1.0 - colorValue.z) * commonQuadValid;
    return vec3(
        colorValue.x - greenValue.x,
        sqrt(colorValue.y * colorValue.y + greenValue.y * greenValue.y),
        valid);
}
// IRIS_V235_CLAUDE_COMMON_QUAD_CLIP_AUTHORITY_END

vec3 robustFourDifferences(
        vec3 d0, vec3 d1, vec3 d2, vec3 d3,
        float spatial0, float spatial1, float spatial2, float spatial3) {
    vec3 values[4] = vec3[4](d0, d1, d2, d3);
    float spatial[4] = float[4](spatial0, spatial1, spatial2, spatial3);
    float validSum = 0.0;
    float initialValue = 0.0;
    for (int i = 0; i < 4; ++i) {
        initialValue += values[i].x * values[i].z;
        validSum += values[i].z;
    }
    float centerDifference = validSum > 0.0 ? initialValue / validSum : 0.0;

    float weightedValue = 0.0;
    float weightSum = 0.0;
    float varianceSum = 0.0;
    for (int i = 0; i < 4; ++i) {
        float sigmaValue = max(values[i].y, 0.00005);
        float deviation = abs(values[i].x - centerDifference);
        float normalizedDeviation = deviation / max(2.5 * sigmaValue, 0.00020);
        float consistency = 1.0 / (1.0 + normalizedDeviation * normalizedDeviation);
        float inverseVariance = 1.0 / (sigmaValue * sigmaValue + 0.00000002);
        float weightValue = values[i].z * spatial[i] * consistency * inverseVariance;
        weightedValue += values[i].x * weightValue;
        weightSum += weightValue;
        varianceSum += sigmaValue * sigmaValue * weightValue * weightValue;
    }
    float resultValue = weightSum > 0.0 ? weightedValue / weightSum : centerDifference;
    float resultSigma = weightSum > 0.0
        ? sqrt(varianceSum) / weightSum
        : max(max(d0.y, d1.y), max(d2.y, d3.y));
    // Two independent uncensored opponent observations are required before this
    // difference can become ordinary color authority.
    float valid = step(1.5, validSum);
    return vec3(resultValue, max(resultSigma, 0.000001), valid);
}

vec3 diagonalDifference(ivec2 q) {
    vec3 d0 = colorDifferenceAt(q + ivec2(-1, -1));
    vec3 d1 = colorDifferenceAt(q + ivec2(1, -1));
    vec3 d2 = colorDifferenceAt(q + ivec2(-1, 1));
    vec3 d3 = colorDifferenceAt(q + ivec2(1, 1));
    return robustFourDifferences(d0, d1, d2, d3, 1.0, 1.0, 1.0, 1.0);
}

vec3 axisDifference(ivec2 q, ivec2 axis) {
    vec3 nearNegative = colorDifferenceAt(q - axis);
    vec3 nearPositive = colorDifferenceAt(q + axis);
    vec3 farNegative = colorDifferenceAt(q - 3 * axis);
    vec3 farPositive = colorDifferenceAt(q + 3 * axis);
    return robustFourDifferences(
        nearNegative, nearPositive, farNegative, farPositive,
        1.0, 1.0, 0.35, 0.35);
}

void demosaicSensorBase(
        ivec2 p, out vec3 sensorRgb, out vec3 sensorSigma, out vec3 sensorValid) {
    ivec2 q = clampPixel(p);
    int centerColor = colorAt(q);
    vec3 greenValue = greenAt(q);
    // The exact highlight-aware guide is now an active reconstructed green owner.
    // Physical opponent permission is handled separately by quadHighlightAt().
    float greenValid = 1.0;

    if (centerColor == 0) {
        vec3 redValue = rawMeasurementAt(q);
        vec3 blueDifference = diagonalDifference(q);
        float blue = greenValue.x + blueDifference.x;
        sensorRgb = vec3(redValue.x, greenValue.x, blue);
        sensorSigma = vec3(
            redValue.y,
            greenValue.y,
            sqrt(greenValue.y * greenValue.y + blueDifference.y * blueDifference.y));
        sensorValid = vec3(
            1.0 - redValue.z,
            greenValid,
            greenValid * blueDifference.z);
        return;
    }
    if (centerColor == 2) {
        vec3 blueValue = rawMeasurementAt(q);
        vec3 redDifference = diagonalDifference(q);
        float red = greenValue.x + redDifference.x;
        sensorRgb = vec3(red, greenValue.x, blueValue.x);
        sensorSigma = vec3(
            sqrt(greenValue.y * greenValue.y + redDifference.y * redDifference.y),
            greenValue.y,
            blueValue.y);
        sensorValid = vec3(
            greenValid * redDifference.z,
            greenValid,
            1.0 - blueValue.z);
        return;
    }

    bool redHorizontal = colorAt(q + ivec2(1, 0)) == 0
        || colorAt(q + ivec2(-1, 0)) == 0;
    vec3 redDifference = axisDifference(q, redHorizontal ? ivec2(1, 0) : ivec2(0, 1));
    vec3 blueDifference = axisDifference(q, redHorizontal ? ivec2(0, 1) : ivec2(1, 0));
    float red = greenValue.x + redDifference.x;
    float blue = greenValue.x + blueDifference.x;
    sensorRgb = vec3(red, greenValue.x, blue);
    sensorSigma = vec3(
        sqrt(greenValue.y * greenValue.y + redDifference.y * redDifference.y),
        greenValue.y,
        sqrt(greenValue.y * greenValue.y + blueDifference.y * blueDifference.y));
    sensorValid = vec3(
        greenValid * redDifference.z,
        greenValid,
        greenValid * blueDifference.z);
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

// IRIS_V235_CLAUDE_NEUTRAL_MISSING_SUPPORT_BEGIN
// The old-Iris normalize path fell back to one phase-invariant brightness only when
// opponent support was actually missing.  Reproduce that behavior without V2.34's
// wide boundary-hue donor: take the maximum WB-balanced physical lower-bound signal
// in this pixel's parent 2x2 Bayer quad.  No neighboring hue is invented.
float neutralFallbackBalanced(ivec2 p, vec3 balanceGains) {
    ivec2 q = clampPixel(p);
    ivec2 base = ivec2(q.x & ~1, q.y & ~1);
    float neutral = 0.0;
    for (int oy = 0; oy < 2; ++oy) {
        for (int ox = 0; ox < 2; ++ox) {
            ivec2 sampleP = clampPixel(base + ivec2(ox, oy));
            vec3 sampleValue = rawMeasurementAt(sampleP);
            int sampleColor = colorAt(sampleP);
            float gain = sampleColor == 0
                ? balanceGains.r : (sampleColor == 2 ? balanceGains.b : balanceGains.g);
            neutral = max(neutral, sampleValue.x * gain);
        }
    }
    return neutral;
}
// IRIS_V235_CLAUDE_NEUTRAL_MISSING_SUPPORT_END

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 sensorRgb;
    vec3 sensorSigma;
    vec3 sensorValid;
    demosaicSensorBase(p, sensorRgb, sensorSigma, sensorValid);
    sensorRgb = max(sensorRgb, vec3(0.0));

    float greenGain = 0.5 * (wbGains.y + wbGains.z);
    vec3 balanceGains = vec3(wbGains.x, greenGain, wbGains.w);
    vec3 balancedRgb = sensorRgb * balanceGains;
    vec3 balancedSigma = sensorSigma * abs(balanceGains);

    // Claude correction items 1-2: if common-quad rejection leaves any required
    // color axis without absolute support, use the existing conservative neutral
    // terminal state.  Do not borrow boundary chromaticity and do not desaturate a
    // later RGB result to hide an upstream opponent error.
    float completeColorSupport = min(sensorValid.r, min(sensorValid.g, sensorValid.b));
    if (completeColorSupport < 0.5) {
        float neutral = max(
            neutralFallbackBalanced(p, balanceGains),
            balancedRgb.g);
        balancedRgb = vec3(max(neutral, 0.0));
        float fallbackSigma = max(
            balancedSigma.r, max(balancedSigma.g, balancedSigma.b));
        balancedSigma = vec3(max(fallbackSigma, 0.000001));
    }

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
        encodeSceneChannel(linearRgb.r),
        encodeSceneChannel(linearRgb.g),
        encodeSceneChannel(linearRgb.b));
    outColor = vec4(encodedScene,
        encodeSigmaAndSaturation(sceneSigma, saturationEvidence));
}
