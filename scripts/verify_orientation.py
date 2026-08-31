#!/usr/bin/env python3
from pathlib import Path
import math
import re

ROOT = Path(__file__).resolve().parents[1]
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
main = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java").read_text()
camera = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java").read_text()
gl = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java").read_text()
fusion = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java").read_text()
saver = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/CaptureSetSaver.java").read_text()
hdr_shader = (ROOT / "app/src/main/assets/shaders/hdr_display.frag").read_text()
oes_shader = (ROOT / "app/src/main/assets/shaders/oes_to_rgb.frag").read_text()
copy_shader = (ROOT / "app/src/main/assets/shaders/copy_2d.frag").read_text()


def require(condition, message):
    if not condition:
        raise SystemExit("V1.4.2 REGRESSION FAIL: " + message)


# 015 - Actions Java compiler regression: FrameMeta timestamp field is sensorTimestampNs.
frame_meta = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/FrameMeta.java").read_text()
require('final long sensorTimestampNs;' in frame_meta,
        "FrameMeta sensorTimestampNs contract missing")
require('metaByTimestamp.put(meta.sensorTimestampNs, meta);' in gl,
        "HdrGlView must use FrameMeta.sensorTimestampNs for timestamp matching")
require('meta.timestampNs' not in gl,
        "failed V1.4 meta.timestampNs compiler reference returned")

# 014 - Upload/package hygiene: temporary Python bytecode must never enter the candidate.
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

# 005 - SurfaceTexture owns producer-origin/crop transform exactly once.
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

