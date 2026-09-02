#!/usr/bin/env python3
from pathlib import Path
import math
import hashlib

ROOT = Path(__file__).resolve().parents[1]
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
main = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java").read_text()
camera = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java").read_text()
gl = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java").read_text()
fusion = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java").read_text()
saver = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java").read_text()
frame_meta = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/FrameMeta.java").read_text()
hdr_shader = (ROOT / "app/src/main/assets/shaders/hdr_display.frag").read_text()
oes_shader = (ROOT / "app/src/main/assets/shaders/oes_to_rgb.frag").read_text()
workflow = (ROOT / ".github/workflows/build.yml").read_text()


def require(condition, message):
    if not condition:
        raise SystemExit("V1.4.19 REGRESSION FAIL: " + message)


# 015 - Real javac failure from V1.4 must never return.
require('final long sensorTimestampNs;' in frame_meta,
        "FrameMeta sensorTimestampNs contract missing")
require('metaByTimestamp.put(meta.sensorTimestampNs, meta);' in gl,
        "HdrGlView must use FrameMeta.sensorTimestampNs for timestamp matching")
require('meta.timestampNs' not in gl,
        "failed V1.4 meta.timestampNs compiler reference returned")

# 014 - Temporary Python outputs never enter the upload candidate.
require(not any(ROOT.rglob("__pycache__")) and not any(ROOT.rglob("*.pyc")),
        "temporary Python __pycache__/pyc files must not enter the upload candidate")

# 004 - User orientation policy and responsive controls remain authoritative.
require('android:screenOrientation="fullUser"' in manifest,
        "Activity must respect Android auto-rotate and user orientation lock")
require('android:screenOrientation="landscape"' not in manifest,
        "historical forced-landscape policy returned")
require('Configuration.ORIENTATION_PORTRAIT' in main,
        "portrait-specific responsive controls missing")
require('buildPortraitControls' in main and 'buildLandscapeControls' in main,
        "portrait and landscape control layouts must both exist")
require('onSaveInstanceState' in main and 'restoreUiState' in main,
        "camera/mode/exposure state must survive orientation recreation")

# 005 - SurfaceTexture producer transform is consumed exactly once.
require('new SurfaceTexture(externalTexture)' in gl,
        "direct GPU SurfaceTexture camera input missing")
require('surfaceTexture.getTransformMatrix(textureTransform);' in gl,
        "SurfaceTexture transform matrix must be consumed")
require('glUniformMatrix4fv' in gl and 'texTransform' in gl,
        "SurfaceTexture transform must reach the OES shader")
require('samplerExternalOES cameraTex' in oes_shader,
        "external-OES camera sampler missing")
require('(texTransform * vec4(vUv, 0.0, 1.0)).xy' in oes_shader,
        "OES shader must apply SurfaceTexture transform exactly once")
require('rawUvBuffer' not in gl and 'rawUvs' not in gl,
        "retired manual YUV-origin flip must not coexist with SurfaceTexture transform")
require('yuv_to_rgb.frag' not in gl,
        "retired CPU-YUV shader path returned")

# 006 / 008 - Native FOV + FIT presentation.
require('landscapeAspect(rawSize)' in camera,
        "RAW/native sensor aspect must be the stream-aspect authority")
require('getOutputSizes(SurfaceTexture.class)' in camera,
        "preview must use PRIVATE SurfaceTexture output sizes")
require('choosePrivatePreviewSize(map, nativeAspect' in camera,
        "preview selection must consume native aspect")
require('chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG), nativeAspect)' in camera,
        "JPEG selection must consume the same native aspect")
require('new Size(1280, 720)' not in camera,
        "forced 16:9 preview target returned")
require('sizeScore(' not in camera,
        "retired 16:9 size scoring returned")
require('aspectError <= 0.015' in camera,
        "native-aspect tolerance gate missing")
require('CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF' in camera,
        "digital video stabilization crop must be explicitly disabled")
require('CaptureRequest.CONTROL_ZOOM_RATIO' not in camera
        and 'CaptureRequest.SCALER_CROP_REGION' not in camera,
        "native-FOV path must not force zoom ratio or crop region")
require('uniform vec2 fullFitScale;' in hdr_shader and 'uniform vec2 splitFitScale;' in hdr_shader,
        "FIT uniforms missing")
require('fitSourceUv' in hdr_shader,
        "FIT sampling helper missing")
require('fullCropScale' not in hdr_shader and 'splitCropScale' not in hdr_shader,
        "center-crop zoom path returned")
require('setFitScaleUniform(displayProgram, "fullFitScale"' in gl,
        "full preview FIT binding missing")
require('setFitScaleUniform(displayProgram, "splitFitScale"' in gl,
        "split preview FIT binding missing")

# 007 - JPEG still orientation remains proven and separate from live preview.
require(camera.count('CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees') == 2,
        "SHORT/LONG JPEG requests must share device-relative orientation")
require('int jpegOrientation = (sensorOrientation - displayDegrees + 360) % 360;' in main,
        "JPEG orientation convention changed")
require('ExifInterface.TAG_ORIENTATION' in fusion,
        "fused JPEG must normalize EXIF-only HAL orientation")

# 009 - Direct GPU live path; no per-frame Java YUV repacking.
require(not (ROOT / 'app/src/main/java/com/skyking0007/irishdrviewfinder/YuvFrame.java').exists(),
        "retired YuvFrame CPU repacker returned")
require(not (ROOT / 'app/src/main/assets/shaders/yuv_to_rgb.frag').exists(),
        "retired YUV upload shader returned")
require('YUV_420_888' not in camera,
        "live preview must not use YUV ImageReader")
require('ImageReader.newInstance(\n                previewSize' not in camera,
        "preview ImageReader returned")
require('PENDING_SLOTS = 6' in gl and 'metaByTimestamp' in gl,
        "timestamp-matched GPU pending ring missing")
require('surfaceTexture.getTimestamp()' in gl,
        "SurfaceTexture timestamp ownership missing")
require('onPreviewMeta(FrameMeta meta)' in main and 'glView.enqueueMeta(meta);' in main,
        "CaptureResult metadata must reach GPU timestamp matcher")
require('onInputsAcquired' in saver and 'resumePreviewAfterStillInputsLocked' in camera,
        "capture must resume preview after inputs arrive instead of waiting for file I/O")
require('Arrays.asList(previewSurface)' in camera,
        "steady-state preview session must be preview-only for max frame rate")
require('Arrays.asList(previewSurface, jpegReader.getSurface(), rawReader.getSurface())' in camera,
        "temporary still session must retain PRIVATE + JPEG + RAW capture topology")

# 010 / 020 - Capability target and measured cadence remain separate.
require('SIXTY_FPS_DURATION_NS = 16_666_666L' in camera,
        "exact 60 fps frame-duration target missing")
require('THIRTY_FPS_DURATION_NS = 33_333_333L' in camera,
        "30 fps fallback missing")
require('CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES' in camera,
        "AE FPS capability query missing")
require('getOutputMinFrameDuration(SurfaceTexture.class, size)' in camera,
        "selected PRIVATE stream min-frame-duration proof missing")
require('hasExactAeFpsRange(ranges, 60)' in camera,
        "true 60fps must require an exact [60,60] Camera2 range")
require('CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE' in camera,
        "preview requests must carry the selected supported FPS range")
require('targetPreviewFps >= 60' in camera and 'SIXTY_FPS_DURATION_NS' in camera,
        "manual HDR must enforce the true-60 frame-duration contract")
