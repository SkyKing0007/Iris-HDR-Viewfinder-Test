#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D normalTex;
uniform sampler2D shortTex;
uniform sampler2D longTex;
uniform int mode;
uniform int rotationQuarterTurns;
uniform int haveNormal;
uniform int haveShort;
uniform int haveLong;
uniform float exposureRatio;
uniform float displayBrightnessEv;
uniform float displayGamma;
uniform float stillRegistrationConfidence;
uniform vec3 stillShortLinearGain;
uniform float stillShortScalarGain;
uniform vec2 fullFitScale;
uniform vec2 splitFitScale;

vec2 rotateUv(vec2 uv) {
    if (rotationQuarterTurns == 1) return vec2(1.0 - uv.y, uv.x);
    if (rotationQuarterTurns == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (rotationQuarterTurns == 3) return vec2(uv.y, 1.0 - uv.x);
    return uv;
}

bool fitSourceUv(vec2 displayUv, vec2 fitScale, out vec2 sourceUv) {
    vec2 fitted = vec2(0.5) + (displayUv - vec2(0.5)) * fitScale;
    if (any(lessThan(fitted, vec2(0.0))) || any(greaterThan(fitted, vec2(1.0)))) {
        return false;
    }
    sourceUv = rotateUv(fitted);
    return true;
}

vec3 fallbackColor(vec2 uv) {
    if (haveNormal == 1) return texture(normalTex, uv).rgb;
    if (haveLong == 1) return texture(longTex, uv).rgb;
    if (haveShort == 1) return texture(shortTex, uv).rgb;
    return vec3(0.0);
}

float srgbToLinearChannel(float value) {
    return value <= 0.04045
        ? value / 12.92
        : pow((value + 0.055) / 1.055, 2.4);
}

vec3 srgbToLinear(vec3 value) {
    return vec3(
        srgbToLinearChannel(value.r),
        srgbToLinearChannel(value.g),
        srgbToLinearChannel(value.b));
}

float linearToSrgbChannel(float value) {
    float clampedValue = max(value, 0.0);
    return clampedValue <= 0.0031308
        ? 12.92 * clampedValue
        : 1.055 * pow(clampedValue, 1.0 / 2.4) - 0.055;
}

vec3 linearToSrgb(vec3 value) {
    return vec3(
        linearToSrgbChannel(value.r),
        linearToSrgbChannel(value.g),
        linearToSrgbChannel(value.b));
}

float max3(vec3 value) {
    return max(value.r, max(value.g, value.b));
}

float adaptiveClipStart(float bracketStops) {
    // Wider brackets carry a noisier/darker SHORT frame, so admit SHORT only
    // closer to LONG saturation. This depends on exposure relationship, not device.
    return clamp(0.90 + 0.01 * (bracketStops - 1.0), 0.90, 0.95);
}

vec3 adaptiveHdrToneMap(vec3 sceneLinear, float ratio, float bracketStops) {
    // LONG owns shadows/midtones unchanged. Recovered scene values above the
    // display range are compressed by bracket width while preserving RGB ratios.
    const float knee = 0.70;
    float scenePeak = max3(sceneLinear);
    if (scenePeak <= knee || scenePeak <= 0.000001) return sceneLinear;

    float whiteAnchor = clamp(0.82 - 0.04 * (bracketStops - 1.0), 0.68, 0.82);
    float displayCeiling = clamp(whiteAnchor + 0.14, 0.84, 0.96);
    float mappedPeak;

    if (scenePeak <= 1.0) {
        float t = clamp((scenePeak - knee) / (1.0 - knee), 0.0, 1.0);
        mappedPeak = mix(knee, whiteAnchor, t);
    } else {
        float headroomLog2 = max(log2(max(ratio, 1.0001)), 0.0001);
        float t = clamp(log2(scenePeak) / headroomLog2, 0.0, 1.0);
        mappedPeak = mix(whiteAnchor, displayCeiling, t);
    }

    return sceneLinear * (mappedPeak / scenePeak);
}

float linearLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float encodedLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

vec3 applyDisplayGamma(vec3 rgb, float gammaValue) {
    float y = linearLuma(rgb);
    if (y <= 0.000001) return rgb;
    float gamma = clamp(gammaValue, 0.50, 2.00);
    float mappedY = pow(clamp(y, 0.0, 1.0), 1.0 / gamma);
    float requestedScale = mappedY / y;
    float gamutScale = 1.0 / max(max3(rgb), 0.000001);
    // Gamma is fail-closed for saturated colors: preserve RGB ratios rather than
    // independently clipping channels when a positive midtone lift hits gamut.
    return rgb * min(requestedScale, gamutScale);
}

float secondLargest3(vec3 rgb) {
    float maximum = max3(rgb);
    float minimum = min(rgb.r, min(rgb.g, rgb.b));
    return rgb.r + rgb.g + rgb.b - maximum - minimum;
}

float gamutScaleForComponent(float currentScale, float targetY, float chroma) {
    if (chroma > 0.000001) return min(currentScale, (1.0 - targetY) / chroma);
    if (chroma < -0.000001) return min(currentScale, targetY / (-chroma));
    return currentScale;
}

vec3 highlightColorOwnership(
        vec3 targetDisplayLinear,
        vec3 longScene,
        vec3 shortScene,
        vec3 longRgb,
        vec3 shortRgb,
        float highlightWeight) {
    float targetY = linearLuma(targetDisplayLinear);
    float longY = linearLuma(longScene);
    if (targetY <= 0.000001 || longY <= 0.000001) return targetDisplayLinear;

    vec3 owned = longScene * (targetY / longY);

    // A single high red/orange channel does not surrender color ownership to SHORT.
    // SHORT chromaticity participates only after at least two LONG channels are
    // genuinely near clipping and SHORT itself has useful signal.
    float multiChannelClip = smoothstep(0.985, 0.998, secondLargest3(longRgb));
    float shortSignal = smoothstep(0.025, 0.10, max3(shortRgb));
    float shortColorNeed = clamp(highlightWeight * multiChannelClip * shortSignal, 0.0, 1.0);
    float shortY = linearLuma(shortScene);
    if (shortColorNeed > 0.0005 && shortY > 0.000001) {
        vec3 shortOwned = shortScene * (targetY / shortY);
        owned = mix(owned, shortOwned, shortColorNeed);
    }

    float ownedY = linearLuma(owned);
    vec3 chroma = owned - vec3(ownedY);
    float gamutScale = 1.0;
    gamutScale = gamutScaleForComponent(gamutScale, targetY, chroma.r);
    gamutScale = gamutScaleForComponent(gamutScale, targetY, chroma.g);
    gamutScale = gamutScaleForComponent(gamutScale, targetY, chroma.b);
    return clamp(vec3(targetY) + chroma * clamp(gamutScale, 0.0, 1.0), 0.0, 1.0);
}

// IRIS_V210_VISUAL_LOSS_BEGIN
float shortChromaFraction(vec3 rgb) {
    float peak = max3(rgb);
    float floorValue = min(rgb.r, min(rgb.g, rgb.b));
    return (peak - floorValue) / max(peak, 0.02);
}

float stillScalarRadiometricRatio(vec3 shortRgb, vec3 longRgb) {
    vec3 mappedShort = srgbToLinear(shortRgb) * stillShortScalarGain;
    vec3 longLinear = srgbToLinear(longRgb);
    return linearLuma(mappedShort) / max(linearLuma(longLinear), 0.00001);
}

float stillShortValidity(vec3 shortRgb) {
    float shortY = encodedLuma(shortRgb);
    float shortPeak = max3(shortRgb);
    return smoothstep(0.07, 0.13, shortY)
        * (1.0 - smoothstep(0.94, 0.975, shortPeak));
}

vec2 encodedGradientAt(sampler2D sourceSampler, vec2 sampleUv, vec2 texel) {
    float tl = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2(-1.0, -1.0), vec2(0.0), vec2(1.0))).rgb);
    float tc = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2( 0.0, -1.0), vec2(0.0), vec2(1.0))).rgb);
    float tr = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2( 1.0, -1.0), vec2(0.0), vec2(1.0))).rgb);
    float ml = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2(-1.0,  0.0), vec2(0.0), vec2(1.0))).rgb);
    float mr = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2( 1.0,  0.0), vec2(0.0), vec2(1.0))).rgb);
    float bl = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2(-1.0,  1.0), vec2(0.0), vec2(1.0))).rgb);
    float bc = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2( 0.0,  1.0), vec2(0.0), vec2(1.0))).rgb);
    float br = encodedLuma(texture(sourceSampler, clamp(sampleUv + texel * vec2( 1.0,  1.0), vec2(0.0), vec2(1.0))).rgb);
    float gx = (tr + 2.0 * mr + br - tl - 2.0 * ml - bl) * 0.125;
    float gy = (bl + 2.0 * bc + br - tl - 2.0 * tc - tr) * 0.125;
    return vec2(gx, gy);
}

