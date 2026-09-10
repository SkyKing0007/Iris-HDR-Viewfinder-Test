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
raw_fusion = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/RawFusion.java").read_text()
service = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/HdrProcessingService.java").read_text()
nafnet = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/NafNetDenoiser.java").read_text()
build_gradle = (ROOT / "app/build.gradle.kts").read_text()
nafnet_model = ROOT / "app/src/main/assets/nafnet_sidd_width32_fp16.tflite"
nafnet_license = ROOT / "app/src/main/assets/licenses/NAFNet_LICENSE.txt"
frame_meta = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/FrameMeta.java").read_text()
hdr_shader = (ROOT / "app/src/main/assets/shaders/hdr_display.frag").read_text()
oes_shader = (ROOT / "app/src/main/assets/shaders/oes_to_rgb.frag").read_text()
raw_preprocess_shader = (ROOT / "app/src/main/assets/shaders/raw_preprocess.frag").read_text()
raw_green_shader = (ROOT / "app/src/main/assets/shaders/raw_green.frag").read_text()
raw_shader = (ROOT / "app/src/main/assets/shaders/raw_reconstruct.frag").read_text()
raw_chroma_shader = (ROOT / "app/src/main/assets/shaders/raw_chroma_dealias.frag").read_text()
raw_proxy_shader = (ROOT / "app/src/main/assets/shaders/raw_proxy.frag").read_text()
workflow = (ROOT / ".github/workflows/build.yml").read_text()


def require(condition, message):
    if not condition:
        raise SystemExit("V1.4.11 V2.37 REGRESSION FAIL: " + message)


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
    print("V1.4.11 V2.37 WORKFLOW EMBEDDED-PYTHON SYNTAX: PASS")
    raise SystemExit(0)


# V2.34 PRE-MORTEM FREEZE: successful V2.33 fusion/registration/topology/ownership
# is immutable. V2.34 may change only the RAW CFA reconstruction/carrier and saved
# post-fusion presentation around these exact mechanics.
def frozen_shader_section(text, start_marker, end_marker):
    start = text.index(start_marker)
    end = text.index(end_marker, start) + len(end_marker)
    return text[start:end]

def frozen_shader_prefix(text, start_marker, end_text):
    start = text.index(start_marker)
    end = text.index(end_text, start) + len(end_text)
    return text[start:end]

def sha_text(text):
    return hashlib.sha256(text.encode()).hexdigest()

require(sha_text(frozen_shader_section(
            hdr_shader,
            '// IRIS_V217_REVERSED_V215_LONG_TRUTH_BEGIN',
            '// IRIS_V217_REVERSED_V215_LONG_TRUTH_END'))
        == '4e0741c2882ae3c326ee113387bff056eed06f3ebf463866dfc585173f75dcd7',
        'V2.34 must not modify successful V2.33 registration/evidence/physical-loss fusion math')
require(sha_text(frozen_shader_section(
            hdr_shader,
            '// IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_BEGIN',
            '// IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_END'))
        == '583c1a512bce64bc6573a586d44a4536813766d4256caa62ed96f49bfc0cf633',
        'V2.34 must not modify successful V2.33 mode-3/mode-4 topology reconstruction')
require(sha_text(frozen_shader_prefix(
            hdr_shader,
            '    if (mode == 5) {',
            '        vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;'))
        == 'b212d64c45a9cafd70b3da2dd4c2d6e6a18d33b028a4ad9fe54ce9e69a6931cf',
        'V2.34 must not modify successful V2.33 mode-5 SHORT/LONG source ownership or geometry')
require(sha_text(frozen_shader_prefix(
            hdr_shader,
            'vec3 adaptiveHdrToneMap(',
            '    return sceneLinear * (mappedPeak / scenePeak);\n}\n'))
        == '41a5a793e7b7d55e86711669670cca2a0de76925f76651de4a5ba1e57e95def5',
        'V2.34 must keep the shared/live V2.33 HDR transfer byte-identical')
def normalized_v235_hdrgl(text):
    text = re.sub(
        r'\s*renderRawGreen\(\n?\s*outputTexture,\s*presentationTexture(?:,\s*rawInputTexture)?(?:,\s*)?(shortRaw|longRaw)(?:,\s*longRaw)?\s*\);',
        lambda m: f'\n                __IRIS_RAW_GREEN_CALL_{m.group(1).upper()}__;',
        text,
        count=2)
    start = text.index('        private void renderRawGreen(')
    end = text.index('        private void renderRawReconstruction(', start)
    return text[:start] + '        __IRIS_RAW_GREEN_METHOD__\n\n' + text[end:]

require(sha_text(normalized_v235_hdrgl(gl))
        == 'b6be39e9d6cbe6c3fe22cbf2dfc7d74ebe3eb2a098c5964ed330fca6ccfdce9c',
        'V2.35 may change only renderRawGreen calls/bindings inside V2.34 HdrGlView; allocation/lifetime/registration plumbing is frozen')
require(hashlib.sha256((ROOT / 'app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java').read_bytes()).hexdigest()
        == '569754e8043928cf86b1f1d34f2ad6b2885e3bf7948789725d4c2092129d4782',
        'V2.34 must not modify V2.33 JpegFusion registration mechanics')
# V2.37 is a localized presentation-control change on exact successful V2.36.
# Normalize ONLY the three agreed V2.37 runtime owners back to their V2.36 bytes and
# hash the result. This prevents a UI repair or extreme-emitter style from silently
# changing acquisition, CFA, fusion, saved HDR transfer, DNG, or unrelated lifecycle.
def normalized_v237_camera(text):
    constants_start = text.index('    // V2.37 extreme-emitter presentation is a narrowly gated AUTO style learned from')
    constants_end = text.index('    private static final float PRESENT_ENHANCEMENT_STEP = 0.06f;', constants_start)
    text = text[:constants_start] + text[constants_end:]

    start = text.index('            // IRIS_V237_EXTREME_EMITTER_PRESENTATION_BEGIN')
    end_marker = '            // IRIS_V237_EXTREME_EMITTER_PRESENTATION_END\n'
    end = text.index(end_marker, start) + len(end_marker)
    if text[end:end + 1] == '\n':
        end += 1
    text = text[:start] + text[end:]

    start = text.index('            // V2.37 leaves V2.36 AUTO enhancement neutral for every ordinary scene.')
    end = text.index('        } else {', start)
    old_auto_enhancement = (
        '            // Keep mode-6 enhancement neutral in AUTO. V2.21 restores contrast in\n'
        '            // the actual scene-key fit rather than by adding a second hidden exponent.\n'
        '            displayDehaze = stepToward(\n'
        '                    displayDehaze, 0.0f, immediate ? 1.0f : PRESENT_ENHANCEMENT_STEP);\n'
        '            displayMicroContrast = stepToward(\n'
        '                    displayMicroContrast, 0.0f, immediate ? 1.0f : PRESENT_ENHANCEMENT_STEP);\n'
    )
    text = text[:start] + old_auto_enhancement + text[end:]

    start = text.index('    private static float extremeEmitterPressureLocked(')
    end = text.index('    private static float predictAutoPresentedLuma(', start)
    text = text[:start] + text[end:]

    start = text.index('    private void publishPresentationLocked(boolean automatic) {')
    end = text.index('    private static float predictAutoPresentedLuma(', start)
    old_publish = (
        '    private void publishPresentationLocked(boolean automatic) {\n'
        '        if (stillFusionView != null) {\n'
        '            stillFusionView.setDisplayBrightnessEv(displayBrightnessEv);\n'
        '            stillFusionView.setDisplayGamma(displayGamma);\n'
        '            stillFusionView.setDisplayEnhancement(displayDehaze, displayMicroContrast);\n'
        '        }\n'
        '        listener.onPresentationSettings(\n'
        '                displayBrightnessEv, displayGamma, displayDehaze, displayMicroContrast, automatic);\n'
        '    }\n\n'
    )
    text = text[:start] + old_publish + text[end:]
    return text

require(sha_text(normalized_v237_camera(camera))
        == '784db5b7221b1d4af3a2a2a457b847a42c9c426f193f4d6f1300052bf2330368',
        'V2.37 CameraController changes escaped the manual-owner/extreme-emitter presentation allowlist')

def normalized_v237_main(text):
    start = text.index('    @Override\n    public void onPresentationSettings(')
    end = text.index('    @Override\n    public void onCaptureBackgroundSafe(', start)
    old_callback = (
        '    @Override\n'
        '    public void onPresentationSettings(\n'
        '            float brightnessEv,\n'
        '            float gamma,\n'
        '            float dehaze,\n'
        '            float microContrast,\n'
        '            boolean automatic) {\n'
        '        runOnUiThread(() -> {\n'
        '            updatingControls = true;\n'
        '            displayBrightnessEv = brightnessEv;\n'
        '            displayGamma = gamma;\n'
        '            brightnessBar.setProgress(brightnessProgressForEv(displayBrightnessEv));\n'
        '            gammaBar.setProgress(gammaProgressForValue(displayGamma));\n'
        '            brightnessLabel.setText(String.format(\n'
        '                    Locale.US,\n'
        '                    automatic ? "Brightness AUTO %+.1f EV  Dehaze %.0f%%"\n'
        '                            : "Brightness %+.1f EV  Dehaze auto %.0f%%",\n'
        '                    displayBrightnessEv,\n'
        '                    100.0f * dehaze));\n'
        '            gammaLabel.setText(String.format(\n'
        '                    Locale.US,\n'
        '                    automatic ? "Gamma AUTO %.2f  Micro %.0f%%"\n'
        '                            : "Gamma %.2f  Micro auto %.0f%%",\n'
        '                    displayGamma,\n'
        '                    100.0f * microContrast));\n'
        '            glView.setDisplayBrightnessEv(displayBrightnessEv);\n'
        '            glView.setDisplayGamma(displayGamma);\n'
        '            glView.setDisplayEnhancement(dehaze, microContrast);\n'
        '            updatingControls = false;\n'
        '            setManualControlsEnabled(!autoHdrEnabled);\n'
        '        });\n'
        '    }\n\n'
    )
    text = text[:start] + old_callback + text[end:]
    return text

require(sha_text(normalized_v237_main(main))
        == '9699c51defa2a47e1d751f0c8d13d65da40677659fa87a448c1a0e71d2ba35ce',
        'V2.37 MainActivity changes escaped the manual presentation callback allowlist')

def normalized_v237_hdr_shader(text):
    new_split = (
        '        // IRIS_V237_SPLIT_MANUAL_BG_PREVIEW_BEGIN\n'
        '        // SPLIT remains the direct SHORT/LONG diagnostic view. It previews only the\n'
        '        // user-owned Manual Safe Brightness/Gamma controls; automatic Dehaze/Micro\n'
        '        // continues to be solved/stored by the controller for FUSED output but is not\n'
        '        // introduced into this diagnostic branch.\n'
        '        vec3 splitEncoded = leftHalf\n'
        '            ? texture(shortTex, splitUv).rgb\n'
        '            : texture(longTex, splitUv).rgb;\n'
        '        vec3 splitLinear = srgbToLinear(splitEncoded);\n'
        '        float splitBrightnessGain = exp2(clamp(displayBrightnessEv, -16.0, 1.0));\n'
        '        splitLinear *= splitBrightnessGain;\n'
        '        splitLinear = applyDisplayGamma(splitLinear, displayGamma);\n'
        '        outColor = vec4(clamp(linearToSrgb(splitLinear), 0.0, 1.0), 1.0);\n'
        '        // IRIS_V237_SPLIT_MANUAL_BG_PREVIEW_END\n'
    )
    old_split = (
        '        outColor = vec4(\n'
        '            leftHalf ? texture(shortTex, splitUv).rgb : texture(longTex, splitUv).rgb,\n'
        '            1.0);\n'
    )
    require(text.count(new_split) == 1, 'V2.37 SPLIT manual B/G normalization anchor missing')
    return text.replace(new_split, old_split, 1)

require(sha_text(normalized_v237_hdr_shader(hdr_shader))
        == 'f0b24371e0a063a492f3602798e39f5e447c29534f336b779d5fc4bb6e1fa96e',
        'V2.37 hdr_display changes escaped the SPLIT-only presentation allowlist')

require(hashlib.sha256((ROOT / 'app/src/main/assets/shaders/raw_preprocess.frag').read_bytes()).hexdigest()
        == '87f11cdd5f678977648cefadd181d21813c3bf23ee1107a3688772a4d46a3982',
        'V2.34 must not modify V2.33 black/white/lens-shading/noise RAW preprocess owner')


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

# 012 / 029 / 037 / 043 / V2.27 - Clean AE is bootstrap-only; converged AUTO is
# independently solved from LONG body/SNR and SHORT highlight-headroom evidence.
# The historical 8x anchor is allowed only during bootstrap and must not become a
# permanent bracket floor.
require('HDR_BRACKET_RATIO = 8.0' in camera,
        "clean-AE bootstrap 8x seed disappeared")
require('AUTO_BRACKET_MIN_RATIO = 1.0' in camera
        and 'AUTO_BRACKET_MAX_RATIO = 64.0' in camera,
        "V2.27 adaptive 1x..64x physical bracket bounds missing")
require('AUTO_MAX_BRACKET_EV' not in camera and 'autoTargetBracketEvLocked' not in camera
        and 'AUTO_APERTURE_REFERENCE_F' not in camera,
        "rejected aperture/scene-class bracket heuristic returned")
require('shortExposureNs = ONE_SECOND_NS / 480' in camera,
        "manual default short exposure must remain 1/480s")
require('longExposureNs = ONE_SECOND_NS / 60' in camera,
        "manual default long exposure must remain 1/60s")
require('AUTO_METER_MIN_FRAMES' in camera and 'buildMeterPreviewRequest' in camera,
        "clean AE bootstrap phase missing")
require('commitAutoAnchorFromResultLocked' in camera and 'deriveAutoPairFromAnchorLocked' in camera,
        "clean AE result must still seed AUTO before scene statistics converge")
require('// IRIS_V225_INDEPENDENT_EXPOSURE_OWNERS_BEGIN' in camera
        and 'stats.longBodyP50Linear' in camera
        and 'stats.longBodyP75Linear' in camera
        and 'stats.shortP99Linear' in camera,
        "independent LONG_BODY / SHORT_HEADROOM closed-loop authority missing")
require('targetShortProduct = Math.min(targetShortProduct, targetLongProduct);' in camera
        and 'targetShortProduct, targetLongProduct / AUTO_BRACKET_MAX_RATIO' in camera,
        "V2.27 bracket ordering must preserve LONG and adjust only SHORT")
require('autoShortIso = solveIsoForProduct(autoLiveShortProduct, autoShortExposureNs);' in camera
        and 'solveShortHeadroomFlickerSettingLocked(' in camera,
        "AUTO SHORT must be able to realize its independent product after shutter headroom is exhausted")
require('double bracketEv = Math.log(longProduct / shortProduct) / Math.log(2.0);' in camera,
        "AUTO HDR must report the actual realized EV ratio")

# 013 / 030 / 036 / 043 / V2.26 - Piecewise sRGB/HDR presentation remains.
# Saved and live HDR both retain one-real-source RGB ownership: LONG body by default,
# with SHORT admitted only by explicit information-loss ownership.
for text, owner in ((hdr_shader, 'shared live/GPU shader'), (fusion, 'CPU utility fusion')):
    require('0.04045' in text and '12.92' in text and '0.0031308' in text and '2.4' in text,
            f"{owner} must use the piecewise sRGB transfer function")
require('if (mode == 3)' in hdr_shader and 'if (mode == 4)' in hdr_shader
        and 'if (mode == 5)' in hdr_shader and 'if (mode == 6)' in hdr_shader,
        "saved GPU fusion must retain the proven four-pass topology")
require('vec3 temporalBody = mix(longScene, bodyShortScene, bodyShortWeight);' in hdr_shader
        and 'vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;' in hdr_shader
        and 'vec3 liveTemporalBody = mix(longScene, shortScene, liveBodyShortWeight);' in hdr_shader
        and 'vec3 mergedScene = liveShortOwns > 0.5 ? shortScene : liveTemporalBody;' in hdr_shader,
        "V2.27 must keep temporal body denoise separate from binary SHORT highlight replacement")
require('float temporalShortWeight(float ratio, float support)' in hdr_shader
        and 'return clamp(support, 0.0, 1.0) / (1.0 + max(ratio, 1.0));' in hdr_shader
        and 'bodyShortWeight' in hdr_shader and 'liveBodyShortWeight' in hdr_shader,
        "V2.27 temporal body merge must use one whole-RGB scalar inverse-variance proxy")
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
require('float pureGammaY = pow(clamp(y, 0.0, 1.0), 1.0 / gamma);' in hdr_shader
        and 'float gammaInfluence = 1.0 - smoothstep(0.50, 0.78, y);' in hdr_shader
        and 'float mappedY = mix(y, pureGammaY, gammaInfluence);' in hdr_shader
        and 'float gamutScale = 1.0 / max(max3(rgb), 0.000001);' in hdr_shader
        and 'return rgb * min(requestedScale, gamutScale);' in hdr_shader,
        "Gamma must remain luminance-driven/RGB-ratio safe and fade to identity through recovered highlights")
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
        and 'float pureGammaY = mapLut(gammaY, gammaLut);' in fusion
        and 'float gammaInfluence = 1.0f - smoothstep(0.50f, 0.78f, gammaY);' in fusion
        and 'float mappedGammaY = gammaY' in fusion
        and 'float gammaScale = Math.min(requestedGammaScale, gammaGamutScale);' in fusion,
        "CPU utility Gamma must mirror highlight-safe fade and remain RGB-ratio/gamut safe")
require('private static float mapLut(float value, float[] lut) {' in fusion,
        "mapLut helper required by saved Gamma must remain present")
require('const float knee = 0.70;' in hdr_shader and 'HDR_KNEE = 0.70f' in fusion,
        "live/save HDR knee must remain 0.70")
require('const float detailTop = 0.965;' in hdr_shader
        and 'float detailStops = clamp(max(bracketStops, 2.0), 2.0, 6.0);' in hdr_shader
        and 'final float detailTop = 0.965f;' in fusion
        and 'float detailStops = clamp(Math.max(bracketStops, 2.0f), 2.0f, 6.0f);' in fusion
        and 'float detailStops = clampFloat(Math.max(bracketStops, 2.0f), 2.0f, 6.0f);' in camera,
        "V2.27 recovered-highlight detail interval must stay synchronized across GPU/CPU/predictor")
require('highlightStops = max(log2(scenePeak / knee), 0.0)' in hdr_shader
        and 'highlightStops = log2(Math.max(scenePeak / HDR_KNEE, 1.0f))' in fusion
        and 'Math.log(y / 0.70f) / Math.log(2.0)' in camera
        and 'tailStopScale = (1.0 - detailTop) * detailStops / (detailTop - knee)' in hdr_shader
        and 'tailStopScale = (1.0f - detailTop) * detailStops' in fusion
        and 'tailStopScale = 0.035f * detailStops / 0.265f;' in camera,
        "V2.27 synchronized guaranteed-slope highlight mapping missing")
