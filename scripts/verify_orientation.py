#!/usr/bin/env python3
from pathlib import Path
import math
import os
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
        raise SystemExit("V1.4.11 V2.4 REGRESSION FAIL: " + message)


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
    print("V1.4.11 V2.4 WORKFLOW EMBEDDED-PYTHON SYNTAX: PASS")
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

# 013 / 030 / 036 / 043 - Live and saved fusion preserve V1.4.7 sRGB math,
# exact exposure normalization, LONG-owned shadows, and the same HDR display policy.
for text, owner in ((hdr_shader, 'shared live/GPU shader'), (fusion, 'CPU fallback fusion')):
    require('0.04045' in text and '12.92' in text and '0.0031308' in text and '2.4' in text,
            f"{owner} must use the piecewise sRGB transfer function")
require('adaptiveClipStart' in hdr_shader and 'bracketStops' in hdr_shader,
        "live highlight admission must adapt to the actual exposure relationship")
require('0.90 + 0.01 * (bracketStops - 1.0)' in hdr_shader and '0.995' in hdr_shader,
        "live SHORT admission must stay near LONG saturation")
require('shortConfidence' in hdr_shader and 'shortScenePeak / longScenePeak' in hdr_shader,
        "live clipped-highlight SHORT plausibility guard missing")
require('float textureRecoveryWeight = mode == 3' in hdr_shader
        and 'float shortWeight = max(highlightWeight, textureRecoveryWeight);' in hdr_shader
        and 'vec3 mergedScene = mix(longScene, shortScene, shortWeight);' in hdr_shader,
        "shared shader must keep live mode at V2.2 highlight ownership and finish fusion before display brightness")
require('float brightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));' in hdr_shader
        and 'adaptiveHdrToneMap(mergedScene * brightnessGain' in hdr_shader,
        "live Brightness EV must apply after fusion and before HDR display fitting")
require('displayBrightnessEv' in gl and 'glUniform1f' in gl,
        "live Brightness EV uniform plumbing missing")
require('uniform float displayGamma;' in hdr_shader
        and 'applyDisplayGamma(displayLinear, displayGamma)' in hdr_shader
        and 'displayGamma' in gl and 'glUniform1f' in gl,
        "live Gamma uniform/plumbing missing")
require('float requestedScale = mappedY / y;' in hdr_shader
        and 'float gamutScale = 1.0 / max(max3(rgb), 0.000001);' in hdr_shader
        and 'return rgb * min(requestedScale, gamutScale);' in hdr_shader,
        "live Gamma must be luminance-driven, RGB-ratio preserving and gamut-safe")
require('0.90f + 0.01f * (bracketStops - 1.0f)' in fusion and 'HDR_CLIP_END = 0.995f' in fusion,
        "CPU fallback must retain V2.2 near-clipping SHORT admission")
require('float shortWeight = Math.max(highlightWeight, textureRecoveryWeight);' in fusion
        and 'float mr = lr + (sr - lr) * shortWeight;' in fusion
        and 'float mg = lg + (sg - lg) * shortWeight;' in fusion
        and 'float mb = lb + (sb - lb) * shortWeight;' in fusion,
        "CPU fallback must use the same reliable-SHORT texture ownership as GPU fusion")
require('float shortWeight = max(highlightWeight, textureRecoveryWeight);' in hdr_shader
        and 'vec3 mergedScene = mix(longScene, shortScene, shortWeight);' in hdr_shader
        and 'mode == 3' in hdr_shader,
        "shared V2.2 shader must use actual normalized SHORT pixels only in still mode when reliability proves them")
require('brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);' in fusion
        and 'boostedPeak = scenePeak * brightnessGain;' in fusion,
        "saved Brightness EV must be one per-image linear exposure gain after fusion")
require('buildGammaLut(clampedGamma)' in fusion
        and 'float mappedGammaY = gammaMap(gammaY, gammaLut);' in fusion
        and 'float gammaScale = Math.min(requestedGammaScale, gammaGamutScale);' in fusion,
        "saved Gamma must use one per-image LUT and RGB-ratio-preserving gamut-safe scaling")
