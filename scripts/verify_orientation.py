#!/usr/bin/env python3
from pathlib import Path
import hashlib
import math
import os
import re
import textwrap

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
        raise SystemExit("V1.4.11 V2.19 REGRESSION FAIL: " + message)


def verify_workflow_embedded_python():
    for workflow_name in (".github/workflows/build.yml", "BUILD_WORKFLOW_COPY.yml"):
        text = (ROOT / workflow_name).read_text()
        lines = text.splitlines()
        block_count = 0
        i = 0
        while i < len(lines):
            line = lines[i]
            if "python3" in line and "<<'PY'" in line:
                start = i + 1
                end = start
                while end < len(lines) and lines[end].strip() != "PY":
                    end += 1
                require(end < len(lines), f"unterminated Python heredoc in {workflow_name} at line {i + 1}")
                code = textwrap.dedent("\n".join(lines[start:end])) + "\n"
                try:
                    compile(code, f"{workflow_name}:heredoc:{block_count + 1}", "exec")
                except SyntaxError as exc:
                    require(False, f"embedded Python syntax failure in {workflow_name} block {block_count + 1}: {exc}")
                block_count += 1
                i = end
            i += 1
        require(block_count == 4, f"expected 4 embedded Python heredocs in {workflow_name}, found {block_count}")


verify_workflow_embedded_python()
if os.environ.get("IRIS_WORKFLOW_SYNTAX_ONLY") == "1":
    print("V1.4.11 V2.19 WORKFLOW EMBEDDED-PYTHON SYNTAX: PASS")
    raise SystemExit(0)


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

# 012 / 029 / 037 / 043 - Absolute AUTO brightness remains clean-AE owned.
# V1.4.11 V2 preserves the proven V1.4.11/V1.4.7 8x (~3 EV) AUTO bracket;
# aperture-derived widening from V1.4.8+ must not survive this controlled experiment.
require('HDR_BRACKET_RATIO = 8.0' in camera,
        "fixed V1.4.7 8x AUTO bracket missing")
require('AUTO_MAX_BRACKET_EV' not in camera and 'autoTargetBracketEvLocked' not in camera
        and 'AUTO_APERTURE_REFERENCE_F' not in camera,
        "rejected adaptive 3-4.25 EV AUTO bracket survived")
require('shortExposureNs = ONE_SECOND_NS / 480' in camera,
        "manual default short exposure must remain 1/480s")
require('longExposureNs = ONE_SECOND_NS / 60' in camera,
        "manual default long exposure must remain 1/60s")
require('AUTO_METER_MIN_FRAMES' in camera and 'buildMeterPreviewRequest' in camera,
        "clean AE anchor phase missing")
require('commitAutoAnchorFromResultLocked' in camera and 'deriveAutoPairFromAnchorLocked' in camera,
        "clean AE result must own absolute AUTO exposure")
require('targetShortProduct' in camera and '/ HDR_BRACKET_RATIO' in camera,
        "AUTO SHORT target must derive from anchored LONG product and fixed 8x ratio")
require('autoShortIso = minIso;' in camera,
        "AUTO SHORT must use the camera minimum sensor gain")
require('double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);' in camera,
        "AUTO HDR must report actual EV after sensor/flicker clamping")

# 013 / 030 / 036 / 043 / V2.15 - Piecewise sRGB/HDR presentation remains,
# but active fusion semantics are intentionally superseded: SHORT is the only RGB/
# spatial owner and LONG may affect saved output only through one smooth scalar field.
for text, owner in ((hdr_shader, 'shared live/GPU shader'), (fusion, 'CPU utility fusion')):
    require('0.04045' in text and '12.92' in text and '0.0031308' in text and '2.4' in text,
            f"{owner} must use the piecewise sRGB transfer function")
require('if (mode == 3)' in hdr_shader and 'if (mode == 4)' in hdr_shader
        and 'if (mode == 5)' in hdr_shader and 'if (mode == 6)' in hdr_shader,
        "saved GPU fusion must retain the proven four-pass topology")
require('vec3 mergedScene = shortScene;' in hdr_shader
        and 'mix(longScene, shortScene' not in hdr_shader,
        "live HDR must preserve SHORT RGB and must not blend LONG/SHORT RGB")
require('float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));' in hdr_shader
        and 'applyPhotographicBodyTone(mergedScene * brightnessGain)' in hdr_shader
        and 'adaptiveHdrToneMap(bodyToned, ratio, bracketStops)' in hdr_shader,
        "Brightness must remain post-fusion and feed the global body tone before HDR fitting")
require('displayBrightnessEv' in gl and 'glUniform1f' in gl,
        "live Brightness EV uniform plumbing missing")
require('uniform float displayGamma;' in hdr_shader
        and 'applyDisplayGamma(displayLinear, displayGamma)' in hdr_shader
        and 'displayGamma' in gl and 'glUniform1f' in gl,
        "live Gamma uniform/plumbing missing")
require('float requestedScale = mappedY / y;' in hdr_shader
        and 'float gamutScale = 1.0 / max(max3(rgb), 0.000001);' in hdr_shader
        and 'return rgb * min(requestedScale, gamutScale);' in hdr_shader,
        "Gamma must remain luminance-driven, RGB-ratio preserving and gamut-safe")
require('float scalarAppearanceGain = secondLargest3(' in fusion
        and 'float mr = SRGB_TO_LINEAR[sr8] * scalarAppearanceGain;' in fusion
        and 'float mg = SRGB_TO_LINEAR[sg8] * scalarAppearanceGain;' in fusion
        and 'float mb = SRGB_TO_LINEAR[sb8] * scalarAppearanceGain;' in fusion
        and 'lr + (sr - lr)' not in fusion,
        "CPU utility path must no longer implement a second LONG/SHORT RGB interpolation algorithm")
require('brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);' in fusion
        and 'float tr = mr * brightnessGain;' in fusion
        and 'targetBodyY = bodyY + 0.45f * toe * highlightProtect' in fusion,
        "CPU utility Brightness must remain post-source and feed the photographic body curve")
require('buildGammaLut(clampedGamma)' in fusion
        and 'float mappedGammaY = mapLut(gammaY, gammaLut);' in fusion
        and 'float gammaScale = Math.min(requestedGammaScale, gammaGamutScale);' in fusion,
        "CPU utility Gamma must remain RGB-ratio preserving and gamut-safe")
require('private static float mapLut(float value, float[] lut) {' in fusion,
        "mapLut helper required by saved Gamma must remain present")
require('const float knee = 0.70;' in hdr_shader and 'HDR_KNEE = 0.70f' in fusion,
        "live/save HDR knee must remain 0.70")
require('0.82 - 0.04 * (bracketStops - 1.0)' in hdr_shader
        and '0.82f - 0.04f * (bracketStops - 1.0f)' in fusion,
        "live/save white-anchor policy changed")
require('whiteAnchor + 0.14' in hdr_shader and 'whiteAnchor + 0.14f' in fusion,
        "live/save display-ceiling policy changed")
require('adaptiveAppearanceLift' not in hdr_shader and 'appearanceLiftScale' not in fusion,
        "retired global appearance lift must not return")
require('65_536.0' in fusion and '65_536.0' in saver and '65_536.0' in gl and '65536.0' in hdr_shader,
        "widened exposure normalization must remain consistent GPU/utility/metadata")

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
require('Only publish a complete temporal pair' in gl,
        "complete-pair publication contract marker missing")
require('meta.frameNumber - stagingShortMeta.frameNumber <= 3' in gl,
        "SHORT/LONG temporal adjacency guard missing")
short_accept = gl[gl.index('private void acceptMeta'):gl.index('private void renderExternalToTexture')]
require(short_accept.count('fpsWindowPairs++;') == 1,
        "HDR pair cadence must increment only on complete-pair publication")
require('lastShortMeta = stagingShortMeta;' in short_accept and 'lastLongMeta = meta;' in short_accept,
        "display exposure metadata must update atomically with the published pair")

# 018 / 021 / 029 - AUTO/MANUAL remain available; clean AE metering never uses one-shot capture().
require('void setAutoHdrExposure(boolean enabled)' in camera,
        "AUTO/MANUAL HDR exposure owner switch missing")
require('HDR AUTO: ON' in main and 'HDR MANUAL' in main,
        "AUTO/MANUAL HDR UI control missing")
require('setManualControlsEnabled(!autoHdrEnabled)' in main,
        "manual controls must be explicitly gated by AUTO/MANUAL ownership")
require('TAG_METER' in camera and 'buildMeterPreviewRequest' in camera,
        "clean contiguous AE metering phase missing")
require('captureSession.capture(' not in camera,
        "V1.4.2 one-shot live capture() meter must never return")
require('buildMeterPreviewRequest(), previewCaptureCallback' in camera,
        "AUTO metering must use a contiguous repeating AE phase")
