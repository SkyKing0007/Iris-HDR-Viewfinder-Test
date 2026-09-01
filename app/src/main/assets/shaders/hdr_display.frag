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
uniform float shortCalibration;
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

vec3 maskedHighlightRecovery(
        vec2 uv, vec3 longRgb, vec3 shortRgb, vec3 longScene, vec3 shortScene,
        float ratio) {
    float shoulderNeed = longHighlightShoulder(longRgb, longScene);
    float clippedCore = longClippedCore(longRgb, longScene);
    float shortEncodedLuma = luma3(shortRgb);
    float shortSceneLuma = luma3(shortScene);
    float longSceneLuma = luma3(longScene);
    float shortPeak = max3(shortRgb);
    float shortSecond = second3(shortRgb);

    // R=luminance/detail trust, G=chroma trust. The 32x24 map is bilinear filtered,
    // so one unstable light does not disable a stable window and mask edges are soft.
    vec2 temporalTrust = texture(shortReliabilityTex, uv).rg;
    float lumaSafe = 1.0 - smoothstep(0.975, 0.997, shortSecond);
    float signalSafe = smoothstep(0.008, 0.025, shortEncodedLuma);
    float radianceEvidence = smoothstep(
        1.01, 1.10,
        max3(shortScene) / max(max3(longScene), 0.0005));
    // A truly clipped LONG core contains no remaining source detail to protect.
    // Current SHORT signal/saturation safety therefore owns core luma/detail permission
    // directly; coarse 5-Hz history may shape only the shoulder and chroma. This keeps
    // recoverable core authority complete and prevents temporal trust from pulsing it.
    float shortUsable = min(lumaSafe, signalSafe);
    float corePermission = smoothstep(0.25, 0.55, shortUsable);
    float coreMask = clippedCore * corePermission;
    float shoulderRaw = shoulderNeed * lumaSafe * signalSafe
        * radianceEvidence * temporalTrust.r;
    float shoulderMask = smoothstep(0.04, 0.58, shoulderRaw) * (1.0 - clippedCore);
    float recoveryMask = max(coreMask, shoulderMask);

    // Color trust is intentionally stricter than luminance trust. If SHORT color is
    // questionable, recover its brightness/detail with LONG chromaticity instead of
    // importing pink/orange modulation. Neutral clipped LONG remains neutral.
    float rgbSafe = 1.0 - smoothstep(0.955, 0.985, shortPeak);
    float agreement = validChannelAgreement(longRgb, longScene, shortScene);
    float colorTrust = clamp(temporalTrust.g * rgbSafe * agreement, 0.0, 1.0);

    float longSpread = max3(longRgb) - min3(longRgb);
    float shortSpread = max3(shortRgb) - min3(shortRgb);
    float neutralLongClip = (1.0 - smoothstep(0.015, 0.060, longSpread))
        * smoothstep(0.975, 0.998, second3(longRgb));
    float mildShortTint = 1.0 - smoothstep(0.10, 0.25, shortSpread);
    colorTrust *= 1.0 - neutralLongClip * mildShortTint;

    vec3 longChromaticityAtShortLuma = longScene
        * (shortSceneLuma / max(longSceneLuma, 0.0005));
    vec3 trustedShort = mix(longChromaticityAtShortLuma, shortScene, colorTrust);
    vec3 mappedShort = mapRecoveredHighlight(trustedShort, ratio);
    return mix(longScene, mappedShort, recoveryMask);
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
    vec3 shortRgb = texture(shortTex, uv).rgb;
    vec3 longRgb = texture(longTex, uv).rgb;
    vec3 shortScene = srgbToLinear(shortRgb) * ratio * shortCalibration;
    vec3 longScene = srgbToLinear(longRgb);

    vec3 displayLinear = maskedHighlightRecovery(
        uv, longRgb, shortRgb, longScene, shortScene, ratio);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
