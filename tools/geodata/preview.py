#!/usr/bin/env python3
"""Quick-look images of a built pack, decoded from the .wwgeo file itself:
<id>.map.png (imagery, buildings, water, roads, path), <id>.relief.png (carved LOD0 heights around a spot)
and <id>.profile.png (our profile against the reference numbers)."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

from geo_common import OUT_DIR, REPO_DIR  # noqa: E402
from pack import T_IMAGE  # noqa: E402
from wwgeo_read import Pack  # noqa: E402


def mosaic(pk, lod, box=None):
    """Imagery mosaic of a LOD (or of the tiles intersecting box = (x0, y0, x1, y1))."""
    t = pk.meta["lods"][lod]["tileM"]
    tiles = pk.tiles(T_IMAGE, lod)
    if box:
        tiles = [(a, b) for a, b in tiles if (a + 1) * t > box[0] and a * t < box[2] and (b + 1) * t > box[1] and b * t < box[3]]
    if not tiles:
        return None, None
    n = pk.image(lod, *tiles[0]).shape[0]
    txs = [a for a, _ in tiles]
    tys = [b for _, b in tiles]
    W = (max(txs) - min(txs) + 1) * n
    H = (max(tys) - min(tys) + 1) * n
    img = np.full((H, W, 3), 245, np.uint8)
    for a, b in tiles:
        im = pk.image(lod, a, b)
        r0 = (max(tys) - b) * n
        c0 = (a - min(txs)) * n
        img[r0:r0 + n, c0:c0 + n] = im
    extent = (min(txs) * t, (max(txs) + 1) * t, min(tys) * t, (max(tys) + 1) * t)
    return img, extent


def render_map(pk, out_png, box=None, lod=0, title="", details=True):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.collections import LineCollection, PolyCollection
    p = pk.path()
    img, ext = mosaic(pk, lod, box)
    w, h = (box[2] - box[0], box[3] - box[1]) if box else (1, 1)
    fig, ax = plt.subplots(figsize=(18, max(5, 18 * h / w)) if w > 1.6 * h else (13, 13))
    ax.imshow(img, extent=ext, interpolation="bilinear")
    t0 = pk.meta["lods"][0]["tileM"]
    tiles = pk.tiles(T_IMAGE, 0)
    if box:
        tiles = [(a, b) for a, b in tiles if (a + 1) * t0 > box[0] and a * t0 < box[2] and (b + 1) * t0 > box[1] and b * t0 < box[3]]
    if details:
        bpolys, wpolys, wlines, rlines, rcols = [], [], [], [], []
        for a, b in tiles:
            bpolys += [bd["ring"] for bd in pk.buildings(a, b)]
            for w in pk.water(0, a, b):
                if w["kind"] == 0:
                    wpolys += w["rings"][:1]
                else:
                    wlines += w["rings"]
            for r in pk.roads(a, b):
                rlines.append(r["pts"])
                rcols.append("#777777" if r["cls"] == 20 else "#ffffff")
        ax.add_collection(PolyCollection(wpolys, facecolor="#2f6fd0", edgecolor="#1040a0", alpha=0.6, lw=0.3))
        ax.add_collection(LineCollection(wlines, colors="#3080ff", lw=0.6))
        ax.add_collection(LineCollection(rlines, colors=rcols, lw=0.5, alpha=0.8))
        ax.add_collection(PolyCollection(bpolys, facecolor="#ff9c3a", edgecolor="#803000", lw=0.2, alpha=0.9))
    ax.plot(p["x"], p["y"], "-", color="#e00020", lw=1.6 if details else 2.2)
    br = (p["flags"] & 1) > 0
    ax.plot(np.where(br, p["x"], np.nan), np.where(br, p["y"], np.nan), "-", color="yellow", lw=2.5)
    step = 1000 if p["length"] < 30000 else 10000
    for km in range(0, int(p["length"] // step) + 1):
        i = min(int(km * step / 5), len(p["x"]) - 1)
        if box and not (box[0] <= p["x"][i] <= box[2] and box[1] <= p["y"][i] <= box[3]):
            continue
        ax.annotate(str(km * step // 1000), (p["x"][i], p["y"][i]), color="white", fontsize=9, weight="bold",
                    bbox=dict(boxstyle="round,pad=0.15", fc="#c00020", ec="none"))
    for h in pk.meta.get("hairpins", []):
        i = min(int(h["s"] / 5), len(p["x"]) - 1)
        if not box or (box[0] <= p["x"][i] <= box[2] and box[1] <= p["y"][i] <= box[3]):
            ax.plot(p["x"][i], p["y"][i], "o", mfc="none", mec="cyan", ms=7)
    pl = pk.places()
    for t in pl["places"]:
        big = (t.get("population") or 0) >= 1500
        if t["subtype"] == "locality" and (details or big) and (not box or (box[0] <= t["x"] <= box[2] and box[1] <= t["y"] <= box[3])):
            ax.text(t["x"], t["y"], t["name"], fontsize=7, color="black",
                    bbox=dict(boxstyle="round,pad=0.1", fc="white", ec="none", alpha=0.7))
    if box:
        ax.set_xlim(box[0], box[2])
        ax.set_ylim(box[1], box[3])
    ax.set_aspect("equal")
    ax.set_title(title or pk.meta["id"])
    fig.savefig(out_png, dpi=80, bbox_inches="tight")
    plt.close(fig)


def render_relief(pk, out_png, centre, half=700.0):
    """Hillshade of the carved LOD0 terrain around [centre]: the road bed should be visible as a shelf."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import LightSource
    from pack import T_HEIGHT
    t = pk.meta["lods"][0]["tileM"]
    n = pk.meta["lods"][0]["grid"]
    box = (centre[0] - half, centre[1] - half, centre[0] + half, centre[1] + half)
    tiles = [(a, b) for a, b in pk.tiles(T_HEIGHT, 0)
             if (a + 1) * t > box[0] and a * t < box[2] and (b + 1) * t > box[1] and b * t < box[3]]
    txs = [a for a, _ in tiles]
    tys = [b for _, b in tiles]
    H = np.full(((max(tys) - min(tys) + 1) * n + 1, (max(txs) - min(txs) + 1) * n + 1), np.nan)
    for a, b in tiles:
        h = pk.heights_fast(0, a, b)
        r0 = (max(tys) - b) * n
        c0 = (a - min(txs)) * n
        H[r0:r0 + n + 1, c0:c0 + n + 1] = h
    ext = (min(txs) * t, (max(txs) + 1) * t, min(tys) * t, (max(tys) + 1) * t)
    Hf = np.where(np.isnan(H), np.nanmin(H), H)
    ls = LightSource(azdeg=315, altdeg=40)
    shade = ls.hillshade(Hf, vert_exag=1.5, dx=t / n, dy=t / n)
    fig, ax = plt.subplots(figsize=(11, 11))
    ax.imshow(shade, extent=ext, cmap="gray")
    cs = ax.contour(np.linspace(ext[0], ext[1], H.shape[1]), np.linspace(ext[3], ext[2], H.shape[0]), Hf,
                    levels=np.arange(np.floor(np.nanmin(H) / 10) * 10, np.nanmax(H), 10), colors="orange", linewidths=0.4)
    p = pk.path()
    ax.plot(p["x"], p["y"], "r-", lw=0.8, alpha=0.7)
    ax.set_xlim(box[0], box[2])
    ax.set_ylim(box[1], box[3])
    ax.set_title(f"{pk.meta['id']}: LOD0 terrain (carved), 10 m contours")
    fig.savefig(out_png, dpi=80, bbox_inches="tight")
    plt.close(fig)


