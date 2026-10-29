#!/usr/bin/env python3
"""
Turns CC0 PBR texture sets (Poly Haven, ambientCG) into the app's material format in
app/src/main/assets/tex (see ui/world/geo/Materials.kt):

  <name>.webp     albedo, sRGB
  <name>_n.webp   tangent-space normal, OpenGL convention (+Y up)
  <name>_m.webp   R = ambient occlusion, G = roughness, B = height

Usage: convert.py MAPPING.json SRC_DIR TEX_DIR
MAPPING maps an app texture name to a source set folder (under SRC_DIR) and optional options:
  {"asphalt_fresh": {"src": "polyhaven/asphalt_02", "tint": [1.0, 1.0, 1.0]}, ...}
Output sizes follow the file that is replaced, so memory use on the phone stays as it was.
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageOps

# Substrings that identify each map in Poly Haven (_diff_2k, _nor_gl_2k, _rough_2k, _ao_2k, _disp_2k)
# and ambientCG (_Color, _NormalGL, _Roughness, _AmbientOcclusion, _Displacement) file names.
KINDS = {
    "albedo": ["_diff", "_color", "_albedo", "_basecolor", "_col"],
    "normal_gl": ["_nor_gl", "_normalgl"],
    "normal_dx": ["_nor_dx", "_normaldx"],
    "rough": ["_rough", "_roughness"],
    "ao": ["_ao", "_ambientocclusion"],
    "height": ["_disp", "_displacement", "_height"],
    "arm": ["_arm"],
}


def find_maps(folder: Path) -> dict:
    maps = {}
    for f in sorted(folder.rglob("*")):
        if f.suffix.lower() not in (".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff", ".exr"):
            continue
        n = f.stem.lower()
        for kind, keys in KINDS.items():
            if kind not in maps and any(k in n for k in keys):
                maps[kind] = f
                break
    return maps


def load(path: Path, size, mode):
    im = Image.open(path)
    if im.mode in ("I;16", "I;16B", "I"):
        im = im.point(lambda v: v / 256).convert("L")
    im = im.convert(mode)
    # A non-square target (a kerb, 2 x 0.5 m) gets the seamless square source repeated along its long
    # side instead of squashed, so the texture keeps its real scale.
    w, h = size
    rx, ry = max(1, round(w / h)), max(1, round(h / w))
    if rx > 1 or ry > 1:
        tiled = Image.new(mode, (im.width * rx, im.height * ry))
        for i in range(rx):
            for j in range(ry):
                tiled.paste(im, (i * im.width, j * im.height))
        im = tiled
    return im.resize(size, Image.LANCZOS)


def convert(name: str, opts: dict, src_root: Path, tex: Path):
    folder = src_root / opts["src"]
    maps = find_maps(folder)
    if "albedo" not in maps:
        raise SystemExit(f"{name}: no albedo in {folder}")
    old = tex / f"{name}.webp"
    size = Image.open(old).size if old.exists() else tuple(opts.get("size", (1024, 1024)))
    old_m = tex / f"{name}_m.webp"
    msize = Image.open(old_m).size if old_m.exists() else size

    albedo = load(maps["albedo"], size, "RGB")
    tint = opts.get("tint")
    if tint:
        r, g, b = albedo.split()
        albedo = Image.merge("RGB", [c.point(lambda v, t=t: min(255, int(v * t))) for c, t in zip((r, g, b), tint)])
    albedo.save(tex / f"{name}.webp", quality=88, method=6)

    if "normal_gl" in maps:
        normal = load(maps["normal_gl"], size, "RGB")
    elif "normal_dx" in maps:
        r, g, b = load(maps["normal_dx"], size, "RGB").split()
        normal = Image.merge("RGB", (r, ImageOps.invert(g), b))
    else:
        normal = Image.new("RGB", size, (128, 128, 255))
    normal.save(tex / f"{name}_n.webp", quality=92, method=6)

    if "arm" in maps:
        ao, rough, _ = load(maps["arm"], msize, "RGB").split()
    else:
        ao = load(maps["ao"], msize, "L") if "ao" in maps else Image.new("L", msize, 255)
        rough = load(maps["rough"], msize, "L") if "rough" in maps else Image.new("L", msize, int(opts.get("roughness", 0.8) * 255))
    height = load(maps["height"], msize, "L") if "height" in maps else Image.new("L", msize, 128)
    height = ImageOps.autocontrast(height, cutoff=1)
    Image.merge("RGB", (ao, rough, height)).save(tex / f"{name}_m.webp", quality=92, method=6)
    print(f"{name}: {folder.name} -> {size[0]}x{size[1]} ({', '.join(sorted(maps))})")


def main():
    mapping = json.loads(Path(sys.argv[1]).read_text())
    src_root, tex = Path(sys.argv[2]), Path(sys.argv[3])
    for name, opts in mapping.items():
        convert(name, opts, src_root, tex)


if __name__ == "__main__":
    main()