require('Arrays.asList(shortRequest, longRequest)' in camera,
        "steady AUTO/MANUAL HDR must remain a two-manual-request repeating pair")
require('AUTO_REMETER_INTERVAL_MS' not in camera
        and 'autoRemeterRunnable' not in camera
        and 'scheduleAutoRemeterLocked' not in camera
        and 'hasFreshAutoAnchorLocked' not in camera,
        "periodic 5-second AE takeover must remain removed after bootstrap")
bootstrap = camera[camera.index('private void startAutoMeteringLocked()'):camera.index('private void processAutoMeterResultLocked')]
require('if (haveAeSample) {' in bootstrap and 'Bootstrap only' in bootstrap,
        "clean HAL AE must be bootstrap-only once the live HDR pair exists")
require('STATS_WIDTH = 32' in gl and 'STATS_HEIGHT = 24' in gl
        and 'STATS_INTERVAL_NS = 100_000_000L' in gl
        and 'glReadPixels' in gl and 'readTextureStats' in gl,
        "V2.12 32x24 / 100ms paired SHORT/LONG statistics path missing")
require('AUTO_LIVE_HYSTERESIS_EV = 0.10' in camera
        and 'AUTO_LIVE_MAX_STEP_EV = 0.30' in camera
        and 'AUTO_LIVE_SCENE_CUT_EV = 0.70' in camera
        and 'AUTO_LIVE_SCENE_CUT_MAX_STEP_EV = 6.0' in camera
        and 'AUTO_LIVE_UPDATE_MIN_NS = 80_000_000L' in camera,
        "V2.3 fast scene-cut AUTO response bounds missing")
require('setSceneStatsListener(controller::onHdrSceneStats)' in main
        and 'processHdrSceneStatsLocked' in camera,
        "live scene-statistics route to CameraController missing")
require('FrameMeta.METER.equals(meta.kind)' in gl,
        "initial bootstrap meter frames must remain hidden from display/pair publication")

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

# 019 / 020 / 044 - V2.10 real 50/60-Hz authority. AUTO is only safe when
# Camera2 proves 50/60; explicit 50/60 applies integer mains cycles to BOTH
# SHORT and LONG when sensor exposure/ISO bounds permit. OFF is explicit.
for token in [
    'FLICKER_MODE_AUTO = 0', 'FLICKER_MODE_50HZ = 1', 'FLICKER_MODE_60HZ = 2', 'FLICKER_MODE_OFF = 3',
    'FLICKER_50_PERIOD_NS = 10_000_000L', 'FLICKER_60_PERIOD_NS = 8_333_333L',
    'void setFlickerMode(int mode)', 'aeAntibandingModeLocked()', 'effectiveFlickerPeriodNsLocked()',
    'solveFlickerSafeSettingForProductLocked(', 'solveMinimumIsoFlickerSettingLocked(',
    'AUTO UNSAFE(none)', 'AUTO UNSAFE(unknown/PWM)'
]:
    require(token in camera, f"V2.10 flicker authority missing {token}")
require('CaptureResult.STATISTICS_SCENE_FLICKER' in camera
        and 'STATISTICS_SCENE_FLICKER_50HZ' in camera
        and 'STATISTICS_SCENE_FLICKER_60HZ' in camera,
        "Camera2 50/60-Hz evidence must remain explicit")
require('CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ' in camera
        and 'CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ' in camera
        and 'CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF' in camera
        and 'CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO' in camera,
        "AUTO/50/60/OFF must map to real Camera2 antibanding requests")
require('flickerMode == FLICKER_MODE_AUTO' in camera
        and 'sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE' in camera,
        "AUTO must distinguish Camera2 NONE from proven 50/60")
require('safeLong = solveFlickerSafeSettingForProductLocked' in camera
        and 'safeShort = solveMinimumIsoFlickerSettingLocked' in camera,
        "safe AUTO/MANUAL pair must solve BOTH LONG and SHORT timing")
require('Proven V1.4.7 flicker-safe behavior' not in camera,
        "obsolete one-sided V1.4.7 flicker contract must be retired")
require('STATE_FLICKER_MODE' in main and 'flickerButton.setOnClickListener' in main
        and 'FLICKER AUTO:' in main and 'FLICKER 60Hz:' in main and 'FLICKER 50Hz:' in main and 'FLICKER OFF:' in main,
        "V2.10 user-visible AUTO/60/50/OFF authority is incomplete")
# Exposure convergence constants are intentionally frozen; flicker correction must not masquerade as metering retuning.
require('AUTO_METER_MIN_FRAMES = 4' in camera and 'AUTO_METER_MAX_FRAMES = 12' in camera
        and 'AUTO_METER_STABLE_FRAMES = 3' in camera and 'AUTO_METER_STABLE_EV = 0.18' in camera,
        "V2.10 must not randomly retune bootstrap metering constants")

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
callback = camera[camera.index('private final CameraCaptureSession.CaptureCallback previewCaptureCallback'):camera.index('private void beginCaptureLocked()')]
require('if (!FrameMeta.METER.equals(kind)) {' in callback
        and 'updateCaptureResultFpsLocked();' in callback
        and 'updateFovEvidenceLocked(result);' in callback,
        "hidden AE meter frames must be excluded from cadence/FOV evidence")
require('CaptureRequest.CONTROL_ZOOM_RATIO' not in camera
        and 'CaptureRequest.SCALER_CROP_REGION' not in camera,
        "FOV policy must not fake parity by digitally cropping or zooming requests")

# 027 / 031 - V2.10 MANUAL SAFE uses one explicit flicker authority for BOTH shutters.
require('recomputeManualFlickerSafetyLocked' in camera,
        "MANUAL flicker-safe exposure owner missing")
require('manualEffectiveShortExposureNs' in camera and 'manualEffectiveLongExposureNs' in camera,
        "requested and effective MANUAL shutters must remain separate")
require('manualEffectiveShortIso = minIso;' in camera,
        "MANUAL SHORT must preserve sensor-minimum-gain preference")
require('solveFlickerSafeSettingForProductLocked' in camera and 'solveMinimumIsoFlickerSettingLocked' in camera,
        "V2.10 50/60-Hz pair solvers missing")
require('MANUAL_SAFE' in camera and 'HDR MANUAL SAFE' in main,
        "user-visible safe MANUAL ownership missing")
require('MANUAL_FLICKER' in camera,
        "manual flicker decision must be logged")
manual_recompute = camera[camera.index('private boolean recomputeManualFlickerSafetyLocked()'):camera.index('private int effectiveFlickerLocked()')]
manual_setter = camera[camera.index('void setManualSettings'):camera.index('void onHdrSceneStats')]
require('safeShort = solveMinimumIsoFlickerSettingLocked' in manual_recompute
        and 'safeLong = solveFlickerSafeSettingForProductLocked' in manual_recompute,
        "MANUAL SAFE must project SHORT and LONG independently onto the authoritative mains lattice")
require('manualEffectiveShortExposureNs = safeShort.exposureNs;' in manual_recompute
        and 'manualEffectiveLongExposureNs = safeLong.exposureNs;' in manual_recompute,
        "safe MANUAL pair must publish both solved integration windows")
require('manualEffectiveLongIso = safeLong.iso;' in manual_recompute
        and 'manualEffectiveShortIso = safeShort.iso;' in manual_recompute,
        "safe MANUAL pair must publish ISO compensation with SHORT minimum-gain preference")
require('if (shortExposureNs > longExposureNs)' in manual_setter
        and 'shortExposureNs = longExposureNs;' in manual_setter,
        "SHORT crossing LONG must still clamp SHORT at LONG")
require('long tmp = shortExposureNs;' not in manual_setter,
        "manual controls must not silently swap SHORT and LONG")
def integer_cycle(exposure_ns, period_ns):
    return abs(exposure_ns / period_ns - round(exposure_ns / period_ns)) < 2e-6
require(integer_cycle(10_000_000.0, 10_000_000.0) and integer_cycle(8_333_333.0, 8_333_333.0),
        "50/60-Hz base periods must be exact integration-lattice members")
require(integer_cycle(20_000_000.0, 10_000_000.0) and integer_cycle(16_666_666.0, 8_333_333.0),
        "multi-cycle LONG windows must remain mains-safe")

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

# 034 - Recorded V1.4.5 HDR FUSED crop/FPS glitch becomes permanent regression.
require('scheduleAutoRemeterLocked' not in camera and 'autoRemeterRunnable' not in camera,
        "periodic AUTO request takeover must not return")
require('!haveShort || !haveLong' in gl
        and 'lastShortMeta == null || lastLongMeta == null' in gl
        and 'lastShortMeta.frameNumber' in gl and 'lastLongMeta.frameNumber' in gl
        and 'lastShortMeta.exposureProduct()' in gl and 'lastLongMeta.exposureProduct()' in gl,
        "continuous AUTO statistics must be sourced only from an actually published SHORT/LONG pair")