def kotlin_reference(rid):
    """Old profile of the route from Routes.kt / Stages.kt: (distances m, elevations m) or None."""
    src = REPO_DIR / "core/src/main/kotlin/app/rollentrainer/core/route"
    rt = (src / "Routes.kt").read_text()
    m = re.search(r'id = "' + re.escape(rid) + r'".*?startElevationM = ([\d.]+), lengthKm = ([\d.]+),\s*kmGradients = listOf\(([^)]*)\)', rt, re.S)
    if m:
        z0, L, gs = float(m.group(1)), float(m.group(2)), [float(v) for v in m.group(3).split(",")]
        d, e = [0.0], [z0]
        for i, g in enumerate(gs):
            span = (L * 1000 - d[-1]) if i == len(gs) - 1 else min(1000.0, L * 1000 - d[-1])
            d.append(d[-1] + span)
            e.append(e[-1] + span * g / 100)
        return np.array(d), np.array(e), "Routes.kt (km-Gradienten)"
    st = (src / "Stages.kt").read_text()
    m = re.search(r'id = "' + re.escape(rid) + r'".*?points = listOf\((.*?)\),\s*\n', st, re.S)
    if m:
        pts = re.findall(r"RoutePoint\(([\d.]+), ([\d.-]+)\)", m.group(1))
        return np.array([float(a) for a, _ in pts]), np.array([float(b) for _, b in pts]), "Stages.kt (alt)"
    return None


