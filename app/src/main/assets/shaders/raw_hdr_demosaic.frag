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
uniform ivec4 activeArray;
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

// Mirror only beyond the real photo boundary. The reflection differs from the
// requested coordinate by an even number of samples, so Bayer phase is preserved.
// Tile boundaries are not mirrored: the Java renderer already supplies a real CFA
// halo around every interior tile.
int mirrorParityCoord(int value, int low, int highExclusive) {
    if (value < low) return low + (low - value);
    if (value >= highExclusive) {
        int last = highExclusive - 1;
        return last - (value - last);
    }
    return value;
}

ivec2 mirrorParityPoint(ivec2 p) {
    return ivec2(
        mirrorParityCoord(p.x, activeArray.x, activeArray.z),
        mirrorParityCoord(p.y, activeArray.y, activeArray.w));
}

vec4 fetchFusionState(ivec2 globalPos) {
    ivec2 mirrored = mirrorParityPoint(globalPos);
    ivec2 local = mirrored - fusedGlobalOrigin;
    // Interior tile requests must stay inside the supplied halo. The clamp is a
    // final memory-safety bound only; true photo edges were already parity-mirrored.
    local = clamp(local, ivec2(0), fusedTextureSize - ivec2(1));
    return texelFetch(fusedCfaTex, local, 0);
}

float fetchCfa(ivec2 globalPos) {
    return fetchFusionState(globalPos).r;
}

float fetchTrust(ivec2 globalPos) {
    return clamp(fetchFusionState(globalPos).g, 0.0, 1.0);
}

float fetchBalanced(ivec2 globalPos) {
    int channel = channelAt(globalPos);
    return fetchCfa(globalPos) * channelGain(channel);
}

float greenAt(ivec2 p) {
    int channel = channelAt(p);
    if (channel == 1 || channel == 2) return fetchBalanced(p);
    float center = fetchBalanced(p);
    ivec2 pl = p + ivec2(-1, 0);
    ivec2 pr = p + ivec2(1, 0);
    ivec2 pu = p + ivec2(0, -1);
    ivec2 pd = p + ivec2(0, 1);
    float gl = fetchBalanced(pl);
    float gr = fetchBalanced(pr);
    float gu = fetchBalanced(pu);
    float gd = fetchBalanced(pd);
    float tl = fetchTrust(pl);
    float tr = fetchTrust(pr);
    float tu = fetchTrust(pu);
    float td = fetchTrust(pd);
    ivec2 pl2 = p + ivec2(-2, 0);
    ivec2 pr2 = p + ivec2(2, 0);
    ivec2 pu2 = p + ivec2(0, -2);
    ivec2 pd2 = p + ivec2(0, 2);
    float highOrderTrust = min(
        min(min(tl, tr), min(tu, td)),
        min(min(fetchTrust(pl2), fetchTrust(pr2)),
            min(fetchTrust(pu2), fetchTrust(pd2))));

    // Exact V1.5.1 detail path remains byte-mathematically equivalent wherever
    // every contributing CFA site has physical authority.
    if (highOrderTrust >= 0.95) {
        float cL2 = fetchBalanced(pl2);
        float cR2 = fetchBalanced(pr2);
        float cU2 = fetchBalanced(pu2);
        float cD2 = fetchBalanced(pd2);
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

    // At a rejected/partly unproven highlight boundary, an untrusted green sample
    // may contribute brightness but may not steer edge direction by itself.
    float supportH = tl + tr;
    float supportV = tu + td;
    float gh = (gl * tl + gr * tr) / max(0.0001, supportH);
    float gv = (gu * tu + gd * td) / max(0.0001, supportV);
    if (supportH < 0.30 && supportV < 0.30) {
        return 0.25 * (gl + gr + gu + gd);
    }
    if (supportH < 0.30) return gv;
    if (supportV < 0.30) return gh;
    float wh = supportH / max(0.002, abs(gl - gr) + 0.002);
    float wv = supportV / max(0.002, abs(gu - gd) + 0.002);
    return (gh * wh + gv * wv) / max(0.0001, wh + wv);
}

float trustedOpponentPair(
        ivec2 p0, ivec2 p1, float gCenter) {
    float t0 = fetchTrust(p0);
    float t1 = fetchTrust(p1);
    float d0 = fetchBalanced(p0) - greenAt(p0);
    float d1 = fetchBalanced(p1) - greenAt(p1);
    if (min(t0, t1) >= 0.95) return gCenter + 0.5 * (d0 + d1);
    float support = t0 + t1;
    if (support < 1.10) return gCenter;
    return gCenter + (d0 * t0 + d1 * t1) / max(0.0001, support);
}

float colorAt(int targetChannel, ivec2 p, float gCenter) {
    int centerChannel = channelAt(p);
    if (centerChannel == targetChannel) {
        float trust = fetchTrust(p);
        return mix(gCenter, fetchBalanced(p), smoothstep(0.45, 0.90, trust));
    }

    bool centerGreen = centerChannel == 1 || centerChannel == 2;
    if (centerGreen) {
        ivec2 left = p + ivec2(-1, 0);
        ivec2 right = p + ivec2(1, 0);
        if (channelAt(left) == targetChannel && channelAt(right) == targetChannel) {
            return max(0.0, trustedOpponentPair(left, right, gCenter));
        }
        ivec2 up = p + ivec2(0, -1);
        ivec2 down = p + ivec2(0, 1);
        return max(0.0, trustedOpponentPair(up, down, gCenter));
    }

    ivec2 q0 = p + ivec2(-1, -1);
    ivec2 q1 = p + ivec2(1, -1);
    ivec2 q2 = p + ivec2(-1, 1);
    ivec2 q3 = p + ivec2(1, 1);
    float t0 = fetchTrust(q0);
    float t1 = fetchTrust(q1);
    float t2 = fetchTrust(q2);
    float t3 = fetchTrust(q3);
    float d0 = fetchBalanced(q0) - greenAt(q0);
    float d1 = fetchBalanced(q1) - greenAt(q1);
    float d2 = fetchBalanced(q2) - greenAt(q2);
    float d3 = fetchBalanced(q3) - greenAt(q3);
    if (min(min(t0, t1), min(t2, t3)) >= 0.95) {
        return max(0.0, gCenter + 0.25 * (d0 + d1 + d2 + d3));
    }
    float support = t0 + t1 + t2 + t3;
    if (support < 1.50) return gCenter;
    float residual = (d0 * t0 + d1 * t1 + d2 * t2 + d3 * t3)
        / max(0.0001, support);
    return max(0.0, gCenter + residual);
}

ivec2 quadOrigin(ivec2 p) {
    return p - ivec2(p.x & 1, p.y & 1);
}

float quadColorRisk(ivec2 origin) {
    float minPhysicalTrust = 1.0;
    float maxPhysicalClipRisk = 0.0;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec4 state = fetchFusionState(origin + ivec2(x, y));
            minPhysicalTrust = min(minPhysicalTrust, clamp(state.g, 0.0, 1.0));
            maxPhysicalClipRisk = max(maxPhysicalClipRisk, clamp(state.b, 0.0, 1.0));
        }
    }
    // Bright color is modified only when physical clipping exists AND at least
    // one CFA phase lacks a validated LONG-or-SHORT measurement.
    return maxPhysicalClipRisk * (1.0 - minPhysicalTrust);
}