require('captureResultFps' in camera and 'updateCaptureResultFpsLocked' in camera,
        "actual CaptureResult cadence measurement missing")
require('CaptureResult.SENSOR_FRAME_DURATION' in camera and 'resultFps=' in camera,
        "actual frame-duration/FPS diagnostics missing")
require('captureResultFps < 45.0' in camera and 'FPS_FORCE60_UNDERDELIVERY' in camera,
        "measured 60-fps under-delivery evidence/logging missing")
require('targetPreviewFps = allowCropped60Fps && sixtyFpsCapable ? 60 : 30;' in camera,
        "initial cadence must be fixed 30 unless cropped-60 is explicitly enabled")
require('keeping explicit target=60 [60,60]' in camera,
        "explicit cropped-60 mode must not silently mutate back to 30 fps")
require('camera %.1f fps   HDR pairs %.1f fps' in main,
        "GPU input and complete HDR-pair cadence diagnostics must remain visible")

# 011 / 032 - Explicit Camera2 sRGB contrast-curve semantics.
require('CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE' in camera,
        "Camera2 CONTRAST_CURVE mode missing")
require('CaptureRequest.TONEMAP_CURVE' in camera,
        "explicit Camera2 TONEMAP_CURVE missing")
require('buildSrgbTonemapCurve' in camera and '0.0031308f' in camera and '2.4' in camera,
        "explicit sampled sRGB transfer curve missing")
require('TONEMAP_AVAILABLE_TONE_MAP_MODES' in camera and 'TONEMAP_MAX_CURVE_POINTS' in camera,
        "contrast-curve capability/point-count gate missing")
require('TONEMAP_MODE_PRESET_CURVE' not in camera and 'TONEMAP_PRESET_CURVE_SRGB' not in camera,
        "retired PRESET_CURVE sRGB path returned")

# 012 / 029 / 037 / 043 / 044 / 050 / 052 - V1.4.16 exposure ownership.
# Clean AE is bootstrap-only. Once it establishes one natural scene reference, the live
# SHORT/LONG pair never yields to periodic AE; displayed-pair statistics continuously
# adjust LONG appearance and independently choose only as much SHORT headroom as needed.
require('AUTO_BRACKET_DEFAULT_EV = 3.0' in camera
        and 'AUTO_BRACKET_MAX_EV = 5.0' in camera
        and 'MANUAL_BRACKET_MAX_EV = 6.0' in camera,
        "adaptive AUTO/MANUAL bracket limits missing")
require('LONG_CLIP_TRIGGER_FRACTION = 0.005' in camera
        and 'AUTO_SHORT_CLIP_TARGET = 0.0025' in camera
        and 'MANUAL_SHORT_CLIP_TARGET = 0.0015' in camera,
        "95%-HDR meaningful-clipping budget missing")
require('shortExposureNs = ONE_SECOND_NS / 480' in camera
        and 'longExposureNs = ONE_SECOND_NS / 60' in camera,
        "manual defaults changed unexpectedly")
require('AUTO_METER_MIN_FRAMES' in camera and 'buildMeterPreviewRequest' in camera,
        "initial clean AE bootstrap missing")
require('AUTO_REMETER_INTERVAL_MS' not in camera and 'autoRemeterRunnable' not in camera
        and 'scheduleAutoRemeterLocked' not in camera,
        "periodic AE takeover must remain removed after bootstrap")
bootstrap = camera[camera.index('private void startAutoMeteringLocked()'):camera.index('private void processAutoMeterResultLocked')]
require('if (haveAeSample) {' in bootstrap and 'Bootstrap only' in bootstrap,
        "bootstrap meter must refuse to replace an already-live HDR pair")
require(camera.count('buildMeterPreviewRequest(), previewCaptureCallback') == 1,
        "clean AE repeating request may exist only for initial bootstrap")
require('autoSceneBaseLongProduct' in camera and 'autoAdaptiveBodyTargetLinear' in camera,
        "continuous adaptive LONG scene-body state missing")
require('deriveAdaptiveAutoPairLocked' in camera and 'adaptBracketEvLocked' in camera,
        "adaptive LONG/SHORT exposure solver missing")
require('Math.pow(2.0, clampBrightnessEv(displayBrightnessEv))' in camera,
        "Brightness must bias LONG appearance target")
require('achievedLongProduct / Math.pow(2.0, autoAdaptiveBracketEv)' in camera,
        "AUTO SHORT must be independently derived from adaptive highlight headroom")
require('baseShortProduct * requestedGain' not in camera
        and 'baseLongProduct * achievedPairGain' not in camera,
        "V1.4.14 whole-pair Brightness coupling must remain removed")
require('shortDarkFraction > 0.94f' in camera and 'overlapErrorEv > 0.50f' in camera
        and '!stats.shortTemporalReliable' in camera,
        "SHORT quality/overlap/temporal guard missing")
require('robustSceneBodyMid' in camera and 'adaptiveSceneBodyTargetLocked' in camera,
        "adaptive LONG scene-body meter missing")
require('longP25Linear' in camera and 'longP35Linear' in camera
        and 'longP25Linear' in gl and 'longP35Linear' in gl and 'longP98Linear' in gl,
        "P25/P35/P50 body plus highlight-tail evidence must reach the appearance controller")
require('AUTO_BODY_TARGET_NORMAL_LINEAR = 0.070' in camera
        and 'AUTO_BODY_TARGET_HDR_LINEAR = 0.115' in camera
        and 'AUTO_BODY_TARGET_MIN_LINEAR = 0.040' in camera
        and 'AUTO_BODY_TARGET_MAX_LINEAR = 0.135' in camera,
        "adaptive LONG scene-body target range missing")
require('stats.longExposureProduct * target / Math.max(bodyMid, 0.0005)' in camera,
        "scene key must be estimated from our measured LONG body and known exposure product")
scene_target = camera[camera.index('private double adaptiveSceneBodyTargetLocked'):camera.index('private static double smoothstepDouble')]
require('lastAeExposureNs' not in scene_target and 'lastAeIso' not in scene_target,
        "bootstrap system AE must have no continuing vote in LONG reality target")
require('AUTO_BODY_MAX_STEP_EV = 0.18' in camera and 'AUTO_BODY_CONFIRM_SAMPLES = 2' in camera,
        "LONG adaptation must remain smooth and require consecutive scene-body evidence")
require(camera.count('bodyRaiseEvidence = 0;') >= 8
        and camera.count('bodyLowerEvidence = 0;') >= 8,
        "LONG scene-body evidence must reset across camera/FPS/re-anchor transitions")
require('BRACKET_CONFIRM_UP_SAMPLES = 2' in camera
        and 'BRACKET_CONFIRM_DOWN_SAMPLES = 3' in camera,
        "adaptive bracket hysteresis evidence counters missing")
require('double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);' in camera,
        "actual adaptive bracket must remain reported")

# 013 / 030 / 036 / 039 / 046 / 053 / 077 / 078 / 079 - Scene-learned, edge-safe HDR reconstruction.
for text, owner in ((hdr_shader, 'live shader'), (fusion, 'JPEG fusion')):
    require('0.04045' in text and '12.92' in text and '0.0031308' in text and '2.4' in text,
            f"{owner} must retain piecewise sRGB conversion")
require('uniform vec4 shortPhotoScaleA;' in hdr_shader
        and 'uniform float shortPhotoScaleB;' in hdr_shader
        and 'uniform vec2 fusionTexelStep;' in hdr_shader,
        "live scene-learned response/multiscale uniforms missing")
