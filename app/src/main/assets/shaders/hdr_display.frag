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

float reliableShortTextureWeight(vec3 shortRgb, vec3 longRgb) {
    // V2.4/V2.5 still-only JPEG luminance/detail ownership. A damaged/flattened
    // bright LONG JPEG must not veto real SHORT structure merely because the two
    // rendered JPEGs no longer agree radiometrically. SHORT proves useful signal
    // and headroom; LONG contributes only a damage trigger.
    float shortSignal = smoothstep(0.08, 0.16, encodedLuma(shortRgb));
    float shortHeadroom = 1.0 - smoothstep(0.985, 0.998, max3(shortRgb));
    float longDamage = max(
        smoothstep(0.55, 0.75, encodedLuma(longRgb)),
        smoothstep(0.88, 0.97, max3(longRgb)));
    float ownershipEvidence = shortSignal * shortHeadroom * longDamage;
    return smoothstep(0.35, 0.65, ownershipEvidence);
}

float reliableShortChromaConfidence(vec3 shortRgb) {
    // V2.5: useful SHORT luminance/detail can exist before its chroma SNR is high
    // enough to survive a positive display Gamma lift. Keep low-signal SHORT chroma
    // from becoming rainbow speckle on smooth bright surfaces; once encoded SHORT
    // luma is strong, preserve the complete SHORT chromaticity.
    return smoothstep(0.16, 0.28, encodedLuma(shortRgb));
}

vec3 mergeStillLumaAndChroma(
        vec3 longScene,
        vec3 shortScene,
        float shortLumaWeight,
        float shortChromaWeight) {
    float targetY = mix(linearLuma(longScene), linearLuma(shortScene), shortLumaWeight);
    vec3 colorSource = mix(longScene, shortScene, shortChromaWeight);
    float colorY = linearLuma(colorSource);
    if (targetY <= 0.000001 || colorY <= 0.000001) return colorSource;
    return colorSource * (targetY / colorY);
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

    // mode==3 is the V2.3 off-screen still pass. Live HDR remains mode==2 and
    // therefore retains V2.2's original SHORT admission/ownership behavior.
    float textureRecoveryWeight = mode == 3
        ? reliableShortTextureWeight(shortRgb, longRgb)
        : 0.0;
    float shortWeight = max(highlightWeight, textureRecoveryWeight);

    // V2.5 still-only split confidence: SHORT may own luminance/detail before its
    // low-signal JPEG chroma is trustworthy. This removes the Gamma-exposed rainbow
    // speckle without restoring the V2.3 damaged-LONG veto. Live mode==2 remains
    // byte-for-behavior on the original RGB merge.
    vec3 mergedScene = mix(longScene, shortScene, shortWeight);
    if (mode == 3 && textureRecoveryWeight > 0.0005) {
        float shortChromaWeight = shortWeight * reliableShortChromaConfidence(shortRgb);
        mergedScene = mergeStillLumaAndChroma(
                longScene, shortScene, shortWeight, shortChromaWeight);
    }

    // HDR reconstruction completes before display brightness is applied. The slider
    // cannot change capture, bracket width, SHORT admission, or fusion ownership.
    float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
    vec3 displayLinear = adaptiveHdrToneMap(mergedScene * brightnessGain, ratio, bracketStops);
    displayLinear = applyDisplayGamma(displayLinear, displayGamma);

    // Live HDR retains V2.2 highlight color ownership. In the still pass, once
    // validated SHORT starts owning a damaged LONG pixel, do not paint LONG chroma
    // back over that real SHORT RGB structure.
    if (highlightWeight > 0.0005 && textureRecoveryWeight < 0.05) {
        displayLinear = highlightColorOwnership(
                displayLinear, longScene, shortScene, longRgb, shortRgb, highlightWeight);
    }
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
