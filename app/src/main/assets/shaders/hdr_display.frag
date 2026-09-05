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

// IRIS_V216_LONG_BODY_SHORT_RECOVERY_BEGIN
// SHORT remains the immutable geometric reference. LONG is the only source that is
// moved into SHORT coordinates. Saved fusion is LONG-owned by default; exact SHORT
// RGB/detail takes ownership only where LONG has proven information loss.
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

vec2 stillLongUvAt(vec2 sampleUv) {
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) {
        return clamp(sampleUv, vec2(0.0), vec2(1.0));
    }
    vec4 flowValue = stillLocalFlowAt(sampleUv);
    vec2 residualPixels = (flowValue.rg * 2.0 - vec2(1.0)) * localFlowMaxPixels;
    vec2 imageSize = max(stillImageSize, vec2(1.0));
    return clamp(sampleUv + residualPixels / imageSize, vec2(0.0), vec2(1.0));
}

vec3 stillShortRgbAt(vec2 sampleUv) {
    // Exact unwarped/unflowed SHORT sample. No LONG-dependent coordinate transform.
    return texture(shortTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;
}

vec3 stillLongRgbAt(vec2 sampleUv) {
    return texture(longTex, stillLongUvAt(sampleUv)).rgb;
}

float mappedShortLinearLumaAt(vec2 sampleUv) {
    return linearLuma(srgbToLinear(stillShortRgbAt(sampleUv)) * stillShortScalarGain);
}

float alignedLongLinearLumaAt(vec2 sampleUv) {
    return linearLuma(srgbToLinear(stillLongRgbAt(sampleUv)));
}

float shortCrossAverageAt(vec2 sampleUv, float radiusPixels) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(shortTex, 0));
    vec2 radius = sourceTexel * radiusPixels;
    float sumValue = mappedShortLinearLumaAt(sampleUv);
    sumValue += mappedShortLinearLumaAt(clamp(sampleUv + vec2( radius.x, 0.0), vec2(0.0), vec2(1.0)));
    sumValue += mappedShortLinearLumaAt(clamp(sampleUv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0)));
    sumValue += mappedShortLinearLumaAt(clamp(sampleUv + vec2(0.0,  radius.y), vec2(0.0), vec2(1.0)));
    sumValue += mappedShortLinearLumaAt(clamp(sampleUv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0)));
    return sumValue * 0.20;
}

float shortCoherentDetailAt(vec2 sampleUv) {
    // A real recoverable feature survives two spatial scales. Random SHORT noise
    // and smooth illumination gradients must not authorize a source switch merely
    // because LONG is bright or clipped. The difference of the 1px and 4px cross
    // averages is a compact band-pass in exposure-mapped SHORT scene space.
    float fineAverage = shortCrossAverageAt(sampleUv, 1.0);
    float broadAverage = shortCrossAverageAt(sampleUv, 4.0);
    float bandPass = abs(fineAverage - broadAverage);
    return smoothstep(0.025, 0.080, bandPass);
}

float shortRecoveryValidityAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
    float signal = smoothstep(0.018, 0.060, encodedLuma(shortRgb));
    float headroom = 1.0 - smoothstep(0.940, 0.985, max3(shortRgb));
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
    float sumConfidence = 0.0;
    float maxConfidence = 0.0;
    float strongVotes = 0.0;
    for (int i = 0; i < 9; ++i) {
        float c = stillLocalRegistrationConfidenceAt(
            clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0)));
        sumConfidence += c;
        maxConfidence = max(maxConfidence, c);
        strongVotes += step(0.16, c);
    }
    float averageConfidence = sumConfidence / 9.0;
    float coherentNeighborhood = smoothstep(2.0, 5.0, strongVotes);
    // A clipped LONG core may have no gradient of its own, but can inherit only
    // coherent nearby camera-motion confidence; an isolated high-confidence cell
    // cannot by itself authorize SHORT ownership.
    return max(stillLocalRegistrationConfidenceAt(sampleUv),
        averageConfidence * coherentNeighborhood * 0.90)
        * smoothstep(0.18, 0.42, maxConfidence);
}

vec2 localLinearRangeAtRadius(vec2 sampleUv, float radiusPixels) {
    vec2 sourceTexel = 1.0 / vec2(textureSize(shortTex, 0));
    vec2 radius = sourceTexel * radiusPixels;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float shortMin = 1.0e9;
    float shortMax = 0.0;
    float longMin = 1.0e9;
    float longMax = 0.0;
    for (int i = 0; i < 9; ++i) {
        vec2 q = clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0));
        float shortY = mappedShortLinearLumaAt(q);
        float longY = alignedLongLinearLumaAt(q);
        shortMin = min(shortMin, shortY);
        shortMax = max(shortMax, shortY);
        longMin = min(longMin, longY);
        longMax = max(longMax, longY);
    }
    return vec2(shortMax - shortMin, longMax - longMin);
}

