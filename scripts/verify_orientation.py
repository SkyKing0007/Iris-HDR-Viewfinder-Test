#!/usr/bin/env python3
from pathlib import Path
import math
import re

ROOT = Path(__file__).resolve().parents[1]
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
main = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/MainActivity.java").read_text()
gl = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/HdrGlView.java").read_text()
shader = (ROOT / "app/src/main/assets/shaders/hdr_display.frag").read_text()
camera = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/CameraController.java").read_text()
fusion = (ROOT / "app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java").read_text()


def require(condition, message):
    if not condition:
        raise SystemExit("ORIENTATION REGRESSION FAIL: " + message)

# 004: respect user orientation policy; never force the historical landscape-only mode.
require('android:screenOrientation="fullUser"' in manifest,
        "activity must use fullUser so Android auto-rotate and user rotation lock remain authoritative")
require('android:screenOrientation="landscape"' not in manifest,
        "forced landscape returned")
require('android:configChanges=' not in manifest,
        "activity must recreate through the normal Android configuration lifecycle on rotation")
require('Configuration.ORIENTATION_PORTRAIT' in main,
        "portrait-specific responsive controls missing")
require('buildPortraitControls' in main and 'buildLandscapeControls' in main,
        "both responsive UI layouts must exist")
require(re.search(r'root\.addView\(glView, new LinearLayout\.LayoutParams\(\s*ViewGroup\.LayoutParams\.MATCH_PARENT,\s*0,\s*1f\)\)', main, re.S),
        "preview must occupy measured remaining space instead of sitting under an overlay panel")
require('onSaveInstanceState' in main and 'restoreUiState' in main,
        "camera/mode/exposure UI state must survive orientation recreation")

# 005: top-left Camera2 YUV memory needs one GL-origin correction, not two.
require('rawUvBuffer' in gl and 'displayUvBuffer' in gl,
        "raw and display UV ownership must stay separate")
require('float[] rawUvs = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};' in gl,
        "Camera2 YUV top-left origin correction changed unexpectedly")
require('float[] displayUvs = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};' in gl,
        "final display pass must use ordinary GL UV coordinates")
require('bindQuad(rawUvBuffer);' in gl,
        "YUV->RGB pass must own the single vertical-origin correction")
require('bindQuad(displayUvBuffer);' in gl,
        "final display pass must not repeat the YUV origin correction")
require(gl.count('bindQuad(rawUvBuffer);') == 1 and gl.count('bindQuad(displayUvBuffer);') == 1,
        "origin correction pass count changed")

# 007: still JPEGs and fused JPEG must follow the same upright device orientation.
require('JPEG_ORIENTATION, 0' not in camera,
        "still capture must not hard-code JPEG orientation to zero")
require(camera.count('CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees') == 2,
        "SHORT and LONG still requests must use the shared device-relative JPEG orientation")
require('int previewRotation = (sensorOrientation + displayDegrees + 360) % 360;' in main,
        "back-camera preview must use Android Display#getRotation counterclockwise convention")
require('int jpegOrientation = (sensorOrientation - displayDegrees + 360) % 360;' in main,
        "still JPEG orientation must convert Display rotation to the JPEG clockwise convention")
require('glView.setRelativeRotationDegrees(previewRotation);' in main,
        "computed preview rotation must reach the GL display owner")
require('setJpegOrientationDegrees(jpegOrientation);' in main,
        "computed still orientation must reach CameraController")
require('ExifInterface.TAG_ORIENTATION' in fusion and 'decodeUpright(shortJpeg)' in fusion
        and 'decodeUpright(longJpeg)' in fusion,
        "fused JPEG must normalize EXIF-only HAL rotation before pixel fusion")

# 006: normal/HDR and split modes must preserve rotated image aspect rather than stretch.
for token in ('uniform vec2 fullCropScale;', 'uniform vec2 splitCropScale;',
              'centerCropUv(vUv, fullCropScale)', 'centerCropUv(localUv, splitCropScale)'):
    require(token in shader, f"aspect-preserving shader token missing: {token}")