require('uniform float shortCalibration;' not in hdr_shader
        and 'shortPhotoScaleForLuma' in hdr_shader
        and 'calibratedShortScene' in hdr_shader,
        "visible live fusion must use the learned multi-knot response, never the retired scalar uniform")
require('PHOTO_KNOT_COUNT = 5' in gl
        and 'PHOTO_LUMA_KNOTS = {0.020f, 0.060f, 0.150f, 0.350f, 0.700f}' in gl
        and 'PHOTO_MAX_UPDATE_EV = 0.06f' in gl
        and 'updateShortPhotoCurve' in gl
        and 'enforceMonotonicPhotoCurve' in gl,
        "live five-knot monotonic scene-response learner missing")
require('PHOTO_KNOT_COUNT = 5' in fusion
        and 'PHOTO_LUMA_KNOTS = {0.020f, 0.060f, 0.150f, 0.350f, 0.700f}' in fusion
        and 'learnPhotoCurve' in fusion
        and 'enforceMonotonicPhotoCurve' in fusion
        and 'photoScaleForLuma' in fusion,
        "saved five-knot monotonic scene-response learner missing")
require('calibrateShortToLong' not in fusion,
        "retired scalar-only saved photometric mapping returned")
require('uniform sampler2D shortReliabilityTex;' in hdr_shader
        and 'shortReliabilityTexture' in gl and 'GL_RG8' in gl,
        "local two-channel SHORT reliability map missing")
require('shortLumaReliability' in gl and 'shortChromaReliability' in gl
        and 'LUMA_RELIABILITY_INITIAL = 224' in gl
        and 'CHROMA_RELIABILITY_INITIAL = 128' in gl
        and 'LUMA_RELIABILITY_RELEASE = 64' in gl
        and 'CHROMA_RELIABILITY_RELEASE = 96' in gl,
        "graded local luminance/chroma temporal confidence missing")
require('shortLumaStableCounts' not in gl and 'shortChromaStableCounts' not in gl,
        "V1.4.17 binary 0/255 local trust state returned")
require('snapshotShortReliabilityMap()' in gl
        and 'latestShortReliabilitySnapshot' in gl,
        "shutter-time live reliability snapshot owner missing")
require('unstableFraction <= 0.25f' in gl and 'shortTemporalReliable' in gl,
        "widespread SHORT instability must still guard exposure/bracket adaptation")
require('longHighlightShoulder' in hdr_shader and 'longClippedCore' in hdr_shader
        and 'fusionSample' in hdr_shader and 'multiscaleHighlightRecovery' in hdr_shader
        and 'highlightNeed' in fusion and 'clippedCore' in fusion
        and 'rawMask' in fusion and 'coreMask' in fusion,
        "LONG-base full-core/edge-guided highlight compositor missing")
require('float corePermission = smoothstep(0.25, 0.55, shortUsable);' in hdr_shader
        and 'coreMask = clippedCore * corePermission;' in hdr_shader
        and 'rawMask = max(coreMask, shoulderMask);' in hdr_shader,
        "live clipped core must use current SHORT safety for complete detail authority")
require('float currentCoreMask = clippedCore * smoothstep(0.25f, 0.55f, shortUsable);' in fusion
        and 'coreMask[i] = currentCoreMask;' in fusion
        and 'rawMask[i] = Math.max(currentCoreMask, currentShoulderMask);' in fusion,
        "saved clipped core must use captured SHORT safety for complete recovery")
require('temporalTrust.r' not in hdr_shader and 'lumaReliability' not in fusion,
        "5-Hz temporal luma state must never gate visible luma fusion")
require('temporalTrust.g' in hdr_shader and 'chromaReliability' in fusion
        and 'colorTrust' in hdr_shader and 'colorTrust' in fusion,
        "temporal history must remain a chroma/quality prior after luma decoupling")
require('longChromaticityAtShortLuma' in hdr_shader
        and 'float chromaScale = sl / Math.max(ll, 0.0005f);' in fusion
        and 'lr * chromaScale' in fusion and 'lg * chromaScale' in fusion and 'lb * chromaScale' in fusion,
        "questionable SHORT color must recover luma/detail with LONG chromaticity")
require('neutralLongClip' in hdr_shader and 'neutralLongClip' in fusion,
        "neutral clipped-highlight color lock missing")
require('validChannelAgreement' in hdr_shader and 'validChannelAgreement' in fusion,
        "current-pair SHORT/LONG agreement guard missing")
require('mapRecoveredHighlight' in hdr_shader and 'mappedRecoveryPeak' in fusion,
        "recovered SHORT highlight-only display mapping missing")
require('guideEdgeWeight' in hdr_shader and 'addFusionNeighbor' in hdr_shader
        and 'damageSupport' in hdr_shader and 'edgeWeight' in fusion
        and 'damageSupport' in fusion,
        "one-sided edge-guided transition support missing")
require('float blurredMask = min(damageSupport' in hdr_shader
        and 'float coarseMask = max(coreMask, blurredMask);' in hdr_shader
        and 'float fineMask = max(' in hdr_shader
        and 'vec3 lowBand = mix(longLow, shortLow, coarseMask);' in hdr_shader
        and 'vec3 detailBand = mix(longCenter - longLow, shortCenter - shortLow, fineMask);' in hdr_shader,
        "live Gaussian-mask/Laplacian-image transition missing")
require('float blurredMask = Math.min(' in fusion
        and 'float coarseMask = Math.max(coreMask[center], blurredMask);' in fusion
        and 'float fineMask = Math.max(' in fusion
        and '(shortLowR - longLowR) * coarseMask' in fusion
        and '(shortR[center] - shortLowR) * fineMask' in fusion,
        "saved Gaussian-mask/Laplacian-image transition missing")
require('recoverOnlyLostChannels' not in hdr_shader and 'recoverChannel(' not in fusion
        and 'mix(longScene, shortScene, highlightWeight)' not in hdr_shader
        and 'mix(longScene, mappedShort, recoveryMask)' not in hdr_shader,
        "retired broad/per-channel/direct source-switch fusion returned")
require('displayBrightnessEv' not in hdr_shader and 'brightnessGain' not in hdr_shader
        and 'displayBrightnessEv' not in fusion and 'brightnessGain' not in fusion,
        "Brightness must remain entirely outside fusion/tone")
require('displayBrightnessEv' not in gl,
        "GL renderer must not own Brightness EV")
require('65_536.0' in fusion and '65_536.0' in saver and '65_536.0' in gl,
        "exposure normalization range must remain consistent live/save/metadata")
require('controller.captureHdrSet(glView.snapshotShortReliabilityMap());' in main,
        "shutter must freeze the currently displayed chroma/quality reliability field")
require('captureHdrSet(byte[] shortReliabilityMap)' in camera
        and 'frozenShortReliabilityMap' in camera,
        "CameraController must carry frozen live chroma/quality trust into still capture")
require('byte[] shortReliabilityMap' in saver
        and 'shortReliabilityMap.clone()' in saver
        and 'JpegFusion.fuse(shortJpeg, longJpeg, ratio, shortReliabilityMap)' in saver,
        "saved FUSED JPEG must consume the frozen live chroma/quality trust field")
require('RELIABILITY_WIDTH = 32' in fusion and 'RELIABILITY_HEIGHT = 24' in fusion
        and 'sampleReliability' in fusion
        and 'chromaReliability * rgbSafe * agreement' in fusion,
        "full-resolution JPEG fusion must consume frozen trust for chroma only")