require('HDR_TONE_MAX_SCENE = 128.0f' in fusion,
        "V2.26 CPU parity LUT must span recovered highlight scene energy without per-pixel logs")
require('whiteAnchor' not in hdr_shader and 'displayCeiling' not in hdr_shader
        and 'whiteAnchor' not in fusion and 'displayCeiling' not in fusion,
        "retired bracket-dependent gray highlight ceiling returned")
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
touch_focus_capture = camera[
        camera.index('private void triggerTouchFocusLocked'):
        camera.index('private CaptureRequest.Builder buildFocusTriggerRequestLocked')]
require(touch_focus_capture.count('captureSession.capture(') == 2
        and 'CONTROL_AF_TRIGGER_CANCEL' in touch_focus_capture
        and 'CONTROL_AF_TRIGGER_START' in touch_focus_capture,
        "V2.24 touch AF must use exactly the AF CANCEL/START one-shot pair")
camera_without_touch_focus_capture = camera.replace(touch_focus_capture, '', 1)
require('captureSession.capture(' not in camera_without_touch_focus_capture
        and 'captureSession.capture(buildMeterPreviewRequest' not in camera,
        "V1.4.2 one-shot live AE/meter capture() must never return")
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
        and 'AUTO_LIVE_SCENE_CUT_MAX_STEP_EV = 1.0' in camera
        and 'AUTO_LIVE_UPDATE_MIN_NS = 80_000_000L' in camera,
        "V2.25 bounded scene-cut AUTO response contract missing")
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

# 033 / V2.27 - Processing ownership is split deliberately. Live processed preview
# may use low-latency ISP NR; HDR still source JPEGs remain NR-OFF and edge-OFF.
require('NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES' in camera
        and 'noiseReductionFastSupported' in camera
        and 'noiseReductionHighQualitySupported' in camera
        and 'noiseReductionOffSupported' in camera,
        "Camera2 preview/still NR capability gates missing")
preview_processing = camera[camera.index('    private void configurePreviewProcessingControls('):
                            camera.index('    private void configureStillProcessingControls(')]
still_processing = camera[camera.index('    private void configureStillProcessingControls('):
                          camera.index('    private void configureSrgbTonemap(')]
require('CaptureRequest.NOISE_REDUCTION_MODE_FAST' in preview_processing
        and 'CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY' in preview_processing
        and preview_processing.index('NOISE_REDUCTION_MODE_FAST') < preview_processing.index('NOISE_REDUCTION_MODE_HIGH_QUALITY'),
        "live preview must prefer FAST NR and use HIGH_QUALITY only as fallback")
require('CaptureRequest.NOISE_REDUCTION_MODE_OFF' in still_processing
        and 'NOISE_REDUCTION_MODE_FAST' not in still_processing
        and 'NOISE_REDUCTION_MODE_HIGH_QUALITY' not in still_processing,
        "HDR still source requests must remain explicitly NR-OFF")
require('if (enforcePreviewCadence) {' in camera
        and 'configurePreviewProcessingControls(builder);' in camera
        and 'configureStillProcessingControls(builder);' in camera,
        "manual request builder must route preview and still processing to separate owners")
require('EDGE_AVAILABLE_EDGE_MODES' in camera and 'CaptureRequest.EDGE_MODE_OFF' in camera,
        "EDGE_MODE_OFF support/request ownership missing")

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


# 036 / 043 / V2.27 - Universal recovered-highlight transfer. The runtime does not
# recognize lamps/windows/ceilings; it preserves radiance ordering for any valid
# recovered SHORT interval and reserves only the final tail for specular convergence.
def smoothstep_math(edge0, edge1, value):
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)

def v227_policy(exposure_ratio):
    ratio = max(1.0, min(65536.0, exposure_ratio))
    stops = max(0.0, min(6.0, math.log(max(ratio, 1.0001), 2.0)))
    detail_stops = max(2.0, min(6.0, max(stops, 2.0)))
    return ratio, stops, detail_stops

def map_peak_math(scene_peak, exposure_ratio, brightness_ev=0.0):
    ratio, stops, detail_stops = v227_policy(exposure_ratio)
    boosted = scene_peak * (2.0 ** max(-16.0, min(1.0, brightness_ev)))
    knee = 0.70
    detail_top = 0.965
    if boosted <= knee:
        return boosted
    h = max(0.0, math.log(max(boosted / knee, 1.0), 2.0))
    if h <= detail_stops:
        return knee + (detail_top - knee) * h / detail_stops
    tail = h - detail_stops
    scale = (1.0 - detail_top) * detail_stops / (detail_top - knee)
    return max(knee, min(1.0, detail_top + (1.0 - detail_top) * (1.0 - math.exp(-tail / scale))))

def gamma_safe_math(y, gamma):
    pure = max(0.0, min(1.0, y)) ** (1.0 / max(0.50, min(2.00, gamma)))
    influence = 1.0 - smoothstep_math(0.50, 0.78, y)
    return y + (pure - y) * influence

for ratio in (1.0, 1.25, 1.5, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0):
    _, stops, detail_stops = v227_policy(ratio)
    samples = [map_peak_math(0.70 * (2.0 ** (8.0 * i / 256.0)), ratio) for i in range(257)]
    require(all(b > a for a, b in zip(samples, samples[1:]) if a < 0.999999),
            f"V2.27 recovered-highlight transfer lost strict monotonicity at {ratio}x")
    stop_samples = [map_peak_math(0.70 * (2.0 ** i), ratio)
                    for i in range(int(detail_stops) + 1)]
    stop_deltas = [b - a for a, b in zip(stop_samples, stop_samples[1:])]
    require(min(stop_deltas) >= 0.044 - 1e-6,
            f"V2.27 recovered one-stop interval collapsed at {ratio}x: {stop_deltas}")
    gamma_samples = [gamma_safe_math(v, 2.0) for v in stop_samples]
    require(all(b > a for a, b in zip(gamma_samples, gamma_samples[1:])),
            f"AUTO gamma re-compressed/reversed recovered highlights at {ratio}x")
    require(samples[-1] > 0.997 and samples[-1] <= 1.0,
            f"V2.27 specular tail must approach white at {ratio}x")

# C1 stop-domain slope continuity at the detail->specular boundary.
for detail_stops in (2.0, 3.0, 4.0, 5.0, 6.0):
    left = (0.965 - 0.70) / detail_stops
    tail_scale = (1.0 - 0.965) * detail_stops / (0.965 - 0.70)
    right = (1.0 - 0.965) / tail_scale
    require(math.isclose(left, right, rel_tol=0.0, abs_tol=1e-9),
            "V2.27 detail/specular join lost C1 stop-domain continuity")

# V2.28 visual-information acceptance regression. A flat/clipped LONG component is
# not considered recovered merely because SHORT chroma appears. Once SHORT owns a
# valid region, spatial luminance variation/rank from SHORT must survive the existing
# whole-RGB V2.27 transfer with meaningful separation; a constant LONG plateau fails.
def srgb_channel_to_linear_math(v):
    x = v / 255.0
    return x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4

def luma_math(rgb):
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

def v227_short_detail_luma(rgb8, scalar_gain=6.25, brightness_ev=0.8,
                           gamma=1.35, ratio=6.25):
    rgb = [srgb_channel_to_linear_math(v) * scalar_gain * (2.0 ** brightness_ev)
           for v in rgb8]
    y = luma_math(rgb)
    if y > 1e-6:
        toe = smoothstep_math(0.015, 0.090, y)
        protect = 1.0 - smoothstep_math(0.45, 0.68, y)
        target_y = y + 0.45 * toe * protect * y * (1.0 - max(0.0, min(1.0, y)))
        scale = min(target_y / y, 1.0 / max(max(rgb), 1e-6))
        rgb = [c * scale for c in rgb]
    peak = max(rgb)
    if peak > 0.70:
        mapped_peak = map_peak_math(peak / (2.0 ** 0.0), ratio, 0.0)
        scale = mapped_peak / peak
        rgb = [c * scale for c in rgb]
    y = luma_math(rgb)
    if y > 1e-6:
        mapped_y = gamma_safe_math(y, gamma)
        scale = min(mapped_y / y, 1.0 / max(max(rgb), 1e-6))
        rgb = [c * scale for c in rgb]
    return luma_math(rgb)

structured_short = [(80, 120, 160), (100, 140, 180),
                    (120, 160, 200), (140, 180, 220)]
structured_output = [v227_short_detail_luma(v) for v in structured_short]
require(all(b > a for a, b in zip(structured_output, structured_output[1:])),
        "SHORT-owned spatial luminance rank must survive fused presentation")
require(structured_output[-1] - structured_output[0] > 0.05,
        "SHORT-owned recovered detail collapsed toward a flat LONG-like plateau")
flat_long_output = [0.225] * len(structured_output)
require(max(flat_long_output) - min(flat_long_output) == 0.0
        and structured_output[-1] - structured_output[0] > 0.05,
        "visual-detail regression fixture must distinguish true SHORT detail from a flat LONG plateau")

# V2.37 runtime authority is the exact last successful compiler-tested V2.36 R1
# candidate/artifact. Older versions remain behavioral/reference evidence only.
require('name: Iris-HDR-Viewfinder-Test-V1.4.11-V2.36' in workflow
        and 'run-id: 34477638919' in workflow
        and "authority='1268c56ae19bcff6a8c9bec42fdc9c911a8436d4'" in workflow
        and "authority_tree='9665c112f2ab7c0aa0cc6d05cbce77894cacda24'" in workflow,
        "workflow must download the exact successful V1.4.11 V2.36 Actions authority")
require('branches: [ experiment-v1.4.11-v2-brightness-4ev ]' in workflow,
        "V1.4.11 V2 workflow must remain isolated to its experimental branch")

# 039 / 043 / V2.27 - HDR highlight source truth remains binary LONG/SHORT. The only
# permitted interpolation is confidence-gated low-DR temporal BODY denoise before the
# final binary highlight selector. No local hue synthesis or per-channel weight exists.
mode5 = hdr_shader[hdr_shader.index('    if (mode == 5) {'):hdr_shader.index('    // V2.27 live parity:')]
live_mode = hdr_shader[hdr_shader.index('    // V2.27 live parity:'):hdr_shader.index('    float brightnessGain', hdr_shader.index('    // V2.27 live parity:'))]
require('vec3 temporalBody = mix(longScene, bodyShortScene, bodyShortWeight);' in mode5
        and 'vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;' in mode5,
        "saved HDR must apply temporal body denoise before binary SHORT highlight replacement")
require('vec3 liveTemporalBody = mix(longScene, shortScene, liveBodyShortWeight);' in live_mode
        and 'vec3 mergedScene = liveShortOwns > 0.5 ? shortScene : liveTemporalBody;' in live_mode,
        "live HDR must preserve LONG body / temporal support / binary SHORT highlight ordering")
require('temporalExposureOverlap' in hdr_shader
        and 'stillTemporalBodySupportAt' in hdr_shader
        and 'liveTemporalBodySupportAt' in hdr_shader
        and 'temporalRgbAgreement' in hdr_shader,
        "V2.27 confidence-gated temporal body owner missing")
require('vec3 temporalWeight' not in hdr_shader and 'rgbWeight' not in hdr_shader
        and 'weightR' not in hdr_shader and 'weightG' not in hdr_shader and 'weightB' not in hdr_shader,
        "temporal body denoise may not introduce per-channel source weights")
require('highlightColorOwnership' not in hdr_shader
        and 'adaptiveClipStart' not in hdr_shader
        and 'applyHighlightColorOwnership' not in fusion
        and 'computeShortOwnership' not in fusion
        and 'computeShortCoreOwnership' not in fusion
        and 'shortSupportEvidence' not in fusion,
        "dead historical RGB ownership machinery must remain absent")
require('if (highlightWeight > 0.0005)' not in hdr_shader,
        "legacy fractional highlight color ownership must not return")
require('float livePreviewShortOwnershipAt(vec2 sampleUv)' in hdr_shader
        and 'float directTemporalAgreement = 1.0 - smoothstep(0.65, 1.35, directErrorEv);' in hdr_shader
        and 'float liveShortOwns = livePreviewShortOwnershipAt(uv);' in hdr_shader,
        "information-relative live SHORT highlight selector disappeared")
require('vec3 mergedScene = shortScene;' not in hdr_shader,
        "globally lifted SHORT body returned to live HDR preview")
require('// V2.27 live parity uses the physical SHORT->LONG exposure ratio as one' in gl
        and 'GLES30.glGetUniformLocation(displayProgram, "stillShortScalarGain"),\n                    ratio);' in gl,
        "V2.27 live preview must send one achromatic physical SHORT-to-LONG scale")
require('colorSafeFromSources' not in hdr_shader and 'adaptiveAppearanceLift' not in hdr_shader,
        "global chroma/appearance repair must not return")
require('textureOffset' not in hdr_shader,
        "runtime shader must not add implicit textureOffset RGB reconstruction")

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
require('AUTO_BRACKET_MIN_RATIO = 1.0' in camera
        and 'AUTO_BRACKET_MAX_RATIO = 64.0' in camera
        and 'AUTO_LONG_BODY_P50_TARGET = 0.015' in camera
        and 'AUTO_LONG_BODY_P75_TARGET = 0.065' in camera
        and 'AUTO_LONG_BODY_SCALE_MAX = 8.0' in camera
        and 'AUTO_SHORT_P99_HEADROOM_TARGET = 0.35' in camera
        and 'AUTO_SHORT_NEAR_CLIP_SOFT = 0.0015f' in camera
        and 'AUTO_SHORT_NEAR_CLIP_HARD = 0.0100f' in camera
        and 'AUTO_SHORT_SCALE_MAX = 4.0' in camera,
        "V2.27 highlight-excluded LONG-body / bidirectional SHORT-headroom targets missing")
require('AUTO_LONG_BODY_P95_TARGET' not in camera
        and 'AUTO_SHORT_P50_LONG_TARGET' not in camera
        and 'AUTO_SHORT_P90_LONG_TARGET' not in camera
        and 'AUTO_SHORT_P98_LONG_HEADROOM' not in camera
        and 'AUTO_LONG_P98_BODY_TARGET' not in camera
        and 'AUTO_LONG_MAX_NEAR_CLIP_FRACTION' not in camera,
        "stale global highlight-tail veto returned to LONG-body AUTO")
scene_stats_block = gl[gl.index('static final class SceneStats'):gl.index('private final HdrRenderer renderer;')]
require('final float longBodyP50Linear;' in scene_stats_block
        and 'final float longBodyP75Linear;' in scene_stats_block
        and 'final float longBodyFraction;' in scene_stats_block
        and 'if (longPeak < 0.70f && longLuma[i] >= 0.00025f)' in gl
        and 'if (longBodyCount >= STATS_PIXELS / 5)' in gl,
        "highlight-excluded LONG body-stat producer missing")
stats_solver = camera[camera.index('// IRIS_V225_INDEPENDENT_EXPOSURE_OWNERS_BEGIN'):
                      camera.index('// IRIS_V225_INDEPENDENT_EXPOSURE_OWNERS_END')]
require('stats.longBodyP50Linear' in stats_solver and 'stats.longBodyP75Linear' in stats_solver
        and 'Math.pow(longP50Scale, 0.70)' in stats_solver
        and 'Math.pow(longP75Scale, 0.30)' in stats_solver
        and 'stats.shortP99Linear' in stats_solver
        and 'stats.shortNearClipFraction' in stats_solver,
        "independent LONG_BODY/SHORT_HEADROOM producers missing")
require('stats.longP95Linear' not in stats_solver
        and 'stats.longP98Linear' not in stats_solver
        and 'stats.longNearClipFraction' not in stats_solver
        and 'stats.shortP98Linear' not in stats_solver,
        "LONG body target must not be vetoed by global highlight-tail statistics")
require('targetShortProduct = Math.min(targetShortProduct, targetLongProduct);' in stats_solver
        and 'targetShortProduct = Math.max(' in stats_solver
        and 'targetLongProduct / AUTO_BRACKET_MAX_RATIO' in stats_solver,
        "V2.27 bracket constraints must adjust SHORT only and never darken LONG")
require('autoLiveShortProduct = Math.min(autoLiveShortProduct, autoLiveLongProduct);' in stats_solver
        and 'autoLiveLongProduct / AUTO_BRACKET_MAX_RATIO' in stats_solver,
        "V2.27 convergence ordering/range must preserve LONG product")
require('AUTO_LIVE_SCENE_CUT_MAX_STEP_EV = 1.0' in camera,
        "safe 1-EV scene-cut convergence limit changed")

def v227_exposure_targets(long_product, short_product, body_p50, body_p75, short_p99, short_clip=0.0):
    p50_scale = 0.015 / max(0.00025, body_p50)
    p75_scale = 0.065 / max(0.002, body_p75)
    long_scale = max(0.25, min(8.0, (p50_scale ** 0.70) * (p75_scale ** 0.30)))
    p99_scale = 0.35 / max(0.010, short_p99)
    clip_t = smoothstep_math(0.0015, 0.0100, short_clip)
    clip_scale = 1.0 - 0.50 * clip_t
    short_scale = p99_scale if clip_t <= 0.0 else min(p99_scale, clip_scale)
    short_scale = max(0.25, min(4.0, short_scale))
    target_long = long_product * long_scale
    target_short = short_product * short_scale
    target_short = min(target_short, target_long)
    target_short = max(target_short, target_long / 64.0)
    return target_long, target_short, target_long / target_short, long_scale, short_scale

# Exact V2.26 dim evidence: the old 8x pair has no highlight demand. As the same
# scene responds to exposure, independent owners must converge toward a near-equal
# pair so both frames can provide body SNR instead of wasting SHORT at ISO-min.
base_lp, base_sp = 8.0, 1.0
base_p50, base_p75, base_p99 = 0.004389, 0.007613, 0.002263
lp, sp = base_lp, base_sp
for _ in range(8):
    p50 = base_p50 * lp / base_lp
    p75 = base_p75 * lp / base_lp
    p99 = base_p99 * sp / base_sp
    new_lp, new_sp, ratio, _, _ = v227_exposure_targets(lp, sp, p50, p75, p99)
    require(new_sp <= new_lp + 1e-9, "SHORT may never become the brighter exposure")
    lp, sp = new_lp, new_sp
require(lp / sp <= 1.02, f"low-DR AUTO must converge near 1x, got {lp/sp:.3f}x")

# High-DR window evidence remains wide: SHORT already sits near its 0.35 headroom
# target while LONG legitimately asks for substantially more body exposure.
wl, ws, wr, wls, wss = v227_exposure_targets(14.0, 1.0, 0.003835420357, 0.018821628764, 0.341, 0.0)
require(wls > 3.5 and 0.95 <= wss <= 1.10 and wr > 16.0,
        f"high-DR independent exposure ownership regressed: {(wl, ws, wr, wls, wss)}")