float radiometricAgreementAt(vec2 sampleUv) {
    float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
    float longY = max(alignedLongLinearLumaAt(sampleUv), 0.00001);
    float errorEv = abs(log2(shortY / longY));
    return 1.0 - smoothstep(0.18, 0.55, errorEv);
}

float longHardLossBaseAt(vec2 sampleUv) {
    // Any clipped LONG channel has lost source information. Spatial coherence is
    // imposed separately so an isolated hot/quantized pixel cannot switch ownership.
    return smoothstep(0.975, 0.997, max3(stillLongRgbAt(sampleUv)));
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
    vec2 fineRanges = localLinearRangeAtRadius(sampleUv, 2.0);
    vec2 broadRanges = localLinearRangeAtRadius(sampleUv, 6.0);
    float shortFineRange = fineRanges.x;
    float longFineRange = fineRanges.y;
    float shortBroadRange = broadRanges.x;
    float longBroadRange = broadRanges.y;
    float nearHighlight = smoothstep(0.72, 0.90, max3(longRgb));
    float fineStructure = smoothstep(0.006, 0.024, shortFineRange);
    float fineDominance = smoothstep(
        0.002, 0.020, shortFineRange - 1.12 * longFineRange);
    float broadStructure = smoothstep(0.012, 0.060, shortBroadRange);
    float broadDominance = smoothstep(
        0.004, 0.040, shortBroadRange - 1.08 * longBroadRange);
    float agreement = radiometricAgreementAt(sampleUv);
    // Effective loss is intentionally stricter than literal clipping. Real SHORT
    // structure must dominate LONG at both fine and broader scales near a highlight.
    // This rejects lifted SHORT noise on ordinary smooth LONG walls while retaining
    // washed foliage/ground/window texture whose average radiometry still agrees.
    return nearHighlight * fineStructure * fineDominance
        * broadStructure * broadDominance
        * smoothstep(0.28, 0.72, agreement)
        * shortCoherentDetailAt(sampleUv);
}

float shortRecoveryProofAt(vec2 sampleUv) {
    float shortValid = shortRecoveryValidityAt(sampleUv);
    float geometry = smoothstep(
        0.18, 0.52, registrationNeighborhoodConfidenceAt(sampleUv));
    float hardLoss = longHardLossBaseAt(sampleUv)
        * smoothstep(0.06, 0.22, compactHardLossSupportAt(sampleUv))
        * shortCoherentDetailAt(sampleUv);
    float effectiveLoss = longEffectiveLossAt(sampleUv);
    return max(hardLoss, effectiveLoss) * shortValid * geometry;
}