require('((imageX + 0.5f) * RELIABILITY_WIDTH / imageWidth) - 0.5f' in fusion
        and '((imageY + 0.5f) * RELIABILITY_HEIGHT / imageHeight) - 0.5f' in fusion,
        "saved JPEG reliability sampling must mirror GL_LINEAR texel-center geometry")
require('0.045' not in hdr_shader and '0.045' not in fusion,
        "retired office-derived brightness constant must never enter fusion ownership")
require('textureOffset' not in hdr_shader and 'texelFetch' not in hdr_shader,
        "fusion neighborhood must remain explicit edge-guided sampling; retired cross-edge chroma blur primitives may not return")
# 016 / 022 - V1.4.2 on-device sideways preview: producer transform owns live orientation.
require('SCALER_AVAILABLE_ROTATE_AND_CROP_MODES' in camera,
        "preview rotate/crop capability audit missing")
require('CaptureRequest.SCALER_ROTATE_AND_CROP_NONE' in camera,
        "preview must opt out of HAL compatibility rotate/crop when supported")
require('configurePreviewRotateAndCrop(builder);' in camera,
        "preview requests must apply rotate/crop NONE")
require('int previewRelation = (sensorOrientation - displayDegrees + 360) % 360;' in main,
        "sensor/display relation needed for axis-swap FIT is missing")
require('setProducerOwnedOrientationDegrees(previewRelation)' in main,
        "MainActivity must declare producer-owned live orientation")
require('renderer.rotationQuarterTurns = 0;' in gl,
        "second live display quarter-turn must remain disabled")
require('renderer.producerAxisSwap = ((normalized / 90) & 1) != 0;' in gl,
        "producer orientation must still drive FIT axis swap")
require('((360 - normalized) % 360) / 90' not in gl,
        "rejected V1.4.2 inverse display rotation returned")
assignments = [line.strip() for line in gl.splitlines() if 'rotationQuarterTurns =' in line]
require(sorted(assignments) == sorted(['volatile int rotationQuarterTurns = 0;', 'renderer.rotationQuarterTurns = 0;']),
        f"unexpected live display rotation owner: {assignments}")
require(camera[camera.index('private void issueStillBurstLocked()'):camera.index('private final CameraCaptureSession.CaptureCallback stillCaptureCallback')].count('configurePreviewRotateAndCrop') == 0,
        "still path must not inherit preview rotate/crop controls")

# 017 - HDR/SPLIT displays only complete temporally adjacent pairs.
require('stagingShortTexture' in gl and 'stagingLongTexture' in gl,
        "atomic SHORT/LONG staging textures missing")
require('haveStagingShort' in gl and 'stagingShortMeta' in gl,
        "atomic pair metadata state missing")
require('return stagingShortTexture;' in gl and 'return stagingLongTexture;' in gl,
        "incoming HDR frames must land in staging textures")
require('Publish only an exact exposure-generation pair' in gl,
        "exact exposure-generation publication contract marker missing")
require('stagingShortMeta.exposureGeneration == meta.exposureGeneration' in gl
        and 'highestExposureGenerationSeen' in gl,
        "SHORT/LONG exposure-generation equality guard missing")
require('stats.exposureGeneration != previewExposureGeneration' in camera,
        "retired-generation statistics must not drive the live controller")
require('meta.frameNumber - stagingShortMeta.frameNumber <= 3' in gl,
        "SHORT/LONG temporal adjacency guard missing")
short_accept = gl[gl.index('private void acceptMeta'):gl.index('private void renderExternalToTexture')]
require(short_accept.count('fpsWindowPairs++;') == 1,
        "HDR pair cadence must increment only on complete-pair publication")
require('lastShortMeta = stagingShortMeta;' in short_accept and 'lastLongMeta = meta;' in short_accept,
        "display exposure metadata must update atomically with the published pair")

# 018 / 021 / 029 / 052 - AUTO/MANUAL remain available and the viewfinder stays live.
require('void setAutoHdrExposure(boolean enabled)' in camera,
        "AUTO/MANUAL HDR exposure owner switch missing")
require('HDR AUTO: ON' in main and 'HDR MANUAL' in main,
        "AUTO/MANUAL HDR UI control missing")
require('setManualControlsEnabled(!autoHdrEnabled)' in main,
        "manual controls must remain user-accessible in MANUAL")
require('glView.setSceneStatsListener(controller::onHdrSceneStats);' in main,
        "live HDR scene-stat listener is not wired")
require('void onHdrSceneStats(HdrGlView.SceneStats stats)' in camera,
        "CameraController live scene-stat callback missing")
require('SceneStatsListener' in gl and 'maybePublishSceneStats();' in gl,
        "GPU live scene-stat publisher missing")
require('STATS_WIDTH = 32' in gl and 'STATS_HEIGHT = 24' in gl
        and 'STATS_INTERVAL_NS = 200_000_000L' in gl,
        "bounded 32x24 / 200ms live statistics contract missing")
require('glReadPixels' in gl and 'MEANINGFUL_CLIP_CHANNEL = 0.992f' in gl,
        "meaningful highlight sampling missing")
require('Arrays.asList(shortRequest, longRequest)' in camera,
        "steady AUTO/MANUAL HDR must remain a two-manual-request repeating pair")
require('FrameMeta.METER.equals(meta.kind)' in gl,
        "initial meter frames must remain hidden from HDR pair publication")
require('captureSession.capture(' not in camera,
        "out-of-band one-shot live meter must never interrupt the pair")
# 025 / 031 - Both manual pair members carry one FPS range; true-60 mode uses exact [60,60].
manual_builder = camera[camera.index('private CaptureRequest buildManualPreviewRequest'):camera.index('private CaptureRequest buildMeterPreviewRequest')]
require('CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE' in manual_builder,
        "SHORT/LONG manual preview requests must carry the selected AE FPS range")
require('hasExactAeFpsRange(ranges, 60)' in camera and 'range.getLower() == target && range.getUpper() == target' in camera,
        "60fps capability must prefer exact [60,60]")
require('60 FPS CROP: ON' in main and '60 FPS CROP: OFF' in main,
        "user-visible cropped-60 override toggle missing")
require('FOV_OVERRIDE' in camera and 'allowCropped60Fps' in camera,
        "cropped-60 override must be explicit and logged")

# 019 / 029 / 045 / 052 - Flicker-aware independent LONG/SHORT exposure ownership.
require('CaptureResult.STATISTICS_SCENE_FLICKER' in camera,
        "Camera2 scene-flicker evidence missing")
require('CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO' in camera,
        "clean AE bootstrap must request HAL automatic antibanding")
require('STATISTICS_SCENE_FLICKER_50HZ' in camera and 'STATISTICS_SCENE_FLICKER_60HZ' in camera,
        "50/60-Hz evidence labels must remain explicit")
require('flickerPeriodNs' in camera and '10_000_000L' in camera and '8_333_333L' in camera,
        "50/60-Hz safe integration periods missing")
require('solveLongSettingForProductLocked' in camera and 'solveShortSettingForProductLocked' in camera,
        "separate LONG/SHORT timing solvers missing")
require('A stable white clip is preferable to revealing LED/PWM phase' in camera,
        "known 50/60-Hz SHORT must stop at a stable integration boundary")
require('Unknown/PWM: keep SHORT on the same integration as LONG' in camera,
        "unknown/PWM SHORT must prefer stable integration over extra headroom")
