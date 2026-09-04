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

// IRIS_V215_IMMUTABLE_SHORT_PROVENANCE_BEGIN
// SHORT is immutable output geometry and the sole spatial/chromatic/detail source.
// The residual field addresses only aligned LONG, never SHORT.
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

float envelopeSampleConfidenceAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
    vec3 longRgb = stillLongRgbAt(sampleUv);
    float shortSignal = smoothstep(0.015, 0.060, encodedLuma(shortRgb));
    float longSignal = smoothstep(0.035, 0.120, encodedLuma(longRgb));
    float shortHeadroom = 1.0 - smoothstep(0.92, 0.975, max3(shortRgb));
    float longHeadroom = 1.0 - smoothstep(0.86, 0.955, max3(longRgb));
    float geometry = smoothstep(0.32, 0.68, stillLocalRegistrationConfidenceAt(sampleUv));
    return shortSignal * longSignal * shortHeadroom * longHeadroom * geometry;
}

vec2 lowFrequencyEnvelopeEvidenceAt(vec2 sampleUv) {
    // The evidence is deliberately broad. Nine exact source samples span roughly
    // 32 source pixels; the 1/32 atlas and mode-4 neighborhood smoothing expand
    // this to a low-frequency field that cannot carry fine LONG structure.
    vec2 texel = 1.0 / vec2(textureSize(shortTex, 0));
    vec2 radius = texel * 16.0;
    vec2 offsets[9] = vec2[9](
        vec2(0.0),
        vec2( radius.x, 0.0), vec2(-radius.x, 0.0),
        vec2(0.0,  radius.y), vec2(0.0, -radius.y),
        vec2( radius.x,  radius.y), vec2(-radius.x,  radius.y),
        vec2( radius.x, -radius.y), vec2(-radius.x, -radius.y));
    float shortSum = 0.0;
    float longSum = 0.0;
    float weightSum = 0.0;
    for (int i = 0; i < 9; ++i) {
        vec2 q = clamp(sampleUv + offsets[i], vec2(0.0), vec2(1.0));
        float w = envelopeSampleConfidenceAt(q);
        shortSum += mappedShortLinearLumaAt(q) * w;
        longSum += alignedLongLinearLumaAt(q) * w;
        weightSum += w;
    }
    if (weightSum < 1.5 || shortSum <= 0.000001 || longSum <= 0.000001) {
        return vec2(0.5, 0.0);
    }
    float logCorrection = clamp(log2(longSum / shortSum), -0.50, 0.50);
    float encodedCorrection = 0.5 + logCorrection;
    float confidence = clamp(weightSum / 9.0, 0.0, 1.0);
    return vec2(encodedCorrection, confidence);
}
// IRIS_V215_IMMUTABLE_SHORT_PROVENANCE_END

// IRIS_V212_ADAPTIVE_CLARITY_BEGIN
float presentationGuideLumaAt(vec2 sampleUv) {
    // Saved mode 6 guides clarity from the already source-proven FUSED image.
    // Live mode 2 guides from immutable SHORT as well; LONG topology is never a
    // spatial presentation guide in V2.15.
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
        // IRIS_V215_TOPOLOGY_SAFE_PRESENTATION_BEGIN
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
        // IRIS_V215_TOPOLOGY_SAFE_PRESENTATION_END
        return;
    }

    // V2.15 saved fusion remains GPU-only and four-pass. mode==3 derives a broad
    // LONG/SHORT scalar luminance correction, mode==4 smooths it, mode==5 applies
    // it achromatically to immutable SHORT RGB, and mode==6 is pointwise display.
    if (mode == 3) {
        // V2.15: produce only a low-frequency achromatic correction estimate.
        // R encodes log2 correction in [-0.5,+0.5] as [0,1]; G is confidence.
        vec2 evidenceValue = lowFrequencyEnvelopeEvidenceAt(uv);
        outColor = vec4(evidenceValue.x, evidenceValue.y, 0.0, 1.0);
        return;
    }

    if (mode == 4) {
        // Smooth the already-low-resolution envelope. No source RGB, edge, hue or
        // texture is stored in this atlas; only one scalar correction + confidence.
        vec2 atlasTexel = 1.0 / vec2(textureSize(normalTex, 0));
        vec2 offsets[9] = vec2[9](
            vec2(0.0),
            vec2( atlasTexel.x, 0.0), vec2(-atlasTexel.x, 0.0),
            vec2(0.0,  atlasTexel.y), vec2(0.0, -atlasTexel.y),
            vec2( atlasTexel.x,  atlasTexel.y), vec2(-atlasTexel.x,  atlasTexel.y),
            vec2( atlasTexel.x, -atlasTexel.y), vec2(-atlasTexel.x, -atlasTexel.y));
        float weightedDeviation = 0.0;
        float confidenceSum = 0.0;
        for (int i = 0; i < 9; ++i) {
            vec4 e = texture(normalTex, clamp(uv + offsets[i], vec2(0.0), vec2(1.0)));
            weightedDeviation += (e.r - 0.5) * e.g;
            confidenceSum += e.g;
        }
        float meanDeviation = confidenceSum > 0.0001
            ? weightedDeviation / confidenceSum
            : 0.0;
        float meanConfidence = clamp(confidenceSum / 9.0, 0.0, 1.0);
        outColor = vec4(clamp(0.5 + meanDeviation, 0.0, 1.0), meanConfidence, 0.0, 1.0);
        return;
    }

    if (mode == 5) {
        // IRIS_V215_SHORT_SPATIAL_TRUTH_BEGIN
        // There is no SHORT/LONG RGB mix in saved fusion. FUSED begins as exact
        // full-resolution SHORT RGB in SHORT coordinates. LONG contributes only a
        // spatially smooth *single scalar* luminance envelope derived by modes 3/4.
        // Therefore LONG cannot introduce peach/orange chroma, gray edge fragments,
        // displaced borders, foliage texture, road texture, or any other structure.
        float ratio = clamp(exposureRatio, 1.0, 65536.0);
        float bracketStops = clamp(log2(max(ratio, 1.0001)), 1.0, 6.0);
        vec3 shortRgb = stillShortRgbAt(uv);
        vec3 shortScene = srgbToLinear(shortRgb) * stillShortScalarGain;
        vec4 envelope = texture(normalTex, uv);
        float envelopeLog2 = clamp(envelope.r - 0.5, -0.50, 0.50);
        float envelopeConfidence = smoothstep(0.18, 0.55, envelope.g);
        float envelopeScale = mix(1.0, exp2(envelopeLog2), envelopeConfidence);

        // Achromatic multiplication preserves SHORT RGB ratios and high-frequency
        // topology. If LONG alignment/radiometry is uncertain, confidence collapses
        // to zero and the result fails closed to exposure-mapped SHORT.
        vec3 mergedScene = shortScene * envelopeScale;

        float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
        vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
        displayLinear = applyDisplayGamma(displayLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(displayLinear), 0.0, 1.0), 1.0);
        // IRIS_V215_SHORT_SPATIAL_TRUTH_END
        return;
    }

    // V2.15 strict live contract: live HDR also preserves SHORT spatial/chromatic
    // truth. The saved still has the extra low-frequency LONG luminance-envelope
    // passes above; live mode fails closed to exposure-normalized SHORT.
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