float logRadiometricRatioAt(vec2 sampleUv) {
    vec3 shortRgb = texture(shortTex, sampleUv).rgb;
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    return log2(max(stillScalarRadiometricRatio(shortRgb, longRgb), 0.0001));
}

float radiometricSmoothnessAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    float centerLogRatio = logRadiometricRatioAt(sampleUv);
    float ratioDeviation = 0.0;
    ratioDeviation += abs(centerLogRatio - logRadiometricRatioAt(clamp(sampleUv + vec2( sourceTexel.x, 0.0), vec2(0.0), vec2(1.0))));
    ratioDeviation += abs(centerLogRatio - logRadiometricRatioAt(clamp(sampleUv + vec2(-sourceTexel.x, 0.0), vec2(0.0), vec2(1.0))));
    ratioDeviation += abs(centerLogRatio - logRadiometricRatioAt(clamp(sampleUv + vec2(0.0,  sourceTexel.y), vec2(0.0), vec2(1.0))));
    ratioDeviation += abs(centerLogRatio - logRadiometricRatioAt(clamp(sampleUv + vec2(0.0, -sourceTexel.y), vec2(0.0), vec2(1.0))));
    ratioDeviation *= 0.25;
    return 1.0 - smoothstep(0.07, 0.24, ratioDeviation);
}