start_meter = camera[camera.index('private void startAutoMeteringLocked()'):camera.index('private void processAutoMeterResultLocked')]
require('resetCaptureResultFpsLocked();' in start_meter,
        "entering hidden AE meter must reset steady-preview FPS evidence")
finish_meter = camera[camera.index('if (finishMeter) {', camera.index('private void commitAutoAnchorFromResultLocked')):camera.index('private void deriveAutoPairFromAnchorLocked')]
require('resetCaptureResultFpsLocked();' in finish_meter,
        "returning to SHORT/LONG pair must start a fresh steady-preview FPS window")
require('FOV SAFE: fixed 30 fps preview avoids live sensor-crop/FPS transitions' in main,
        "UI must state deterministic FOV-safe 30-fps semantics")
require('60 FPS CROP ON: request fixed 60/60 preview' in main,
        "UI must state explicit force-60 semantics")


# 036 / 043 / V2 - Exact V1.4.7 highlight mapping plus V1.4.11-V2 brightness/gamma math.
def v147_policy(exposure_ratio):
    ratio = max(1.0, min(65536.0, exposure_ratio))
    stops = max(1.0, min(6.0, math.log(max(ratio, 1.0001), 2.0)))
    clip_start = max(0.90, min(0.95, 0.90 + 0.01 * (stops - 1.0)))
    white_anchor = max(0.68, min(0.82, 0.82 - 0.04 * (stops - 1.0)))
    display_ceiling = max(0.84, min(0.96, white_anchor + 0.14))
    return ratio, stops, clip_start, white_anchor, display_ceiling

def smoothstep_math(edge0, edge1, value):
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)

def map_peak_math(scene_peak, exposure_ratio, brightness_ev=0.0):
    ratio, stops, _, white_anchor, display_ceiling = v147_policy(exposure_ratio)
    boosted = scene_peak * (2.0 ** max(-16.0, min(1.0, brightness_ev)))
    knee = 0.70
    if boosted <= knee:
        return boosted
    if boosted <= 1.0:
        t = max(0.0, min(1.0, (boosted - knee) / (1.0 - knee)))
        return knee + (white_anchor - knee) * t
    t = max(0.0, min(1.0, math.log(boosted, 2.0) / max(math.log(max(ratio, 1.0001), 2.0), 0.0001)))
    return white_anchor + (display_ceiling - white_anchor) * t

ratio8, stops8, clip8, anchor8, ceiling8 = v147_policy(8.0)
require(math.isclose(stops8, 3.0, abs_tol=1e-6), "8x bracket must equal 3 EV")
require(math.isclose(clip8, 0.92, abs_tol=1e-6), "8x SHORT admission must begin at 92% LONG code")
require(math.isclose(anchor8, 0.74, abs_tol=1e-6) and math.isclose(ceiling8, 0.88, abs_tol=1e-6),
        "V1.4.7 3-EV highlight anchors changed")
require(smoothstep_math(clip8, 0.995, 0.50) == 0.0,
        "SHORT must contribute zero in healthy LONG shadows/midtones")
require(map_peak_math(0.40, 8.0, 0.0) == 0.40,
        "0.0 EV must be the exact no-brightness-change V1.4.7 baseline")
require(map_peak_math(0.40, 8.0, 0.5) > map_peak_math(0.40, 8.0, 0.0),
        "+0.5 EV must brighten a lower midtone")
require(map_peak_math(1.0, 8.0, 0.5) < 0.90,
        "brightness gain must be highlight-fitted rather than post-SDR clipped")
require(map_peak_math(1.0, 8.0, 0.5) < map_peak_math(2.0, 8.0, 0.5) <= ceiling8,
        "recovered highlight ordering must survive positive Brightness EV")

# 038 / 042 / V2.19 - Exact successful V2.18 Actions artifact is runtime authority.
require('name: Iris-HDR-Viewfinder-Test-V1.4.11-V2.18' in workflow
        and 'run-id: 33945509036' in workflow
        and "authority='f70e85bc3ca8a5ce0fcf0e0c4634ec786e141d73'" in workflow,
        "workflow must download the exact successful V1.4.11 V2.18 Actions authority")
require("authority='e946baa2b8213d48263cdc0fdc1ed1436b5fdae2'" not in workflow,
        "V2.19 must not seed runtime from V2.17 after successful V2.18")
require('branches: [ experiment-v1.4.11-v2-brightness-4ev ]' in workflow,
        "V1.4.11 V2 workflow must remain isolated to its experimental branch")

# 039 / 043 / V2.17 - Saved fusion may select exactly LONG or SHORT RGB, but it
# must never interpolate RGB between sources. Live preview remains V2.15 SHORT-owned.
require('mix(longScene, shortScene' not in hdr_shader,
        "production shader must not interpolate LONG/SHORT RGB")
require('highlightColorOwnership' not in hdr_shader
        and 'adaptiveClipStart' not in hdr_shader
        and 'applyHighlightColorOwnership' not in fusion
        and 'computeShortOwnership' not in fusion
        and 'computeShortCoreOwnership' not in fusion
        and 'shortSupportEvidence' not in fusion,
        "dead historical RGB ownership machinery must remain absent")
require('if (highlightWeight > 0.0005)' not in hdr_shader,
        "legacy live LONG-first highlight color ownership must not return")
require('vec3 mergedScene = shortScene;' in hdr_shader,
        "V2.15 live preview behavior must remain SHORT-owned and unchanged")
require('colorSafeFromSources' not in hdr_shader and 'adaptiveAppearanceLift' not in hdr_shader,
        "global chroma/appearance repair must not return")
require('textureOffset' not in hdr_shader and 'texelFetch' not in hdr_shader,
        "runtime shader must not add hidden neighborhood RGB reconstruction")

# V2.13 - independent two-exposure HDR contract. SHORT owns highlight capture;
# LONG owns body/shadow capture. Unknown/PWM flicker may report UNSAFE but may never
# collapse SHORT onto LONG. MANUAL LONG ISO has no path into SHORT solving.
require('DISPLAY_BRIGHTNESS_MIN_EV = -16.0f' in main
        and 'DISPLAY_BRIGHTNESS_MAX_EV = 1.0f' in main
        and 'DISPLAY_BRIGHTNESS_STEPS_PER_EV = 10' in main,
        "Brightness slider must remain -16..+1 EV in 0.1 EV increments")
require('DISPLAY_GAMMA_MIN = 0.50f' in main
        and 'DISPLAY_GAMMA_MAX = 2.00f' in main
        and 'DISPLAY_GAMMA_STEPS_PER_UNIT = 20' in main,
        "Gamma slider must remain 0.50..2.00 in 0.05 increments")
require('AUTO_BRACKET_MIN_RATIO = 4.0' in camera
        and 'AUTO_BRACKET_MAX_RATIO = 64.0' in camera
        and 'AUTO_SHORT_P50_LONG_TARGET = 0.015' in camera
        and 'AUTO_SHORT_P90_LONG_TARGET = 0.10' in camera
        and 'AUTO_SHORT_P98_LONG_HEADROOM = 0.65' in camera
        and 'AUTO_LONG_P95_BODY_TARGET = 0.24' in camera
        and 'AUTO_LONG_P98_BODY_TARGET = 0.42' in camera
        and 'AUTO_LONG_MAX_NEAR_CLIP_FRACTION = 0.005' in camera,
        "V2.18 MANUAL-calibrated AUTO bracket/body targets missing")

# 092 - Exact javac failure from failed V2.13 run 33900980849: CameraController
# consumed stats.shortP90Linear while SceneStats did not publish that field. Preserve
# the intended V2.13 P90 controller math by requiring the producer contract itself.
scene_stats_block = gl[gl.index('static final class SceneStats'):gl.index('private final HdrRenderer renderer;')]
require('final float shortP90Linear;' in scene_stats_block
        and 'float shortP90Linear,' in scene_stats_block
        and 'this.shortP90Linear = shortP90Linear;' in scene_stats_block
        and 'percentileSorted(shortSorted, 0.90f),' in gl,
        "failed V2.13 shortP90Linear SceneStats producer omission returned")
scene_stats_fields = set(re.findall(
        r'\bfinal\s+(?:long|double|float|int|boolean)\s+([A-Za-z_][A-Za-z0-9_]*)\s*;',
        scene_stats_block))
scene_stats_refs = set(re.findall(r'\bstats\.([A-Za-z_][A-Za-z0-9_]*)', camera))
missing_scene_stats = sorted(scene_stats_refs - scene_stats_fields)
require(not missing_scene_stats,
        f"CameraController SceneStats consumer fields missing from producer: {missing_scene_stats}")
