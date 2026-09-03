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

float fetchProvenance(ivec2 globalPos) {
    return fetchFusionState(globalPos).g;
}

float fetchTrust(ivec2 globalPos) {
    // Proven Iris semantic encoding: 0=NORMAL_MEASURED,
    // 1=CENSORED_UNKNOWN_CHROMA, 2=SHORT_VALIDATED. Reconstruction gets a
    // binary physical-color admission mask. CENSORED may carry luminance in R,
    // but it contributes exactly zero to directional/opponent-color authority.
    float provenance = fetchProvenance(globalPos);
    return abs(provenance - 1.0) < 0.25 ? 0.0 : 1.0;
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

    // Preserve the proven high-order edge estimator when trust is complete, but
    // approach it continuously. V1.5.3 switched abruptly at trust=0.95 and at
    // support=0.30, which turned tiny provenance changes into visible blocks.
    float cL2 = fetchBalanced(pl2);
    float cR2 = fetchBalanced(pr2);
    float cU2 = fetchBalanced(pu2);
    float cD2 = fetchBalanced(pd2);
    float gradH = abs(gl - gr) + abs(2.0 * center - cL2 - cR2);
    float gradV = abs(gu - gd) + abs(2.0 * center - cU2 - cD2);
    float highGh = 0.5 * (gl + gr);
    float highGv = 0.5 * (gu + gd);
    float highOrderGreen;
    if (gradH < gradV * 0.75) highOrderGreen = highGh;
    else if (gradV < gradH * 0.75) highOrderGreen = highGv;
    else {
        float highWh = 1.0 / max(0.0001, gradH);
        float highWv = 1.0 / max(0.0001, gradV);
        highOrderGreen = (highGh * highWh + highGv * highWv)
            / max(0.0001, highWh + highWv);
    }

    // Proven semantic sites contribute; censored sites contribute zero. The
    // reconstruction family still varies continuously with geometric support, but
    // semantic meaning itself is never interpolated or re-guessed downstream.
    float supportH = tl + tr;
    float supportV = tu + td;
    float gh = (gl * tl + gr * tr) / max(0.0001, supportH);
    float gv = (gu * tu + gd * td) / max(0.0001, supportV);
    float hAvail = smoothstep(0.02, 0.60, supportH);
    float vAvail = smoothstep(0.02, 0.60, supportV);
    float wh = hAvail * supportH / max(0.002, abs(gl - gr) + 0.002);
    float wv = vAvail * supportV / max(0.002, abs(gu - gd) + 0.002);
    float directionalWeight = wh + wv;
    float directionalGreen = (gh * wh + gv * wv)
        / max(0.0001, directionalWeight);
    float localMean = 0.25 * (gl + gr + gu + gd);
    float fallbackGreen = mix(
        localMean, directionalGreen,
        smoothstep(0.02, 0.20, directionalWeight));
    return mix(
        fallbackGreen, highOrderGreen,
        smoothstep(0.70, 0.95, highOrderTrust));
}

float trustedOpponentPair(
        ivec2 p0, ivec2 p1, float gCenter) {
    float t0 = fetchTrust(p0);
    float t1 = fetchTrust(p1);
    float d0 = fetchBalanced(p0) - greenAt(p0);
    float d1 = fetchBalanced(p1) - greenAt(p1);
    float support = t0 + t1;
    float residual = (d0 * t0 + d1 * t1) / max(0.0001, support);
    float trustStrength = smoothstep(0.30, 1.70, support)
        * smoothstep(0.05, 0.85, min(t0, t1));
    return gCenter + residual * trustStrength;
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
    float support = t0 + t1 + t2 + t3;
    float residual = (d0 * t0 + d1 * t1 + d2 * t2 + d3 * t3)
        / max(0.0001, support);
    float meanTrust = 0.25 * support;
    float trustStrength = smoothstep(0.60, 3.40, support)
        * smoothstep(0.08, 0.80, meanTrust);
    return max(0.0, gCenter + residual * trustStrength);
}

ivec2 quadOrigin(ivec2 p) {
    return p - ivec2(p.x & 1, p.y & 1);
}

float quadMinPhysicalTrust(ivec2 origin) {
    float minPhysicalTrust = 1.0;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            minPhysicalTrust = min(minPhysicalTrust,
                clamp(fetchFusionState(origin + ivec2(x, y)).g, 0.0, 1.0));
        }
    }
    return minPhysicalTrust;
}

float quadColorRisk(ivec2 origin) {
    float minPhysicalTrust = quadMinPhysicalTrust(origin);
    float maxPhysicalClipRisk = 0.0;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec4 state = fetchFusionState(origin + ivec2(x, y));
            maxPhysicalClipRisk = max(maxPhysicalClipRisk, clamp(state.b, 0.0, 1.0));
        }
    }
    // Color is modified only when physical clipping exists AND at least one CFA
    // phase is semantically CENSORED_UNKNOWN_CHROMA. NORMAL_MEASURED and
    // SHORT_VALIDATED are equally real color evidence downstream.
    return maxPhysicalClipRisk * (1.0 - minPhysicalTrust);
}

float coherentHighlightColorRisk(ivec2 p) {
    ivec2 q = quadOrigin(p);
    float currentTrust = quadMinPhysicalTrust(q);
    float risk = quadColorRisk(q);
    // Neighbor risk remains bounded to one Bayer-cell neighborhood for a genuinely
    // incomplete boundary, but a fully measured/validated current Bayer quad is
    // sovereign. Because trust is semantic binary now, censored neighbors cannot
    // steer green direction or opponent chroma before this final quad decision. RGB
    // itself is never blurred and no neighboring hue is borrowed.
    float neighborPermission = 1.0 - smoothstep(0.80, 0.98, currentTrust);
    for (int qy = -1; qy <= 1; ++qy) {
        for (int qx = -1; qx <= 1; ++qx) {
            if (qx == 0 && qy == 0) continue;
            risk = max(risk, 0.85 * neighborPermission
                * quadColorRisk(q + ivec2(2 * qx, 2 * qy)));
        }
    }
    return smoothstep(0.035, 0.45, clamp(risk, 0.0, 1.0));
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
    // Fully physically proven color is unchanged. If clipping left opponent
    // chroma unproven, reduce only R-G/B-G. V1.5.3 used max(g, median(rgb)) as the
    // neutral target; that could raise brightness and paint white strokes/blocks.
    // The neutral fallback is now exactly the reconstructed green/luminance anchor:
    // uncertainty may remove chroma, but it may never create luminance.
    vec3 balancedCameraRgb = vec3(r, g, b);
    float colorRisk = coherentHighlightColorRisk(p);
    vec3 neutralCameraRgb = vec3(g);
    balancedCameraRgb = mix(balancedCameraRgb, neutralCameraRgb, colorRisk);

    vec3 linearSrgb = vec3(
        dot(colorRow0, balancedCameraRgb),
        dot(colorRow1, balancedCameraRgb),
        dot(colorRow2, balancedCameraRgb));
    outColor = vec4(max(linearSrgb, vec3(0.0)), 1.0);
}
