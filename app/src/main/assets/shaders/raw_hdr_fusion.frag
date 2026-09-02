#version 300 es
precision highp float;
precision highp int;
layout(location=0) out float outCfa;
uniform highp usampler2D shortRawTex;
uniform highp usampler2D longRawTex;
uniform sampler2D shortShadingTex;
uniform sampler2D longShadingTex;
uniform sampler2D flowTex;
uniform sampler2D photometricTex;
uniform int cfaPattern;
uniform int rawTileOriginY;
uniform int fusedGlobalStartY;
uniform ivec2 rawSize;
uniform ivec4 activeArray;
uniform ivec4 sensorActiveArray;
uniform vec4 shortBlackPhase;
uniform vec4 longBlackPhase;
uniform float shortWhiteLevel;
uniform float longWhiteLevel;
uniform float exposureRatio;
uniform float shortResidualScale;

int channelAt(ivec2 p) {
    int x = p.x & 1;
    int y = p.y & 1;
    if (cfaPattern == 0) { // RGGB
        if (y == 0) return x == 0 ? 0 : 1;
        return x == 0 ? 2 : 3;
    }
    if (cfaPattern == 1) { // GRBG
        if (y == 0) return x == 0 ? 1 : 0;
        return x == 0 ? 3 : 2;
    }
    if (cfaPattern == 2) { // GBRG
        if (y == 0) return x == 0 ? 1 : 3;
        return x == 0 ? 0 : 2;
    }
    // BGGR
    if (y == 0) return x == 0 ? 3 : 1;
    return x == 0 ? 2 : 0;
}

ivec2 phaseOffset(int channel) {
    if (cfaPattern == 0) {
        if (channel == 0) return ivec2(0, 0);
        if (channel == 1) return ivec2(1, 0);
        if (channel == 2) return ivec2(0, 1);
        return ivec2(1, 1);
    }
    if (cfaPattern == 1) {
        if (channel == 0) return ivec2(1, 0);
        if (channel == 1) return ivec2(0, 0);
        if (channel == 2) return ivec2(1, 1);
        return ivec2(0, 1);
    }
    if (cfaPattern == 2) {
        if (channel == 0) return ivec2(0, 1);
        if (channel == 1) return ivec2(0, 0);
        if (channel == 2) return ivec2(1, 1);
        return ivec2(1, 0);
    }
    if (channel == 0) return ivec2(1, 1);
    if (channel == 1) return ivec2(1, 0);
    if (channel == 2) return ivec2(0, 1);
    return ivec2(0, 0);
}

float phaseValue(vec4 values, int channel) {
    if (channel == 0) return values.r;
    if (channel == 1) return values.g;
    if (channel == 2) return values.b;
    return values.a;
}

vec4 sampleMapBilinear(sampler2D mapTex, vec2 uv) {
    ivec2 size = textureSize(mapTex, 0);
    vec2 position = clamp(uv, vec2(0.0), vec2(1.0)) * vec2(max(size - ivec2(1), ivec2(1)));
    ivec2 p0 = ivec2(floor(position));
    ivec2 p1 = min(p0 + ivec2(1), size - ivec2(1));
    vec2 f = fract(position);
    vec4 a = mix(texelFetch(mapTex, ivec2(p0.x, p0.y), 0),
                 texelFetch(mapTex, ivec2(p1.x, p0.y), 0), f.x);
    vec4 b = mix(texelFetch(mapTex, ivec2(p0.x, p1.y), 0),
                 texelFetch(mapTex, ivec2(p1.x, p1.y), 0), f.x);
    return mix(a, b, f.y);
}

vec2 renderUv(vec2 sensorPos) {
    float w = max(1.0, float(activeArray.z - activeArray.x - 1));
    float h = max(1.0, float(activeArray.w - activeArray.y - 1));
    return clamp(
        vec2((sensorPos.x - float(activeArray.x)) / w,
             (sensorPos.y - float(activeArray.y)) / h),
        vec2(0.0), vec2(1.0));
}

vec2 shadingUv(vec2 sensorPos) {
    float w = max(1.0, float(sensorActiveArray.z - sensorActiveArray.x - 1));
    float h = max(1.0, float(sensorActiveArray.w - sensorActiveArray.y - 1));
    return clamp(
        vec2((sensorPos.x - float(sensorActiveArray.x)) / w,
             (sensorPos.y - float(sensorActiveArray.y)) / h),
        vec2(0.0), vec2(1.0));
}

float shadingGain(sampler2D mapTex, vec2 sensorPos, int channel) {
    vec4 gain = sampleMapBilinear(mapTex, shadingUv(sensorPos));
    return phaseValue(gain, channel);
}

