#!/usr/bin/env python3
"""Puts the app's landmarks and stage climbs where they are on the real road.

The app's landmark signs (Routes.kt / Stages.kt, German labels) were placed from published km
figures; RealProfiles would only stretch them to the real length. Here every label is looked up
on the built route instead:

1. a manual entry in landmarks.json (move to a km, rename, drop, or add a label);
2. a waypoint whose name shares a distinctive word with the label ("Simpson-Gedenkstein" ->
   waypoint "Tom Simpson memorial"), at the routed distance of that waypoint;
3. a place of the pack (Overture locality next to the road) with that name;
4. "Flamme rouge" 1 km before the end, "Ziel ..." at the end;
5. otherwise the stretched position.

A match is only taken near the stretched position (the same name can occur twice, e.g. laps).
Stage climbs get the position and elevation of the profile's high point next to their summit
waypoint. The result goes into the profile resource's meta (`landmarks`, `climbs`), which
RealProfiles applies.

    python3 -I tools/geodata/landmarks.py tour26-06 ventoux     # print the matches, rewrite <id>.profile.bin
"""
from __future__ import annotations

import json
import os
import re
import struct
import sys
import unicodedata
import zlib
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

from geo_common import OUT_DIR, REPO_DIR, TOOLS_DIR  # noqa: E402

SRC = REPO_DIR / "core/src/main/kotlin/app/rollentrainer/core/route"
OVERRIDES = TOOLS_DIR / "landmarks.json"

STOP = set("""
col cote de du des la le les l d saint sainte st ste pont route avenue rue chemin place rond point
und der die das den dem des ein eine ende erste ersten erster letzte letzten letzter steilste steilster steilstes
stuck kilometer kehre kehren serpentinen zwischensprint sprint ziel start bergwertung kat ortseingang ortsausgang
durch nach uber zum zur im am in auf mit ab bis jetzt noch flamme rouge gipfel pass fuss km hc foot summit
finish junction town village via hairpin climb near pres the of and lap passage runde wende zwischenzeit
anstieg abfahrt schlussanstieg kuppe grenze frankreich spanien italien schweiz see lac bridge brucke tunnel
above below after before towards toward near entry exit entree sortie centre center village resort area approx
bend road upper lower north south east west itinerary point
""".split())
# A waypoint "Hairpin below the Casse Déserte" is near, not at, the place: such names rank after
# direct ones. A label saying the place is still ahead ("Casse Déserte voraus") keeps its own spot.
RELATIONAL = {"above", "below", "after", "before", "towards", "toward", "near", "pres"}
LABEL_AHEAD = {"voraus", "voraus", "unterhalb", "oberhalb", "hinter"}
EDGE_M = 50.0  # landmarks keep this far from the start and the finish line


def elevation(s):
    m = re.search(r"\(~?\s*(\d{3,4})\s*m\b", s or "")
    return float(m.group(1)) if m else None


def norm(s):
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", s)


def tokens(s):
    return {t for t in norm(s).split() if len(t) >= 4 and t not in STOP and not t.isdigit()}


def shared(label_tokens, name_tokens):
    """Label words found in the name; a word may be cut short in the app ("Vic-en-Bigorr")."""
    return {t for t in label_tokens if any(u == t or (len(t) >= 5 and u.startswith(t)) for u in name_tokens)}


def kind_ok(label, kind):
    """A sprint or a categorised climb in the label wants the waypoint of that kind, not another
    point in the same town ("Decize – Zwischensprint" is the sprint line, not the town's exit)."""
    words = set(norm(label).split())
    if words & {"zwischensprint", "sprint"}:
        return kind == "sprint"
    if words & {"kat", "bergwertung"}:
        return kind in ("summit", "pass", "climb")
    return True


def _block(text, rid):
    i = text.find(f'id = "{rid}"')
    if i < 0:
        return None
    j = text.find('id = "', i + 5)
    return text[i:j if j > 0 else len(text)]


