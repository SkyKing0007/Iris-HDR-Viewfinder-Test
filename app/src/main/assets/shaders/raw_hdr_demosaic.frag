#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D fusedCfaTex;
uniform int cfaPattern;
uniform ivec2 fusedTextureSize;
uniform ivec2 fusedGlobalOrigin;
uniform ivec2 outputGlobalOrigin;
uniform vec4 whiteBalanceGains;
uniform vec3 colorRow0;
uniform vec3 colorRow1;
uniform vec3 colorRow2;

int channelAt(ivec2 p) {
    int x = p.x & 1;
    int y = p.y & 1;
    if (cfaPattern == 0) {
        if (y == 0) return x == 0 ? 0 : 1;
        return x == 0 ? 2 : 3;
    }
    if (cfaPattern == 1) {
        if (y == 0) return x == 0 ? 1 : 0;
        return x == 0 ? 3 : 2;
    }
    if (cfaPattern == 2) {
        if (y == 0) return x == 0 ? 1 : 3;
        return x == 0 ? 0 : 2;
    }
    if (y == 0) return x == 0 ? 3 : 1;
    return x == 0 ? 2 : 0;
}

float channelGain(int channel) {
    if (channel == 0) return whiteBalanceGains.r;
    if (channel == 1) return whiteBalanceGains.g;
    if (channel == 2) return whiteBalanceGains.b;
    return whiteBalanceGains.a;
}

float fetchCfa(ivec2 globalPos) {
    ivec2 local = globalPos - fusedGlobalOrigin;
    local = clamp(local, ivec2(0), fusedTextureSize - ivec2(1));
    return texelFetch(fusedCfaTex, local, 0).r;
}

float fetchBalanced(ivec2 globalPos) {
    int channel = channelAt(globalPos);
    return fetchCfa(globalPos) * channelGain(channel);
}

float greenAt(ivec2 p) {
    int channel = channelAt(p);
    if (channel == 1 || channel == 2) return fetchBalanced(p);
    float center = fetchBalanced(p);
    float gl = fetchBalanced(p + ivec2(-1, 0));
    float gr = fetchBalanced(p + ivec2(1, 0));
    float gu = fetchBalanced(p + ivec2(0, -1));
    float gd = fetchBalanced(p + ivec2(0, 1));
    float cL2 = fetchBalanced(p + ivec2(-2, 0));
    float cR2 = fetchBalanced(p + ivec2(2, 0));
    float cU2 = fetchBalanced(p + ivec2(0, -2));
    float cD2 = fetchBalanced(p + ivec2(0, 2));
    float gradH = abs(gl - gr) + abs(2.0 * center - cL2 - cR2);
    float gradV = abs(gu - gd) + abs(2.0 * center - cU2 - cD2);
    float gh = 0.5 * (gl + gr);
    float gv = 0.5 * (gu + gd);
    if (gradH < gradV * 0.75) return gh;
    if (gradV < gradH * 0.75) return gv;
    float wh = 1.0 / max(0.0001, gradH);
    float wv = 1.0 / max(0.0001, gradV);
    return (gh * wh + gv * wv) / (wh + wv);
}

float colorAt(int targetChannel, ivec2 p, float gCenter) {
    int centerChannel = channelAt(p);
    if (centerChannel == targetChannel) return fetchBalanced(p);

    bool centerGreen = centerChannel == 1 || centerChannel == 2;
    if (centerGreen) {
        ivec2 left = p + ivec2(-1, 0);
        ivec2 right = p + ivec2(1, 0);
        if (channelAt(left) == targetChannel && channelAt(right) == targetChannel) {
            float d0 = fetchBalanced(left) - greenAt(left);
            float d1 = fetchBalanced(right) - greenAt(right);
            return max(0.0, gCenter + 0.5 * (d0 + d1));
        }
        ivec2 up = p + ivec2(0, -1);
        ivec2 down = p + ivec2(0, 1);
        float d0 = fetchBalanced(up) - greenAt(up);
        float d1 = fetchBalanced(down) - greenAt(down);
        return max(0.0, gCenter + 0.5 * (d0 + d1));
    }

    ivec2 d0p = p + ivec2(-1, -1);
    ivec2 d1p = p + ivec2(1, -1);
    ivec2 d2p = p + ivec2(-1, 1);
    ivec2 d3p = p + ivec2(1, 1);
    float d0 = fetchBalanced(d0p) - greenAt(d0p);
    float d1 = fetchBalanced(d1p) - greenAt(d1p);
    float d2 = fetchBalanced(d2p) - greenAt(d2p);
    float d3 = fetchBalanced(d3p) - greenAt(d3p);
    return max(0.0, gCenter + 0.25 * (d0 + d1 + d2 + d3));
}

void main() {
    ivec2 outputLocal = ivec2(gl_FragCoord.xy);
    ivec2 p = outputGlobalOrigin + outputLocal;
    float g = greenAt(p);
    float r = colorAt(0, p, g);
    float b = colorAt(3, p, g);
    vec3 sensorRgb = vec3(r, g, b);
    vec3 linearSrgb = vec3(
        dot(colorRow0, sensorRgb),
        dot(colorRow1, sensorRgb),
        dot(colorRow2, sensorRgb));
    outColor = vec4(max(linearSrgb, vec3(0.0)), 1.0);
}
