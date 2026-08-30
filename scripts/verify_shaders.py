#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SHADER_DIR = ROOT / "app/src/main/assets/shaders"

# GLSL / GLSL ES keywords plus reserved/future-use words that must never be used as identifiers.
RESERVED = set("""
attribute const uniform varying buffer shared coherent volatile restrict readonly writeonly
atomic_uint layout centroid flat smooth noperspective patch sample break continue do for while
switch case default if else subroutine in out inout float double int void bool true false invariant
precise discard return mat2 mat3 mat4 dmat2 dmat3 dmat4 mat2x2 mat2x3 mat2x4 mat3x2 mat3x3 mat3x4
mat4x2 mat4x3 mat4x4 dmat2x2 dmat2x3 dmat2x4 dmat3x2 dmat3x3 dmat3x4 dmat4x2 dmat4x3 dmat4x4
vec2 vec3 vec4 ivec2 ivec3 ivec4 bvec2 bvec3 bvec4 dvec2 dvec3 dvec4 uvec2 uvec3 uvec4
lowp mediump highp precision sampler1D sampler2D sampler3D samplerCube sampler1DShadow
sampler2DShadow samplerCubeShadow sampler1DArray sampler2DArray sampler1DArrayShadow
sampler2DArrayShadow isampler1D isampler2D isampler3D isamplerCube isampler1DArray isampler2DArray
usampler1D usampler2D usampler3D usamplerCube usampler1DArray usampler2DArray sampler2DRect
sampler2DRectShadow isampler2DRect usampler2DRect samplerBuffer isamplerBuffer usamplerBuffer
sampler2DMS isampler2DMS usampler2DMS sampler2DMSArray isampler2DMSArray usampler2DMSArray
samplerCubeArray samplerCubeArrayShadow isamplerCubeArray usamplerCubeArray image1D iimage1D uimage1D
image2D iimage2D uimage2D image3D iimage3D uimage3D image2DRect iimage2DRect uimage2DRect
imageCube iimageCube uimageCube imageBuffer iimageBuffer uimageBuffer image1DArray iimage1DArray
uimage1DArray image2DArray iimage2DArray uimage2DArray imageCubeArray iimageCubeArray uimageCubeArray
image2DMS iimage2DMS uimage2DMS image2DMSArray iimage2DMSArray uimage2DMSArray struct
common partition active asm class union enum typedef template this resource goto inline noinline
public static extern external interface long short half fixed unsigned superp input output hvec2 hvec3
hvec4 fvec2 fvec3 fvec4 sampler3DRect filter sizeof cast namespace using row_major
""".split())

TYPE = r"(?:float|double|int|uint|bool|vec[234]|ivec[234]|uvec[234]|bvec[234]|mat[234](?:x[234])?|sampler\w+|void)"
QUAL = r"(?:(?:const|uniform|in|out|inout|flat|smooth|centroid|highp|mediump|lowp|precision|layout\s*\([^)]*\))\s+)*"

failures = []
for path in sorted(SHADER_DIR.glob("*")):
    if path.suffix not in {".vert", ".frag", ".glsl"}:
        continue
    text = re.sub(r"//.*?$|/\*.*?\*/", "", path.read_text(), flags=re.M | re.S)
    declared = set()
    # Variables and interface declarations.
    for m in re.finditer(rf"\b{QUAL}{TYPE}\s+([A-Za-z_]\w*)\b", text):
        declared.add(m.group(1))
    # Function names (return type followed by identifier and opening parenthesis).
    for m in re.finditer(rf"\b{TYPE}\s+([A-Za-z_]\w*)\s*\(", text):
        declared.add(m.group(1))
    bad = sorted(name for name in declared if name in RESERVED)
    if bad:
        failures.append(f"{path.relative_to(ROOT)}: reserved declared identifier(s): {', '.join(bad)}")
    else:
        print(f"RESERVED IDENTIFIER PASS: {path.relative_to(ROOT)} ({len(declared)} declared identifiers scanned)")

if failures:
    print("\n".join(failures), file=sys.stderr)
    raise SystemExit(1)
print("GLSL RESERVED IDENTIFIER SCAN PASS")
