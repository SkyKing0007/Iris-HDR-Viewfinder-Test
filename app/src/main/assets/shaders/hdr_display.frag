#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D normalTex;
uniform sampler2D shortTex;
uniform sampler2D longTex;
uniform sampler2D localFlowTex;
uniform int mode;
uniform int rotationQuarterTurns;
uniform int haveNormal;
uniform int haveShort;
uniform int haveLong;
uniform float exposureRatio;
uniform float displayBrightnessEv;
uniform float displayGamma;
uniform float displayDehaze;
uniform float displayMicroContrast;
uniform float stillRegistrationConfidence;
uniform vec3 stillShortLinearGain;
uniform float stillShortScalarGain;
uniform int haveLocalFlow;
uniform vec2 stillImageSize;
uniform float localFlowMaxPixels;
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

// IRIS_V214_STRICT_SOURCE_PROVENANCE_BEGIN
float shortChromaFraction(vec3 rgb) {
    float peak = max3(rgb);
    float floorValue = min(rgb.r, min(rgb.g, rgb.b));
    return (peak - floorValue) / max(peak, 0.02);
}

vec4 stillLocalFlowAt(vec2 sampleUv) {
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) {
        return vec4(0.5, 0.5, 0.0, 1.0);
    }
    return texture(localFlowTex, clamp(sampleUv, vec2(0.0), vec2(1.0)));
}

float stillLocalRegistrationConfidenceAt(vec2 sampleUv) {
    vec4 flowValue = stillLocalFlowAt(sampleUv);
    return clamp(stillRegistrationConfidence * flowValue.b, 0.0, 1.0);
}

vec2 stillShortUvAt(vec2 sampleUv) {
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) {
        return clamp(sampleUv, vec2(0.0), vec2(1.0));
    }
    vec4 flowValue = stillLocalFlowAt(sampleUv);
    vec2 residualPixels = (flowValue.rg * 2.0 - vec2(1.0)) * localFlowMaxPixels;
    vec2 imageSize = max(stillImageSize, vec2(1.0));
    return clamp(sampleUv + residualPixels / imageSize, vec2(0.0), vec2(1.0));
}

vec3 stillShortRgbAt(vec2 sampleUv) {
    return texture(shortTex, stillShortUvAt(sampleUv)).rgb;
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

vec2 encodedStillShortGradientAt(vec2 sampleUv, vec2 texel) {
    float tl = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2(-1.0, -1.0), vec2(0.0), vec2(1.0))));
    float tc = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2( 0.0, -1.0), vec2(0.0), vec2(1.0))));
    float tr = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2( 1.0, -1.0), vec2(0.0), vec2(1.0))));
    float ml = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2(-1.0,  0.0), vec2(0.0), vec2(1.0))));
    float mr = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2( 1.0,  0.0), vec2(0.0), vec2(1.0))));
    float bl = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2(-1.0,  1.0), vec2(0.0), vec2(1.0))));
    float bc = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2( 0.0,  1.0), vec2(0.0), vec2(1.0))));
    float br = encodedLuma(stillShortRgbAt(clamp(sampleUv + texel * vec2( 1.0,  1.0), vec2(0.0), vec2(1.0))));
    float gx = (tr + 2.0 * mr + br - tl - 2.0 * ml - bl) * 0.125;
    float gy = (bl + 2.0 * bc + br - tl - 2.0 * tc - tr) * 0.125;
    return vec2(gx, gy);
}

float logRadiometricRatioAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
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
    return linearLuma(srgbToLinear(stillShortRgbAt(sampleUv)) * stillShortScalarGain);
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
    vec2 shortGrad = encodedStillShortGradientAt(sampleUv, sourceTexel);
    vec2 longGrad = encodedGradientAt(longTex, sampleUv, sourceTexel);
    float shortMag = length(shortGrad);
    float longMag = length(longGrad);
    float bothMag = min(shortMag, longMag);
    float maxMag = max(shortMag, longMag);
    float directionCos = dot(shortGrad, longGrad) / max(shortMag * longMag, 0.000001);
    float ratioSmooth = radiometricSmoothnessAt(sampleUv);
    float flatSupport = (1.0 - smoothstep(0.005, 0.009, maxMag)) * ratioSmooth;
    float gradientSupport = smoothstep(0.45, 0.90, directionCos)
        * smoothstep(0.004, 0.018, bothMag)
        * smoothstep(0.15, 0.70, ratioSmooth);
    // LONG may have lost the very edge that SHORT is supposed to recover. This
    // path requires a bright LONG regime plus stronger SHORT structure, and is
    // still gated by the independently estimated local registration field below.
    float explainedLostEdge = smoothstep(0.68, 0.90, encodedLuma(texture(longTex, sampleUv).rgb))
        * smoothstep(0.006, 0.024, shortMag - 1.08 * longMag);
    float localGeometry = smoothstep(
        0.28, 0.68, stillLocalRegistrationConfidenceAt(sampleUv));
    return max(flatSupport, max(gradientSupport, explainedLostEdge)) * localGeometry;
}

float fullResolutionSourceAgreementAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 shortGrad = encodedStillShortGradientAt(sampleUv, sourceTexel);
    vec2 longGrad = encodedGradientAt(longTex, sampleUv, sourceTexel);
    float shortMag = length(shortGrad);
    float longMag = length(longGrad);
    float maxMag = max(shortMag, longMag);
    float directionCos = dot(shortGrad, longGrad) / max(shortMag * longMag, 0.000001);
    float flatAgreement = (1.0 - smoothstep(0.004, 0.012, maxMag))
        * radiometricSmoothnessAt(sampleUv);
    float edgeAgreement = smoothstep(0.60, 0.92, directionCos)
        * smoothstep(0.003, 0.014, min(shortMag, longMag))
        * smoothstep(0.40, 0.80, radiometricSmoothnessAt(sampleUv));
    return max(flatAgreement, edgeAgreement)
        * smoothstep(0.30, 0.70, stillLocalRegistrationConfidenceAt(sampleUv));
}

float visualLossProofAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    float localGeometry = smoothstep(
        0.28, 0.68, stillLocalRegistrationConfidenceAt(sampleUv));
    float shortValid = smoothstep(0.45, 0.78, stillShortValidity(shortRgb));
    // Visual evidence from the V2.13 3072x4096 office capture shows that LONG
    // can lose real ground/foliage/road texture well before encoded near-white.
    // Start effective-loss eligibility in the bright body, then require SHORT
    // range dominance plus independently proven local registration below.
    float longBright = smoothstep(0.45, 0.68, encodedLuma(longRgb));
    float longSecond = secondLargest3(longRgb);
    float hardLoss = smoothstep(0.975, 0.997, longSecond)
        * shortValid * localGeometry;

    vec2 ranges = localLinearRangeAt(sampleUv);
    float shortRange = ranges.x;
    float longRange = ranges.y;
    float rangeDominance = smoothstep(0.004, 0.020, shortRange)
        * smoothstep(0.002, 0.020, shortRange - 1.08 * longRange);
    float ratioLogError = abs(logRadiometricRatioAt(sampleUv));
    float radiometricAgreement = 1.0 - smoothstep(0.20, 0.60, ratioLogError);
    float effectiveLoss = longBright
        * rangeDominance
        * smoothstep(0.30, 0.78, radiometricAgreement)
        * shortValid
        * localGeometry;
    return max(hardLoss, effectiveLoss);
}

float broadSeedAt(vec2 sampleUv) {
    // visualLossProofAt already requires: bright/effectively damaged LONG,
    // valid SHORT detail, range dominance, radiometric agreement, and local
    // registration. Do not square those gates here: the V2.13 device crop proved
    // that doing so rejects exactly the washed exterior detail SHORT must own.
    return visualLossProofAt(sampleUv);
}