uint fetchRaw(highp usampler2D tex, ivec2 globalPos) {
    ivec2 clamped = clamp(globalPos, ivec2(0), rawSize - ivec2(1));
    return texelFetch(tex, ivec2(clamped.x, clamped.y - rawTileOriginY), 0).r;
}

float normalizedRaw(uint rawValue, float black, float whiteLevel) {
    float denominator = max(1.0, whiteLevel - black);
    return clamp((float(rawValue) - black) / denominator, 0.0, 1.25);
}

vec2 rowPhotometric(float sensorY) {
    int count = textureSize(photometricTex, 0).y;
    float h = max(1.0, float(activeArray.w - activeArray.y - 1));
    float normalized = clamp((sensorY - float(activeArray.y)) / h, 0.0, 1.0);
    float position = normalized * float(max(count - 1, 1));
    int y0 = int(floor(position));
    int y1 = min(y0 + 1, count - 1);
    float f = fract(position);
    vec4 a = texelFetch(photometricTex, ivec2(0, y0), 0);
    vec4 b = texelFetch(photometricTex, ivec2(0, y1), 0);
    return vec2(mix(a.r, b.r, f), mix(a.g, b.g, f));
}

vec3 shortSampleAt(ivec2 globalPos, int channel, float rowScale) {
    uint rawValue = fetchRaw(shortRawTex, globalPos);
    float sensor = normalizedRaw(
        rawValue, phaseValue(shortBlackPhase, channel), shortWhiteLevel);
    float signal = smoothstep(0.004, 0.009, sensor);
    float headroom = 1.0 - smoothstep(0.965, 0.995, sensor);
    float valid = signal * headroom;
    float scene = sensor
        * shadingGain(shortShadingTex, vec2(globalPos), channel)
        * exposureRatio * shortResidualScale * rowScale;
    return vec3(scene, valid, sensor);
}

vec2 sampleShortSamePhase(vec2 sourcePos, int channel, float rowScale) {
    ivec2 offset = phaseOffset(channel);
    vec2 plane = (sourcePos - vec2(offset)) * 0.5;
    ivec2 base = ivec2(floor(plane));
    vec2 f = fract(plane);
    float sceneSum = 0.0;
    float weightSum = 0.0;
    float validSupport = 0.0;
    for (int j = 0; j < 2; ++j) {
        for (int i = 0; i < 2; ++i) {
            ivec2 planePos = base + ivec2(i, j);
            ivec2 rawPos = planePos * 2 + offset;
            float wx = i == 0 ? 1.0 - f.x : f.x;
            float wy = j == 0 ? 1.0 - f.y : f.y;
            float geometricWeight = wx * wy;
            vec3 sampleState = shortSampleAt(rawPos, channel, rowScale);
            float usableWeight = geometricWeight * sampleState.y;
            sceneSum += sampleState.x * usableWeight;
            weightSum += usableWeight;
            validSupport += usableWeight;
        }
    }
    float scene = weightSum > 0.0001 ? sceneSum / weightSum : 0.0;
    return vec2(scene, clamp(validSupport, 0.0, 1.0));
}

void main() {
    ivec2 localPos = ivec2(gl_FragCoord.xy);
    ivec2 globalPos = ivec2(activeArray.x + localPos.x, fusedGlobalStartY + localPos.y);
    int channel = channelAt(globalPos);

    uint longRawValue = fetchRaw(longRawTex, globalPos);
    float longSensor = normalizedRaw(
        longRawValue, phaseValue(longBlackPhase, channel), longWhiteLevel);
    float longScene = longSensor
        * shadingGain(longShadingTex, vec2(globalPos), channel);

    vec2 flowUv = renderUv(vec2(globalPos));
    vec4 flowState = sampleMapBilinear(flowTex, flowUv);
    vec2 sourcePos = vec2(globalPos) + flowState.rg;
    vec2 photoState = rowPhotometric(sourcePos.y);
    vec2 shortState = sampleShortSamePhase(sourcePos, channel, photoState.x);

    // LONG is the high-SNR body owner. SHORT is admitted only as physical LONG
    // validity collapses near sensor saturation, and only with geometrically valid,
    // unsaturated, above-black same-CFA evidence.
    float longDamage = smoothstep(0.88, 0.985, longSensor);
    float shortSupport = smoothstep(0.35, 0.85, shortState.y);
    float flowConfidence = clamp(flowState.b, 0.0, 1.0);
    float photometricConfidence = clamp(photoState.y, 0.0, 1.0);
    float ownership = clamp(
        longDamage * shortSupport * flowConfidence
            * smoothstep(0.35, 0.70, photometricConfidence),
        0.0, 1.0);
    if (longSensor >= 0.995 && shortState.y >= 0.85
            && flowConfidence >= 0.60 && photometricConfidence >= 0.55) {
        ownership = 1.0;
    }

    outCfa = max(0.0, mix(longScene, shortState.x, ownership));
}