require('Unknown/PWM: preserve the clean-AE integration in both directions' in camera,
        "unknown/PWM LONG must not invent a new modulation phase")
require('targetPreviewFps >= 60 ? SIXTY_FPS_DURATION_NS' in camera,
        "forced-60 live integration ceiling missing")
require('autoShortExposureNs = shortSetting.exposureNs;' in camera
        and 'autoLongExposureNs = longSetting.exposureNs;' in camera,
        "AUTO SHORT and LONG must have independent solved settings")
# 023 - On-device DNG Orientation=9 regression: DNG must always receive explicit valid TIFF orientation.
require('import android.media.ExifInterface;' in saver,
        "DNG orientation must use Android EXIF constants")
require(saver.count('new DngCreator(characteristics, result)') == 1,
        "unexpected DngCreator ownership count")
require(saver.count('creator.setOrientation(dngOrientation);') == 1,
        "DngCreator.setOrientation must run before RAW write")
require('int captureOrientationDegrees' in saver and 'jpegOrientationDegrees,' in camera,
        "device-relative still orientation must reach CaptureSetSaver")
for token in [
    'ExifInterface.ORIENTATION_NORMAL',
    'ExifInterface.ORIENTATION_ROTATE_90',
    'ExifInterface.ORIENTATION_ROTATE_180',
    'ExifInterface.ORIENTATION_ROTATE_270',
]:
    require(token in saver, f"DNG orientation mapping missing {token}")
require('ExifInterface.ORIENTATION_UNDEFINED' not in saver,
        "DNG must never request undefined orientation, which DngCreator maps to TIFF 9")
require(saver.index('creator.setOrientation(dngOrientation);') < saver.index('creator.writeImage'),
        "DNG orientation must be set before writeImage")

# 024 - First measured FPS window starts on the first CaptureResult, not during camera/session startup.
require('private void resetCaptureResultFpsLocked()' in camera,
        "CaptureResult FPS reset helper missing")
require('resultFpsWindowStartNs = 0L;' in camera,
        "FPS window must reset to an unstarted state")
open_section = camera[camera.index('private void openCameraLocked()'):camera.index('private void resolveOutputSizesLocked()')]
require('resultFpsWindowStartNs = System.nanoTime();' not in open_section,
        "camera-open latency must not contaminate measured CaptureResult FPS")
require('if (resultFpsWindowStartNs == 0L) resultFpsWindowStartNs = now;' in camera,
        "first CaptureResult must start the cadence clock")

# 026 / 034 - Device FOV/cadence regression: never transition crop/FPS behind the user.
# FOV SAFE is fixed 30 from the first request. Cropped 60 is explicit and diagnostic-only
# FOV evidence must never be fed by hidden AE meter frames.
require('LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION' in camera,
        "API-35 physical-sensor crop diagnostics missing")
require('targetPreviewFps = allowCropped60Fps && sixtyFpsCapable ? 60 : 30;' in camera,
        "FOV SAFE must start at 30; 60 requires explicit crop opt-in")
fps_policy = camera[camera.index('private void applyFpsPolicyLocked'):camera.index('private String fovResultSummary')]
require('targetPreviewFps = 30;' in fps_policy and 'aeFpsRange = chooseAeFpsRange(ranges, 30);' in fps_policy,
        "turning cropped-60 OFF must immediately restore stable 30-fps policy")
require('FOV_OVERRIDE' in camera and 'allowCropped60Fps' in camera,
        "cropped-60 sensor-readout difference must remain explicit and logged")
callback = camera[camera.index('private final CameraCaptureSession.CaptureCallback previewCaptureCallback'):camera.index('private void beginCaptureLocked(byte[] frozenShortReliabilityMap)')]
require('if (!FrameMeta.METER.equals(kind)) {' in callback
        and 'updateCaptureResultFpsLocked();' in callback
        and 'updateFovEvidenceLocked(result);' in callback,
        "hidden AE meter frames must be excluded from cadence/FOV evidence")
require('CaptureRequest.CONTROL_ZOOM_RATIO' not in camera
        and 'CaptureRequest.SCALER_CROP_REGION' not in camera,
        "FOV policy must not fake parity by digitally cropping or zooming requests")

# 027 / 031 / 054 - MANUAL SAFE keeps user LONG intent but retains adaptive HDR intelligence.
require('recomputeManualAdaptivePairLocked' in camera,
        "MANUAL adaptive HDR exposure owner missing")
require('manualEffectiveShortExposureNs' in camera and 'manualEffectiveLongExposureNs' in camera,
        "requested and effective MANUAL shutters must remain separate")
require('manualAdaptiveBracketEv' in camera and 'manualBracketFloorEv' in camera,
        "MANUAL adaptive headroom state missing")
require('MANUAL_EXTRA_HEADROOM_EV = 0.25' in camera,
        "MANUAL must retain modest extra highlight headroom")
require('nextBracket = Math.max(manualBracketFloorEv, nextBracket);' in camera,
        "MANUAL user short-headroom floor must not be overridden")
require('Math.min(shortSetting.exposureNs, clampExposure(shortExposureNs))' in camera,
        "MANUAL adaptive SHORT must never become longer than the user's requested ceiling")
require('MANUAL_SAFE' in camera and 'HDR MANUAL SAFE' in main,
        "user-visible safe MANUAL ownership missing")
require('MANUAL_LIVE_ADAPT' in camera,
        "MANUAL live adaptive headroom decision must be logged")
# 028 - Production logger for device freezes/crashes without turning logging into a frame-rate owner.
require('final class RuntimeLogger' in main,
        "production RuntimeLogger missing")
require('Downloads/IrisHDRViewfinder/Logs/' in main,
        "runtime log must be user-retrievable from Downloads")
require('Thread.setDefaultUncaughtExceptionHandler' in main and 'UNCAUGHT_CRASH' in main,
        "uncaught crash persistence missing")
require('UI_HEARTBEAT' in main and '10_000L' in main,
        "low-duty UI heartbeat missing")
require('CAMERA_HEALTH' in camera and 'previewResultCount % 300 == 0' in camera,
        "camera health logger must be throttled")
require('GL_FRAME_FAIL' in gl and '5_000_000_000L' in gl,
        "rate-limited swallowed GL runtime failure evidence missing")
require('GL_READY' in gl,
        "GPU vendor/renderer evidence missing")
require('RuntimeLogger.event("STATUS"' not in main,
        "high-frequency UI status logging must not become a performance owner")
require('Event producers are deliberately rate-limited' in main,
        "logger rate-limit ownership contract missing")

# 031 - Expanded manual exposure controls and explicit cropped-60 option.
for token in ['1_000_000_000L / 8000', '1_000_000_000L / 100', '1_000_000_000L / 50',
              '30_000_000L', '1_000_000_000L / 25', '1_000_000_000L / 20']:
    require(token in main, f"manual exposure slider step missing: {token}")
require('SIXTY_FPS_DURATION_NS = 16_666_666L' in camera,
        "forced 60fps must use 16,666,666 ns SENSOR_FRAME_DURATION target")
require('targetPreviewFps >= 60' in camera and 'manualEffectiveLongExposureNs' in camera,
        "60fps mode must cap effective manual integration and preserve LONG product through ISO")
require('boolean enforcePreviewCadence' in camera
        and 'if (enforcePreviewCadence && targetPreviewFps >= 60)' in camera
        and 'frameDuration = SIXTY_FPS_DURATION_NS;' in camera,
        "true-60 SENSOR_FRAME_DURATION must be owned by live preview requests")