# Temporal body denoise exists only while exposures overlap. It is scalar whole-RGB,
# equals a 50/50 average at 1x, and disappears before a wide HDR bracket.
def temporal_overlap_math(ratio):
    stops = max(0.0, math.log(max(ratio, 1.0), 2.0))
    return 1.0 - smoothstep_math(0.35, 1.70, stops)
def temporal_short_weight_math(ratio, support=1.0):
    return max(0.0, min(1.0, support)) / (1.0 + max(ratio, 1.0))
require(math.isclose(temporal_short_weight_math(1.0), 0.5, abs_tol=1e-9),
        "equal-exposure temporal body pair must average 50/50 at full confidence")
require(0.0 < temporal_overlap_math(2.0) < 1.0,
        "modest bracket should taper temporal body support")
require(math.isclose(temporal_overlap_math(4.0), 0.0, abs_tol=1e-9),
        "wide 4x HDR bracket must not average SHORT into body")
require('IRIS_V227_TEMPORAL_BODY_SNR_BEGIN' in hdr_shader
        and 'IRIS_V227_TEMPORAL_BODY_SNR_END' in hdr_shader
        and 'registrationNeighborhoodConfidenceAt(sampleUv)' in hdr_shader
        and 'radiometricAgreementAt(sampleUv)' in hdr_shader
        and 'temporalRgbAgreement(shortScene, longScene)' in hdr_shader,
        "saved temporal body denoise must fail closed on geometry/radiometry/RGB disagreement")

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
require('stats.longBodyP50Linear' in camera
        and 'stats.longBodyP75Linear' in camera
        and 'stats.shortP99Linear' in camera
        and 'targetShortProduct = Math.min(targetShortProduct, targetLongProduct);' in camera,
        "V2.27 AUTO must use highlight-excluded LONG-body and SHORT-headroom closed-loop authorities")
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
                              camera.index('    private void enforceAutoExposureOrderingLocked(String reason)')]
require(hashlib.sha256(flicker_solver_slice.encode()).hexdigest() ==
        '70338617bf724bf03f96f2ab25c50e2b21bc06c920329df9561ac297dbc1c29f',
        "V2.17 physical minimum-ISO flicker solver changed")
require(hashlib.sha256(manual_flicker_slice.encode()).hexdigest() ==
        'b7cd90754b4403529e5e3e7ac43115285aae66e1601887722296f50af2559108',
        "V2.29 must preserve V2.28 MANUAL flicker/order math while changing AUTO only")
require(camera.count('manualEffectiveShortExposureNs, manualEffectiveLongExposureNs') == 4
        and camera.count('manualEffectiveLongIso);') >= 4
        and 'listener.onManualSettings(shortExposureNs, longExposureNs, manualIso);' not in camera,
        "MANUAL callbacks must expose effective realizable SAFE values")
require('Short ACTUAL ' in main and 'Long ACTUAL ' in main,
        "MANUAL UI must label effective shutter values as actual")

# V2.26 refines the inherited V2.25 scene-stat exposure-policy region
# after the exact V2.25 window audit. Preserve the proven pair realization and
# clean-AE anchor solvers byte-exact; validate the refined policy semantically rather
# than weakening the inherited V2.25 independent-exposure ownership.
physical_stats_slice = camera[camera.index('    private void processHdrSceneStatsLocked('):
                              camera.index('    private void deriveAutoPairFromSceneTargetsLocked()')]
physical_pair_slice = camera[camera.index('    private void deriveAutoPairFromSceneTargetsLocked()'):
                             camera.index('    private void updateAdaptivePresentationLocked(')]
physical_anchor_slice = camera[camera.index('    private void deriveAutoPairFromAnchorLocked()'):
                               camera.index('    private void processHdrSceneStatsLocked(')]
marker_begin = '        // IRIS_V225_INDEPENDENT_EXPOSURE_OWNERS_BEGIN\n'
marker_end = '        // IRIS_V225_INDEPENDENT_EXPOSURE_OWNERS_END\n'
require(marker_begin in physical_stats_slice and marker_end in physical_stats_slice,
        "V2.26 inherited independent exposure-ownership policy markers missing")
require('updateAdaptivePresentationLocked(stats, autoHdrExposure, false);' in physical_stats_slice
        and 'if (stats.longFrameNumber <= lastAutoLiveStatsFrame) return;' in physical_stats_slice
        and 'staleLongEv > 0.30 || staleShortEv > 0.30' in physical_stats_slice,
        "V2.26 changed the synchronized scene-stat freshness/presentation preconditions")
require('solveShortHeadroomFlickerSettingLocked(' in physical_pair_slice
        and 'autoShortIso = solveIsoForProduct(autoLiveShortProduct, autoShortExposureNs);' in physical_pair_slice
        and 'double feasibleLongProduct = Math.max(achievedShortProduct,' in physical_pair_slice
        and 'Math.min(autoLiveLongProduct, achievedShortProduct * autoDesiredBracketRatio)' in physical_pair_slice,
        "V2.27 pair realization must hit SHORT product when cadence caps shutter and preserve LONG target/order")
require(hashlib.sha256(physical_anchor_slice.encode()).hexdigest() ==
        '306add9a9eed6e80d14555c24c8a37f35b93a79f4af7cf6d7d0e939cec6e9e7d',
        "successful V2.24 clean-AE bootstrap solver changed")

# V2.21 full-distribution presentation regression. V2.19 matched only P50/P90;
# the real V2.20 4x window sample therefore solved to -1.3 EV / Gamma 1.80 and
# flattened P10/P25 into a gray veil. V2.21 fits P10/P25/P50/P90 together while
# preserving deep-shadow scenes that already have strong source contrast.
def body_tone_v221(y):
    if y <= 0.000001:
        return y
    toe = smoothstep_math(0.015, 0.090, y)
    protect = 1.0 - smoothstep_math(0.45, 0.68, y)
    return y + 0.45 * toe * protect * y * (1.0 - max(0.0, min(1.0, y)))

def hdr_fit_v221(y, ratio):
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

def predict_presented_v221(scene_y, brightness_ev, gamma, ratio):
    y = max(0.0, scene_y) * (2.0 ** brightness_ev)
    y = body_tone_v221(y)
    y = hdr_fit_v221(y, ratio)
    return max(0.0, min(1.0, y)) ** (1.0 / max(0.50, min(2.00, gamma)))

def targets_v221(f10, f25, f50, f90, long_p98, long_clip):
    contrast_stops = math.log(max(0.001, f90) / max(0.001, f50), 2.0)
    highlight_pressure = max(
        smoothstep_math(0.50, 0.85, long_p98),
        smoothstep_math(0.003, 0.015, long_clip))
    contrast_pressure = smoothstep_math(1.20, 2.60, contrast_stops)
    isolated_specular = highlight_pressure * (1.0 - 0.75 * contrast_pressure)
    target_p90 = max(0.26, min(0.42, 0.40 - 0.12 * isolated_specular))
    contrast_ceiling = 2.00 + 0.60 * highlight_pressure
    target_contrast = max(1.00, min(contrast_ceiling, contrast_stops))
    target_p50 = max(0.055, min(0.18, target_p90 / (2.0 ** target_contrast)))
    source_p10_ratio = max(0.04, min(0.65, f10 / max(0.001, f50)))
    source_p25_ratio = max(0.15, min(0.85, f25 / max(0.001, f50)))
    target_p10 = max(0.003, min(target_p50 * 0.85, target_p50 * source_p10_ratio))
    target_p25 = max(target_p10 * 1.40,
                     min(target_p50 * 0.90, target_p50 * source_p25_ratio))
    shadow_pressure = smoothstep_math(0.12, 0.35, source_p10_ratio)
    return (target_p10, target_p25, target_p50, target_p90,
            0.45 * shadow_pressure, 0.65 * shadow_pressure)

def solve_presentation_v221(f10, f25, f50, f90, long_p98, long_clip, ratio=4.0):
    t10, t25, t50, t90, w10, w25 = targets_v221(
        f10, f25, f50, f90, long_p98, long_clip)
    best = None
    brightness = -4.0
    while brightness <= 1.0001:
        gamma = 0.80
        while gamma <= 2.0001:
            p10 = predict_presented_v221(f10, brightness, gamma, ratio)
            p25 = predict_presented_v221(f25, brightness, gamma, ratio)
            p50 = predict_presented_v221(f50, brightness, gamma, ratio)
            p90 = predict_presented_v221(f90, brightness, gamma, ratio)
            e10 = math.log(max(0.0001, p10) / max(0.0001, t10), 2.0)
            e25 = math.log(max(0.0001, p25) / max(0.0001, t25), 2.0)
            e50 = math.log(max(0.0001, p50) / max(0.0001, t50), 2.0)
            e90 = math.log(max(0.0001, p90) / max(0.0001, t90), 2.0)
            score = (w10 * e10 * e10 + w25 * e25 * e25
                     + 2.00 * e50 * e50 + 1.20 * e90 * e90
                     + 0.01 * brightness * brightness
                     + 0.01 * (gamma - 1.20) * (gamma - 1.20))
            if best is None or score < best[0]:
                best = (score, brightness, gamma, p10, p25, p50, p90)
            gamma += 0.05
        brightness += 0.10
    return (t10, t25, t50, t90, w10, w25, best)

# Recovered pre-presentation V2.18 shelf statistics. Its very deep source P10 must
# not dominate the fit; P50/P90 remain close to the supplied Photon shelf reference.
shelf = solve_presentation_v221(
    0.0014976449, 0.0051540911, 0.0197257439, 0.1298812415,
    0.3373257, 0.0007289, 4.0)
require(0.095 <= shelf[2] <= 0.105 and 0.395 <= shelf[3] <= 0.405,
        "V2.21 shelf target key moved away from Photon-calibrated body")
require(shelf[4] < 0.05 and shelf[5] < 0.05,
        "already-deep shelf shadows must not dominate the AUTO fit")
require(0.20 <= shelf[6][1] <= 0.45 and 1.50 <= shelf[6][2] <= 1.70,
        "V2.21 shelf solution must retain the bright photographic body without haze")
require(abs(math.log(shelf[6][5] / 0.10583283, 2.0)) < 0.30
        and abs(math.log(shelf[6][6] / 0.39880091, 2.0)) < 0.18,
        "V2.21 shelf P50/P90 drifted materially from supplied Photon reference")

# Chandelier: isolated highlight pressure lowers P90 while its naturally moderate
# shadow ratio activates four-anchor fitting instead of V2.19's gamma-heavy shortcut.
chandelier = solve_presentation_v221(
    0.0135062163, 0.0338126582, 0.0527917832, 0.1087644859,
    0.80, 0.020, 4.0)
require(0.130 <= chandelier[2] <= 0.140 and 0.275 <= chandelier[3] <= 0.285,
        "V2.21 chandelier target key/upper body changed")
require(0.30 <= chandelier[6][1] <= 0.50 and 1.10 <= chandelier[6][2] <= 1.30,
        "V2.21 chandelier must not return to a high-gamma veil")
require(abs(math.log(chandelier[6][5] / 0.13369069, 2.0)) < 0.12
        and abs(math.log(chandelier[6][6] / 0.26844543, 2.0)) < 0.12,
        "V2.21 chandelier P50/P90 no longer track supplied Photon reference")

# Exact V2.20 device failure: AUTO 4x selected -1.3 EV / Gamma 1.80. The supplied
# final Iris P10/P25/P50/P90 were ~0.050/0.080/0.098/0.379 while Photon was
# ~0.009/0.044/0.066/0.356. Inverting the exact V2.20 pointwise tone gives these
# source anchors. V2.21 must materially deepen P10/P25 and lower Gamma without
# throwing away the already-correct upper body.
window = solve_presentation_v221(
    0.0111906788, 0.0258683423, 0.0378244887, 0.3078418471,
    0.9936363, 0.0358775, 4.0)
require(0.055 <= window[2] <= 0.065 and 0.365 <= window[3] <= 0.375,
        "V2.21 high-dynamic window target must retain deep body contrast")
require(window[4] > 0.35 and window[5] > 0.50,
        "V2.20 gray-veil scene must activate four-anchor shadow constraints")
require(-0.45 <= window[6][1] <= -0.15 and 1.10 <= window[6][2] <= 1.30,
        "V2.21 window solution must replace -1.3EV/Gamma1.80 haze with photographic contrast")
require(window[6][3] < 0.025 and window[6][4] < 0.050
        and 0.050 <= window[6][5] <= 0.070
        and 0.36 <= window[6][6] <= 0.42,
        "V2.21 window final distribution must deepen shadows while preserving upper body")

# Ordinary lower-contrast scene remains bright and clean rather than inheriting the
# high-contrast window key.
kitchen = solve_presentation_v221(0.020, 0.045, 0.080, 0.180, 0.35, 0.001, 4.0)
require(0.17 <= kitchen[2] <= 0.18 and 0.395 <= kitchen[3] <= 0.405,
        "V2.21 ordinary-scene target must remain kitchen-bright")
require(kitchen[6][1] > 0.35 and 1.00 <= kitchen[6][2] <= 1.20
        and kitchen[6][5] > 0.17 and kitchen[6][6] > 0.35,
        "V2.21 ordinary scene must remain bright without shadow flattening")

# V2.22 SHORT headroom math remains applicable; only the historical forced 4x floor
# is superseded. Excess SHORT clipping must still shorten exposure while an already
# protected SHORT stays near its solved headroom target.
def v222_short_scale(p99, near_clip):
    p99_scale = 0.35 / max(0.010, p99)
    t = smoothstep_math(0.0015, 0.0100, near_clip)
    clip_scale = 1.0 - 0.50 * t
    scale = p99_scale if t <= 0.0 else min(p99_scale, clip_scale)
    return max(0.25, min(4.0, scale))
auto_chandelier_short_scale = v222_short_scale(0.7318, 0.00832)
manual_chandelier_short_scale = v222_short_scale(0.2598, 0.00434)
require(0.45 <= auto_chandelier_short_scale <= 0.52,
        "SHORT clipping pressure no longer materially shortens an over-bright highlight exposure")
require(manual_chandelier_short_scale >= 0.84,
        "already-protected SHORT headroom is being over-corrected")

# V2.27 SNR-aware AUTO may no longer hide weak physical body evidence with the old
# +1EV/gamma2 rescue. The exact dim sample must produce strong pressure; clean target
# evidence at sensor-min ISO must produce essentially none.
def noise_pressure_math(p50, p75, long_iso, min_iso=50):
    iso_stops = math.log(max(min_iso, long_iso) / float(min_iso), 2.0)
    iso_pressure = smoothstep_math(1.0, 3.5, iso_stops)
    p50_deficit = 1.0 - smoothstep_math(0.55, 1.00, p50 / 0.015)
    p75_deficit = 1.0 - smoothstep_math(0.50, 1.00, p75 / 0.065)
    signal_pressure = 0.65 * p50_deficit + 0.35 * p75_deficit
    return max(0.0, min(1.0, 0.82 * signal_pressure + 0.30 * iso_pressure))
require(noise_pressure_math(0.004389, 0.007613, 400) > 0.95,
        "exact dim sample must strongly suppress software-only rescue")
require(noise_pressure_math(0.015, 0.065, 50) < 0.01,
        "clean target-level body at min ISO must not be falsely noise-limited")
require('autoPresentationNoisePressureLocked(stats)' in camera
        and 'IRIS_V236_AUTO_NEUTRAL_PRESENTATION_POLICY' in camera
        and 'float maxAutoBrightnessEv = AUTO_PRESENT_BRIGHTNESS_MAX_EV;' in camera
        and 'float maxAutoGamma = AUTO_PRESENT_GAMMA_MAX;' in camera
        and '3.00f * excessLiftEv * excessLiftEv' in camera,
        "V2.36 must preserve scene/SNR-aware AUTO optimization while forbidding noise-dependent positive rescue")
require('targetBrightness = Math.min(targetBrightness, 0.15f);' not in camera
        and 'targetGamma = Math.min(targetGamma, 1.20f);' not in camera,
        "obsolete collapsed-bracket failure rule must not punish intentional low-DR 1x pairs")

# Existing settling mechanics remain bounded; only scene target ownership changes.
def live_step(error_ev):
    if abs(error_ev) <= 0.10:
        return 0.0
    max_step = 1.0 if abs(error_ev) >= 0.70 else 0.30
    return max(-max_step, min(max_step, error_ev))
require(math.isclose(live_step(+2.0), +1.0) and math.isclose(live_step(-2.0), -1.0),
        "large scene cuts must retain the safe 1-EV correction cap")
require(math.isclose(live_step(+0.50), +0.30) and math.isclose(live_step(-0.50), -0.30),
        "ordinary exposure drift must retain the successful 0.30-EV bound")
require(math.isclose(live_step(+0.05), 0.0) and math.isclose(live_step(-0.05), 0.0),
        "small scene-stat jitter must remain inside exposure hysteresis")

# Presentation controller ownership and capture freeze. V2.21 keeps MANUAL domains
# unchanged but AUTO now closes the loop on P10/P25/P50/P90 rather than two anchors.
require('private void updateAdaptivePresentationLocked(' in camera
        and 'AUTO_PRESENT_BRIGHTNESS_MIN_EV = -4.00f' in camera
        and 'AUTO_PRESENT_BRIGHTNESS_MAX_EV = 0.00f' in camera
        and 'AUTO_PRESENT_GAMMA_MIN = 0.50f' in camera
        and 'AUTO_PRESENT_GAMMA_MAX = 1.65f' in camera
        and 'float highlightPressure = Math.max(' in camera
        and 'float contrastCeiling = lerpFloat(2.00f, 2.60f, highlightPressure);' in camera
        and 'float sourceP10Ratio = clampFloat(' in camera
        and 'float sourceP25Ratio = clampFloat(' in camera
        and 'float shadowCompressionPressure = smoothstepFloat(' in camera
        and 'float predictedP10 = predictAutoPresentedLuma(' in camera
        and 'float predictedP25 = predictAutoPresentedLuma(' in camera
        and 'float predictedMedian = predictAutoPresentedLuma(' in camera
        and 'float predictedP90 = predictAutoPresentedLuma(' in camera,
        "V2.36 four-anchor AUTO scene-key solver / neutral presentation ceilings missing")
require('float targetP90 = lerpFloat(0.024f, 0.020f, highlightPressure);' not in camera
        and '0.029f, 0.023f' not in camera,
        "V2.18 dark MANUAL-calibrated final-render targets survived")
