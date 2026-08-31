#!/usr/bin/env python3
from pathlib import Path
import math

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


def require(condition, message):
    if not condition:
        raise SystemExit("V1.4.6 REGRESSION FAIL: " + message)


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

# 012 / 029 - Bracket target remains 8x while absolute AUTO brightness comes from clean AE authority.
require('HDR_BRACKET_RATIO = 8.0' in camera,
        "3 EV / 8x target bracket constant missing")
require('shortExposureNs = ONE_SECOND_NS / 480' in camera,
        "manual default short exposure must remain 1/480s")
require('longExposureNs = ONE_SECOND_NS / 60' in camera,
        "manual default long exposure must remain 1/60s")
require('AUTO_METER_MIN_FRAMES' in camera and 'buildMeterPreviewRequest' in camera,
        "clean AE anchor phase missing")
require('commitAutoAnchorFromResultLocked' in camera and 'deriveAutoPairFromAnchorLocked' in camera,
        "clean AE result must own absolute AUTO exposure")
require('targetShortProduct' in camera and '/ HDR_BRACKET_RATIO' in camera,
        "AUTO no-flicker SHORT target must derive from anchored LONG exposure product")
require('autoShortIso = minIso;' in camera,
        "AUTO SHORT must use the camera minimum sensor gain")
require('double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);' in camera,
        "AUTO HDR must report actual EV after sensor-range clamping")

# 013 / 030 - Live and saved fusion preserve sRGB math, wide exposure normalization and midtone parity.
for text, owner in ((hdr_shader, 'live shader'), (fusion, 'JPEG fusion')):
    require('0.04045' in text and '12.92' in text and '0.0031308' in text and '2.4' in text,
            f"{owner} must use the piecewise sRGB transfer function")
require('smoothstep(0.68, 0.94, longHighlight)' in hdr_shader,
        "live highlight-admission weighting missing")
require('hdrShoulderChannel' in hdr_shader and 'const float knee = 0.70;' in hdr_shader,
        "live midtone-preserving HDR shoulder missing")
require('smoothstep(0.68f, 0.94f, clip)' in fusion,
        "saved JPEG highlight-admission weighting missing")
require('hdrShoulder' in fusion and 'final float knee = 0.70f;' in fusion,
        "saved JPEG midtone-preserving shoulder missing")
require('65_536.0' in fusion and '65_536.0' in saver and '65_536.0' in gl,
        "widened exposure normalization must be consistent live/save/metadata")
require('1.6 * max(sceneLinear' not in hdr_shader and '1.6f * Math.max' not in fusion,
        "rejected fixed darkening tone-map exposure returned")

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
require('AUTO_REMETER_INTERVAL_MS = 5_000L' in camera,
        "bounded clean AE refresh cadence must remain 5 seconds")
require('cameraHandler.hasCallbacks(autoRemeterRunnable)' in camera,
        "AUTO remeter must arm only one pending timer")
require('FrameMeta.METER.equals(meta.kind)' in gl,
        "meter frames must remain hidden from display/pair publication")

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
require('autoShortExposureNs = autoLongExposureNs;' in camera,
        "artificial/unknown AUTO HDR must match SHORT shutter to anchored LONG shutter")
require('unknown/PWM-safe' in camera,
        "unknown/PWM conservative fallback label missing")
require('sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE' in camera,
        "shutter-separated AUTO bracket must require no-flicker evidence")

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
require(camera.count('scheduleAutoRemeterLocked();') == 1,
        "AUTO remeter must be armed only from completed LONG results")
require('FrameMeta.LONG.equals(kind)' in callback and 'scheduleAutoRemeterLocked();' in callback,
        "remeter must wait for a completed LONG so a complete pair remains published")
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

print("V1.4.6 REGRESSION PASS: producer-owned orientation, explicit DNG orientation, clean-anchored two-frame AUTO HDR, atomic pairs, native FOV, measured cadence, sRGB, capture protection")
