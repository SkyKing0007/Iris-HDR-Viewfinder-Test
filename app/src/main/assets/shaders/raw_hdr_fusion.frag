#version 300 es
precision highp float;
precision highp int;
// V1.5.4 V1.2: R=fused scene-linear CFA, G=semantic color provenance,
// B=physical LONG clipping risk, A=SHORT ownership. Provenance uses the proven
// Iris semantic contract exactly: 0=NORMAL_MEASURED, 1=CENSORED_UNKNOWN_CHROMA,
// 2=SHORT_VALIDATED. Confidence may decide a state here, but downstream stages
// may not reinterpret a censored numeric value as measured color.
layout(location=0) out vec4 outFusionState;
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

ivec2 quadOrigin(ivec2 p) {
    return p - ivec2(p.x & 1, p.y & 1);
}

float longSensorAt(ivec2 p) {
    int channel = channelAt(p);
    return normalizedRaw(
        fetchRaw(longRawTex, p), phaseValue(longBlackPhase, channel), longWhiteLevel);
}

float quadLongMean(ivec2 origin) {
    return 0.25 * (
        longSensorAt(origin)
        + longSensorAt(origin + ivec2(1, 0))
        + longSensorAt(origin + ivec2(0, 1))
        + longSensorAt(origin + ivec2(1, 1)));
}

float quadLongPeak(ivec2 origin) {
    float peak = 0.0;
    peak = max(peak, longSensorAt(origin));
    peak = max(peak, longSensorAt(origin + ivec2(1, 0)));
    peak = max(peak, longSensorAt(origin + ivec2(0, 1)));
    peak = max(peak, longSensorAt(origin + ivec2(1, 1)));
    return peak;
}

float quadBoundaryContrast(ivec2 origin) {
    float center = quadLongMean(origin);
    float contrast = 0.0;
    contrast = max(contrast, abs(center - quadLongMean(origin + ivec2(2, 0))));
    contrast = max(contrast, abs(center - quadLongMean(origin - ivec2(2, 0))));
    contrast = max(contrast, abs(center - quadLongMean(origin + ivec2(0, 2))));
    contrast = max(contrast, abs(center - quadLongMean(origin - ivec2(0, 2))));
    return contrast;
}

