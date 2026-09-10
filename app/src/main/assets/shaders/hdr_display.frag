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
uniform vec2 stillGlobalShortOffsetPixels;
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

// IRIS_V232_EXTENDED_LINEAR_RAW_CARRIER_BEGIN
// Saved RAW stills remain an extended-linear RGB + physical-sigma carrier. V2.34
// changes only the RGB code distribution inside the inherited RGBA8 allocation:
// shadows keep square-root precision, 1..8 scene energy receives the dense majority
// of highlight codes, and >8 remains an asymptotic specular tail. Alpha retains the
// exact V2.32/V2.33 sigma+saturation contract consumed by fusion ownership.
const float rawCarrierSigmaK = 0.01;
const float rawCarrierMax = 254.0 / 255.0;
const float rawCarrierBodyEnd = rawCarrierMax * 0.42;
const float rawCarrierDetailEnd = rawCarrierMax * 0.92;
const float rawCarrierDetailTop = 8.0;
const float rawCarrierDetailStops = 3.0;
const float rawCarrierTailTop = 32.0;
const float rawCarrierTailStops = 2.0;

bool savedRawSourceMode() {
    return mode == 3 || mode == 4 || mode == 5;
}

float expandRawCarrier(float encoded, float k) {
    float e = min(max(encoded, 0.0), rawCarrierMax);
    float e2 = e * e;
    return k * e2 / max(1.0 - e2, 0.0000001);
}

float decodeRawSceneChannel(float encoded) {
    float e = clamp(encoded, 0.0, rawCarrierMax);
    if (e <= rawCarrierBodyEnd) {
        float t = e / max(rawCarrierBodyEnd, 0.000001);
        return t * t;
    }
    if (e <= rawCarrierDetailEnd) {
        float t = (e - rawCarrierBodyEnd)
            / max(rawCarrierDetailEnd - rawCarrierBodyEnd, 0.000001);
        return exp2(rawCarrierDetailStops * t);
    }
    float tailT = clamp((e - rawCarrierDetailEnd)
        / max(rawCarrierMax - rawCarrierDetailEnd, 0.000001), 0.0, 1.0);
    return rawCarrierDetailTop * exp2(rawCarrierTailStops * tailT);
}

float rawCarrierSigma(float encodedAlpha) {
    float code = floor(encodedAlpha * 255.0 + 0.5);
    float saturation = step(127.5, code);
    float sigmaCode = code - 128.0 * saturation;
    return expandRawCarrier(sigmaCode / 127.0, rawCarrierSigmaK);
}

float rawCarrierSaturation(float encodedAlpha) {
    return step(127.5, floor(encodedAlpha * 255.0 + 0.5));
}

vec4 decodeRawCarrier(vec4 encoded) {
    return vec4(
        decodeRawSceneChannel(encoded.r),
        decodeRawSceneChannel(encoded.g),
        decodeRawSceneChannel(encoded.b),
        rawCarrierSigma(encoded.a));
}

vec4 shortCarrierLinearNearestAt(vec2 sourceUv) {
    ivec2 size = textureSize(shortTex, 0);
    ivec2 p = clamp(ivec2(clamp(sourceUv, vec2(0.0), vec2(0.99999994)) * vec2(size)),
        ivec2(0), size - ivec2(1));
    return decodeRawCarrier(texelFetch(shortTex, p, 0));
}

vec4 shortCarrierLinearAt(vec2 sourceUv) {
    ivec2 size = textureSize(shortTex, 0);
    vec2 pixel = clamp(sourceUv, vec2(0.0), vec2(1.0)) * vec2(size) - vec2(0.5);
    ivec2 p0 = ivec2(floor(pixel));
    vec2 f = fract(pixel);
    ivec2 maxPixel = size - ivec2(1);
    ivec2 p00 = clamp(p0, ivec2(0), maxPixel);
    ivec2 p10 = clamp(p0 + ivec2(1, 0), ivec2(0), maxPixel);
    ivec2 p01 = clamp(p0 + ivec2(0, 1), ivec2(0), maxPixel);
    ivec2 p11 = clamp(p0 + ivec2(1, 1), ivec2(0), maxPixel);
    vec4 a = decodeRawCarrier(texelFetch(shortTex, p00, 0));
    vec4 b = decodeRawCarrier(texelFetch(shortTex, p10, 0));
    vec4 c = decodeRawCarrier(texelFetch(shortTex, p01, 0));
    vec4 d = decodeRawCarrier(texelFetch(shortTex, p11, 0));
    float w00 = (1.0 - f.x) * (1.0 - f.y);
    float w10 = f.x * (1.0 - f.y);
    float w01 = (1.0 - f.x) * f.y;
    float w11 = f.x * f.y;
    vec3 rgb = a.rgb * w00 + b.rgb * w10 + c.rgb * w01 + d.rgb * w11;
    // Independent-sample interpolation propagates variance with squared weights.
    float sigma = sqrt(
        a.a * a.a * w00 * w00 + b.a * b.a * w10 * w10
        + c.a * c.a * w01 * w01 + d.a * d.a * w11 * w11);
    return vec4(rgb, sigma);
}

vec4 longCarrierLinearAt(vec2 sourceUv) {
    ivec2 size = textureSize(longTex, 0);
    ivec2 p = clamp(ivec2(clamp(sourceUv, vec2(0.0), vec2(0.99999994)) * vec2(size)),
        ivec2(0), size - ivec2(1));
    return decodeRawCarrier(texelFetch(longTex, p, 0));
}

float shortCarrierSaturationAt(vec2 sourceUv) {
    ivec2 size = textureSize(shortTex, 0);
    vec2 pixel = clamp(sourceUv, vec2(0.0), vec2(1.0)) * vec2(size) - vec2(0.5);
    ivec2 p0 = ivec2(floor(pixel));
    vec2 f = fract(pixel);
    ivec2 maxPixel = size - ivec2(1);
    ivec2 p00 = clamp(p0, ivec2(0), maxPixel);
    ivec2 p10 = clamp(p0 + ivec2(1, 0), ivec2(0), maxPixel);
    ivec2 p01 = clamp(p0 + ivec2(0, 1), ivec2(0), maxPixel);
    ivec2 p11 = clamp(p0 + ivec2(1, 1), ivec2(0), maxPixel);
    float w00 = (1.0 - f.x) * (1.0 - f.y);
    float w10 = f.x * (1.0 - f.y);
    float w01 = (1.0 - f.x) * f.y;
    float w11 = f.x * f.y;
    return clamp(
        rawCarrierSaturation(texelFetch(shortTex, p00, 0).a) * w00
        + rawCarrierSaturation(texelFetch(shortTex, p10, 0).a) * w10
        + rawCarrierSaturation(texelFetch(shortTex, p01, 0).a) * w01
        + rawCarrierSaturation(texelFetch(shortTex, p11, 0).a) * w11,
        0.0, 1.0);
}

