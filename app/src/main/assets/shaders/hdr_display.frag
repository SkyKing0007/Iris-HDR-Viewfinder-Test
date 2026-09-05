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

vec3 adaptiveHdrToneMap(vec3 sceneLinear, float ratio, float bracketStops) {
    // V2.15 preserves SHORT spatial/chromatic truth. Scene values above the
    // display range are compressed pointwise while preserving SHORT RGB ratios.
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

// IRIS_V217_REVERSED_V215_LONG_TRUTH_BEGIN
// V2.17 reverses the successful V2.15 geometry contract. LONG is immutable output
// geometry and the clean spatial/chromatic/detail body. SHORT is the only source
// moved into LONG coordinates. A broad ownership atlas admits complete aligned SHORT
// RGB/detail only inside coherent regions where LONG has lost highlight information.
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
    // SHORT is the aligned auxiliary. Both global and bounded residual registration
    // address SHORT only; LONG coordinates never depend on SHORT motion.
    return texture(shortTex, stillShortUvAt(sampleUv)).rgb;
}

vec3 stillLongRgbAt(vec2 sampleUv) {
    // Exact immutable LONG body sample.
    return texture(longTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;
}

float mappedShortLinearLumaAt(vec2 sampleUv) {
    return linearLuma(srgbToLinear(stillShortRgbAt(sampleUv)) * stillShortScalarGain);
}

float longLinearLumaAt(vec2 sampleUv) {
    return linearLuma(srgbToLinear(stillLongRgbAt(sampleUv)));
}

float shortRecoveryValidityAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
    float signal = smoothstep(0.015, 0.055, encodedLuma(shortRgb));
    float headroom = 1.0 - smoothstep(0.955, 0.992, max3(shortRgb));
    return signal * headroom;
}

float registrationNeighborhoodConfidenceAt(vec2 sampleUv) {
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) return 0.0;
    vec2 flowTexel = 1.0 / vec2(textureSize(localFlowTex, 0));
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( flowTexel.x, 0.0), vec2(-flowTexel.x, 0.0),
        vec2(0.0,  flowTexel.y), vec2(0.0, -flowTexel.y),
        vec2( flowTexel.x,  flowTexel.y), vec2(-flowTexel.x,  flowTexel.y),
        vec2( flowTexel.x, -flowTexel.y), vec2(-flowTexel.x, -flowTexel.y));
    float confidenceSum = 0.0;
    float maximumConfidence = 0.0;
    float supportedVotes = 0.0;
    for (int i = 0; i < 9; ++i) {
        float confidenceValue = stillLocalRegistrationConfidenceAt(
            clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0)));
        confidenceSum += confidenceValue;
        maximumConfidence = max(maximumConfidence, confidenceValue);
        supportedVotes += step(0.14, confidenceValue);
    }
    float averageConfidence = confidenceSum / 9.0;
    float coherentNeighborhood = smoothstep(2.0, 5.0, supportedVotes);
    // A clipped LONG region can have no gradient itself. It may inherit the nearby
    // camera-motion field only when several surrounding cells agree.
    return max(stillLocalRegistrationConfidenceAt(sampleUv),
        averageConfidence * coherentNeighborhood * 0.92)
        * smoothstep(0.16, 0.40, maximumConfidence);
}

vec2 registrationNeighborhoodFlowAt(vec2 sampleUv) {
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) return vec2(0.5);
    float centerConfidence = stillLocalRegistrationConfidenceAt(sampleUv);
    if (centerConfidence >= 0.16) return stillLocalFlowAt(sampleUv).rg;

    vec2 flowTexel = 1.0 / vec2(textureSize(localFlowTex, 0));
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( flowTexel.x, 0.0), vec2(-flowTexel.x, 0.0),
        vec2(0.0,  flowTexel.y), vec2(0.0, -flowTexel.y),
        vec2( flowTexel.x,  flowTexel.y), vec2(-flowTexel.x,  flowTexel.y),
        vec2( flowTexel.x, -flowTexel.y), vec2(-flowTexel.x, -flowTexel.y));
    vec2 flowSum = vec2(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < 9; ++i) {
        vec2 q = clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0));
        float confidenceValue = stillLocalRegistrationConfidenceAt(q);
        if (confidenceValue <= 0.0) continue;
        flowSum += stillLocalFlowAt(q).rg * confidenceValue;
        weightSum += confidenceValue;
    }
    return weightSum > 0.0001 ? flowSum / weightSum : vec2(0.5);
}