float broadCenterProofAt(vec2 sampleUv) {
    // LONG is allowed to have lost the edge being recovered, so LONG/SHORT
    // per-pixel gradient correspondence is not an additional ownership veto.
    // Spatial coherence is enforced by the mode-4 broad-region consensus.
    return visualLossProofAt(sampleUv);
}

float shortSaturationContextAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(shortTex, 0));
    vec2 radius = sourceTexel * 3.0;
    float risk = 0.0;
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2( radius.x, 0.0), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2(0.0,  radius.y), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2( radius.x,  radius.y), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2(-radius.x,  radius.y), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2( radius.x, -radius.y), vec2(0.0), vec2(1.0)))));
    risk += step(0.975, max3(stillShortRgbAt(clamp(sampleUv + vec2(-radius.x, -radius.y), vec2(0.0), vec2(1.0)))));
    return risk * 0.125;
}

float compactBaseSeedAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    float exactClip = smoothstep(0.990, 0.999, secondLargest3(longRgb))
        * smoothstep(0.990, 0.999, max3(longRgb));
    return exactClip
        * smoothstep(0.45, 0.78, stillShortValidity(shortRgb))
        * smoothstep(0.30, 0.72, stillLocalRegistrationConfidenceAt(sampleUv));
}

float compactNeighborhoodSupportAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * 2.0;
    float supportValue = compactBaseSeedAt(sampleUv);
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2( radius.x, 0.0), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2(0.0,  radius.y), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2( radius.x,  radius.y), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2(-radius.x,  radius.y), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2( radius.x, -radius.y), vec2(0.0), vec2(1.0)));
    supportValue += compactBaseSeedAt(clamp(sampleUv + vec2(-radius.x, -radius.y), vec2(0.0), vec2(1.0)));
    return supportValue / 9.0;
}
// IRIS_V214_STRICT_SOURCE_PROVENANCE_END

