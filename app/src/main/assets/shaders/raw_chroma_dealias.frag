#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D sourceTex;

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
    float x = max(value, 0.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

vec3 linearToSrgb(vec3 value) {
    return vec3(
        linearToSrgbChannel(value.r),
        linearToSrgbChannel(value.g),
        linearToSrgbChannel(value.b));
}

float linearLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float max3(vec3 value) {
    return max(value.r, max(value.g, value.b));
}

float min3(vec3 value) {
    return min(value.r, min(value.g, value.b));
}

float median5(float a, float b, float c, float d, float e) {
    float values[5] = float[5](a, b, c, d, e);
    for (int i = 0; i < 4; ++i) {
        for (int j = i + 1; j < 5; ++j) {
            float low = min(values[i], values[j]);
            float high = max(values[i], values[j]);
            values[i] = low;
            values[j] = high;
        }
    }
    return values[2];
}

ivec2 clampPixel(ivec2 p) {
    ivec2 size = textureSize(sourceTex, 0);
    return clamp(p, ivec2(0), size - ivec2(1));
}

vec3 linearAt(ivec2 p) {
    return srgbToLinear(texelFetch(sourceTex, clampPixel(p), 0).rgb);
}

vec2 chromaAt(vec3 rgb, float y) {
    // Opponent coordinates are expressed around exact luminance so filtering these
    // two values cannot alter spatial/luma detail.
    return vec2(rgb.b - y, rgb.r - y);
}

vec3 rgbFromLumaChroma(float y, vec2 chroma) {
    float blue = y + chroma.x;
    float red = y + chroma.y;
    float green = (y - 0.2126 * red - 0.0722 * blue) / 0.7152;
    return vec3(red, green, blue);
}

vec3 projectAtFixedLuma(vec3 rgb, float y) {
    vec3 neutral = vec3(clamp(y, 0.0, 1.0));
    float low = min3(rgb);
    if (low < 0.0) {
        float t = clamp(y / max(y - low, 0.000001), 0.0, 1.0);
        rgb = mix(neutral, rgb, t);
    }
    float high = max3(rgb);
    if (high > 1.0) {
        float t = clamp((1.0 - y) / max(high - y, 0.000001), 0.0, 1.0);
        rgb = mix(neutral, rgb, t);
    }
    return clamp(rgb, vec3(0.0), vec3(1.0));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 centerRgb = linearAt(p);
    vec3 northRgb = linearAt(p + ivec2(0, -1));
    vec3 southRgb = linearAt(p + ivec2(0, 1));
    vec3 westRgb = linearAt(p + ivec2(-1, 0));
    vec3 eastRgb = linearAt(p + ivec2(1, 0));

    float centerY = linearLuma(centerRgb);
    float northY = linearLuma(northRgb);
    float southY = linearLuma(southRgb);
    float westY = linearLuma(westRgb);
    float eastY = linearLuma(eastRgb);
    vec2 centerC = chromaAt(centerRgb, centerY);
    vec2 northC = chromaAt(northRgb, northY);
    vec2 southC = chromaAt(southRgb, southY);
    vec2 westC = chromaAt(westRgb, westY);
    vec2 eastC = chromaAt(eastRgb, eastY);

    vec2 medianC = vec2(
        median5(centerC.x, northC.x, southC.x, westC.x, eastC.x),
        median5(centerC.y, northC.y, southC.y, westC.y, eastC.y));
    float medianY = median5(centerY, northY, southY, westY, eastY);

    float chromaExcursion = length(centerC - medianC);
    float lumaExcursion = abs(centerY - medianY);
    float localLumaRange = max(
        max(abs(northY - centerY), abs(southY - centerY)),
        max(abs(westY - centerY), abs(eastY - centerY)));

    // Bayer false color and moire characteristically alternate around one pixel:
    // both opposite neighbors move away from the center in the same chroma direction.
    // A real step edge normally has one same-side neighbor, so this product collapses.
    float horizontalAlternation = smoothstep(
        0.00015, 0.0035, dot(eastC - centerC, westC - centerC));
    float verticalAlternation = smoothstep(
        0.00015, 0.0035, dot(northC - centerC, southC - centerC));
    float alternatingChroma = max(horizontalAlternation, verticalAlternation);

    // Only chroma energy substantially in excess of local luminance structure is
    // eligible. This protects real monochrome detail and keeps luminance byte-for-byte
    // owned by the demosaic result while reducing colored Nyquist aliases.
    float excessChroma = chromaExcursion
        - 0.70 * lumaExcursion
        - 0.18 * localLumaRange;
    float aliasEvidence = smoothstep(0.004, 0.035, excessChroma);
    float fineStructure = smoothstep(0.004, 0.040, localLumaRange);
    float brightEdge = smoothstep(0.55, 0.92, max3(centerRgb)) * fineStructure;
    float strength = aliasEvidence * max(
        0.68 * alternatingChroma,
        0.82 * brightEdge);
    strength = clamp(strength, 0.0, 0.85);

    vec2 correctedC = mix(centerC, medianC, strength);
    vec3 correctedRgb = rgbFromLumaChroma(centerY, correctedC);
    correctedRgb = projectAtFixedLuma(correctedRgb, centerY);
    outColor = vec4(clamp(linearToSrgb(correctedRgb), vec3(0.0), vec3(1.0)), 1.0);
}