vec2 localLinearRangeAtRadius(vec2 sampleUv, float radiusPixels) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * radiusPixels;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float shortMinimum = 1.0e9;
    float shortMaximum = 0.0;
    float longMinimum = 1.0e9;
    float longMaximum = 0.0;
    for (int i = 0; i < 9; ++i) {
        vec2 q = clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0));
        float shortY = mappedShortLinearLumaAt(q);
        float longY = longLinearLumaAt(q);
        shortMinimum = min(shortMinimum, shortY);
        shortMaximum = max(shortMaximum, shortY);
        longMinimum = min(longMinimum, longY);
        longMaximum = max(longMaximum, longY);
    }
    return vec2(shortMaximum - shortMinimum, longMaximum - longMinimum);
}

float radiometricAgreementAt(vec2 sampleUv) {
    float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
    float longY = max(longLinearLumaAt(sampleUv), 0.00001);
    float errorEv = abs(log2(shortY / longY));
    return 1.0 - smoothstep(0.20, 0.65, errorEv);
}

float longHardLossBaseAt(vec2 sampleUv) {
    // Any near-saturated LONG channel is real information-loss evidence. Unlike
    // V2.16, literal clipping does not also require SHORT texture at that pixel.
    return smoothstep(0.965, 0.995, max3(stillLongRgbAt(sampleUv)));
}

float compactHardLossSupportAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * 2.0;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float support = 0.0;
    for (int i = 0; i < 9; ++i) {
        support += longHardLossBaseAt(
            clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0)));
    }
    return support / 9.0;
}

float longEffectiveLossAt(vec2 sampleUv) {
    vec3 longRgb = stillLongRgbAt(sampleUv);
    vec2 mediumRanges = localLinearRangeAtRadius(sampleUv, 4.0);
    vec2 broadRanges = localLinearRangeAtRadius(sampleUv, 12.0);
    float shortMediumRange = mediumRanges.x;
    float longMediumRange = mediumRanges.y;
    float shortBroadRange = broadRanges.x;
    float longBroadRange = broadRanges.y;
    float nearHighlight = smoothstep(0.68, 0.90, max3(longRgb));
    float mediumStructure = smoothstep(0.004, 0.022, shortMediumRange);
    float mediumDominance = smoothstep(
        0.0015, 0.018, shortMediumRange - 1.06 * longMediumRange);
    float broadStructure = smoothstep(0.008, 0.050, shortBroadRange);
    float broadDominance = smoothstep(
        0.003, 0.035, shortBroadRange - 1.04 * longBroadRange);
    float agreement = radiometricAgreementAt(sampleUv);
    // Smooth recovered shading is valid information. No fine-detail/band-pass test
    // is required here; medium+broad response is enough to detect a LONG plateau.
    return nearHighlight
        * max(mediumStructure * mediumDominance,
              broadStructure * broadDominance)
        * smoothstep(0.18, 0.65, agreement);
}

float shortRecoveryEvidenceAt(vec2 sampleUv) {
    float shortValid = shortRecoveryValidityAt(sampleUv);
    float geometry = smoothstep(
        0.16, 0.48, registrationNeighborhoodConfidenceAt(sampleUv));
    float hardLoss = longHardLossBaseAt(sampleUv)
        * smoothstep(0.05, 0.20, compactHardLossSupportAt(sampleUv));
    float effectiveLoss = longEffectiveLossAt(sampleUv);
    return max(hardLoss, effectiveLoss) * shortValid * geometry;
}

float longLossRecoveryDomainAt(vec2 sampleUv) {
    // V2.20 separates strict seed proof from the domain through which an already
    // proven SHORT-owned component may propagate. Literal LONG clipping is allowed
    // to inherit geometry from its trustworthy boundary because a clipped plateau
    // cannot provide a local gradient in its interior by definition.
    vec3 longRgb = stillLongRgbAt(sampleUv);
    float shortValid = shortRecoveryValidityAt(sampleUv);
    float hardLoss = longHardLossBaseAt(sampleUv);
    float hardSupport = compactHardLossSupportAt(sampleUv);
    float nearHardBoundary = smoothstep(0.90, 0.975, max3(longRgb))
        * smoothstep(0.08, 0.30, hardSupport);
    float effectiveLoss = longEffectiveLossAt(sampleUv);
    float globalGeometry = smoothstep(0.08, 0.32, stillRegistrationConfidence);
    float localGeometry = smoothstep(
        0.08, 0.32, registrationNeighborhoodConfidenceAt(sampleUv));

    float hardDomain = max(hardLoss, nearHardBoundary) * globalGeometry;
    float effectiveDomain = effectiveLoss
        * max(localGeometry,
              0.65 * globalGeometry * smoothstep(0.30, 0.70, hardSupport));
    return shortValid * max(hardDomain, effectiveDomain);
}