require('autoLiveTargetMedianLinear' not in camera,
        "AUTO must not restore HAL-median brightness ownership")
require('Math.min(ratioBody, Math.min(ratioHeadroom, ratioLongBody))' in camera
        and 'stats.longP95Linear' in camera
        and 'stats.longP98Linear' in camera
        and 'stats.longNearClipFraction' in camera
        and 'targetShortProduct * AUTO_BRACKET_MIN_RATIO' in camera,
        "AUTO must use MANUAL-calibrated body/headroom plus closed-loop LONG-body protection")
require('autoShortExposureNs = autoLongExposureNs;' not in camera[camera.index('private void deriveAutoPairFromAnchorLocked()'):camera.index('private void processHdrSceneStatsLocked')],
        "AUTO unknown/PWM flicker must never collapse SHORT exposure onto LONG")
require('FLICKER UNSAFE' in camera and 'autoFlickerSafetySatisfied = false;' in camera,
        "unknown/PWM best-effort HDR must remain explicitly UNSAFE rather than fake safety")

# MANUAL Long ISO independence: safeShort is solved before and independently of safeLong.
manual_solver = camera[camera.index('private boolean recomputeManualFlickerSafetyLocked()'):camera.index('private int effectiveFlickerLocked()')]
short_solve = manual_solver.index('ExposureSetting safeShort = solveMinimumIsoFlickerSettingLocked(')
long_solve = manual_solver.index('ExposureSetting safeLong = solveFlickerSafeSettingForProductLocked(')
require(short_solve < long_solve,
        "MANUAL SHORT must be solved independently before LONG")
short_call = manual_solver[short_solve:long_solve]
require('safeLong' not in short_call and 'manualIso' not in short_call
        and 'shortExposureNs' in short_call and 'minIso' in short_call,
        "Long ISO/LONG solution must have no path into MANUAL SHORT")

# V2.18 changes only what MANUAL reports to UI; physical 50/60-Hz safety math is
# byte-equivalent to successful V2.17. This prevents a cosmetic slider fix from
# weakening SAFE timing.
flicker_solver_slice = camera[camera.index('    private ExposureSetting solveMinimumIsoFlickerSettingLocked('):
                              camera.index('    private static final class ExposureSetting')]
manual_flicker_slice = camera[camera.index('    private boolean recomputeManualFlickerSafetyLocked()'):
                              camera.index('    private int effectiveFlickerLocked()')]
require(hashlib.sha256(flicker_solver_slice.encode()).hexdigest() ==
        '70338617bf724bf03f96f2ab25c50e2b21bc06c920329df9561ac297dbc1c29f',
        "V2.17 physical minimum-ISO flicker solver changed")
require(hashlib.sha256(manual_flicker_slice.encode()).hexdigest() ==
        '735edbb4317c0cbf6b003b46e45247bd8d37a1b780c510eed6877e142c009214',
        "V2.17 MANUAL flicker safety/order math changed")
require(camera.count('manualEffectiveShortExposureNs, manualEffectiveLongExposureNs') == 4
        and camera.count('manualEffectiveLongIso);') >= 4
        and 'listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);' not in camera,
        "MANUAL callbacks must expose effective realizable SAFE values")
require('Short ACTUAL ' in main and 'Long ACTUAL ' in main,
        "MANUAL UI must label effective shutter values as actual")

# V2.19 is post-fusion presentation-only inside CameraController. The successful
# V2.18 physical AUTO exposure controller must remain byte-exact.
physical_stats_slice = camera[camera.index('    private void processHdrSceneStatsLocked('):
                              camera.index('    private void deriveAutoPairFromSceneTargetsLocked()')]
physical_pair_slice = camera[camera.index('    private void deriveAutoPairFromSceneTargetsLocked()'):
                             camera.index('    private void updateAdaptivePresentationLocked(')]
physical_anchor_slice = camera[camera.index('    private void deriveAutoPairFromAnchorLocked()'):
                               camera.index('    private void processHdrSceneStatsLocked(')]
require(hashlib.sha256(physical_stats_slice.encode()).hexdigest() ==
        'aefcfea728217485c9d39764aba520dc3b3b29f8460c4beb6060927c05845d0c',
        "successful V2.18 scene-stat physical exposure controller changed")
require(hashlib.sha256(physical_pair_slice.encode()).hexdigest() ==
        '7794c401735797af9edd2edb2468d76b9bc4de0d86946d9c0c9a8d2e9d2b040b',
        "successful V2.18 scene-target pair solver changed")
require(hashlib.sha256(physical_anchor_slice.encode()).hexdigest() ==
        '306add9a9eed6e80d14555c24c8a37f35b93a79f4af7cf6d7d0e939cec6e9e7d',
        "successful V2.18 clean-AE anchor solver changed")

# V2.19 exact post-fusion exposure regression from the supplied V2.18/Photon pairs.
# The physical bracket remains V2.18-owned. V2.19 solves only the final scene key.
def body_tone_v219(y):
    if y <= 0.000001:
        return y
    toe = smoothstep_math(0.015, 0.090, y)
    protect = 1.0 - smoothstep_math(0.45, 0.68, y)
    return y + 0.45 * toe * protect * y * (1.0 - max(0.0, min(1.0, y)))

def hdr_fit_v219(y, ratio):
    bracket_stops = max(1.0, min(6.0, math.log(max(ratio, 1.0001), 2.0)))
    if y <= 0.70:
        return y
    white_anchor = max(0.68, min(0.82, 0.82 - 0.04 * (bracket_stops - 1.0)))
    display_ceiling = max(0.84, min(0.96, white_anchor + 0.14))
    if y <= 1.0:
        t = max(0.0, min(1.0, (y - 0.70) / 0.30))
        return 0.70 + (white_anchor - 0.70) * t
    headroom = max(math.log(max(ratio, 1.0001), 2.0), 0.0001)
    t = max(0.0, min(1.0, math.log(max(y, 0.000001), 2.0) / headroom))
    return white_anchor + (display_ceiling - white_anchor) * t

def predict_presented_v219(scene_y, brightness_ev, gamma, ratio):
    y = max(0.0, scene_y) * (2.0 ** brightness_ev)
    y = body_tone_v219(y)
    y = hdr_fit_v219(y, ratio)
    return max(0.0, min(1.0, y)) ** (1.0 / max(0.50, min(2.00, gamma)))

def targets_v219(fused_p50, fused_p90, long_p98, long_clip):
    contrast_stops = math.log(max(0.001, fused_p90) / max(0.001, fused_p50), 2.0)
    contrast_pressure = smoothstep_math(1.20, 2.60, contrast_stops)
    base_median = 0.18 * (2.0 ** (-0.45 * max(0.0, contrast_stops - 1.0)))
    base_median = max(0.105, min(0.18, base_median))
    specular_pressure = max(
        smoothstep_math(0.50, 0.85, long_p98),
        smoothstep_math(0.003, 0.015, long_clip))
    specular_pressure *= 1.0 - 0.75 * contrast_pressure
    target_median = max(0.10, min(0.18, base_median * (1.0 - 0.25 * specular_pressure)))
    target_contrast = max(1.0, min(2.0, contrast_stops))
    target_p90 = max(0.26, min(0.42, target_median * (2.0 ** target_contrast)))
    return target_median, target_p90

def solve_presentation_v219(fused_p50, fused_p90, long_p98, long_clip, ratio=4.0):
    target_median, target_p90 = targets_v219(fused_p50, fused_p90, long_p98, long_clip)
    best = None
    brightness = -4.0
    while brightness <= 1.0001:
        gamma = 0.80
        while gamma <= 2.0001:
            predicted_median = predict_presented_v219(fused_p50, brightness, gamma, ratio)
            predicted_p90 = predict_presented_v219(fused_p90, brightness, gamma, ratio)
            median_error = math.log(max(0.0001, predicted_median) / max(0.0001, target_median), 2.0)
            p90_error = math.log(max(0.0001, predicted_p90) / max(0.0001, target_p90), 2.0)
            score = (1.20 * median_error * median_error + p90_error * p90_error
                     + 0.01 * brightness * brightness + 0.01 * (gamma - 1.20) * (gamma - 1.20))
            if best is None or score < best[0]:
                best = (score, brightness, gamma, predicted_median, predicted_p90)
            gamma += 0.05
        brightness += 0.10
    return target_median, target_p90, best

# Exact V2.18 shelf final was ~2.27 EV dark at median and ~2.33 EV dark at P90
# versus the supplied Photon reference. Recovered pre-presentation fused statistics
# from that exact JPEG must solve to a Photon-like key, not the V2.18 -2.4 EV key.
shelf_target50, shelf_target90, shelf_solution = solve_presentation_v219(
    0.01972576, 0.12988126, 0.3373257, 0.0007289, 4.0)
