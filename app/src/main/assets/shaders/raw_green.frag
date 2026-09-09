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

vec2 pack16(float encoded) {
    float code = min(PACK_MAX_CODE, floor(clamp(encoded, 0.0, 1.0) * PACK_DENOM + 0.5));
    float highByte = floor(code / 256.0);
    float lowByte = code - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

vec2 packSigmaAndCensor(float sigma, float censored) {
    float sigmaEncoded = compandPositive(max(sigma, 0.000001), SIGMA_COMPAND_K);
    float sigmaCode = min(SIGMA_MAX_CODE,
        floor(clamp(sigmaEncoded, 0.0, 1.0) * SIGMA_MAX_CODE + 0.5));
    float code = sigmaCode + 32768.0 * step(0.5, censored);
    float highByte = floor(code / 256.0);
    float lowByte = code - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

// IRIS_V234_CENSORED_GREEN_OWNER_BEGIN
// A saturated green photosite is a lower bound, not a valid green measurement.
// Reconstruct the guide only from uncensored green support.  The output censor bit
// remains asserted whenever the center/direct green is clipped or local two-sided
// support is incomplete; raw_reconstruct then refuses to form ordinary R-G/B-G
// opponent differences against that estimate and uses highlight-aware color recovery.
vec3 pairEstimate(vec3 negativeValue, vec3 positiveValue) {
    float negativeValid = 1.0 - negativeValue.z;
    float positiveValid = 1.0 - positiveValue.z;
    float support = negativeValid + positiveValid;
    float meanValue = (negativeValue.x * negativeValid + positiveValue.x * positiveValid)
        / max(support, 0.000001);
    float sigmaValue = sqrt(
        negativeValue.y * negativeValue.y * negativeValid
        + positiveValue.y * positiveValue.y * positiveValid)
        / max(support, 0.000001);
    float noiseScale = sqrt(
        negativeValue.y * negativeValue.y + positiveValue.y * positiveValue.y
        + 0.00000001);
    float gradient = support > 1.5
        ? abs(negativeValue.x - positiveValue.x) / max(noiseScale, 0.00010)
        : 8.0;
    float reliability = support * (1.0 / (1.0 + gradient * gradient));
    return vec3(meanValue, max(sigmaValue, 0.000001), reliability);
}

vec3 wideGreenEstimate(ivec2 p, bool sameGreenPhase) {
    ivec2 offsets[12] = ivec2[12](
        ivec2(-2, 0), ivec2(2, 0), ivec2(0, -2), ivec2(0, 2),
        ivec2(-4, 0), ivec2(4, 0), ivec2(0, -4), ivec2(0, 4),
        ivec2(-6, 0), ivec2(6, 0), ivec2(0, -6), ivec2(0, 6));
    float valueSum = 0.0;
    float varianceSum = 0.0;
    float weightSum = 0.0;
    for (int i = 0; i < 12; ++i) {
        ivec2 q = p + offsets[i];
        if (!sameGreenPhase) {
            // At a red/blue center, odd cardinal offsets are green; map the even
            // template above onto distances 3/5/7 without dynamic array construction.
            ivec2 direction = ivec2(sign(float(offsets[i].x)), sign(float(offsets[i].y)));
            int radius = 3 + 2 * (i / 4);
            q = p + direction * radius;
        }
        ivec2 qc = clampPixel(q);
        if (colorAt(qc) != 1) continue;
        vec3 value = rawMeasurementAt(qc);
        float valid = 1.0 - value.z;
        float radiusPixels = length(vec2(q - p));
        float distanceWeight = 1.0 / max(radiusPixels, 1.0);
        float weightValue = valid * distanceWeight
            / (value.y * value.y + 0.00000002);
        valueSum += value.x * weightValue;
        varianceSum += value.y * value.y * weightValue * weightValue;
        weightSum += weightValue;
    }
    if (weightSum <= 0.000001) return vec3(0.0, 1.0, 0.0);
    return vec3(
        valueSum / weightSum,
        max(sqrt(varianceSum) / weightSum, 0.000001),
        weightSum);
}

vec3 greenAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    if (colorAt(q) == 1) {
        vec3 directValue = rawMeasurementAt(q);
        if (directValue.z < 0.5) {
            return vec3(directValue.xy, 0.0);
        }
        vec3 wideValue = wideGreenEstimate(q, true);
        float estimate = wideValue.z > 0.0 ? wideValue.x : directValue.x;
        float sigmaValue = wideValue.z > 0.0
            ? max(wideValue.y, directValue.y) : directValue.y;
        return vec3(max(estimate, 0.0), max(sigmaValue, 0.000001), 1.0);
    }

    vec3 leftValue = rawMeasurementAt(q + ivec2(-1, 0));
    vec3 rightValue = rawMeasurementAt(q + ivec2(1, 0));
    vec3 upValue = rawMeasurementAt(q + ivec2(0, -1));
    vec3 downValue = rawMeasurementAt(q + ivec2(0, 1));
    vec3 horizontal = pairEstimate(leftValue, rightValue);
    vec3 vertical = pairEstimate(upValue, downValue);
    float weightSum = horizontal.z + vertical.z;

    float green = weightSum > 0.000001
        ? (horizontal.x * horizontal.z + vertical.x * vertical.z) / weightSum
        : 0.0;
    float greenSigma = weightSum > 0.000001
        ? sqrt(horizontal.z * horizontal.z * horizontal.y * horizontal.y
            + vertical.z * vertical.z * vertical.y * vertical.y) / weightSum
        : 1.0;

    float horizontalComplete = (1.0 - leftValue.z) * (1.0 - rightValue.z);
    float verticalComplete = (1.0 - upValue.z) * (1.0 - downValue.z);
    float reliableLocal = max(horizontalComplete, verticalComplete);
    if (weightSum <= 0.000001) {
        vec3 wideValue = wideGreenEstimate(q, false);
        if (wideValue.z > 0.0) {
            green = wideValue.x;
            greenSigma = wideValue.y;
        }
    }

    // Only a complete uncensored directional pair is valid opponent authority.
    // A wide/one-sided estimate still helps spatial luminance continuity but remains
    // censored so raw_reconstruct cannot mistake it for measured color evidence.
    float censored = 1.0 - step(0.5, reliableLocal);
    return vec3(max(green, 0.0), max(greenSigma, 0.000001), censored);
}
// IRIS_V234_CENSORED_GREEN_OWNER_END

void main() {
    vec3 greenValue = greenAt(ivec2(gl_FragCoord.xy));
    vec2 greenPacked = pack16(compandPositive(greenValue.x, SIGNAL_COMPAND_K));
    vec2 sigmaAndCensorPacked = packSigmaAndCensor(greenValue.y, greenValue.z);
    outColor = vec4(greenPacked, sigmaAndCensorPacked);
}
