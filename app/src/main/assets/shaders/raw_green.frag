#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D packedRawTex;
uniform highp usampler2D rawTex;
uniform int cfaArrangement;
uniform vec4 blackPatternCode;
uniform float whiteLevelCode;
uniform vec4 wbGains;
uniform float highlightClipThreshold;
uniform float highlightCeiling;

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

int clampPhaseCoordinate(int value, int extent) {
    int phase = value & 1;
    if (phase >= extent) return extent - 1;
    int last = phase + 2 * ((extent - 1 - phase) / 2);
    return clamp(value, phase, last);
}

ivec2 phaseClamp(ivec2 p) {
    ivec2 size = textureSize(rawTex, 0);
    return ivec2(
        clampPhaseCoordinate(p.x, size.x),
        clampPhaseCoordinate(p.y, size.y));
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

// x=the existing V2.34 lens-shaded linear CFA signal, y=physical sigma,
// z=the literal pre-lens-shading sensor saturation bit produced by raw_preprocess.
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

float blackAt(ivec2 p) {
    int i = patternIndex(p);
    if (i == 0) return blackPatternCode.x;
    if (i == 1) return blackPatternCode.y;
    if (i == 2) return blackPatternCode.z;
    return blackPatternCode.w;
}

// The Claude diagnostic's clipping authority is the physical sensor domain before
// calculation WB.  Read that domain directly from the still-live R16UI RAW texture;
// do not infer clipping from the lens-shaded packed signal.
float physicalSensorAt(ivec2 p) {
    ivec2 q = phaseClamp(p);
    float code = float(texelFetch(rawTex, q, 0).r);
    float black = blackAt(q);
    return max(code - black, 0.0) / max(whiteLevelCode - black, 0.000001);
}

float greenGain() {
    return max(0.5 * (wbGains.y + wbGains.z), 0.000001);
}

float calculationWbForColor(int color) {
    float g = greenGain();
    if (color == 0) return wbGains.x / g;
    if (color == 2) return wbGains.w / g;
    return 1.0;
}

float nativeCalculationSample(ivec2 p) {
    ivec2 q = phaseClamp(p);
    return rawMeasurementAt(q).x * calculationWbForColor(colorAt(q));
}

// IRIS_V235_CLAUDE_EXACT_HIGHLIGHT_CALCULATION_SAMPLE_BEGIN
// Literal semantic port of the old-Iris Claude prescription.  The reconstruction
// itself is the original 3x3 opposed-channel power-3 mean, bounded below by the
// clipped physical observation and above by highlightCeiling.  Only the carrier
// plumbing is Viewfinder-native: values come from V2.34's already lens-shaded linear
// CFA carrier, while clipMask comes from the exact pre-LSC sensor-normalized RAW.
float highlightCalculationSample(ivec2 p) {
    ivec2 q = phaseClamp(p);
    int targetColor = colorAt(q);
    float targetWb = max(calculationWbForColor(targetColor), 0.000001);
    float sensor = physicalSensorAt(q);
    float cameraFallback = rawMeasurementAt(q).x;
    float clipMask = smoothstep(highlightClipThreshold, 1.0, sensor);
    if (clipMask <= 0.0) return cameraFallback * targetWb;

    float sumR = 0.0;
    float sumG = 0.0;
    float sumB = 0.0;
    float countR = 0.0;
    float countG = 0.0;
    float countB = 0.0;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            ivec2 sampleP = phaseClamp(q + ivec2(dx, dy));
            int sampleColor = colorAt(sampleP);
            float value = nativeCalculationSample(sampleP);
            if (sampleColor == 0) {
                sumR += value;
                countR += 1.0;
            } else if (sampleColor == 1) {
                sumG += value;
                countG += 1.0;
            } else {
                sumB += value;
                countB += 1.0;
            }
        }
    }

    const float power = 3.0;
    float rootR = pow(max(sumR / max(countR, 1.0), 0.0), 1.0 / power);
    float rootG = pow(max(sumG / max(countG, 1.0), 0.0), 1.0 / power);
    float rootB = pow(max(sumB / max(countB, 1.0), 0.0), 1.0 / power);
    float opposed = targetColor == 0
        ? 0.5 * (rootG + rootB)
        : (targetColor == 1
            ? 0.5 * (rootR + rootB)
            : 0.5 * (rootR + rootG));
    float calculationFallback = cameraFallback * targetWb;
    float reconstructed = pow(max(opposed, 0.0), power);
    reconstructed = min(max(reconstructed, calculationFallback), highlightCeiling);
    return mix(calculationFallback, reconstructed, clipMask);
}
// IRIS_V235_CLAUDE_EXACT_HIGHLIGHT_CALCULATION_SAMPLE_END

