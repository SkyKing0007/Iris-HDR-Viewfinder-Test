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
uniform float shortCalibration;
uniform float shortTemporalReliability;
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

float min3(vec3 value) {
    return min(value.r, min(value.g, value.b));
}

float second3(vec3 value) {
    return value.r + value.g + value.b - min3(value) - max3(value);
}

float validChannelAgreement(vec3 longRgb, vec3 longScene, vec3 shortScene) {
    vec3 valid = vec3(1.0) - smoothstep(vec3(0.94), vec3(0.985), longRgb);
    float count = valid.r + valid.g + valid.b;
    if (count < 0.5) return 1.0;
    vec3 ratio = max(shortScene, vec3(0.000001)) / max(longScene, vec3(0.000001));
    vec3 deltaEv = abs(log2(max(ratio, vec3(0.000001))));
    float disagreement = dot(deltaEv, valid) / count;
    return 1.0 - smoothstep(0.16, 0.55, disagreement);
}

vec3 recoverStableHighlight(
        vec3 longRgb, vec3 shortRgb, vec3 longScene, vec3 shortScene) {
    // One current SHORT + one current LONG only. LONG owns the image. SHORT gets
    // authority only for a coherent clipped highlight, only when all SHORT channels
    // are comfortably below their processed ceiling, and only after consecutive
    // live pairs prove that SHORT brightness/chroma is temporally stable.
    float longLuma = dot(longScene, vec3(0.2126, 0.7152, 0.0722));
    float shortLuma = dot(shortScene, vec3(0.2126, 0.7152, 0.0722));
    float longSecond = second3(longRgb);
    float longPeak = max3(longRgb);
    float longHighlight = max(
        smoothstep(0.965, 0.992, longSecond),
        smoothstep(0.62, 0.78, longLuma) * smoothstep(0.985, 0.998, longPeak));
    float shortSafe = 1.0 - smoothstep(0.965, 0.985, max3(shortRgb));
    float radianceEvidence = smoothstep(
        1.04, 1.14,
        max3(shortScene) / max(max3(longScene), 0.0005));
    float agreement = validChannelAgreement(longRgb, longScene, shortScene);
    float weight = clamp(
        longHighlight * shortSafe * radianceEvidence * agreement
            * shortTemporalReliability,
        0.0, 1.0);

    // When LONG is a neutral multi-channel clip, mild SHORT ISP tint is not allowed
    // to turn a white reflection/light pink or orange. Strong genuine SHORT chroma is
    // retained; only weak/moderate tint on a neutral clipped LONG is neutralized.
    float longSpread = max3(longRgb) - min3(longRgb);
    float shortSpread = max3(shortRgb) - min3(shortRgb);
    float neutralLongClip = (1.0 - smoothstep(0.015, 0.060, longSpread))
        * smoothstep(0.985, 0.999, longSecond);
    float mildShortTint = 1.0 - smoothstep(0.10, 0.25, shortSpread);
    float neutralLock = neutralLongClip * mildShortTint;
    vec3 trustedShort = mix(shortScene, vec3(shortLuma), neutralLock);
    return mix(longScene, trustedShort, weight);
}

vec3 highlightOnlyToneMap(vec3 sceneLinear, float ratio) {
    // Keep the requested LONG appearance intact through normal mids. Only the top
    // display decade and recovered values above LONG white are compressed.
    const float knee = 0.90;
    const float whiteAnchor = 0.965;
    const float displayCeiling = 0.995;
    float scenePeak = max3(sceneLinear);
    if (scenePeak <= knee || scenePeak <= 0.000001) return sceneLinear;

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

    vec3 mergedScene = recoverStableHighlight(longRgb, shortRgb, longScene, shortScene);
    vec3 displayLinear = highlightOnlyToneMap(mergedScene, ratio);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