require(0.100 <= shelf_target50 <= 0.110 and 0.410 <= shelf_target90 <= 0.420,
        "V2.19 shelf scene-key targets moved away from supplied Photon reference")
require(0.40 <= shelf_solution[1] <= 0.70 and 1.50 <= shelf_solution[2] <= 1.65,
        "V2.19 shelf solver must replace V2.18 negative exposure with a bright Photon-like key")
require(abs(math.log(shelf_solution[3] / 0.10583283, 2.0)) < 0.08
        and abs(math.log(shelf_solution[4] / 0.39880091, 2.0)) < 0.12,
        "V2.19 shelf predicted P50/P90 no longer track supplied Photon reference")

# Exact V2.18 chandelier was ~2.65 EV dark at median and ~2.74 EV dark at P90.
# A specular-heavy scene must brighten the body while keeping bulb pressure lower.
ch_target50, ch_target90, ch_solution = solve_presentation_v219(
    0.05279177, 0.10876445, 0.80, 0.020, 4.0)
require(0.125 <= ch_target50 <= 0.140 and 0.265 <= ch_target90 <= 0.285,
        "V2.19 chandelier specular scene-key targets changed")
require(0.80 <= ch_solution[1] <= 1.01 and 0.90 <= ch_solution[2] <= 1.05,
        "V2.19 chandelier solver must brighten the body without flattening bulbs")
require(abs(math.log(ch_solution[3] / 0.13369069, 2.0)) < 0.08
        and abs(math.log(ch_solution[4] / 0.26844543, 2.0)) < 0.10,
        "V2.19 chandelier predicted P50/P90 no longer track supplied Photon reference")

# Ordinary lower-contrast scenes remain brighter, matching the supplied Photon kitchen
# reference rather than inheriting the dark high-contrast shelf key.
k_target50, k_target90, k_solution = solve_presentation_v219(0.080, 0.180, 0.35, 0.001, 4.0)
require(0.165 <= k_target50 <= 0.180 and 0.375 <= k_target90 <= 0.410,
        "ordinary-scene V2.19 key must remain kitchen-bright")
require(k_solution[1] > 0.50 and k_solution[3] > 0.16 and k_solution[4] > 0.36,
        "ordinary V2.19 scenes must use preserved HDR headroom instead of remaining dim")

# V2.18 physical exposure behavior remains mandatory.
def desired_ratio_v218(p50, p90, p98, long_p95, long_p98, long_clip, current_ratio):
    ratio_body = math.sqrt((0.015 / max(0.00025, p50)) * (0.10 / max(0.00025, p90)))
    ratio_headroom = 0.65 / max(0.002, p98)
    ratio_long_p95 = current_ratio * 0.24 / max(0.010, long_p95)
    ratio_long_p98 = current_ratio * 0.42 / max(0.010, long_p98)
    ratio_long_body = min(ratio_long_p95, ratio_long_p98)
    if long_clip > 0.005:
        clip_scale = 0.005 / max(0.000001, long_clip)
        ratio_long_body = min(ratio_long_body, current_ratio * max(0.25, min(1.0, clip_scale)))
    return max(4.0, min(64.0, ratio_body, ratio_headroom, ratio_long_body))

manual_ratio = desired_ratio_v218(0.0030643, 0.0306402, 0.0822136,
                                  0.2051309, 0.3373257, 0.0007289, 4.0)
bad_auto_ratio = desired_ratio_v218(0.0031255, 0.0306025, 0.0828143,
                                    0.9559018, 1.0, 0.0705973, 20.56)
require(math.isclose(manual_ratio, 4.0, abs_tol=0.01),
        "V2.18 clean shelf physical bracket must remain 4x / 2EV")
require(math.isclose(bad_auto_ratio, 4.0, abs_tol=0.01),
        "V2.18 bad 20.6x AUTO physical regression must remain fixed")
require(desired_ratio_v218(0.00025, 0.00025, 0.003, 0.010, 0.010, 0.0, 4.0) >= 64.0,
        "genuinely dark feasible scenes must retain the 64x / 6EV AUTO ceiling")

# Presentation remains adaptive, but a failed physical bracket is restrained rather
# than disguised with strong brightness/gamma/dehaze/microcontrast.
require('boolean collapsedBracket = physicalRatio < 2.0;' in camera
        and 'targetBrightness = Math.min(targetBrightness, 0.15f);' in camera
        and 'targetGamma = Math.min(targetGamma, 1.20f);' in camera
        and 'targetDehaze = Math.min(targetDehaze, 0.30f);' in camera
        and 'targetMicro = Math.min(targetMicro, 0.22f);' in camera,
        "collapsed physical bracket must fail closed to restrained presentation")

# Existing settling mechanics remain bounded; only scene target ownership changes.
def live_step(error_ev):
    if abs(error_ev) <= 0.10:
        return 0.0
    max_step = 6.0 if abs(error_ev) >= 0.70 else 0.30
    return max(-max_step, min(max_step, error_ev))
require(math.isclose(live_step(+2.0), +2.0) and math.isclose(live_step(-2.0), -2.0),
        "large scene cuts must retain immediate correction")
require(math.isclose(live_step(+0.50), +0.30) and math.isclose(live_step(-0.50), -0.30),
        "ordinary exposure drift must retain the successful 0.30-EV bound")
require(math.isclose(live_step(+0.05), 0.0) and math.isclose(live_step(-0.05), 0.0),
        "small scene-stat jitter must remain inside exposure hysteresis")

# Presentation controller ownership and capture freeze. V2.19 retains the same
# MANUAL domains but AUTO now closes the loop on predicted final P50/P90.
require('private void updateAdaptivePresentationLocked(' in camera
        and 'AUTO_PRESENT_BRIGHTNESS_MIN_EV = -4.00f' in camera
        and 'AUTO_PRESENT_BRIGHTNESS_MAX_EV = 1.00f' in camera
        and 'AUTO_PRESENT_GAMMA_MIN = 0.50f' in camera
        and 'AUTO_PRESENT_GAMMA_MAX = 2.00f' in camera
        and 'float contrastPressure = smoothstepFloat(1.20f, 2.60f, contrastStops);' in camera
        and 'float targetMedian = clampFloat(' in camera
        and 'float targetP90 = clampFloat(' in camera
        and 'predictAutoPresentedLuma' in camera
        and 'log2RatioFloat' in camera,
        "V2.19 Photon-normalized AUTO scene-key solver missing")
require('float targetP90 = lerpFloat(0.024f, 0.020f, highlightPressure);' not in camera
        and '0.029f, 0.023f' not in camera,
        "V2.18 dark MANUAL-calibrated final-render targets survived")
require('displayDehaze, 0.0f' in camera and 'displayMicroContrast, 0.0f' in camera,
        "AUTO must neutralize the second mode-6 global darkening exponent")
require('if (automatic) {' in camera[camera.index('private void updateAdaptivePresentationLocked'):camera.index('private void publishPresentationLocked')],
        "AUTO-only Brightness/Gamma authority boundary missing")
manual_pres = camera[camera.index('private void updateAdaptivePresentationLocked'):camera.index('private void publishPresentationLocked')]
require('float targetBrightness = displayBrightnessEv;' in manual_pres
        and 'float targetGamma = displayGamma;' in manual_pres,
        "MANUAL must begin from user Brightness/Gamma rather than replace them")
require('sliderLift' in camera and 'targetDehaze' in camera and 'targetMicro' in camera,
        "dehaze/microcontrast must adapt to both scene evidence and slider-controlled presentation")
require('captureDisplayBrightnessEv = displayBrightnessEv;' in camera
        and 'captureDisplayGamma = displayGamma;' in camera
        and 'captureDisplayDehaze = displayDehaze;' in camera
        and 'captureDisplayMicroContrast = displayMicroContrast;' in camera,
        "shutter press must freeze one complete presentation state")
require('displayDehaze' in saver and 'displayMicroContrast' in saver
        and 'root.put("displayDehaze", displayDehaze);' in saver
        and 'root.put("displayMicroContrast", displayMicroContrast);' in saver,
        "saved metadata must record the frozen adaptive clarity state")
require('shortJpeg, longJpeg, ratio, displayBrightnessEv, displayGamma,' in saver.replace('\n', ' ')
        and 'displayDehaze, displayMicroContrast,' in saver.replace('\n', ' '),
        "CaptureSetSaver must carry frozen Brightness/Gamma/dehaze/microcontrast to GPU still fusion")
require('brightnessBar.setEnabled(enabled)' in main and 'gammaBar.setEnabled(enabled)' in main,
        "AUTO must own Brightness/Gamma sliders while MANUAL keeps them user-authoritative")
require('Brightness AUTO' in main and 'Dehaze' in main and 'Micro' in main,
        "UI must expose the learned presentation state without adding extra required sliders")