vec3 broadRecoverySeedStatsAt(vec2 sampleUv) {
    // A seed remains deliberately strict: real LONG loss, valid SHORT and locally
    // trustworthy registration. The broader propagation domain is computed
    // separately and never turns an unsupported cell into a seed by itself.
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * 8.0;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float evidenceSum = 0.0;
    float strongVotes = 0.0;
    float maximumEvidence = 0.0;
    for (int i = 0; i < 9; ++i) {
        float evidenceValue = shortRecoveryEvidenceAt(
            clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0)));
        evidenceSum += evidenceValue;
        strongVotes += step(0.22, evidenceValue);
        maximumEvidence = max(maximumEvidence, evidenceValue);
    }
    return vec3(evidenceSum / 9.0, strongVotes, maximumEvidence);
}

float broadRecoveryDomainAt(vec2 sampleUv) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = sourceTexel * 8.0;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float domainSum = 0.0;
    float domainVotes = 0.0;
    float maximumDomain = 0.0;
    for (int i = 0; i < 9; ++i) {
        float domainValue = longLossRecoveryDomainAt(
            clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0)));
        domainSum += domainValue;
        domainVotes += step(0.20, domainValue);
        maximumDomain = max(maximumDomain, domainValue);
    }
    float domainAverage = domainSum / 9.0;
    return max(
        smoothstep(0.10, 0.40, domainAverage) * smoothstep(1.0, 5.0, domainVotes),
        smoothstep(0.35, 0.70, maximumDomain) * smoothstep(1.0, 3.0, domainVotes));
}
// IRIS_V217_REVERSED_V215_LONG_TRUTH_END