def app_route(rid):
    """(landmarks [(m, label)], climbs [dict], app length m) from Routes.kt or Stages.kt."""
    for f in ("Routes.kt", "Stages.kt"):
        b = _block((SRC / f).read_text(), rid)
        if b is None:
            continue
        lms = [(float(m), lab.replace('\\"', '"')) for m, lab in re.findall(r'Landmark\(([\d.]+), "((?:[^"\\]|\\.)*)"\)', b)]
        climbs = [dict(name=n, summitM=float(a), summitKm=float(k), lengthKm=float(L), avgPct=float(g), category=c)
                  for n, a, k, L, g, c in re.findall(r'Climb\("([^"]*)", ([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+), "([^"]*)"\)', b)]
        m = re.search(r"lengthKm = ([\d.]+)", b)
        if m:
            length = float(m.group(1)) * 1000
        else:
            pts = re.findall(r"RoutePoint\(([\d.]+), ", b)
            length = float(pts[-1]) if pts else None
        return lms, climbs, length
    return [], [], None


def _positions(rid, out):
    """Named positions on the built route: waypoints (routed distance) and pack places."""
    rep = json.loads((out / f"{rid}.report.json").read_text())
    wf = TOOLS_DIR / "waypoints" / f"{rid}.json"
    spec = json.loads(wf.read_text()) if wf.exists() else {}
    wps = spec.get("waypoints", spec if isinstance(spec, list) else [])
    legs = rep.get("legs", [])
    named = []
    for k, w in enumerate(wps):
        s = 0.0 if k == 0 else (legs[k - 1]["routedKm"] * 1000 if k - 1 < len(legs) else None)
        if s is not None:
            named.append(("waypoint", w.get("name") or "", s, (w.get("kind") or "").lower()))
    pack = out / f"{rid}.wwgeo"
    if pack.exists():
        from wwgeo_read import Pack
        pl = Pack(pack).places()
        for p in pl.get("places", []):
            if abs(p.get("offset", 1e9)) < 1500:
                named.append(("place", p["name"], p["s"], p.get("subtype", "")))
        for t in pl.get("towns", []):
            if t.get("name"):
                named.append(("town", t["name"], (t["sIn"] + t["sOut"]) / 2, "town"))
    return rep, named