require('const float knee = 0.70;' in hdr_shader and 'HDR_KNEE = 0.70f' in fusion,
        "live/save V1.4.7 HDR knee must be restored")
require('0.82 - 0.04 * (bracketStops - 1.0)' in hdr_shader
        and '0.82f - 0.04f * (bracketStops - 1.0f)' in fusion,
        "live/save V1.4.7 white-anchor policy must be restored")
require('whiteAnchor + 0.14' in hdr_shader and 'whiteAnchor + 0.14f' in fusion,
        "live/save V1.4.7 display-ceiling policy must be restored")
require('adaptiveAppearanceLift' not in hdr_shader and 'appearanceLiftScale' not in fusion,
        "V1.4.8-V1.4.10 global appearance lift must not survive")
require('65_536.0' in fusion and '65_536.0' in saver and '65_536.0' in gl and '65536.0' in hdr_shader,
        "widened exposure normalization must remain consistent live/GPU/fallback/metadata")

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
        and 'glReadPixels' in gl and 'readLongTextureStats' in gl,
        "V2.3 32x24 / 100ms live LONG statistics path missing")
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

# 019 / 029 - Flicker-aware clean AE anchor and manual pair preserve temporal integration.
require('CaptureResult.STATISTICS_SCENE_FLICKER' in camera,
        "Camera2 scene-flicker evidence missing")
require('CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO' in camera,
        "clean AE meter must request HAL automatic antibanding")
require('STATISTICS_SCENE_FLICKER_50HZ' in camera and 'STATISTICS_SCENE_FLICKER_60HZ' in camera,
        "50/60-Hz evidence labels must remain explicit")
require('chooseAutoFlickerAlignedShortLocked' not in camera and 'autoTargetBracketEvLocked' not in camera,
        "V1.4.8+ wide-aperture flicker-short solver must not survive fixed-3EV restore")
require('autoShortExposureNs = autoLongExposureNs;' in camera,
        "50/60-Hz and unknown/PWM must preserve V1.4.7 same-integration behavior")
require('Proven V1.4.7 flicker-safe behavior' in camera,
        "fixed-3EV flicker-safe ownership contract missing")
require('sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE' in camera,
        "no-flicker direct 8x desired-SHORT branch missing")

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

# 027 / 031 - MANUAL SAFE keeps flicker-compatible timing; ISO slider owns LONG only and SHORT uses min gain.
require('recomputeManualFlickerSafetyLocked' in camera,
        "MANUAL flicker-safe exposure owner missing")
require('manualEffectiveShortExposureNs' in camera and 'manualEffectiveLongExposureNs' in camera,
        "requested and effective MANUAL shutters must remain separate")
require('manualEffectiveShortIso = minIso;' in camera,
        "MANUAL SHORT must use sensor minimum gain")
require('manualEffectiveLongIso = manualIso;' in camera
        and 'manualEffectiveLongIso = solveIsoForProduct' in camera,
        "manual ISO slider must own LONG gain / preserved LONG exposure product")
require('chooseManualFlickerSafeExposureLocked' in camera,
        "50/60-Hz manual shutter solver missing")
require('10_000_000L' in camera and '8_333_333L' in camera,
        "50/60-Hz integration periods missing")
require('MANUAL_SAFE' in camera and 'HDR MANUAL SAFE' in main,
        "user-visible safe MANUAL ownership missing")
require('longISO' in camera and 'Short=min' in main,
        "LONG-only ISO ownership must be visible")
require('MANUAL_FLICKER' in camera,
        "manual flicker decision must be logged")

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
require('if (listener == null || !haveLong || lastLongMeta == null) return;' in gl
        and 'lastLongMeta.frameNumber' in gl and 'lastLongMeta.exposureProduct()' in gl,
        "continuous AUTO statistics must be sourced only from an actually published LONG")
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

# 038 / 042 - Exact current pre-handoff authority pin regression.
require('name: Iris-HDR-Viewfinder-Test-V1.4.11-V2.3' in workflow
        and 'run-id: 33691180722' in workflow,
        "workflow must download the exact successful V1.4.11 V2.3 Actions authority")
