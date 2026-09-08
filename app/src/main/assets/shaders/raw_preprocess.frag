#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform highp usampler2D rawTex;
uniform highp sampler2D shadingTex;
uniform vec4 blackPatternCode;
uniform float whiteLevelCode;
uniform int cfaArrangement;

int patternIndex(ivec2 p) {
    return ((p.y & 1) << 1) | (p.x & 1);
}

int colorAt(ivec2 p) {
    int i = patternIndex(p);
    if (cfaArrangement == 0) { // RGGB
        if (i == 0) return 0;
        if (i == 3) return 2;
        return 1;
    }
    if (cfaArrangement == 1) { // GRBG
        if (i == 1) return 0;
        if (i == 2) return 2;
        return 1;
    }
    if (cfaArrangement == 2) { // GBRG
        if (i == 2) return 0;
        if (i == 1) return 2;
        return 1;
    }
    // BGGR
    if (i == 3) return 0;
    if (i == 0) return 2;
    return 1;
}

float blackAt(ivec2 p) {
    int i = patternIndex(p);
    if (i == 0) return blackPatternCode.x;
    if (i == 1) return blackPatternCode.y;
    if (i == 2) return blackPatternCode.z;
    return blackPatternCode.w;
}

vec4 shadingMapAt(ivec2 p) {
    ivec2 rawSize = textureSize(rawTex, 0);
    ivec2 mapSize = textureSize(shadingTex, 0);
    vec2 rawDenom = max(vec2(rawSize - ivec2(1)), vec2(1.0));
    vec2 mapMax = max(vec2(mapSize - ivec2(1)), vec2(0.0));
    vec2 mapPos = vec2(p) / rawDenom * mapMax;
    ivec2 p0 = ivec2(floor(mapPos));
    ivec2 p1 = min(p0 + ivec2(1), mapSize - ivec2(1));
    vec2 f = fract(mapPos);
    vec4 a = mix(texelFetch(shadingTex, p0, 0),
                 texelFetch(shadingTex, ivec2(p1.x, p0.y), 0), f.x);
    vec4 b = mix(texelFetch(shadingTex, ivec2(p0.x, p1.y), 0),
                 texelFetch(shadingTex, p1, 0), f.x);
    return mix(a, b, f.y);
}

float shadingGainAt(ivec2 p) {
    vec4 gains = shadingMapAt(p);
    int color = colorAt(p);
    if (color == 0) return gains.x;
    if (color == 2) return gains.w;
    return (p.y & 1) == 0 ? gains.y : gains.z;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float code = float(texelFetch(rawTex, p, 0).r);
    float black = blackAt(p);
    // Integer RAW sample, black, and white are deliberately in the same sensor-code
    // domain. This is the permanent V2.30 purple-frame regression contract.
    float signal = max(code - black, 0.0) / max(whiteLevelCode - black, 0.000001);
    signal = clamp(signal * shadingGainAt(p), 0.0, 1.0);

    // Preserve the normalized Bayer signal through the proven RGBA8 full-resolution
    // carrier without reducing it to 8-bit. R/G store one exact 16-bit fixed-point
    // value; B/A are unused/opaque. This avoids a new half-float color-buffer
    // requirement on GLES3 devices while caching expensive lens-shading work once.
    float quantized = floor(signal * 65535.0 + 0.5);
    float highByte = floor(quantized / 256.0);
    float lowByte = quantized - highByte * 256.0;
    outColor = vec4(highByte / 255.0, lowByte / 255.0, 0.0, 1.0);
}