float mappedShortLinearLumaAt(vec2 sampleUv) {
    return linearLuma(srgbToLinear(texture(shortTex, sampleUv).rgb) * stillShortScalarGain);
}

float longLinearLumaAt(vec2 sampleUv) {
    return linearLuma(srgbToLinear(texture(longTex, sampleUv).rgb));
}

vec2 localLinearRangeAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * 2.0;
    float shortMin = mappedShortLinearLumaAt(sampleUv);
    float shortMax = shortMin;
    float longMin = longLinearLumaAt(sampleUv);
    float longMax = longMin;
    vec2 offsets[8] = vec2[8](
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    for (int i = 0; i < 8; ++i) {
        vec2 q = clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0));
        float shortY = mappedShortLinearLumaAt(q);
        float longY = longLinearLumaAt(q);
        shortMin = min(shortMin, shortY);
        shortMax = max(shortMax, shortY);
        longMin = min(longMin, longY);
        longMax = max(longMax, longY);
    }
    return vec2(shortMax - shortMin, longMax - longMin);
}

float stillStaticConfidenceAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 shortGrad = encodedGradientAt(shortTex, sampleUv, sourceTexel);
    vec2 longGrad = encodedGradientAt(longTex, sampleUv, sourceTexel);
    float shortMag = length(shortGrad);
    float longMag = length(longGrad);
    float bothMag = min(shortMag, longMag);
    float maxMag = max(shortMag, longMag);
    float directionCos = dot(shortGrad, longGrad) / max(shortMag * longMag, 0.000001);
    float ratioSmooth = radiometricSmoothnessAt(sampleUv);
    float flatSupport = (1.0 - smoothstep(0.005, 0.007, maxMag)) * ratioSmooth;
    float gradientSupport = smoothstep(0.45, 0.90, directionCos)
        * smoothstep(0.004, 0.018, bothMag)
        * smoothstep(0.15, 0.70, ratioSmooth);

    // Saturation can legitimately erase a LONG edge that SHORT still sees. Admit
    // that mismatch only in an already-bright multi-channel LONG regime, never as
    // a generic temporal-difference detector.
    float explainedLostEdge = smoothstep(0.90, 0.975, secondLargest3(texture(longTex, sampleUv).rgb))
        * smoothstep(0.006, 0.024, shortMag - 1.10 * longMag);
    return max(flatSupport, max(gradientSupport, explainedLostEdge));
}

float effectiveGradientCorrespondenceAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 shortGrad = encodedGradientAt(shortTex, sampleUv, sourceTexel);
    vec2 longGrad = encodedGradientAt(longTex, sampleUv, sourceTexel);
    float shortMag = length(shortGrad);
    float longMag = length(longGrad);
    float directionCos = dot(shortGrad, longGrad) / max(shortMag * longMag, 0.000001);
    return smoothstep(0.35, 0.82, directionCos)
        * smoothstep(0.002, 0.012, longMag)
        * smoothstep(0.003, 0.015, shortMag);
}

vec3 normalizedColorSignature(vec3 rgb) {
    return rgb / max(rgb.r + rgb.g + rgb.b, 0.02);
}

float longNeighborColorSupport(vec3 shortSignature, vec2 sampleUv) {
    vec3 longRgb = texture(longTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;
    float undamaged = 1.0 - smoothstep(0.90, 0.97, secondLargest3(longRgb));
    float signal = smoothstep(0.18, 0.55, encodedLuma(longRgb));
    float colorDistance = length(shortSignature - normalizedColorSignature(longRgb));
    return undamaged * signal * (1.0 - smoothstep(0.055, 0.18, colorDistance));
}

float chromaTopologySupportAt(vec2 sampleUv) {
    vec3 shortSignature = normalizedColorSignature(texture(shortTex, sampleUv).rgb);
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 nearRadius = sourceTexel * 2.0;
    vec2 farRadius = sourceTexel * 6.0;
    float support = longNeighborColorSupport(shortSignature, sampleUv);
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2( nearRadius.x, 0.0)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2(-nearRadius.x, 0.0)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2(0.0,  nearRadius.y)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2(0.0, -nearRadius.y)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2( farRadius.x, 0.0)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2(-farRadius.x, 0.0)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2(0.0,  farRadius.y)));
    support = max(support, longNeighborColorSupport(shortSignature, sampleUv + vec2(0.0, -farRadius.y)));
    return support;
}

float visualLossProofAt(vec2 sampleUv) {
    vec3 shortRgb = texture(shortTex, sampleUv).rgb;
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    float longSecond = secondLargest3(longRgb);
    float longBright = smoothstep(0.72, 0.90, encodedLuma(longRgb));
    float ratioProof = stillScalarRadiometricRatio(shortRgb, longRgb);
    vec2 ranges = localLinearRangeAt(sampleUv);
    float shortRange = ranges.x;
    float longRange = ranges.y;

    float hardLoss = smoothstep(0.985, 0.998, longSecond)
        * smoothstep(1.08, 1.30, ratioProof);
    float rangeLoss = smoothstep(0.004, 0.020, shortRange)
        * smoothstep(0.002, 0.020, shortRange - 1.12 * longRange)
        * effectiveGradientCorrespondenceAt(sampleUv);
    float shortChroma = shortChromaFraction(shortRgb);
    float longChroma = shortChromaFraction(longRgb);
    float chromaLoss = smoothstep(0.08, 0.18, shortChroma)
        * smoothstep(0.025, 0.10, shortChroma - longChroma)
        * chromaTopologySupportAt(sampleUv);
    float effectiveRegime = smoothstep(0.90, 0.975, longSecond) * longBright;
    float effectiveLoss = effectiveRegime
        * max(rangeLoss, chromaLoss)
        * smoothstep(0.96, 1.06, ratioProof);
    return max(hardLoss, effectiveLoss);
}