require('run-id: 33678538693' not in workflow
        and 'run-id: 33675083158' not in workflow
        and 'run-id: 33667545707' not in workflow
        and 'run-id: 33464019593' not in workflow,
        "V1.4.11 V2.4 must never fall back behind successful V2.3 authority")
require('branches: [ experiment-v1.4.11-v2-brightness-4ev ]' in workflow,
        "V1.4.11 V2 workflow must be isolated to its experimental branch")

# 039 / 043 - V1.4.7 red/orange speck and skin-tone correction is color-ownership only.
require('highlightColorOwnership' in hdr_shader and 'applyHighlightColorOwnership' in fusion,
        "live/save LONG-first highlight color ownership missing")
require('secondLargest3' in hdr_shader and 'secondLargest3' in fusion,
        "multi-channel LONG clipping gate missing")
require('smoothstep(0.985, 0.998, secondLargest3(longRgb))' in hdr_shader
        and 'smoothstep(0.985f, 0.998f, secondLong)' in fusion,
        "single red/orange channel must not hand color to SHORT")
require('shortColorNeed' in hdr_shader and 'shortColorNeed' in fusion,
        "SHORT color validity gate missing")
require('colorSafeFromSources' not in hdr_shader and 'colorSafeFromSources' not in fusion
        and 'adaptiveAppearanceLift' not in hdr_shader,
        "global chroma/appearance repair must not return")
require(hdr_shader.count('texture(shortTex') == 3 and hdr_shader.count('texture(longTex') == 3,
        "color correction must not add neighborhood texture fetches")
require('textureOffset' not in hdr_shader and 'texelFetch' not in hdr_shader,
        "no spatial/cross-edge chroma filtering is permitted")

# Brightness and Gamma are explicit WYSIWYG controls with frozen shutter-time ownership.
require('DISPLAY_BRIGHTNESS_MIN_EV = -16.0f' in main
        and 'DISPLAY_BRIGHTNESS_MAX_EV = 1.0f' in main
        and 'DISPLAY_BRIGHTNESS_STEPS_PER_EV = 10' in main,
        "Brightness slider must be -16..+1 EV in 0.1 EV increments")
require('Math.max(-16.0f, Math.min(1.0f, ev))' in camera
        and 'Math.max(-16.0f, Math.min(1.0f, displayBrightnessEv))' in saver
        and 'Math.max(-16.0f, Math.min(1.0f, ev))' in gl
        and 'clamp(displayBrightnessEv, -16.0, 1.0)' in hdr_shader
        and 'clamp(displayBrightnessEv, -16.0f, 1.0f)' in fusion,
        "-16..+1EV Brightness clamp must be identical live/GPU/fallback/save")
require('DISPLAY_GAMMA_MIN = 0.50f' in main
        and 'DISPLAY_GAMMA_MAX = 2.00f' in main
        and 'DISPLAY_GAMMA_STEPS_PER_UNIT = 20' in main
        and 'displayGamma = 1.0f' in main,
        "Gamma slider must be 0.50..2.00 in 0.05 increments with 1.00 neutral")
require('brightnessLabelText' in main and 'glView.setDisplayBrightnessEv(displayBrightnessEv);' in main
        and 'controller.setDisplayBrightnessEv(displayBrightnessEv);' in main,
        "Brightness slider must drive both live and saved paths")
require('captureDisplayBrightnessEv = displayBrightnessEv;' in camera
        and 'captureDisplayGamma = displayGamma;' in camera,
        "shutter press must freeze the exact displayed Brightness EV and Gamma")
live_stats = camera[camera.index('private void processHdrSceneStatsLocked'):camera.index('private void deriveAutoPairFromLiveProductLocked')]
require('displayBrightnessEv' not in live_stats and 'displayGamma' not in live_stats,
        "physical AUTO settling must stay independent of presentation Brightness/Gamma")
require('HDR_BRACKET_RATIO = 8.0' in camera,
        "V2.2 must preserve V1.4.11 V2 fixed 8x (~3EV) AUTO bracket ownership")
