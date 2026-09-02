#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 outColor;
uniform sampler2D shortTex;
uniform sampler2D longTex;
uniform float exposureRatio;
uniform vec4 shortPhotoScaleA;
uniform float shortPhotoScaleB;

float max3(vec3 value) { return max(value.r, max(value.g, value.b)); }
float min3(vec3 value) { return min(value.r, min(value.g, value.b)); }
float luma3(vec3 value) { return dot(value, vec3(0.2126, 0.7152, 0.0722)); }

float srgbToLinearChannel(float value) {
    return value <= 0.04045
        ? value / 12.92
        : pow((value + 0.055) / 1.055, 2.4);
}

vec3 srgbToLinear(vec3 value) {
    return vec3(
        srgbToLinearChannel(value.r),
        srgbToLinearChannel(value.g),
        srgbToLinearChannel(value.b));
}

float shortPhotoScaleForLuma(float normalizedLuma) {
    const float k0 = 0.020;
    const float k1 = 0.060;
    const float k2 = 0.150;
    const float k3 = 0.350;
    const float k4 = 0.700;
    float value = max(normalizedLuma, 0.00001);
    if (value <= k0) return shortPhotoScaleA.x;
    if (value <= k1) {
        float t = clamp(log(value / k0) / log(k1 / k0), 0.0, 1.0);
        return mix(shortPhotoScaleA.x, shortPhotoScaleA.y, t);
    }
    if (value <= k2) {
        float t = clamp(log(value / k1) / log(k2 / k1), 0.0, 1.0);
        return mix(shortPhotoScaleA.y, shortPhotoScaleA.z, t);
    }
    if (value <= k3) {
        float t = clamp(log(value / k2) / log(k3 / k2), 0.0, 1.0);
        return mix(shortPhotoScaleA.z, shortPhotoScaleA.w, t);
    }
    if (value <= k4) {
        float t = clamp(log(value / k3) / log(k4 / k3), 0.0, 1.0);
        return mix(shortPhotoScaleA.w, shortPhotoScaleB, t);
    }
    return shortPhotoScaleB;
}

vec3 calibratedShortScene(vec3 shortRgb) {
    vec3 normalizedScene = srgbToLinear(shortRgb) * exposureRatio;
    return normalizedScene * shortPhotoScaleForLuma(luma3(normalizedScene));
}

void main() {
    float logGainSum = 0.0;
    float logGainSqSum = 0.0;
    float chromaDeltaSum = 0.0;
    float weightSum = 0.0;

    // 16x64 pair-rate field: high vertical resolution catches moving horizontal
    // rolling/PWM bands; 16 horizontal regions prevent one lamp/TV from steering
    // unrelated image content. Each cell uses eight local same-row overlap probes.
    const float cellWidth = 1.0 / 16.0;
    float cellLeft = floor(vUv.x * 16.0) * cellWidth;
    for (int i = 0; i < 8; ++i) {
        float x = cellLeft + (float(i) + 0.5) * (cellWidth / 8.0);
        vec2 uv = vec2(clamp(x, 0.0, 1.0), clamp(vUv.y, 0.0, 1.0));
        vec3 longRgb = texture(longTex, uv).rgb;
        vec3 shortRgb = texture(shortTex, uv).rgb;
        float longValid = smoothstep(0.025, 0.070, min3(longRgb))
            * (1.0 - smoothstep(0.86, 0.93, max3(longRgb)));
        float shortValid = smoothstep(0.008, 0.020, min3(shortRgb))
            * (1.0 - smoothstep(0.86, 0.93, max3(shortRgb)));
        float weight = longValid * shortValid;
        if (weight <= 0.02) continue;

        vec3 longScene = srgbToLinear(longRgb);
        vec3 shortScene = calibratedShortScene(shortRgb);
        float longLuma = luma3(longScene);
        float shortLuma = luma3(shortScene);
        if (longLuma <= 0.002 || shortLuma <= 0.002) continue;

        float gainEv = clamp(log2(longLuma / shortLuma), -1.5, 1.5);
        vec3 longChromaticity = longScene / max(longLuma, 0.0005);
        vec3 shortChromaticity = shortScene / max(shortLuma, 0.0005);
        float chromaDelta = max3(abs(longChromaticity - shortChromaticity));
        logGainSum += gainEv * weight;
        logGainSqSum += gainEv * gainEv * weight;
        chromaDeltaSum += chromaDelta * weight;
        weightSum += weight;
    }

    if (weightSum < 1.20) {
        outColor = vec4(0.5, 0.0, 0.0, 0.0);
        return;
    }

    float meanEv = logGainSum / weightSum;
    float varianceEv = max(0.0, logGainSqSum / weightSum - meanEv * meanEv);
    float sigmaEv = sqrt(varianceEv);
    float evidence = smoothstep(1.20, 4.00, weightSum);
    float consistency = 1.0 - smoothstep(0.12, 0.38, sigmaEv);
    float amplitudeTrust = 1.0 - smoothstep(1.35, 1.50, abs(meanEv));
    float lumaTrust = evidence * consistency * amplitudeTrust;
    float chromaMean = chromaDeltaSum / weightSum;
    float chromaTrust = lumaTrust * (1.0 - smoothstep(0.045, 0.140, chromaMean));

    float correctedEv = clamp(meanEv, -1.5, 1.5) * lumaTrust;
    float encodedGain = correctedEv / 3.0 + 0.5;
    outColor = vec4(
        clamp(encodedGain, 0.0, 1.0),
        clamp(lumaTrust, 0.0, 1.0),
        clamp(chromaTrust, 0.0, 1.0),
        clamp(evidence, 0.0, 1.0));
}