float longCarrierSaturationAt(vec2 sourceUv) {
    ivec2 size = textureSize(longTex, 0);
    ivec2 p = clamp(ivec2(clamp(sourceUv, vec2(0.0), vec2(0.99999994)) * vec2(size)),
        ivec2(0), size - ivec2(1));
    return rawCarrierSaturation(texelFetch(longTex, p, 0).a);
}
// IRIS_V232_EXTENDED_LINEAR_RAW_CARRIER_END

vec3 adaptiveHdrToneMap(vec3 sceneLinear, float ratio, float bracketStops) {
    // V2.27 universal recovered-highlight transfer. Reserve a real display interval
    // for valid SHORT information with a guaranteed stop-domain slope; only energy
    // beyond the recoverable bracket enters the final asymptotic specular tail.
    // This is object-agnostic and whole-RGB, so windows, lamps, sky, chrome, fabric
    // and any other recovered highlight obey the same monotonic radiance ordering.
    const float knee = 0.70;
    const float detailTop = 0.965;
    float scenePeak = max3(sceneLinear);
    if (scenePeak <= knee || scenePeak <= 0.000001) return sceneLinear;

    float detailStops = clamp(max(bracketStops, 2.0), 2.0, 6.0);
    float highlightStops = max(log2(scenePeak / knee), 0.0);
    float mappedPeak;
    if (highlightStops <= detailStops) {
        mappedPeak = knee + (detailTop - knee) * (highlightStops / detailStops);
    } else {
        float tailStops = highlightStops - detailStops;
        // Match the stop-domain slope at detailTop exactly (C1 continuity).
        float tailStopScale = (1.0 - detailTop) * detailStops / (detailTop - knee);
        mappedPeak = detailTop + (1.0 - detailTop)
            * (1.0 - exp(-tailStops / tailStopScale));
    }
    mappedPeak = clamp(mappedPeak, knee, 1.0);
    return sceneLinear * (mappedPeak / scenePeak);
}

// IRIS_V234_CONTINUOUS_SAVED_HIGHLIGHT_PRESENTATION_BEGIN
// Presentation must not reveal the binary fusion ownership mask.  Mode-5 has already
// selected one physically registered scene radiance; from here every saved pixel uses
// one pointwise whole-RGB transfer.  A mild concave stop shape keeps the lower/middle
// ceiling gradient and subtle X reflection separated while a lower detailTop reserves
// headroom so the chandelier glow cannot become a detached white island.
vec3 savedContinuousHdrToneMap(vec3 sceneLinear, float ratio, float bracketStops) {
    const float knee = 0.70;
    const float detailTop = 0.930;
    const float lowerSlopeBias = 0.35;
    float scenePeak = max3(sceneLinear);
    if (scenePeak <= knee || scenePeak <= 0.000001) return sceneLinear;

    float detailStops = clamp(max(bracketStops, 2.0), 2.0, 6.0);
    float highlightStops = max(log2(scenePeak / knee), 0.0);
    float mappedPeak;
    if (highlightStops <= detailStops) {
        float t = clamp(highlightStops / detailStops, 0.0, 1.0);
        // Concave endpoint-preserving shape: lower/mid recovered levels receive more
        // slope while the upper interval compresses progressively toward detailTop.
        // No spatial neighborhood or owner mask participates, so smooth ceilings
        // cannot acquire rings at SHORT/LONG ownership boundaries.
        float shapedT = t + lowerSlopeBias * t * (1.0 - t);
        mappedPeak = knee + (detailTop - knee) * clamp(shapedT, 0.0, 1.0);
    } else {
        float tailStops = highlightStops - detailStops;
        // Match the reduced end slope of the concave detail interval into the
        // asymptotic specular tail (C1 continuity, no hard shoulder boundary).
        float endSlopeScale = max(1.0 - lowerSlopeBias, 0.05);
        float tailStopScale = (1.0 - detailTop) * detailStops
            / ((detailTop - knee) * endSlopeScale);
        mappedPeak = detailTop + (1.0 - detailTop)
            * (1.0 - exp(-tailStops / tailStopScale));
    }
    mappedPeak = clamp(mappedPeak, knee, 1.0);
    return sceneLinear * (mappedPeak / scenePeak);
}
// IRIS_V234_CONTINUOUS_SAVED_HIGHLIGHT_PRESENTATION_END

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
    float pureGammaY = pow(clamp(y, 0.0, 1.0), 1.0 / gamma);
    // Gamma remains a body/midtone presentation control. Its authority fades before
    // the recovered-highlight interval so AUTO cannot re-compress SHORT ordering
    // after HDR reconstruction has already established that ordering.
    float gammaInfluence = 1.0 - smoothstep(0.50, 0.78, y);
    float mappedY = mix(y, pureGammaY, gammaInfluence);
    float requestedScale = mappedY / y;
    float gamutScale = 1.0 / max(max3(rgb), 0.000001);
    return rgb * min(requestedScale, gamutScale);
}

// IRIS_V217_REVERSED_V215_LONG_TRUTH_BEGIN
// V2.17 reverses the successful V2.15 geometry contract. LONG is immutable output
// geometry and the clean spatial/chromatic/detail body. SHORT is the only source
// moved into LONG coordinates. A broad ownership atlas admits complete aligned SHORT
// RGB/detail only inside coherent regions where LONG has lost highlight information.
vec4 stillLocalFlowAt(vec2 sampleUv) {
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) {
        return vec4(0.5, 0.5, 0.0, 0.0);
    }
    return texture(localFlowTex, clamp(sampleUv, vec2(0.0), vec2(1.0)));
}

float stillLocalRegistrationConfidenceAt(vec2 sampleUv) {
    vec4 flowValue = stillLocalFlowAt(sampleUv);
    return clamp(stillRegistrationConfidence * flowValue.b, 0.0, 1.0);
}

// IRIS_V238_FAIL_CLOSED_SHORT_GEOMETRY_BEGIN
float stillLocalDirectRegistrationConfidenceAt(vec2 sampleUv) {
    vec4 flowValue = stillLocalFlowAt(sampleUv);
    // localFlowTex.a is 1 only for a cell with its own cycle-validated measurement.
    // Bilinear interpolation intentionally fades authority before a measured/inferred
    // boundary instead of letting an inferred cell warp final SHORT RGB.
    float directAuthority = smoothstep(0.80, 0.98, flowValue.a);
    return clamp(stillRegistrationConfidence * flowValue.b * directAuthority, 0.0, 1.0);
}