require('extremeEmitterPressure * extremeTargetDehaze' in camera
        and 'extremeEmitterPressure * extremeTargetMicro' in camera,
        "ordinary AUTO must keep the second mode-6 enhancement neutral; only the V2.37 physically gated extreme-emitter style may enable it")
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
require('shortRaw, longRaw, ratio, captureOrientationDegrees,' in saver.replace('\n', ' ')
        and 'displayBrightnessEv, displayGamma, displayDehaze, displayMicroContrast,' in saver.replace('\n', ' '),
        "CaptureSetSaver must carry timestamp-matched RAW pair plus frozen presentation state to GPU still fusion")
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

# Production still fusion remains GPU-only and preserves the successful mode
# ordering. V2.20 intentionally repeats only mode 4 on the small topology atlas
# until connected-region reconstruction converges; modes 3/5/6 remain one-shot.
require('controller.setStillFusionView(glView);' in main
        and 'renderStillPass(' in gl
        and '3, longTexture, shortTexture, longTexture' in gl
        and '4, readTopologyTexture, shortTexture, longTexture' in gl
        and '5, readTopologyTexture, shortTexture, longTexture' in gl
        and '6, presentationTexture, shortTexture, longTexture' in gl
        and 'while (propagationPasses < maxPropagationPasses)' in gl
        and 'stillFusionProgram' not in gl
        and 'still_fusion.frag' not in gl
        and 'GPU_STILL_RAW_FUSION' in gl,
        "saved HDR must remain GPU-only with RAW-derived input and mode-4-only convergent topology reconstruction")
require('submitCpuFusionFallback' not in saver
        and 'JpegFusion.fuse(' not in saver
        and 'GPU_STILL_FUSION_REQUIRED' in saver
        and 'CPU HDR substitution is disabled' in saver,
        "production capture must never substitute independent CPU HDR after GPU failure")
require('final class RawFusion' in raw_fusion
        and 'RawFusion.copyFromImage(raw, characteristics, data.result)' in saver
        and 'stillFusionView.fuseStillRaws(' in saver
        and 'void fuseStillRaws(' in gl
        and 'shaders/raw_reconstruct.frag' in gl,
        "V2.30 production saved fusion must be owned by timestamp-matched RAW_SENSOR inputs")
require('fuseStillJpegs(' not in saver
        and 'fuseStillJpegs(' not in gl
        and 'decodeUpright(' not in gl,
        "V2.30 HAL JPEG decode/fusion owner survived in production saved HDR")
require('data.jpegBytes' not in saver and 'byte[] jpegBytes;' not in saver,
        "V2.30 may save HAL JPEG references but may not retain them as fusion payload")
require('result SENSOR_TIMESTAMP == image.getTimestamp()' in raw_fusion
        or ('timestamp != image.getTimestamp()' in raw_fusion and 'RAW/result timestamp mismatch' in raw_fusion),
        "V2.30 RAW/result timestamp equality gate missing")
require('SENSOR_DYNAMIC_BLACK_LEVEL' in raw_fusion
        and 'SENSOR_DYNAMIC_WHITE_LEVEL' in raw_fusion
        and 'COLOR_CORRECTION_GAINS' in raw_fusion
        and 'COLOR_CORRECTION_TRANSFORM' in raw_fusion
        and 'transform.getElement(col, row)' in raw_fusion,
        "V2.30 RAW black/white/WB/color metadata contract missing or matrix transposed")
require('shortFrame.exposureTimeNs * shortFrame.sensitivityIso' in raw_fusion
        and 'longFrame.exposureTimeNs * longFrame.sensitivityIso' in raw_fusion
        and 'POST_RAW_SENSITIVITY_BOOST' not in raw_fusion,
        "V2.30 RAW radiometric ratio must use physical exposure*ISO and exclude post-RAW JPEG boost")
require('GLES30.GL_R16UI' in gl and 'GLES30.GL_RED_INTEGER' in gl
        and 'GLES30.GL_UNSIGNED_SHORT' in gl
        and 'GLES30.GL_R16,' not in gl
        and 'uniform highp usampler2D rawTex;' in raw_preprocess_shader
        and 'float(texelFetch(rawTex, q, 0).r)' in raw_preprocess_shader,
        "V2.32 RAW integer texture contract missing or failed GL_R16 regression returned")
require('float codeScale = 1.0f / 65535.0f;' not in gl
        and 'black[0], black[1], black[2], black[3]' in gl
        and 'sourceFrame.whiteLevel);' in gl
        and 'max(code - black, 0.0)' in raw_preprocess_shader
        and '/ max(whiteLevelCode - black, 0.000001)' in raw_preprocess_shader,
        "V2.32 purple-frame regression: integer RAW sample/black/white must share sensor-code units")
require('CaptureResult.SENSOR_NOISE_PROFILE' in raw_fusion
        and 'final float noiseSlope;' in raw_fusion
        and 'final float noiseOffset;' in raw_fusion
        and 'maxNoiseSlope' in raw_fusion and 'maxNoiseOffset' in raw_fusion
        and 'uniform float noiseSlope;' in raw_preprocess_shader
        and 'uniform float noiseOffset;' in raw_preprocess_shader
        and 'noiseSlope * normalized + noiseOffset' in raw_preprocess_shader
        and 'sourceFrame.noiseSlope' in gl and 'sourceFrame.noiseOffset' in gl,
        "V2.32 must carry the timestamp-matched physical S*signal+O RAW noise model into CFA reconstruction")
require('vec3 cleanedMeasurementAt(ivec2 p)' in raw_preprocess_shader
        and 'p + ivec2(-2, 0)' in raw_preprocess_shader
        and 'isolatedOutlier' in raw_preprocess_shader
        and '(1.0 - centerValue.z)' in raw_preprocess_shader
        and 'float signal = normalized * shadingGain;' in raw_preprocess_shader
        and 'clamp(signal * shadingGain' not in raw_preprocess_shader
        and 'PACK_MAX_CODE = 65534.0' in raw_preprocess_shader,
        "V2.32 same-CFA outlier/noise preprocessing must occur once before demosaic with no scene-domain upper clamp")
require('String rawGreenShader = loadAsset(context, "shaders/raw_green.frag");' in gl
        and 'rawGreenProgram = buildProgram(vertexShader, rawGreenShader);' in gl
        and gl.count('renderRawGreen(') == 3
        and 'uniform sampler2D packedRawTex;' in raw_green_shader
        and 'uniform highp usampler2D rawTex;' in raw_green_shader
        and 'uniform float highlightClipThreshold;' in raw_green_shader
        and 'uniform float highlightCeiling;' in raw_green_shader
        and 'IRIS_V235_CLAUDE_EXACT_HIGHLIGHT_CALCULATION_SAMPLE_BEGIN' in raw_green_shader
        and 'const float power = 3.0;' in raw_green_shader
        and 'float opposed = targetColor == 0' in raw_green_shader
        and 'reconstructed = min(max(reconstructed, calculationFallback), highlightCeiling);' in raw_green_shader
        and 'return mix(calculationFallback, reconstructed, clipMask);' in raw_green_shader
        and 'wideGreenEstimate' not in raw_green_shader
        and 'IRIS_V234_CENSORED_GREEN_OWNER_BEGIN' not in raw_green_shader,
        "V2.35 must wire Claude's exact power-3 highlightCalculationSample into the active staged green owner with no V2.34 wide-green hybrid")
require('bindSampler2d(rawGreenProgram, "rawTex", rawTexture, 1);' in gl
        and 'GLES30.glGetUniformLocation(rawGreenProgram, "highlightClipThreshold")' in gl
        and '0.985f);' in gl
        and 'GLES30.glGetUniformLocation(rawGreenProgram, "highlightCeiling")' in gl
        and '8.0f);' in gl
        and 'RawFusion.RawFrame colorOwner' in gl
        and 'float[] wb = colorOwner.wbGains;' in gl,
        "V2.35 must bind Claude guide physical sensor threshold, 8x ceiling and common LONG color owner")
require('uniform sampler2D greenTex;' in raw_shader
        and 'vec3 colorDifferenceAt(ivec2 p)' in raw_shader
        and 'vec3 robustFourDifferences(' in raw_shader
        and 'axisDifference' in raw_shader and 'diagonalDifference' in raw_shader
        and 'IRIS_V235_CLAUDE_COMMON_QUAD_CLIP_AUTHORITY_BEGIN' in raw_shader
        and 'float commonQuadValid = 1.0 - quadHighlightAt(q);' in raw_shader
        and 'float valid = (1.0 - colorValue.z) * commonQuadValid;' in raw_shader
        and 'float valid = step(1.5, validSum);' in raw_shader
        and 'opponentSupportQuality' not in raw_shader
        and 'recoverCensoredBalanced' not in raw_shader
        and 'boundaryChroma' not in raw_shader
        and 'wideOffsets' not in raw_shader
        and 'texelFetch(greenTex' in raw_shader
        and 'cleanedRawAt(' not in raw_shader
        and 'shadingMapAt(' not in raw_shader,
        "V2.35 must use common 2x2 physical clip authority plus absolute opponent support and remove V2.34 boundary-hue recovery")
project_start = raw_shader.index('vec3 projectNonNegativeAtFixedLuma(vec3 rgb)')
project_end = raw_shader.index('float transformedSigma', project_start)
project_block = raw_shader[project_start:project_end]
require('vec3 projectNonNegativeAtFixedLuma(vec3 rgb)' in raw_shader
        and 'return max(rgb, vec3(0.0));' in project_block
        and 'min(rgb' not in project_block
        and 'clamp(rgb' not in project_block
        and 'linearToSrgbChannel' not in raw_shader
        and 'clamp(linearRgb, vec3(0.0), vec3(1.0))' not in raw_shader
        and 'float encodeSceneChannel(float value)' in raw_shader
        and 'if (x <= 1.0)' in raw_shader
        and 'if (x <= CARRIER_DETAIL_TOP)' in raw_shader
        and 'encodeSceneChannel(linearRgb.r)' in raw_shader
        and 'encodeSceneChannel(linearRgb.g)' in raw_shader
        and 'encodeSceneChannel(linearRgb.b)' in raw_shader,
        "V2.32 saved RAW reconstruction must remain extended-linear through V2.34's higher-precision carrier and may encode to sRGB only after HDR presentation")
require('String rawChromaDealiasShader = loadAsset(context, "shaders/raw_chroma_dealias.frag");' in gl
        and 'rawChromaDealiasProgram = buildProgram(vertexShader, rawChromaDealiasShader);' in gl
        and gl.count('renderRawChromaDealias(') == 3
        and 'float median9(' in raw_chroma_shader
        and 'float sigmaAt(ivec2 p)' in raw_chroma_shader
        and 'normalizedChromaExcursion' in raw_chroma_shader
        and 'periodicAlias' in raw_chroma_shader
        and 'IRIS_V236_SINGLE_CHROMA_AUTHORITY' in raw_chroma_shader
        and 'wideOffsets[16]' not in raw_chroma_shader
        and 'boundaryRepair' not in raw_chroma_shader
        and 'rgbFromLumaChroma(centerY, correctedC)' in raw_chroma_shader
        and 'linearToSrgbChannel' not in raw_chroma_shader,
        "V2.32 chroma/moire safety pass must be physical-noise-normalized, luminance-preserving and linear-domain")
require('String rawProxyShader = loadAsset(context, "shaders/raw_proxy.frag");' in gl
        and 'rawProxyProgram = buildProgram(vertexShader, rawProxyShader);' in gl
        and gl.count('renderRawProxy(') == 3
        and 'linearToSrgbChannel' in raw_proxy_shader
        and 'registration evidence only' in raw_proxy_shader
        and 'renderRawProxy(presentationTexture, shortTexture' in gl
        and 'renderRawProxy(presentationTexture, longTexture' in gl,
        "V2.32 may create sRGB only as a transient JpegFusion registration proxy, never production source authority")
require('IRIS_V232_EXTENDED_LINEAR_RAW_CARRIER_BEGIN' in hdr_shader
        and 'vec4 shortCarrierLinearAt(vec2 sourceUv)' in hdr_shader
        and 'texelFetch(shortTex' in hdr_shader
        and 'setTextureFilter(shortTexture, GLES30.GL_NEAREST);' in gl
        and 'savedShortLinearAt(uv)' in hdr_shader
        and 'srgbToLinear(shortRgb)' not in hdr_shader[hdr_shader.index('    if (mode == 5) {'):hdr_shader.index('    // V2.27 live parity:')],
        "V2.32 SHORT displacement/interpolation must happen explicitly after extended-linear carrier decode, never in encoded sRGB/companded space")
require('allocateRgbTexture(shortTexture, width, height);' in gl
        and 'allocateRgbTexture(longTexture, width, height);' in gl
        and gl.count('allocateRgbTexture(presentationTexture, width, height);') == 1
        and gl.count('allocateRgbTexture(outputTexture, width, height);') == 1
        and 'int rawInputTexture = 0;' in gl and 'int shadingTexture = 0;' in gl
        and 'shortRawTexture' not in gl and 'longRawTexture' not in gl
        and 'shortShadingTexture' not in gl and 'longShadingTexture' not in gl,
        "V2.32 staged CFA/green/reconstruction/dealias path must reuse the inherited four full-resolution RGBA8 targets and one sequential RAW/shading pair")

# V2.17 reverses V2.15 geometry ownership around the clean LONG body. LONG is the
# immutable reference and only SHORT is globally/local-residual aligned into it.
require('static Registration estimateRegistration(Bitmap movingBitmap, Bitmap referenceBitmap)' in fusion
        and 'estimateOneWayRegistration(movingBitmap, referenceBitmap)' in fusion
        and 'estimateOneWayRegistration(referenceBitmap, movingBitmap)' in fusion
        and 'registrationAnalysisScale(' in fusion
        and 'float analysisCycleError = cycleError * analysisScale;' in fusion
        and 'float coarseCycleConfidence = 1.0f - smoothstep(' in fusion
        and '0.75f, 2.25f, coarseCycleErrorAnalysis' in fusion
        and 'float refinementConfidence = 1.0f - smoothstep(' in fusion
        and '0.45f, 1.50f, analysisCycleError' in fusion
        and 'float confidence = bidirectional * coarseCycleConfidence;' in fusion,
        "V2.28 bidirectional global registration must separate coarse authority from analysis-domain subpixel refinement")
require('cycleConfidence = 1.0f - smoothstep(0.45f, 1.50f, cycleError)' not in fusion,
        "V2.27 full-resolution subpixel cycle kill switch returned")
require('static Bitmap alignLongToShort(Bitmap longBitmap, Registration registration)' in fusion
        and 'canvas.drawBitmap(longBitmap, matrix, paint);' in fusion,
        "byte-protected generic moving-frame alignment helper changed")
require('JpegFusion.estimateRegistration(shortProxy, longProxy)' in gl
        and 'JpegFusion.alignLongToShort(shortProxy, registration)' in gl
        and 'JpegFusion.estimateLocalRegistration(alignedShortProxy, longProxy)' in gl,
        "V2.30 RAW saved path must estimate SHORT-to-LONG geometry from RAW-derived proxies while LONG remains immutable reference")
require('JpegFusion.estimateAppearanceGain(' in gl
        and 'alignedShortProxy, longProxy, exposureRatio' in gl.replace('\n', ' ')
        and 'float scalarGain = median3(' in gl
        and 'physicalRatio=%.3f' in gl,
        "V2.31 RAW saved path must restore V2.28/V2.29 robust overlap-derived achromatic radiometric scale using RAW-derived proxies only")
require(gl.count('private static float median3(float a, float b, float c)') == 1
        and 'return a + b + c' in gl
        and '- Math.max(a, Math.max(b, c))' in gl
        and '- Math.min(a, Math.min(b, c))' in gl,
        "V2.31 V1.1 javac regression: median3 helper must exist exactly once when overlap radiometry calls it")
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
local_registration_slice = fusion[fusion.index('    static LocalRegistrationField estimateLocalRegistration('):
                                  fusion.index('    private static LocalRegistrationField neutralLocalRegistration')]
require(hashlib.sha256(local_registration_slice.encode()).hexdigest() ==
        'ef711f360d9d5c65ad95af2f81a6862ecefbd3c555ac225faa87bf3cc5c7b525',
        "V2.28 must not redesign the proven V2.27 local bidirectional residual field")
