#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D yTex;
uniform sampler2D uTex;
uniform sampler2D vTex;
void main() {
    float yy = (texture(yTex, vUv).r * 255.0 - 16.0) / 219.0;
    float uu = (texture(uTex, vUv).r * 255.0 - 128.0) / 224.0;
    float vv = (texture(vTex, vUv).r * 255.0 - 128.0) / 224.0;
    yy = max(0.0, yy);
    vec3 rgb = vec3(
        yy + 1.402 * vv,
        yy - 0.344136 * uu - 0.714136 * vv,
        yy + 1.772 * uu
    );
    outColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
