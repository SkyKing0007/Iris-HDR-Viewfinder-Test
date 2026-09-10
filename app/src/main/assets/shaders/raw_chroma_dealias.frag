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
    for (int i = 0; i < 9; ++i) {
        rgbValues[i] = sceneAt(p + offsets[i]);
        yValues[i] = linearLuma(rgbValues[i]);
        cValues[i] = chromaAt(rgbValues[i], yValues[i]);
        sigmaValues[i] = sigmaAt(p + offsets[i]);
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

    float baseStrength = max(flatFalseColor, max(0.88 * periodicAlias, 0.78 * brightEdge));
    baseStrength = clamp(baseStrength, 0.0, 0.92);

    // IRIS_V236_SINGLE_CHROMA_AUTHORITY:
    // CFA reconstruction now owns highlight/edge chroma. This stage may suppress
    // only local, noise-proven single-pixel/periodic aliases; it may not search
    // distant +/-4..10 pixels and borrow an unrelated hue across foliage, signs,
    // shelves, skin, or architectural edges.
    vec2 correctedC = mix(centerC, medianC, baseStrength);
    vec3 correctedRgb = rgbFromLumaChroma(centerY, correctedC);
    correctedRgb = projectNonNegativeAtFixedLuma(correctedRgb, centerY);

    vec3 encodedScene = vec3(
        encodeSceneChannel(correctedRgb.r),
        encodeSceneChannel(correctedRgb.g),
        encodeSceneChannel(correctedRgb.b));
    // Alpha is copied byte-for-byte from V2.32 center authority. Chroma repair may not
    // change physical sigma or the RAW saturation bit consumed by fusion ownership.
    outColor = vec4(encodedScene, carrierAt(p).a);
}