require('GPU_STILL_RAW_LOCAL_REGISTRATION' in gl
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
require('setTextureFilter(longTexture, GLES30.GL_NEAREST);' in gl
        and 'setTextureFilter(presentationTexture, GLES30.GL_NEAREST);' in gl,
        "immutable LONG source and full-resolution mode-5 raster must both use nearest sampling")
require('setTextureFilter(presentationTexture, GLES30.GL_NEAREST);' in gl,
        "mode-6 must not bilinear-resample the selected-source fused raster")

# V2.21 source-loss atlas separates strict seeds from the topology-complete physical
# propagation domain. The atlas still never carries source RGB.
require('IRIS_V217_REVERSED_V215_LONG_TRUTH_BEGIN' in hdr_shader
        and 'float channelClipDamage(vec3 rgb)' in hdr_shader
        and 'float shortInformationAdvantageAt(vec2 sampleUv)' in hdr_shader
        and 'float shortRecoveryValidityAt(vec2 sampleUv)' in hdr_shader
        and 'float registrationNeighborhoodConfidenceAt(vec2 sampleUv)' in hdr_shader
        and 'vec2 registrationNeighborhoodFlowAt(vec2 sampleUv)' in hdr_shader
        and 'float longHardLossBaseAt(vec2 sampleUv)' in hdr_shader
        and 'float compactHardLossSupportAt(vec2 sampleUv)' in hdr_shader
        and 'float longEffectiveLossAt(vec2 sampleUv)' in hdr_shader
        and 'float shortRecoveryEvidenceAt(vec2 sampleUv)' in hdr_shader
        and 'float shortRecoveryDomainValidityAt(vec2 sampleUv)' in hdr_shader
        and 'float longLossRecoveryDomainAt(vec2 sampleUv)' in hdr_shader
        and 'vec3 broadRecoverySeedStatsAt(vec2 sampleUv)' in hdr_shader
        and 'float broadRecoveryDomainAt(vec2 sampleUv)' in hdr_shader,
        "V2.21 seed/domain source-loss evidence chain is incomplete")
require('localLinearRangeAtRadius(sampleUv, 4.0)' in hdr_shader
        and 'localLinearRangeAtRadius(sampleUv, 12.0)' in hdr_shader
        and 'localNoiseSigmaAtRadius(sampleUv, 4.0)' in hdr_shader
        and 'localNoiseSigmaAtRadius(sampleUv, 12.0)' in hdr_shader
        and 'IRIS_V232_NOISE_NORMALIZED_EFFECTIVE_LOSS_BEGIN' in hdr_shader
        and 'shortMediumRange - 4.0 * mediumNoise.x' in hdr_shader
        and 'shortBroadRange - 4.0 * broadNoise.x' in hdr_shader
        and 'shortMediumExcess / max(longMediumExcess, 0.0025)' in hdr_shader
        and '1.0 - smoothstep(1.25, 2.75, errorEv)' in hdr_shader,
        "V2.32 effective LONG information-loss proof must preserve connected bathroom recovery using only structure above physical RAW uncertainty")
require('shortCoherentDetailAt' not in hdr_shader
        and 'float recoveryProof =' not in hdr_shader
        and 'step(0.58, recoveryProof)' not in hdr_shader,
        "V2.16 per-pixel detail/recovery gate must be completely removed")
require('int analysisWidth = Math.max(1, (width + 15) / 16);' in gl
        and 'int analysisHeight = Math.max(1, (height + 15) / 16);' in gl,
        "V2.17 ownership atlas must preserve the proven 1/16 allocation")
require(math.ceil(4096 / 16) == 256 and math.ceil(3072 / 16) == 192,
        "3072x4096 device captures must map to a 192x256 ownership atlas")
v222_recon = hdr_shader[hdr_shader.index('// IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_BEGIN'):
                         hdr_shader.index('// IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_END')]
require('float seed = step(0.30, seedStrength);' in v222_recon
        and 'float recoveryDomain = step(0.16, broadRecoveryDomainAt(uv));' in v222_recon
        and 'float currentOwned = step(0.5, centerState.r);' in v222_recon
        and 'if (currentOwned > 0.5 || recoveryDomain < 0.5)' in v222_recon
        and 'float propagate = step(0.35, coherentFlow * geometryBarrier) * recoveryDomain;' in v222_recon,
        "V2.31 mode 3/4 must keep strict V2.29 seeds while allowing connected moderately flattened exterior domain under the same monotonic geometry barrier")
require('for (int oy = -2; oy <= 2; ++oy)' not in v222_recon
        and 'float coherentSupport = seededRegion' not in v222_recon,
        "retired finite-radius one-pass V2.19 closure returned")
require('exposureRatio' not in v222_recon,
        "ownership reconstruction topology must be exposure-ratio invariant")
domain_slice = hdr_shader[hdr_shader.index('float longLossRecoveryDomainAt(vec2 sampleUv)'):
                          hdr_shader.index('vec3 broadRecoverySeedStatsAt(vec2 sampleUv)')]
require('registrationNeighborhoodConfidenceAt' not in domain_slice
        and 'stillRegistrationConfidence' not in domain_slice
        and 'shortRecoveryDomainValidityAt(sampleUv)' in domain_slice,
        "physical recovery domain must not be hole-punched by registration confidence")
short_validity_slice = hdr_shader[hdr_shader.index('float shortRecoveryValidityAt(vec2 sampleUv)'):
                                  hdr_shader.index('float shortRecoveryDomainValidityAt(vec2 sampleUv)')]
short_domain_validity_slice = hdr_shader[hdr_shader.index('float shortRecoveryDomainValidityAt(vec2 sampleUv)'):
                                         hdr_shader.index('float registrationNeighborhoodConfidenceAt(vec2 sampleUv)')]
require('max3(shortRgb)' not in short_validity_slice
        and 'max3(shortRgb)' not in short_domain_validity_slice
        and 'shortInformationAdvantageAt(sampleUv)' in short_validity_slice
        and 'localLinearRangeAtRadius(sampleUv, 4.0)' in short_validity_slice,
        "V2.22 must not reject useful SHORT merely because one channel approaches clipping")
require('headroom' not in short_domain_validity_slice
        and 'return smoothstep(0.004, 0.020, encodedLuma(shortRgb));' in short_domain_validity_slice,
        "V2.22 connected recovery domain must keep real SHORT signal eligible through near-clipped interiors")
require('for (int oy = -2; oy <= 2; ++oy)' in hdr_shader[hdr_shader.index('float broadRecoveryDomainAt'):hdr_shader.index('// IRIS_V217_REVERSED_V215_LONG_TRUTH_END')]
        and 'domainSum / 25.0' in hdr_shader,
        "V2.21 recovery domain must cover each 16x16 atlas cell densely")
require('float targetConfidence = stillLocalRegistrationConfidenceAt(uv);' in v222_recon
        and 'float targetFlowError = length(targetFlowPixels - meanFlowPixels);' in v222_recon
        and 'float geometryBarrier = mix(1.0, targetAgreement, targetMeasured);' in v222_recon,
        "supported local-flow disagreement must remain the motion/disocclusion barrier")
require('meanFlowPixels' in v222_recon
        and 'flowRms' in v222_recon
        and 'smoothstep(0.85, 1.25, flowRms)' in v222_recon
        and 'registrationNeighborhoodFlowAt(uv)' in v222_recon,
        "wide clipped regions must inherit coherent residual geometry from proven seeds")

# V2.29 final saved source ownership: the 16x16 atlas proves connected topology only.
# Final source choice is re-evaluated at full resolution, and path-propagated BA flow
# must never warp final SHORT. Unsupported local-flow panes fall back to the stable
# globally registered SHORT bitmap through stillShortRgbAt().
require('IRIS_V229_FULL_RES_FINAL_SHORT_OWNERSHIP_BEGIN' in hdr_shader
        and 'float connectedRecovery = step(0.50, support.r);' in hdr_shader
        and 'float fullResolutionLoss = longLossRecoveryDomainAt(uv);' in hdr_shader
        and 'float shortOwns = connectedRecovery * step(0.08, fullResolutionLoss);' in hdr_shader
        and 'vec4 shortRaw = savedShortLinearAt(uv);' in hdr_shader
        and 'vec4 longRaw = savedLongLinearAt(uv);' in hdr_shader,
        "V2.32 full-resolution LONG/SHORT source selector missing")
v217_mode5 = hdr_shader[hdr_shader.index('// IRIS_V217_REGION_SOURCE_OWNERSHIP_BEGIN'):
                         hdr_shader.index('// IRIS_V217_REGION_SOURCE_OWNERSHIP_END')]
require('support.ba' not in v217_mode5
        and 'propagatedResidualPixels' not in v217_mode5
        and 'shortOwnedUv' not in v217_mode5,
        "V2.29 final raster must not consume path-propagated atlas flow")
require(v217_mode5.count('longLossRecoveryDomainAt(uv)') == 1
        and 'shortRecoveryEvidenceAt(uv)' not in v217_mode5,
        "V2.32 final raster must prove physical source domain exactly once at the output pixel")
require('vec3 temporalBody = mix(longScene, bodyShortScene, bodyShortWeight);' in v217_mode5
        and 'vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;' in v217_mode5,
        "V2.29 must preserve binary SHORT highlight ownership over LONG-default temporal body")
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

# V2.26 changes live parity/stat production but must preserve the successful V2.25
# saved-still registration/render orchestration and binary geodesic topology mechanics.
saved_fusion_slice = gl[gl.index('        private byte[] fuseStillRaws('):
        gl.index('        private void uploadRaw16Texture', gl.index('        private byte[] fuseStillRaws('))]
require('JpegFusion.estimateRegistration(shortProxy, longProxy)' in saved_fusion_slice
        and 'JpegFusion.estimateLocalRegistration(alignedShortProxy, longProxy)' in saved_fusion_slice
        and 'JpegFusion.estimateAppearanceGain(' in saved_fusion_slice
        and 'float scalarGain = median3(' in saved_fusion_slice
        and 'appearanceGain.r, appearanceGain.g, appearanceGain.b' in saved_fusion_slice,
        "V2.31 must preserve successful V2.28/V2.29 registration plus robust achromatic overlap radiometry using RAW-derived proxies only")
require('renderRawPreprocess(\n                        presentationTexture, rawInputTexture, shadingTexture, shortRaw);' in saved_fusion_slice
        and 'renderRawGreen(\n                        outputTexture, presentationTexture, rawInputTexture, shortRaw, longRaw);' in saved_fusion_slice
        and 'renderRawReconstruction(\n                        shortTexture, presentationTexture, outputTexture, shortRaw, longRaw);' in saved_fusion_slice
        and 'renderRawChromaDealias(presentationTexture, shortTexture, width, height);' in saved_fusion_slice
        and 'renderRawPreprocess(\n                        presentationTexture, rawInputTexture, shadingTexture, longRaw);' in saved_fusion_slice
        and 'renderRawGreen(\n                        outputTexture, presentationTexture, rawInputTexture, longRaw, longRaw);' in saved_fusion_slice
        and 'renderRawReconstruction(\n                        longTexture, presentationTexture, outputTexture, longRaw, longRaw);' in saved_fusion_slice
        and 'renderRawChromaDealias(presentationTexture, longTexture, width, height);' in saved_fusion_slice,
        "V2.35 staged preprocess/green/reconstruct/dealias must retain LONG matched color metadata as common SHORT/LONG color owner while exposing physical RAW only to Claude guide")
require('registration.sampleDx, registration.sampleDy' in saved_fusion_slice
        and 'setTextureFilter(longTexture, GLES30.GL_NEAREST);' in saved_fusion_slice,
        "V2.30 must align only SHORT while retaining immutable LONG output geometry")
require(hashlib.sha256(fusion.encode()).hexdigest() ==
        '569754e8043928cf86b1f1d34f2ad6b2885e3bf7948789725d4c2092129d4782',
        "V2.30 must not reopen successful V2.29 JpegFusion registration math")
v226_mode4 = hdr_shader[hdr_shader.index('    if (mode == 4) {'):
                          hdr_shader.index('    // IRIS_V222_INFORMATION_RELATIVE_REGION_RECONSTRUCTION_END')]
v226_mode4_code = ' '.join(
        re.sub(r'//.*', '', line).strip()
        for line in v226_mode4.splitlines()
        if re.sub(r'//.*', '', line).strip())
require(hashlib.sha256(v226_mode4_code.encode()).hexdigest() ==
        '3dc1958d0eedeed47405a386e319b24a78cb3ca3427ca46e9d5a0c4f96b1198b',
        "successful V2.25 geodesic topology propagation code changed")
v229_mode5_select = hdr_shader[hdr_shader.index('    if (mode == 5) {'):
                                hdr_shader.index('        float brightnessGain =', hdr_shader.index('    if (mode == 5) {'))]
require('vec4 support = texture(normalTex, uv);' in v229_mode5_select
        and 'float connectedRecovery = step(0.50, support.r);' in v229_mode5_select
        and 'float shortOwns = connectedRecovery * step(0.08, fullResolutionLoss);' in v229_mode5_select
        and 'vec4 shortRaw = savedShortLinearAt(uv);' in v229_mode5_select
        and 'vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;' in v229_mode5_select,
        "V2.29 must preserve geodesic connectivity while moving final ownership to full resolution")
require('support.ba' not in v229_mode5_select
        and 'propagatedResidualPixels' not in v229_mode5_select
        and 'shortOwnedUv' not in v229_mode5_select,
        "V2.29 path-propagated residual flow returned to final source sampling")
fusion_provenance_prefix = fusion[
        fusion.index('    static byte[] fuse('):
        fusion.index('        float clampedBrightnessEv', fusion.index('    static byte[] fuse('))]
cpu_reg_log_start = fusion_provenance_prefix.index(
        '        RuntimeLogger.event(\n                "CPU_STILL_REGISTRATION",')
cpu_reg_log_end = fusion_provenance_prefix.index('\n\n        int width =', cpu_reg_log_start)
fusion_provenance_without_reg_log = (fusion_provenance_prefix[:cpu_reg_log_start]
        + '        /* CPU_STILL_REGISTRATION_TELEMETRY */'
        + fusion_provenance_prefix[cpu_reg_log_end:])
require(hashlib.sha256(fusion_provenance_without_reg_log.encode()).hexdigest() ==
        '58bd480cb39f289424d1cd8ee069b65bc928b0e00015e3656e3f190a377494e2',
        "V2.28 changed CPU fallback provenance outside intended registration telemetry")
still_burst = camera[camera.index('    private void issueStillBurstLocked()'):
                     camera.index('    private final CameraCaptureSession.CaptureCallback stillCaptureCallback')]
v230_short_shading_request = '''            // V2.30 RAW fusion requires the exact per-frame lens-shading map.
            // Camera2 guarantees ON support on RAW-capable devices; the map is
            // applied during RAW reconstruction and never inferred from JPEG.
            shortBuilder.set(
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
'''
v230_long_shading_request = '''            longBuilder.set(
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
'''
require(v230_short_shading_request in still_burst and v230_long_shading_request in still_burst,
        "V2.30 both RAW still requests must explicitly request the per-frame lens shading map")
protected_still_burst = still_burst.replace(v230_short_shading_request, '').replace(
        v230_long_shading_request, '')
require(hashlib.sha256(protected_still_burst.encode()).hexdigest() ==
        'af1a49f21c4b660c4b8c79b10abb729daf57619a4b091bbcc0aa01000172378e',
        "V2.30 still-burst request changed outside the intended lens-shading metadata request")
require('stillFusionView.fuseStillRaws(' in saver
        and 'shortRaw, longRaw, ratio, captureOrientationDegrees,' in saver.replace('\n', ' ')
        and 'displayBrightnessEv, displayGamma, displayDehaze, displayMicroContrast,' in saver.replace('\n', ' ')
        and 'JpegFusion.fuse' not in saver,
        "V2.30 must preserve one GPU still-fusion owner while changing its source authority to RAW")

# V2.17 permanent visual/source regressions include the exact V2.16 device failure:
# valid SHORT highlight pieces may not be dropped by a per-pixel re-proof inside one
# coherent LONG-loss region. LONG remains global/default body; SHORT is aligned to it.
require('vec2 stillShortUvAt(vec2 sampleUv)' in hdr_shader
        and 'stillLongUvAt' not in hdr_shader,
        "reversed V2.15 geometry ownership disappeared")
require('vec3 temporalBody = mix(longScene, bodyShortScene, bodyShortWeight);' in v217_mode5
        and 'vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;' in v217_mode5,
        "V2.27 must preserve aligned binary SHORT highlight ownership over the confidence-gated temporal LONG body")
require('mix(shortScene, longScene' not in hdr_shader
        and 'mix(shortScene, temporalBody' not in hdr_shader,
        "gray/blue fractional highlight edge interpolation returned")
require('vec3 mergedScene = shortScene * envelopeScale;' not in hdr_shader,
        "V2.15 global SHORT ownership regression returned")
require('step(0.58, recoveryProof)' not in hdr_shader
        and 'shortCoherentDetailAt' not in hdr_shader,
        "exact V2.16 fragmented pixel-gate regression returned")
require('(width + 15) / 16' in gl
        and 'while (propagationPasses < maxPropagationPasses)' in gl
        and 'counts[0] == previousOwned' in gl
        and 'readTopologyTexture = writeTopologyTexture;' in gl,
        "V2.21 must preserve V2.20 connected ownership topology/convergence loop")
require('LONG is the complete clean body' in hdr_shader and 'immutable LONG geometry' in hdr_shader,
        "LONG-body / SHORT-to-LONG source contract markers disappeared")
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
              'forward.sampleDy + backward.sampleDy',
              'forward.coarseSampleDx + backward.coarseSampleDx',
              'forward.coarseSampleDy + backward.coarseSampleDy',
              'analysisCycleError = cycleError * analysisScale',
              'confidence = bidirectional * coarseCycleConfidence']:
    require(token in registration_core, f"V2.28 global registration owner missing: {token}")
require('float sampleDx = forward.coarseSampleDx' in registration_core
        and '+ refinementConfidence * (forward.sampleDx - forward.coarseSampleDx);' in registration_core
        and 'float sampleDy = forward.coarseSampleDy' in registration_core
        and '+ refinementConfidence * (forward.sampleDy - forward.coarseSampleDy);' in registration_core,
        "V2.28 bad subpixel refinement must fall back continuously toward the coarse anchor")

# Exact 2026-09-07 backlit-window failure condition. The real pair had excellent
# bidirectional coarse anchors at (0,0)/(0,0), but exposure-dependent parabolic
# refinement produced ~1.36 full-resolution pixels of cycle residual on a 2048px
# image. V2.27 judged that full-resolution value directly and collapsed confidence
# below every shader seed threshold. V2.28 judges the residual in the <=384px domain.
def registration_scale_v228(width, height):
    return min(1.0, 384.0 / max(width, height))

def global_registration_confidence_v228(width, height, refined_cycle_full,
                                        coarse_cycle_analysis, bidirectional=1.0):
    scale = registration_scale_v228(width, height)
    analysis_cycle = refined_cycle_full * scale
    coarse_conf = 1.0 - smoothstep_math(0.75, 2.25, coarse_cycle_analysis)
    refine_conf = 1.0 - smoothstep_math(0.45, 1.50, analysis_cycle)
    return bidirectional * coarse_conf, refine_conf, analysis_cycle

old_window_confidence = 1.0 - smoothstep_math(0.45, 1.50, 1.36)
new_window_confidence, new_window_refine, new_window_cycle = global_registration_confidence_v228(
        1536, 2048, 1.36, 0.0, 1.0)
require(old_window_confidence < 0.16,
        "exact V2.27 window fixture must reproduce the historical global seed kill")
require(new_window_cycle < 0.30 and new_window_confidence > 0.95 and new_window_refine > 0.95,
        "V2.28 must retain a strong coarse-registered high-DR pair despite harmless scaled subpixel asymmetry")
# Opposite regression: true coarse inconsistency remains a hard global failure.
bad_coarse_confidence, _, _ = global_registration_confidence_v228(
        1536, 2048, 0.4, 3.0, 1.0)
require(bad_coarse_confidence < 0.01,
        "V2.28 must still fail closed on genuinely inconsistent coarse bidirectional registration")

# V2.29 bathroom-class source-ownership regression. The prior visual audit measured
# useful exterior LONG levels around encoded 0.17..0.26; V2.28's 0.55 high-white gate
# was identically zero there. V2.29 uses source observability while retaining the same
# multi-scale information-dominance and radiometric plausibility proof.
require('IRIS_V229_INFORMATION_LOSS_NOT_WHITE_GATED_BEGIN' in hdr_shader
        and 'float observableContext = smoothstep(0.08, 0.20, max3(longRgb));' in hdr_shader
        and 'float brightContext = smoothstep(0.55, 0.86, max3(longRgb));' not in hdr_shader
        and 'informationDominance * radiometricPlausibility' in hdr_shader,
        "V2.29 effective information loss must not be synonymous with near-white LONG")
for encoded_long in (0.17, 0.20, 0.26):
    old_context = smoothstep_math(0.55, 0.86, encoded_long)
    new_context = smoothstep_math(0.08, 0.20, encoded_long)
    require(old_context == 0.0 and new_context >= 0.84,
            f"V2.29 bathroom observability regression failed at LONG={encoded_long}: old={old_context} new={new_context}")

# The same connected 16x16 topology cell must be able to contain both final source
# owners. Connectivity is necessary, but full-resolution information loss is decisive.
def v229_final_short_owns(connected, full_loss):
    return connected >= 0.5 and full_loss >= 0.16