vec2 stillShortUvUnclampedAt(vec2 sampleUv) {
    vec2 imageSize = max(stillImageSize, vec2(1.0));
    vec2 globalUv = sampleUv + stillGlobalShortOffsetPixels / imageSize;
    if (haveLocalFlow == 0 || localFlowMaxPixels <= 0.0) {
        return globalUv;
    }
    vec4 flowValue = stillLocalFlowAt(sampleUv);
    vec2 residualPixels = (flowValue.rg * 2.0 - vec2(1.0)) * localFlowMaxPixels;
    float directConfidence = stillLocalDirectRegistrationConfidenceAt(sampleUv);
    // A low-confidence/inferred residual has exactly zero final warp authority.
    float residualAuthority = step(0.16, directConfidence);
    return globalUv + residualAuthority * residualPixels / imageSize;
}

float stillShortSourceBoundsValidityAt(vec2 sampleUv) {
    vec2 imageSize = max(stillImageSize, vec2(1.0));
    vec2 sourceUv = stillShortUvUnclampedAt(sampleUv);
    // Four-tap linear decode needs a real source neighborhood. Never manufacture
    // border pixels by clamping an out-of-frame registration request to the edge.
    vec2 margin = vec2(1.25) / imageSize;
    vec2 lower = step(margin, sourceUv);
    vec2 upper = step(sourceUv, vec2(1.0) - margin);
    return lower.x * lower.y * upper.x * upper.y;
}

vec2 stillShortUvAt(vec2 sampleUv) {
    return clamp(stillShortUvUnclampedAt(sampleUv), vec2(0.0), vec2(1.0));
}
// IRIS_V238_FAIL_CLOSED_SHORT_GEOMETRY_END

vec3 stillShortRgbAt(vec2 sampleUv) {
    // Live preview keeps the inherited sRGB source contract. Saved RAW modes never
    // consume this path as scene authority; they decode the extended-linear carrier.
    return texture(shortTex, stillShortUvAt(sampleUv)).rgb;
}