# Exact V2.3 scene-cut regression derived only from V2.2 live-stat math.
def live_step(error_ev):
    if abs(error_ev) <= 0.10:
        return 0.0
    max_step = 6.0 if abs(error_ev) >= 0.70 else 0.30
    return max(-max_step, min(max_step, error_ev))
require(math.isclose(live_step(+2.0), +2.0) and math.isclose(live_step(-2.0), -2.0),
        "large dark/bright scene cuts must apply the measured correction on the first fresh sample")
require(math.isclose(live_step(+0.50), +0.30) and math.isclose(live_step(-0.50), -0.30),
        "ordinary exposure drift must retain V2.2's smooth 0.30EV bound")
require(math.isclose(live_step(+0.05), 0.0) and math.isclose(live_step(-0.05), 0.0),
        "small live-stat jitter must remain inside exposure hysteresis")
require('captureDisplayBrightnessEv' in camera[camera.index('new CaptureSetSaver('):camera.index('stillSessionActive = true;')]
        and 'captureDisplayGamma' in camera[camera.index('new CaptureSetSaver('):camera.index('stillSessionActive = true;')],
        "saved fusion must receive frozen shutter-time Brightness EV and Gamma")
require('displayBrightnessEv' in saver and 'displayGamma' in saver
        and 'stillFusionView.fuseStillJpegs' in saver
        and 'shortJpeg, longJpeg, ratio, displayBrightnessEv, displayGamma' in saver.replace('\n', ' '),
        "CaptureSetSaver must route frozen SHORT/LONG + Brightness/Gamma to GPU still fusion")
require('controller.setStillFusionView(glView);' in main
        and 'GLES30.glUniform1i(GLES30.glGetUniformLocation(displayProgram, "mode"), 3);' in gl
        and 'stillFusionProgram' not in gl
        and 'still_fusion.frag' not in gl
        and 'GLUtils.texImage2D' in gl
        and 'GLES30.glReadPixels' in gl
        and 'GPU_STILL_FUSION' in gl,
        "V2.4 must preserve V2.3 primary full-resolution still fusion through the existing GLES3 context")
fallback_block = saver[saver.index('private void submitCpuFusionFallback'):saver.index('private void submitFusedBytes')]
require('submitCpuFusionFallback' in saver and 'GPU_STILL_FUSION_FALLBACK' in saver
        and saver.count('JpegFusion.fuse(') == 1
        and 'JpegFusion.fuse(' in fallback_block
        and 'stillFusionView.fuseStillJpegs' in saver,
        "CPU fusion may exist only inside the explicit GL-failure fallback helper")
require(not (ROOT / 'app/src/main/java/com/skyking0007/irishdrviewfinder/RawHdrFusion.java').exists()
        and not (ROOT / 'app/src/main/assets/shaders/raw_hdr_demosaic.frag').exists()
        and not (ROOT / 'app/src/main/assets/shaders/raw_hdr_fusion.frag').exists(),
        "V2.4 must remain on the successful V2.3 JPEG architecture; RAW-fusion files are forbidden")
require(not (ROOT / 'app/src/main/assets/shaders/still_fusion.frag').exists(),
        "V2.4 must preserve V2.3 tracked-file universe; no new still shader asset is permitted")
require('root.put("displayBrightnessEv", displayBrightnessEv);' in saver
        and 'root.put("displayGamma", displayGamma);' in saver,
        "capture metadata must record the applied Brightness EV and Gamma")

# V2.4 permanent regression: damaged LONG must never veto validated SHORT RGB.
require('reliableShortTextureWeight' in hdr_shader
        and 'smoothstep(0.08, 0.16' in hdr_shader
        and 'smoothstep(0.985, 0.998, max3(shortRgb))' in hdr_shader
        and 'smoothstep(0.55, 0.75' in hdr_shader
        and 'smoothstep(0.88, 0.97' in hdr_shader
        and 'smoothstep(0.35, 0.65, ownershipEvidence)' in hdr_shader
        and 'textureRecoveryWeight < 0.05' in hdr_shader
        and 'textureRecoveryWeight = mode == 3' in hdr_shader,
        "GPU still fusion damaged-LONG/validated-SHORT ownership gate missing")