float broadSeedAt(vec2 sampleUv) {
    vec3 shortRgb = texture(shortTex, sampleUv).rgb;
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    return smoothstep(0.68, 0.86, encodedLuma(longRgb))
        * stillShortValidity(shortRgb)
        * visualLossProofAt(sampleUv)
        * stillStaticConfidenceAt(sampleUv);
}

float broadCenterProofAt(vec2 sampleUv) {
    vec3 shortRgb = texture(shortTex, sampleUv).rgb;
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    float loss = visualLossProofAt(sampleUv);
    float staticConfidence = stillStaticConfidenceAt(sampleUv);
    return smoothstep(0.72, 0.90, encodedLuma(longRgb))
        * smoothstep(0.55, 0.82, stillShortValidity(shortRgb))
        * smoothstep(0.45, 0.78, loss)
        * smoothstep(0.45, 0.75, staticConfidence);
}

float shortSaturationContextAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(shortTex, 0));
    vec2 radius = sourceTexel * 3.0;
    float risk = 0.0;
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2( radius.x, 0.0), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2(0.0,  radius.y), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2( radius.x,  radius.y), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2(-radius.x,  radius.y), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2( radius.x, -radius.y), vec2(0.0), vec2(1.0))).rgb));
    risk += step(0.975, max3(texture(shortTex, clamp(sampleUv + vec2(-radius.x, -radius.y), vec2(0.0), vec2(1.0))).rgb));
    return risk * 0.125;
}

float compactBaseSeedAt(vec2 sampleUv) {
    vec3 shortRgb = texture(shortTex, sampleUv).rgb;
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    float exactClip = smoothstep(0.997, 0.9995, secondLargest3(longRgb))
        * smoothstep(0.995, 0.9995, max3(longRgb));
    return exactClip
        * stillShortValidity(shortRgb)
        * smoothstep(1.35, 1.80, stillScalarRadiometricRatio(shortRgb, longRgb))
        * smoothstep(0.40, 0.72, stillStaticConfidenceAt(sampleUv));
}

float compactNeighborhoodSupportAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * 2.0;
    float support = compactBaseSeedAt(sampleUv);
    support += compactBaseSeedAt(clamp(sampleUv + vec2( radius.x, 0.0), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2(0.0,  radius.y), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2( radius.x,  radius.y), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2(-radius.x,  radius.y), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2( radius.x, -radius.y), vec2(0.0), vec2(1.0)));
    support += compactBaseSeedAt(clamp(sampleUv + vec2(-radius.x, -radius.y), vec2(0.0), vec2(1.0)));
    return support / 9.0;
}

vec3 gamutSafeScaleToLuma(vec3 sourceLinear, float targetY) {
    float sourceY = linearLuma(sourceLinear);
    if (sourceY <= 0.000001) return vec3(targetY);
    vec3 scaled = sourceLinear * (targetY / sourceY);
    float peak = max3(scaled);
    if (peak > 1.0) scaled /= peak;
    return clamp(scaled, 0.0, 1.0);
}