require(not v229_final_short_owns(1.0, 0.0) and v229_final_short_owns(1.0, 0.25),
        "V2.29 final ownership collapsed back to whole-cell SHORT blocks")

# V2.29 pre-shoulder HDR-energy regression: recovered scene values above 1.0 must
# remain distinct until adaptiveHdrToneMap, whose stop-domain mapping stays monotonic.
body_tone_slice = hdr_shader[hdr_shader.index('vec3 applyPhotographicBodyTone'):
                             hdr_shader.index('void main()')]
require('max3(rgb) > 1.0' in body_tone_slice
        and 'float gamutScale =' not in body_tone_slice,
        "V2.29 body tone still clips >1 scene energy before the HDR shoulder")
for ratio in (1.0, 2.0, 4.0, 8.0, 16.0):
    peaks = [1.05, 1.25, 1.60, 2.20]
    mapped = [map_peak_math(p, ratio, 0.0) for p in peaks]
    require(all(b > a for a, b in zip(mapped, mapped[1:])),
            f"V2.29 HDR shoulder lost >1 scene ordering at ratio={ratio}: {mapped}")

# V2.29 AUTO low-bracket physical-SNR owner. Preserve independently solved product,
# but LONG may never integrate for less time than SHORT.
auto_order_v229 = camera[camera.index('    private void enforceAutoExposureOrderingLocked(String reason)'):
                         camera.index('    private boolean enforceFrozenExposureOrderingLocked()')]
require('IRIS_V229_LONG_PHYSICAL_SNR_BODY_BEGIN' in auto_order_v229
        and 'if (autoLongExposureNs < autoShortExposureNs)' in auto_order_v229
        and 'autoLongExposureNs = autoShortExposureNs;' in auto_order_v229
        and 'autoLongIso = solveIsoForProduct(longProduct, autoLongExposureNs);' in auto_order_v229,
        "V2.29 AUTO LONG physical-SNR shutter owner missing")

# V2.29 NAFNet: smooth chroma variance is a denoise symptom, not a veto. Universal
# structural protection still uses luma plus R-G/B-G opponent gradients.
naf_smooth = nafnet[nafnet.index('    private static float smoothSourceAuthority('):
                       nafnet.index('    private static float coherentStructureProtection(')]
naf_prepare = nafnet[nafnet.index('    private static void prepareTileAnalysis('):
                        nafnet.index('    private static float smoothSourceAuthority(')]
require('IRIS_V229_CHROMA_NOISE_CANNOT_DISABLE_DENOISER_BEGIN' in naf_smooth
        and 'chromaAuthority' not in naf_smooth
        and 'broadChromaAuthority' not in naf_smooth
        and '1.0f - coherentProtection' in naf_smooth,
        "V2.29 chroma-noise self-disable correction/structure fail-closed contract missing")
require('gxRg' in naf_prepare and 'gyRg' in naf_prepare
        and 'gxBg' in naf_prepare and 'gyBg' in naf_prepare
        and 'tensorXx[i] = gxY * gxY + gxRg * gxRg + gxBg * gxBg;' in naf_prepare,
        "V2.29 universal opponent-chroma structure tensor was weakened")


# Global photographic body tone is tone reproduction only: black stays anchored,
# body/midtones rise, and extra lift is zero before the 0.70 HDR shoulder.
require('applyPhotographicBodyTone' in hdr_shader
        and '0.45 * toe * highlightProtect * y' in hdr_shader
        and 'smoothstep(0.45, 0.68, y)' in hdr_shader
        and 'targetBodyY = bodyY + 0.45f * toe * highlightProtect' in fusion,
        "GPU/CPU photographic body tone curve missing or mismatched")
live_mode_start = hdr_shader.index('// V2.27 live parity: LONG remains the default body source. A near-equal pair')
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
require('versionCode = 55' in build_gradle
        and 'versionName = "1.0-v1.4.11-v2.37"' in build_gradle,
        "V2.37 version/build marker must be exact")

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

# V2.22 exact filament/gray-block regression: one-channel saturation is not whole-pixel
# information loss. SHORT stays recovery-authoritative when it retains more channel
# information or local structure than LONG. Near-clipped interiors stay connected.
def clip_damage(rgb):
    def ss(x):
        t = max(0.0, min(1.0, (x - 0.985) / (0.9995 - 0.985)))
        return t * t * (3.0 - 2.0 * t)
    return sum(ss(v) for v in rgb) / 3.0

short_filament = (1.0, 0.91, 0.74)
long_filament = (1.0, 1.0, 1.0)
require(clip_damage(short_filament) < clip_damage(long_filament),
        "filament fixture must retain more SHORT channel information than LONG")
require(clip_damage((1.0, 0.70, 0.55)) < 0.50,
        "one clipped SHORT channel must not classify the whole RGB sample as lost")
require('float headroom = 1.0 - smoothstep(0.955, 0.992, max3(shortRgb));' not in hdr_shader
        and 'float headroom = 1.0 - smoothstep(0.985, 0.999, max3(shortRgb));' not in hdr_shader,
        "retired max-channel SHORT veto returned")

# V2.22 topology regression preserves V2.20/V2.21 convergence and adds the exact 4x domain-hole failure. The supplied MANUAL failure used
# SHORT 1/1000 ISO50 and LONG 1/100 ISO100 = 20x / 4.32 EV. At the production
# 1/16 atlas, its large hard-loss components contain interior cells roughly 14 cells
# from a valid boundary, far beyond the retired radius-2 one-pass closure.
require('int maxPropagationPasses = Math.min(1024, Math.max(64, analysisWidth * analysisHeight));' in gl
        and 'final int convergenceBatch = 8;' in gl
        and 'int[] counts = countAtlasMasks(' in gl
        and 'if (counts[0] == previousOwned)' in gl,
        "GPU topology must iterate in bounded batches until monotonic occupancy converges")
require('setTextureFilter(evidenceTexture, GLES30.GL_NEAREST);' in gl
        and 'setTextureFilter(supportTexture, GLES30.GL_NEAREST);' in gl
        and 'setTextureFilter(readTopologyTexture, GLES30.GL_LINEAR);' in gl,
        "topology propagation must be nearest/discrete; only final binary boundary lookup may be linear")
count_masks = gl[gl.index('        private int[] countAtlasMasks('):
                 gl.index('        private static void setTextureFilter(')]
require('shortTexture' not in count_masks and 'longTexture' not in count_masks
        and 'glReadPixels' in count_masks,
        "convergence readback may inspect only the small ownership atlas, never CPU-fuse source RGB")

# Mathematical replay of the shader's monotonic geodesic reconstruction. Ratio is
# deliberately not an argument: 4x/20x/64x affect the physical domain size only.
def reconstruct_mask(seed, domain, max_passes=1024):
    h = len(domain)
    w = len(domain[0])
    owned = [row[:] for row in seed]
    previous_count = sum(sum(1 for value in row if value) for row in owned)
    for pass_index in range(max_passes):
        nxt = [row[:] for row in owned]
        for y in range(h):
            for x in range(w):
                if owned[y][x] or not domain[y][x]:
                    continue
                found = False
                for oy in (-1, 0, 1):
                    for ox in (-1, 0, 1):
                        if ox == 0 and oy == 0:
                            continue
                        ny, nx = y + oy, x + ox
                        if 0 <= ny < h and 0 <= nx < w and owned[ny][nx]:
                            found = True
                            break
                    if found:
                        break
                if found:
                    nxt[y][x] = True
        owned = nxt
        current_count = sum(sum(1 for value in row if value) for row in owned)
        if current_count == previous_count:
            return owned, pass_index + 1
        previous_count = current_count
    return owned, max_passes

def enclosed_component(radius):
    side = 2 * radius + 5
    domain = [[False] * side for _ in range(side)]
    seed = [[False] * side for _ in range(side)]
    lo, hi = 2, side - 3
    for y in range(lo, hi + 1):
        for x in range(lo, hi + 1):
            domain[y][x] = True
            if y in (lo, hi) or x in (lo, hi):
                seed[y][x] = True
    return seed, domain

for ratio, radius in ((4.0, 2), (20.0, 14), (64.0, 64)):
    seed, domain = enclosed_component(radius)
    owned, passes = reconstruct_mask(seed, domain)
    require(all((not domain[y][x]) or owned[y][x]
                for y in range(len(domain)) for x in range(len(domain[0]))),
            f"{ratio:g}x connected LONG-loss component retained an internal LONG hole")
    require(passes <= radius + 2,
            f"{ratio:g}x reconstruction did not converge with one-cell geodesic growth")

# The exact 20x failure must be impossible under the new topology: a radius-14
# component cannot be completed by the retired radius-2 closure, but reconstruction
# reaches its center without changing any exposure/ratio-specific threshold.
seed20, domain20 = enclosed_component(14)
center = len(domain20) // 2
require(not any(seed20[y][x]
                for y in range(center - 2, center + 3)
                for x in range(center - 2, center + 3)),
        "20x regression fixture must exceed the retired finite closure radius")
owned20, _ = reconstruct_mask(seed20, domain20)
require(owned20[center][center],
        "exact 20x wide-plateau center must inherit SHORT ownership after convergence")

# Exact V2.20 4x failure class: propagation itself converged, but confidence-shaped
# G contained internal false holes. Geometry confidence may be absent in an interior
# cell without removing that cell from the physical LONG-loss domain.
physical_domain_4x = [[True] * 9 for _ in range(9)]
seed_4x = [[False] * 9 for _ in range(9)]
seed_4x[0][4] = True
registration_supported = [[True] * 9 for _ in range(9)]
registration_supported[4][4] = False
# V2.21 domain is physical, so the unsupported center remains eligible.
require(physical_domain_4x[4][4] and not registration_supported[4][4],
        "4x fixture must contain a registration-confidence hole inside physical loss")
owned4, _ = reconstruct_mask(seed_4x, physical_domain_4x)
require(owned4[4][4],
        "V2.21 physical domain must carry SHORT ownership through the exact 4x confidence hole")

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

# V2.23 - NAFNet-SIDD width32 is a strictly post-fusion denoise owner.
require(nafnet_model.is_file(),
        "NAFNet-SIDD width32 model asset missing")
require(hashlib.sha256(nafnet_model.read_bytes()).hexdigest()
        == "f8fbaa422411683c53e802cf7cc7cf9be0a0de00886ad4af057232e26b172a0c",
        "NAFNet-SIDD width32 model SHA-256 mismatch")
require(nafnet_license.is_file() and "MIT License" in nafnet_license.read_text()
        and "Copyright (c) 2022 megvii-model" in nafnet_license.read_text()
        and "BasicSR" in nafnet_license.read_text() and "Apache License" in nafnet_license.read_text(),
        "NAFNet/BasicSR license notice missing")
require('implementation("com.google.ai.edge.litert:litert:2.1.5")' in build_gradle,
        "V2.23 must pin LiteRT 2.1.5")
require('noCompress += listOf("tflite")' in build_gradle,
        "V2.23 model asset must remain uncompressed for LiteRT asset loading")
require('NafNetDenoiser.denoiseFusedJpeg(context, fused)' in saver
        and 'byte[] finalFused = fused;' in saver
        and 'NAFNET_DENOISE_FALLBACK' in saver
        and 'captureId + "_FUSED_HDR.jpg", "image/jpeg", finalFused' in saver,
        "V2.23 final fused save must attempt NAFNet and fail closed to exact V2.22 bytes")
require('NafNetDenoiser' not in gl and 'NafNetDenoiser' not in fusion and 'NafNetDenoiser' not in camera,
        "NAFNet must not enter HDR alignment, source ownership, shader fusion, or exposure control")
require('MODEL_ASSET = "nafnet_sidd_width32_fp16.tflite"' in nafnet
        and 'EXPECTED_MODEL_SHA256' in nafnet
        and 'TILE = 256' in nafnet and 'HALO = 32' in nafnet
        and 'CORE = TILE - 2 * HALO' in nafnet,
        "V2.23 fixed full-resolution halo tiling contract missing")
require('new CompiledModel.Options(Accelerator.GPU)' in nafnet
        and 'model.createInputBuffers()' in nafnet
        and 'model.createOutputBuffers()' in nafnet
        and 'inputBuffer.writeFloat(input);' in nafnet
        and 'model.run(inputBuffers, outputBuffers);' in nafnet
        and 'outputBuffer.readFloat()' in nafnet,
        "V2.23 GPU-only LiteRT CompiledModel inference contract missing")
require('Bitmap.createScaledBitmap' not in nafnet and '.resize(' not in nafnet,
        "V2.23 must not downscale the fused output for ML inference")
require('MAX_RESIDUAL = 0.12f' in nafnet
        and 'highlightSafeStrength' in nafnet
        and 'if (maxChannel >= 0.985f)' in nafnet,
        "V2.23 bounded-residual/highlight-protection contract missing")
require('synchronized (GPU_LOCK)' in nafnet
        and 'buffer.close()' in nafnet and 'model.close()' in nafnet,
        "V2.23 GPU inference must serialize and close model/buffers deterministically")
require('JpegFusion.encodeJpeg(output)' in nafnet,
        "V2.23 must preserve the existing quality-100 fused JPEG encoder")

# V2.23 permanent packaging regression: the 62 MB model makes the canonical Git
# patch binary. Keep the exact successful full-index binary proof for git apply,
# while GNU patch fuzz=0 replays the text projection with the exact model preseeded
# and hash-pinned. GNU patch must never be asked to consume the Git binary hunk.
require('diff --binary --full-index "$authority" HEAD' in workflow
        and 'diff --binary --full-index HEAD "$authority"' in workflow,
        "V2.23 canonical forward/rollback patches must remain full-index binary Git patches")
require('model_rel=\'app/src/main/assets/nafnet_sidd_width32_fp16.tflite\'' in workflow
        and '":(exclude)$model_rel"' in workflow
        and 'forward-text-$abbrev.patch' in workflow
        and 'rollback-text-$abbrev.patch' in workflow,
        "V2.23 GNU fuzz=0 projection must exclude only the exact pinned binary model")
require("subprocess.run(['git','apply',str(forward)]" in workflow
        and "subprocess.run(['git','apply',str(rollback)]" in workflow,
        "V2.23 full binary forward/rollback must be replayed by git apply")
require("subprocess.run(['patch','-p1','--fuzz=0','-i',str(forward_text)]" in workflow
        and "subprocess.run(['patch','-p1','--fuzz=0','-i',str(rollback_text)]" in workflow,
        "V2.23 GNU text projection must preserve fuzz=0 forward/rollback replay")
require("GNU FUZZ0 INHERITED MODEL SHA FAIL" in workflow
        and "GNU FUZZ0 FORWARD MODEL INVARIANCE FAIL" in workflow
        and "GNU FUZZ0 ROLLBACK MODEL INVARIANCE FAIL" in workflow
        and 'model_dst.unlink()' not in workflow,
        "V2.24 GNU projection must independently pin and preserve the exact inherited model")
require('LEFT PATH SET FAIL' in workflow and 'RIGHT PATH SET FAIL' in workflow,
        "V2.23 patch replay must prove exact path sets as well as byte equality")


# V2.25 - complete-pair presentation persistence and deadband.
require('lastAutoPresentationLongFrame' in camera
        and 'stats.longFrameNumber <= lastAutoPresentationLongFrame' in camera
        and 'AUTO_PRESENT_STABLE_PAIRS = 3' in camera
        and 'AUTO_PRESENT_GAMMA_DEADBAND = 0.075f' in camera
        and 'AUTO_PRESENT_BRIGHTNESS_DEADBAND_EV = 0.10f' in camera,
        "V2.25 paired-presentation persistence/deadband contract missing")
require('haveStagingShort && stagingShortMeta != null' in gl
        and 'meta.frameNumber > stagingShortMeta.frameNumber' in gl
        and 'lastShortMeta = stagingShortMeta;' in gl
        and 'lastLongMeta = meta;' in gl,
        "V2.25 presentation statistics must remain sourced from complete synchronized pairs")
require('AUTO_PRESENT_BRIGHTNESS_STEP_EV = 0.08f' in camera
        and 'AUTO_PRESENT_GAMMA_STEP = 0.025f' in camera,
        "V2.25 presentation convergence must remain slower than the V2.24 0.18EV/0.05 pumping path")

# V2.24 - post-fusion denoise ownership, AF-only touch focus and safe background lifetime.
require('android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING' in manifest
        and 'android:foregroundServiceType="mediaProcessing"' in manifest
        and 'android:name=".HdrProcessingService"' in manifest,
        "V2.24 mediaProcessing foreground service declaration/permission missing")
require('FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING' in service
        and 'START_NOT_STICKY' in service
        and 'NafNetDenoiser' not in service
        and 'HdrGlView' not in service
        and 'JpegFusion' not in service,
        "V2.24 service must own process lifetime only, never image math")
require('onCaptureBackgroundSafe(String captureId)' in camera
        and 'listener.onCaptureBackgroundSafe(id);' in camera
        and '"HDR Captured. You can now move the phone."' in main,
        "V2.24 safe-move toast must be driven by the background-safe capture state")
require('fusionBytesReady = true;' in saver
        and '!shortData.rawReleased || !longData.rawReleased' in saver
        and 'HdrProcessingService.start(context, captureId);' in saver
        and 'backgroundSafe = true;' in saver,
        "V2.24 background-safe state must require fused bytes and released RAW Images")
require('boolean preserveBackgroundProcessing = captureSaver != null && captureSaver.isBackgroundSafe();' in camera
        and 'captureSaver.abort("Camera closed")' in camera
        and 'CAPTURE_BACKGROUND_PRESERVE' in camera,
        "V2.24 camera-close behavior must abort before safe boundary and preserve after it")
require('HdrProcessingService.stop(context);' in saver
        and saver.count('HdrProcessingService.stop(context);') >= 2,
        "V2.24 processing service must stop on both terminal success and failure")
require('CaptureRequest.CONTROL_AF_REGIONS' in camera
        and 'CaptureRequest.CONTROL_AF_TRIGGER_START' in camera
        and 'CaptureRequest.CONTROL_AF_TRIGGER_CANCEL' in camera
        and 'CaptureRequest.CONTROL_AE_REGIONS' not in camera,
        "V2.24 touch focus must be AF-only and must never acquire AE authority")
require('focusAtNormalized(sensorX, sensorY);' in main
        and 'glView.setOnTouchListener(this::handleFocusTouch);' in main,
        "V2.24 preview touch mapping must reach the Camera2 AF owner")
require('out.add(new CameraDescriptor(id, "ID" + id));' in camera
        and 'CAMERA_DISCOVERY' in camera,
        "V2.24 compact ID<n> UI must preserve detailed camera capability logging")
require('denoiseButton.setText("Denoise");' in main
        and 'denoiseButton.setAlpha(denoiseEnabled ? 1.0f : 0.55f);' in main
        and 'captureDenoiseEnabled = denoiseEnabled;' in camera
        and 'captureDenoiseEnabled,' in camera,
        "V2.24 Denoise button label/state and per-capture freeze contract missing")
