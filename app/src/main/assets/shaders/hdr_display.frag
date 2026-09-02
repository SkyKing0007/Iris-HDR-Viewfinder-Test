#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D normalTex;
uniform sampler2D shortTex;
uniform sampler2D longTex;
uniform sampler2D shortReliabilityTex;
uniform int mode;
uniform int rotationQuarterTurns;
uniform int haveNormal;
uniform int haveShort;
uniform int haveLong;
uniform float exposureRatio;
uniform vec4 shortPhotoScaleA;
uniform float shortPhotoScaleB;
uniform vec2 fusionTexelStep;
uniform vec2 reliabilityUvScale;
uniform vec2 reliabilityUvOffset;
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
    float nonnegative = max(value, 0.0);
    return nonnegative <= 0.0031308
        ? 12.92 * nonnegative
        : 1.055 * pow(nonnegative, 1.0 / 2.4) - 0.055;
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

float min3(vec3 value) {
    return min(value.r, min(value.g, value.b));
}

float second3(vec3 value) {
    return value.r + value.g + value.b - min3(value) - max3(value);
}

float luma3(vec3 value) {
    return dot(value, vec3(0.2126, 0.7152, 0.0722));
}

float validChannelAgreement(vec3 longRgb, vec3 longScene, vec3 shortScene) {
    vec3 valid = vec3(1.0) - smoothstep(vec3(0.94), vec3(0.985), longRgb);
    float count = valid.r + valid.g + valid.b;
    if (count < 0.5) return 1.0;
    vec3 radianceRatio = max(shortScene, vec3(0.000001)) / max(longScene, vec3(0.000001));
    vec3 deltaEv = abs(log2(max(radianceRatio, vec3(0.000001))));
    float disagreement = dot(deltaEv, valid) / count;
    return 1.0 - smoothstep(0.18, 0.60, disagreement);
}

float longHighlightShoulder(vec3 longRgb, vec3 longScene) {
    // Scene-general encoded/linear evidence: thresholds describe display clipping,
    // not any office-specific brightness. LONG remains untouched below this shoulder.
    float longPeak = max3(longRgb);
    float longSecond = second3(longRgb);
    float longLuma = luma3(longScene);
    float multiChannel = smoothstep(0.925, 0.985, longSecond);
    float brightSingleChannel = smoothstep(0.970, 0.997, longPeak)
        * smoothstep(0.55, 0.82, longLuma);
    return max(multiChannel, brightSingleChannel);
}

float longClippedCore(vec3 longRgb, vec3 longScene) {
    // Inside a genuine clipped core LONG has lost scene information. If SHORT is
    // usable, recovery authority must reach 1.0 rather than being weakened by a
    // product of several soft masks. Only the outer shoulder is blended gradually.
    float longPeak = max3(longRgb);
    float longSecond = second3(longRgb);
    float longLuma = luma3(longScene);
    float multiChannelCore = smoothstep(0.980, 0.990, longSecond);
    float brightSingleCore = smoothstep(0.992, 0.998, longPeak)
        * smoothstep(0.50, 0.78, longLuma);
    return max(multiChannelCore, brightSingleCore);
}

vec3 mapRecoveredHighlight(vec3 recoveredScene, float ratio) {
    // This curve is applied only inside the SHORT mask. Recovered detail therefore
    // has a useful 0.72-0.995 display range instead of being squeezed near pure white.
    float scenePeak = max3(recoveredScene);
    if (scenePeak <= 0.72 || scenePeak <= 0.000001) return recoveredScene;
    float mappedPeak;
    if (scenePeak <= 1.0) {
        float t = clamp((scenePeak - 0.72) / 0.28, 0.0, 1.0);
        mappedPeak = mix(0.72, 0.90, t);
    } else {
        float headroomLog2 = max(log2(max(ratio, 1.0001)), 0.0001);
        float t = clamp(log2(scenePeak) / headroomLog2, 0.0, 1.0);
        mappedPeak = mix(0.90, 0.995, t);
    }
    return recoveredScene * (mappedPeak / scenePeak);
}