// Keep the successful pre-hybrid Viewfinder edge weighting outside clipped regions.
// Claude explicitly allowed porting highlightCalculationSample into the existing guide;
// this avoids changing ordinary unsaturated CFA geometry while making clipped green
// use the exact opposed-channel reconstruction instead of V2.34's wide donor search.
vec2 greenAt(ivec2 p) {
    ivec2 q = clampPixel(p);
    if (colorAt(q) == 1) {
        vec3 directValue = rawMeasurementAt(q);
        return vec2(highlightCalculationSample(q), max(directValue.y, 0.000001));
    }

    ivec2 pL = phaseClamp(q + ivec2(-1, 0));
    ivec2 pR = phaseClamp(q + ivec2(1, 0));
    ivec2 pU = phaseClamp(q + ivec2(0, -1));
    ivec2 pD = phaseClamp(q + ivec2(0, 1));
    vec3 leftRaw = rawMeasurementAt(pL);
    vec3 rightRaw = rawMeasurementAt(pR);
    vec3 upRaw = rawMeasurementAt(pU);
    vec3 downRaw = rawMeasurementAt(pD);
    float leftValue = highlightCalculationSample(pL);
    float rightValue = highlightCalculationSample(pR);
    float upValue = highlightCalculationSample(pU);
    float downValue = highlightCalculationSample(pD);

    float horizontal = 0.5 * (leftValue + rightValue);
    float vertical = 0.5 * (upValue + downValue);
    float horizontalSigma = 0.5 * sqrt(
        leftRaw.y * leftRaw.y + rightRaw.y * rightRaw.y);
    float verticalSigma = 0.5 * sqrt(
        upRaw.y * upRaw.y + downRaw.y * downRaw.y);

    float horizontalNoise = sqrt(
        leftRaw.y * leftRaw.y + rightRaw.y * rightRaw.y + 0.00000001);
    float verticalNoise = sqrt(
        upRaw.y * upRaw.y + downRaw.y * downRaw.y + 0.00000001);
    float horizontalGradient = abs(leftValue - rightValue)
        / max(horizontalNoise, 0.00010);
    float verticalGradient = abs(upValue - downValue)
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

vec2 packSigmaAndCensor(float sigma, float censored) {
    float sigmaEncoded = compandPositive(max(sigma, 0.000001), SIGMA_COMPAND_K);
    float sigmaCode = min(SIGMA_MAX_CODE,
        floor(clamp(sigmaEncoded, 0.0, 1.0) * SIGMA_MAX_CODE + 0.5));
    float code = sigmaCode + 32768.0 * step(0.5, censored);
    float highByte = floor(code / 256.0);
    float lowByte = code - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

void main() {
    vec2 greenValue = greenAt(ivec2(gl_FragCoord.xy));
    vec2 greenPacked = pack16(compandPositive(greenValue.x, SIGNAL_COMPAND_K));
    // The highlight guide is now the active owner.  Opponent permission is decided
    // independently by the common 2x2 physical clip gate in raw_reconstruct.
    vec2 sigmaAndCensorPacked = packSigmaAndCensor(greenValue.y, 0.0);
    outColor = vec4(greenPacked, sigmaAndCensorPacked);
}
