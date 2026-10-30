#!/usr/bin/env python3
"""
Renders the scanned Poly Haven plant models (CC0) into the ground-cover atlas the app draws its grass
tufts, weeds and flowers with (app/src/main/assets/tex/scn_meadow.webp, 4 x 4 cells).

Every cell is one or more glTF nodes seen from the side (orthographic), so the cards carry the photo
textures of real blades instead of painted ones. A small software rasteriser does the work (numpy only,
no Blender needed): albedo from the model's diffuse map, alpha from its alpha map, a little depth and
height shading for volume. The tuft sits on the cell's bottom edge, centred, as Plants.clump() expects.

    python3 tools/assets-cc0/tufts.py <folder with the downloaded models> app/src/main/assets/tex/scn_meadow.webp

The models folder holds one sub-folder per model, as Poly Haven's 2k glTF download unpacks
(<id>/<id>_2k.gltf, <id>/textures/...), plus the alpha (and dry diffuse) maps from the API's "Alpha" and
"dry_diff" entries. See CELLS for what goes where; Plants.kt names the cells (MeadowAtlas).
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

CELL = 512        # output pixels per cell
SS = 2            # supersampling
GRID = 4

# (model, nodes (name substrings; all matching nodes are drawn side by side unless 'stack'), view angle
#  in degrees, texture: 'diff' or 'dry', layout: 'single' | 'stack' (all at the origin, a denser clump))
CELLS = [
    # row 0: green grass clumps, medium and tall
    ("grass_medium_01", ["large_a"], 0, "diff", "single"),
    ("grass_medium_01", ["large_b"], 35, "diff", "single"),
    ("grass_medium_01", ["large_c"], 70, "diff", "single"),
    ("grass_medium_01", ["tall_a", "tall_b"], 20, "diff", "stack"),
    # row 1: green, small and mid
    ("grass_medium_01", ["mid_a", "mid_b"], 10, "diff", "stack"),
    ("grass_medium_01", ["mid_c", "small_a"], 50, "diff", "stack"),
    ("grass_medium_02", ["_a", "_b", "_c", "_d", "_e"], 0, "diff", "stack"),
    ("grass_bermuda_01", ["medium_a", "medium_b", "medium_c", "medium_d", "small_a", "small_b"], 0, "diff", "stack"),
    # row 2: dry and mixed
    ("grass_medium_01", ["large_a"], 60, "dry", "single"),
    ("grass_medium_01", ["mid_a", "tall_c"], 0, "dry", "stack"),
    ("grass_medium_02", ["_a", "_c", "_e"], 30, "dry", "stack"),
    ("grass_bermuda_01", ["dead_a", "dead_b", "medium_e", "medium_f", "small_c"], 0, "diff", "stack"),
    # row 3: weeds and flowers
    ("dandelion_01", ["_a_"], 0, "diff", "single"),
    ("dandelion_01", ["_c_"], 40, "diff", "single"),
    ("weed_plant_02", ["_a_"], 0, "diff", "single"),
    ("weed_plant_02", ["_c_"], 30, "diff", "single"),
]


def load_gltf(folder, mid):
    path = os.path.join(folder, mid, f"{mid}_2k.gltf")
    g = json.load(open(path))
    buffers = [open(os.path.join(folder, mid, b["uri"]), "rb").read() for b in g["buffers"]]

    def acc(i):
        a = g["accessors"][i]
        v = g["bufferViews"][a["bufferView"]]
        comp = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}[a["componentType"]]
        n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
        off = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = v.get("byteStride", 0)
        item = np.dtype(comp).itemsize * n
        raw = buffers[v["buffer"]]
        if stride and stride != item:
            rows = [np.frombuffer(raw, comp, n, off + k * stride) for k in range(a["count"])]
            return np.array(rows)
        return np.frombuffer(raw, comp, a["count"] * n, off).reshape(a["count"], n) if n > 1 else \
            np.frombuffer(raw, comp, a["count"], off)

    return g, acc


def quat_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def node_tris(g, acc, node):
    """Triangles of one node in its own frame (rotation and scale kept, translation dropped)."""
    m = np.eye(3)
    if "rotation" in node:
        m = quat_mat(node["rotation"]) @ m
    if "scale" in node:
        m = m * np.array(node["scale"])[None, :]
    P, U = [], []
    for prim in g["meshes"][node["mesh"]]["primitives"]:
        pos = acc(prim["attributes"]["POSITION"]).astype(np.float64) @ m.T
        uv = acc(prim["attributes"]["TEXCOORD_0"]).astype(np.float64)
        idx = acc(prim["indices"]).astype(np.int64).reshape(-1, 3)
        P.append(pos[idx])
        U.append(uv[idx])
    return np.concatenate(P), np.concatenate(U)


def rasterise(tris, uvs, angle, tex_rgb, tex_a, size):
    """Orthographic side view: x right, y up, looking along the rotated z. Returns RGBA float image."""
    a = math.radians(angle)
    ca, sa = math.cos(a), math.sin(a)
    x = tris[..., 0] * ca + tris[..., 2] * sa
    z = -tris[..., 0] * sa + tris[..., 2] * ca
    y = tris[..., 1]
    y0 = min(0.0, y.min())
    height = y.max() - y0
    half = max(abs(x.min()), abs(x.max()))
    span = max(height, 2 * half) * 1.04
    s = size / span
    sx = (x * s) + size / 2
    sy = size - 1 - (y - y0) * s - size * 0.004
    zmin, zmax = z.min(), z.max()
    depth = np.full((size, size), -1e9)
    out = np.zeros((size, size, 4))
    th, tw = tex_a.shape
    for t in range(len(tris)):
        px, py, pz = sx[t], sy[t], z[t]
        x0, x1 = int(max(0, math.floor(px.min()))), int(min(size - 1, math.ceil(px.max())))
        y0_, y1 = int(max(0, math.floor(py.min()))), int(min(size - 1, math.ceil(py.max())))
        if x1 < x0 or y1 < y0_:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0_, y1 + 1) + 0.5)
        d = (py[1] - py[2]) * (px[0] - px[2]) + (px[2] - px[1]) * (py[0] - py[2])
        if abs(d) < 1e-12:
            continue
        l0 = ((py[1] - py[2]) * (gx - px[2]) + (px[2] - px[1]) * (gy - py[2])) / d
        l1 = ((py[2] - py[0]) * (gx - px[2]) + (px[0] - px[2]) * (gy - py[2])) / d
        l2 = 1 - l0 - l1
        inside = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
        if not inside.any():
            continue
        zz = l0 * pz[0] + l1 * pz[1] + l2 * pz[2]
        u = l0 * uvs[t, 0, 0] + l1 * uvs[t, 1, 0] + l2 * uvs[t, 2, 0]
        v = l0 * uvs[t, 0, 1] + l1 * uvs[t, 1, 1] + l2 * uvs[t, 2, 1]
        tx = np.clip((u % 1.0) * tw, 0, tw - 1).astype(np.int64)
        ty = np.clip((v % 1.0) * th, 0, th - 1).astype(np.int64)
        al = tex_a[ty, tx]
        sub_d = depth[y0_:y1 + 1, x0:x1 + 1]
        hit = inside & (al > 0.5) & (zz > sub_d)
        if not hit.any():
            continue
        sub_d[hit] = zz[hit]
        col = tex_rgb[ty, tx]
        # Blades further back and lower down get a little less light: volume without a lighting pass.
        back = 0.82 + 0.18 * (zz - zmin) / max(1e-6, zmax - zmin)
        ys = (size - 1 - gy) / size
        low = 0.78 + 0.22 * np.clip(ys / max(1e-6, (height * s) / size), 0, 1)
        shade = (back * low)[..., None]
        sub = out[y0_:y1 + 1, x0:x1 + 1]
        sub[hit, :3] = (col * shade)[hit]
        sub[hit, 3] = 1.0
    return out


def downsample(img, f):
    h, w, _ = img.shape
    a = img[..., 3:4]
    pre = img[..., :3] * a
    pre = pre.reshape(h // f, f, w // f, f, 3).mean(axis=(1, 3))
    al = a.reshape(h // f, f, w // f, f, 1).mean(axis=(1, 3))
    rgb = np.where(al > 1e-4, pre / np.maximum(al, 1e-4), 0)
    return np.concatenate([rgb, al], axis=-1)


def bleed(img, rounds=24):
    """Spreads the edge colours into the transparent area so mipmaps do not get dark or grey rims."""
    rgb = img[..., :3].copy()
    known = img[..., 3] > 0.02
    for _ in range(rounds):
        if known.all():
            break
        acc = np.zeros_like(rgb)
        cnt = np.zeros(known.shape)
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            k = np.roll(known, (dy, dx), axis=(0, 1))
            acc += np.roll(rgb, (dy, dx), axis=(0, 1)) * k[..., None]
            cnt += k
        new = (~known) & (cnt > 0)
        rgb[new] = acc[new] / cnt[new][:, None]
        known = known | new
    rgb[~known] = rgb[known].mean(axis=0)
    return np.concatenate([rgb, img[..., 3:4]], axis=-1)


def main(folder, out_path):
    cache = {}
    atlas = np.zeros((CELL * GRID, CELL * GRID, 4))
    for k, (mid, names, angle, texkind, layout) in enumerate(CELLS):
        if mid not in cache:
            g, acc = load_gltf(folder, mid)
            tdir = os.path.join(folder, mid, "textures")
            files = os.listdir(tdir)
            pick = lambda key: os.path.join(tdir, next(f for f in files if key in f))
            diff = np.asarray(Image.open(pick("_diff_2k.jpg")).convert("RGB"), dtype=np.float64) / 255
            dry = None
            if any("dry_diff" in f for f in files):
                dry = np.asarray(Image.open(pick("dry_diff")).convert("RGB"), dtype=np.float64) / 255
            alpha = np.asarray(Image.open(pick("alpha")).convert("L"), dtype=np.float64) / 255
            cache[mid] = (g, acc, diff, dry, alpha)
        g, acc, diff, dry, alpha = cache[mid]
        tex = dry if texkind == "dry" and dry is not None else diff
        nodes = [n for n in g["nodes"] if "mesh" in n and any(s in n.get("name", "") for s in names)]
        if not nodes:
            raise SystemExit(f"{mid}: no node matches {names}")
        P, U = [], []
        for i, n in enumerate(nodes):
            p, u = node_tris(g, acc, n)
            if layout == "stack" and len(nodes) > 1:
                # Spread the pieces a little around the centre so the clump reads wider and denser.
                ang = i * 2.4
                r = 0.18 * max(np.ptp(p[..., 0]), np.ptp(p[..., 2]))
                p = p + np.array([math.cos(ang) * r, 0, math.sin(ang) * r])
            P.append(p)
            U.append(u)
        P, U = np.concatenate(P), np.concatenate(U)
        img = rasterise(P, U, angle, tex, alpha, CELL * SS)
        img = bleed(downsample(img, SS))
        r, c = divmod(k, GRID)
        atlas[r * CELL:(r + 1) * CELL, c * CELL:(c + 1) * CELL] = img
        print(f"cell {k}: {mid} {names} {texkind} -> {len(P)} triangles, coverage {img[..., 3].mean():.2f}", flush=True)
    im = Image.fromarray((np.clip(atlas, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")
    im.save(out_path, "WEBP", quality=90, method=6)
    print("wrote", out_path)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