float coherentHighlightColorRisk(ivec2 p) {
    ivec2 q = quadOrigin(p);
    float risk = quadColorRisk(q);
    // Expand only the trust decision by one Bayer quad around a strong clipping
    // boundary. RGB itself is never blurred and no neighboring hue is borrowed.
    for (int qy = -1; qy <= 1; ++qy) {
        for (int qx = -1; qx <= 1; ++qx) {
            if (qx == 0 && qy == 0) continue;
            risk = max(risk, 0.85 * quadColorRisk(q + ivec2(2 * qx, 2 * qy)));
        }
    }
    return smoothstep(0.035, 0.45, clamp(risk, 0.0, 1.0));
}

float median3(vec3 value) {
    return value.r + value.g + value.b
        - min(value.r, min(value.g, value.b))
        - max(value.r, max(value.g, value.b));
}

void main() {
    ivec2 outputLocal = ivec2(gl_FragCoord.xy);
    ivec2 p = outputGlobalOrigin + outputLocal;
    float g = greenAt(p);
    float r = colorAt(0, p, g);
    float b = colorAt(3, p, g);

    // The demosaic above is in white-balanced camera RGB. V1.5.2 consumes
    // sensor-domain trust during interpolation, then makes one Bayer-quad color
    // decision AFTER LSC/WB balancing and BEFORE the Camera2 color matrix.
    //
    // Fully physically proven color is unchanged. If clipping left a phase
    // unproven, preserve the majority/green-derived brightness but progressively
    // drive only opponent chroma (R-G and B-G) toward zero. This is the proven
    // Iris fail-closed contract and is intentionally NOT a brightness-triggered
    // "anything above 70% becomes white" repair.
    vec3 balancedCameraRgb = vec3(r, g, b);
    float colorRisk = coherentHighlightColorRisk(p);
    float neutralLevel = max(g, median3(balancedCameraRgb));
    vec3 neutralCameraRgb = vec3(neutralLevel);
    balancedCameraRgb = mix(balancedCameraRgb, neutralCameraRgb, colorRisk);

    vec3 linearSrgb = vec3(
        dot(colorRow0, balancedCameraRgb),
        dot(colorRow1, balancedCameraRgb),
        dot(colorRow2, balancedCameraRgb));
    outColor = vec4(max(linearSrgb, vec3(0.0)), 1.0);
}