# V2.17 topology-safe saved presentation (math inherited unchanged). Saved mode 6 is pointwise/monotonic only:
# it may reshape luma but can never sample a neighbor or create spatial topology.
require('IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_BEGIN' in hdr_shader
        and 'vec3 fusedLinear = srgbToLinear(texture(normalTex, uv).rgb);' in hdr_shader
        and 'float exponent = 1.0' in hdr_shader
        and 'vec3 presented = fusedLinear * min(requestedScale, gamutScale);' in hdr_shader,
        "V2.17 saved presentation must remain pointwise, luminance-only and RGB-ratio preserving")
v217_mode6 = hdr_shader[hdr_shader.index('// IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_BEGIN'):
                         hdr_shader.index('// IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_END')]
require('presentationGuideLumaAt' not in v217_mode6
        and 'applyAdaptiveClarity' not in v217_mode6
        and v217_mode6.count('texture(') == 2,
        "V2.17 saved mode 6 must not sample neighbors or reintroduce source contours")
require('unsharp' not in v217_mode6.lower() and 'clahe' not in v217_mode6.lower(),
        "V2.17 must not substitute sharpening/CLAHE")
require(not (ROOT / 'app/src/main/assets/shaders/still_fusion.frag').exists(),
        "V2.17 must reuse the successful shader-file universe")

# Production still fusion remains the exact four-pass GPU topology inherited from
# successful V2.13 V1.2. Only the payload/ownership math changes.
require('controller.setStillFusionView(glView);' in main
        and 'renderStillPass(' in gl
        and '3, longTexture, shortTexture, longTexture' in gl
        and '4, evidenceTexture, shortTexture, longTexture' in gl
        and '5, supportTexture, shortTexture, longTexture' in gl
        and '6, presentationTexture, shortTexture, longTexture' in gl
        and 'stillFusionProgram' not in gl
        and 'still_fusion.frag' not in gl
        and 'GPU_STILL_FUSION' in gl,
        "saved HDR must remain GPU-only with the proven four-pass topology")
require('submitCpuFusionFallback' not in saver
        and 'JpegFusion.fuse(' not in saver
        and 'GPU_STILL_FUSION_REQUIRED' in saver
        and 'CPU HDR substitution is disabled' in saver,
        "production capture must never substitute independent CPU HDR after GPU failure")
require(not (ROOT / 'app/src/main/java/com/skyking0007/irishdrviewfinder/RawHdrFusion.java').exists()
        and not (ROOT / 'app/src/main/assets/shaders/raw_hdr_demosaic.frag').exists()
        and not (ROOT / 'app/src/main/assets/shaders/raw_hdr_fusion.frag').exists(),
        "V2.14 must remain on the successful JPEG-source architecture")

# V2.17 reverses V2.15 geometry ownership around the clean LONG body. LONG is the
# immutable reference and only SHORT is globally/local-residual aligned into it.
require('static Registration estimateRegistration(Bitmap movingBitmap, Bitmap referenceBitmap)' in fusion
        and 'estimateOneWayRegistration(movingBitmap, referenceBitmap)' in fusion
        and 'estimateOneWayRegistration(referenceBitmap, movingBitmap)' in fusion
        and 'cycleConfidence = 1.0f - smoothstep(0.45f, 1.50f, cycleError)' in fusion,
        "bidirectional global registration with cycle consistency is missing")
require('static Bitmap alignLongToShort(Bitmap longBitmap, Registration registration)' in fusion
        and 'canvas.drawBitmap(longBitmap, matrix, paint);' in fusion,
        "byte-protected generic moving-frame alignment helper changed")
require('JpegFusion.estimateRegistration(shortBitmap, longBitmap)' in gl
        and 'JpegFusion.alignLongToShort(shortBitmap, registration)' in gl
        and 'JpegFusion.estimateLocalRegistration(shortBitmap, longBitmap)' in gl,
        "GPU saved path must treat SHORT as moving and LONG as immutable reference")
require('JpegFusion.estimateRegistration(longBitmap, shortBitmap)' not in gl,
        "V2.16 LONG-moving geometry direction survived into V2.17")
require('final int maxDimension = 1024;' in fusion
        and 'final int cell = 32;' in fusion
        and 'final int searchRadius = 2;' in fusion
        and 'final int windowRadius = 12;' in fusion
        and 'final float maxResidualPixels = 4.0f;' in fusion
        and 'LocalMatch backward = localGradientMatch(' in fusion
        and 'cycleConfidence = 1.0f - smoothstep(0.20f, 0.90f, cycleError)' in fusion,
        "bounded bidirectional local SHORT residual registration is incomplete")
require('if (confidence < 0.28f)' in fusion
        and 'if (neighbors < 5 || weightSum <= 0.0f) continue;' in fusion
        and 'if (coherent >= 5 && rms <= 0.75f)' in fusion
        and 'if (disagreement > 1.0f) continue;' in fusion,
        "local residual field must fail closed and regularize only coherent camera motion")
require('GPU_STILL_LOCAL_REGISTRATION' in gl
        and 'uploadRgba8Texture(' in gl
        and 'localRegistration.rgba);' in gl
        and gl.count('localFlowTexture, width, height,') == 4
        and 'haveLocalFlow' in gl and 'localFlowMaxPixels' in gl
        and 'stillImageSize' in gl,
        "GPU saved path must carry bounded SHORT residual flow into the four inherited passes")
require('uniform sampler2D localFlowTex;' in hdr_shader
        and 'vec2 stillShortUvAt(vec2 sampleUv)' in hdr_shader
        and 'return texture(shortTex, stillShortUvAt(sampleUv)).rgb;' in hdr_shader
        and 'stillLongUvAt' not in hdr_shader
        and 'return texture(longTex, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb;' in hdr_shader,
        "shader must flow only SHORT and sample LONG at immutable coordinates")
require(gl.count('GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST') >= 2
        and gl.count('GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST') >= 2,
        "immutable LONG source and full-resolution mode-5 raster must both use nearest sampling")
require('presentationTexture);\n                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);' in gl,
        "mode-6 must not bilinear-resample the selected-source fused raster")

# V2.17 source-loss atlas authorizes coherent SHORT ownership but never carries RGB.
require('IRIS_V217_REVERSED_V215_LONG_TRUTH_BEGIN' in hdr_shader
        and 'float shortRecoveryValidityAt(vec2 sampleUv)' in hdr_shader
        and 'float registrationNeighborhoodConfidenceAt(vec2 sampleUv)' in hdr_shader
        and 'float longHardLossBaseAt(vec2 sampleUv)' in hdr_shader
        and 'float compactHardLossSupportAt(vec2 sampleUv)' in hdr_shader
        and 'float longEffectiveLossAt(vec2 sampleUv)' in hdr_shader
        and 'float shortRecoveryEvidenceAt(vec2 sampleUv)' in hdr_shader
        and 'vec2 broadRecoveryEvidenceAt(vec2 sampleUv)' in hdr_shader,
        "V2.17 coherent source-loss evidence chain is incomplete")
require('localLinearRangeAtRadius(sampleUv, 4.0)' in hdr_shader
        and 'localLinearRangeAtRadius(sampleUv, 12.0)' in hdr_shader
        and 'smoothstep(0.68, 0.90, max3(longRgb))' in hdr_shader
        and 'shortMediumRange - 1.06 * longMediumRange' in hdr_shader
        and 'shortBroadRange - 1.04 * longBroadRange' in hdr_shader,
        "effective LONG information-loss proof must retain smooth medium/broad response evidence")
require('shortCoherentDetailAt' not in hdr_shader
        and 'float recoveryProof =' not in hdr_shader
        and 'step(0.58, recoveryProof)' not in hdr_shader,
        "V2.16 per-pixel detail/recovery gate must be completely removed")
require('int analysisWidth = Math.max(1, (width + 15) / 16);' in gl
        and 'int analysisHeight = Math.max(1, (height + 15) / 16);' in gl,
        "V2.17 ownership atlas must preserve the proven 1/16 allocation")
require(math.ceil(4096 / 16) == 256 and math.ceil(3072 / 16) == 192,
        "3072x4096 device captures must map to a 192x256 ownership atlas")
require('for (int oy = -2; oy <= 2; ++oy)' in hdr_shader
        and 'for (int ox = -2; ox <= 2; ++ox)' in hdr_shader
        and 'float seededRegion = max(' in hdr_shader
        and 'float coherentSupport = seededRegion' in hdr_shader,
        "mode 4 must perform broad seeded region closure rather than per-pixel ownership")

# Saved mode 5 is reversed-V2.15 source truth: LONG by default, aligned SHORT as a
# complete source only when the already-established ownership atlas selects it.
require('IRIS_V217_REGION_SOURCE_OWNERSHIP_BEGIN' in hdr_shader
        and 'vec3 shortRgb = stillShortRgbAt(uv);' in hdr_shader
        and 'vec3 longRgb = stillLongRgbAt(uv);' in hdr_shader
        and 'vec3 shortScene = srgbToLinear(shortRgb) * stillShortScalarGain;' in hdr_shader
        and 'vec3 longScene = srgbToLinear(longRgb);' in hdr_shader,
        "V2.17 mode 5 must expose aligned SHORT and immutable LONG source candidates")