require('if (denoiseEnabled)' in saver
        and 'bypassed; exact pre-denoise fused JPEG preserved' in saver,
        "V2.24 Denoise OFF must bypass NAFNet and preserve the pre-denoise fused JPEG")
require('RESIDUAL_MEAN_RADIUS = 12' in nafnet
        and 'residualR[tileIndex] - meanR' in nafnet
        and 'residualG[tileIndex] - meanG' in nafnet
        and 'residualB[tileIndex] - meanB' in nafnet,
        "V2.24 broad/DC NAFNet residual suppression missing")
require('smoothSourceAuthority(' in nafnet
        and 'BROAD_SMOOTH_RADIUS = RESIDUAL_MEAN_RADIUS' in nafnet
        and 'broadLumaAuthority' in nafnet
        and 'broadChromaAuthority' not in nafnet[nafnet.index('    private static float smoothSourceAuthority('):nafnet.index('    private static float coherentStructureProtection(')],
        "V2.29 NAFNet must keep broad luma smoothness while chroma noise cannot veto denoising")
require('coherentStructureProtection(' in nafnet
        and 'integralTensorXx' in nafnet
        and '1.0f - coherentProtection' in nafnet
        and 'Positive proof only. Ambiguous texture/noise boundaries fail closed to source.' in nafnet,
        "V2.24 universal coherent-structure protection/fail-closed contract missing")
require('HdrGlView.java' not in '' or True, "internal")
# Protected fusion/runtime SHA pins are enforced in the authoritative workflow; source-level ownership
# additionally forbids the new service/NAFNet/touch path from calling a second fusion implementation.
require('JpegFusion.fuse' not in saver
        and 'fuseStillJpegs(' not in saver
        and 'fuseStillJpegs(' not in gl
        and 'fuseStillRaws(' not in service
        and 'fuseStillRaws(' not in nafnet,
        "V2.30 may not introduce a second HDR fusion owner")
require('V1.4.11-V2.36_to_V1.4.11-V2.37.forward.patch' in workflow
        and 'V1.4.11-V2.37_to_V1.4.11-V2.36.rollback.patch' in workflow,
        "V2.37 final artifact must export correctly named V2.36<->V2.37 patches")
require("authority='1268c56ae19bcff6a8c9bec42fdc9c911a8436d4'" in workflow
        and "authority_tree='9665c112f2ab7c0aa0cc6d05cbce77894cacda24'" in workflow
        and 'test "$(git rev-parse HEAD^)" = "$authority"' in workflow
        and "failed_v236='" not in workflow,
        "V2.37 must prove exact successful V2.36 direct-parent authority; repair-only V2.36 lineage mechanics may not remain active")
require("authority = '1268c56ae19bcff6a8c9bec42fdc9c911a8436d4'" in workflow,
        "V2.37 changed-file allowlist must compare against successful V2.36")
require('273 - Exact V2.36 R1 allowlist regression:' in workflow,
        "V2.36 R1 exact stale-allowlist-authority failure must remain a permanent regression")
require('274 - V2.37 runtime authority is exactly successful V2.36 R1 commit 1268c56ae19bcff6a8c9bec42fdc9c911a8436d4' in workflow,
        "V2.37 exact successful-authority regression missing")
require("if len(tracked) != 35:" in workflow
        and "V1.4.11 V2.36 AUTHORITY REPOSITORY COUNT FAIL" in workflow
        and "POST-BUILD TRACKED COUNT FAIL" in workflow,
        "V2.37 must prove the 35-file V2.36 authority and exact 35-file candidate universe")

require('uniform vec2 stillGlobalShortOffsetPixels;' in hdr_shader
        and 'sampleUv + stillGlobalShortOffsetPixels / imageSize' in hdr_shader
        and 'stillLongUvAt' not in hdr_shader,
        "V2.30 global registration must move SHORT sampling only; LONG geometry remains immutable")
require('FUSED_HDR.jpg is generated from the timestamp-matched SHORT/LONG RAW_SENSOR mosaics' in saver
        and 'HAL JPEGs are saved references only and never feed fusion' in saver,
        "V2.30 metadata must state RAW fusion provenance precisely")
require('CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP' in raw_fusion
        and 'shading.copyGainFactors(shadingRgba, 0);' in raw_fusion
        and 'value >= 1.0f' in raw_fusion,
        "V2.30 RAW carrier must preserve the timestamp-matched physical lens shading map")
require('uniform highp sampler2D shadingTex;' in raw_preprocess_shader
        and 'vec4 shadingMapAt(ivec2 p)' in raw_preprocess_shader
        and 'float signal = normalized * shadingGain;' in raw_preprocess_shader
        and 'float sigma = sqrt(variance) * shadingGain;' in raw_preprocess_shader,
        "V2.32 RAW preprocess must apply lens shading to both sensor signal and physical sigma before WB/demosaic")
require('GLES30.GL_RGBA32F' in gl
        and 'bindSampler2d(rawPreprocessProgram, "shadingTex", shadingTexture, 1);' in gl,
        "V2.32 RAW preprocess must upload and sample the per-frame lens shading map")

# V2.32 bathroom continuation regression. Keep the V2.31 connected exterior recovery,
# but subtract the physical RAW uncertainty before judging SHORT structure advantage.
def v232_effective_loss_fixture(long_peak, short_m, long_m, short_b, long_b, error_ev,
                                short_noise_m, long_noise_m, short_noise_b, long_noise_b):
    short_m = max(short_m - 4.0 * short_noise_m, 0.0)
    long_m = max(long_m - 4.0 * long_noise_m, 0.0)
    short_b = max(short_b - 4.0 * short_noise_b, 0.0)
    long_b = max(long_b - 4.0 * long_noise_b, 0.0)
    observable = smoothstep_math(0.007, 0.033, long_peak)
    medium_structure = smoothstep_math(0.003, 0.020, short_m)
    medium_relative = smoothstep_math(1.03, 1.18, short_m / max(long_m, 0.0025))
    medium_absolute = smoothstep_math(0.0010, 0.012, short_m - long_m)
    medium_dominance = max(0.72 * medium_relative, medium_absolute)
    broad_structure = smoothstep_math(0.006, 0.045, short_b)
    broad_relative = smoothstep_math(1.02, 1.15, short_b / max(long_b, 0.0040))
    broad_absolute = smoothstep_math(0.0020, 0.025, short_b - long_b)
    broad_dominance = max(0.75 * broad_relative, broad_absolute)
    radiometry = 1.0 - smoothstep_math(1.25, 2.75, abs(error_ev))
    return observable * max(
        medium_structure * medium_dominance,
        broad_structure * broad_dominance) * radiometry

bathroom_house_loss = v232_effective_loss_fixture(
    0.42, 0.028, 0.024, 0.055, 0.048, 0.35,
    0.0010, 0.0006, 0.0012, 0.0007)
healthy_body_loss = v232_effective_loss_fixture(
    0.42, 0.022, 0.021, 0.040, 0.039, 0.10,
    0.0010, 0.0006, 0.0012, 0.0007)
require(bathroom_house_loss >= 0.08,
        "V2.32 bathroom house/siding fixture must remain eligible after physical noise subtraction")
require(healthy_body_loss < 0.08,
        "V2.32 healthy near-equal body/noise fixture must remain LONG-owned")
require('float recoveryDomain = step(0.16, broadRecoveryDomainAt(uv));' in hdr_shader
        and 'float shortOwns = connectedRecovery * step(0.08, fullResolutionLoss);' in hdr_shader,
        "V2.32 must retain V2.31 strict-seed/connected-domain/native-resolution ownership thresholds")

# V2.31 exact device failure: a bright-window scene had SHORT=LONG=1/120 ISO50.
# High-DR classification is statistical and must preserve a physical 8x/3EV bracket,
# while a low-DR pair remains eligible for V2.27 near-1x temporal denoise.
require('AUTO_HIGH_DR_MIN_RATIO = 8.0' in camera
        and 'AUTO_HIGH_DR_MIN_SEPARATION_STOPS = 3.0' in camera
        and 'isHighDynamicRangeSceneLocked' in camera
        and 'enforceFrozenHighDynamicRangeBracketLocked' in camera
        and 'solveStillLongSnrSettingForProductLocked' in camera,
        "V2.32 must inherit the successful V2.31 high-DR physical acquisition guard byte-exact")
def high_dr_fixture(p98, short_p99, near_clip, body_p50):
    highlight = max(p98, short_p99)
    body = max(0.00025, body_p50)
    separation = math.log(max(0.0005, highlight) / body, 2.0)
    highlight_pressure = p98 >= 0.55 or near_clip >= 0.0015
    return highlight_pressure and body_p50 <= 0.18 and separation >= 3.0
require(high_dr_fixture(0.90, 0.953, 0.0104, 0.046),
        "exact V2.30 bright-window 0EV device scene must classify as high DR")
require(not high_dr_fixture(0.24, 0.26, 0.0, 0.12),
        "low-DR scene must remain eligible for V2.27 bracket collapse")
short_product = (1_000_000_000 / 120.0) * 50.0
require((short_product * 8.0) / short_product == 8.0,
        "V2.32 high-DR fixture must retain a true 3EV physical LONG/SHORT ratio")

# Exact numerical RAW-domain regression. V2.30 V1.1 compiled successfully while
# black/white were silently expressed in normalized-16-bit units against integer
# usampler2D values. Exercise the actual sensor-code equation numerically, not just
# source tokens, and prove the RGBA8 high/low-byte cache round-trips all 16-bit codes.
def normalize_sensor_code(code, black, white):
    return max(code - black, 0.0) / max(white - black, 1.0e-6)
require(abs(normalize_sensor_code(64.0, 64.0, 1023.0) - 0.0) < 1.0e-12,
        "V2.32 RAW black point must normalize exactly to zero")
mid_signal = normalize_sensor_code(512.0, 64.0, 1023.0)
require(0.46 < mid_signal < 0.48,
        "V2.32 RAW mid-code must remain intermediate rather than saturating")
require(abs(normalize_sensor_code(1023.0, 64.0, 1023.0) - 1.0) < 1.0e-12,
        "V2.32 RAW white level must normalize exactly to one")
for raw16 in range(65536):
    high = raw16 // 256
    low = raw16 - high * 256
    recovered = high * 256 + low
    if recovered != raw16:
        raise SystemExit("V1.4.11 V2.32 REGRESSION FAIL: 16-bit RAW RGBA8 pack round-trip")

# Chroma-only de-alias must preserve center luminance mathematically. These fixtures
# cover both ordinary and bright false-color excursions so moire suppression cannot
# turn into luminance blur or a new highlight edge.
def rgb_from_luma_chroma(y, cb, cr):
    blue = y + cb
    red = y + cr
    green = (y - 0.2126 * red - 0.0722 * blue) / 0.7152
    return red, green, blue
def luma(rgb):
    r, g, b = rgb
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
for y, cb, cr in [(0.18, 0.03, -0.02), (0.72, -0.08, 0.10), (0.93, 0.02, -0.015)]:
    require(abs(luma(rgb_from_luma_chroma(y, cb, cr)) - y) < 1.0e-9,
            "V2.32 chroma reconstruction must preserve center luminance")

# Exact bright-window still-guard solve: at 60-Hz safety, 1/120 ISO50 SHORT plus
# an 8x high-DR requirement must prefer 1/15 ISO50 LONG rather than manufacturing
# the bracket through ISO or collapsing back to the same frame.
period_ns = 8_333_333
short_exposure_ns = 8_333_333
min_iso = 50
required_product = short_exposure_ns * min_iso * 8.0
best = None
best_score = float('inf')
for periods in range(1, 3601):
    exposure = periods * period_ns
    iso = max(min_iso, math.ceil(required_product / exposure))
    if iso > 6400:
        continue
    achieved = exposure * iso
    if achieved + 0.5 < required_product:
        continue
    iso_stops = math.log(max(1.0, iso / min_iso), 2.0)
    overshoot_ev = math.log(achieved / required_product, 2.0)
    shutter_error_ev = abs(math.log(exposure / short_exposure_ns, 2.0))
    score = 10.0 * iso_stops + overshoot_ev + 0.001 * shutter_error_ev
    if score < best_score:
        best_score = score
        best = (exposure, iso)
require(best == (period_ns * 8, 50),
        f"V2.32 bright-window still guard must solve to 1/15 ISO50, got {best}")

# V2.32 exact supplied-DNG noise fixture (2026-09-08 16:05:48). The capture is a
# real 3.06-EV pair: SHORT=2ms ISO50, LONG=8.333333ms ISO100. Camera2/DNG reported
# variance=S*signal+O. Exposure-normalized SHORT uncertainty is therefore materially
# larger than LONG body uncertainty and must never be mistaken for retained structure.
short_noise_s = 3.12719138616e-05
short_noise_o = 4.450756137593575e-07
long_noise_s = 5.4129515027e-05
long_noise_o = 4.5067941007243e-07
real_ratio = (8_333_333.0 * 100.0) / (2_000_000.0 * 50.0)
require(8.32 < real_ratio < 8.34,
        "V2.32 supplied manual-HDR DNG fixture must remain a real approximately 3.06EV pair")
def sigma_model(slope, offset, signal):
    return math.sqrt(max(slope * signal + offset, 0.0))
for long_signal in (0.01, 0.02, 0.05, 0.10, 0.20, 0.50, 0.80):
    short_signal = long_signal / real_ratio
    short_sigma_mapped = real_ratio * sigma_model(short_noise_s, short_noise_o, short_signal)
    long_sigma = sigma_model(long_noise_s, long_noise_o, long_signal)
    require(short_sigma_mapped > 2.0 * long_sigma,
            "V2.32 supplied DNG fixture proves SHORT noise must be normalized before structure ownership")

# Extended-linear carrier regression: >1 source values must survive storage semantics;
# saturation is a separate physical bit, not a consequence of an RGB value reaching 1.
def compand_positive(x, k):
    return math.sqrt(max(x, 0.0) / max(max(x, 0.0) + k, 1e-12))
def expand_positive(e, k):
    e = min(max(e, 0.0), 254.0 / 255.0)
    return k * e * e / max(1.0 - e * e, 1e-12)
for scene_value in (0.0, 0.001, 0.01, 0.1, 0.5, 1.0, 2.0, 4.0):
    encoded = compand_positive(scene_value, 1.0)
    quantized = min(254, max(0, round(encoded * 255.0))) / 255.0
    decoded = expand_positive(quantized, 1.0)
    require(decoded >= 0.0 and math.isfinite(decoded),
            "V2.32 extended-linear carrier decode must remain finite/non-negative")
require(expand_positive(min(254, round(compand_positive(2.0, 1.0) * 255.0)) / 255.0, 1.0) > 1.5,
        "V2.32 extended-linear carrier must preserve source energy materially above 1.0")
for sigma_code in (0, 1, 31, 63, 95, 127):
    for saturation in (0, 1):
        code = sigma_code + 128 * saturation
        recovered_saturation = 1 if code >= 128 else 0
        recovered_sigma = code - 128 * recovered_saturation
        require(recovered_saturation == saturation and recovered_sigma == sigma_code,
                "V2.32 sigma/saturation alpha packing must preserve its explicit physical saturation bit")

# V2.35 exact Claude correction regressions.  Claude analyzed the old Iris Camera
# application, not this Viewfinder app; only its causal CFA contract is ported here.
# Viewfinder fusion/registration/source ownership and the V2.34 carrier/presentation
# remain frozen.  No donor/boundary hue method is added to this root correction.
require('// IRIS_V235_CLAUDE_EXACT_HIGHLIGHT_CALCULATION_SAMPLE_BEGIN' in raw_green_shader
        and 'float physicalSensorAt(ivec2 p)' in raw_green_shader
        and 'int clampPhaseCoordinate(int value, int extent)' in raw_green_shader
        and 'ivec2 phaseClamp(ivec2 p)' in raw_green_shader
        and 'ivec2 sampleP = phaseClamp(q + ivec2(dx, dy));' in raw_green_shader
        and 'smoothstep(highlightClipThreshold, 1.0, sensor)' in raw_green_shader
        and 'const float power = 3.0;' in raw_green_shader
        and '0.5 * (rootR + rootB)' in raw_green_shader
        and 'reconstructed = min(max(reconstructed, calculationFallback), highlightCeiling);' in raw_green_shader,
        "V2.35 must port Claude's exact highlightCalculationSample power-3 opposed-channel guide")
require('// IRIS_V235_CLAUDE_COMMON_QUAD_CLIP_AUTHORITY_BEGIN' in raw_shader
        and 'ivec2 base = ivec2(q.x & ~1, q.y & ~1);' in raw_shader
        and 'for (int oy = 0; oy < 2; ++oy)' in raw_shader
        and 'for (int ox = 0; ox < 2; ++ox)' in raw_shader
        and 'float commonQuadValid = 1.0 - quadHighlightAt(q);' in raw_shader
        and 'float valid = (1.0 - colorValue.z) * commonQuadValid;' in raw_shader,
        "V2.35 must make the physical 2x2 Bayer quad the common opponent-color clipping authority")
require('float valid = step(1.5, validSum);' in raw_shader
        and 'opponentSupportQuality' not in raw_shader,
        "V2.35 opponent confidence must be absolute support, never a ratio against collapsing green support")
require('float saturation = step(0.985, normalized);' in raw_preprocess_shader
        and 'highlightClipThreshold' in raw_green_shader
        and '0.985f);' in gl,
        "V2.35 clip exclusion and highlight guide must share the physical pre-WB 0.985 sensor domain")
require('float signal = normalized * shadingGain;' in raw_preprocess_shader
        and raw_preprocess_shader.find('float signal = normalized * shadingGain;') >= 0
        and 'neutralFallbackBalanced' in raw_shader,
        "V2.35 must retain Viewfinder lens shading before any neutral missing-support terminal state")
require('recoverCensoredBalanced' not in raw_shader
        and 'boundaryChroma' not in raw_shader
        and 'wideOffsets' not in raw_shader
        and 'wideGreenEstimate' not in raw_green_shader,
        "V2.35 Claude root correction must not retain V2.34 boundary/wide-donor hybrid reconstruction")
require('IRIS_V233_SATURATION_CHROMA_BOUNDARY_BEGIN' not in raw_chroma_shader
        and 'wideOffsets[16]' not in raw_chroma_shader
        and 'boundaryC' not in raw_chroma_shader
        and 'IRIS_V236_SINGLE_CHROMA_AUTHORITY' in raw_chroma_shader
        and 'outColor = vec4(encodedScene, carrierAt(p).a);' in raw_chroma_shader,
        "V2.36 must retire the stale distant boundary-hue donor while retaining local luminance-preserving chroma cleanup")