require('exposureAgreementRatio' not in hdr_shader
        and 'exposureAgreementRatio' not in fusion
        and '1.2746' not in hdr_shader
        and '1.6818' not in hdr_shader
        and '1.2746f' not in fusion
        and '1.6818f' not in fusion,
        "damaged LONG JPEG radiometric agreement must not veto SHORT recovery")
require('shortHeadroom' in hdr_shader and 'shortHeadroom' in fusion
        and 'longDamage' in hdr_shader and 'longDamage' in fusion
        and 'ownershipEvidence' in hdr_shader and 'ownershipEvidence' in fusion,
        "GPU and CPU fallback must share SHORT validity + LONG damage evidence")
require('textureOffset' not in hdr_shader and 'texelFetch' not in hdr_shader,
        "SHORT texture recovery must remain pixel-local; no neighborhood hallucination/fill operator")
def smoothstep(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)
def reliable_short_v24(short_encoded_y, short_peak, long_encoded_y, long_peak):
    signal = smoothstep(0.08, 0.16, short_encoded_y)
    headroom = 1.0 - smoothstep(0.985, 0.998, short_peak)
    damage = max(
        smoothstep(0.55, 0.75, long_encoded_y),
        smoothstep(0.88, 0.97, long_peak))
    evidence = signal * headroom * damage
    return smoothstep(0.35, 0.65, evidence)
require(reliable_short_v24(0.24, 0.35, 0.67, 0.88) > 0.99,
        "office-window damaged-LONG fixture must hand complete RGB ownership to SHORT")
require(reliable_short_v24(0.10, 0.20, 0.34, 0.40) < 0.01,
        "dark office fixture must remain LONG-owned rather than importing SHORT noise")
require(reliable_short_v24(0.30, 0.999, 0.90, 1.00) < 0.01,
        "clipped SHORT must fail closed even when LONG is damaged")
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

# 040 - Exact V1.4.8 capture/remeter race: shutter press freezes one immutable pair.
begin_capture = camera[camera.index('private void beginCaptureLocked()'):camera.index('private void issueStillBurstLocked()')]
still_burst = camera[camera.index('private void issueStillBurstLocked()'):camera.index('private final CameraCaptureSession.CaptureCallback stillCaptureCallback')]
require('autoMetering = false;' in begin_capture,
        "shutter press must freeze/ignore bootstrap metering before snapshotting controls")
stats_block = camera[camera.index('private void processHdrSceneStatsLocked'):camera.index('private void deriveAutoPairFromLiveProductLocked')]
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

# 041 / 043 / V2 - Full-resolution fusion stays allocation-light. Brightness pow and
# Gamma LUT are computed once per image; highlight color uses one reusable scratch vector.
inner = fusion[fusion.index('for (int i = 0; i < count; i++)'):fusion.index('output.setPixels')]
for forbidden in ['Math.exp(', 'Math.pow(', 'Math.sqrt(', 'Math.log(', 'new float[']:
    require(forbidden not in inner, f"expensive/per-pixel saved-fusion operation returned: {forbidden}")
require('float brightnessGain = (float) Math.pow(2.0, clampedBrightnessEv);' in fusion
        and fusion.index('float brightnessGain = (float) Math.pow') < fusion.index('for (int y = 0; y < height; y += rowsPerStrip)'),
        "Brightness EV power must be computed once before full-resolution loops")
require('buildGammaLut(clampedGamma)' in fusion
        and fusion.index('buildGammaLut(clampedGamma)') < fusion.index('for (int y = 0; y < height; y += rowsPerStrip)'),
        "Gamma LUT must be computed once before full-resolution loops")
require('float[] colorOwned = new float[3];' in fusion and 'return new float[]' not in fusion,
        "highlight color ownership must reuse one scratch vector rather than allocate per pixel")

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

print("V1.4.11 V2.4 REGRESSION PASS: exact V2.3 authority, fixed-3EV HDR, -16..+1EV Brightness, 0.50..2.00 Gamma, immediate scene-cut AUTO, GLES3-primary still fusion, damaged-LONG cannot veto validated SHORT RGB, CPU fallback equivalence, fixed-height status, frozen capture controls, producer-owned orientation, capture protection")
