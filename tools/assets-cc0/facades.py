#!/usr/bin/env python3
"""
Rebuilds facade textures on real wall scans: the windows, shutters, doors and sills of the old drawn
facade (app/src/main/assets/tex/facade_*.webp) stay, the wall around them becomes a CC0 photo texture.

The elements are found in the old height map (_m blue): they are flat drawn rectangles, the drawn wall
is not. Flat connected regions of a sensible size and shape become boxes; inside the boxes the old
albedo, normal and _m are kept, outside the real wall is tiled at its real size.

Usage: facades.py MAPPING.json SRC_DIR TEX_DIR [PREVIEW.jpg]
MAPPING: {"facade_stone_village": {"src": "facade_stone_village", "wall_m": 3.0}, ...}
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

from convert import find_maps

# Facade repeat sizes in metres (Materials.kt SIZE).
SIZE = {
    "facade_barn": (6.0, 3.0), "facade_basque": (6.0, 3.0), "facade_chalet": (6.0, 3.0),
    "facade_church": (4.0, 8.0), "facade_eixample": (6.0, 3.3), "facade_haussmann": (6.0, 3.4),
    "facade_modern": (6.0, 3.0), "facade_plaster_cream": (6.0, 3.0), "facade_plaster_ochre": (6.0, 3.0),
    "facade_pyrenean": (6.0, 3.0), "facade_shop": (6.0, 3.5), "facade_stone_village": (6.0, 3.0),
}


def element_mask(height: Image.Image, size, fill=0.75, join=21) -> np.ndarray:
    """Boolean mask (h, w) of the drawn elements, as filled boxes."""
    h = height.resize(size, Image.NEAREST)
    span = np.asarray(h.filter(ImageFilter.MaxFilter(7))).astype(int) - np.asarray(h.filter(ImageFilter.MinFilter(7))).astype(int)
    flat = span <= 4
    labels, n = ndimage.label(flat)
    area = size[0] * size[1]
    boxes = np.zeros((size[1], size[0]), bool)
    for i, sl in enumerate(ndimage.find_objects(labels), start=1):
        comp = labels[sl] == i
        a = comp.sum()
        bh, bw = comp.shape
        if a < area * 0.002 or bh * bw > area * 0.3 or a / (bh * bw) < fill:
            continue
        boxes[sl] = True
    # A window, its frame, shutters, sill and balcony are separate drawn pieces: merge neighbours into
    # groups, and keep each group's bounding rectangle when it is big enough to be a real element (small
    # flat patches of the drawn stone wall are not).
    groups, _ = ndimage.label(ndimage.binary_dilation(boxes, structure=np.ones((join, join))))
    out = np.zeros_like(boxes)
    for i, sl in enumerate(ndimage.find_objects(groups), start=1):
        inner = boxes[sl] & (groups[sl] == i)
        if not inner.any():
            continue
        ys, xs = np.nonzero(inner)
        y0, y1, x0, x1 = sl[0].start + ys.min(), sl[0].start + ys.max(), sl[1].start + xs.min(), sl[1].start + xs.max()
        if (y1 - y0) * (x1 - x0) < area * 0.035:
            continue
        out[max(0, y0 - 3):y1 + 4, max(0, x0 - 3):x1 + 4] = True
    return out


def tiled(path: Path, size, reps, mode):
    im = Image.open(path).convert(mode)
    tw, th = round(size[0] / reps[0]), round(size[1] / reps[1])
    im = im.resize((max(1, tw), max(1, th)), Image.LANCZOS)
    out = Image.new(mode, size)
    for x in range(0, size[0], im.width):
        for y in range(0, size[1], im.height):
            out.paste(im, (x, y))
    return out


def blend(old: Image.Image, new: Image.Image, mask: np.ndarray) -> Image.Image:
    soft = Image.fromarray((mask * 255).astype(np.uint8)).resize(old.size, Image.BILINEAR).filter(ImageFilter.GaussianBlur(1.2))
    return Image.composite(old, new, soft)


def build(name, opts, src_root: Path, tex: Path):
    maps = find_maps(src_root / opts["src"])
    a_old = Image.open(tex / f"{name}.webp").convert("RGB")
    n_old = Image.open(tex / f"{name}_n.webp").convert("RGB")
    m_old = Image.open(tex / f"{name}_m.webp").convert("RGB")
    size = a_old.size
    if "boxes" in opts:
        # Boxes given by hand (x0, y0, x1, y1 on a 1024 x 512 facade) where the detection picks up wall too.
        mask = np.zeros((size[1], size[0]), bool)
        sx, sy = size[0] / 1024, size[1] / 512
        for x0, y0, x1, y1 in opts["boxes"]:
            mask[round(y0 * sy):round(y1 * sy), round(x0 * sx):round(x1 * sx)] = True
    else:
        mask = element_mask(m_old.split()[2], size, opts.get("fill", 0.75), opts.get("join", 21))
    wm, hm = SIZE[name]
    wall = opts.get("wall_m", 3.0)
    reps = (wm / wall, hm / wall)
    a = blend(a_old, tiled(maps["albedo"], size, reps, "RGB"), mask)
    n_new = tiled(maps["normal_gl"], n_old.size, reps, "RGB") if "normal_gl" in maps else Image.new("RGB", n_old.size, (128, 128, 255))
    n = blend(n_old, n_new, mask)
    ms = m_old.size
    ao = tiled(maps["ao"], ms, reps, "L") if "ao" in maps else Image.new("L", ms, 255)
    rough = tiled(maps["rough"], ms, reps, "L") if "rough" in maps else Image.new("L", ms, 220)
    hgt = tiled(maps["height"], ms, reps, "L") if "height" in maps else Image.new("L", ms, 128)
    m = blend(m_old, Image.merge("RGB", (ao, rough, hgt)), mask)
    a.save(tex / f"{name}.webp", quality=88, method=6)
    n.save(tex / f"{name}_n.webp", quality=92, method=6)
    m.save(tex / f"{name}_m.webp", quality=92, method=6)
    print(f"{name}: elements {mask.mean() * 100:.0f} % of the facade")
    return a


def main():
    mapping = json.loads(Path(sys.argv[1]).read_text())
    src_root, tex = Path(sys.argv[2]), Path(sys.argv[3])
    shots = [build(n, o, src_root, tex) for n, o in mapping.items()]
    if len(sys.argv) > 4:
        cols = 2
        sheet = Image.new("RGB", (512 * cols, 256 * ((len(shots) + cols - 1) // cols)))
        for i, s in enumerate(shots):
            sheet.paste(s.resize((512, 256)), ((i % cols) * 512, (i // cols) * 256))
        sheet.save(sys.argv[4], quality=85)


if __name__ == "__main__":
    main()