vec3 stillLongRgbAt(vec2 sampleUv) {
    // Live preview keeps the inherited sRGB source contract.
    return texture(longTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;
}

vec4 savedShortLinearAt(vec2 sampleUv) {
    // Final source sampling uses explicit four-tap interpolation after linear decode.
    return shortCarrierLinearAt(stillShortUvAt(sampleUv));
}

vec4 savedShortEvidenceAt(vec2 sampleUv) {
    // Analysis/ownership probes deliberately use one nearest decoded source sample.
    // This keeps V2.32 full-resolution mode-5 cost bounded near V2.31 while avoiding
    // any interpolation in companded or sRGB code space.
    return shortCarrierLinearNearestAt(stillShortUvAt(sampleUv));
}

vec4 savedLongLinearAt(vec2 sampleUv) {
    return longCarrierLinearAt(clamp(sampleUv, vec2(0.0), vec2(1.0)));
}

float savedShortSaturationAt(vec2 sampleUv) {
    return shortCarrierSaturationAt(stillShortUvAt(sampleUv));
}

float savedShortEvidenceSaturationAt(vec2 sampleUv) {
    vec2 sourceUv = stillShortUvAt(sampleUv);
    ivec2 size = textureSize(shortTex, 0);
    ivec2 p = clamp(ivec2(clamp(sourceUv, vec2(0.0), vec2(0.99999994)) * vec2(size)),
        ivec2(0), size - ivec2(1));
    return rawCarrierSaturation(texelFetch(shortTex, p, 0).a);
}

float savedLongSaturationAt(vec2 sampleUv) {
    return longCarrierSaturationAt(clamp(sampleUv, vec2(0.0), vec2(1.0)));
}

float mappedShortLinearLumaAt(vec2 sampleUv) {
    if (savedRawSourceMode()) {
        return linearLuma(savedShortEvidenceAt(sampleUv).rgb * stillShortScalarGain);
    }
    return linearLuma(srgbToLinear(stillShortRgbAt(sampleUv)) * stillShortScalarGain);
}

float longLinearLumaAt(vec2 sampleUv) {
    if (savedRawSourceMode()) {
        return linearLuma(savedLongLinearAt(sampleUv).rgb);
    }
    return linearLuma(srgbToLinear(stillLongRgbAt(sampleUv)));
}

float mappedShortSigmaAt(vec2 sampleUv) {
    return savedRawSourceMode()
        ? savedShortEvidenceAt(sampleUv).a * stillShortScalarGain
        : 0.0;
}

float longSigmaAt(vec2 sampleUv) {
    return savedRawSourceMode() ? savedLongLinearAt(sampleUv).a : 0.0;
}

vec2 localLinearRangeAtRadius(vec2 sampleUv, float radiusPixels);
vec2 localNoiseSigmaAtRadius(vec2 sampleUv, float radiusPixels);

float channelClipDamage(vec3 rgb) {
    // A single near-clipped channel is not equivalent to losing the whole RGB
    // sample. Average channel damage preserves useful filament/color structure in
    // the remaining channels instead of rejecting the pixel via max(R,G,B).
    float rDamage = smoothstep(0.985, 0.9995, rgb.r);
    float gDamage = smoothstep(0.985, 0.9995, rgb.g);
    float bDamage = smoothstep(0.985, 0.9995, rgb.b);
    return (rDamage + gDamage + bDamage) / 3.0;
}

float shortInformationAdvantageAt(vec2 sampleUv) {
    if (!savedRawSourceMode()) {
        vec3 shortRgb = stillShortRgbAt(sampleUv);
        vec3 longRgb = stillLongRgbAt(sampleUv);
        float clipAdvantage = smoothstep(
            0.03, 0.45, channelClipDamage(longRgb) - channelClipDamage(shortRgb));
        vec2 localRanges = localLinearRangeAtRadius(sampleUv, 4.0);
        float shortStructure = smoothstep(0.003, 0.022, localRanges.x);
        float structureAdvantage = smoothstep(
            0.0015, 0.018, localRanges.x - 1.03 * localRanges.y);
        return max(clipAdvantage, shortStructure * structureAdvantage);
    }

    // V2.32 saved RAW evidence is noise-normalized. A SHORT range that is explainable
    // by its physical S*x+O uncertainty is not scene structure and cannot steal LONG
    // ownership. Four sigma is intentionally conservative for the sparse 9-point range.
    vec2 localRanges = localLinearRangeAtRadius(sampleUv, 4.0);
    vec2 localNoise = localNoiseSigmaAtRadius(sampleUv, 4.0);
    float shortExcess = max(localRanges.x - 4.0 * localNoise.x, 0.0);
    float longExcess = max(localRanges.y - 4.0 * localNoise.y, 0.0);
    float shortStructure = smoothstep(0.0025, 0.020, shortExcess);
    float structureAdvantage = smoothstep(
        0.0010, 0.015, shortExcess - 1.03 * longExcess);

    vec3 shortScene = savedShortEvidenceAt(sampleUv).rgb * stillShortScalarGain;
    vec3 longScene = savedLongLinearAt(sampleUv).rgb;
    // Extended-linear headroom replaces encoded-channel clipping as the saved-RAW
    // loss cue. Whole-RGB source ownership remains unchanged.
    float longPressure = max(savedLongSaturationAt(sampleUv),
        smoothstep(0.96, 1.12, max3(longScene)));
    float shortPressure = max(savedShortEvidenceSaturationAt(sampleUv),
        smoothstep(0.96, 1.12, max3(shortScene)));
    float headroomAdvantage = longPressure * (1.0 - 0.90 * shortPressure);
    return max(headroomAdvantage, shortStructure * structureAdvantage);
}

float shortRecoveryValidityAt(vec2 sampleUv) {
    // V2.22 strict seed validity is INFORMATION-relative, not max-channel-headroom
    // based. V2.32 additionally requires saved RAW structure to rise above the
    // timestamp-matched physical sensor uncertainty.
    if (!savedRawSourceMode()) {
        vec3 shortRgb = stillShortRgbAt(sampleUv);
        float signal = smoothstep(0.012, 0.050, encodedLuma(shortRgb));
        vec2 localRanges = localLinearRangeAtRadius(sampleUv, 4.0);
        float retainedStructure = smoothstep(0.003, 0.020, localRanges.x);
        float channelRetention = 1.0 - smoothstep(
            0.72, 0.995, channelClipDamage(shortRgb));
        float relativeAdvantage = shortInformationAdvantageAt(sampleUv);
        return signal * max(relativeAdvantage, max(retainedStructure, channelRetention * 0.55));
    }

    if (stillShortSourceBoundsValidityAt(sampleUv) < 0.5) return 0.0;
    vec4 shortRaw = savedShortEvidenceAt(sampleUv);
    vec3 shortScene = shortRaw.rgb * stillShortScalarGain;
    float shortSigma = shortRaw.a * stillShortScalarGain;
    float signal = smoothstep(0.0009, 0.0040, linearLuma(shortScene));
    vec2 localRanges = localLinearRangeAtRadius(sampleUv, 4.0);
    vec2 localNoise = localNoiseSigmaAtRadius(sampleUv, 4.0);
    float retainedExcess = max(localRanges.x - 4.0 * localNoise.x, 0.0);
    vec2 microRanges = localLinearRangeAtRadius(sampleUv, 1.5);
    vec2 microNoise = localNoiseSigmaAtRadius(sampleUv, 1.5);
    float retainedMicroExcess = max(microRanges.x - 5.0 * microNoise.x, 0.0);
    float retainedStructure = max(
        smoothstep(0.0025, 0.018, retainedExcess),
        smoothstep(0.0012, 0.010, retainedMicroExcess));
    float signalSnr = smoothstep(2.0, 6.0,
        linearLuma(shortScene) / max(shortSigma, 0.000001));
    float headroomValidity = (1.0 - savedShortEvidenceSaturationAt(sampleUv))
        * (1.0 - smoothstep(1.50, 3.00, max3(shortScene)));
    float relativeAdvantage = shortInformationAdvantageAt(sampleUv);
    return signal * max(relativeAdvantage,
        max(retainedStructure, 0.55 * signalSnr * headroomValidity));
}

float shortRecoveryDomainValidityAt(vec2 sampleUv) {
    // Once a connected LONG-loss component has a valid seed, every real SHORT
    // sample with usable signal remains eligible for ownership propagation. Near
    // clipping is deliberately NOT a domain hole.
    if (!savedRawSourceMode()) {
        vec3 shortRgb = stillShortRgbAt(sampleUv);
        return smoothstep(0.004, 0.020, encodedLuma(shortRgb));
    }
    if (stillShortSourceBoundsValidityAt(sampleUv) < 0.5) return 0.0;
    vec4 shortRaw = savedShortEvidenceAt(sampleUv);
    float mappedY = linearLuma(shortRaw.rgb * stillShortScalarGain);
    float mappedSigma = shortRaw.a * stillShortScalarGain;
    float signal = smoothstep(0.00030, 0.00155, mappedY);
    float snr = smoothstep(1.0, 3.0, mappedY / max(mappedSigma, 0.000001));
    return signal * snr;
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

vec2 localNoiseSigmaAtRadius(vec2 sampleUv, float radiusPixels) {
    if (!savedRawSourceMode()) return vec2(0.0);
    // Lens shading and the S*x+O profile vary smoothly compared with these local
    // structure probes. V2.38 adds a 1.5px microstructure scale; use a slightly
    // smaller spatial safety inflation there but a stricter sigma multiple below.
    float safety = radiusPixels <= 2.0 ? 1.12 : (radiusPixels <= 4.0 ? 1.20 : 1.35);
    return vec2(mappedShortSigmaAt(sampleUv), longSigmaAt(sampleUv)) * safety;
}

float radiometricAgreementAt(vec2 sampleUv) {
    float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
    float longY = max(longLinearLumaAt(sampleUv), 0.00001);
    float errorEv = abs(log2(shortY / longY));
    return 1.0 - smoothstep(0.20, 0.65, errorEv);
}

// IRIS_V238_FULL_RES_CORRESPONDENCE_BARRIER_BEGIN
float stillStaticCorrespondenceAt(vec2 sampleUv) {
    float bounds = stillShortSourceBoundsValidityAt(sampleUv);
    if (bounds < 0.5) return 0.0;
    // Effective-loss replacement is permitted only where this atlas location has its
    // own cycle-validated local match. This is deliberately stricter than topology
    // connectivity: a moving/disoccluded cell may not inherit a neighbor's warp.
    float directGeometry = smoothstep(
        0.10, 0.30, stillLocalDirectRegistrationConfidenceAt(sampleUv));
    float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
    float longY = max(longLinearLumaAt(sampleUv), 0.00001);
    float errorEv = abs(log2(shortY / longY));
    // Leave enough pointwise tolerance for the very microdetail difference we are
    // trying to recover; geometry + same-scene luminance remain mandatory.
    float radiometry = 1.0 - smoothstep(0.35, 1.05, errorEv);
    return bounds * directGeometry * radiometry;
}
// IRIS_V238_FULL_RES_CORRESPONDENCE_BARRIER_END

// IRIS_V227_TEMPORAL_BODY_SNR_BEGIN
float temporalExposureOverlap(float ratio) {
    float stops = max(log2(max(ratio, 1.0)), 0.0);
    // Equal/near-equal pairs are genuine temporal denoise evidence. By ~3.25x the
    // SHORT has become an HDR auxiliary and contributes no body average.
    return 1.0 - smoothstep(0.35, 1.70, stops);
}

float temporalRgbAgreement(vec3 shortScene, vec3 longScene) {
    float scale = max(max3(longScene), 0.020);
    float relativeError = max3(abs(shortScene - longScene)) / scale;
    return 1.0 - smoothstep(0.08, 0.30, relativeError);
}

float temporalShortWeight(float ratio, float support) {
    // Shot-noise-domain inverse-variance proxy after exposure normalization:
    // normalized SHORT variance grows with exposure ratio, so its contribution is
    // 1/(1+ratio). One scalar weight is applied to complete RGB, never per-channel.
    return clamp(support, 0.0, 1.0) / (1.0 + max(ratio, 1.0));
}

float stillTemporalBodySupportAt(vec2 sampleUv, float ratio) {
    float overlap = temporalExposureOverlap(ratio);
    if (overlap <= 0.0) return 0.0;
    vec4 longRaw = savedLongLinearAt(sampleUv);
    vec4 shortRaw = savedShortEvidenceAt(sampleUv);
    vec3 longScene = longRaw.rgb;
    vec3 shortScene = shortRaw.rgb * stillShortScalarGain;
    float body = 1.0 - smoothstep(0.34, 0.67, max3(longScene));
    float geometry = smoothstep(0.20, 0.50, registrationNeighborhoodConfidenceAt(sampleUv));
    float radiometry = radiometricAgreementAt(sampleUv);
    float rgbAgreement = temporalRgbAgreement(shortScene, longScene);
    float shortY = linearLuma(shortScene);
    float shortSigma = shortRaw.a * stillShortScalarGain;
    float shortSignal = smoothstep(0.00045, 0.0023, shortY)
        * smoothstep(1.5, 4.0, shortY / max(shortSigma, 0.000001));
    float sourceBounds = stillShortSourceBoundsValidityAt(sampleUv);
    return overlap * body * geometry * radiometry * rgbAgreement * shortSignal * sourceBounds;
}

float liveTemporalBodySupportAt(
        vec2 sampleUv, float ratio, vec3 shortScene, vec3 longScene) {
    float overlap = temporalExposureOverlap(ratio);
    if (overlap <= 0.0) return 0.0;
    vec3 longRgb = texture(longTex, sampleUv).rgb;
    vec3 shortRgb = texture(shortTex, sampleUv).rgb;
    float body = 1.0 - smoothstep(0.62, 0.84, max3(longRgb));
    float shortY = max(linearLuma(shortScene), 0.00001);
    float longY = max(linearLuma(longScene), 0.00001);
    float temporalEvError = abs(log2(shortY / longY));
    float radiometry = 1.0 - smoothstep(0.18, 0.48, temporalEvError);
    float rgbAgreement = temporalRgbAgreement(shortScene, longScene);
    vec2 localRanges = localLinearRangeAtRadius(sampleUv, 3.0);
    // Live has no residual flow field. Average only locally smooth agreement regions;
    // edges/motion fail closed to LONG. Preview FAST/HQ NR handles the rest.
    float smoothInterior = 1.0 - smoothstep(0.010, 0.040, max(localRanges.x, localRanges.y));
    float shortSignal = smoothstep(0.006, 0.030, encodedLuma(shortRgb));
    return overlap * body * radiometry * rgbAgreement * smoothInterior * shortSignal;
}
// IRIS_V227_TEMPORAL_BODY_SNR_END

float longHardLossBaseAt(vec2 sampleUv) {
    // Live retains the exact encoded-JPEG-era threshold. Saved RAW uses extended
    // linear headroom; storage no longer clips at 1.0 before this test.
    if (!savedRawSourceMode()) {
        return smoothstep(0.965, 0.995, max3(stillLongRgbAt(sampleUv)));
    }
    float physicalSaturation = savedLongSaturationAt(sampleUv);
    float extendedPressure = smoothstep(0.98, 1.15, max3(savedLongLinearAt(sampleUv).rgb));
    return max(physicalSaturation, 0.55 * extendedPressure);
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
    vec2 mediumRanges = localLinearRangeAtRadius(sampleUv, 4.0);
    vec2 broadRanges = localLinearRangeAtRadius(sampleUv, 12.0);
    float shortMediumRange = mediumRanges.x;
    float longMediumRange = mediumRanges.y;
    float shortBroadRange = broadRanges.x;
    float longBroadRange = broadRanges.y;

    // IRIS_V229_INFORMATION_LOSS_NOT_WHITE_GATED_BEGIN
    if (!savedRawSourceMode()) {
        vec3 longRgb = stillLongRgbAt(sampleUv);
        float observableContext = smoothstep(0.08, 0.20, max3(longRgb));
        float mediumStructure = smoothstep(0.004, 0.022, shortMediumRange);
        float mediumRelativeDominance = smoothstep(
            1.03, 1.18, shortMediumRange / max(longMediumRange, 0.0025));
        float mediumAbsoluteDominance = smoothstep(
            0.0010, 0.012, shortMediumRange - longMediumRange);
        float mediumDominance = max(
            0.72 * mediumRelativeDominance, mediumAbsoluteDominance);
        float broadStructure = smoothstep(0.008, 0.050, shortBroadRange);
        float broadRelativeDominance = smoothstep(
            1.02, 1.15, shortBroadRange / max(longBroadRange, 0.0040));
        float broadAbsoluteDominance = smoothstep(
            0.0020, 0.025, shortBroadRange - longBroadRange);
        float broadDominance = max(
            0.75 * broadRelativeDominance, broadAbsoluteDominance);
        float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
        float longY = max(longLinearLumaAt(sampleUv), 0.00001);
        float errorEv = abs(log2(shortY / longY));
        float radiometricPlausibility = 1.0 - smoothstep(1.25, 2.75, errorEv);
        float informationDominance = max(
            mediumStructure * mediumDominance,
            broadStructure * broadDominance);
        return observableContext * informationDominance * radiometricPlausibility;
    }
    // IRIS_V229_INFORMATION_LOSS_NOT_WHITE_GATED_END

    // IRIS_V232_NOISE_NORMALIZED_EFFECTIVE_LOSS_BEGIN
    // The V2.31 bathroom completion remains, but only structure above each frame's
    // physical RAW uncertainty may contribute. This prevents amplified SHORT CFA/noise
    // from masquerading as detail while preserving real house/tree recovery.
    vec2 microRanges = localLinearRangeAtRadius(sampleUv, 1.5);
    float shortMicroRange = microRanges.x;
    float longMicroRange = microRanges.y;
    vec2 microNoise = localNoiseSigmaAtRadius(sampleUv, 1.5);
    vec2 mediumNoise = localNoiseSigmaAtRadius(sampleUv, 4.0);
    vec2 broadNoise = localNoiseSigmaAtRadius(sampleUv, 12.0);
    // The universal V2.38 microstructure class covers grass, pine needles, hair,
    // fabric weave, text, mesh, thin branches and any similarly coherent 1-2px
    // signal. Five-sigma rejection prevents amplified SHORT noise/CFA residue from
    // masquerading as detail, and direct local geometry is mandatory at this scale.
    float shortMicroExcess = max(shortMicroRange - 5.0 * microNoise.x, 0.0);
    float longMicroExcess = max(longMicroRange - 5.0 * microNoise.y, 0.0);
    float shortMediumExcess = max(shortMediumRange - 4.0 * mediumNoise.x, 0.0);
    float longMediumExcess = max(longMediumRange - 4.0 * mediumNoise.y, 0.0);
    float shortBroadExcess = max(shortBroadRange - 4.0 * broadNoise.x, 0.0);
    float longBroadExcess = max(longBroadRange - 4.0 * broadNoise.y, 0.0);

    vec3 longScene = savedLongLinearAt(sampleUv).rgb;
    float observableContext = smoothstep(0.007, 0.033, max3(longScene));
    float microStructure = smoothstep(0.0012, 0.010, shortMicroExcess);
    float microRelativeDominance = smoothstep(
        1.08, 1.32, shortMicroExcess / max(longMicroExcess, 0.0010));
    float microAbsoluteDominance = smoothstep(
        0.00055, 0.0060, shortMicroExcess - longMicroExcess);
    float directMicroGeometry = smoothstep(
        0.14, 0.34, stillLocalDirectRegistrationConfidenceAt(sampleUv));
    float microDominance = microStructure
        * max(0.72 * microRelativeDominance, microAbsoluteDominance)
        * directMicroGeometry;

    float mediumStructure = smoothstep(0.003, 0.020, shortMediumExcess);
    float mediumRelativeDominance = smoothstep(
        1.03, 1.18, shortMediumExcess / max(longMediumExcess, 0.0025));
    float mediumAbsoluteDominance = smoothstep(
        0.0010, 0.012, shortMediumExcess - longMediumExcess);
    float mediumDominance = max(
        0.72 * mediumRelativeDominance, mediumAbsoluteDominance);
    float broadStructure = smoothstep(0.006, 0.045, shortBroadExcess);
    float broadRelativeDominance = smoothstep(
        1.02, 1.15, shortBroadExcess / max(longBroadExcess, 0.0040));
    float broadAbsoluteDominance = smoothstep(
        0.0020, 0.025, shortBroadExcess - longBroadExcess);
    float broadDominance = max(
        0.75 * broadRelativeDominance, broadAbsoluteDominance);

    float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
    float longY = max(longLinearLumaAt(sampleUv), 0.00001);
    float errorEv = abs(log2(shortY / longY));
    float radiometricPlausibility = 1.0 - smoothstep(1.25, 2.75, errorEv);
    float informationDominance = max(
        microDominance,
        max(mediumStructure * mediumDominance,
            broadStructure * broadDominance));
    return observableContext * informationDominance * radiometricPlausibility;
    // IRIS_V232_NOISE_NORMALIZED_EFFECTIVE_LOSS_END
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
    // V2.21 makes the recovery domain PHYSICAL rather than confidence-shaped.
    // Geometry remains mandatory for a strict seed and is enforced as a real
    // motion/disocclusion barrier during reconstruction, but it may not carve
    // random holes through the featureless interior of an already-proven clipped
    // LONG component. This is the key distinction between component topology and
    // local registration confidence.
    vec3 longRgb = stillLongRgbAt(sampleUv);
    vec3 longScene = savedRawSourceMode()
        ? savedLongLinearAt(sampleUv).rgb
        : srgbToLinear(longRgb);
    float shortUsable = shortRecoveryDomainValidityAt(sampleUv);
    float hardLoss = longHardLossBaseAt(sampleUv);
    float hardSupport = compactHardLossSupportAt(sampleUv);
    float nearHardInterior = savedRawSourceMode()
        ? max(savedLongSaturationAt(sampleUv), smoothstep(0.78, 1.00, max3(longScene)))
            * smoothstep(0.05, 0.24, hardSupport)
        : smoothstep(0.86, 0.965, max3(longRgb)) * smoothstep(0.05, 0.24, hardSupport);
    float effectiveLoss = longEffectiveLossAt(sampleUv);
    float physicalLoss = max(hardLoss, max(nearHardInterior, effectiveLoss));
    return shortUsable * physicalLoss;
}

// IRIS_V238_FINAL_MOTION_DISOCCLUSION_BARRIER_BEGIN
float finalLongLossRecoveryAt(vec2 sampleUv) {
    float sourceBounds = stillShortSourceBoundsValidityAt(sampleUv);
    if (sourceBounds < 0.5) return 0.0;
    float shortUsable = shortRecoveryDomainValidityAt(sampleUv);
    float hardLoss = longHardLossBaseAt(sampleUv);
    float hardSupport = compactHardLossSupportAt(sampleUv);
    vec3 longScene = savedRawSourceMode()
        ? savedLongLinearAt(sampleUv).rgb
        : srgbToLinear(stillLongRgbAt(sampleUv));
    float nearHardInterior = savedRawSourceMode()
        ? max(savedLongSaturationAt(sampleUv), smoothstep(0.78, 1.00, max3(longScene)))
            * smoothstep(0.05, 0.24, hardSupport)
        : smoothstep(0.86, 0.965, max3(stillLongRgbAt(sampleUv)))
            * smoothstep(0.05, 0.24, hardSupport);
    float hardPhysicalLoss = max(hardLoss, nearHardInterior);

    // Literal/connected clipping may legitimately destroy LONG correspondence, so it
    // keeps the V2.37 physical-loss authority (with real-source bounds). Non-clipped
    // effective/microdetail replacement is temporal and therefore additionally needs
    // full-resolution static RGB/radiometric correspondence. Moving/disoccluded
    // content fails closed to immutable LONG instead of printing a warped SHORT block.
    float staticCorrespondence = stillStaticCorrespondenceAt(sampleUv);
    float effectiveLoss = longEffectiveLossAt(sampleUv) * staticCorrespondence;
    return shortUsable * max(hardPhysicalLoss, effectiveLoss) * sourceBounds;
}
// IRIS_V238_FINAL_MOTION_DISOCCLUSION_BARRIER_END

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
    // Cover the complete 16x16 ownership cell with a dense 5x5 physical-domain
    // probe. A single strong hard-loss sample may keep the cell connected; vote/
    // average support handles smoother effective-loss plateaus. No registration
    // confidence participates here, so confidence dropouts cannot create gray LONG
    // islands inside one physically connected clipped component.
    vec2 sourceTexel = 1.0 / vec2(textureSize(longTex, 0));
    float domainSum = 0.0;
    float domainVotes = 0.0;
    float maximumDomain = 0.0;
    for (int oy = -2; oy <= 2; ++oy) {
        for (int ox = -2; ox <= 2; ++ox) {
            vec2 offset = vec2(float(ox), float(oy)) * sourceTexel * 4.0;
            float domainValue = longLossRecoveryDomainAt(
                clamp(sampleUv + offset, vec2(0.0), vec2(1.0)));
            domainSum += domainValue;
            domainVotes += step(0.16, domainValue);
            maximumDomain = max(maximumDomain, domainValue);
        }
    }
    float domainAverage = domainSum / 25.0;
    float supportedInterior = smoothstep(0.045, 0.24, domainAverage)
        * smoothstep(1.0, 7.0, domainVotes);
    float hardConnectedCell = smoothstep(0.42, 0.78, maximumDomain);
    return max(supportedInterior, hardConnectedCell);
}
// IRIS_V217_REVERSED_V215_LONG_TRUTH_END

// IRIS_V226_LIVE_LONG_BODY_SHORT_HIGHLIGHT_BEGIN
float livePreviewShortOwnershipAt(vec2 sampleUv) {
    vec3 shortRgb = stillShortRgbAt(sampleUv);
    float shortSignal = smoothstep(0.008, 0.035, encodedLuma(shortRgb));
    float shortChannelValidity = 1.0 - smoothstep(
        0.80, 0.995, channelClipDamage(shortRgb));

    // Literal/compact LONG loss is safe enough for direct live recovery even
    // without the saved-still residual field: LONG contains no detail to preserve.
    float hardLoss = longHardLossBaseAt(sampleUv)
        * smoothstep(0.05, 0.20, compactHardLossSupportAt(sampleUv));

    // Effective-loss recovery is intentionally stricter in live preview because
    // SHORT and LONG are temporally adjacent rather than registered. Require direct
    // radiometric agreement in addition to the shared information-dominance test;
    // motion/disocclusion therefore fails closed to clean LONG body.
    float shortY = max(mappedShortLinearLumaAt(sampleUv), 0.00001);
    float longY = max(longLinearLumaAt(sampleUv), 0.00001);
    float directErrorEv = abs(log2(shortY / longY));
    float directTemporalAgreement = 1.0 - smoothstep(0.65, 1.35, directErrorEv);
    float effectiveLoss = longEffectiveLossAt(sampleUv) * directTemporalAgreement;

    float ownershipProof = max(hardLoss, effectiveLoss)
        * shortSignal * shortChannelValidity;
    return step(0.35, ownershipProof);
}
// IRIS_V226_LIVE_LONG_BODY_SHORT_HIGHLIGHT_END

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
    // IRIS_V229_PRE_SHOULDER_HDR_ENERGY_PRESERVATION_BEGIN
    // Body tone is upstream of adaptiveHdrToneMap and therefore must not project
    // scene-linear HDR back into [0,1]. V2.28's gamutScale silently collapsed any
    // >1.0 SHORT-derived energy before the HDR shoulder could order/compress it.
    // Preserve the complete recovered-highlight interval exactly; below it, apply
    // only the existing bounded luminance body lift and let the HDR shoulder own
    // the first display-referred projection.
    float y = linearLuma(rgb);
    if (y <= 0.000001 || y >= 0.70 || max3(rgb) > 1.0) return rgb;
    float toe = smoothstep(0.015, 0.090, y);
    float highlightProtect = 1.0 - smoothstep(0.45, 0.68, y);
    float targetY = y + 0.45 * toe * highlightProtect * y * (1.0 - clamp(y, 0.0, 1.0));
    float requestedScale = targetY / y;
    return rgb * requestedScale;
    // IRIS_V229_PRE_SHOULDER_HDR_ENERGY_PRESERVATION_END
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
        // IRIS_V237_SPLIT_MANUAL_BG_PREVIEW_BEGIN
        // SPLIT remains the direct SHORT/LONG diagnostic view. It previews only the
        // user-owned Manual Safe Brightness/Gamma controls; automatic Dehaze/Micro
        // continues to be solved/stored by the controller for FUSED output but is not
        // introduced into this diagnostic branch.
        vec3 splitEncoded = leftHalf
            ? texture(shortTex, splitUv).rgb
            : texture(longTex, splitUv).rgb;
        vec3 splitLinear = srgbToLinear(splitEncoded);
        float splitBrightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        splitLinear *= splitBrightnessGain;
        splitLinear = applyDisplayGamma(splitLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(splitLinear), 0.0, 1.0), 1.0);
        // IRIS_V237_SPLIT_MANUAL_BG_PREVIEW_END
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

    // IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_BEGIN
    // Mode 3 creates two distinct masks: R is a strict, locally registered seed;
    // G is a topology-complete PHYSICAL LONG-loss + usable-SHORT domain. BA carry
    // residual SHORT flow owned by the seed. Mode 4 reconstructs to convergence;
    // local flow confidence is used only as a supported motion/disocclusion barrier,
    // never as a hole-punching requirement for a featureless clipped interior.
    if (mode == 3) {
        vec3 seedStats = broadRecoverySeedStatsAt(uv);
        float seedStrength = max(
            smoothstep(0.08, 0.30, seedStats.x) * smoothstep(2.0, 7.0, seedStats.y),
            smoothstep(0.22, 0.52, seedStats.z) * smoothstep(1.0, 4.0, seedStats.y));
        float seed = step(0.30, seedStrength);
        // V2.31 bathroom regression: strict seeds remain unchanged, but a connected
        // component may traverse moderately flattened exterior structure. The domain
        // is not ownership by itself; mode 4 still enforces coherent geometry and mode
        // 5 performs a full-resolution physical-loss check before selecting SHORT.
        float recoveryDomain = step(0.16, broadRecoveryDomainAt(uv));
        vec2 localFlow = registrationNeighborhoodFlowAt(uv);
        vec2 seedFlow = mix(vec2(0.5), localFlow, seed);
        outColor = vec4(seed, recoveryDomain, seedFlow);
        return;
    }

    if (mode == 4) {
        // Morphological reconstruction by dilation under a mask, not finite-radius
        // closing. Ownership can advance exactly one atlas cell per pass and only
        // through G==recoveryDomain. The operation is monotonic; already owned cells
        // can never revert to LONG. BA remains an internal coherence carrier for the
        // topology walk only; V2.29 mode 5 never uses propagated BA to warp final
        // pixels. Unsupported final panes therefore use the stable globally registered
        // SHORT geometry rather than inheriting a path-propagated residual warp.
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

        // A target cell with its own trustworthy local measurement is a genuine
        // geometric authority. It may join the component only when that measured
        // residual agrees with the residual propagated from already-owned neighbors.
        // An unsupported clipped cell has no such authority and therefore inherits
        // the coherent component flow instead of becoming a false barrier.
        float targetConfidence = stillLocalRegistrationConfidenceAt(uv);
        vec2 targetFlowPixels = (stillLocalFlowAt(uv).rg * 2.0 - vec2(1.0))
            * localFlowMaxPixels;
        float targetFlowError = length(targetFlowPixels - meanFlowPixels);
        float targetMeasured = smoothstep(0.16, 0.36, targetConfidence);
        float targetAgreement = 1.0 - smoothstep(0.75, 1.25, targetFlowError);
        float geometryBarrier = mix(1.0, targetAgreement, targetMeasured);
        float propagate = step(0.35, coherentFlow * geometryBarrier) * recoveryDomain;

        vec2 propagatedFlowPixels = mix(
            meanFlowPixels,
            targetFlowPixels,
            targetMeasured * targetAgreement);
        vec2 encodedFlow = localFlowMaxPixels > 0.0
            ? clamp(vec2(0.5) + 0.5 * propagatedFlowPixels / localFlowMaxPixels,
                    vec2(0.0), vec2(1.0))
            : vec2(0.5);
        outColor = vec4(propagate, recoveryDomain, mix(centerState.ba, encodedFlow, propagate));
        return;
    }
    // IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_END

    if (mode == 5) {
        // IRIS_V217_REGION_SOURCE_OWNERSHIP_BEGIN
        // LONG is the complete clean body. Once mode 3/4 establishes a coherent
        // information-loss region, aligned SHORT owns that region as one source-truth
        // image. There is deliberately no full-resolution recoveryProof re-test that
        // can punch gray/lavender LONG holes back through a valid SHORT highlight.
        float ratio = clamp(exposureRatio, 1.0, 65536.0);
        float bracketStops = clamp(log2(max(ratio, 1.0001)), 0.0, 6.0);
        vec4 support = texture(normalTex, uv);
        // IRIS_V229_FULL_RES_FINAL_SHORT_OWNERSHIP_BEGIN
        // The 16x16 atlas is connectivity authority only. It may prove that this
        // full-resolution pixel belongs to a connected recoverable component, but
        // it may not turn the entire atlas cell into SHORT. Re-evaluate the physical
        // LONG-loss/usable-SHORT domain at the actual output pixel.
        float connectedRecovery = step(0.50, support.r);
        // V2.32 evaluates the physical recovery domain once at native resolution.
        // Strict seed/geometry proof already belongs to the connected mode-3/4 atlas;
        // recomputing shortRecoveryEvidence here duplicated the expensive multi-scale
        // RAW probes and could not create a new connected owner by itself.
        float fullResolutionLoss = finalLongLossRecoveryAt(uv);
        // V2.31 completes the connected exterior component at native resolution.
        // The V2.29 0.16 re-test left house/siding/tree holes even after a valid sky
        // seed had proven the component. Connectivity + physical loss remain mandatory;
        // this lower final threshold cannot grant SHORT ownership to unrelated body.
        float shortOwns = connectedRecovery * step(0.08, fullResolutionLoss);

        // The globally registered SHORT bitmap is already in immutable LONG geometry.
        // Use the proven local residual field only where that field itself supplies it;
        // unsupported panes therefore fall back to the stable global registration.
        // Never warp a pane with a residual merely propagated along an atlas path.
        vec4 shortRaw = savedShortLinearAt(uv);
        // IRIS_V229_FULL_RES_FINAL_SHORT_OWNERSHIP_END
        vec4 longRaw = savedLongLinearAt(uv);
        vec3 shortScene = shortRaw.rgb * stillShortScalarGain;
        vec3 longScene = longRaw.rgb;

        // V2.27 low-DR body denoise is a separate owner from HDR replacement. V2.32
        // keeps this operation in the extended-linear RAW-derived domain; no sRGB
        // encode/decode round-trip or gamma-space SHORT interpolation participates.
        vec3 bodyShortScene = shortRaw.rgb * stillShortScalarGain;
        float bodySupport = stillTemporalBodySupportAt(uv, ratio);
        float bodyShortWeight = temporalShortWeight(ratio, bodySupport);
        vec3 temporalBody = mix(longScene, bodyShortScene, bodyShortWeight);
        vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;

        float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
        vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
        vec3 displayLinear = savedContinuousHdrToneMap(
            bodyToned, ratio, bracketStops);
        displayLinear = applyDisplayGamma(displayLinear, displayGamma);
        outColor = vec4(clamp(linearToSrgb(displayLinear), 0.0, 1.0), 1.0);
        // IRIS_V217_REGION_SOURCE_OWNERSHIP_END
        return;
    }

    // V2.27 live parity: LONG remains the default body source. A near-equal pair
    // may contribute only smooth, direct-agreement temporal denoise; highlight
    // replacement remains binary SHORT and motion/disocclusion fails closed to LONG.
    float ratio = clamp(exposureRatio, 1.0, 65536.0);
    float bracketStops = clamp(log2(max(ratio, 1.0001)), 0.0, 6.0);
    vec3 shortRgb = texture(shortTex, uv).rgb;
    vec3 longRgb = texture(longTex, uv).rgb;
    vec3 shortScene = srgbToLinear(shortRgb) * ratio;
    vec3 longScene = srgbToLinear(longRgb);
    float liveShortOwns = livePreviewShortOwnershipAt(uv);
    float liveBodySupport = liveTemporalBodySupportAt(uv, ratio, shortScene, longScene);
    float liveBodyShortWeight = temporalShortWeight(ratio, liveBodySupport);
    vec3 liveTemporalBody = mix(longScene, shortScene, liveBodyShortWeight);
    vec3 mergedScene = liveShortOwns > 0.5 ? shortScene : liveTemporalBody;

    float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));
    vec3 bodyToned = applyPhotographicBodyTone(mergedScene * brightnessGain);
    vec3 displayLinear = adaptiveHdrToneMap(bodyToned, ratio, bracketStops);
    displayLinear = applyDisplayGamma(displayLinear, displayGamma);
    displayLinear = applyAdaptiveClarity(displayLinear, uv);
    vec3 displayRgb = linearToSrgb(displayLinear);
    outColor = vec4(clamp(displayRgb, 0.0, 1.0), 1.0);
}