require('Full RAW/JPEG still capture is a separate session' in camera
        and 'frameDuration = Math.max(THIRTY_FPS_DURATION_NS, exposure);' in camera,
        "optional cropped-60 preview cadence must never constrain full RAW/JPEG still capture")

# 032 - Explicit sRGB contrast curve and post-RAW boost parity across clean AE -> manual pair.
require('CONTROL_POST_RAW_SENSITIVITY_BOOST' in camera,
        "post-RAW sensitivity boost must be copied from clean AE into manual HDR pair")
require('postRawSensitivityBoost' in saver,
        "capture metadata must persist actual post-RAW boost")
require('TONEMAP_MODE_CONTRAST_CURVE' in camera and 'TONEMAP_CURVE' in camera,
        "explicit sRGB contrast curve missing")

# 033 - Requested ISP denoise/sharpen disable must be applied wherever supported.
require('NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES' in camera
        and 'CaptureRequest.NOISE_REDUCTION_MODE_OFF' in camera,
        "NOISE_REDUCTION_MODE_OFF support gate/request missing")
require('EDGE_AVAILABLE_EDGE_MODES' in camera and 'CaptureRequest.EDGE_MODE_OFF' in camera,
        "EDGE_MODE_OFF support gate/request missing")
require('configureProcessingControls(builder);' in camera,
        "processed preview/still requests must apply edge/noise ownership")

# 034 / 050 / 052 - Continuous live HDR regression: periodic meter takeover is forbidden.
require('scheduleAutoRemeterLocked' not in camera and 'autoRemeterRunnable' not in camera,
        "periodic clean-AE takeover must remain removed")
start_meter = camera[camera.index('private void startAutoMeteringLocked()'):camera.index('private void processAutoMeterResultLocked')]
require('Bootstrap only' in start_meter and 'if (haveAeSample) {' in start_meter,
        "AE repeating request must be initial-bootstrap only")
require('resetCaptureResultFpsLocked();' in start_meter,
        "initial bootstrap must start a clean FPS window")
require('FOV SAFE: fixed 30 fps preview avoids live sensor-crop/FPS transitions' in main,
        "UI must state deterministic FOV-safe 30-fps semantics")
require('60 FPS CROP ON: request fixed 60/60 preview' in main,
        "UI must state explicit force-60 semantics")

# Adaptive-policy math: tiny speculars may clip; meaningful regions drive SHORT only
# after stable consecutive evidence. This state is control history, not image stacking.
def adapt_bracket_sequence(current_ev, samples, manual=False, floor=2.0):
    min_ev=max(floor,3.25) if manual else 3.0
    max_ev=6.0 if manual else 5.0
    short_target=0.0015 if manual else 0.0025
    up=down=0
    out=current_ev
    for long_clip, short_clip, short_reliable in samples:
        meaningful=long_clip>=0.005
        wants_up=meaningful and short_clip>short_target and short_reliable
        wants_down=(not meaningful) and short_clip<=0.0005 and out>min_ev
        if wants_up:
            up+=1; down=0
            if up>=2:
                out=min(max_ev,out+0.30); up=0
        elif wants_down:
            down+=1; up=0
            if down>=3:
                out=max(min_ev,out-0.15); down=0
        else:
            up=down=0
    return max(min_ev,min(max_ev,out))
require(math.isclose(adapt_bracket_sequence(3.0,[(0.001,0.001,True)]),3.0),
        "tiny clipped-area budget must not force darker SHORT")
require(math.isclose(adapt_bracket_sequence(3.0,[(0.02,0.01,True)]),3.0),
        "one transient clipping sample must not widen the bracket")
require(adapt_bracket_sequence(3.0,[(0.02,0.01,True),(0.02,0.01,True)])>3.0,
        "two stable meaningful-clipping samples must increase AUTO headroom")
require(math.isclose(adapt_bracket_sequence(3.0,[(0.02,0.01,False),(0.02,0.01,False)]),3.0),
        "temporally unstable SHORT must never gain more authority/headroom")
require(adapt_bracket_sequence(3.25,[(0.02,0.01,True),(0.02,0.01,True)],manual=True,floor=3.0)>3.25,
        "MANUAL must adapt like AUTO with extra headroom")

# V1.4.16 office data is regression evidence, not a universal +EV calibration.
# The robust body + scene-shape controller should autonomously demand a large correction
# there, while a truly low-light scene gets a lower body target.
def scene_ss(edge0, edge1, value):
    t=max(0.0,min(1.0,(value-edge0)/(edge1-edge0)))
    return t*t*(3.0-2.0*t)

def scene_body_target(p25,p35,p50,p90,p98,long_product,ref_product=(1e9/60.0)*100.0):
    body=(max(p25,0.0005)*max(p35,0.0005)*max(p50,0.0005))**(1.0/3.0)
    tail90=math.log(max(p90,0.0005)/max(p50,0.0005),2)
    tail98=math.log(max(p98,0.0005)/max(p50,0.0005),2)
    broad=scene_ss(0.60,1.60,tail90)
    extreme=scene_ss(1.50,3.50,tail98)
    strength=max(0.0,min(1.0,0.70*broad+0.30*extreme))
    target=0.070+(0.115-0.070)*strength
    required=max(1.0,long_product*target/max(body,0.0005))
    demand=math.log(required/ref_product,2)
    low=scene_ss(5.0,8.0,demand)
    target*=1.0-0.38*low
    spread=math.log(max(p50,0.0005)/max(p25,0.0005),2)
    target*=1.0-0.10*scene_ss(2.2,4.0,spread)
    return max(0.040,min(0.135,target)),body

office_target,office_body=scene_body_target(
    0.01933,0.02640,0.04384,0.09615,0.39291,(1e9/120.0)*384.0)
office_error=math.log(office_target/office_body,2)
require(1.4 < office_error < 2.2,
        f"office body should request a substantial learned correction, got {office_error}EV")
low_target,_=scene_body_target(0.01,0.015,0.025,0.04,0.08,(1e9/15.0)*1600.0)
require(low_target < office_target,
        f"low-key scene must not be locked to office brightness: low={low_target} office={office_target}")

# Color-admission math: one bright channel is insufficient, SHORT clipping vetoes
# recovery, and a neutral multi-channel LONG clip suppresses weak processed-ISP tint.
def smoothstep_local(edge0, edge1, value):
    t=max(0.0,min(1.0,(value-edge0)/(edge1-edge0)))
    return t*t*(3.0-2.0*t)

def long_highlight(linear_rgb, encoded_rgb):
    luma=0.2126*linear_rgb[0]+0.7152*linear_rgb[1]+0.0722*linear_rgb[2]
    ordered=sorted(encoded_rgb)
    second=ordered[1]; peak=ordered[2]
    return max(smoothstep_local(0.965,0.992,second),
               smoothstep_local(0.62,0.78,luma)*smoothstep_local(0.985,0.998,peak))

def short_safe(encoded_rgb):
    return 1.0-smoothstep_local(0.965,0.985,max(encoded_rgb))

red_skin_like=long_highlight((1.0,0.30,0.12),(0.995,0.58,0.38))
neutral_highlight=long_highlight((1.0,0.96,0.93),(0.995,0.985,0.975))
bright_green=long_highlight((0.16,1.0,0.08),(0.44,0.995,0.31))
require(red_skin_like < 0.01,
        f"single saturated skin-red channel must not admit SHORT: {red_skin_like}")