vec2 quadShortSupportAndCorrespondence(
        ivec2 origin, vec2 flow, float rowScale) {
    float minSupport = 1.0;
    float agreementSum = 0.0;
    float agreementWeight = 0.0;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            ivec2 q = origin + ivec2(x, y);
            int channel = channelAt(q);
            vec2 shortState = sampleShortSamePhase(vec2(q) + flow, channel, rowScale);
            minSupport = min(minSupport, shortState.y);
            float longSensor = longSensorAt(q);
            float longScene = longSensor
                * shadingGain(longShadingTex, vec2(q), channel);
            // Compare only where LONG still contains real radiometric information.
            // Fully clipped phases cannot veto SHORT, which is the highlight authority.
            float compareWeight = smoothstep(0.025, 0.10, longSensor)
                * (1.0 - smoothstep(0.93, 0.985, longSensor))
                * smoothstep(0.45, 0.80, shortState.y);
            if (compareWeight > 0.0) {
                float deltaEv = abs(log2(
                    max(shortState.x, 0.00001) / max(longScene, 0.00001)));
                float agree = 1.0 - smoothstep(0.18, 0.62, deltaEv);
                agreementSum += agree * compareWeight;
                agreementWeight += compareWeight;
            }
        }
    }
    float correspondence = agreementWeight > 0.0001
        ? agreementSum / agreementWeight : 1.0;
    return vec2(clamp(minSupport, 0.0, 1.0), clamp(correspondence, 0.0, 1.0));
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

    // V1.5.2 source ownership is a Bayer-quad decision. Once LONG loses highlight
    // information, SHORT is the designated exposure authority for the complete CFA
    // quad; R/G1/G2/B are never allowed to choose different exposure mixtures.
    ivec2 qOrigin = quadOrigin(globalPos);
    vec2 qCenter = vec2(qOrigin) + vec2(0.5);
    vec2 flowUv = renderUv(qCenter);
    vec4 flowState = sampleMapBilinear(flowTex, flowUv);
    vec2 sourcePos = vec2(globalPos) + flowState.rg;
    vec2 photoState = rowPhotometric(qCenter.y + flowState.g);
    // The metadata exposure ratio + robust global residual are radiometric authority.
    // A local row correction is optional refinement only: its confidence must control
    // whether its VALUE is consumed. Confidence=0 therefore means exactly 1.0 local
    // correction, not "apply an unproven scale but remember that it was uncertain".
    float rowCorrectionWeight = smoothstep(0.30, 0.70, clamp(photoState.y, 0.0, 1.0));
    float effectiveRowScale = mix(1.0, photoState.x, rowCorrectionWeight);
    vec2 shortState = sampleShortSamePhase(sourcePos, channel, effectiveRowScale);

    float longPeak = quadLongPeak(qOrigin);
    // SHORT is the designated highlight exposure. Begin the coherent hand-off well
    // before the sensor ceiling so real SHORT structure survives the HDR shoulder.
    // Once ANY phase in the LONG Bayer quad has reached the physical clipping zone,
    // LONG has lost information and is no longer permitted to veto valid SHORT.
    float longHighlightNeed = smoothstep(0.70, 0.92, longPeak);
    float hardLongClip = smoothstep(0.985, 0.997, longPeak);
    vec2 quadValidation = quadShortSupportAndCorrespondence(
        qOrigin, flowState.rg, effectiveRowScale);
    float shortSupport = smoothstep(0.35, 0.78, quadValidation.x);
    float hardShortAvailable = smoothstep(0.10, 0.35, quadValidation.x);
    float correspondenceConfidence = quadValidation.y;
    float flowConfidence = clamp(flowState.b, 0.0, 1.0);
    float localFlowEvidence = clamp(flowState.a, 0.0, 1.0);
    // Alignment confidence may shape the pre-clipping transition, but it may not
    // resurrect clipped LONG. Java has already resolved one safe SHORT geometry per
    // cell (local proof -> coherent-neighbor -> global). Alpha now means LOCAL geometry
    // authority only; inherited geometry remains conservative across sharp boundaries.
    float inheritedFlow = 1.0 - smoothstep(0.70, 0.95, localFlowEvidence);
    float boundaryContrast = quadBoundaryContrast(qOrigin);
    float inheritedBoundaryGate = 1.0
        - inheritedFlow * smoothstep(0.08, 0.28, boundaryContrast);
    float geometricAdmission = flowConfidence
        * correspondenceConfidence * inheritedBoundaryGate;

    float softOwnership = clamp(
        longHighlightNeed * shortSupport * geometricAdmission, 0.0, 1.0);
    float hardShortTakeover = hardLongClip * hardShortAvailable;
    float ownership = max(softOwnership, hardShortTakeover);
    if (longPeak >= 0.997 && quadValidation.x >= 0.10) {
        // Permanent clipping contract: a physically clipped LONG quad with usable
        // SHORT can never leak LONG white, regardless of soft confidence gates.
        ownership = 1.0;
        hardShortTakeover = 1.0;
    }

    // V1.5.4 V1.2 semantic color authority. The exact parent Bayer quad owns one
    // terminal state. LONG remains NORMAL_MEASURED only while every phase is below
    // the physical clipping-risk zone. Once the quad enters that zone, color is either
    // demonstrably SHORT_VALIDATED or CENSORED_UNKNOWN_CHROMA; there is no fractional
    // downstream chroma authority.
    const float PROVENANCE_NORMAL_MEASURED = 0.0;
    const float PROVENANCE_CENSORED_UNKNOWN_CHROMA = 1.0;
    const float PROVENANCE_SHORT_VALIDATED = 2.0;
    float longQuadCensored = step(0.985, longPeak);

    // A SHORT quad may gain chroma authority only when all four same-phase samples
    // are strongly usable, correspondence agrees where LONG still measures, and the
    // geometry itself is observable. Locally proven flow qualifies directly. A strong
    // coherent-neighbor fallback may qualify through flowConfidence >= 0.70. The final
    // global fallback is deliberately capped below that in Java, so a saturated or
    // textureless center cannot self-certify color from smooth global flow alone.
    float shortPhaseComplete = step(0.90, quadValidation.x);
    float shortCorrespondence = step(0.55, correspondenceConfidence);
    float observableGeometry = max(
        step(0.50, localFlowEvidence),
        step(0.70, flowConfidence));
    float shortSemanticValidated =
        shortPhaseComplete * shortCorrespondence * observableGeometry;

    // When LONG has entered the censoring zone and SHORT is semantically validated,
    // the numeric CFA carrier must match the provenance: use the coherent SHORT quad
    // outright instead of labelling a LONG/SHORT mixture as SHORT_VALIDATED.
    if (longQuadCensored >= 1.0 && shortSemanticValidated >= 1.0) {
        ownership = 1.0;
    }

    float colorProvenance = PROVENANCE_NORMAL_MEASURED;
    if (longQuadCensored >= 1.0) {
        colorProvenance = shortSemanticValidated >= 1.0
            ? PROVENANCE_SHORT_VALIDATED
            : PROVENANCE_CENSORED_UNKNOWN_CHROMA;
    }
    float longPhysicalClipRisk = longQuadCensored;

    outFusionState = vec4(
        max(0.0, mix(longScene, shortState.x, ownership)),
        colorProvenance,
        longPhysicalClipRisk,
        ownership);
}