float shortPhotoScaleForLuma(float normalizedLuma) {
    const float k0 = 0.020;
    const float k1 = 0.060;
    const float k2 = 0.150;
    const float k3 = 0.350;
    const float k4 = 0.700;
    float value = max(normalizedLuma, 0.00001);
    if (value <= k0) return shortPhotoScaleA.x;
    if (value <= k1) {
        float t = clamp(log(value / k0) / log(k1 / k0), 0.0, 1.0);
        return mix(shortPhotoScaleA.x, shortPhotoScaleA.y, t);
    }
    if (value <= k2) {
        float t = clamp(log(value / k1) / log(k2 / k1), 0.0, 1.0);
        return mix(shortPhotoScaleA.y, shortPhotoScaleA.z, t);
    }
    if (value <= k3) {
        float t = clamp(log(value / k2) / log(k3 / k2), 0.0, 1.0);
        return mix(shortPhotoScaleA.z, shortPhotoScaleA.w, t);
    }
    if (value <= k4) {
        float t = clamp(log(value / k3) / log(k4 / k3), 0.0, 1.0);
        return mix(shortPhotoScaleA.w, shortPhotoScaleB, t);
    }
    return shortPhotoScaleB;
}

vec3 calibratedShortScene(vec3 shortRgb, float ratio) {
    vec3 normalizedScene = srgbToLinear(shortRgb) * ratio;
    return normalizedScene * shortPhotoScaleForLuma(luma3(normalizedScene));
}

float guideEdgeWeight(float centerLuma, float neighborLuma) {
    float deltaEv = abs(log2(max(neighborLuma, 0.0001) / max(centerLuma, 0.0001)));
    return 1.0 - smoothstep(0.18, 0.70, deltaEv);
}

void fusionSample(
        vec2 uv, float ratio,
        out vec3 longScene, out vec3 recoveredShort,
        out float rawMask, out float coreMask,
        out float damageSupport, out float guideLuma) {
    vec3 longRgb = texture(longTex, uv).rgb;
    vec3 shortRgb = texture(shortTex, uv).rgb;
    longScene = srgbToLinear(longRgb);
    vec3 shortScene = calibratedShortScene(shortRgb, ratio);
    float longSceneLuma = luma3(longScene);

    float shoulderNeed = longHighlightShoulder(longRgb, longScene);
    float clippedCore = longClippedCore(longRgb, longScene);
    float shortEncodedLuma = luma3(shortRgb);
    float shortSceneLuma = luma3(shortScene);
    float shortPeak = max3(shortRgb);
    float shortSecond = second3(shortRgb);
    float lumaSafe = 1.0 - smoothstep(0.975, 0.997, shortSecond);
    float signalSafe = smoothstep(0.008, 0.025, shortEncodedLuma);
    float shortUsable = min(lumaSafe, signalSafe);
    float corePermission = smoothstep(0.25, 0.55, shortUsable);
    coreMask = clippedCore * corePermission;

    float radianceEvidence = smoothstep(
        1.01, 1.10,
        max3(shortScene) / max(max3(longScene), 0.0005));
    float agreement = validChannelAgreement(longRgb, longScene, shortScene);
    // Visible luma fusion is owned by this exact complete SHORT/LONG pair. The
    // 5-Hz history no longer gates luma, eliminating its remaining periodic pulse.
    float shoulderRaw = shoulderNeed * lumaSafe * signalSafe
        * radianceEvidence * agreement;
    float shoulderMask = smoothstep(0.04, 0.58, shoulderRaw) * (1.0 - clippedCore);
    rawMask = max(coreMask, shoulderMask);
    // One-sided protection: multiscale mask filtering may smooth only inside a
    // LONG-damaged region; it cannot leak SHORT across an intact dark edge.
    damageSupport = max(
        coreMask,
        smoothstep(0.02, 0.50, shoulderNeed * lumaSafe * signalSafe));

    // V1.4.21 validity-aware boundary guide. LONG defines edges while it still
    // carries scene information. As LONG loses highlight structure, the calibrated
    // SHORT exposure takes over the guide so clipped glass/filament boundaries are
    // not flattened by LONG and then crossed by the recovery-mask blur.
    float shortGuideAuthority = max(
        clippedCore,
        smoothstep(0.20, 0.85, shoulderNeed * shortUsable));
    guideLuma = mix(longSceneLuma, shortSceneLuma, shortGuideAuthority);

    vec2 reliabilityUv = clamp(uv * reliabilityUvScale + reliabilityUvOffset, vec2(0.0), vec2(1.0));
    vec2 temporalTrust = texture(shortReliabilityTex, reliabilityUv).rg;
    float rgbSafe = 1.0 - smoothstep(0.955, 0.985, shortPeak);
    float colorTrust = clamp(temporalTrust.g * rgbSafe * agreement, 0.0, 1.0);
    float longSpread = max3(longRgb) - min3(longRgb);
    float shortSpread = max3(shortRgb) - min3(shortRgb);
    float neutralLongClip = (1.0 - smoothstep(0.015, 0.060, longSpread))
        * smoothstep(0.975, 0.998, second3(longRgb));
    float mildShortTint = 1.0 - smoothstep(0.10, 0.25, shortSpread);
    colorTrust *= 1.0 - neutralLongClip * mildShortTint;

    vec3 longChromaticityAtShortLuma = longScene
        * (shortSceneLuma / max(guideLuma, 0.0005));
    vec3 trustedShort = mix(longChromaticityAtShortLuma, shortScene, colorTrust);
    recoveredShort = mapRecoveredHighlight(trustedShort, ratio);
}

