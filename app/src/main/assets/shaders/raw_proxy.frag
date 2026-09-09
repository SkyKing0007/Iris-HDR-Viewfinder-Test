#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D sourceTex;

const float SIGNAL_COMPAND_K = 1.0;
const float CARRIER_MAX = 254.0 / 255.0;

float expandPositive(float encoded, float k) {
    float e = min(max(encoded, 0.0), CARRIER_MAX);
    float e2 = e * e;
    return k * e2 / max(1.0 - e2, 0.0000001);
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
        expandPositive(encoded.r, SIGNAL_COMPAND_K),
        expandPositive(encoded.g, SIGNAL_COMPAND_K),
        expandPositive(encoded.b, SIGNAL_COMPAND_K));
    // This RGBA8 image is diagnostic/registration evidence only. The real saved HDR
    // source remains the extended-linear carrier above and is never replaced by this.
    vec3 proxy = vec3(
        linearToSrgbChannel(clamp(scene.r, 0.0, 1.0)),
        linearToSrgbChannel(clamp(scene.g, 0.0, 1.0)),
        linearToSrgbChannel(clamp(scene.b, 0.0, 1.0)));
    outColor = vec4(proxy, 1.0);
}