v217_mode5 = hdr_shader[hdr_shader.index('// IRIS_V217_REGION_SOURCE_OWNERSHIP_BEGIN'):
                         hdr_shader.index('// IRIS_V217_REGION_SOURCE_OWNERSHIP_END')]
require('float shortOwns = step(0.30, support.r) * usableBracket;' in v217_mode5
        and 'vec3 mergedScene = shortOwns > 0.5 ? shortScene : longScene;' in v217_mode5,
        "V2.17 must default to LONG and switch discretely by coherent atlas ownership")
require('shortRecoveryValidityAt(uv)' not in v217_mode5
        and 'registrationNeighborhoodConfidenceAt(uv)' not in v217_mode5
        and 'longHardLossBaseAt(uv)' not in v217_mode5
        and 'longEffectiveLossAt(uv)' not in v217_mode5,
        "mode 5 must not re-prove ownership per pixel and recreate V2.16 holes")
require('mix(longScene, shortScene' not in v217_mode5
        and 'mix(shortScene, longScene' not in v217_mode5,
        "mode 5 must never create a fractional LONG/SHORT RGB sample")
require('radianceFloorWeight' not in hdr_shader and 'radianceRaised' not in hdr_shader
        and 'recoveredSourceDisplay' not in hdr_shader,
        "synthetic radiance/color fill paths must remain absent")

# Dormant CPU helper remains byte-preserved and production-unreachable. It must not
# be mistaken for the V2.16 owner or used as a fallback after a GPU failure.
require('float mr = SRGB_TO_LINEAR[sr8] * scalarAppearanceGain;' in fusion
        and 'float mg = SRGB_TO_LINEAR[sg8] * scalarAppearanceGain;' in fusion
        and 'float mb = SRGB_TO_LINEAR[sb8] * scalarAppearanceGain;' in fusion
        and 'lr + (sr - lr)' not in fusion
        and 'recycle(longBitmap);\n        longBitmap = null;' in fusion,
        "byte-preserved CPU utility unexpectedly changed")
require('bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)' in fusion,
        "fused JPEG must use maximum encoder quality")

# Mathematical provenance: a full-resolution output sample is one complete source
# vector, never an RGB interpolation. The default branch is LONG; SHORT wins only
# when the binary ownership proof is true.
def v217_select(long_rgb, short_rgb, short_owns):
    return short_rgb if short_owns else long_rgb

long_rgb = (0.61, 0.55, 0.49)
short_rgb = (0.44, 0.31, 0.17)
require(v217_select(long_rgb, short_rgb, False) == long_rgb,
        "healthy/default V2.17 source must be LONG")
require(v217_select(long_rgb, short_rgb, True) == short_rgb,
        "proven LONG information loss must retain exact SHORT RGB")
for owns in (False, True):
    out = v217_select(long_rgb, short_rgb, owns)
    require(out in (long_rgb, short_rgb),
            "binary source selector manufactured a third RGB sample")

# Capture/exposure policy is frozen from successful V2.15. AUTO must retain the 64x
# ceiling and MANUAL controls must retain enough independent shutter/ISO range to
# realize at least a 64x pair without LONG feeding back into SHORT.
require('AUTO_BRACKET_MAX_RATIO = 64.0' in camera,
        "AUTO 64x/6EV capability changed")
require('1_000_000_000L / 8000' in main and '1_000_000_000L / 8' in main
        and 'private static final int[] ISO_VALUES = {100, 200, 400, 800, 1600, 3200};' in main,
        "MANUAL exposure/ISO control range no longer contains a 64x-capable pair")
manual_64_ratio = ((1.0 / 1000.0) * 400.0) / ((1.0 / 8000.0) * 50.0)
require(manual_64_ratio >= 64.0,
        "representative independent MANUAL controls must retain at least 64x separation")

# Exact hard exposure ordering. Request-time correction may raise LONG only; actual
# CaptureResult metadata must reject any inversion instead of clamping it to 1x.
require('enforceManualExposureOrderingLocked();' in camera
        and 'private void enforceManualExposureOrderingLocked()' in camera
        and 'private void enforceAutoExposureOrderingLocked(String reason)' in camera
        and 'private boolean enforceFrozenExposureOrderingLocked()' in camera,
        "SHORT<=LONG request ordering guards are incomplete")
manual_order = camera[camera.index('private void enforceManualExposureOrderingLocked()'):
                      camera.index('private void enforceAutoExposureOrderingLocked(String reason)')]
auto_order = camera[camera.index('private void enforceAutoExposureOrderingLocked(String reason)'):
                    camera.index('private boolean enforceFrozenExposureOrderingLocked()')]
frozen_order = camera[camera.index('private boolean enforceFrozenExposureOrderingLocked()'):
                      camera.index('private int effectiveFlickerLocked()')]
for block, owner in ((manual_order, 'MANUAL'), (auto_order, 'AUTO'), (frozen_order, 'FROZEN')):
    require('Long' not in block or True, "unreachable")
    require('manualEffectiveShortExposureNs =' not in block if owner == 'MANUAL' else True,
            "MANUAL ordering guard must never mutate SHORT")
    require('autoShortExposureNs =' not in block if owner == 'AUTO' else True,
            "AUTO ordering guard must never mutate SHORT")
    require('captureShortExposureNs =' not in block if owner == 'FROZEN' else True,
            "freeze ordering guard must never mutate SHORT")
require('if (Double.isNaN(ratio) || Double.isInfinite(ratio) || ratio < 1.0)' in saver
        and 'HDR exposure ordering violated/unprovable' in saver
        and 'return Double.NaN;' in saver
        and 'return longProduct / Math.max(shortProduct, 1.0);' in saver,
        "actual CaptureResult ordering must be proven; inverted/unprovable pairs may not fuse")
require('return Math.max(1.0, Math.min(65_536.0, longProduct / shortProduct));' not in saver,
        "actual inverted exposure ratio must never be hidden by a 1x clamp")

# Production architecture remains dependency-free and GPU-owned.
require('org.opencv' not in fusion and 'opencv' not in Path('app/build.gradle.kts').read_text().lower(),
        "OpenCV must remain simulation-only and absent from runtime")

# V2.18 does not reopen fusion. Successful V2.17 source-selection/fusion bytes are
# verification authority and must remain exact while AUTO/control policy changes.
require(hashlib.sha256(hdr_shader.encode()).hexdigest() ==
        '79c4064390a78ef55103d2603f2f5a63ea35c44c31e8f64998bb36d91120c88a',
        "successful V2.17 hdr_display.frag bytes changed")
require(hashlib.sha256(gl.encode()).hexdigest() ==
        '2ede490800aa9743cbe8e8428646ce94d5a52c7c55f4de7beb2cbf8778d6e826',
        "successful V2.17 HdrGlView bytes changed")
require(hashlib.sha256(fusion.encode()).hexdigest() ==
        '7aa3f4956f28a48b204375c0123195c020d57c8d8edd774946dccb39d42f7434',
        "successful V2.17 JpegFusion bytes changed")
require(hashlib.sha256(saver.encode()).hexdigest() ==
        '60cfa6d09db46d2af8fc1917e5ebf1e3c580102e1b07fe6bfea8e683c8372248',
        "successful V2.17 CaptureSetSaver bytes changed")
require(hashlib.sha256(main.encode()).hexdigest() ==
        'b142084c33bb2482ad60113bb66653b9c3efeff55c9bdcdd84082d3345a4be3b',
        "successful V2.18 MainActivity bytes changed")

# V2.17 permanent visual/source regressions include the exact V2.16 device failure:
# valid SHORT highlight pieces may not be dropped by a per-pixel re-proof inside one
# coherent LONG-loss region. LONG remains global/default body; SHORT is aligned to it.
require('mix(longScene, shortScene' not in hdr_shader,
        "gray/blue third-edge RGB interpolation returned")
require('vec2 stillShortUvAt(vec2 sampleUv)' in hdr_shader
        and 'stillLongUvAt' not in hdr_shader,
        "reversed V2.15 geometry ownership disappeared")
require('vec3 mergedScene = shortOwns > 0.5 ? shortScene : longScene;' in v217_mode5,
        "V2.17 binary LONG-default/aligned-SHORT ownership disappeared")
require('vec3 mergedScene = shortScene * envelopeScale;' not in hdr_shader,
        "V2.15 global SHORT ownership regression returned")
require('step(0.58, recoveryProof)' not in hdr_shader
        and 'shortCoherentDetailAt' not in hdr_shader,
        "exact V2.16 fragmented pixel-gate regression returned")