void addFusionNeighbor(
        vec2 uv, float ratio, float centerGuide,
        inout float maskAccum, inout float weightAccum) {
    vec3 longNeighbor;
    vec3 shortNeighbor;
    float maskNeighbor;
    float coreNeighbor;
    float damageNeighbor;
    float guideNeighbor;
    fusionSample(
        clamp(uv, vec2(0.0), vec2(1.0)), ratio,
        longNeighbor, shortNeighbor, maskNeighbor, coreNeighbor,
        damageNeighbor, guideNeighbor);
    float weight = guideEdgeWeight(centerGuide, guideNeighbor);
    maskAccum += maskNeighbor * weight;
    weightAccum += weight;
}

vec3 multiscaleHighlightRecovery(vec2 uv, float ratio) {
    vec3 longCenter;
    vec3 shortCenter;
    float centerMask;
    float coreMask;
    float damageSupport;
    float centerGuide;
    fusionSample(
        uv, ratio, longCenter, shortCenter,
        centerMask, coreMask, damageSupport, centerGuide);

    // V1.4.21 uses one coherent source-ownership field for every spatial band.
    // V1.4.20 mixed a broad low-frequency mask with a different fine-detail mask,
    // which could synthesize SHORT base tone with LONG detail (or the reverse) and
    // create cyan/bright contours that existed in neither registered exposure.
    // The new bilateral mask may soften only inside LONG damage support, while the
    // final RGB remains a convex interpolation of the actual center samples.
    float maskAccum = centerMask * 4.0;
    float weightAccum = 4.0;
    addFusionNeighbor(
        uv + vec2(fusionTexelStep.x, 0.0), ratio, centerGuide,
        maskAccum, weightAccum);
    addFusionNeighbor(
        uv - vec2(fusionTexelStep.x, 0.0), ratio, centerGuide,
        maskAccum, weightAccum);
    addFusionNeighbor(
        uv + vec2(0.0, fusionTexelStep.y), ratio, centerGuide,
        maskAccum, weightAccum);
    addFusionNeighbor(
        uv - vec2(0.0, fusionTexelStep.y), ratio, centerGuide,
        maskAccum, weightAccum);

    float blurredMask = min(damageSupport, maskAccum / max(weightAccum, 0.0001));
    float ownershipMask = clamp(max(coreMask, blurredMask), 0.0, 1.0);
    return mix(longCenter, shortCenter, ownershipMask);
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

    float ratio = clamp(exposureRatio, 1.0, 65536.0);
    vec3 displayLinear = multiscaleHighlightRecovery(uv, ratio);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