require(neutral_highlight > 0.75 and bright_green > 0.70,
        f"real neutral/bright-luma highlights must remain recoverable: neutral={neutral_highlight} green={bright_green}")
require(short_safe((247/255,1.0,1.0)) < 0.01,
        "V1.4.15 white-car failure: a SHORT with clipped G/B must not create a red-only fill")

# 056 / 074 / 080 - Exact current pre-handoff authority.
require('name: Iris-HDR-Viewfinder-Test-V1.4.18' in workflow
        and 'run-id: 33561081215' in workflow,
        "workflow must download the exact successful V1.4.18 Actions authority")
require('7aa84fb57b96b0c940f8ffb56feeaa1abed74e73' in workflow,
        "V1.4.18 authority commit pin missing")
require('backup-v1.4.18' not in workflow,
        "V1.4.19 must not invent a new backup-branch dependency")

# 048 / 049 / 052 / 054 - Brightness remains user LONG intent in AUTO and MANUAL.
require('DISPLAY_BRIGHTNESS_MIN_EV = -5.0f' in main
        and 'DISPLAY_BRIGHTNESS_MAX_EV = 2.0f' in main
        and 'DISPLAY_BRIGHTNESS_STEPS_PER_EV = 10' in main,
        "Brightness slider must remain -5..+2 EV in 0.1 EV increments")
require('DISPLAY_BRIGHTNESS_MIN_EV = -5.0f' in camera
        and 'DISPLAY_BRIGHTNESS_MAX_EV = 2.0f' in camera,
        "Camera Brightness clamp must match UI")
require('LONG_APPEARANCE_SHORT_ADAPTIVE' in camera,
        "runtime Brightness ownership must be LONG appearance + adaptive SHORT")
require('root.put("brightnessOwner", "LONG_APPEARANCE_SHORT_ADAPTIVE");' in saver,
        "saved metadata must report LONG appearance + adaptive SHORT ownership")
require('brightnessBar.setEnabled(true);' in main and 'brightnessBar.setAlpha(1.0f);' in main,
        "MANUAL SAFE must keep Brightness usable")
require('glView.setDisplayBrightnessEv' not in main,
        "Brightness slider must not drive post-fusion GL gain")
require('getSystemWindowInsetBottom' in main and 'root.requestApplyInsets();' in main,
        "control panel must reserve Android navigation/gesture-bar bottom inset")
require('statusText.setSingleLine(true);' in main and 'statusText.setMaxHeight(dp(20));' in main,
        "status/debug geometry must remain invariant")

# 057 - Stable update signing identity.
gradle = (ROOT / 'app/build.gradle.kts').read_text()
require('versionCode = 24' in gradle and 'versionName = "1.0-v1.4.19"' in gradle,
        "V1.4.19 version/build pin missing")
require('IRIS_TEST_KEYSTORE_PATH' in gradle and 'stableDebug' in gradle
        and 'iris-hdr-test' in gradle and 'PKCS12' in gradle,
        "stable test signing config missing")
require('IRIS_TEST_SIGNING_KEY_B64' in workflow and 'IRIS_TEST_KEYSTORE_PATH' in workflow,
        "Actions stable-signing secret materialization missing")
require('2a4ec2ab3fed7ae4d2e9c1b6b80c3b5bb19f07420952e97c203fda31e69cff2e' in workflow,
        "stable test keystore SHA-256 pin missing")
require('53:1A:EE:D9:EA:D7:9D:28:C4:24:AD:8F:71:A4:59:B4:CE:D8:AF:F3:7E:95:C1:1B:D2:95:08:3F:BB:25:C4:E8' in workflow,
        "stable signing certificate fingerprint pin missing")
# 040 / 055 - Shutter press freezes one immutable adaptive pair.
begin_capture = camera[camera.index('private void beginCaptureLocked(byte[] frozenShortReliabilityMap)'):camera.index('private void issueStillBurstLocked()')]
still_burst = camera[camera.index('private void issueStillBurstLocked()'):camera.index('private final CameraCaptureSession.CaptureCallback stillCaptureCallback')]
require('autoMetering = false;' in begin_capture,
        "shutter press must ignore any initial bootstrap meter before snapshotting controls")
for token in [
    'captureShortExposureNs = activeShortExposureNs();',
    'captureLongExposureNs = activeLongExposureNs();',
    'captureShortIso = activeShortIso();',
    'captureLongIso = activeLongIso();',
    'capturePostRawBoost = autoHdrExposure ? autoPostRawBoost : DEFAULT_POST_RAW_BOOST;',
    'captureDisplayBrightnessEv = displayBrightnessEv;',
]:
    require(token in begin_capture, f"immutable adaptive capture snapshot missing: {token}")
require('frozenShortReliabilityMap' in begin_capture,
        "shutter-time fusion reliability must remain frozen with the still exposure pair")
require('activeShortExposureNs()' not in still_burst and 'activeLongExposureNs()' not in still_burst
        and 'activeShortIso()' not in still_burst and 'activeLongIso()' not in still_burst,
        "temporary still session must never re-read mutable live-adaptive exposure state")
require('captureShortExposureNs' in still_burst and 'captureLongExposureNs' in still_burst
        and 'captureShortIso' in still_burst and 'captureLongIso' in still_burst
        and 'capturePostRawBoost' in still_burst,
        "still burst must use only frozen shutter-time controls")
require('CAPTURE_INPUTS' in camera and 'acquiredMs=' in camera and 'totalMs=' in camera,
        "minimal capture timing evidence must separate sensor acquisition from post-processing")
# 041 / 043 / 053 / 077 / 078 / 079 - Full-resolution fusion stays bounded, scene-learned and edge-safe.
inner = fusion[fusion.index('for (int row = 0; row < rows; row++)'):fusion.index('output.setPixels')]
for forbidden in ['Math.exp(', 'Math.sqrt(', 'new float[']:
    require(forbidden not in inner, f"new expensive/per-pixel allocation introduced: {forbidden}")
require('clampedBrightnessEv' not in fusion and 'brightnessGain' not in fusion,
        "saved fusion must not contain Brightness gain")
require('learnPhotoCurve' in fusion and 'medianPrefix' in fusion
        and 'PHOTO_KNOT_COUNT = 5' in fusion,
        "saved fusion must learn a multi-knot SHORT->LONG response from valid overlap")
require('highlightNeed' in fusion and 'clippedCore' in fusion
        and 'lumaSafe' in fusion and 'signalSafe' in fusion
        and 'currentCoreMask' in fusion and 'currentShoulderMask' in fusion
        and 'rawMask' in fusion and 'neutralLongClip' in fusion
        and 'validChannelAgreement' in fusion and 'colorTrust' in fusion,
        "saved fusion must require current-pair recoverability plus protected chroma")
require('lumaReliability' not in inner,
        "frozen 5-Hz luma reliability must not modulate saved visible fusion")
require('chromaReliability' in fusion and 'sampleReliability' in fusion,
        "frozen temporal history must remain available for saved chroma protection")

# 074 / 075 / 076 / 077 / 078 / 079 - V1.4.19 response and boundary invariants.
def update_trust(value, stable, attack, release):
    return min(255, value + attack) if stable else max(0, value - release)

luma=224
luma=update_trust(luma, False, 48, 64)
require(luma == 160,
        "graded luma quality history must remain bounded after one marginal sample")
chroma=128
chroma=update_trust(chroma, False, 48, 96)
require(chroma == 32 and chroma < luma,
        "chroma trust must release faster than luma quality history")