# Exact causal fixture from Claude's verdict: G support collapses while R/B still carry
# the true balanced scene level.  The old failure produced R=B>G; common-quad rejection
# removes those opponent observations, and missing color resolves to one neutral physical
# brightness rather than a guessed hue.
claude_green = 0.962
claude_opponent = 0.519
faulty = [claude_green + claude_opponent, claude_green, claude_green + claude_opponent]
require(abs(faulty[0] - 1.481) < 1e-12 and abs(faulty[2] - 1.481) < 1e-12
        and faulty[0] / faulty[1] > 1.5,
        "Claude causal magenta fixture changed unexpectedly")
valid_sum = 0.0  # common highlighted quad contributes no ordinary opponent sample
ordinary_opponent_valid = 1.0 if valid_sum >= 1.5 else 0.0
require(ordinary_opponent_valid == 0.0,
        "V2.35 common-quad clip gate must make Claude failure opponent support zero")
# Power-3 guide fixture: neutral R/B calculation evidence at L=1.519 reconstructs the
# clipped green to the same L without any boundary donor.
L = 1.519
root_r = L ** (1.0 / 3.0)
root_b = L ** (1.0 / 3.0)
reconstructed_green = (0.5 * (root_r + root_b)) ** 3.0
require(abs(reconstructed_green - L) < 1e-12,
        "V2.35 exact Claude power-3 highlight guide must reconstruct neutral clipped green")
# All four CFA phases in one highlighted parent quad must share one permission decision.
quad_flags = [1.0, 0.0, 0.0, 0.0]
quad_highlight = max(quad_flags)
permissions = [1.0 - quad_highlight for _ in quad_flags]
require(permissions == [0.0, 0.0, 0.0, 0.0],
        "V2.35 common Bayer quad clipping authority may not alternate by CFA phase")


# V2.36 completion regressions from the full V2.35 device-sample audit.
require('IRIS_V236_CALCULATION_WB_EDGE_GREEN_BEGIN' in raw_green_shader
        and 'float edgeGreen(ivec2 p, float center)' in raw_green_shader
        and '0.25 * (2.0 * center - cL2 - cR2)' in raw_green_shader
        and '0.25 * (2.0 * center - cU2 - cD2)' in raw_green_shader
        and 'float blendH = gv / max(gh + gv, 0.0000001);' in raw_green_shader,
        "V2.36 must map the old-Iris edgeGreen geometry into the Viewfinder CFA owner")
require('IRIS_V236_CALCULATION_WB_OPPONENT_DOMAIN' in raw_shader
        and 'float calculationGain = calculationWbForColor(colorAt(q));' in raw_shader
        and 'calculationColor - greenValue.x' in raw_shader
        and 'vec3 balancedRgb = sensorRgb * commonGreenGain;' in raw_shader,
        "V2.36 must form opponent chroma in calculation-WB space and restore the common green scale exactly once")
require('IRIS_V236_PHASE_INVARIANT_CENSORED_ROLLOFF' in raw_shader
        and 'return 0.25 * count;' in raw_shader
        and 'float neutralMix = smoothstep(0.0, 0.75, censoredFraction);' in raw_shader
        and 'if (completeColorSupport < 0.5)' not in raw_shader,
        "V2.36 terminal censored fallback must not reprint the strict 2x2 permission gate as a hard RGB block")
require('IRIS_V236_AUTO_NEUTRAL_PRESENTATION_POLICY' in camera
        and 'AUTO_PRESENT_BRIGHTNESS_MAX_EV = 0.00f' in camera
        and 'AUTO_PRESENT_GAMMA_MAX = 1.65f' in camera,
        "V2.36 AUTO must use Manual-like neutral presentation ceilings without copying another scene's histogram")

# Exact V2.35 edge-domain failure fixture.  The R photosite lies on a brighter
# neutral side of an edge (raw R=.40 -> calculation-WB R=.80, local G=.80),
# while the target green-site geometry is .50.  In the correct old-Iris domain
# the neutral opponent is zero and reconstructed R stays .50.  V2.35 instead
# formed raw-R minus calculation-G (-.40), added that to target G, then applied
# 2x R WB, producing .20: a .30 false chroma edge from the same sensor evidence.
raw_r = 0.40
local_green = 0.80
target_green = 0.50
wb_r_norm = 2.0
correct_rg = raw_r * wb_r_norm - local_green
correct_r = target_green + correct_rg
require(abs(correct_r - 0.50) < 1e-12,
        "V2.36 calculation-WB opponent fixture no longer preserves a neutral edge")
faulty_rg = raw_r - local_green
faulty_r = (target_green + faulty_rg) * wb_r_norm
require(abs(faulty_r - correct_r) > 0.25,
        "V2.35 WB-domain failure fixture unexpectedly stopped exposing edge-color amplification")

one_phase_fraction = 0.25
neutral_mix_one_phase = smoothstep_math(0.0, 0.75, one_phase_fraction)
require(0.0 < neutral_mix_one_phase < 0.5 and ordinary_opponent_valid == 0.0,
        "V2.36 must separate strict opponent rejection from smooth terminal chroma rolloff")

# V2.37 manual slider/live-preview and extreme-emitter presentation regressions.
require('IRIS_V237_MANUAL_UI_PRESENTATION_OWNER_BEGIN' in main,
        'V2.37 manual UI ownership marker missing')
manual_callback = main[main.index('    public void onPresentationSettings('):
        main.index('    public void onCaptureBackgroundSafe(', main.index('    public void onPresentationSettings('))]
require(manual_callback.count('brightnessBar.setProgress(') == 1
        and manual_callback.count('gammaBar.setProgress(') == 1
        and manual_callback.count('glView.setDisplayBrightnessEv(') == 1
        and manual_callback.count('glView.setDisplayGamma(') == 1
        and 'if (automatic) {' in manual_callback,
        'V2.37 presentation callback may update B/G bars/uniforms only inside AUTO ownership')
require('IRIS_V237_MANUAL_PRESENTATION_OWNERSHIP_BEGIN' in camera,
        'V2.37 controller manual presentation ownership marker missing')
publish_start = camera.index('    private void publishPresentationLocked(boolean automatic) {')
publish_end = camera.index('    private static float extremeEmitterPressureLocked(', publish_start)
publish_block = camera[publish_start:publish_end]
require('if (automatic) {' in publish_block
        and publish_block.count('stillFusionView.setDisplayBrightnessEv(') == 1
        and publish_block.count('stillFusionView.setDisplayGamma(') == 1
        and publish_block.count('stillFusionView.setDisplayEnhancement(') == 1,
        'V2.37 controller must let manual sliders own B/G while retaining automatic Dehaze/Micro ownership')

split_start = hdr_shader.index('    if (mode == 1) {')
split_end = hdr_shader.index('\n    if (haveShort == 0 || haveLong == 0) {', split_start) + 1
split_mode = hdr_shader[split_start:split_end]
require('IRIS_V237_SPLIT_MANUAL_BG_PREVIEW_BEGIN' in split_mode
        and 'splitBrightnessGain' in split_mode
        and 'applyDisplayGamma(splitLinear, displayGamma)' in split_mode,
        'V2.37 SPLIT must visibly consume user-owned manual Brightness/Gamma')
require('displayDehaze' not in split_mode
        and 'displayMicroContrast' not in split_mode
        and 'applyAdaptiveClarity' not in split_mode
        and 'applySplitAdaptiveClarity' not in split_mode,
        'V2.37 SPLIT must not introduce Dehaze/Micro or clarity processing')
require('applyPhotographicBodyTone' not in split_mode
        and 'adaptiveHdrToneMap' not in split_mode
        and 'savedContinuousHdrToneMap' not in split_mode,
        'V2.37 SPLIT preview must not import FUSED tone/fusion architecture')
require('splitPresentationGuideLumaAt' not in hdr_shader
        and 'applySplitAdaptiveClarity' not in hdr_shader,
        'V2.37 approved scope forbids a new per-half SPLIT clarity authority')

# Exact successful V2.36 FUSED rendering remains byte-frozen while SPLIT gains preview controls.
mode6_start = hdr_shader.index('    if (mode == 6) {')
mode6_end = hdr_shader.index('        // IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_END\n        return;\n    }', mode6_start) + len('        // IRIS_V217_TOPOLOGY_SAFE_PRESENTATION_END\n        return;\n    }')
require(sha_text(hdr_shader[mode6_start:mode6_end])
        == 'beefb67f773372e4011359b34f7d8a9b2f711a74bb7c36ffc7e4a30b4902f0c5',
        'V2.37 may not change successful V2.36 mode-6 saved post-fusion presentation')
mode5_start = hdr_shader.index('    if (mode == 5) {')
mode5_end = hdr_shader.index('        return;\n    }\n\n    // V2.27 live parity', mode5_start) + len('        return;\n    }')
require(sha_text(hdr_shader[mode5_start:mode5_end])
        == '2abf93c31853d6b0093dca2ca003ea0cb77798f190920e1e98c3c917f61dbb34',
        'V2.37 may not change successful V2.36 saved FUSED source/tone path')
live_start = hdr_shader.index('    // V2.27 live parity:')
require(sha_text(hdr_shader[live_start:])
        == 'd6e7a12c86e37fbe25fdf5b8a607c57f38329c5f19051c2f5fe7034d98f8ca43',
        'V2.37 may not change successful V2.36 live FUSED shader math')

require('IRIS_V237_EXTREME_EMITTER_PRESENTATION_BEGIN' in camera
        and 'EXTREME_EMITTER_BRIGHTNESS_EV = -1.40f' in camera
        and 'EXTREME_EMITTER_GAMMA = 1.15f' in camera
        and 'extremeEmitterPressureLocked(stats, physicalRatio)' in camera,
        'V2.37 direct-sun style gate/presentation anchors missing')

# Device-derived 32x24 fixture. The direct-sun set is the only provided sample with
# a real 3EV bracket, >20% LONG clip population, and SHORT P99 still above ~0.52.
# Costco/restaurant/bright-car negatives remain exactly zero: no global daylight style.
def extreme_emitter_pressure(short_p99, short_near_clip, long_near_clip, ratio):
    bracket_stops = math.log(max(ratio, 1.0), 2.0)
    short_survival = max(
        smoothstep_math(0.38, 0.52, short_p99),
        smoothstep_math(0.0005, 0.0025, short_near_clip))
    long_clip_mass = smoothstep_math(0.08, 0.20, long_near_clip)
    real_bracket = smoothstep_math(2.60, 3.00, bracket_stops)
    return max(0.0, min(1.0, short_survival * long_clip_mass * real_bracket))

sun_pressure = extreme_emitter_pressure(0.528529, 1.0/768.0, 0.308594, 8.0)
car_pressure = extreme_emitter_pressure(0.361987, 0.0, 0.072917, 8.0)
costco_pressure = extreme_emitter_pressure(0.083083, 0.0, 0.0, 2.2)
restaurant_pressure = extreme_emitter_pressure(0.093853, 0.0, 0.0, 2.4)
require(sun_pressure > 0.99,
        f'V2.37 direct-sun fixture must fully engage extreme-emitter style: {sun_pressure}')
require(car_pressure == 0.0 and costco_pressure == 0.0 and restaurant_pressure == 0.0,
        'V2.37 extreme-emitter style leaked into provided non-sun scene fixtures')

normal_brightness = -3.0
normal_gamma = 1.45
sun_brightness = normal_brightness + (-1.40 - normal_brightness) * sun_pressure
sun_gamma = normal_gamma + (1.15 - normal_gamma) * sun_pressure
require(abs(sun_brightness + 1.40) < 1e-6 and abs(sun_gamma - 1.15) < 1e-6,
        'V2.37 full direct-sun pressure must reproduce validated -1.4EV/gamma1.15 style')
require(normal_brightness + (-1.40 - normal_brightness) * car_pressure == normal_brightness
        and normal_gamma + (1.15 - normal_gamma) * car_pressure == normal_gamma,
        'V2.37 non-sun fixture must preserve V2.36 AUTO target exactly')

# V2.34 redistributes the SAME RGBA8 RGB carrier; no extra full-resolution texture is
# permitted.  The exact 1..8 recovered-highlight interval must receive at least twice
# V2.33's distinct code levels while shadows and explicit sigma/saturation alpha remain.
require('CARRIER_BODY_END = CARRIER_MAX * 0.42' in raw_shader
        and 'CARRIER_DETAIL_END = CARRIER_MAX * 0.92' in raw_shader
        and 'CARRIER_DETAIL_TOP = 8.0' in raw_shader
        and 'CARRIER_TAIL_TOP = 32.0' in raw_shader,
        "V2.34 precision carrier constants missing from RAW producer")
require('IRIS_V234_HIGHLIGHT_PRECISION_CARRIER_BEGIN' in raw_chroma_shader
        and 'decodeSceneChannel' in raw_chroma_shader
        and 'encodeSceneChannel' in raw_chroma_shader,
        "V2.34 chroma pass must decode/re-encode the same precision carrier")
require('decodeSceneChannel' in raw_proxy_shader
        and 'rawCarrierBodyEnd' in hdr_shader
        and 'decodeRawSceneChannel' in hdr_shader,
        "V2.34 registration proxy and HDR consumer must decode the same precision carrier")
require('allocateRgbTexture(shortTexture, width, height);' in gl
        and 'allocateRgbTexture(longTexture, width, height);' in gl
        and 'allocateRgbTexture(presentationTexture, width, height);' in gl
        and 'allocateRgbTexture(outputTexture, width, height);' in gl
        and 'GL_RGBA16F' not in gl,
        "V2.34 must improve highlight precision without changing V2.33 full-resolution allocation count/format")

carrier_max=254.0/255.0
carrier_body_end=carrier_max*0.42
carrier_detail_end=carrier_max*0.92

def v234_carrier_encode(x):
    x=max(x,0.0)
    if x <= 1.0:
        return carrier_body_end*math.sqrt(x)
    if x <= 8.0:
        return carrier_body_end+(carrier_detail_end-carrier_body_end)*(math.log(x,2.0)/3.0)
    tail_stops=max(0.0,min(2.0,math.log(x/8.0,2.0)))
    return min(carrier_max,carrier_detail_end+(carrier_max-carrier_detail_end)*(tail_stops/2.0))

def v234_carrier_decode(e):
    e=max(0.0,min(carrier_max,e))
    if e <= carrier_body_end:
        t=e/max(carrier_body_end,1e-12); return t*t
    if e <= carrier_detail_end:
        t=(e-carrier_body_end)/max(carrier_detail_end-carrier_body_end,1e-12)
        return 2.0**(3.0*t)
    t=max(0.0,min(1.0,(e-carrier_detail_end)/max(carrier_max-carrier_detail_end,1e-12)))
    return 8.0*(2.0**(2.0*t))

def v233_carrier_encode(x):
    return min(carrier_max,math.sqrt(max(x,0.0)/max(x+1.0,1e-12))) if x > 0 else 0.0

new_codes=len({round(v234_carrier_encode(2.0**(3.0*i/10000.0))*255.0) for i in range(10001)})
old_codes=len({round(v233_carrier_encode(2.0**(3.0*i/10000.0))*255.0) for i in range(10001)})
require(new_codes >= 120 and new_codes >= 2*old_codes,
        f"V2.34 1..8 highlight carrier precision insufficient: new={new_codes} old={old_codes}")
for x in (1.0,2.0,4.0,8.0,16.0):
    code=max(0,min(254,round(v234_carrier_encode(x)*255.0)))
    restored=v234_carrier_decode(code/255.0)
    require(math.isfinite(restored) and abs(restored-x)/x < 0.04,
            f"V2.34 carrier round-trip too coarse at {x}: {restored}")
require(v234_carrier_decode(carrier_max) <= 32.000001,
        "V2.34 extreme specular carrier must remain finite/bounded above the 1..8 detail interval")

# Saved presentation is now a single continuous radiance transfer after fusion. It may
# not consume shortOwns or any topology mask; this removes the V2.33 owner-boundary ring
# mechanism while keeping the shared/live V2.33 adaptiveHdrToneMap byte-identical.
require('// IRIS_V234_CONTINUOUS_SAVED_HIGHLIGHT_PRESENTATION_BEGIN' in hdr_shader
        and 'savedContinuousHdrToneMap(' in hdr_shader
        and 'savedRecoveredHdrToneMap(' not in hdr_shader
        and hdr_shader.count('savedContinuousHdrToneMap(') == 2
        and hdr_shader.count('adaptiveHdrToneMap(bodyToned, ratio, bracketStops)') == 1,
        "V2.34 saved presentation must use one owner-independent transfer; live remains inherited")
mode5_after_merge=mode5[mode5.index('vec3 mergedScene = shortOwns > 0.5 ? shortScene : temporalBody;'):]
require('savedContinuousHdrToneMap(\n            bodyToned, ratio, bracketStops);' in mode5_after_merge
        and 'savedContinuousHdrToneMap(\n            bodyToned, ratio, bracketStops, shortOwns' not in mode5_after_merge,
        "V2.34 post-fusion presentation may not expose the binary shortOwns mask")

def v234_hdr_peak(scene_peak, detail_stops=3.0):
    knee=0.70; top=0.930; bias=0.35
    if scene_peak <= knee: return scene_peak
    hs=max(math.log(scene_peak/knee,2.0),0.0)
    if hs <= detail_stops:
        t=max(0.0,min(1.0,hs/detail_stops))
        shaped=t+bias*t*(1.0-t)
        return knee+(top-knee)*max(0.0,min(1.0,shaped))
    tail=hs-detail_stops
    end=max(1.0-bias,0.05)
    scale=(1.0-top)*detail_stops/((top-knee)*end)
    return top+(1.0-top)*(1.0-math.exp(-tail/scale))

for x in (0.0,0.1,0.35,0.69,0.70):
    require(abs(v234_hdr_peak(x)-x) < 1e-12,
            "V2.34 saved highlight transfer must be identity through the protected body/knee")
prev=-1.0
for i in range(1,5001):
    x=0.70*math.exp(math.log(64.0/0.70)*i/5000.0)
    y=v234_hdr_peak(x)
    require(y + 1e-12 >= prev,
            "V2.34 saved highlight transfer must remain strictly monotonic/non-reversing")
    prev=y
require(v234_hdr_peak(1.0) > 0.74 and v234_hdr_peak(2.0) > 0.82,
        "V2.34 ceiling/X fixture lost lower/middle highlight separation")
require(v234_hdr_peak(4.0) < 0.91 and v234_hdr_peak(5.6) <= 0.931,
        "V2.34 chandelier ceiling core remains a detached overbright island")
require(v234_hdr_peak(16.0) < 0.99,
        "V2.34 must reserve smooth headroom above the ceiling gradient for true lamp cores")

print("V1.4.11 V2.37 REGRESSION PASS: exact successful V2.36 CFA/fusion/saved-FUSED mechanics preserved; manual Brightness/Gamma ownership is live and non-echoing; SPLIT previews only manual B/G while auto Dehaze/Micro remain FUSED-owned; direct-sun extreme-emitter style is physically gated and zero for provided negative scenes")