vec2 broadRecoveryEvidenceAt(vec2 sampleUv) {
    // Broad evidence cannot carry source RGB/detail. Nine source probes span a
    // 16-pixel neighborhood; the 1/16 atlas and mode-4 consensus then provide only
    // a coherent recovery-region prior for the full-resolution binary decision.
    vec2 sourceTexel = 1.0 / vec2(textureSize(shortTex, 0));
    vec2 radius = sourceTexel * 8.0;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float sumProof = 0.0;
    float strongVotes = 0.0;
    for (int i = 0; i < 9; ++i) {
        float proof = shortRecoveryProofAt(
            clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0)));
        sumProof += proof;
        strongVotes += step(0.30, proof);
    }
    return vec2(sumProof / 9.0, strongVotes / 9.0);
}
// IRIS_V216_LONG_BODY_SHORT_RECOVERY_END

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
        // IRIS_V216_TOPOLOGY_SAFE_PRESENTATION_BEGIN
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
        // IRIS_V216_TOPOLOGY_SAFE_PRESENTATION_END
        return;
    }

    // V2.16 saved fusion remains GPU-only and four-pass. mode==3 derives only
    // source-loss evidence, mode==4 enforces broad recovery-region coherence,
    // mode==5 makes a full-resolution binary LONG-or-SHORT ownership decision,
    // and mode==6 is pointwise presentation. No pass interpolates source RGB.
    if (mode == 3) {
        vec2 evidence = broadRecoveryEvidenceAt(uv);
        outColor = vec4(
            evidence.x,
            evidence.y,
            registrationNeighborhoodConfidenceAt(uv),
            shortRecoveryValidityAt(uv));
        return;
    }

    if (mode == 4) {
        // The 1/16 atlas is a region prior only. It cannot carry a source edge,
        // texture or hue. Requiring several neighboring votes prevents isolated
        // LONG clip/noise pixels from producing a speckled SHORT ownership mask.
        vec2 atlasTexel = 1.0 / vec2(textureSize(normalTex, 0));
        vec2 offsets[9] = vec2[9](
            vec2(0.0),
            vec2( atlasTexel.x, 0.0), vec2(-atlasTexel.x, 0.0),
            vec2(0.0,  atlasTexel.y), vec2(0.0, -atlasTexel.y),
            vec2( atlasTexel.x,  atlasTexel.y), vec2(-atlasTexel.x,  atlasTexel.y),
            vec2( atlasTexel.x, -atlasTexel.y), vec2(-atlasTexel.x, -atlasTexel.y));
        float evidenceSum = 0.0;
        float voteSum = 0.0;
        float geometrySum = 0.0;
        float shortValiditySum = 0.0;
        float strongCells = 0.0;
        for (int i = 0; i < 9; ++i) {
            vec4 e = texture(normalTex, clamp(uv + offsets[i], vec2(0.0), vec2(1.0)));
            evidenceSum += e.r;
            voteSum += e.g;
            geometrySum += e.b;
            shortValiditySum += e.a;
            strongCells += step(0.20, e.r);
        }
        float evidenceAverage = evidenceSum / 9.0;
        float voteAverage = voteSum / 9.0;
        float geometryAverage = geometrySum / 9.0;
        float shortValidityAverage = shortValiditySum / 9.0;
        float coherentRegion = smoothstep(2.0, 5.0, strongCells);
        float broadRecovery = smoothstep(0.10, 0.38, evidenceAverage)
            * smoothstep(0.08, 0.34, voteAverage)
            * coherentRegion;
        outColor = vec4(
            broadRecovery,
            evidenceAverage,
            geometryAverage,
            shortValidityAverage);
        return;
    }

    if (mode == 5) {
        // IRIS_V216_BINARY_SOURCE_OWNERSHIP_BEGIN
        // LONG owns the normal photograph and therefore its clean body/SNR/detail.
        // SHORT owns only proven LONG information-loss regions. Fine RGB structure
        // is selected from exactly one real source; there is no LONG/SHORT RGB mix.
        float ratio = clamp(exposureRatio, 1.0, 65536.0);
        float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
        vec3 shortRgb = stillShortRgbAt(uv);
        vec3 longRgb = stillLongRgbAt(uv);
        vec3 shortScene = srgbToLinear(shortRgb) * stillShortScalarGain;
        vec3 longScene = srgbToLinear(longRgb);
        vec4 support = texture(normalTex, uv);

        float shortValid = shortRecoveryValidityAt(uv);
        float geometry = smoothstep(
            0.18, 0.52, registrationNeighborhoodConfidenceAt(uv));
        // Even literal clipping is not enough by itself: the broad mode-3/4
        // region must prove that SHORT contains coherent detail worth recovering.
        // Smooth clipped light pools therefore stay LONG-owned instead of turning
        // into noisy SHORT patches.
        float hardLoss = longHardLossBaseAt(uv)
            * smoothstep(0.06, 0.22, compactHardLossSupportAt(uv))
            * smoothstep(0.18, 0.55, support.r);
        float effectiveLoss = longEffectiveLossAt(uv)
            * smoothstep(0.34, 0.70, support.r);

        // Literal/highly compact clipping can recover without waiting for a broad
        // region mask; effective pre-clip flattening must be region-coherent.
        float recoveryProof = max(hardLoss, effectiveLoss) * shortValid * geometry;
        float usableBracket = step(2.0, ratio);
        float shortOwns = step(0.58, recoveryProof) * usableBracket;

        // Ternary selection is intentional: no fractional source value can create
        // a third edge, third hue, peach/orange fill or gray displaced contour.
        vec3 mergedScene = shortOwns > 0.5 ? shortScene : longScene;

        float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
        vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
        displayLinear = applyDisplayGamma(displayLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(displayLinear), 0.0, 1.0), 1.0);
        // IRIS_V216_BINARY_SOURCE_OWNERSHIP_END
        return;
    }

    // V2.16 leaves V2.15 live preview behavior unchanged. The source-ownership
    // correction above is saved-still-only so the working capture/viewfinder path
    // is not reopened while still fusion is being corrected.
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
