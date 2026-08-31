#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform samplerExternalOES cameraTex;
uniform mat4 texTransform;

void main() {
    vec2 cameraUv = (texTransform * vec4(vUv, 0.0, 1.0)).xy;
    outColor = texture(cameraTex, cameraUv);
}
