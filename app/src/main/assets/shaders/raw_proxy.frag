#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D sourceTex;

const float CARRIER_MAX = 254.0 / 255.0;
const float CARRIER_BODY_END = CARRIER_MAX * 0.42;
const float CARRIER_DETAIL_END = CARRIER_MAX * 0.92;
const float CARRIER_DETAIL_TOP = 8.0;
const float CARRIER_DETAIL_STOPS = 3.0;
const float CARRIER_TAIL_TOP = 32.0;
const float CARRIER_TAIL_STOPS = 2.0;

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

float linearToSrgbChannel(float value) {
    float x = max(value, 0.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

void main() {
    ivec2 size = textureSize(sourceTex, 0);
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - ivec2(1));
    vec3 encoded = texelFetch(sourceTex, p, 0).rgb;
    vec3 scene = vec3(
        decodeSceneChannel(encoded.r),
        decodeSceneChannel(encoded.g),
        decodeSceneChannel(encoded.b));
    // RGBA8 proxy remains registration evidence only.  Production fusion consumes
    // the higher-highlight-precision extended-linear carrier directly.
    vec3 proxy = vec3(
        linearToSrgbChannel(clamp(scene.r, 0.0, 1.0)),
        linearToSrgbChannel(clamp(scene.g, 0.0, 1.0)),
        linearToSrgbChannel(clamp(scene.b, 0.0, 1.0)));
    outColor = vec4(proxy, 1.0);
}
