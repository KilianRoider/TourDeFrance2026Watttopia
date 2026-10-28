#!/usr/bin/env python3
"""Builds the geodata pack of one or more built-in routes.

    python3 -I tools/geodata/build.py alpe-dhuez tour26-12
    python3 -I tools/geodata/build.py --all
    python3 -I tools/geodata/build.py tour26-12 --skip-world     # path + profile only (fast)

Outputs (WWGEO_OUT, default build/geodata/out): <id>.wwgeo, <id>.report.json, <id>.profile.bin and
preview PNGs. With --install-profiles the validated profiles are copied into the core resources.
"""
from __future__ import annotations

import argparse
import datetime
import json
import math
import os
import shutil
import struct
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402
import shapely  # noqa: E402

import layers as L  # noqa: E402
import pack as P  # noqa: E402
from geo_common import (OUT_DIR, OVERTURE_RELEASE, REPO_DIR, TOOLS_DIR, LocalProjection, Overture,  # noqa: E402
                        gdal_env, log)
from profile import profile_for_path  # noqa: E402
from canopy import ATTRIBUTION as CANOPY_ATTRIBUTION  # noqa: E402
from route import CLASS_CODE, CLASS_WIDTH, F_BRIDGE, F_COBBLES, F_LINK, F_TUNNEL, F_UNPAVED, load_spec, resample, route  # noqa: E402

PIPELINE_VERSION = 1
PROFILE_STEP = 20.0
PROFILE_RES_DIR = REPO_DIR / "core/src/main/resources/app/rollentrainer/core/geo/profiles"
SEED_FILES = [TOOLS_DIR / "seed" / "stages_seed.json"]
ATTRIBUTION = [
    "Overture Maps Foundation, release " + OVERTURE_RELEASE + " (ODbL: © OpenStreetMap contributors; CDLA Permissive 2.0)",
    "Copernicus DEM GLO-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA",
    "ESA WorldCover 10 m 2021 v200 and Sentinel-2 cloudless composite © ESA WorldCover project / contains modified Copernicus Sentinel data (2021), CC BY 4.0",
    CANOPY_ATTRIBUTION,
]


def waypoint_file(rid, extra_dirs):
    for d in list(extra_dirs) + [os.environ.get("WWGEO_WAYPOINTS"), TOOLS_DIR / "waypoints"]:
        if d and (Path(d) / f"{rid}.json").exists():
            return Path(d) / f"{rid}.json"
    raise FileNotFoundError(f"no waypoint file for {rid}")


def seed_for(rid):
    for f in SEED_FILES + [Path(os.environ.get("WWGEO_SEED", "/nonexistent"))]:
        if f.exists():
            for d in json.loads(f.read_text()):
                if d.get("id") == rid:
                    return d
    return None


def detect_hairpins(s, x, y, z, window=80.0, min_turn=140.0):
    h = np.unwrap(np.arctan2(np.gradient(y), np.gradient(x)))
    step = s[1] - s[0]
    w = max(2, int(window / step))
    out = []
    i = 0
    while i < len(s) - w:
        d = h[i + w] - h[i]
        if abs(d) > math.radians(min_turn):
            j = i + int(np.argmax(np.abs(np.diff(h[i:i + w + 1]))))
            out.append({"s": round(float(s[j]), 1), "turnDeg": round(float(math.degrees(d))), "z": round(float(z[j]), 1)})
            i += w + 10
        else:
            i += 1
    return out


GRADE_HARD_LIMIT = 25.0  # %, the app's Gearing.MAX_GRADE: the trainer cannot simulate more
GRADE_TOLERANCE = 2.0  # %-points above the published steepest 100 m
GRADE_CAP_MARGIN = 1.5  # %-points: the fit's cap sits below the gate


def published_max(rid):
    """Steepest published grade of a climb (%): climbfinder's steepest 100 m (published_max.json)
    or the steepest published km of Routes.kt, whichever is higher. None for stages."""
    vals, src = [], []
    f = TOOLS_DIR / "published_max.json"
    if f.exists():
        e = json.loads(f.read_text()).get(rid)
        if isinstance(e, dict) and e.get("max100Pct"):
            vals.append(float(e["max100Pct"]))
            src.append(e.get("source", "published_max.json"))
    from validate import climb_reference
    ref = climb_reference(rid)
    if ref:
        vals.append(max(ref["kmGrades"]))
        src.append("Routes.kt kmGradients")
    if not vals:
        return None, None
    i = int(np.argmax(vals))
    return vals[i], src[i]


