#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D sourceTex;

const float SIGMA_COMPAND_K = 0.01;
const float CARRIER_MAX = 254.0 / 255.0;
const float CARRIER_BODY_END = CARRIER_MAX * 0.42;
const float CARRIER_DETAIL_END = CARRIER_MAX * 0.92;
const float CARRIER_DETAIL_TOP = 8.0;
const float CARRIER_DETAIL_STOPS = 3.0;
const float CARRIER_TAIL_TOP = 32.0;
const float CARRIER_TAIL_STOPS = 2.0;

float expandPositive(float encoded, float k) {
    float e = min(max(encoded, 0.0), CARRIER_MAX);
    float e2 = e * e;
    return k * e2 / max(1.0 - e2, 0.0000001);
}

float compandPositive(float value, float k) {
    float x = max(value, 0.0);
    return min(CARRIER_MAX, sqrt(x / max(x + k, 0.0000001)));
}

// IRIS_V234_HIGHLIGHT_PRECISION_CARRIER_BEGIN
float decodeSceneChannel(float encoded) {
    float e = clamp(encoded, 0.0, CARRIER_MAX);
    if (e <= CARRIER_BODY_END) {
        float t = e / max(CARRIER_BODY_END, 0.000001);
        return t * t;
    }
    if (e <= CARRIER_DETAIL_END) {
        float t = (e - CARRIER_BODY_END)
            / max(CARRIER_DETAIL_END - CARRIER_BODY_END, 0.000001);
        return exp2(CARRIER_DETAIL_STOPS * t);
    }
    float tailT = clamp((e - CARRIER_DETAIL_END)
        / max(CARRIER_MAX - CARRIER_DETAIL_END, 0.000001), 0.0, 1.0);
    return CARRIER_DETAIL_TOP * exp2(CARRIER_TAIL_STOPS * tailT);
}

float encodeSceneChannel(float value) {
    float x = max(value, 0.0);
    if (x <= 1.0) return CARRIER_BODY_END * sqrt(x);
    if (x <= CARRIER_DETAIL_TOP) {
        return CARRIER_BODY_END
            + (CARRIER_DETAIL_END - CARRIER_BODY_END)
                * (log2(x) / CARRIER_DETAIL_STOPS);
    }
    float tailStops = clamp(log2(x / CARRIER_DETAIL_TOP), 0.0, CARRIER_TAIL_STOPS);
    float tailT = tailStops / CARRIER_TAIL_STOPS;
    return min(CARRIER_MAX,
        CARRIER_DETAIL_END + (CARRIER_MAX - CARRIER_DETAIL_END) * tailT);
}
// IRIS_V234_HIGHLIGHT_PRECISION_CARRIER_END

ivec2 clampPixel(ivec2 p) {
    ivec2 size = textureSize(sourceTex, 0);
    return clamp(p, ivec2(0), size - ivec2(1));
}

vec4 carrierAt(ivec2 p) {
    return texelFetch(sourceTex, clampPixel(p), 0);
}

vec3 sceneAt(ivec2 p) {
    vec3 encoded = carrierAt(p).rgb;
    return vec3(
        decodeSceneChannel(encoded.r),
        decodeSceneChannel(encoded.g),
        decodeSceneChannel(encoded.b));
}

float sigmaAt(ivec2 p) {
    float code = floor(carrierAt(p).a * 255.0 + 0.5);
    float saturation = step(127.5, code);
    float sigmaCode = code - 128.0 * saturation;
    return expandPositive(sigmaCode / 127.0, SIGMA_COMPAND_K);
}

float saturationAt(ivec2 p) {
    float code = floor(carrierAt(p).a * 255.0 + 0.5);
    return step(127.5, code);
}

float encodeSigmaAndSaturation(float sigma, float saturation) {
    float sigmaEncoded = compandPositive(sigma, SIGMA_COMPAND_K);
    float sigmaCode = min(127.0, floor(clamp(sigmaEncoded, 0.0, 1.0) * 127.0 + 0.5));
    return (sigmaCode + 128.0 * step(0.5, saturation)) / 255.0;
}

float linearLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float min3(vec3 value) {
    return min(value.r, min(value.g, value.b));
}

vec2 chromaAt(vec3 rgb, float y) {
    return vec2(rgb.b - y, rgb.r - y);
}

vec3 rgbFromLumaChroma(float y, vec2 chroma) {
    float blue = y + chroma.x;
    float red = y + chroma.y;
    float green = (y - 0.2126 * red - 0.0722 * blue) / 0.7152;
    return vec3(red, green, blue);
}