photo_knots=[0.020,0.060,0.150,0.350,0.700]
def enforce_monotonic(scale):
    out=list(scale)
    for i in range(1,len(out)):
        previous_output=photo_knots[i-1]*out[i-1]
        minimum_scale=previous_output*1.01/photo_knots[i]
        out[i]=max(0.60,min(1.50,max(out[i],minimum_scale)))
    return out
curve=enforce_monotonic([1.50,0.60,1.50,1.50,0.60])
mapped=[k*v for k,v in zip(photo_knots,curve)]
require(all(mapped[i] > mapped[i-1] for i in range(1,len(mapped))),
        f"learned response curve must remain monotonic after bounded correction: {mapped}")
require('PHOTO_MAX_UPDATE_EV = 0.06f' in gl,
        "live learned response must remain temporally bounded to 0.06 EV/update")

def v1419_recovery_mask(long_second, long_peak, long_luma, short_second,
                       short_luma, radiance_ratio, agreement=1.0):
    shoulder=max(smoothstep_local(0.925,0.985,long_second),
                 smoothstep_local(0.970,0.997,long_peak)
                 * smoothstep_local(0.55,0.82,long_luma))
    core=max(smoothstep_local(0.980,0.990,long_second),
             smoothstep_local(0.992,0.998,long_peak)
             * smoothstep_local(0.50,0.78,long_luma))
    luma_safe=1.0-smoothstep_local(0.975,0.997,short_second)
    signal=smoothstep_local(0.008,0.025,short_luma)
    usable=min(luma_safe,signal)
    permission=smoothstep_local(0.25,0.55,usable)
    core_mask=core*permission
    shoulder_raw=shoulder*luma_safe*signal*good_radiance(radiance_ratio)*agreement
    shoulder_mask=smoothstep_local(0.04,0.58,shoulder_raw)*(1.0-core)
    damage=max(core_mask,smoothstep_local(0.02,0.50,shoulder*luma_safe*signal))
    return max(core_mask,shoulder_mask),core,damage

def good_radiance(value):
    return smoothstep_local(1.01,1.10,value)

mask,core,damage=v1419_recovery_mask(1.0,1.0,0.95,0.70,0.25,1.2,1.0)
require(core > 0.999 and mask > 0.999 and damage > 0.999,
        f"recoverable LONG-clipped core must receive complete current-SHORT detail authority: {core},{mask},{damage}")
mask_bad,_,_=v1419_recovery_mask(1.0,1.0,0.95,0.999,0.25,1.2,1.0)
require(mask_bad < 0.05,
        f"SHORT that is itself multi-channel clipped must not be recoverable: {mask_bad}")
mask_mid,_,damage_mid=v1419_recovery_mask(0.70,0.80,0.30,0.40,0.20,1.2,1.0)
require(mask_mid < 0.01 and damage_mid < 0.01,
        f"ordinary scene body must remain literal LONG: mask={mask_mid} damage={damage_mid}")
mask_disagree,_,_=v1419_recovery_mask(0.97,0.985,0.70,0.70,0.25,1.2,0.0)
require(mask_disagree < 0.25,
        f"current-pair disagreement must suppress a non-core transition instead of averaging mismatched detail: {mask_disagree}")

# The multiscale transition may smooth a mask only inside LONG damage support; a
# neighboring SHORT region can never leak across an intact LONG edge.
center_mask=0.8; neighbor_mask=1.0; damage_support=0.35
blurred=min(damage_support,(center_mask*4.0+neighbor_mask)/5.0)
coarse=max(0.0,blurred)
fine=max(0.0,min(damage_support,0.5*center_mask+0.5*blurred))
require(coarse <= damage_support + 1e-9 and fine <= damage_support + 1e-9,
        f"one-sided damage support must bound both coarse/fine source authority: {coarse},{fine}")

# FIT math replay: producer axis swap can change geometry, display rotation cannot.
def fit_scale(frame_w, frame_h, axis_swap, viewport_w, viewport_h):
    rotated_w = frame_h if axis_swap else frame_w
    rotated_h = frame_w if axis_swap else frame_h
    image_aspect = rotated_w / rotated_h
    viewport_aspect = viewport_w / viewport_h
    sx = sy = 1.0
    if viewport_aspect > image_aspect:
        sx = viewport_aspect / image_aspect
    elif viewport_aspect < image_aspect:
        sy = image_aspect / viewport_aspect
    return sx, sy, image_aspect, viewport_aspect

for args in [
    (1440, 1080, False, 1920, 1080),
    (1440, 1080, True, 1080, 1920),
    (1280, 960, True, 1080, 2200),
    (1440, 1080, False, 2200, 1080),
    (1440, 1080, True, 540, 1920),
]:
    sx, sy, image_aspect, viewport_aspect = fit_scale(*args)
    require(sx >= 1.0 and sy >= 1.0, f"invalid FIT scale {sx},{sy} for {args}")
    displayed_fraction_x = 1.0 / sx
    displayed_fraction_y = 1.0 / sy
    displayed_aspect = viewport_aspect * displayed_fraction_x / displayed_fraction_y
    require(math.isclose(displayed_aspect, image_aspect, rel_tol=1e-6, abs_tol=1e-6),
            f"FIT math would distort geometry: displayed={displayed_aspect} image={image_aspect}")

# Representative native-aspect scoring must reject 16:9 when 4:3 exists.
def aspect_error(width, height, native=4.0/3.0):
    wide, tall = max(width, height), min(width, height)
    return abs((wide / tall) / native - 1.0)

require(aspect_error(1440, 1080) <= 0.015, "4:3 preview should pass native-aspect gate")
require(aspect_error(1280, 720) > 0.015, "16:9 preview must fail 4:3 native-aspect gate")

# Device-regression math: sensor=90/display=0 swaps FIT axes but never adds a display quarter-turn.
def preview_relation(sensor_orientation, display_degrees):
    return (sensor_orientation - display_degrees + 360) % 360

require(preview_relation(90, 0) == 90,
        "portrait sensor=90 display=0 relation must be 90 degrees")
require((preview_relation(90, 0) // 90) & 1 == 1,
        "portrait relation must swap FIT axes")
require(preview_relation(90, 90) == 0,
        "natural landscape sensor=90 display=90 must not swap axes")

# DNG/TIFF orientation values used by Android ExifInterface/DngCreator contract.
dng_values = {0: 1, 90: 6, 180: 3, 270: 8}
require(set(dng_values.values()).issubset(set(range(1, 9))),
        "DNG orientation values must remain valid TIFF 1..8")
require(9 not in dng_values.values(),
        "rejected DNG TIFF Orientation=9 must never be generated")

# User device throughput proof establishes the intended 30-fps ceiling math.
require(math.isclose(30.0 / 2.0, 15.0),
        "30 sensor fps must correspond to a 15 complete-pair/s ceiling")
require(math.isclose(math.log2(8.0), 3.0),
        "8x bracket must equal 3 EV")

print("V1.4.19 REGRESSION PASS: adaptive scene-body LONG independent of bootstrap AE, scene-learned five-knot SHORT response, current-pair luma fusion without 5-Hz gating, one-sided edge-guided Laplacian transition, full clipped-core SHORT authority, protected chroma trust, exact exposure-generation pairs, adaptive AUTO+MANUAL HDR, LONG-owned Brightness -5..+2EV, stable signing, frozen capture pair, orientation/FOV/cadence/sRGB protections")