vec3 recoveredSourceDisplay(
        vec2 sampleUv,
        vec3 longDisplay,
        float ratio,
        float bracketStops,
        float brightnessGain,
        float sourceWeight,
        float ceilingY) {
    vec3 shortLinear = srgbToLinear(texture(shortTex, sampleUv).rgb);
    vec3 mappedShortScene = shortLinear * stillShortScalarGain * brightnessGain;
    vec3 mappedShortDisplay = adaptiveHdrToneMap(mappedShortScene, ratio, bracketStops);
    float longY = linearLuma(longDisplay);
    float shortY = linearLuma(mappedShortDisplay);
    float targetY = clamp(mix(longY, shortY, sourceWeight), 0.0, ceilingY);
    return gamutSafeScaleToLuma(shortLinear, targetY);
}
// IRIS_V210_VISUAL_LOSS_END

vec3 applyPhotographicBodyTone(vec3 rgb) {
    // Global SDR photographic tone reproduction: anchor true blacks, lift the
    // body/midtones modestly, and make that lift exactly disappear before the
    // existing HDR shoulder starts at 0.70. No local contrast/pop operator.
    float y = linearLuma(rgb);
    if (y <= 0.000001) return rgb;
    float toe = smoothstep(0.015, 0.090, y);
    float highlightProtect = 1.0 - smoothstep(0.45, 0.68, y);
    float targetY = y + 0.45 * toe * highlightProtect * y * (1.0 - clamp(y, 0.0, 1.0));
    float requestedScale = targetY / y;
    float gamutScale = 1.0 / max(max3(rgb), 0.000001);
    return rgb * min(requestedScale, gamutScale);
}