float median9(float v0, float v1, float v2, float v3, float v4,
        float v5, float v6, float v7, float v8) {
    float values[9] = float[9](v0, v1, v2, v3, v4, v5, v6, v7, v8);
    for (int i = 0; i < 8; ++i) {
        for (int j = i + 1; j < 9; ++j) {
            float lowValue = min(values[i], values[j]);
            float highValue = max(values[i], values[j]);
            values[i] = lowValue;
            values[j] = highValue;
        }
    }
    return values[4];
}

vec3 projectNonNegativeAtFixedLuma(vec3 rgb, float y) {
    vec3 neutral = vec3(max(y, 0.0));
    float lowValue = min3(rgb);
    if (lowValue < 0.0) {
        float scale = clamp(y / max(y - lowValue, 0.000001), 0.0, 1.0);
        rgb = mix(neutral, rgb, scale);
    }
    return max(rgb, vec3(0.0));
}


void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 offsets[9] = ivec2[9](
        ivec2(0, 0),
        ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1),
        ivec2(-1, -1), ivec2(1, -1), ivec2(-1, 1), ivec2(1, 1));

    vec3 rgbValues[9];
    float yValues[9];
    vec2 cValues[9];
    float sigmaValues[9];
    float saturationValues[9];
    for (int i = 0; i < 9; ++i) {
        rgbValues[i] = sceneAt(p + offsets[i]);
        yValues[i] = linearLuma(rgbValues[i]);
        cValues[i] = chromaAt(rgbValues[i], yValues[i]);
        sigmaValues[i] = sigmaAt(p + offsets[i]);
        saturationValues[i] = saturationAt(p + offsets[i]);
    }

    float centerY = yValues[0];
    vec2 centerC = cValues[0];
    vec2 medianC = vec2(
        median9(cValues[0].x, cValues[1].x, cValues[2].x, cValues[3].x, cValues[4].x,
                cValues[5].x, cValues[6].x, cValues[7].x, cValues[8].x),
        median9(cValues[0].y, cValues[1].y, cValues[2].y, cValues[3].y, cValues[4].y,
                cValues[5].y, cValues[6].y, cValues[7].y, cValues[8].y));
    float medianY = median9(
        yValues[0], yValues[1], yValues[2], yValues[3], yValues[4],
        yValues[5], yValues[6], yValues[7], yValues[8]);
    float medianSigma = median9(
        sigmaValues[0], sigmaValues[1], sigmaValues[2], sigmaValues[3], sigmaValues[4],
        sigmaValues[5], sigmaValues[6], sigmaValues[7], sigmaValues[8]);

    float localMinimumY = yValues[0];
    float localMaximumY = yValues[0];
    float coherentVotes = 0.0;
    float chromaNoise = max(0.00020, 1.60 * max(sigmaValues[0], medianSigma));
    for (int i = 1; i < 9; ++i) {
        localMinimumY = min(localMinimumY, yValues[i]);
        localMaximumY = max(localMaximumY, yValues[i]);
        coherentVotes += 1.0 - smoothstep(
            2.0 * chromaNoise,
            5.0 * chromaNoise + 0.002,
            length(cValues[i] - centerC));
    }

    float localLumaRange = localMaximumY - localMinimumY;
    float chromaExcursion = length(centerC - medianC);
    float normalizedChromaExcursion = chromaExcursion / chromaNoise;
    float lumaExcursion = abs(centerY - medianY);

    // A real color region normally has several neighboring pixels with similar chroma.
    // An isolated CFA false-color dot does not. Smooth luminance plus a many-sigma
    // chroma excursion therefore gets strong cleanup without touching flat luminance.
    float lowLumaStructure = 1.0 - smoothstep(
        2.0 * chromaNoise + 0.002,
        8.0 * chromaNoise + 0.018,
        max(localLumaRange, lumaExcursion));
    float isolatedChroma = 1.0 - smoothstep(1.5, 4.5, coherentVotes);
    float flatFalseColor = smoothstep(3.0, 7.0, normalizedChromaExcursion)
        * lowLumaStructure * isolatedChroma;

    // One-pixel Bayer aliases on mesh/shutters alternate: both opposite neighbors move
    // away from the center in the same opponent-chroma direction. Normalize the dot
    // product by the physical noise scale so ordinary sensor noise cannot trigger it.
    float noiseEnergy = chromaNoise * chromaNoise;
    float horizontalDot = dot(cValues[2] - centerC, cValues[1] - centerC);
    float verticalDot = dot(cValues[3] - centerC, cValues[4] - centerC);
    float horizontalAlternation = smoothstep(
        1.0 * noiseEnergy, 16.0 * noiseEnergy + 0.000020, horizontalDot);
    float verticalAlternation = smoothstep(
        1.0 * noiseEnergy, 16.0 * noiseEnergy + 0.000020, verticalDot);
    float alternatingChroma = max(horizontalAlternation, verticalAlternation);
    float periodicAlias = smoothstep(2.5, 6.0, normalizedChromaExcursion)
        * alternatingChroma;

    float fineStructure = smoothstep(
        max(0.004, 2.0 * chromaNoise),
        max(0.030, 8.0 * chromaNoise),
        localLumaRange);
    float brightEdge = smoothstep(0.65, 1.25, max(rgbValues[0].r,
        max(rgbValues[0].g, rgbValues[0].b)))
        * fineStructure
        * smoothstep(3.0, 7.0, normalizedChromaExcursion)
        * (1.0 - smoothstep(3.0, 6.0, coherentVotes));

    // IRIS_V238_SATURATION_TRANSITION_CHROMA_RELIABILITY_BEGIN
    // V2.36 removed distant hue donors, but a false pink/green CFA fringe can be
    // spatially coherent along a long saturated strip light and therefore look like
    // legitimate color to coherentVotes. Physical RAW saturation is independent
    // evidence. Only its immediate 3x3 transition halo may use this correction.
    // raw_reconstruct already expands literal RAW saturation over the immediate 3x3
    // neighborhood. Use the center evidence here; taking another 3x3 maximum would
    // unnecessarily grow the special chroma authority to roughly a 5x5 halo.
    float localSaturation = saturationValues[0];
    float unsaturatedWeight = 0.0;
    vec2 unsaturatedChromaSum = vec2(0.0);
    float centerSide = centerY >= medianY ? 1.0 : -1.0;
    for (int i = 0; i < 9; ++i) {
        if (i == 0 || saturationValues[i] > 0.5) continue;
        float neighborSide = (yValues[i] - medianY) * centerSide;
        float sameSide = smoothstep(
            -2.0 * chromaNoise - 0.002,
             2.0 * chromaNoise + 0.002,
            neighborSide);
        float lumaDistance = abs(yValues[i] - centerY);
        float lumaCompatible = 1.0 - smoothstep(
            0.018 + 3.0 * chromaNoise,
            0.090 + 8.0 * chromaNoise,
            lumaDistance);
        float weight = sameSide * lumaCompatible;
        unsaturatedChromaSum += cValues[i] * weight;
        unsaturatedWeight += weight;
    }
    vec2 unsaturatedC = unsaturatedWeight > 0.0001
        ? unsaturatedChromaSum / unsaturatedWeight
        : medianC;
    float unsaturatedSupport = smoothstep(1.2, 3.5, unsaturatedWeight);
    float transitionExcursion = length(centerC - unsaturatedC) / chromaNoise;
    float saturationTransition = localSaturation
        * smoothstep(2.5, 6.0, transitionExcursion)
        * unsaturatedSupport
        * fineStructure;
    // Strong correction is confined to a physical saturation halo. Ordinary bright
    // colored edges retain the existing V2.36 local chroma authority.
    float saturationStrength = clamp(0.90 * saturationTransition, 0.0, 0.94);
    // IRIS_V238_SATURATION_TRANSITION_CHROMA_RELIABILITY_END

    float baseStrength = max(flatFalseColor, max(0.88 * periodicAlias, 0.78 * brightEdge));
    baseStrength = clamp(baseStrength, 0.0, 0.92);

    // Saturation-transition cleanup owns only its immediate physical halo and uses
    // the unsaturated same-side estimate. The inherited local median remains the
    // owner for isolated/periodic non-saturation CFA aliases.
    vec2 baseCorrectedC = mix(centerC, medianC, baseStrength);
    vec2 correctedC = mix(baseCorrectedC, unsaturatedC, saturationStrength);

    // IRIS_V239_RAW_NOISE_MODEL_CLEANUP_BEGIN
    // The RAW carrier already contains the conservative Camera2 S*x+O scene sigma.
    // Use that uncertainty before tone/gamma amplification to suppress only noise-like
    // smooth interiors. Luma and chroma have separate strengths: chroma receives the
    // stronger reduction needed for green/magenta RAW grain while luma retains more
    // source texture. Range weights make this a local bilateral estimator rather than
    // blur, and coherent 1-2px luminance structure universally protects grass, pine
    // needles, hair, fabric, text, foliage, carpet fibers, mesh and equivalent detail.
    float sceneNoise = max(0.00008, max(sigmaValues[0], medianSigma));
    float lumaNoiseScale = max(0.00010, 1.18 * sceneNoise);
    float chromaNoiseScale = max(0.00016, 1.70 * sceneNoise);

    float lumaDifferenceEnergy = 0.0;
    for (int i = 1; i < 9; ++i) {
        float deltaY = yValues[i] - centerY;
        lumaDifferenceEnergy += deltaY * deltaY;
    }
    float lumaDifferenceRms = sqrt(lumaDifferenceEnergy / 8.0);
    // Pure independent noise has center-to-neighbor RMS near sqrt(2)*sigma.
    // Real coherent structure rises beyond that envelope and fades denoise authority.
    float coherentStructure = smoothstep(
        1.70, 3.35, lumaDifferenceRms / lumaNoiseScale);
    float smoothNoiseInterior = 1.0 - coherentStructure;

    float lumaWeightSum = 1.0;
    float lumaSum = centerY;
    float chromaWeightSum = 1.0;
    vec2 chromaSum = correctedC;
    for (int i = 1; i < 9; ++i) {
        float lumaDistance = abs(yValues[i] - centerY);
        float chromaDistance = length(cValues[i] - correctedC);
        float lumaCompatible = 1.0 - smoothstep(
            2.0 * lumaNoiseScale + 0.0005,
            5.5 * lumaNoiseScale + 0.0050,
            lumaDistance);
        float chromaCompatible = 1.0 - smoothstep(
            2.2 * chromaNoiseScale + 0.0008,
            6.5 * chromaNoiseScale + 0.0080,
            chromaDistance);
        float unsaturatedNeighbor = 1.0 - 0.90 * saturationValues[i];
        float weight = lumaCompatible * chromaCompatible * unsaturatedNeighbor;
        lumaSum += yValues[i] * weight;
        lumaWeightSum += weight;
        chromaSum += cValues[i] * weight;
        chromaWeightSum += weight;
    }

    // Four same-domain radius-2 samples increase Gaussian-noise averaging without
    // crossing edges because they pass the same luma/chroma range test. This reaches
    // a materially lower chroma floor in walls/ceilings while remaining local.
    ivec2 farOffsets[4] = ivec2[4](
        ivec2(-2, 0), ivec2(2, 0), ivec2(0, -2), ivec2(0, 2));
    for (int i = 0; i < 4; ++i) {
        ivec2 q = p + farOffsets[i];
        vec3 farRgb = sceneAt(q);
        float farY = linearLuma(farRgb);
        vec2 farC = chromaAt(farRgb, farY);
        float farSaturation = saturationAt(q);
        float lumaDistance = abs(farY - centerY);
        float chromaDistance = length(farC - correctedC);
        float lumaCompatible = 1.0 - smoothstep(
            2.0 * lumaNoiseScale + 0.0005,
            5.0 * lumaNoiseScale + 0.0045,
            lumaDistance);
        float chromaCompatible = 1.0 - smoothstep(
            2.2 * chromaNoiseScale + 0.0008,
            6.0 * chromaNoiseScale + 0.0070,
            chromaDistance);
        float weight = lumaCompatible * chromaCompatible * (1.0 - 0.90 * farSaturation);
        lumaSum += farY * weight;
        lumaWeightSum += weight;
        chromaSum += farC * weight;
        chromaWeightSum += weight;
    }

    float filteredY = lumaSum / max(lumaWeightSum, 1.0);
    vec2 filteredC = chromaSum / max(chromaWeightSum, 1.0);
    // Physical saturation/highlight-transition color stays with the existing V2.38
    // owner. Ordinary smooth RAW interiors can receive strong chroma and moderate
    // luma cleanup; coherent source structure drives both strengths toward zero.
    float ordinaryNoiseDomain = 1.0 - localSaturation;
    float lumaDenoiseStrength = clamp(
        0.52 * smoothNoiseInterior * ordinaryNoiseDomain, 0.0, 0.52);
    float chromaDenoiseStrength = clamp(
        0.92 * smoothNoiseInterior * ordinaryNoiseDomain, 0.0, 0.92);
    float cleanedY = mix(centerY, filteredY, lumaDenoiseStrength);
    correctedC = mix(correctedC, filteredC, chromaDenoiseStrength);
    // Alpha intentionally remains the original conservative physical sigma+saturation
    // upper bound. Denoise may lower residual noise, but it may never make subsequent
    // fusion believe the sensor was more certain than the timestamp-matched S*x+O model.
    // IRIS_V239_RAW_NOISE_MODEL_CLEANUP_END

    // IRIS_V236_SINGLE_CHROMA_AUTHORITY:
    // CFA reconstruction now owns highlight/edge chroma. This stage may suppress
    // only local, noise-proven single-pixel/periodic aliases; it may not search
    // distant +/-4..10 pixels and borrow an unrelated hue across foliage, signs,
    // shelves, skin, or architectural edges.
    vec3 correctedRgb = rgbFromLumaChroma(cleanedY, correctedC);
    correctedRgb = projectNonNegativeAtFixedLuma(correctedRgb, cleanedY);

    vec3 encodedScene = vec3(
        encodeSceneChannel(correctedRgb.r),
        encodeSceneChannel(correctedRgb.g),
        encodeSceneChannel(correctedRgb.b));
    // Alpha is copied byte-for-byte from V2.32 center authority. Chroma repair may not
    // change physical sigma or the RAW saturation bit consumed by fusion ownership.
    outColor = vec4(encodedScene, carrierAt(p).a);
}
