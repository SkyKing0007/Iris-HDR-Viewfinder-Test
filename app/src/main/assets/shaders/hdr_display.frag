#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D normalTex;
uniform sampler2D shortTex;
uniform sampler2D longTex;
uniform int mode;
uniform int rotationQuarterTurns;
uniform int haveNormal;
uniform int haveShort;
uniform int haveLong;
uniform float exposureRatio;

vec2 rotateUv(vec2 uv) {
    if (rotationQuarterTurns == 1) return vec2(uv.y, 1.0 - uv.x);
    if (rotationQuarterTurns == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (rotationQuarterTurns == 3) return vec2(1.0 - uv.y, uv.x);
    return uv;
}

vec3 fallbackColor(vec2 uv) {
    if (haveNormal == 1) return texture(normalTex, uv).rgb;
    if (haveLong == 1) return texture(longTex, uv).rgb;
    if (haveShort == 1) return texture(shortTex, uv).rgb;
    return vec3(0.0);
}

void main() {
    vec2 uv = rotateUv(vUv);
    if (mode == 0) {
        outColor = vec4(fallbackColor(uv), 1.0);
        return;
    }
    if (mode == 1) {
        if (haveShort == 0 || haveLong == 0) {
            outColor = vec4(fallbackColor(uv), 1.0);
            return;
        }
        vec2 halfUv = uv;
        if (vUv.x < 0.5) {
            halfUv.x = uv.x * 2.0;
            outColor = vec4(texture(shortTex, halfUv).rgb, 1.0);
        } else {
            halfUv.x = (uv.x - 0.5) * 2.0;
            outColor = vec4(texture(longTex, halfUv).rgb, 1.0);
        }
        return;
    }
    if (haveShort == 0 || haveLong == 0) {
        outColor = vec4(fallbackColor(uv), 1.0);
        return;
    }

    vec3 shortRgb = texture(shortTex, uv).rgb;
    vec3 longRgb = texture(longTex, uv).rgb;
    vec3 shortLinear = pow(max(shortRgb, vec3(0.0)), vec3(2.2)) * exposureRatio;
    vec3 longLinear = pow(max(longRgb, vec3(0.0)), vec3(2.2));
    float clipSignal = max(longRgb.r, max(longRgb.g, longRgb.b));
    float shortWeight = smoothstep(0.62, 0.93, clipSignal);
    vec3 mergedLinear = mix(longLinear, shortLinear, shortWeight);
    vec3 toneMapped = (1.15 * mergedLinear) / (vec3(1.0) + 0.15 * mergedLinear);
    vec3 rgb = pow(clamp(toneMapped, 0.0, 1.0), vec3(1.0 / 2.2));
    outColor = vec4(rgb, 1.0);
}
