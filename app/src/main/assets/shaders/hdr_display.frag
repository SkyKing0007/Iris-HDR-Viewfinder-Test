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

const float HDR_KNEE = 0.70;
const float HDR_TRUE_CLIP_START = 0.985;
const float HDR_TRUE_CLIP_END = 0.998;
const float HDR_WHITE_ANCHOR = 0.74;
const float HDR_DISPLAY_CEILING = 0.88;
const float HDR_TONE_REFERENCE_STOPS = 3.0;

vec3 fixedHdrToneMap(vec3 sceneLinear) {
    // Brightness changes physical LONG exposure upstream. The display mapping itself
    // remains fixed to the proven ~3 EV V1.4.7 shape, so moving Brightness cannot
    // silently move the knee, white anchor or display ceiling.
    float scenePeak = max3(sceneLinear);
    if (scenePeak <= HDR_KNEE || scenePeak <= 0.000001) return sceneLinear;

    float mappedPeak;
    if (scenePeak <= 1.0) {
        float t = clamp((scenePeak - HDR_KNEE) / (1.0 - HDR_KNEE), 0.0, 1.0);
        mappedPeak = mix(HDR_KNEE, HDR_WHITE_ANCHOR, t);
    } else {
        float t = clamp(log2(scenePeak) / HDR_TONE_REFERENCE_STOPS, 0.0, 1.0);
        mappedPeak = mix(HDR_WHITE_ANCHOR, HDR_DISPLAY_CEILING, t);
    }
    return sceneLinear * (mappedPeak / scenePeak);
}

float linearLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
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

vec3 recoverHighlightScene(
        vec3 longScene,
        vec3 shortScene,
        vec3 longRgb,
        vec3 shortRgb) {
    float longY = linearLuma(longScene);
    float shortY = linearLuma(shortScene);
    if (longY <= 0.000001 || shortY <= 0.000001) return longScene;

    // Requiring two LONG channels near clipping prevents a single saturated red/orange
    // channel from opening the HDR handoff on skin or colored surfaces.
    float secondLong = secondLargest3(longRgb);
    float trueClip = smoothstep(HDR_TRUE_CLIP_START, HDR_TRUE_CLIP_END, secondLong);

    // Same-point normalized SHORT should not become substantially darker. A low ratio
    // is one-sided evidence of SHORT/LONG edge disagreement, so SHORT is rejected.
    float shortAgreement = smoothstep(0.80, 0.98, shortY / longY);
    float recoveryWeight = trueClip * shortAgreement;
    float recoveredY = mix(longY, max(longY, shortY), recoveryWeight);

    vec3 owned = longScene * (recoveredY / longY);

    // SHORT chromaticity is emergency-only after virtually complete multi-channel clip.
    float extremeClip = smoothstep(0.997, 0.9995, secondLong);
    float shortSignal = smoothstep(0.025, 0.10, max3(shortRgb));
    float shortColorNeed = recoveryWeight * extremeClip * shortSignal;
    if (shortColorNeed > 0.0005) {
        vec3 shortOwned = shortScene * (recoveredY / shortY);
        owned = mix(owned, shortOwned, shortColorNeed);
    }
    return max(owned, vec3(0.0));
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
    vec3 shortScene = srgbToLinear(shortRgb) * ratio;
    vec3 longScene = srgbToLinear(longRgb);

    // V1.4.13 keeps LONG as the complete owner until genuine multi-channel clipping.
    // SHORT contributes missing highlight radiance only; it never full-RGB blends into
    // ordinary walls, skin, shelf lighting or foliage edges.
    vec3 recoveredScene = recoverHighlightScene(longScene, shortScene, longRgb, shortRgb);
    vec3 displayLinear = fixedHdrToneMap(recoveredScene);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