// IRIS_V212_ADAPTIVE_CLARITY_BEGIN
float presentationGuideLumaAt(vec2 sampleUv) {
    // Saved mode 6 guides clarity from the already source-proven FUSED image.
    // Live mode 2 remains the byte-preserved V2.15 SHORT guide; V2.16 changes only
    // saved-still source ownership after capture.
    vec3 encodedGuide = mode == 6
        ? texture(normalTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb
        : texture(shortTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;
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
        // IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_BEGIN
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
        // IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_END
        return;
    }

    // IRIS_V220_RATIO_INVARIANT_REGION_RECONSTRUCTION_BEGIN
    // Mode 3 creates two distinct masks: R is a strict, locally registered seed;
    // G is the broader physical recovery domain. BA carry the residual SHORT flow
    // owned by the seed. Mode 4 then performs one monotonic geodesic reconstruction
    // step; HdrGlView ping-pongs mode 4 until the R occupancy stops growing.
    if (mode == 3) {
        vec3 seedStats = broadRecoverySeedStatsAt(uv);
        float seedStrength = max(
            smoothstep(0.08, 0.30, seedStats.x) * smoothstep(2.0, 7.0, seedStats.y),
            smoothstep(0.22, 0.52, seedStats.z) * smoothstep(1.0, 4.0, seedStats.y));
        float seed = step(0.30, seedStrength);
        float recoveryDomain = step(0.30, broadRecoveryDomainAt(uv));
        vec2 localFlow = registrationNeighborhoodFlowAt(uv);
        vec2 seedFlow = mix(vec2(0.5), localFlow, seed);
        outColor = vec4(seed, recoveryDomain, seedFlow);
        return;
    }

    if (mode == 4) {
        // Morphological reconstruction by dilation under a mask, not finite-radius
        // closing. Ownership can advance exactly one atlas cell per pass and only
        // through G==recoveryDomain. The operation is monotonic; already owned cells
        // can never revert to LONG. The propagated BA residual lets a featureless
        // clipped interior inherit coherent boundary geometry instead of reverting to
        // a different global-only SHORT sample in the middle of a large plateau.
        vec2 atlasTexel = 1.0 / vec2(textureSize(normalTex, 0));
        vec4 centerState = texture(normalTex, uv);
        float currentOwned = step(0.5, centerState.r);
        float recoveryDomain = step(0.5, centerState.g);
        if (currentOwned > 0.5 || recoveryDomain < 0.5) {
            outColor = vec4(currentOwned, recoveryDomain, centerState.ba);
            return;
        }

        float flowWeightSum = 0.0;
        vec2 flowSumPixels = vec2(0.0);
        float ownedNeighbors = 0.0;
        for (int oy = -1; oy <= 1; ++oy) {
            for (int ox = -1; ox <= 1; ++ox) {
                if (ox == 0 && oy == 0) continue;
                vec2 offset = vec2(float(ox), float(oy)) * atlasTexel;
                vec4 neighborState = texture(
                    normalTex, clamp(uv + offset, vec2(0.0), vec2(1.0)));
                if (neighborState.r < 0.5) continue;
                float distanceWeight = 1.0 / (1.0 + float(ox * ox + oy * oy));
                vec2 neighborFlowPixels = (neighborState.ba * 2.0 - vec2(1.0))
                    * localFlowMaxPixels;
                flowSumPixels += neighborFlowPixels * distanceWeight;
                flowWeightSum += distanceWeight;
                ownedNeighbors += 1.0;
            }
        }
        if (ownedNeighbors < 0.5 || flowWeightSum <= 0.0) {
            outColor = vec4(0.0, recoveryDomain, centerState.ba);
            return;
        }

        vec2 meanFlowPixels = flowSumPixels / flowWeightSum;
        float disagreementSum = 0.0;
        float disagreementWeight = 0.0;
        for (int oy = -1; oy <= 1; ++oy) {
            for (int ox = -1; ox <= 1; ++ox) {
                if (ox == 0 && oy == 0) continue;
                vec2 offset = vec2(float(ox), float(oy)) * atlasTexel;
                vec4 neighborState = texture(
                    normalTex, clamp(uv + offset, vec2(0.0), vec2(1.0)));
                if (neighborState.r < 0.5) continue;
                float distanceWeight = 1.0 / (1.0 + float(ox * ox + oy * oy));
                vec2 neighborFlowPixels = (neighborState.ba * 2.0 - vec2(1.0))
                    * localFlowMaxPixels;
                vec2 delta = neighborFlowPixels - meanFlowPixels;
                disagreementSum += dot(delta, delta) * distanceWeight;
                disagreementWeight += distanceWeight;
            }
        }
        float flowRms = sqrt(disagreementSum / max(disagreementWeight, 0.0001));
        float coherentFlow = 1.0 - smoothstep(0.85, 1.25, flowRms);
        float propagate = step(0.35, coherentFlow) * recoveryDomain;
        vec2 encodedFlow = localFlowMaxPixels > 0.0
            ? clamp(vec2(0.5) + 0.5 * meanFlowPixels / localFlowMaxPixels,
                    vec2(0.0), vec2(1.0))
            : vec2(0.5);
        outColor = vec4(propagate, recoveryDomain, mix(centerState.ba, encodedFlow, propagate));
        return;
    }
    // IRIS_V220_RATIO_INVARIANT_REGION_RECONSTRUCTION_END

    if (mode == 5) {
        // IRIS_V217_REGION_SOURCE_OWNERSHIP_BEGIN
        // LONG is the complete clean body. Once mode 3/4 establishes a coherent
        // information-loss region, aligned SHORT owns that region as one source-truth
        // image. There is deliberately no full-resolution recoveryProof re-test that
        // can punch gray/lavender LONG holes back through a valid SHORT highlight.
        float ratio = clamp(exposureRatio, 1.0, 65536.0);
        float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
        vec4 support = texture(normalTex, uv);
        vec2 propagatedResidualPixels = (support.ba * 2.0 - vec2(1.0))
            * localFlowMaxPixels;
        vec2 shortOwnedUv = clamp(
            uv + propagatedResidualPixels / max(stillImageSize, vec2(1.0)),
            vec2(0.0), vec2(1.0));
        vec3 shortRgb = texture(shortTex, shortOwnedUv).rgb;
        vec3 longRgb = stillLongRgbAt(uv);
        vec3 shortScene = srgbToLinear(shortRgb) * stillShortScalarGain;
        vec3 longScene = srgbToLinear(longRgb);
        float usableBracket = step(2.0, ratio);
        float shortOwns = step(0.50, support.r) * usableBracket;

        // Exactly one aligned real source owns high-frequency RGB at each output
        // coordinate. Atlas interpolation affects only the location of the binary
        // ownership boundary, never the RGB values themselves.
        vec3 mergedScene = shortOwns > 0.5 ? shortScene : longScene;

        float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
        vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
        displayLinear = applyDisplayGamma(displayLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(displayLinear), 0.0, 1.0), 1.0);
        // IRIS_V217_REGION_SOURCE_OWNERSHIP_END
        return;
    }

    // V2.17 leaves the successful live preview path unchanged. The reversed
    // LONG-body ownership correction is saved-still-only; capture/viewfinder
    // behavior is not reopened.
    float ratio = clamp(exposureRatio, 1.0, 65536.0);
    float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
    vec3 shortRgb = texture(shortTex, uv).rgb;
    vec3 shortScene = srgbToLinear(shortRgb) * ratio;
    vec3 mergedScene = shortScene;

    float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
    vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
    vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
    displayLinear = applyDisplayGamma(displayLinear, displayGamma);
    displayLinear = applyAdaptiveClarity(displayLinear, uv);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
