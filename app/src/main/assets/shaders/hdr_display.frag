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

vec3 adaptiveAppearanceLift(vec3 displayLinear) {
    // V1.4.7 HDR reconstruction is already complete here. Apply a monotonic,
    // pointwise perceptual lift only to lower/mid tones. Deep shadows barely move
    // and recovered highlights converge back to identity, preserving noise and HDR.
    float linearY = dot(displayLinear, vec3(0.2126, 0.7152, 0.0722));
    if (linearY <= 0.000001) return displayLinear;
    float perceptualY = linearToSrgbChannel(clamp(linearY, 0.0, 1.0));
    float centered = (perceptualY - 0.20) / 0.11;
    float targetY = perceptualY
            + 0.70 * perceptualY * (1.0 - perceptualY) * exp(-(centered * centered));
    targetY = clamp(targetY, 0.0, 1.0);
    float targetLinearY = srgbToLinearChannel(targetY);
    float scale = targetLinearY / linearY;
    float peak = max3(displayLinear);
    if (peak > 0.000001) scale = min(scale, 0.98 / peak);
    return displayLinear * scale;
}

float encodedLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

vec3 colorSafeFromSources(vec3 fusedRgb, vec3 shortRgb, vec3 longRgb) {
    // HDR luminance is already complete. Rebuild only encoded chroma from actual
    // source colors so exposure normalization cannot invent orange/pink/green
    // specks. This is content-agnostic: no skin/white/scene/device classification.
    float targetY = encodedLuma(fusedRgb);
    float shortY = encodedLuma(shortRgb);
    float longY = encodedLuma(longRgb);
    vec3 fusedChroma = fusedRgb - vec3(targetY);
    vec3 shortChroma = shortRgb - vec3(shortY);
    vec3 longChroma = longRgb - vec3(longY);

    float shortPeak = max3(shortRgb);
    float shortSignal = smoothstep(0.03, 0.12, shortPeak);
    float shortChromaMag = length(shortChroma);
    float longChromaMag = length(longChroma);

    // A dark SHORT may contain harmless low-level chroma noise that becomes visible
    // only after HDR raises its luminance. Preserve strong source-supported color
    // unchanged; attenuate only that low-amplitude component as display gain grows.
    float strongColor = smoothstep(0.025, 0.085, shortChromaMag);
    float displayGain = max(targetY, 0.0001) / max(shortY, 0.0001);
    float chromaExponent = 0.35 * (1.0 - strongColor);
    float shortChromaScale = pow(max(displayGain, 1.0), -chromaExponent);
    vec3 supportedShortChroma = shortChroma * shortChromaScale;

    // SHORT supplies color when it has usable signal; otherwise retain LONG color.
    // This keeps the clean LONG-owned shadow contract and avoids brightening SHORT
    // chroma noise where the SHORT exposure is near its noise floor.
    vec3 supportedChroma = mix(longChroma, supportedShortChroma, shortSignal);

    // Deep shadows keep the exact pre-existing fused color. The source-supported
    // reconstruction ramps in only through lower/mid tones where the failure was
    // observed and where SHORT has enough signal to be a trustworthy color source.
    float colorApply = smoothstep(0.18, 0.48, targetY);
    vec3 outputChroma = mix(fusedChroma, supportedChroma, colorApply);

    // Universal no-amplification contract: FUSED encoded chroma may not exceed the
    // stronger chroma actually present in either source frame. This never suppresses
    // a source-supported vivid color merely because it is saturated.
    float outputMag = length(outputChroma);
    float sourceMaxMag = max(shortChromaMag, longChromaMag);
    if (outputMag > sourceMaxMag && outputMag > 0.000001) {
        outputChroma *= sourceMaxMag / outputMag;
    }

    // Gamut-fit by scaling chroma toward the same luma; never clip channels
    // independently because that would rotate hue.
    float gamutScale = 1.0;
    for (int channel = 0; channel < 3; ++channel) {
        float chroma = outputChroma[channel];
        if (chroma > 0.000001) {
            gamutScale = min(gamutScale, (1.0 - targetY) / chroma);
        } else if (chroma < -0.000001) {
            gamutScale = min(gamutScale, targetY / (-chroma));
        }
    }
    gamutScale = clamp(gamutScale, 0.0, 1.0);
    return vec3(targetY) + outputChroma * gamutScale;
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

    // Full-RGB handoff avoids per-channel clipping seams and preserves highlight hue.
    vec3 mergedScene = mix(longScene, shortScene, highlightWeight);
    vec3 displayLinear = adaptiveHdrToneMap(mergedScene, ratio, bracketStops);
    displayLinear = adaptiveAppearanceLift(displayLinear);
    vec3 displayRgb = linearToSrgb(displayLinear);
    displayRgb = colorSafeFromSources(displayRgb, shortRgb, longRgb);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