require('if (rotationQuarterTurns == 1) return vec2(1.0 - uv.y, uv.x);' in shader,
        "quarter-turn 1 must display the source 90 degrees clockwise")
require('if (rotationQuarterTurns == 3) return vec2(uv.y, 1.0 - uv.x);' in shader,
        "quarter-turn 3 must display the source 270 degrees clockwise")
require('setCropScaleUniform(displayProgram, "fullCropScale", surfaceWidth, surfaceHeight);' in gl,
        "full-preview crop scale binding missing")
require('setCropScaleUniform(displayProgram, "splitCropScale", surfaceWidth * 0.5f, surfaceHeight);' in gl,
        "split-preview crop scale binding missing")


# Display#getRotation is counterclockwise from the user's point of view.
# This app enumerates back-facing cameras only: preview uses sensor + display;
# JPEG orientation uses sensor - display because JPEG's device angle is clockwise.
for sensor in (90, 270):
    for display in (0, 90, 180, 270):
        preview_rotation = (sensor + display + 360) % 360
        jpeg_orientation = (sensor - display + 360) % 360
        require(preview_rotation in (0, 90, 180, 270),
                f"invalid preview rotation {preview_rotation}")
        require(jpeg_orientation in (0, 90, 180, 270),
                f"invalid JPEG orientation {jpeg_orientation}")

def crop_scale(frame_w, frame_h, quarter_turns, viewport_w, viewport_h):
    quarter = quarter_turns & 1
    rotated_w = frame_h if quarter else frame_w
    rotated_h = frame_w if quarter else frame_h
    image_aspect = rotated_w / rotated_h
    viewport_aspect = viewport_w / viewport_h
    sx = sy = 1.0
    if viewport_aspect > image_aspect:
        sy = image_aspect / viewport_aspect
    elif viewport_aspect < image_aspect:
        sx = viewport_aspect / image_aspect
    return sx, sy, image_aspect, viewport_aspect

# Representative 16:9 sensor preview in landscape, portrait, and tall-screen portrait.
for args in [
    (1280, 720, 0, 1920, 1080),
    (1280, 720, 1, 1080, 1920),
    (1280, 720, 1, 1080, 2200),
    (1280, 720, 0, 2200, 1080),
    (1280, 720, 1, 540, 1920),  # one portrait split half
]:
    sx, sy, image_aspect, viewport_aspect = crop_scale(*args)
    require(0.0 < sx <= 1.0 and 0.0 < sy <= 1.0,
            f"invalid crop scale {sx},{sy} for {args}")
    visible_aspect = image_aspect * sx / sy
    require(math.isclose(visible_aspect, viewport_aspect, rel_tol=1e-6, abs_tol=1e-6),
            f"crop math would stretch: visible={visible_aspect} viewport={viewport_aspect} args={args}")

# Quarter-turn UV mappings must remain bounded and map corners bijectively.
def rotate_uv(uv, q):
    # Shader mapping is output UV -> source UV. Positive q means the displayed
    # source image is rotated clockwise by q * 90 degrees.
    x, y = uv
    if q == 1:
        return 1.0 - y, x
    if q == 2:
        return 1.0 - x, 1.0 - y
    if q == 3:
        return y, 1.0 - x
    return x, y

corners = [(0.0, 0.0), (1.0, 0.0), (0.0, 1.0), (1.0, 1.0)]
for q in range(4):
    mapped = [rotate_uv(p, q) for p in corners]
    require(len(set(mapped)) == 4, f"rotation {q} collapses corners")
    require(all(0.0 <= v <= 1.0 for p in mapped for v in p), f"rotation {q} leaves UV range")

# For q=1, source top-left must land at output top-right: a clockwise quarter-turn.
def output_for_source(source, q):
    matches = [out for out in corners if rotate_uv(out, q) == source]
    require(len(matches) == 1, f"rotation {q} does not invert uniquely for source {source}")
    return matches[0]

require(output_for_source((0.0, 1.0), 1) == (1.0, 1.0),
        "q=1 shader direction is not clockwise")

print("ORIENTATION REGRESSION PASS: responsive policy, single origin correction, aspect crop, and upright still JPEGs")