void main() {
    vec2 uv;
    if (!fitSourceUv(vUv, fullFitScale, uv)) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    if (mode == 0) {
        outColor = vec4(fallbackColor(uv), 1.0);
        return;
    }

    if (mode == 1) {
        if (haveShort == 0 || haveLong == 0) {
            outColor = vec4(fallbackColor(uv), 1.0);
            return;
        }
        bool leftHalf = vUv.x < 0.5;
        vec2 localUv = vec2(
            leftHalf ? vUv.x * 2.0 : (vUv.x - 0.5) * 2.0,
            vUv.y);
        vec2 splitUv;
        if (!fitSourceUv(localUv, splitFitScale, splitUv)) {
            outColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        outColor = vec4(
            leftHalf ? texture(shortTex, splitUv).rgb : texture(longTex, splitUv).rgb,
            1.0);
        return;
    }

    if (haveShort == 0 || haveLong == 0) {
        outColor = vec4(fallbackColor(uv), 1.0);
        return;
    }

    // V2.10 saved fusion remains GPU-only and deliberately multi-pass. mode==2 below
    // is the byte-preserved V2.8 live path. mode==3 builds hard/effective-loss evidence,
    // mode==4 enforces low-frequency/isotropic broad support, and mode==5 composes
    // the final provenance image.  No mode cross-blends temporally uncertain data.
    if (mode == 3) {
        vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
        vec2 radius = sourceTexel * 3.0;
        float broadAverage = broadSeedAt(uv);
        broadAverage += broadSeedAt(clamp(uv + vec2( radius.x, 0.0), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2(0.0,  radius.y), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2( radius.x,  radius.y), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2(-radius.x,  radius.y), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2( radius.x, -radius.y), vec2(0.0), vec2(1.0)));
        broadAverage += broadSeedAt(clamp(uv + vec2(-radius.x, -radius.y), vec2(0.0), vec2(1.0)));
        broadAverage /= 9.0;
        outColor = vec4(
            broadAverage,
            broadCenterProofAt(uv),
            stillStaticConfidenceAt(uv),
            shortSaturationContextAt(uv));
        return;
    }

    if (mode == 4) {
        vec2 analysisTexel = 1.0 / vec2(textureSize(normalTex, 0));
        vec4 centerEvidence = texture(normalTex, uv);
        float isotropic = centerEvidence.r;
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2( analysisTexel.x, 0.0), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(-analysisTexel.x, 0.0), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(0.0,  analysisTexel.y), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(0.0, -analysisTexel.y), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(2.0 * analysisTexel.x, 0.0), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(-2.0 * analysisTexel.x, 0.0), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(0.0, 2.0 * analysisTexel.y), vec2(0.0), vec2(1.0))).r);
        isotropic = min(isotropic, texture(normalTex, clamp(uv + vec2(0.0, -2.0 * analysisTexel.y), vec2(0.0), vec2(1.0))).r);

        float broadAverage = centerEvidence.r;
        broadAverage += texture(normalTex, clamp(uv + vec2( analysisTexel.x, 0.0), vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv + vec2(-analysisTexel.x, 0.0), vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv + vec2(0.0,  analysisTexel.y), vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv + vec2(0.0, -analysisTexel.y), vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv + analysisTexel, vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv - analysisTexel, vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv + vec2( analysisTexel.x, -analysisTexel.y), vec2(0.0), vec2(1.0))).r;
        broadAverage += texture(normalTex, clamp(uv + vec2(-analysisTexel.x,  analysisTexel.y), vec2(0.0), vec2(1.0))).r;
        broadAverage /= 9.0;

        float shortNeighborhoodSafety = 1.0 - smoothstep(0.30, 0.75, centerEvidence.a);
        float broadCore = smoothstep(0.63, 0.69, isotropic)
            * smoothstep(0.78, 0.84, broadAverage)
            * smoothstep(0.66, 0.74, centerEvidence.g)
            * smoothstep(0.68, 0.76, centerEvidence.b)
            * shortNeighborhoodSafety;
        float featherSupport = smoothstep(0.32, 0.62, isotropic)
            * smoothstep(0.55, 0.80, broadAverage)
            * smoothstep(0.45, 0.76, centerEvidence.g)
            * smoothstep(0.62, 0.88, centerEvidence.b)
            * shortNeighborhoodSafety;
        outColor = vec4(broadCore, featherSupport, broadAverage, centerEvidence.a);
        return;
    }

    if (mode == 5) {
        // IRIS_V211_SCENE_DOMAIN_PROVENANCE_BEGIN
        // Saved fusion chooses source provenance in scene-linear space first, then
        // uses the same global HDR presentation ordering as live mode 2. A SHORT
        // sample that is too clipped for trustworthy RGB may still prove a radiance
        // lower bound; that can keep a genuinely clipped emitter bright, but it may
        // not invent texture or hue.
        float ratio = clamp(exposureRatio, 1.0, 65536.0);
        float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
        vec3 shortRgb = texture(shortTex, uv).rgb;
        vec3 longRgb = texture(longTex, uv).rgb;
        vec3 longScene = srgbToLinear(longRgb);
        vec3 shortScene = srgbToLinear(shortRgb) * stillShortScalarGain;

        float registrationGate = smoothstep(0.58, 0.78, stillRegistrationConfidence);
        vec4 support = texture(normalTex, uv);
        float shortNeighborhoodSafety = 1.0 - smoothstep(0.30, 0.75, shortSaturationContextAt(uv));
        float shortColorConfidence = smoothstep(0.55, 0.82, stillShortValidity(shortRgb));
        float broadCore = step(0.78, support.r)
            * shortNeighborhoodSafety
            * registrationGate;

        vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
        vec2 shortGrad = encodedGradientAt(shortTex, uv, sourceTexel);
        vec2 longGrad = encodedGradientAt(longTex, uv, sourceTexel);
        float shortMag = length(shortGrad);
        float longMag = length(longGrad);
        float compactStructure = max(
            1.0 - smoothstep(0.005, 0.007, max(shortMag, longMag)),
            smoothstep(0.009, 0.013, longMag));
        float compactSeed = compactBaseSeedAt(uv);
        float compactSupport = compactNeighborhoodSupportAt(uv);
        float compactCore = step(0.65, compactSeed)
            * smoothstep(0.11, 0.16, compactSupport)
            * compactStructure
            * registrationGate;

        // The 1/8-resolution support atlas is spatially coherent. Full-resolution
        // SHORT validity now modulates color ownership continuously rather than
        // switching individual pixels between two post-toned images.
        float core = max(broadCore, compactCore);
        float coreOwnership = core * shortColorConfidence;
        float boundaryOwnership = 0.20
            * support.g
            * shortNeighborhoodSafety
            * shortColorConfidence
            * registrationGate
            * (1.0 - core);
        float shortOwnership = clamp(max(coreOwnership, boundaryOwnership), 0.0, 1.0);
        vec3 mergedScene = mix(longScene, shortScene, shortOwnership);

        // If LONG is genuinely multi-channel clipped, a registered/coherent SHORT
        // observation can still prove that scene radiance is much higher even when
        // SHORT itself is too saturated to own complete RGB. Raise only the scene
        // radiance lower bound while preserving the already-selected source chroma;
        // do not synthesize detail from the saturated SHORT sample.
        float longHardClip = smoothstep(0.985, 0.998, secondLargest3(longRgb));
        float shortSignal = smoothstep(0.07, 0.13, encodedLuma(shortRgb));
        float staticRadianceSupport = smoothstep(0.45, 0.72, stillStaticConfidenceAt(uv));
        float radianceFloorWeight = longHardClip
            * shortSignal
            * staticRadianceSupport
            * registrationGate;
        float mergedY = linearLuma(mergedScene);
        float shortSceneY = linearLuma(shortScene);
        if (radianceFloorWeight > 0.0005 && mergedY > 0.000001 && shortSceneY > mergedY) {
            vec3 radianceRaised = mergedScene * (shortSceneY / mergedY);
            mergedScene = mix(mergedScene, radianceRaised, radianceFloorWeight);
        }

        // Presentation happens once, after provenance/radiance composition. This
        // prevents displayBrightnessEv from turning a source-proven clipped light
        // into a fixed mid-gray plateau and preserves source-supported SHORT RGB.
        float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
        vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
        displayLinear = applyDisplayGamma(displayLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(displayLinear), 0.0, 1.0), 1.0);
        // IRIS_V211_SCENE_DOMAIN_PROVENANCE_END
        return;
    }

    float ratio = clamp(exposureRatio, 1.0, 65536.0);
    float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
    vec3 shortRgb = texture(shortTex, uv).rgb;
    vec3 longRgb = texture(longTex, uv).rgb;
    vec3 shortScene = srgbToLinear(shortRgb) * ratio;
    vec3 longScene = srgbToLinear(longRgb);

    float longEncodedPeak = max3(longRgb);
    float longScenePeak = max(max3(longScene), 0.000001);
    float shortScenePeak = max3(shortScene);
    float shortConfidence = smoothstep(0.35, 0.65, shortScenePeak / longScenePeak);
    float highlightWeight = smoothstep(
        adaptiveClipStart(bracketStops),
        0.995,
        longEncodedPeak) * shortConfidence;

    // V2.9: this remainder is live mode==2 only. Its V2.8 physical-ratio HDR
    // equations are preserved; saved still fusion returned above through modes 3/4/5.
    vec3 mergedScene = mix(longScene, shortScene, highlightWeight);

    // Brightness remains presentation-only. The photographic body curve is global,
    // RGB-ratio preserving, and fades to zero before the 0.70 HDR shoulder, so
    // recovered highlights retain their existing headroom while the SDR body reads
    // brighter/cleaner without a local-HDR "pop" look.
    float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
    vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
    vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
    displayLinear = applyDisplayGamma(displayLinear, displayGamma);

    if (highlightWeight > 0.0005) {
        displayLinear = highlightColorOwnership(
                displayLinear, longScene, shortScene, longRgb, shortRgb, highlightWeight);
    }
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