def stage_climbs(rid, spec, legs):
    """Climbs of a stage with a published steepest 100 m (published_max.json, "climbs"), as
    routed distance ranges between their foot and summit waypoints (named, so a re-routed stage
    keeps them): [{name, s0, s1, max100Pct, source}]."""
    f = TOOLS_DIR / "published_max.json"
    e = json.loads(f.read_text()).get(rid) if f.exists() else None
    if not isinstance(e, dict) or not e.get("climbs"):
        return []
    names = [w.name for w in spec.waypoints]
    at = [0.0] + [lg["routedKm"] * 1000 for lg in legs]
    out = []
    for c in e["climbs"]:
        try:
            i, j = names.index(c["fromWaypoint"]), names.index(c["toWaypoint"])
        except ValueError:
            raise RuntimeError(f"{rid}: published_max.json climb {c['name']}: waypoint not found")
        out.append({"name": c["name"], "s0": at[i], "s1": at[j], "max100Pct": float(c["max100Pct"]),
                    "source": c.get("source", "published_max.json")})
    return out


def grade_gate(rid, s, z, prof_s, prof_z, climbs=()):
    """The steepest 100 m (sliding, every 10 m) of the profile must stay within the published
    maximum + GRADE_TOLERANCE and below GRADE_HARD_LIMIT; so must every segment of the 20 m
    profile resource, whose gradients the app uses directly. A stage is checked the same way on
    each climb listed for it in published_max.json."""
    d = np.arange(0.0, s[-1] - 100.0 + 1e-6, 10.0)
    g100 = (np.interp(d + 100.0, s, z) - np.interp(d, s, z)) if len(d) else np.zeros(1)
    gres = np.diff(prof_z) / np.diff(prof_s) * 100
    pub, src = published_max(rid)
    i = int(np.argmax(np.abs(g100)))
    res = {"max100Pct": round(float(np.abs(g100).max()), 1), "max100AtKm": round(float(d[i] + 50) / 1000, 2) if len(d) else 0.0,
           "maxUp100Pct": round(float(g100.max()), 1), "maxResourcePct": round(float(np.abs(gres).max()), 1),
           "publishedMaxPct": pub, "publishedSource": src, "fails": []}
    if res["max100Pct"] >= GRADE_HARD_LIMIT:
        res["fails"].append(f"100 m grade {res['max100Pct']} % at km {res['max100AtKm']} (app limit {GRADE_HARD_LIMIT:.0f} %)")
    if pub is not None and res["maxUp100Pct"] > pub + GRADE_TOLERANCE:
        res["fails"].append(f"steepest 100 m {res['maxUp100Pct']} % at km {res['max100AtKm']} > published {pub} % + {GRADE_TOLERANCE:.0f}")
    if res["maxResourcePct"] >= GRADE_HARD_LIMIT:
        res["fails"].append(f"profile resource segment {res['maxResourcePct']} % (app limit {GRADE_HARD_LIMIT:.0f} %)")
    for c in climbs:
        m = (d >= c["s0"]) & (d + 100.0 <= c["s1"])
        up = round(float(g100[m].max()), 1) if m.any() else 0.0
        at = round(float(d[m][np.argmax(g100[m])] + 50) / 1000, 2) if m.any() else None
        res.setdefault("climbs", []).append({"name": c["name"], "fromKm": round(c["s0"] / 1000, 2),
                                             "toKm": round(c["s1"] / 1000, 2), "maxUp100Pct": up, "atKm": at,
                                             "publishedMaxPct": c["max100Pct"], "publishedSource": c["source"]})
        if up > c["max100Pct"] + GRADE_TOLERANCE:
            res["fails"].append(f"{c['name']}: steepest 100 m {up} % at km {at} > published {c['max100Pct']} % "
                                f"+ {GRADE_TOLERANCE:.0f}")
    res["ok"] = not res["fails"]
    return res


def ascent(z, threshold=2.0):
    tot, last = 0.0, z[0]
    for v in z[1:]:
        if v > last + threshold:
            tot += v - last
            last = v
        elif v < last - threshold:
            last = v
    return tot


def profile_points(s, z, step=PROFILE_STEP):
    """The resource's points: every [step] m, the last at the exact length (cm)."""
    L_ = float(s[-1])
    d = np.arange(0, L_, step)
    if len(d) > 1 and L_ - d[-1] < step / 2:
        d = d[:-1]  # no last segment of a few metres: the app reads its gradient as dz over that stub
    d = np.append(d, L_)
    return d, np.round(np.interp(d, s, z) * 100).astype(np.int64)


def write_profile_resource(path, s, z, step=PROFILE_STEP, meta=None):
    """Compact profile for the app: z every [step] m (last point at the exact length), cm deltas, deflated."""
    L_ = float(s[-1])
    d, zz = profile_points(s, z, step)
    deltas = np.diff(zz).astype("<i2").tobytes()
    meta_b = json.dumps(meta or {}, ensure_ascii=False, separators=(",", ":")).encode()
    body = struct.pack("<IfdiI", len(d), step, L_, int(zz[0]), len(meta_b)) + meta_b + deltas
    data = b"WWPROF\x00\x01" + zlib.compress(body, 9)
    Path(path).write_bytes(data)
    return len(data)


