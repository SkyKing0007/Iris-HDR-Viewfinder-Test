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

float shortOwnershipEvidenceAt(vec2 sampleUv) {
    vec3 sampleShort = texture(shortTex, sampleUv).rgb;
    vec3 sampleLong = texture(longTex, sampleUv).rgb;
    float shortSignal = smoothstep(0.14, 0.24, encodedLuma(sampleShort));
    float shortHeadroom = 1.0 - smoothstep(0.985, 0.998, max3(sampleShort));
    float longDamage = max(
        smoothstep(0.62, 0.78, encodedLuma(sampleLong)),
        smoothstep(0.90, 0.98, max3(sampleLong)));
    return shortSignal * shortHeadroom * longDamage;
}

float coherentShortOwnership(vec2 uv, vec3 centerShortRgb) {
    // V2.6 still-only source provenance. Neighboring pixels may vote only on the
    // scalar ownership mask; output RGB always comes from the center LONG or SHORT
    // captured JPEG pixel. The wider 3x3 footprint prevents grass/dirt/foliage from
    // alternating LONG/SHORT ownership pixel-by-pixel without inventing image data.
    vec2 texel = 1.0 / vec2(textureSize(longTex, 0));
    vec2 radius = texel * 6.0;
    float evidence = 0.0;
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(-radius.x, -radius.y), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(0.0, -radius.y), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(radius.x, -radius.y), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(-radius.x, 0.0), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(uv);
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(radius.x, 0.0), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(-radius.x, radius.y), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(0.0, radius.y), vec2(0.0), vec2(1.0)));
    evidence += shortOwnershipEvidenceAt(clamp(uv + vec2(radius.x, radius.y), vec2(0.0), vec2(1.0)));
    float neighborhoodEvidence = evidence / 9.0;

    float centerSignal = smoothstep(0.08, 0.14, encodedLuma(centerShortRgb));
    float centerHeadroom = 1.0 - smoothstep(0.985, 0.998, max3(centerShortRgb));
    return centerSignal * centerHeadroom * smoothstep(0.28, 0.58, neighborhoodEvidence);
}

vec3 liftShortProvenanceRgb(vec3 shortRgb, float bracketStops) {
    // Rendered HAL JPEG is not RAW radiance. Use the bracket only to choose a
    // bounded display-domain lift; never multiply the saved-JPEG SHORT by the full
    // physical exposure ratio. One scalar preserves center-pixel RGB ratios.
    float y = encodedLuma(shortRgb);
    if (y <= 0.000001) return shortRgb;
    float exponent = clamp(0.82 - 0.075 * (bracketStops - 1.0), 0.58, 0.82);
    float targetY = pow(clamp(y, 0.0, 1.0), exponent);
    float requestedScale = targetY / y;
    float gamutScale = 1.0 / max(max3(shortRgb), 0.000001);
    return clamp(shortRgb * min(requestedScale, gamutScale), 0.0, 1.0);
}

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

    // V2.6: live mode==2 retains V2.5/V2.2 physical-ratio HDR ownership. Saved
    // mode==3 is JPEG-domain source provenance: a coherent scalar mask chooses the
    // complete center LONG or complete center SHORT RGB sample. No luma/chroma
    // recombination and no per-pixel physical-ratio reconstruction of saved SHORT.
    float stillShortOwnership = mode == 3
        ? coherentShortOwnership(uv, shortRgb)
        : 0.0;
    vec3 mergedScene;
    if (mode == 3) {
        vec3 shortProvenanceRgb = liftShortProvenanceRgb(shortRgb, bracketStops);
        vec3 shortProvenanceScene = srgbToLinear(shortProvenanceRgb);
        mergedScene = mix(longScene, shortProvenanceScene, stillShortOwnership);
    } else {
        mergedScene = mix(longScene, shortScene, highlightWeight);
    }

    // Brightness remains presentation-only. The photographic body curve is global,
    // RGB-ratio preserving, and fades to zero before the 0.70 HDR shoulder, so
    // recovered highlights retain their existing headroom while the SDR body reads
    // brighter/cleaner without a local-HDR "pop" look.
    float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
    vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
    vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
    displayLinear = applyDisplayGamma(displayLinear, displayGamma);

    // The legacy LONG-first highlight color owner remains live-preview-only. Saved
    // V2.6 provenance never repaints LONG chroma over an owned SHORT RGB pixel.
    if (mode != 3 && highlightWeight > 0.0005) {
        displayLinear = highlightColorOwnership(
                displayLinear, longScene, shortScene, longRgb, shortRgb, highlightWeight);
    }
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