def render_profile(pk, report, out_png):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    p = pk.path()
    s, z = p["s"], p["z"]
    fig, axs = plt.subplots(2, 1, figsize=(15, 9), gridspec_kw={"height_ratios": [3, 1]})
    ax = axs[0]
    ax.fill_between(s / 1000, z.min() - 20, z, color="#d9e6f2")
    ax.plot(s / 1000, z, color="#1f4e79", lw=1.2, label="neu: Overture-Weg + GLO-30 (Pack)")
    ref = kotlin_reference(pk.meta["id"])
    if ref is not None:
        d, e, lab = ref
        k = s[-1] / d[-1]
        ax.plot(d * k / 1000, e, color="#c0392b", lw=1.0, ls="--", label=f"{lab}, auf Länge skaliert")
    seed = report.get("seed")
    if seed:
        for pl in seed["places"]:
            ax.plot(pl["km"] * s[-1] / 1000 / (pk.meta["officialLengthKm"] or s[-1] / 1000), pl["seedM"], "v", color="#e67e22", ms=5)
        for c in seed["climbs"]:
            ax.annotate(f"{c['name']}\nseed {c['seedSummitM']} m / {c['seedAvgPct']} %\nneu {c['oursSummitM']} m / {c['oursAvgPct']} %",
                        (c["oursSummitKm"], c["oursSummitM"]), fontsize=7, xytext=(0, 18), textcoords="offset points",
                        ha="center", arrowprops=dict(arrowstyle="-", color="gray"))
    for t in report.get("towns", [])[:60]:
        ax.axvspan(t["sIn"] / 1000, t["sOut"] / 1000, color="#f0c040", alpha=0.25, lw=0)
    for h in pk.meta.get("hairpins", []):
        ax.plot(h["s"] / 1000, h["z"], "|", color="black", ms=8)
    ax.set_ylabel("Höhe (m)")
    ax.grid(alpha=0.3)
    ax.legend(loc="upper left", fontsize=8)
    ax.set_title(f"{pk.meta['id']}: {s[-1] / 1000:.2f} km (offiziell {pk.meta.get('officialLengthKm')}), "
                 f"{z[0]:.0f} → {z[-1]:.0f} m, max {z.max():.0f} m, Anstieg {report.get('ascent2m')} m")
    ax2 = axs[1]
    seg = 100.0 if s[-1] < 30000 else 500.0
    d = np.arange(0, s[-1], seg)
    g = np.diff(np.interp(np.append(d, s[-1]), s, z)) / np.diff(np.append(d, s[-1])) * 100
    ax2.bar(d / 1000, g, width=seg / 1000, align="edge", color=np.where(g > 0, "#c0392b", "#2e86c1"))
    if ref is not None and s[-1] < 30000:
        dd, ee, _ = ref
        k = s[-1] / dd[-1]
        gg = np.diff(ee) / np.diff(dd) * 100
        ax2.step(dd[:-1] * k / 1000, gg, where="post", color="black", lw=1, label="Referenz km-Gradient")
        kg = report.get("kmGrades", [])
        ax2.step(np.arange(len(kg)), kg, where="post", color="#1f4e79", lw=1.5, label="neu km-Gradient")
        ax2.legend(fontsize=8)
    ax2.set_ylabel(f"Steigung % ({seg:.0f} m)")
    ax2.set_xlabel("km")
    ax2.grid(alpha=0.3)
    fig.savefig(out_png, dpi=80, bbox_inches="tight")
    plt.close(fig)


def render(rid, out=OUT_DIR, boxes=None):
    out = Path(out)
    pk = Pack(out / f"{rid}.wwgeo")
    report = json.loads((out / f"{rid}.report.json").read_text())
    render_profile(pk, report, out / f"{rid}.profile.png")
    p = pk.path()
    if p["length"] < 30000:
        pad = 300
        box = (p["x"].min() - pad, p["y"].min() - pad, p["x"].max() + pad, p["y"].max() + pad)
        render_map(pk, out / f"{rid}.map.png", box, lod=0)
    else:
        pad = 3000
        box = (p["x"].min() - pad, p["y"].min() - pad, p["x"].max() + pad, p["y"].max() + pad)
        render_map(pk, out / f"{rid}.map.png", box, lod=1, details=False)
    # Detail views: around the highest point and around the biggest town.
    i = int(np.argmax(p["z"]))
    c = (p["x"][i], p["y"][i])
    render_map(pk, out / f"{rid}.detail.png", (c[0] - 900, c[1] - 900, c[0] + 900, c[1] + 900), lod=0,
               title=f"{rid}: Umgebung km {p['s'][i] / 1000:.1f}")
    render_relief(pk, out / f"{rid}.relief.png", c)
    for k, b in enumerate(boxes or []):
        render_map(pk, out / f"{rid}.detail{k + 1}.png", b, lod=0)


if __name__ == "__main__":
    for rid in sys.argv[1:]:
        render(rid)