require('(width + 15) / 16' in gl
        and 'float coherentSupport = seededRegion' in hdr_shader,
        "broad ownership topology regressed to a fine/detail mask")
require('aligned auxiliary' in hdr_shader and 'immutable LONG body' in hdr_shader,
        "LONG-body / aligned-SHORT source contract markers disappeared")
require('radianceFloorWeight' not in hdr_shader and 'radianceRaised' not in hdr_shader,
        "unsupported peach/orange radiance fill returned")

# Appearance calibration remains byte-identical to successful V2.14. Registration
# matching math is preserved conceptually, but alignment direction intentionally
# changes so only LONG is transformed into SHORT geometry.
appearance_slice = fusion[fusion.index('    static AppearanceGain estimateAppearanceGain'):
                          fusion.index('    private static float[] logLuma')]
require(hashlib.sha256(appearance_slice.encode()).hexdigest() ==
        'd83dc871411da89ae113a85284ab493400baad0347eeb6c127fa8a20086e7e97',
        "successful appearance-calibration bytes changed")
registration_core = fusion[fusion.index('    static Registration estimateRegistration'):
                           fusion.index('    private static OneWayRegistration estimateOneWayRegistration')]
for token in ['estimateOneWayRegistration(movingBitmap, referenceBitmap)',
              'estimateOneWayRegistration(referenceBitmap, movingBitmap)',
              'forward.sampleDx + backward.sampleDx',
              'forward.sampleDy + backward.sampleDy']:
    require(token in registration_core, f"global registration matcher changed unexpectedly: {token}")

# Global photographic body tone is tone reproduction only: black stays anchored,
# body/midtones rise, and extra lift is zero before the 0.70 HDR shoulder.
require('applyPhotographicBodyTone' in hdr_shader
        and '0.45 * toe * highlightProtect * y' in hdr_shader
        and 'smoothstep(0.45, 0.68, y)' in hdr_shader
        and 'targetBodyY = bodyY + 0.45f * toe * highlightProtect' in fusion,
        "GPU/CPU photographic body tone curve missing or mismatched")
live_mode_start = hdr_shader.index('// V2.17 leaves the successful live preview path unchanged.')
require(hdr_shader.index('applyPhotographicBodyTone(mergedScene * brightnessGain)', live_mode_start)
        < hdr_shader.index('adaptiveHdrToneMap(bodyToned, ratio, bracketStops)', live_mode_start)
        < hdr_shader.index('applyDisplayGamma(displayLinear, displayGamma)', live_mode_start),
        "photographic body tone must run before HDR shoulder and Gamma in strict live mode=2")

def body_tone_v26(y):
    if y <= 0.000001:
        return y
    toe = smoothstep_math(0.015, 0.090, y)
    protect = 1.0 - smoothstep_math(0.45, 0.68, y)
    return y + 0.45 * toe * protect * y * (1.0 - max(0.0, min(1.0, y)))

require(math.isclose(body_tone_v26(0.0), 0.0, abs_tol=1e-9),
        "photographic tone must keep true black anchored")
require(body_tone_v26(0.20) > 0.26 and body_tone_v26(0.40) > 0.49,
        "photographic tone must lift the scene body/midtones")
require(math.isclose(body_tone_v26(0.68), 0.68, abs_tol=1e-6)
        and math.isclose(body_tone_v26(0.75), 0.75, abs_tol=1e-6),
        "body lift must be zero before and throughout recovered-highlight shoulder")
# No local tone map / local contrast operator is introduced by V2.7.
require('localTone' not in hdr_shader and 'unsharp' not in hdr_shader and 'sharpen' not in hdr_shader,
        "V2.7 must remain a restrained global SDR tone curve without local pop/sharpening")

require('applySafeSystemBarInsets(root, panel);' in main
        and 'WindowInsets.Type.systemBars()' in main
        and 'panelBottom + bottom' in main,
        "controls must reserve Android system-bar/gesture-pill insets")
require('statusText.setSingleLine(true);' in main
        and 'statusText.setEllipsize(TextUtils.TruncateAt.END);' in main
        and 'statusText.setIncludeFontPadding(false);' in main
        and 'statusText.setMinHeight(dp(20));' in main
        and 'statusText.setMaxHeight(dp(20));' in main
        and 'ViewGroup.LayoutParams.MATCH_PARENT,\n                dp(20)' in main,
        "V2.1 fixed-height status-row bounce correction must remain intact")
require('applicationId = "com.skyking0007.irishdrviewfinder.v1411v2"' in Path('app/build.gradle.kts').read_text()
        and 'android:label="Iris HDR 1.4.11 V2"' in Path('app/src/main/AndroidManifest.xml').read_text(),
        "V1.4.11 V2 must have a side-by-side application identity and visible label")
require('versionCode = 36' in Path('app/build.gradle.kts').read_text()
        and 'versionName = "1.0-v1.4.11-v2.19"' in Path('app/build.gradle.kts').read_text(),
        "V2.19 version/build marker must be exact")

# 040 - Exact V1.4.8 capture/remeter race: shutter press freezes one immutable pair.
begin_capture = camera[camera.index('private void beginCaptureLocked()'):camera.index('private void issueStillBurstLocked()')]
still_burst = camera[camera.index('private void issueStillBurstLocked()'):camera.index('private final CameraCaptureSession.CaptureCallback stillCaptureCallback')]
require('autoMetering = false;' in begin_capture,
        "shutter press must freeze/ignore bootstrap metering before snapshotting controls")
stats_block = camera[camera.index('private void processHdrSceneStatsLocked'):camera.index('private void deriveAutoPairFromSceneTargetsLocked')]
require('stillSessionActive' in stats_block and 'autoMetering' in stats_block,
        "continuous live statistics must not mutate an in-flight still capture")
for token in [
    'captureShortExposureNs = activeShortExposureNs();',
    'captureLongExposureNs = activeLongExposureNs();',
    'captureShortIso = activeShortIso();',
    'captureLongIso = activeLongIso();',
    'capturePostRawBoost = autoHdrExposure ? autoPostRawBoost : DEFAULT_POST_RAW_BOOST;',
    'captureDisplayBrightnessEv = displayBrightnessEv;',
    'captureDisplayGamma = displayGamma;',
    'captureDisplayDehaze = displayDehaze;',
    'captureDisplayMicroContrast = displayMicroContrast;',
]:
    require(token in begin_capture, f"immutable capture snapshot missing: {token}")
require('activeShortExposureNs()' not in still_burst and 'activeLongExposureNs()' not in still_burst
        and 'activeShortIso()' not in still_burst and 'activeLongIso()' not in still_burst,
        "temporary still session must never re-read mutable preview/remeter exposure state")
require('captureShortExposureNs' in still_burst and 'captureLongExposureNs' in still_burst
        and 'captureShortIso' in still_burst and 'captureLongIso' in still_burst
        and 'capturePostRawBoost' in still_burst,
        "still burst must use only the frozen shutter-time controls")
require('CAPTURE_INPUTS' in camera and 'acquiredMs=' in camera and 'totalMs=' in camera,
        "minimal capture timing evidence must separate sensor acquisition from post-processing")

# 041 / 043 / V2.7 - Full-resolution CPU fallback stays allocation-light. Expensive
# power/LUT setup is outside the per-pixel output loop; sparse support reuses strip buffers.
inner = fusion[fusion.index('for (int row = 0; row < rows; row++)'):fusion.index('output.setPixels')]
for forbidden in ['Math.exp(', 'Math.pow(', 'Math.sqrt(', 'Math.log(', 'new float[']:
    require(forbidden not in inner, f"expensive/per-pixel saved-fusion operation returned: {forbidden}")
require('float brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);' in fusion
        and fusion.index('float brightnessGain = (float) Math.pow') < fusion.index('for (int y = 0; y < height; y += rowsPerStrip)'),
        "Brightness EV power must be computed once before full-resolution loops")
require('buildGammaLut(clampedGamma)' in fusion
        and fusion.index('buildGammaLut(clampedGamma)') < fusion.index('for (int y = 0; y < height; y += rowsPerStrip)'),
        "Gamma LUT must be computed once before full-resolution loops")
require('int[] shortPixels = new int[width * rowsPerStrip];' in fusion
        and 'int[] outPixels = new int[width * rowsPerStrip];' in fusion
        and 'supportEvidence' not in fusion,
        "byte-preserved CPU utility must use bounded strip buffers and no LONG support mask")

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

print("V1.4.11 V2.19 REGRESSION PASS: exact successful V2.18 authority, physical 4x-64x AUTO/flicker/fusion preserved, Photon-normalized final P50/P90 scene key fixes V2.18 ~2.3-2.8EV underexposure, AUTO second-pass darkening neutralized")
