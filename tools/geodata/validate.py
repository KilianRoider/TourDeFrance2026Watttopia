#!/usr/bin/env python3
"""Checks built routes against published facts and decides whether their profile goes into the app.

    python3 -I tools/geodata/validate.py tour26-06 alpe-dhuez   # checks -> <id>.check.json, printed
    python3 -I tools/geodata/validate.py --install tour26-06     # copy the profile if the gate passed
    python3 -I tools/geodata/validate.py --summary               # SUMMARY.md over every built route

References: climbs use Routes.kt (start elevation, length, km gradients: the published figures);
stages use the seed (official length, start/finish elevation, categorised climbs) and summit
elevations written in waypoint names ("Col d'Aspin (1489 m, cat. 1)"), which come from the road
signs and are the most reliable numbers we have.

The gate is deliberately about what a rider notices: the right length, the right summits and
climb gradients, no detours and no artefacts (spikes, sawtooth, impossible grades).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

from geo_common import OUT_DIR, REPO_DIR, TOOLS_DIR  # noqa: E402

PROFILE_RES_DIR = REPO_DIR / "core/src/main/resources/app/rollentrainer/core/geo/profiles"
SEED = TOOLS_DIR / "seed" / "stages_seed.json"
ROUTES_KT = REPO_DIR / "core/src/main/kotlin/app/rollentrainer/core/route/Routes.kt"

# Gate limits. Stage lengths are official to 0.1 km; a climb's start is a matter of definition.
MAX_LEN_ERR_STAGE = 2.0
MAX_LEN_ERR_CLIMB = 3.0
MAX_SUMMIT_ERR = 25.0  # m, against a signed summit elevation
MAX_CLIMB_AVG_ERR = 0.6  # %-points, climbs vs Routes.kt
MAX_END_ERR = 35.0  # m, climb start / summit vs Routes.kt
# Routes that really pass the same line twice (checked on the map): reason, and the km ranges
# where that happens (None: anywhere on the route).
DOUBLE_BACK_OK = {"champs-elysees": ("up and down the avenue share one centre line in Overture", None),
                  "tour26-21": ("up and down the Champs-Élysées share one centre line in Overture", None),
                  "tour26-02": ("U-turn at the Fonts de Montjuïc, back down the Avinguda de la Reina Maria Cristina "
                                "(research notes and the PIMEC itinerary)", [(135.0, 137.5)])}


def climb_reference(rid):
    rt = ROUTES_KT.read_text()
    m = re.search(r'id = "' + re.escape(rid) + r'".*?startElevationM = ([\d.]+), lengthKm = ([\d.]+),\s*'
                  r'kmGradients = listOf\(([^)]*)\)', rt, re.S)
    if not m:
        return None
    z0, length, gs = float(m.group(1)), float(m.group(2)), [float(v) for v in m.group(3).split(",")]
    d, e = [0.0], [z0]
    for i, g in enumerate(gs):
        span = (length * 1000 - d[-1]) if i == len(gs) - 1 else min(1000.0, length * 1000 - d[-1])
        d.append(d[-1] + span)
        e.append(e[-1] + span * g / 100)
    return {"lengthKm": length, "startM": z0, "summitM": round(e[-1]), "avgPct": (e[-1] - z0) / (length * 10),
            "kmGrades": gs, "d": np.array(d), "e": np.array(e)}


def stage_reference(rid):
    if not SEED.exists():
        return None
    for d in json.loads(SEED.read_text()):
        if d.get("id") == rid:
            return d
    return None


def name_elevation(name):
    m = re.search(r"\((\d{3,4})\s*m\b", name or "")
    return float(m.group(1)) if m else None


def waypoint_file(rid):
    for d in (TOOLS_DIR / "waypoints",):
        p = d / f"{rid}.json"
        if p.exists():
            return p
    return None


def out_and_back(s, x, y, near=6.0, min_ds=200.0, max_ds=3000.0, min_spur=20.0):
    """Places where the path comes back to where it was a little earlier: a spur ridden in and out
    or a U-turn, which a race never does (laps of a circuit are further apart than max_ds). A spur
    runs on the same centre line both ways (5 m samples: pairs within 2.5 m); the legs of a real
    tight switchback are 10 m or more apart (D526 below the Grand'Maison dam, stage 20). Spurs
    shorter than min_ds (a waypoint on a roundabout arm or a bridge ramp, ridden 30-100 m in and
    out) count when the path runs the other way at the second pass."""
    from scipy.spatial import cKDTree
    pts = np.column_stack([x, y])
    pairs = cKDTree(pts).query_pairs(near, output_type="ndarray")
    if len(pairs) == 0:
        return []
    ds = np.abs(s[pairs[:, 1]] - s[pairs[:, 0]])
    tx, ty = np.gradient(x), np.gradient(y)
    n = np.hypot(tx, ty) + 1e-9
    back = (tx[pairs[:, 0]] * tx[pairs[:, 1]] + ty[pairs[:, 0]] * ty[pairs[:, 1]]) / (n[pairs[:, 0]] * n[pairs[:, 1]]) < -0.8
    pairs = pairs[((ds > min_ds) & (ds < max_ds)) | ((ds > min_spur) & (ds <= min_ds) & back)]
    out = []
    for i in sorted(set(int(min(a, b)) for a, b in pairs)):
        if not out or s[i] - out[-1] > 500:
            out.append(float(s[i]))
    return out


def grade_stats(s, z):
    d = np.arange(0, s[-1], 100.0)
    g100 = np.diff(np.interp(np.append(d, s[-1]), s, z)) / np.maximum(np.diff(np.append(d, s[-1])), 1) * 100
    # Spikes: the profile against its 300 m running median. Real roads do not jump several metres
    # and back within a few hundred metres; elevation-model artefacts (a bridge, a cutting) do.
    from scipy.ndimage import median_filter
    step = s[1] - s[0]
    med = median_filter(z, size=max(3, int(300 / step) | 1), mode="nearest")
    dev = np.abs(z - med)
    return {"max100Pct": round(float(np.abs(g100).max()), 1),
            "maxUp100Pct": round(float(g100.max()), 1),
            "spikeM": round(float(dev.max()), 1), "spikeAtKm": round(float(s[int(np.argmax(dev))]) / 1000, 2)}


def best_km_alignment(s, z, ref):
    """Per-km grades of ours vs the reference, aligned at the start offset that fits best (climb
    start points are defined differently between sources)."""
    gs = np.array(ref["kmGrades"])
    n = len(gs) - 1  # the last reference km is often partial
    best = None
    for off in np.arange(0, max(0.0, s[-1] - n * 1000) + 1, 50.0):
        ours = np.array([(np.interp(off + (k + 1) * 1000, s, z) - np.interp(off + k * 1000, s, z)) / 10 for k in range(n)])
        rms = float(np.sqrt(np.mean((ours - gs[:n]) ** 2)))
        if best is None or rms < best[0]:
            best = (rms, off, ours)
    return best


def check(rid, out=OUT_DIR):
    rep = json.loads((out / f"{rid}.report.json").read_text())
    p = np.load(out / f"{rid}.path.npz")
    s, x, y, z = p["s"], p["x"], p["y"], p["z"]
    wf = waypoint_file(rid)
    spec = json.loads(wf.read_text()) if wf else {}
    wps = spec.get("waypoints", spec if isinstance(spec, list) else [])
    climb = climb_reference(rid)
    stage = stage_reference(rid)
    is_stage = rid.startswith("tour26-")
    fails, warns, notes = [], [], []

    official = rep.get("officialKm") or (climb or {}).get("lengthKm") or (stage or {}).get("lengthKm")
    err = 100 * (s[-1] / 1000 - official) / official if official else None
    lim = MAX_LEN_ERR_STAGE if is_stage else MAX_LEN_ERR_CLIMB
    if err is None:
        warns.append("no official length")
    elif abs(err) > lim:
        fails.append(f"length {s[-1] / 1000:.2f} km vs {official} km ({err:+.1f} %)")

    # Legs: a leg far longer than the waypoints' km difference is a detour.
    # A leg much shorter than expected is a shortcut (a parallel road won over the race road).
    detours, shortcuts = [], []
    for lg in rep.get("legs", []):
        e = lg.get("expectedLegKm")
        if e is None:
            continue
        diff = lg["legKm"] - e
        if diff > max(0.6, 0.35 * e):
            detours.append(f"{lg['to']} ({lg['legKm']} vs {e} km)")
        elif diff < -max(0.3, 0.2 * e):
            shortcuts.append(f"{lg['to']} ({lg['legKm']} vs {e} km)")
    if detours:
        warns.append("long legs: " + "; ".join(detours[:6]) + (" ..." if len(detours) > 6 else ""))
    if shortcuts:
        warns.append("short legs: " + "; ".join(shortcuts[:6]) + (" ..." if len(shortcuts) > 6 else ""))
    oab = out_and_back(s, x, y)
    why, ranges = DOUBLE_BACK_OK.get(rid, (None, []))
    ok_here = [v for v in oab if why and (ranges is None or any(a * 1000 <= v <= b * 1000 for a, b in ranges))]
    other = [v for v in oab if v not in ok_here]
    if ok_here:
        warns.append("path doubles back at km " + ", ".join(f"{v / 1000:.1f}" for v in ok_here[:8]) + f" ({why})")
    if other:
        fails.append("path doubles back at km " + ", ".join(f"{v / 1000:.1f}" for v in other[:8]))

    gg = rep.get("gradeGate")
    if gg is None:
        fails.append("no grade gate in the report (built before the gate existed): rebuild")
    else:
        fails.extend(gg["fails"])
    gst = grade_stats(s, z)
    if gst["max100Pct"] > 20:
        warns.append(f"100 m grade {gst['max100Pct']} %")
    if gst["spikeM"] > 8:
        warns.append(f"spike {gst['spikeM']} m at km {gst['spikeAtKm']}")

    # Summits named with their signed elevation, or the climbs of the seed.
    summits = []
    leg_s = [0.0] + [lg["routedKm"] * 1000 for lg in rep.get("legs", [])]
    for k, w in enumerate(wps):
        kind = (w.get("kind") or "").lower()
        ref = name_elevation(w.get("name"))
        if kind not in ("summit", "pass", "climb") and not (ref and kind in ("finish",)):
            continue
        if k >= len(leg_s):
            continue
        at = leg_s[k]
        m = (s > at - 700) & (s < at + 700)
        i = int(np.argmax(np.where(m, z, -1e9))) if kind != "finish" else len(s) - 1
        src = "name"
        if ref is None and stage:
            # Seed climb by name, the one sharing the most words: "Ballon d'Alsace (cat. 1) summit"
            # also shares "ballon" with the stage's Grand Ballon, 163 m higher.
            best = None
            for c in stage.get("climbs", []):
                toks = [t for t in re.findall(r"\w{4,}", c["name"].lower()) if t not in ("côte", "cote", "col")]
                hit = sum(t in (w.get("name") or "").lower() for t in toks)
                if hit and (best is None or hit > best[0]):
                    best = (hit, c)
            if best:
                ref, src = float(best[1]["summitM"]), "seed"
        item = {"name": w.get("name"), "km": round(float(s[i]) / 1000, 2), "oursM": round(float(z[i])),
                "refM": ref, "src": src if ref else None}
        summits.append(item)
        if ref is not None:
            d = z[i] - ref
            item["errM"] = round(float(d))
            if src == "name" and abs(d) > MAX_SUMMIT_ERR:
                fails.append(f"summit {w.get('name')}: {z[i]:.0f} m vs {ref:.0f} m")
            elif src == "seed" and abs(d) > 40:
                warns.append(f"summit {w.get('name')}: {z[i]:.0f} m vs seed {ref:.0f} m")

    res = {"id": rid, "officialKm": official, "builtKm": round(float(s[-1]) / 1000, 2),
           "lengthErrPct": round(err, 2) if err is not None else None, "startM": round(float(z[0])),
           "finishM": round(float(z[-1])), "maxM": round(float(z.max())), "ascentM": rep.get("ascent2m"),
           "grades": gst, "gradeGate": gg, "summits": summits, "outAndBack": [round(v / 1000, 2) for v in oab]}
    if climb:
        avg = (z[-1] - z[0]) / s[-1] * 100
        res["ref"] = {"startM": climb["startM"], "summitM": climb["summitM"], "avgPct": round(climb["avgPct"], 2),
                      "lengthKm": climb["lengthKm"]}
        res["avgPct"] = round(float(avg), 2)
        if abs(z[0] - climb["startM"]) > MAX_END_ERR:
            warns.append(f"start {z[0]:.0f} m vs {climb['startM']:.0f} m")
        if abs(z.max() - climb["summitM"]) > MAX_END_ERR and rid != "champs-elysees":
            fails.append(f"summit {z.max():.0f} m vs {climb['summitM']} m")
        if abs(avg - climb["avgPct"]) > MAX_CLIMB_AVG_ERR and rid != "champs-elysees":
            fails.append(f"average {avg:.1f} % vs {climb['avgPct']:.1f} %")
        al = best_km_alignment(s, z, climb)
        if al:
            res["kmRms"] = round(al[0], 2)
            res["kmOffsetM"] = al[1]
            res["kmOurs"] = [round(float(v), 1) for v in al[2]]
            res["kmRef"] = climb["kmGrades"][:len(al[2])]
            if al[0] > 2.0:
                warns.append(f"per-km grades differ (RMS {al[0]:.1f} points)")
    if stage:
        res["ref"] = {"startM": stage.get("startElevationM"), "finishM": stage.get("finishElevationM"),
                      "ascentM": stage.get("totalAscentM")}
        if stage.get("finishElevationM") and abs(z[-1] - stage["finishElevationM"]) > 40:
            warns.append(f"finish {z[-1]:.0f} m vs seed {stage['finishElevationM']} m")
        if stage.get("totalAscentM") and rep.get("ascent2m"):
            r = rep["ascent2m"] / stage["totalAscentM"]
            res["ascentRatio"] = round(r, 2)
            if r > 1.45 or r < 0.6:
                warns.append(f"ascent {rep['ascent2m']} m vs seed {stage['totalAscentM']} m")
    res["fails"], res["warnings"], res["notes"] = fails, warns, notes
    res["gate"] = not fails
    pack = out / f"{rid}.wwgeo"
    res["packMB"] = round(pack.stat().st_size / 1e6, 2) if pack.exists() else None
    res["timings"] = rep.get("timings")
    (out / f"{rid}.check.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
    return res


def installed_state(rid, out=OUT_DIR):
    res = PROFILE_RES_DIR / f"{rid}.bin"
    built = out / f"{rid}.profile.bin"
    if not res.exists():
        return "old"
    if built.exists() and res.read_bytes() == built.read_bytes():
        return "installed"
    return "installed (older build)"


def install(rid, out=OUT_DIR, force=False):
    c = check(rid, out)
    if not c["gate"] and not force:
        print(f"{rid}: gate failed, not installed: {c['fails']}")
        return False
    import landmarks
    landmarks.refresh(rid, out)  # landmarks.json may have changed since the build
    PROFILE_RES_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(out / f"{rid}.profile.bin", PROFILE_RES_DIR / f"{rid}.bin")
    print(f"{rid}: profile installed")
    return True


ALL_IDS = [f"tour26-{i:02d}" for i in range(1, 22)] + [
    "alpe-dhuez", "ventoux", "tourmalet", "galibier", "planche", "izoard", "hautacam", "madeleine", "puy-de-dome",
    "loze", "portet", "mur-de-bretagne", "aubisque", "luz-ardiden", "joux-plane", "peyresourde", "champs-elysees",
    "marie-blanque"]


def summary(out=OUT_DIR, remarks=None):
    remarks = remarks or {}
    rf = out / "remarks.json"
    if rf.exists():
        remarks = {**json.loads(rf.read_text()), **remarks}
    rows = []
    for rid in ALL_IDS:
        cf = out / f"{rid}.check.json"
        c = json.loads(cf.read_text()) if cf.exists() else None
        rows.append((rid, c, installed_state(rid, out), remarks.get(rid, "")))
    lines = ["# Geodata build summary", "",
             "Generated by `tools/geodata/validate.py --summary`. Lengths in km, elevations in m. "
             "Gate: length within 2 % (stages) / 3 % (climbs), signed summits within 25 m, climb average within "
             "0.6 points of Routes.kt, no path doubling back. Profile: `installed` = the measured profile is in "
             "the app, `old` = the route keeps its synthetic profile.", "",
             "| route | official | built | err % | start | finish | max | ascent (ref) | summits ours/ref | steepest 100 m up (published) | gate | profile | pack MB | remarks |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for rid, c, inst, rem in rows:
        if c is None:
            lines.append(f"| {rid} | | | | | | | | | | not built | {inst} | | {rem} |")
            continue
        ref = c.get("ref") or {}
        asc = f"{c['ascentM']}" + (f" ({ref['ascentM']})" if ref.get("ascentM") else "")
        sm = "; ".join(f"{(x['name'] or '')[:28]} {x['oursM']}/{int(x['refM']) if x.get('refM') else '-'}" for x in c["summits"]
                       if x.get("refM"))
        if c.get("ref", {}).get("summitM") is not None and "avgPct" in c:
            sm = (sm + "; " if sm else "") + f"top {c['maxM']}/{ref['summitM']}, avg {c['avgPct']}/{ref['avgPct']} %"
        gate = "pass" if c["gate"] else "FAIL: " + "; ".join(c["fails"])
        gg = c.get("gradeGate") or {}
        steep = f"{gg.get('maxUp100Pct')}" + (f" ({gg['publishedMaxPct']})" if gg.get("publishedMaxPct") else "")
        for cl in gg.get("climbs", []):
            steep += f"; {cl['name']} {cl['maxUp100Pct']} ({cl['publishedMaxPct']})"
        lines.append(f"| {rid} | {c['officialKm']} | {c['builtKm']} | {c['lengthErrPct']} | {c['startM']} | {c['finishM']} | "
                     f"{c['maxM']} | {asc} | {sm} | {steep} | {gate} | {inst} | {c['packMB']} | {rem} |")
    lines.append("")
    warn_lines = [f"* **{rid}**: " + "; ".join(c["warnings"]) for rid, c, _, _ in rows if c and c["warnings"]]
    if warn_lines:
        lines += ["## Warnings (reviewed by eye on the preview images)", ""] + warn_lines + [""]
    (out / "SUMMARY.md").write_text("\n".join(lines))
    print(out / "SUMMARY.md")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("routes", nargs="*")
    ap.add_argument("--out", default=str(OUT_DIR))
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--summary", action="store_true")
    a = ap.parse_args()
    out = Path(a.out)
    for rid in a.routes:
        if a.install:
            install(rid, out, a.force)
        else:
            c = check(rid, out)
            print(json.dumps({k: c[k] for k in ("id", "officialKm", "builtKm", "lengthErrPct", "startM", "finishM", "maxM",
                                                "ascentM", "gate", "fails", "warnings")}, ensure_ascii=False))
            for sm in c["summits"]:
                print("   summit", sm)
            if "kmRms" in c:
                print("   km grades ours", c["kmOurs"], "\n   km grades ref ", c["kmRef"], "rms", c["kmRms"])
    if a.summary:
        summary(out)


if __name__ == "__main__":
    main()