def clip_parts(geom, box, kind):
    g = shapely.intersection(geom, box)
    if g.is_empty:
        return []
    parts = list(g.geoms) if hasattr(g, "geoms") else [g]
    out = []
    for p in parts:
        if kind == "line" and p.geom_type == "LineString" and p.length > 0.5:
            out.append(p)
        elif kind == "area" and p.geom_type == "Polygon" and p.area > 1.0:
            out.append(p)
        elif hasattr(p, "geoms"):
            out += clip_parts(p, box, kind)
    return out


def q(coords, origin, unit):
    return np.round((np.asarray(coords)[:, :2] - origin) / unit).astype(np.int64)


def build_route(rid, args):
    t_start = time.time()
    timings = {}
    spec = load_spec(waypoint_file(rid, args.waypoints or []))
    seed = seed_for(rid)
    official_km = spec.length_km or (seed or {}).get("lengthKm")
    lats = [w.lat for w in spec.waypoints]
    lons = [w.lon for w in spec.waypoints]
    lat0 = round((min(lats) + max(lats)) / 2, 4)
    lon0 = round((min(lons) + max(lons)) / 2, 4)
    proj = LocalProjection(lat0, lon0)
    ov = Overture()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    # 1. Road path.
    t0 = time.time()
    rp, _graph = route(spec, proj, ov)
    s, x, y, flags, width, cls, edge_idx = resample(rp, step=5.0)
    timings["route"] = time.time() - t0
    length = float(s[-1])
    legs = []
    acc = 0.0
    for k, ll in enumerate(rp.leg_lengths):
        a, b = spec.waypoints[k], spec.waypoints[k + 1]
        acc += ll
        exp = (b.km - a.km) * 1000 if (a.km is not None and b.km is not None) else None
        legs.append({"to": b.name, "km": b.km, "routedKm": round(acc / 1000, 2), "legKm": round(ll / 1000, 2),
                     "expectedLegKm": round(exp / 1000, 2) if exp else None,
                     "errorPct": round(100 * (ll - exp) / exp, 1) if exp else None})
    mismatches = [lg for lg in legs if lg["errorPct"] is not None and abs(lg["errorPct"]) > 5]
    log(f"{rid}: path {length / 1000:.2f} km (official {official_km}), {len(mismatches)} legs off by >5 %")

    # 2. Elevation profile.
    t0 = time.time()
    pub, pub_src = published_max(rid)
    cap = (pub + GRADE_CAP_MARGIN) / 100 if pub is not None else None
    pm = TOOLS_DIR / "published_max.json"
    entry = json.loads(pm.read_text()).get(rid) if pm.exists() else None
    if isinstance(entry, dict) and entry.get("capPct"):
        cap = float(entry["capPct"]) / 100  # a route without a published figure (see its "capReason")
    climbs = stage_climbs(rid, spec, legs)
    if climbs:
        cap = [(c["s0"], c["s1"], (c["max100Pct"] + GRADE_CAP_MARGIN) / 100) for c in climbs]
    # A stretch where the surface model is known to be wrong and nothing is published (a gorge
    # rim beside the road reads as a cliff): capped by an estimate from the heights around it,
    # never gated against it as if it were a published figure.
    sections = entry.get("capSections", []) if isinstance(entry, dict) else []
    if sections:
        names = [w.name for w in spec.waypoints]
        at = [0.0] + [lg["routedKm"] * 1000 for lg in legs]
        cap = [(0.0, length, cap)] if cap is not None and np.isscalar(cap) else list(cap or [])
        for c in sections:
            try:
                i, j = names.index(c["fromWaypoint"]), names.index(c["toWaypoint"])
            except ValueError:
                raise RuntimeError(f"{rid}: published_max.json capSection: waypoint not found")
            cap.append((at[i], at[j], float(c["capPct"]) / 100))
    with gdal_env():
        z, obs, zc = profile_for_path(proj, s, x, y, flags, cls=cls, route_cap=cap)
    timings["profile"] = time.time() - t0
    hairpins = detect_hairpins(s, x, y, z)
    lon, lat = proj.to_lonlat(x, y)

    report = {"id": rid, "lengthM": round(length, 1), "officialKm": official_km,
              "lengthErrorPct": round(100 * (length / 1000 - official_km) / official_km, 2) if official_km else None,
              "startZ": round(float(z[0]), 1), "finishZ": round(float(z[-1]), 1), "maxZ": round(float(z.max()), 1),
              "maxZAtKm": round(float(s[int(np.argmax(z))]) / 1000, 2),
              "ascent2m": round(ascent(zc, 2.0)), "avgGradePct": round(float((z[-1] - z[0]) / length * 100), 2),
              "legs": legs, "legMismatches": len(mismatches), "hairpins": len(hairpins),
              "bridgeM": float(((flags & F_BRIDGE) != 0).sum() * 5), "tunnelM": float(((flags & F_TUNNEL) != 0).sum() * 5)}
    if seed:
        report["seed"] = compare_seed(seed, s, z, length)
    report["kmGrades"] = [round(float((np.interp(min((k + 1) * 1000, length), s, z) - np.interp(k * 1000, s, z)) /
                                      (min((k + 1) * 1000, length) - k * 1000) * 100), 1)
                          for k in range(int(math.ceil(length / 1000))) if min((k + 1) * 1000, length) - k * 1000 > 50]

    pd, pz = profile_points(s, z)
    report["gradeGate"] = grade_gate(rid, s, z, pd, pz / 100.0, climbs)
    gg = report["gradeGate"]
    log(f"{rid}: steepest 100 m {gg['maxUp100Pct']} % (published {gg['publishedMaxPct']}), "
        f"{'grade gate passed' if gg['ok'] else 'GRADE GATE FAILED: ' + '; '.join(gg['fails'])}")

    prof_path = out / f"{rid}.profile.bin"
    prof_meta = {"id": rid, "officialKm": official_km, "pipeline": PIPELINE_VERSION,
                 "hairpinsAtM": [h["s"] for h in hairpins]}
    report["profileBytes"] = write_profile_resource(prof_path, s, z, meta=prof_meta)
    np.savez_compressed(out / f"{rid}.path.npz", s=s, x=x, y=y, z=z, flags=flags, width=width, cls=cls,
                        lon=lon, lat=lat, lat0=lat0, lon0=lon0, obs_s=obs["s"], obs_low=obs["low"],
                        obs_centre=obs["centre"], obs_tree=obs["tree"], zc=zc)

    if not args.skip_world:
        t0 = time.time()
        sizes = build_world(rid, spec, proj, ov, out, s, x, y, z, flags, width, cls, rp, hairpins, legs, report,
                            official_km, lat0, lon0)
        timings["world"] = time.time() - t0
        report["sizes"] = sizes
    timings["total"] = time.time() - t_start
    report["timings"] = {k: round(v, 1) for k, v in timings.items()}
    (out / f"{rid}.report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    try:
        import landmarks
        landmarks.refresh(rid, out)
    except Exception as e:  # landmarks are a refinement; a failure must not lose the build
        log(f"{rid}: landmarks not placed: {e}")
    if args.preview:
        import preview
        preview.render(rid, out)
    ok = (report["lengthErrorPct"] is None or abs(report["lengthErrorPct"]) <= args.max_length_error) and gg["ok"]
    if args.install_profiles:
        if ok or args.force:
            PROFILE_RES_DIR.mkdir(parents=True, exist_ok=True)
            shutil.copy(prof_path, PROFILE_RES_DIR / f"{rid}.bin")
            log(f"{rid}: profile installed into core resources")
        else:
            log(f"{rid}: profile NOT installed (length off by {report['lengthErrorPct']} %, grade gate {gg['fails']})")
    log(f"{rid}: done in {timings['total']:.0f}s")
    return report


def compare_seed(seed, s, z, length):
    """Seed places/climbs vs the profile; seed km are scaled to the routed length."""
    official = seed["lengthKm"] * 1000
    k = length / official
    places = []
    for p in seed.get("places", []):
        d = p["km"] * 1000 * k
        places.append({"km": p["km"], "place": p["place"], "seedM": p["m"], "oursM": round(float(np.interp(d, s, z)))})
    climbs = []
    for c in seed.get("climbs", []):
        b = c["summitKm"] * 1000 * k
        m = (s > b - 1500) & (s < b + 1500)
        i = int(np.argmax(np.where(m, z, -1e9)))
        a = s[i] - c["lengthKm"] * 1000
        climbs.append({"name": c["name"], "seedSummitM": c["summitM"], "seedKm": c["summitKm"],
                       "seedLengthKm": c["lengthKm"], "seedAvgPct": c["avgPct"],
                       "oursSummitM": round(float(z[i])), "oursSummitKm": round(float(s[i]) / 1000, 2),
                       "oursAvgPct": round(float((z[i] - np.interp(a, s, z)) / (c["lengthKm"] * 10)), 1)})
    return {"startSeedM": seed.get("startElevationM"), "finishSeedM": seed.get("finishElevationM"),
            "ascentSeedM": seed.get("totalAscentM"), "places": places, "climbs": climbs}


def build_world(rid, spec, proj, ov, out, s, x, y, z, flags, width, cls, rp, hairpins, legs, report, official_km,
                lat0, lon0):
    path_line = shapely.LineString(np.column_stack([x, y])).simplify(1.0)
    pw = P.PackWriter(out / f"{rid}.wwgeo.tmp")
    pw.add(P.T_PATH, P.enc_path(x, y, z, width, flags, cls, 5.0, float(s[-1])))
    sampler = L.Sampler(proj)
    bed = L.RoadBed(s, x, y, z, width, flags)
    bed_grid = L.RoadBed(s, x, y, z, width, flags, min_half=7.5)
    L0, L1, L2 = L.LODS
    counts = {}

    # Road verge/embankment cross-sections, every path sample.
    t0 = time.time()
    strips, free = L.corridor_strips(sampler, bed, s, x, y, z, width)
    for k0 in range(0, len(s), L.CORRIDOR_SECTION):
        sl = slice(k0, min(k0 + L.CORRIDOR_SECTION, len(s)))
        pw.add(P.T_CORRIDOR, P.enc_corridor(k0, strips["left"][sl], strips["right"][sl], free["left"][sl],
                                            free["right"][sl], L.CORRIDOR_OFFSETS), 0, k0 // L.CORRIDOR_SECTION, 0)
    log(f"corridor strips in {time.time() - t0:.0f}s")

    # Vector layers for the corridor.
    t0 = time.time()
    b_rows, b_geoms = L.collect_buildings(proj, ov, path_line, reach=350.0)
    r_rows, r_geoms = L.collect_roads(proj, ov, path_line, reach=L0.reach)
    w_rows, w_geoms = L.collect_water(proj, ov, path_line, reach=L1.reach)
    landuse = L.collect_landuse(proj, ov, path_line, reach=L1.reach)
    places = L.collect_places(proj, ov, path_line, s, x, y)
    towns = L.town_stretches(s, x, y, b_geoms, places)
    log(f"vectors: {len(b_rows)} buildings, {len(r_rows)} roads, {len(w_rows)} water, {len(landuse)} vineyard/orchard,"
        f" {len(places)} places, {len(towns)} towns in {time.time() - t0:.0f}s")

    # Route pieces are drawn from the path itself; other roads keep only what lies off the route.
    route_buf = path_line.buffer(3.5, quad_segs=2)
    shapely.prepare(route_buf)
    b_cent = shapely.get_coordinates(shapely.centroid(np.array(b_geoms, dtype=object))) if b_geoms else np.zeros((0, 2))
    w_geoms_arr = np.array(w_geoms, dtype=object)
    w_tree = shapely.STRtree(w_geoms_arr) if len(w_geoms) else None
    r_tree = shapely.STRtree(np.array(r_geoms, dtype=object)) if r_geoms else None
    lu_tree = shapely.STRtree(np.array([g for g, _ in landuse], dtype=object)) if landuse else None
    water_level_cache = {}

    def water_level(i):
        """Lake/pond/reservoir surface from the DEM (flat in the DSM), NaN for rivers and streams."""
        if i in water_level_cache:
            return water_level_cache[i]
        r, g = w_rows[i], w_geoms[i]
        lvl = float("nan")
        if r["subtype"] in ("lake", "pond", "reservoir") or r["class"] in ("lake", "pond", "reservoir", "basin"):
            if g.geom_type in ("Polygon", "MultiPolygon") and g.area > 50:
                pts = shapely.get_coordinates(shapely.points(
                    np.random.default_rng(1).uniform(*np.array(g.bounds).reshape(2, 2), size=(60, 2))))
                inside = shapely.contains_xy(g, pts[:, 0], pts[:, 1])
                if inside.sum() >= 3:
                    lvl = float(np.median(sampler.dsm(pts[inside, 0], pts[inside, 1])))
        water_level_cache[i] = lvl
        return lvl

    def enc_water(lod, tx, ty, min_area, simplify):
        if w_tree is None:
            return None
        box = L.tile_box(lod, tx, ty)
        origin = np.array([tx * lod.tile, ty * lod.tile])
        unit = 0.1 * 4 ** lod.lod
        items = []
        for i in w_tree.query(box.buffer(2)):
            g = w_geoms[i]
            r = w_rows[i]
            code = L.WATER_CODE.get(r["class"] or "", L.WATER_CODE.get(r["subtype"] or "", 0))
            if g.geom_type in ("Polygon", "MultiPolygon"):
                if g.area < min_area:
                    continue
                lvl = water_level(i)
                for p in clip_parts(g.simplify(simplify), box.buffer(2 * unit), "area"):
                    rings = [p.exterior] + list(p.interiors)
                    body = b"".join(P.enc_polyline(q(np.asarray(rr.coords)[:-1], origin, unit)) for rr in rings
                                    if len(rr.coords) >= 4)
                    items.append(struct.pack("<BBHf", 0, code, len(rings), lvl) + body)
            elif lod.lod == 0 and g.geom_type in ("LineString", "MultiLineString"):
                for p in clip_parts(g.simplify(simplify), box.buffer(2 * unit), "line"):
                    items.append(struct.pack("<BBHf", 1, code, 1, float("nan")) +
                                 P.enc_polyline(q(p.coords, origin, unit)))
        if not items:
            return None
        return struct.pack("<If", len(items), unit) + b"".join(items)

    # LOD 0 tiles.
    t0 = time.time()
    tiles0 = L.tiles_near(L0, path_line)
    n_b = n_r = 0
    for (tx, ty) in tiles0:
        X, Y = L.grid_points(L0, tx, ty)
        H = sampler.ground(X, Y)
        H = bed_grid.apply(X, Y, H)
        pw.add(P.T_HEIGHT, P.enc_heights(H), 0, tx, ty)
        box = L.tile_box(L0, tx, ty)
        origin = np.array([tx * L0.tile, ty * L0.tile])
        # Land cover with vineyards/orchards and roads burnt in (trees are scattered from it at runtime).
        CX, CY = L.cell_centres(L0, tx, ty, L0.landcover)
        lc = sampler.landcover(CX, CY)
        if lu_tree is not None:
            shp = [(landuse[i][0], landuse[i][1]) for i in lu_tree.query(box)]
            if shp:
                lu = L.rasterize_local(shp, L0, tx, ty, L0.landcover)
                lc = np.where((lu > 0) & np.isin(lc, [2, 3, 4, 6]), lu, lc)
        road_shapes = []
        if r_tree is not None:
            for i in r_tree.query(box):
                r = r_rows[i]
                if r["subtype"] != "road" or r["class"] in ("footway", "path", "steps", "bridleway"):
                    continue
                road_shapes.append((r_geoms[i].buffer(CLASS_WIDTH.get(r["class"] or "", 4.0) / 2, quad_segs=2), L.LC_ROAD))
        road_shapes.append((path_line.buffer(3.5, quad_segs=2).intersection(box.buffer(20)), L.LC_ROAD))
        rl = L.rasterize_local([sh for sh in road_shapes if not sh[0].is_empty], L0, tx, ty, L0.landcover)
        lc = np.where(rl > 0, rl, lc)
        pw.add(P.T_LANDCOVER, P.enc_u8_raster(lc), 0, tx, ty)
        IX, IY = L.cell_centres(L0, tx, ty, L0.image)
        rgb = sampler.imagery(IX, IY).transpose(1, 2, 0)
        pw.add(P.T_IMAGE, P.enc_jpeg(np.ascontiguousarray(rgb), L0.jpeg_q), 0, tx, ty, codec=P.C_JPEG)

        # Buildings whose centroid lies in this tile.
        if len(b_cent):
            sel = np.nonzero((b_cent[:, 0] >= box.bounds[0]) & (b_cent[:, 0] < box.bounds[2]) &
                             (b_cent[:, 1] >= box.bounds[1]) & (b_cent[:, 1] < box.bounds[3]))[0]
            if len(sel):
                pw.add(P.T_BUILDINGS, enc_buildings([b_rows[i] for i in sel], [b_geoms[i] for i in sel], origin,
                                                    sampler, bed), 0, tx, ty)
                n_b += len(sel)
        # Other roads (and railways), clipped to the tile, without the parts that are the route itself.
        if r_tree is not None:
            items = []
            for i in r_tree.query(box):
                r, g = r_rows[i], r_geoms[i]
                g = g.difference(route_buf) if g.intersects(route_buf) else g
                for p in clip_parts(g.simplify(0.5), box.buffer(1), "line"):
                    if p.length < 3:
                        continue
                    fl = 0
                    for rf in r.get("road_flags") or []:
                        for v in rf.get("values") or []:
                            fl |= {"is_bridge": F_BRIDGE, "is_tunnel": F_TUNNEL, "is_link": F_LINK}.get(v, 0)
                    for rs in r.get("road_surface") or []:
                        if rs.get("value") in ("unpaved", "gravel", "dirt", "compacted", "fine_gravel", "ground"):
                            fl |= F_UNPAVED
                        elif rs.get("value") in ("paving_stones", "cobblestone", "sett"):
                            fl |= F_COBBLES
                    code = 20 if r["subtype"] == "rail" else CLASS_CODE.get(r["class"] or "unknown", 0)
                    w = CLASS_WIDTH.get(r["class"] or "", 4.0) if r["subtype"] == "road" else 3.0
                    items.append(struct.pack("<BBBB", code, fl, min(255, int(round(w * 10))), 0) +
                                 P.enc_polyline(q(p.coords, origin, 0.1)))
            if items:
                pw.add(P.T_ROADS, struct.pack("<If", len(items), 0.1) + b"".join(items), 0, tx, ty)
                n_r += len(items)
        wb = enc_water(L0, tx, ty, 0.0, 0.5)
        if wb:
            pw.add(P.T_WATER, wb, 0, tx, ty)
    counts["lod0Tiles"] = len(tiles0)
    counts["buildings"] = n_b
    counts["roadPieces"] = n_r
    log(f"LOD0: {len(tiles0)} tiles in {time.time() - t0:.0f}s")

    # LOD 1 and 2: terrain, imagery, land cover (LOD 1) and larger water bodies.
    for lod, min_area, simp in ((L1, 2000.0, 4.0), (L2, 50000.0, 16.0)):
        t0 = time.time()
        tl = L.tiles_near(lod, path_line)
        for (tx, ty) in tl:
            X, Y = L.grid_points(lod, tx, ty)
            H = sampler.ground(X, Y, ovr=lod.dem_ovr, order=1)
            pw.add(P.T_HEIGHT, P.enc_heights(H, 0.1 if lod.lod == 1 else 0.25), lod.lod, tx, ty)
            if lod.landcover:
                CX, CY = L.cell_centres(lod, tx, ty, lod.landcover)
                pw.add(P.T_LANDCOVER, P.enc_u8_raster(sampler.landcover(CX, CY, ovr=lod.wc_ovr)), lod.lod, tx, ty)
            IX, IY = L.cell_centres(lod, tx, ty, lod.image)
            rgb = sampler.imagery(IX, IY, ovr=lod.s2_ovr).transpose(1, 2, 0)
            pw.add(P.T_IMAGE, P.enc_jpeg(np.ascontiguousarray(rgb), lod.jpeg_q), lod.lod, tx, ty, codec=P.C_JPEG)
            wb = enc_water(lod, tx, ty, min_area, simp)
            if wb:
                pw.add(P.T_WATER, wb, lod.lod, tx, ty)
        counts[f"lod{lod.lod}Tiles"] = len(tl)
        log(f"LOD{lod.lod}: {len(tl)} tiles in {time.time() - t0:.0f}s")

    bounds = [float(x.min()), float(y.min()), float(x.max()), float(y.max())]
    meta = {
        "format": "wwgeo", "version": P.VERSION, "pipeline": PIPELINE_VERSION, "id": rid,
        "built": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "lengthM": round(float(s[-1]), 2), "officialLengthKm": official_km,
        "projection": {"type": "tmerc", "lat0": lat0, "lon0": lon0, "k0": 1.0, "ellipsoid": "WGS84",
                       "proj4": proj.proj4},
        "pathStepM": 5.0, "pathBounds": bounds,
        "corridor": {"offsetsM": L.CORRIDOR_OFFSETS.tolist(), "sectionSamples": L.CORRIDOR_SECTION},
        "lods": [{"lod": l.lod, "tileM": l.tile, "grid": l.grid, "image": l.image, "landcover": l.landcover,
                  "reachM": l.reach, "unitM": 0.1 * 4 ** l.lod} for l in L.LODS],
        "landcoverClasses": L.LC_NAMES, "roadClasses": list(CLASS_CODE.keys()) + ["", "", "", "rail"],
        "buildingClasses": L.BUILDING_CLASSES, "waterClasses": L.WATER_CLASSES,
        "roofShapes": {v: k for k, v in L.ROOF_SHAPES.items()},
        "hairpins": hairpins,
        "waypoints": [{"name": w.name, "kind": w.kind, "km": w.km, "lat": w.lat, "lon": w.lon} for w in spec.waypoints],
        "legs": legs,
        "stats": {"startZ": report["startZ"], "finishZ": report["finishZ"], "maxZ": report["maxZ"],
                  "ascentM": report["ascent2m"], **counts},
        "attribution": ATTRIBUTION,
    }
    pw.add(P.T_META, P.json_bytes(meta))
    pw.add(P.T_PLACES, P.json_bytes({"places": places, "towns": towns}))
    pw.close(lat0, lon0)
    os.replace(out / f"{rid}.wwgeo.tmp", out / f"{rid}.wwgeo")
    names = {P.T_META: "meta", P.T_PATH: "path", P.T_PLACES: "places", P.T_CORRIDOR: "corridor", P.T_HEIGHT: "height",
             P.T_LANDCOVER: "landcover", P.T_IMAGE: "image", P.T_BUILDINGS: "buildings", P.T_ROADS: "roads",
             P.T_WATER: "water"}
    sizes = {f"{names[t]}@{l}": v for (t, l), v in sorted(pw.sizes.items())}
    sizes["total"] = (out / f"{rid}.wwgeo").stat().st_size
    log(f"{rid}.wwgeo: {sizes['total'] / 1e6:.1f} MB " + ", ".join(f"{k} {v / 1e6:.2f}" for k, v in sizes.items() if k != "total"))
    report["towns"] = towns
    return sizes


def enc_buildings(rows, geoms, origin, sampler, bed):
    items = []
    polys = []
    for r, g in zip(rows, geoms):
        if g.geom_type == "MultiPolygon":
            g = max(g.geoms, key=lambda p: p.area)
        if g.geom_type != "Polygon" or g.area < 4:
            polys.append(None)
            continue
        polys.append(g.simplify(0.3))
    # Base elevation: lowest carved terrain under the footprint, so no building floats.
    pts = [np.asarray(p.exterior.coords)[:-1] for p in polys if p is not None]
    if not pts:
        return struct.pack("<If", 0, 0.1)
    allp = np.vstack(pts)
    gz = bed.apply(allp[:, 0], allp[:, 1], sampler.ground(allp[:, 0], allp[:, 1]))
    k = 0
    for r, p in zip(rows, polys):
        if p is None:
            continue
        ring = np.asarray(p.exterior.coords)[:-1]
        base = float(gz[k:k + len(ring)].min())
        k += len(ring)
        if len(ring) < 3:
            continue
        cls = r.get("class") or r.get("subtype") or "unknown"
        flags = 0
        h = r.get("height")
        floors = r.get("num_floors") or 0
        if not h or h <= 0:
            h = floors * 3.0 + 1.5 if floors else L.default_height(p.area, cls)
            flags |= 1
        if floors:
            flags |= 2
        items.append(struct.pack("<BBBBHHHHfII", L.BUILDING_CODE.get(cls, 0), flags,
                                 L.ROOF_SHAPES.get(r.get("roof_shape") or "", 0), min(255, int(floors)),
                                 min(65535, int(round(h * 10))), min(65535, int(round((r.get("min_height") or 0) * 10))),
                                 min(65535, int(round((r.get("roof_height") or 0) * 10))), 0, base,
                                 L.parse_colour(r.get("roof_color")), L.parse_colour(r.get("facade_color"))) +
                     P.enc_polyline(q(ring, origin, 0.1)))
    return struct.pack("<If", len(items), 0.1) + b"".join(items)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("routes", nargs="*")
    ap.add_argument("--all", action="store_true", help="every route with a waypoint file")
    ap.add_argument("--waypoints", action="append", help="extra directory with <id>.json waypoint files")
    ap.add_argument("--out", default=str(OUT_DIR))
    ap.add_argument("--skip-world", action="store_true", help="path and profile only")
    ap.add_argument("--preview", action="store_true", help="render map and profile PNGs")
    ap.add_argument("--install-profiles", action="store_true", help="copy validated profiles into core resources")
    ap.add_argument("--max-length-error", type=float, default=2.0, help="percent, for --install-profiles")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--in-process", action="store_true", help="build several routes in this process")
    args = ap.parse_args()
    ids = list(args.routes)
    if args.all:
        dirs = [Path(d) for d in (args.waypoints or [])] + [TOOLS_DIR / "waypoints"]
        for d in dirs:
            ids += [p.stem for p in sorted(d.glob("*.json")) if p.stem not in ids]
    if not ids:
        ap.error("no routes given")
    if len(ids) > 1 and not args.in_process:
        # One process per route: a long stage holds a few GB of Overture rows, freed when it exits.
        import subprocess
        fails = []
        for rid in ids:
            cmd = [sys.executable, "-I", os.path.abspath(__file__), rid, "--out", args.out,
                   "--max-length-error", str(args.max_length_error)]
            for d in args.waypoints or []:
                cmd += ["--waypoints", d]
            for flag in ("skip_world", "preview", "install_profiles", "force"):
                if getattr(args, flag):
                    cmd.append("--" + flag.replace("_", "-"))
            if subprocess.call(cmd) != 0:
                fails.append(rid)
        if fails:
            log("FAILED: " + ", ".join(fails))
        return
    results = []
    for rid in ids:
        try:
            results.append(build_route(rid, args))
        except Exception as e:  # keep going: one broken waypoint file must not stop a batch
            import traceback
            traceback.print_exc()
            log(f"{rid}: FAILED {e}")
    if len(results) < len(ids):
        sys.exit(1)
    print(json.dumps([{k: r.get(k) for k in ("id", "lengthM", "officialKm", "lengthErrorPct", "startZ", "finishZ",
                                             "maxZ", "ascent2m", "legMismatches", "hairpins", "timings")}
                      for r in results], indent=1))


if __name__ == "__main__":
    main()
