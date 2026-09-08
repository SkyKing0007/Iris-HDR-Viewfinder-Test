#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform highp sampler2D rawTex;
uniform highp sampler2D shadingTex;
uniform vec4 blackPatternCode;
uniform float whiteLevelCode;
uniform int cfaArrangement;
uniform vec4 wbGains;
uniform vec3 colorRow0;
uniform vec3 colorRow1;
uniform vec3 colorRow2;

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

float gainAt(ivec2 p) {
    int color = colorAt(p);
    if (color == 0) return wbGains.x;
    if (color == 2) return wbGains.w;
    return (p.y & 1) == 0 ? wbGains.y : wbGains.z;
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

ivec2 clampPixel(ivec2 p) {
    ivec2 size = textureSize(rawTex, 0);
    return clamp(p, ivec2(0), size - ivec2(1));
}

float balancedSample(ivec2 p) {
    ivec2 q = clampPixel(p);
    float code = texelFetch(rawTex, q, 0).r;
    float black = blackAt(q);
    float signal = max(code - black, 0.0) / max(whiteLevelCode - black, 0.000001);
    return clamp(signal * shadingGainAt(q), 0.0, 1.0) * gainAt(q);
}

float avg2(float a, float b) {
    return 0.5 * (a + b);
}

float avg4(float a, float b, float c, float d) {
    return 0.25 * (a + b + c + d);
}

vec3 demosaic(ivec2 p) {
    int color = colorAt(p);
    float center = balancedSample(p);
    if (color == 0) {
        float g = avg4(
            balancedSample(p + ivec2(-1, 0)), balancedSample(p + ivec2(1, 0)),
            balancedSample(p + ivec2(0, -1)), balancedSample(p + ivec2(0, 1)));
        float b = avg4(
            balancedSample(p + ivec2(-1, -1)), balancedSample(p + ivec2(1, -1)),
            balancedSample(p + ivec2(-1, 1)), balancedSample(p + ivec2(1, 1)));
        return vec3(center, g, b);
    }
    if (color == 2) {
        float g = avg4(
            balancedSample(p + ivec2(-1, 0)), balancedSample(p + ivec2(1, 0)),
            balancedSample(p + ivec2(0, -1)), balancedSample(p + ivec2(0, 1)));
        float r = avg4(
            balancedSample(p + ivec2(-1, -1)), balancedSample(p + ivec2(1, -1)),
            balancedSample(p + ivec2(-1, 1)), balancedSample(p + ivec2(1, 1)));
        return vec3(r, g, center);
    }

    bool redHorizontal = colorAt(p + ivec2(1, 0)) == 0
        || colorAt(p + ivec2(-1, 0)) == 0;
    float r = redHorizontal
        ? avg2(balancedSample(p + ivec2(-1, 0)), balancedSample(p + ivec2(1, 0)))
        : avg2(balancedSample(p + ivec2(0, -1)), balancedSample(p + ivec2(0, 1)));
    float b = redHorizontal
        ? avg2(balancedSample(p + ivec2(0, -1)), balancedSample(p + ivec2(0, 1)))
        : avg2(balancedSample(p + ivec2(-1, 0)), balancedSample(p + ivec2(1, 0)));
    return vec3(r, center, b);
}

float linearToSrgbChannel(float value) {
    float x = max(value, 0.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 sensorRgb = demosaic(p);
    vec3 linearSrgb = vec3(
        dot(colorRow0, sensorRgb),
        dot(colorRow1, sensorRgb),
        dot(colorRow2, sensorRgb));
    linearSrgb = clamp(linearSrgb, vec3(0.0), vec3(1.0));
    vec3 encoded = vec3(
        linearToSrgbChannel(linearSrgb.r),
        linearToSrgbChannel(linearSrgb.g),
        linearToSrgbChannel(linearSrgb.b));
    outColor = vec4(clamp(encoded, vec3(0.0), vec3(1.0)), 1.0);
}