def resolve(rid, out=OUT_DIR, verbose=False):
    lms, climbs, app_len = app_route(rid)
    if app_len is None:
        return None
    p = np.load(out / f"{rid}.path.npz")
    s, z = p["s"], p["z"]
    length = float(s[-1])
    k = length / app_len
    rep, named = _positions(rid, out)
    ov = json.loads(OVERRIDES.read_text()).get(rid, {}) if OVERRIDES.exists() else {}
    is_stage = rid.startswith("tour26-")
    window = 5000.0 if is_stage else max(1000.0, 0.2 * length)
    out_l, log = [], []
    for m, label in lms:
        scaled = m * k
        o = ov.get(label, {})
        if o.get("drop"):
            log.append((label, m, None, "dropped (landmarks.json)"))
            continue
        new_label = o.get("label", label)
        if "km" in o:
            pos, how = o["km"] * 1000, "landmarks.json"
        elif "flamme rouge" in norm(label):
            pos, how = length - 1000, "1 km before the end"
        elif norm(label).startswith("ziel"):
            pos, how = length, "finish"
        elif set(norm(label).split()) & LABEL_AHEAD:
            pos, how = scaled, "stretched (label says ahead)"
        else:
            tl = tokens(label)
            el = elevation(label)
            best = None
            for src, name, ps, kind in named:
                tn = tokens(name)
                hit = shared(tl, tn)
                if not hit:
                    continue
                d = abs(ps - scaled)
                if d > window:
                    continue
                en = elevation(name)
                if el is not None and en is not None and abs(el - en) > 40:
                    continue  # "Eaux-Bonnes (710 m)" is not the "hairpin above Eaux-Bonnes (~815 m)"
                if el is not None and abs(float(np.interp(ps, s, z)) - el) > 60:
                    continue  # "Altiport (1700 m)" is not the Route de l'Altiport 100 m lower down
                rel = 1 if set(norm(name).split()) & RELATIONAL else 0
                # Waypoints are what the researchers placed for the race; prefer them, then more
                # shared words, then the nearer one by whole kilometres (on a climb every waypoint
                # can carry the climb's name), then names with fewer other words ("Artigues"
                # before "D918 towards Artigues"), then the one closest to the stretched position.
                key = (0 if src == "waypoint" else 1, 0 if kind_ok(label, kind) else 1, rel, -len(hit), int(d // 1000),
                       len(tn) - len(hit), d)
                if best is None or key < best[0]:
                    best = (key, ps, f"{src} '{name}'")
            if best:
                pos, how = best[1], best[2]
            else:
                pos, how = scaled, "stretched"
        # Strictly inside the route: the app drops a sign at 0 or at the line, and a town label
        # matched to the start waypoint ("Brive-la-Gaillarde", stage 9's départ réel) would sit at 0.
        pos = float(min(max(pos, EDGE_M), length - EDGE_M))
        out_l.append({"m": round(pos, 1), "label": new_label})
        log.append((label, m, pos, how))
    for a in ov.get("_add", []):
        out_l.append({"m": round(a["km"] * 1000, 1), "label": a["label"]})
        log.append((a["label"], None, a["km"] * 1000, "added (landmarks.json)"))
    out_l.sort(key=lambda d: d["m"])

    out_c = []
    for c in climbs:
        scaled = c["summitKm"] * 1000 * k
        tl = tokens(c["name"])
        best = None
        for src, name, ps, kind in named:
            if src != "waypoint" or not shared(tl, tokens(name)):
                continue
            d = abs(ps - scaled)
            if d > window:
                continue
            key = (0 if kind in ("summit", "pass", "climb") else 1, d)
            if best is None or key < best[0]:
                best = (key, ps, name)
        at = best[1] if best else scaled
        msk = (s > at - 700) & (s < at + 700)
        i = int(np.argmax(np.where(msk, z, -1e9)))
        out_c.append({"name": c["name"], "summitKm": round(float(s[i]) / 1000, 2), "summitM": round(float(z[i]))})
        log.append((c["name"], c["summitKm"] * 1000, float(s[i]), f"climb: {'waypoint ' + repr(best[2]) if best else 'stretched'}, "
                                                              f"{z[i]:.0f} m (app {c['summitM']:.0f} m)"))
    if verbose:
        for label, m, pos, how in log:
            a = f"{m / 1000:7.2f}" if m is not None else "      -"
            b = f"{pos / 1000:7.2f}" if pos is not None else "      -"
            print(f"  {a} -> {b} km  {label[:40]:40s} {how}")
    return {"landmarks": out_l, "climbs": out_c}


def rewrite_profile_meta(path, extra):
    """Replaces the meta block of a WWPROF resource, keeping the profile itself."""
    data = Path(path).read_bytes()
    assert data[:8] == b"WWPROF\x00\x01"
    body = zlib.decompress(data[8:])
    n, step, length, z0, mlen = struct.unpack_from("<IfdiI", body, 0)
    off = struct.calcsize("<IfdiI")
    meta = json.loads(body[off:off + mlen].decode())
    meta.update(extra)
    mb = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode()
    body = struct.pack("<IfdiI", n, step, length, z0, len(mb)) + mb + body[off + mlen:]
    Path(path).write_bytes(b"WWPROF\x00\x01" + zlib.compress(body, 9))


def refresh(rid, out=OUT_DIR, verbose=False):
    r = resolve(rid, out, verbose)
    if r is not None:
        rewrite_profile_meta(out / f"{rid}.profile.bin", r)
    return r


if __name__ == "__main__":
    for rid in sys.argv[1:]:
        print(rid)
        refresh(rid, OUT_DIR, verbose=True)