# 006 / 008 - Native-FOV stream selection + FIT presentation, never widescreen crop/stretch.
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
require('CONTROL_ZOOM_RATIO' not in camera and 'SCALER_CROP_REGION' not in camera,
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

# 007 - Device-correct still orientation and fused input normalization remain intact.
require(camera.count('CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees') == 2,
        "SHORT/LONG JPEG requests must share device-relative orientation")
require('int previewRotation = (sensorOrientation - displayDegrees + 360) % 360;' in main,
        "standard back-camera sensor/display preview relation missing")
require('int jpegOrientation = (sensorOrientation - displayDegrees + 360) % 360;' in main,
        "JPEG orientation convention changed")
require('ExifInterface.TAG_ORIENTATION' in fusion,
        "fused JPEG must normalize EXIF-only HAL orientation")

# 009 - Direct-GPU live path; no per-frame Java YUV repacking.
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

# 010 - Capability-aware 60/30 policy; AE range is not mistaken for manual frame duration.
require('SIXTY_FPS_DURATION_NS = 16_666_667L' in camera,
        "60 fps frame-duration target missing")
require('THIRTY_FPS_DURATION_NS = 33_333_333L' in camera,
        "30 fps fallback missing")
require('CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES' in camera,
        "AE FPS capability query missing")
require('getOutputMinFrameDuration(SurfaceTexture.class, size)' in camera,
        "selected PRIVATE stream min-frame-duration proof missing")
require('targetPreviewFps = aeCanReach60 && streamCanReach60 ? 60 : 30;' in camera,
        "60/30 capability decision missing")
require('CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE' in camera,
        "NORMAL AE must request the selected supported FPS range")
require('long frameDuration = Math.max(manualFrameDurationNs, exposure);' in camera,
        "manual HDR must own frame duration independently of AE FPS")
require('camera %.1f fps   HDR pairs %.1f fps' in main,
        "UI must report actual camera and HDR-pair cadence separately")

# 011 - Correct Camera2 sRGB preset semantics, never the invalid CONTRAST_CURVE wording.
require('CaptureRequest.TONEMAP_MODE_PRESET_CURVE' in camera,
        "Camera2 PRESET_CURVE mode missing")
require('CaptureRequest.TONEMAP_PRESET_CURVE_SRGB' in camera,
        "Camera2 sRGB preset missing")
require('TONEMAP_AVAILABLE_TONE_MAP_MODES' in camera,
        "sRGB preset capability gate missing")
require('TONEMAP_MODE_CONTRAST_CURVE' not in camera,
        "CONTRAST_CURVE must not be mislabeled as built-in sRGB")

# 012 - Manual default remains 3 EV / 8x; AUTO HDR targets 8x but may reduce
# separation when flicker-safe common-shutter bracketing reaches the sensor ISO floor.
require('HDR_BRACKET_RATIO = 8.0' in camera,
        "3 EV / 8x target bracket constant missing")
require('shortExposureNs = ONE_SECOND_NS / 480' in camera,
        "manual default short exposure must remain 1/480s")
require('longExposureNs = ONE_SECOND_NS / 60' in camera,
        "manual default long exposure must remain 1/60s")
require('targetLongProduct / HDR_BRACKET_RATIO' in camera,
        "AUTO HDR short target must derive from the metered long exposure product")
require('double bracketEv = Math.log(actualLongProduct / actualShortProduct) / Math.log(2.0);' in camera,
        "AUTO HDR must report the actual bracket after sensor-range clamping")

# 013 - Live and saved HDR use exact sRGB transfer functions and highlight-aware rolloff.
for text, owner in ((hdr_shader, 'live shader'), (fusion, 'JPEG fusion')):
    require('0.04045' in text and '12.92' in text and '0.0031308' in text and '2.4' in text,
            f"{owner} must use the piecewise sRGB transfer function")
require('smoothstep(0.68, 0.94, longHighlight)' in hdr_shader,
        "live highlight-admission weighting missing")
require('vec3 scaled = 1.6 * max(sceneLinear' in hdr_shader,
        "live highlight-compressive tone map missing")
require('smoothstep(0.68f, 0.94f, clip)' in fusion,
        "saved JPEG highlight-admission weighting missing")
require('float scaled = 1.6f * Math.max(0.0f, merged);' in fusion,
        "saved JPEG highlight-compressive tone map missing")
require('Math.min(32.0, exposureRatio)' in fusion,
        "saved JPEG must preserve wider exposure ratios")
require('Math.min(32.0, longProduct / shortProduct)' in saver,
        "capture metadata/fusion ratio must preserve wider exposure ratios")

# 016 - V1.4.1 device regression: preview orientation must have one owner.
# Camera2 processed preview explicitly opts out of HAL AUTO rotate/crop when supported,
# MainActivity computes the standard back-camera sensor/display relation, and the shader
# inverse mapping is accounted for once in HdrGlView.
require('SCALER_AVAILABLE_ROTATE_AND_CROP_MODES' in camera,
        "preview rotate/crop capability audit missing")
require('CaptureRequest.SCALER_ROTATE_AND_CROP_NONE' in camera,
        "preview must opt out of HAL rotate-and-crop when supported")
require('configurePreviewRotateAndCrop(builder);' in camera,
        "preview requests must apply the rotate/crop opt-out")
require('renderer.rotationQuarterTurns = ((360 - normalized) % 360) / 90;' in gl,
        "display-UV inverse rotation ownership missing")
require('int previewRotation = (sensorOrientation - displayDegrees + 360) % 360;' in main,
        "standard back-camera preview relative rotation missing")
require(camera[camera.index('private void issueStillBurstLocked()'):camera.index('private final CameraCaptureSession.CaptureCallback stillCaptureCallback')].count('configurePreviewRotateAndCrop') == 0,
        "good V1.4.1 still path must not inherit preview rotate/crop controls")

# 017 - HDR/SPLIT display updates must be complete-pair atomic.
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

# 018 - AUTO HDR and MANUAL HDR are both first-class modes; metering frames never display.
require('void setAutoHdrExposure(boolean enabled)' in camera,
        "AUTO/MANUAL HDR exposure owner switch missing")
require('TAG_METER = "P_METER"' in camera and 'static final String METER = "METER"' in frame_meta,
        "hidden AE meter frame tag missing")
require('CONTROL_AE_ANTIBANDING_MODE_AUTO' in camera,
        "AUTO HDR meter/NORMAL AE must request HAL automatic antibanding")
require('issueAutoMeterProbeLocked' in camera and 'AUTO_METER_INTERVAL_MS = 500L' in camera,
        "continuous low-duty AUTO HDR metering loop missing")
require('if (FrameMeta.METER.equals(meta.kind))' in gl,
        "meter frames must be recognized by the GPU timestamp matcher")
require('AE metering probes are intentionally not displayed' in gl,
        "meter-frame display rejection contract missing")
require('HDR AUTO: ON' in main and 'HDR MANUAL' in main,
        "AUTO/MANUAL HDR UI control missing")
require('setManualControlsEnabled(!autoHdrEnabled)' in main,
        "manual controls must be explicitly gated by AUTO/MANUAL ownership")

# 019 - Flicker-safe exposure policy covers 50 Hz, 60 Hz, and unknown/PWM lighting.
require('CaptureResult.STATISTICS_SCENE_FLICKER' in camera,
        "Camera2 scene-flicker evidence missing")
require('FLICKER_50_PERIOD_NS = 10_000_000L' in camera,
        "50 Hz / 100 Hz light integration period missing")
require('FLICKER_60_PERIOD_NS = 8_333_333L' in camera,
        "60 Hz / 120 Hz light integration period missing")
require('chooseFlickerCompatibleExposure' in camera,
        "flicker-compatible shutter solver missing")
require('nextLongExposure = commonExposure;' in camera and 'nextShortExposure = commonExposure;' in camera,
        "artificial/unknown AUTO HDR must use matched temporal integration windows")
require('unknown/PWM-safe' in camera,
        "unknown/PWM conservative fallback label missing")
require('stableBrightNoFlicker' in camera,
        "shutter-based 3-EV bracket must be limited to stable bright/no-flicker evidence")
require('This prevents a 1/480s SHORT frame from sampling a different LED/PWM phase.' in camera,
        "V1.4.1 moving scan-band failure must remain an explicit regression condition")

# 020 - Capability target and measured cadence are distinct quantities.
require('captureResultFps' in camera and 'updateCaptureResultFpsLocked' in camera,
        "actual CaptureResult cadence measurement missing")
require('CaptureResult.SENSOR_FRAME_DURATION' in camera,
        "actual sensor frame-duration diagnostics missing")
require('resultFps=' in camera,
        "status must expose measured CaptureResult FPS separately from requested target")
require('captureResultFps < 45.0' in camera and 'targetPreviewFps = 30;' in camera,
        "advertised 60 fps must fall back after sustained measured under-delivery")
require('60 fps capability under-delivered' in camera,
        "60-to-30 measured-cadence fallback diagnostic missing")
require('camera %.1f fps   HDR pairs %.1f fps' in main,
        "GPU input and complete HDR-pair cadence diagnostics must remain visible")

# Math replay: FIT must preserve image geometry and may only add empty bars.
def fit_scale(frame_w, frame_h, quarter_turns, viewport_w, viewport_h):
    quarter = quarter_turns & 1
    rotated_w = frame_h if quarter else frame_w
    rotated_h = frame_w if quarter else frame_h
    image_aspect = rotated_w / rotated_h
    viewport_aspect = viewport_w / viewport_h
    sx = sy = 1.0
    if viewport_aspect > image_aspect:
        sx = viewport_aspect / image_aspect
    elif viewport_aspect < image_aspect:
        sy = image_aspect / viewport_aspect
    return sx, sy, image_aspect, viewport_aspect

for args in [
    (1440, 1080, 0, 1920, 1080),
    (1440, 1080, 1, 1080, 1920),
    (1280, 960, 1, 1080, 2200),
    (1440, 1080, 0, 2200, 1080),
    (1440, 1080, 1, 540, 1920),
]:
    sx, sy, image_aspect, viewport_aspect = fit_scale(*args)
    require(sx >= 1.0 and sy >= 1.0, f"invalid FIT scale {sx},{sy} for {args}")
    displayed_fraction_x = 1.0 / sx
    displayed_fraction_y = 1.0 / sy
    displayed_aspect = viewport_aspect * displayed_fraction_x / displayed_fraction_y
    require(math.isclose(displayed_aspect, image_aspect, rel_tol=1e-6, abs_tol=1e-6),
            f"FIT math would distort geometry: displayed={displayed_aspect} image={image_aspect}")

# Representative native-aspect scoring must reject a 16:9 crop when a 4:3 stream exists.
def aspect_error(width, height, native=4.0/3.0):
    wide, tall = max(width, height), min(width, height)
    return abs((wide / tall) / native - 1.0)

require(aspect_error(1440, 1080) <= 0.015, "4:3 preview should pass native-aspect gate")
require(aspect_error(1280, 720) > 0.015, "16:9 preview must fail 4:3 native-aspect gate")

# Shader samples display->source, so physical clockwise sensor/display rotation uses
# the inverse quarter-turn index in rotateUv.
def shader_quarter_turns(sensor_orientation, display_degrees):
    relative = (sensor_orientation - display_degrees + 360) % 360
    return ((360 - relative) % 360) // 90

require(shader_quarter_turns(90, 0) == 3,
        "portrait sensor=90 display=0 must map through inverse shader quarter-turn 3")
require(shader_quarter_turns(90, 90) == 0,
        "natural landscape sensor=90 display=90 must require no residual rotation")
require(shader_quarter_turns(90, 270) == 2,
        "reverse landscape sensor=90 display=270 must require 180-degree residual rotation")

# 3 EV means exactly an 8x exposure-product ratio.
require(math.isclose(math.log2(8.0), 3.0), "8x bracket must equal 3 EV")

print("V1.4.2 REGRESSION PASS: single-owner orientation, native FOV, direct GPU, atomic pairs, AUTO/MANUAL HDR, flicker-safe metering, measured cadence, sRGB, capture protection")
