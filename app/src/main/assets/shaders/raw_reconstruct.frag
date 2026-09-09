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

// x=opponent difference, y=sigma, z=validity.  A color difference is meaningful
// only when BOTH the color photosite and its green reference are uncensored.
vec3 colorDifferenceAt(ivec2 p) {
    vec3 colorValue = rawMeasurementAt(p);
    vec3 greenValue = greenAt(p);
    float valid = (1.0 - colorValue.z) * (1.0 - greenValue.z);
    return vec3(
        colorValue.x - greenValue.x,
        sqrt(colorValue.y * colorValue.y + greenValue.y * greenValue.y),
        valid);
}

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
    float greenValid = 1.0 - greenValue.z;

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

// IRIS_V234_CENSORED_OPPONENT_HIGHLIGHT_RECOVERY_BEGIN
// Recover only channels whose CFA/opponent evidence is censored.  Valid measured
// channels never move.  Boundary chromaticity is derived BEFORE WB/CCM from fully
// valid same-phase neighborhoods, then applied in balanced-sensor space.  This is
// the causal correction for the white chandelier/grow-light magenta shoulder: a
// clipped green can no longer be subtracted as if it were a real green measurement.
void recoverCensoredBalanced(
        ivec2 p,
        vec3 balanceGains,
        vec3 baseSensorRgb,
        vec3 baseSensorSigma,
        vec3 baseValid,
        out vec3 recoveredBalanced,
        out vec3 recoveredBalancedSigma) {
    vec3 centerBalanced = max(baseSensorRgb, vec3(0.0)) * balanceGains;
    vec3 centerSigma = max(baseSensorSigma, vec3(0.000001)) * abs(balanceGains);
    vec3 invalid = vec3(1.0) - clamp(baseValid, vec3(0.0), vec3(1.0));
    if (max(invalid.r, max(invalid.g, invalid.b)) < 0.5) {
        recoveredBalanced = centerBalanced;
        recoveredBalancedSigma = centerSigma;
        return;
    }

    ivec2 offsets[24] = ivec2[24](
        ivec2(-6, 0), ivec2(6, 0), ivec2(0, -6), ivec2(0, 6),
        ivec2(-6, -6), ivec2(6, -6), ivec2(-6, 6), ivec2(6, 6),
        ivec2(-24, 0), ivec2(24, 0), ivec2(0, -24), ivec2(0, 24),
        ivec2(-24, -24), ivec2(24, -24), ivec2(-24, 24), ivec2(24, 24),
        ivec2(-72, 0), ivec2(72, 0), ivec2(0, -72), ivec2(0, 72),
        ivec2(-72, -72), ivec2(72, -72), ivec2(-72, 72), ivec2(72, 72));

    vec3 chromaSum = vec3(0.0);
    vec3 chromaSecond = vec3(0.0);
    float weightSum = 0.0;
    float votes = 0.0;
    for (int i = 0; i < 24; ++i) {
        vec3 candidateRgb;
        vec3 candidateSigma;
        vec3 candidateValid;
        demosaicSensorBase(p + offsets[i], candidateRgb, candidateSigma, candidateValid);
        float fullyValid = min(candidateValid.r, min(candidateValid.g, candidateValid.b));
        vec3 candidateBalanced = max(candidateRgb, vec3(0.0)) * balanceGains;
        float candidateSum = candidateBalanced.r + candidateBalanced.g + candidateBalanced.b;
        float signalGate = smoothstep(0.015, 0.080, candidateSum);
        vec3 chroma = candidateBalanced / max(candidateSum, 0.000001);
        float distanceWeight = i < 8 ? 1.0 : (i < 16 ? 0.50 : 0.20);
        float weightValue = fullyValid * signalGate * distanceWeight;
        chromaSum += chroma * weightValue;
        chromaSecond += chroma * chroma * weightValue;
        weightSum += weightValue;
        votes += step(0.20, weightValue);
    }

    vec3 boundaryChroma = weightSum > 0.0001
        ? chromaSum / weightSum : vec3(1.0 / 3.0);
    vec3 varianceVector = weightSum > 0.0001
        ? max(chromaSecond / weightSum - boundaryChroma * boundaryChroma, vec3(0.0))
        : vec3(1.0);
    float chromaVariance = varianceVector.r + varianceVector.g + varianceVector.b;
    float boundaryConsensus = (1.0 - smoothstep(0.0008, 0.0120, chromaVariance))
        * smoothstep(2.0, 5.0, votes);

    float validScaleNumerator = dot(centerBalanced * boundaryChroma, baseValid);
    float validScaleDenominator = dot(boundaryChroma * boundaryChroma, baseValid);
    float inferredScale = validScaleNumerator / max(validScaleDenominator, 0.000001);
    vec3 boundaryPrediction = boundaryChroma * max(inferredScale, 0.0);

    // A neutral fallback is allowed only when at least two uncensored balanced channels
    // already agree.  This fixes a fully surrounded white-light shoulder without
    // erasing genuinely colored saturated lamps whose surviving channels disagree.
    float validCount = baseValid.r + baseValid.g + baseValid.b;
    float validMean = dot(centerBalanced, baseValid) / max(validCount, 1.0);
    float validSpread = 0.0;
    if (baseValid.r > 0.5) validSpread = max(validSpread, abs(centerBalanced.r - validMean));
    if (baseValid.g > 0.5) validSpread = max(validSpread, abs(centerBalanced.g - validMean));
    if (baseValid.b > 0.5) validSpread = max(validSpread, abs(centerBalanced.b - validMean));
    float neutralEvidence = step(1.5, validCount)
        * (1.0 - smoothstep(0.10, 0.32, validSpread / max(validMean, 0.001)));

    float boundaryStrength = boundaryConsensus * step(0.5, validCount);
    float neutralStrength = (1.0 - boundaryStrength) * neutralEvidence;
    vec3 prediction = mix(vec3(validMean), boundaryPrediction, boundaryStrength);
    float recoveryStrength = clamp(max(boundaryStrength, neutralStrength), 0.0, 1.0);
    recoveredBalanced = mix(
        centerBalanced,
        prediction,
        invalid * recoveryStrength);

    float validSigmaMean = dot(centerSigma, baseValid) / max(validCount, 1.0);
    vec3 inferredSigma = vec3(max(1.50 * validSigmaMean, 0.00020));
    recoveredBalancedSigma = mix(
        centerSigma,
        inferredSigma,
        invalid * recoveryStrength);
}
// IRIS_V234_CENSORED_OPPONENT_HIGHLIGHT_RECOVERY_END

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 sensorRgb;
    vec3 sensorSigma;
    vec3 sensorValid;
    demosaicSensorBase(p, sensorRgb, sensorSigma, sensorValid);
    sensorRgb = max(sensorRgb, vec3(0.0));

    float greenGain = 0.5 * (wbGains.y + wbGains.z);
    vec3 balanceGains = vec3(wbGains.x, greenGain, wbGains.w);
    vec3 balancedRgb;
    vec3 balancedSigma;
    recoverCensoredBalanced(
        p, balanceGains, sensorRgb, sensorSigma, sensorValid,
        balancedRgb, balancedSigma);

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