// IRIS_V212_ADAPTIVE_CLARITY_BEGIN
float presentationGuideLumaAt(vec2 sampleUv) {
    // Saved mode 6 guides clarity from the already source-proven FUSED image.
    // Live mode 2 retains its established LONG guide.  This prevents a LONG-only
    // local pattern/color decision from being painted back over SHORT-owned stills.
    vec3 encodedGuide = mode == 6
        ? texture(normalTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb
        : texture(longTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;
    return linearLuma(srgbToLinear(encodedGuide));
}

float guideRangeWeight(float centerGuide, float neighborGuide) {
    float relativeDifference = abs(neighborGuide - centerGuide) / max(centerGuide, 0.03);
    return 1.0 - smoothstep(0.10, 0.30, relativeDifference);
}

vec3 applyAdaptiveClarity(vec3 rgb, vec2 sampleUv) {
    float y = linearLuma(rgb);
    if (y <= 0.000001) return rgb;

    // Five-tap, luminance-only range guide. The cross is symmetric, and neighbors
    // across a strong luminance boundary lose weight before the local base is formed,
    // so shutters/window frames/lamp edges cannot create a broad clarity halo.
    vec2 texel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = 6.0 * texel;
    float centerGuide = presentationGuideLumaAt(sampleUv);
    float guideXp = presentationGuideLumaAt(sampleUv + vec2( radius.x, 0.0));
    float guideXm = presentationGuideLumaAt(sampleUv + vec2(-radius.x, 0.0));
    float guideYp = presentationGuideLumaAt(sampleUv + vec2(0.0,  radius.y));
    float guideYm = presentationGuideLumaAt(sampleUv + vec2(0.0, -radius.y));
    float weightXp = guideRangeWeight(centerGuide, guideXp);
    float weightXm = guideRangeWeight(centerGuide, guideXm);
    float weightYp = guideRangeWeight(centerGuide, guideYp);
    float weightYm = guideRangeWeight(centerGuide, guideYm);
    float weightSum = 1.0 + weightXp + weightXm + weightYp + weightYm;
    float localBase = (centerGuide
            + guideXp * weightXp + guideXm * weightXm
            + guideYp * weightYp + guideYm * weightYm) / max(weightSum, 1.0);

    float relativeDetail = abs(centerGuide - localBase) / max(localBase, 0.02);
    float edgeSafety = 1.0 - smoothstep(0.12, 0.42, relativeDetail);
    float signalSafety = smoothstep(0.008, 0.040, y)
        * (1.0 - smoothstep(0.55, 0.78, y));

    // "Dehaze" is a bounded luminance-only veil suppression, not an atmospheric
    // RGB dehaze model. True blacks and highlights stay anchored; strong edges are
    // excluded so shutters, window frames, lamps, and silhouettes cannot halo.
    float dehazeGate = smoothstep(0.012, 0.070, localBase)
        * (1.0 - smoothstep(0.32, 0.60, localBase))
        * edgeSafety;
    float targetY = y * (1.0 - 0.16 * clamp(displayDehaze, 0.0, 1.0) * dehazeGate);

    // Microcontrast restores only moderate source-supported luminance structure.
    // The noise floor, strong edges, and highlights remain untouched.
    float normalizedDetail = (centerGuide - localBase) / max(centerGuide, 0.02);
    targetY += y * normalizedDetail
        * (0.30 * clamp(displayMicroContrast, 0.0, 1.0))
        * signalSafety * edgeSafety;
    targetY = clamp(targetY, 0.0, 1.0);

    float requestedScale = targetY / y;
    float gamutScale = 1.0 / max(max3(rgb), 0.000001);
    return rgb * min(requestedScale, gamutScale);
}
// IRIS_V212_ADAPTIVE_CLARITY_END

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

    if (mode == 6) {
        // IRIS_V214_TOPOLOGY_SAFE_PRESENTATION_BEGIN
        // The V2.13 device failure contained disconnected gray/blue contour
        // fragments. Saved presentation is therefore pointwise and monotonic: it
        // cannot sample a neighbor, move an edge, or create a new spatial contour.
        vec3 fusedLinear = srgbToLinear(texture(normalTex, uv).rgb);
        float y = linearLuma(fusedLinear);
        if (y <= 0.000001) {
            outColor = vec4(texture(normalTex, uv).rgb, 1.0);
            return;
        }
        float exponent = 1.0
            + 0.10 * clamp(displayDehaze, 0.0, 1.0)
            + 0.04 * clamp(displayMicroContrast, 0.0, 1.0);
        float targetY = pow(clamp(y, 0.0, 1.0), exponent);
        float requestedScale = targetY / y;
        float gamutScale = 1.0 / max(max3(fusedLinear), 0.000001);
        vec3 presented = fusedLinear * min(requestedScale, gamutScale);
        outColor = vec4(clamp(linearToSrgb(presented), 0.0, 1.0), 1.0);
        // IRIS_V214_TOPOLOGY_SAFE_PRESENTATION_END
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
        // The 1/8-resolution atlas is now a broad region prior only. Nine-neighbor
        // consensus removes isolated evidence specks; this pass never owns a fine
        // source edge and therefore cannot create the gray disconnected borders
        // observed in the V2.13 FUSED crop.
        vec2 analysisTexel = 1.0 / vec2(textureSize(normalTex, 0));
        vec4 centerEvidence = texture(normalTex, uv);
        vec2 offsets[8] = vec2[8](
            vec2( analysisTexel.x, 0.0), vec2(-analysisTexel.x, 0.0),
            vec2(0.0,  analysisTexel.y), vec2(0.0, -analysisTexel.y),
            vec2( analysisTexel.x,  analysisTexel.y),
            vec2(-analysisTexel.x,  analysisTexel.y),
            vec2( analysisTexel.x, -analysisTexel.y),
            vec2(-analysisTexel.x, -analysisTexel.y));
        float seedSum = centerEvidence.r;
        float proofSum = centerEvidence.g;
        float confidenceSum = centerEvidence.b;
        float strongVotes = step(0.38, centerEvidence.r);
        for (int i = 0; i < 8; ++i) {
            vec4 evidenceValue = texture(
                normalTex, clamp(uv + offsets[i], vec2(0.0), vec2(1.0)));
            seedSum += evidenceValue.r;
            proofSum += evidenceValue.g;
            confidenceSum += evidenceValue.b;
            strongVotes += step(0.38, evidenceValue.r);
        }
        float seedAverage = seedSum / 9.0;
        float proofAverage = proofSum / 9.0;
        float confidenceAverage = confidenceSum / 9.0;
        float neighborhoodConsensus = smoothstep(3.0, 6.0, strongVotes);
        float broadRegion = smoothstep(0.26, 0.55, seedAverage)
            * smoothstep(0.22, 0.52, proofAverage)
            * neighborhoodConsensus;
        outColor = vec4(
            broadRegion,
            max(centerEvidence.g, proofAverage),
            confidenceAverage,
            centerEvidence.a);
        return;
    }

    if (mode == 5) {
        // IRIS_V214_DISCRETE_DETAIL_OWNERSHIP_BEGIN
        // Fine detail is source provenance, not an interpolation hint. Where the
        // sources disagree spatially, ownership is exactly SHORT or exactly LONG.
        // Fractional blending is permitted only where full-resolution gradients and
        // radiometry already agree, so it cannot manufacture a third displaced edge.
        float ratio = clamp(exposureRatio, 1.0, 65536.0);
        float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
        vec3 shortRgb = stillShortRgbAt(uv);
        vec3 longRgb = texture(longTex, uv).rgb;
        vec3 longScene = srgbToLinear(longRgb);
        vec3 shortScene = srgbToLinear(shortRgb) * stillShortScalarGain;
        vec4 supportValue = texture(normalTex, uv);

        float registrationGate = smoothstep(
            0.30, 0.70, stillLocalRegistrationConfidenceAt(uv));
        float shortColorConfidence = smoothstep(
            0.48, 0.80, stillShortValidity(shortRgb));
        float shortNeighborhoodSafety = 1.0
            - smoothstep(0.30, 0.75, shortSaturationContextAt(uv));
        float longHardClip = smoothstep(0.975, 0.997, secondLargest3(longRgb));
        // supportValue.r is only a coherent broad recovery-region prior. It
        // never carries fine texture and therefore cannot cut disconnected mask
        // fragments through grass, foliage, signs, or road edges. Full-resolution
        // registration/SHORT validity below decide whether SHORT may own detail.
        float broadRecovery = smoothstep(0.32, 0.60, supportValue.r);
        float compactRecovery = smoothstep(0.10, 0.18, compactNeighborhoodSupportAt(uv));
        float sourceNeed = max(longHardClip, max(broadRecovery, compactRecovery));
        float ownershipConfidence = sourceNeed
            * shortColorConfidence
            * shortNeighborhoodSafety
            * registrationGate;

        float shortCore = step(0.58, ownershipConfidence);
        float sourceAgreement = fullResolutionSourceAgreementAt(uv);
        float boundaryCandidate = smoothstep(0.34, 0.58, ownershipConfidence);
        float safeBoundaryWeight = (1.0 - shortCore)
            * boundaryCandidate
            * smoothstep(0.72, 0.92, sourceAgreement);
        float shortWeight = max(shortCore, safeBoundaryWeight);

        // A collapsed physical bracket is not HDR. Fail closed to the intended
        // highlight source instead of rendering LONG plus pseudo-HDR processing.
        float usableBracket = step(2.0, ratio);
        vec3 mergedScene = usableBracket > 0.5
            ? mix(longScene, shortScene, shortWeight)
            : shortScene;

        // Strict provenance: there is deliberately no radiance-floor invention in
        // saved fusion. If SHORT cannot own valid RGB/detail, LONG remains the source
        // even if that means an unrecovered white highlight; false peach/orange fill
        // is never preferable to honest clipping.
        float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
        vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
        displayLinear = applyDisplayGamma(displayLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(displayLinear), 0.0, 1.0), 1.0);
        // IRIS_V214_DISCRETE_DETAIL_OWNERSHIP_END
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
    displayLinear = applyAdaptiveClarity(displayLinear, uv);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
