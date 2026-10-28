"""Road path through the waypoints on the Overture road graph.

Overture segments are split at their connectors into graph edges; the path is the cheapest chain
through the waypoints. Costs favour the roads races use, forbid motorways and footpaths, and ignore
one-way rules (the race closes the roads). Soft waypoints (towns, summits) may be passed anywhere
within a radius; a small dynamic programme picks the pass points so that no spur is ridden in and
out again.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field

import numpy as np
import shapely
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import dijkstra

from geo_common import Overture, cells_for_lonlat_polygon, log, primary_name

# Cost per metre relative to a main road. None = never used (unless a waypoint allows the class).
CLASS_COST = {
    "primary": 1.0, "secondary": 1.0, "tertiary": 1.05, "trunk": 1.4, "unclassified": 1.15,
    "residential": 1.4, "living_street": 2.0, "service": 4.0, "unknown": 2.5, "pedestrian": 3.0,
    "cycleway": 3.0, "track": 8.0, "motorway": None, "footway": None, "steps": None, "path": None,
    "bridleway": None, "sidewalk": None, "crosswalk": None,
}
# Classes a waypoint can unlock with "road": "<class>" or kind "gravel"/"cycleway". A motorway is
# unlocked where the race rides an expressway Overture classes as one (the C-17 tunnels, stage 3).
UNLOCKABLE = {"track": 3.0, "path": 4.0, "footway": 4.0, "cycleway": 1.5, "pedestrian": 1.5, "service": 1.2,
              "motorway": 1.0}
# An unlock holds only near the waypoints that ask for it: a stage with one car-free kilometre must
# not be free to cut through a park or a car park 100 km further on.
UNLOCK_RADIUS_M = 2500.0

# Default road widths by class, metres (Overture rarely has widths).
CLASS_WIDTH = {
    "motorway": 11.0, "trunk": 9.0, "primary": 7.5, "secondary": 6.5, "tertiary": 6.0, "unclassified": 5.0,
    "residential": 5.5, "living_street": 4.5, "service": 4.0, "pedestrian": 6.0, "cycleway": 3.0,
    "track": 3.0, "unknown": 5.0, "footway": 2.0, "path": 1.5, "steps": 2.0, "bridleway": 2.0,
}
CLASS_CODE = {c: i for i, c in enumerate(
    ["unknown", "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
     "living_street", "service", "pedestrian", "cycleway", "track", "footway", "path", "steps", "bridleway"])}

# Path sample flags (bit set), shared with the pack format.
F_BRIDGE, F_TUNNEL, F_UNPAVED, F_COBBLES, F_LINK, F_ROUNDABOUT = 1, 2, 4, 8, 16, 32

UNPAVED = {"unpaved", "gravel", "dirt", "compacted", "fine_gravel", "ground", "grass", "sand", "earth", "mud", "rock",
           "pebblestone", "wood"}
COBBLES = {"paving_stones", "cobblestone", "sett", "unhewn_cobblestone"}


@dataclass
class Waypoint:
    km: float | None
    lat: float
    lon: float
    kind: str = "via"
    road: str | None = None
    name: str = ""
    radius: float = 0.0  # 0 = pass exactly here
    widen: float = 0.0  # up to this radius when the point lies off its (numbered) road


@dataclass
class RouteSpec:
    id: str
    length_km: float | None
    waypoints: list[Waypoint]
    meta: dict = field(default_factory=dict)


SOFT_RADIUS = {"town": 450.0, "place": 450.0, "village": 350.0, "city": 700.0, "summit": 120.0, "pass": 120.0,
               "climb": 150.0, "sprint": 200.0, "via": 60.0, "junction": 40.0, "turn": 40.0, "start": 0.0,
               "finish": 0.0, "roundabout": 60.0}


def load_spec(path) -> RouteSpec:
    """Reads a waypoint file. Accepts a bare list or {"waypoints": [...]}, lat/lon or latitude/longitude."""
    d = json.loads(open(path, encoding="utf-8").read())
    meta = {}
    if isinstance(d, dict):
        meta = {k: v for k, v in d.items() if k != "waypoints"}
        wps = d["waypoints"]
    else:
        wps = d
    out = []
    for i, w in enumerate(wps):
        lat = w.get("lat", w.get("latitude"))
        lon = w.get("lon", w.get("lng", w.get("longitude")))
        kind = (w.get("kind") or w.get("type") or "via").lower()
        if i == 0:
            kind = "start" if kind in ("via", "town", "place") else kind
        if i == len(wps) - 1:
            kind = "finish" if kind in ("via", "town", "place") else kind
        r = w.get("radius")
        widen = 0.0
        if r is None:
            r = SOFT_RADIUS.get(kind, 60.0)
            if i == 0 or i == len(wps) - 1:
                r = 0.0
            elif re.match(r"^\s*(R?[DN]|M|E)\s?\d", w.get("road") or ""):
                # The waypoint names a numbered road: researchers sometimes place such points on the
                # hamlet next to the road. route() widens the radius just enough to reach a main
                # road (at most this far), so the pass point lands on it instead of a spur ridden
                # in and out; a point that is on its road keeps the tight radius, so a short
                # parallel road nearby cannot win.
                widen = 80.0 if kind in ("junction", "turn", "roundabout") else 250.0
        out.append(Waypoint(km=w.get("km"), lat=float(lat), lon=float(lon), kind=kind, road=w.get("road"),
                            name=w.get("name") or w.get("place") or w.get("label") or "", radius=float(r),
                            widen=widen))
    rid = meta.get("id") or str(path).rsplit("/", 1)[-1].removesuffix(".json")
    return RouteSpec(id=rid, length_km=meta.get("lengthKm") or meta.get("length_km"), waypoints=out, meta=meta)


@dataclass
class Edge:
    seg_id: str
    a: int
    b: int
    xy: np.ndarray  # (n, 2) projected polyline from node a to node b
    length: float
    cls: str
    name: str
    flags_at: list  # [(f0, f1, flagbits)] in edge-length fraction
    width: float
    surface: str
    unlocked: bool = False  # near a waypoint that unlocks its class


def _ranges(rules, key="value"):
    """Overture 'between' rules -> list of (lo, hi, value) in segment fractions."""
    out = []
    for r in rules or []:
        b = r.get("between") or [0.0, 1.0]
        out.append((b[0], b[1], r.get(key) if key != "values" else r.get("values")))
    return out


def build_graph(rows, proj, corridor_xy, allow_classes=()):
    """Edges of all road segments inside the projected corridor polygon."""
    node_ids: dict[str, int] = {}
    node_xy: list = []
    edges: list[Edge] = []

    def node(cid, xy):
        i = node_ids.get(cid)
        if i is None:
            i = node_ids[cid] = len(node_xy)
            node_xy.append(xy)
        return i

    rows = [r for r in rows if r["subtype"] == "road" and
            (CLASS_COST.get(r["class"] or "unknown", 2.0) is not None or (r["class"] in allow_classes))]
    if not rows:
        return np.zeros((0, 2)), []
    geoms = shapely.from_wkb([r["geometry"] for r in rows])
    coords, gi = shapely.get_coordinates(geoms, return_index=True)
    px, py = proj.to_xy(coords[:, 0], coords[:, 1])
    pxy = np.column_stack([px, py])
    starts = np.searchsorted(gi, np.arange(len(rows)))
    ends = np.append(starts[1:], len(gi))
    lines = shapely.linestrings(pxy, indices=gi)
    shapely.prepare(corridor_xy)
    inside = shapely.intersects(corridor_xy, lines)
    for k, r in enumerate(rows):
        if not inside[k]:
            continue
        cls = r["class"] or "unknown"
        xy = pxy[starts[k]:ends[k]]
        x, y = xy[:, 0], xy[:, 1]
        seg_len = np.concatenate([[0.0], np.cumsum(np.hypot(*np.diff(xy, axis=0).T))])
        total = seg_len[-1]
        if total <= 0.01:
            continue
        flags = []
        for lo, hi, vals in _ranges(r.get("road_flags"), "values"):
            bits = 0
            for v in vals or []:
                bits |= {"is_bridge": F_BRIDGE, "is_tunnel": F_TUNNEL, "is_link": F_LINK}.get(v, 0)
            if bits:
                flags.append((lo, hi, bits))
        for lo, hi, lv in _ranges(r.get("level_rules")):
            if lv and lv > 0:
                flags.append((lo, hi, F_BRIDGE))
            elif lv and lv < 0:
                flags.append((lo, hi, F_TUNNEL))
        surface = "paved"
        for lo, hi, sv in _ranges(r.get("road_surface")):
            if sv in UNPAVED:
                flags.append((lo, hi, F_UNPAVED))
                surface = sv
            elif sv in COBBLES:
                flags.append((lo, hi, F_COBBLES))
                surface = sv
        width = CLASS_WIDTH.get(cls, 5.0)
        for lo, hi, wv in _ranges(r.get("width_rules")):
            if wv and 2.0 <= wv <= 30.0:
                width = wv
        if (r.get("subclass") or "") == "link":
            flags.append((0.0, 1.0, F_LINK))
        conns = sorted(((c["at"], c["connector_id"]) for c in (r.get("connectors") or [])), key=lambda t: t[0])
        if len(conns) < 2:
            continue
        name = primary_name(r) or ""
        for (fa, ca), (fb, cb) in zip(conns[:-1], conns[1:]):
            da, db = fa * total, fb * total
            if db - da < 0.05:
                continue
            inner = (seg_len > da) & (seg_len < db)
            pa = np.array([np.interp(da, seg_len, x), np.interp(da, seg_len, y)])
            pb = np.array([np.interp(db, seg_len, x), np.interp(db, seg_len, y)])
            pts = np.vstack([pa, xy[inner], pb])
            ef = []
            for lo, hi, bits in flags:
                lo2, hi2 = (max(lo, fa) - fa) / (fb - fa), (min(hi, fb) - fa) / (fb - fa)
                if hi2 > lo2:
                    ef.append((lo2, hi2, bits))
            edges.append(Edge(r["id"], node(ca, pa), node(cb, pb), pts, db - da, cls, name, ef, width, surface))
    return np.array(node_xy), edges


@dataclass
class Graph:
    node_xy: np.ndarray
    edges: list
    matrix: csr_matrix
    pred_edge: dict  # (u, v) -> edge index (cheapest parallel edge)


def _cost(e: Edge, allow):
    c = CLASS_COST.get(e.cls, 2.0)
    if e.cls in allow and e.unlocked:
        c = allow[e.cls]
    if c is None:
        return None
    for lo, hi, bits in e.flags_at:
        if bits & F_LINK:
            c *= 1.5
            break
    return e.length * c


def make_graph(node_xy, edges, allow=None) -> Graph:
    allow = allow or {}
    best = {}
    for i, e in enumerate(edges):
        c = _cost(e, allow)
        if c is None:
            continue
        for k in ((e.a, e.b), (e.b, e.a)):
            if k not in best or best[k][0] > c:
                best[k] = (c, i)
    keys = list(best.keys())
    u = np.array([k[0] for k in keys], dtype=np.int64)
    v = np.array([k[1] for k in keys], dtype=np.int64)
    w = np.array([best[k][0] for k in keys]) + 1e-6
    n = len(node_xy)
    m = csr_matrix((w, (u, v)), shape=(n, n))
    return Graph(node_xy, edges, m, {k: best[k][1] for k in keys})


def split_edge_at(node_xy, edges, ei, point):
    """Inserts a node where [point] projects onto edge ei; returns the new node index."""
    e = edges[ei]
    line = shapely.LineString(e.xy)
    d = line.project(shapely.Point(point))
    d = min(max(d, 0.0), line.length)
    if d < 0.5:
        return e.a, node_xy, edges
    if d > line.length - 0.5:
        return e.b, node_xy, edges
    cum = np.concatenate([[0.0], np.cumsum(np.hypot(*np.diff(e.xy, axis=0).T))])
    p = np.array([np.interp(d, cum, e.xy[:, 0]), np.interp(d, cum, e.xy[:, 1])])
    inner = cum > 0
    first = np.vstack([e.xy[(cum < d)], p])
    second = np.vstack([p, e.xy[(cum > d)]])
    nid = len(node_xy)
    node_xy = np.vstack([node_xy, p])
    f = d / line.length

    def sub(lo, hi):
        out = []
        for a, b, bits in e.flags_at:
            a2, b2 = (max(a, lo) - lo) / (hi - lo), (min(b, hi) - lo) / (hi - lo)
            if b2 > a2:
                out.append((a2, b2, bits))
        return out

    e1 = Edge(e.seg_id, e.a, nid, first, d, e.cls, e.name, sub(0, f), e.width, e.surface, e.unlocked)
    e2 = Edge(e.seg_id, nid, e.b, second, line.length - d, e.cls, e.name, sub(f, 1), e.width, e.surface, e.unlocked)
    edges = edges[:ei] + [e1] + edges[ei + 1:] + [e2]
    return nid, node_xy, edges


def corridor(proj, spec: RouteSpec, min_buf=1500.0, max_buf=10000.0, frac=0.35):
    x, y = proj.to_xy([w.lon for w in spec.waypoints], [w.lat for w in spec.waypoints])
    parts = []
    for i in range(len(x) - 1):
        seg = shapely.LineString([(x[i], y[i]), (x[i + 1], y[i + 1])])
        buf = min(max(seg.length * frac, min_buf), max_buf)
        parts.append(seg.buffer(buf))
    if len(parts) == 0:
        parts = [shapely.Point(x[0], y[0]).buffer(min_buf)]
    return shapely.union_all(parts)


def xy_poly_to_lonlat(proj, poly):
    def tr(coords):
        c = np.asarray(coords)
        lo, la = proj.to_lonlat(c[:, 0], c[:, 1])
        return np.column_stack([lo, la])
    polys = [poly] if poly.geom_type == "Polygon" else list(poly.geoms)
    return shapely.MultiPolygon([shapely.Polygon(tr(p.exterior.coords), [tr(r.coords) for r in p.interiors])
                                 for p in polys])


MAIN_CLASSES = {"primary", "secondary", "tertiary", "unclassified", "trunk"}
# How strongly the pass point is pulled to the waypoint (cost metres per metre of distance). Towns only
# need to be passed through; summits and turns are exact places.
PULL = {"town": 0.05, "village": 0.05, "city": 0.05, "place": 0.05, "sprint": 0.15}


def candidates(graph_nodes_tree, node_xy, edges, wp_xy, radius, allowed_edge, main_node, max_n=16):
    """Graph nodes usable as the pass point of a soft waypoint: nodes on main roads if there are any,
    so a town centroid does not drag the route into its back streets."""
    idx = graph_nodes_tree.query_ball_point(wp_xy, radius)
    idx = [i for i in idx if allowed_edge[i]]
    main = [i for i in idx if main_node[i]]
    if main:
        idx = main
    if not idx:
        return []
    d = np.hypot(*(node_xy[idx] - wp_xy).T)
    order = np.argsort(d)[:max_n]
    return [idx[i] for i in order]


@dataclass
class RoutedPath:
    xy: np.ndarray  # dense polyline
    edge_idx: np.ndarray  # per polyline vertex: edge index of the following piece (-1 at end)
    edge_frac: np.ndarray  # per vertex: fraction along that edge (oriented as ridden)
    edge_rev: np.ndarray  # per vertex: True when the edge is ridden from b to a
    leg_lengths: list
    leg_nodes: list
    edges: list
    used_edges: set


def route(spec: RouteSpec, proj, ov: Overture, corridor_poly=None):
    from scipy.spatial import cKDTree
    corr = corridor_poly if corridor_poly is not None else corridor(proj, spec)
    area = xy_poly_to_lonlat(proj, corr)
    rows = ov.query_cells("transportation/segment", cells_for_lonlat_polygon(area), area=area)
    allow, unlock_at = {}, {}
    for w in spec.waypoints:
        r = (w.road or "").lower()
        for cls, c in UNLOCKABLE.items():
            if cls in r or (cls == "track" and w.kind in ("gravel", "unpaved")) or (cls == "cycleway" and w.kind == "cycleway"):
                allow[cls] = c
                unlock_at.setdefault(cls, []).append(proj.to_xy(w.lon, w.lat))
    node_xy, edges = build_graph(rows, proj, corr, allow_classes=tuple(allow))
    n_unlocked = 0
    for cls, pts in unlock_at.items():
        tree = cKDTree(np.array(pts, float).reshape(-1, 2))
        for e in edges:
            if e.cls == cls and np.isfinite(tree.query(e.xy, distance_upper_bound=UNLOCK_RADIUS_M)[0]).any():
                e.unlocked = True
                n_unlocked += 1
    log(f"graph: {len(rows)} segments -> {len(edges)} edges, {len(node_xy)} nodes, allow={allow} "
        f"({n_unlocked} edges within {UNLOCK_RADIUS_M / 1000:.1f} km of their waypoints)")

    # Every waypoint gets a graph node on the road beside it. Hard waypoints split their nearest
    # routable edge; soft ones split the nearest edges within their radius (main roads first, both
    # carriageways of a divided road), because a long segment such as an expressway between two
    # junctions has no node anywhere near the waypoint, and a node-only candidate set then sends the
    # route over a bridge or a parallel side road instead.
    wp_xy = np.column_stack(proj.to_xy([w.lon for w in spec.waypoints], [w.lat for w in spec.waypoints]))
    routable = [i for i, e in enumerate(edges) if _cost(e, allow) is not None]
    lines = [shapely.LineString(edges[i].xy) for i in routable]
    tree_lines = shapely.STRtree(lines)
    main_idx = [j for j, i in enumerate(routable) if edges[i].cls in MAIN_CLASSES]
    main_tree = shapely.STRtree([lines[j] for j in main_idx]) if main_idx else None
    for k, w in enumerate(spec.waypoints):
        if w.widen > w.radius > 0 and main_tree is not None:
            p = shapely.Point(wp_xy[k])
            d = lines[main_idx[int(main_tree.nearest(p))]].distance(p)
            if d > w.radius - 10:
                w.radius = min(w.widen, d + 40.0)
    requests: dict[int, list] = {}
    for k, w in enumerate(spec.waypoints):
        p = shapely.Point(wp_xy[k])
        if w.radius <= 0:
            j = int(tree_lines.nearest(p))
            dist = lines[j].distance(p)
            if dist > 300:
                log(f"WARNING waypoint {k} ({w.name}) is {dist:.0f} m from the nearest road")
            picks = [j]
        else:
            hits = tree_lines.query(p, predicate="dwithin", distance=w.radius)
            hits = sorted(hits, key=lambda j: lines[j].distance(p))
            main = [j for j in hits if edges[routable[j]].cls in MAIN_CLASSES]
            picks, segs = [], set()
            for j in (main or hits):
                if edges[routable[j]].seg_id not in segs:
                    segs.add(edges[routable[j]].seg_id)
                    picks.append(j)
                if len(picks) == 2:
                    break
        for j in picks:
            requests.setdefault(routable[j], []).append((lines[j].project(p), k))
    hard_nodes, soft_nodes = {}, {}
    # Splitting an edge keeps its first part at the same index, so the points on one edge are split
    # from the far end backwards and every request still finds its piece at the original index.
    for ei, reqs in requests.items():
        for _, k in sorted(reqs, reverse=True):
            nid, node_xy, edges = split_edge_at(node_xy, edges, ei, wp_xy[k])
            if spec.waypoints[k].radius <= 0:
                hard_nodes[k] = nid
            else:
                soft_nodes.setdefault(k, []).append(nid)
    g = make_graph(node_xy, edges, allow)
    has_edge = np.zeros(len(node_xy), bool)
    has_edge[g.matrix.nonzero()[0]] = True
    main_node = np.zeros(len(node_xy), bool)
    for e in edges:
        if e.cls in MAIN_CLASSES:
            main_node[e.a] = main_node[e.b] = True
    ntree = cKDTree(node_xy)

    cand = []
    for k, w in enumerate(spec.waypoints):
        if k in hard_nodes:
            cand.append([hard_nodes[k]])
            continue
        c = candidates(ntree, node_xy, edges, wp_xy[k], w.radius, has_edge, main_node)
        r = w.radius
        while not c and r < 3000:
            r *= 2
            c = candidates(ntree, node_xy, edges, wp_xy[k], r, has_edge, main_node)
        c = list(dict.fromkeys(soft_nodes.get(k, []) + c))
        if not c:
            raise RuntimeError(f"no road near waypoint {k} {w.name}")
        cand.append(c)

    # A race never turns round on the road it came up. When the cheapest chain does (a pass point at
    # a junction reached and left on the same road, a waypoint on a spur), the reversed direction of
    # that edge is made expensive and the chain solved again, so the route takes the other road.
    for attempt in range(3):
        # Dynamic programme over the candidate pass points: total cost = road cost + small pull to the waypoint.
        n_wp = len(cand)
        best = [dict() for _ in range(n_wp)]
        for c in cand[0]:
            best[0][c] = (0.0, None)
        preds = {}
        for k in range(n_wp - 1):
            src = list(best[k].keys())
            dist, pred = dijkstra(g.matrix, directed=True, indices=src, return_predecessors=True)
            for si, s in enumerate(src):
                preds[(k, s)] = pred[si]
            for c in cand[k + 1]:
                pull = PULL.get(spec.waypoints[k + 1].kind, 0.3) * float(np.hypot(*(node_xy[c] - wp_xy[k + 1])))
                for si, s in enumerate(src):
                    d = dist[si, c]
                    if not np.isfinite(d):
                        continue
                    tot = best[k][s][0] + d + pull
                    if c not in best[k + 1] or best[k + 1][c][0] > tot:
                        best[k + 1][c] = (tot, s)
            if not best[k + 1]:
                raise RuntimeError(f"no path from waypoint {k} ({spec.waypoints[k].name}) to {k + 1} ({spec.waypoints[k + 1].name})")
        end = min(best[-1], key=lambda c: best[-1][c][0])
        chain = [end]
        for k in range(n_wp - 1, 0, -1):
            chain.append(best[k][chain[-1]][1])
        chain.reverse()

        # Expand node chains into a polyline with edge references.
        xy_parts, ei_parts, fr_parts, rev_parts, leg_lengths = [], [], [], [], []
        used = set()
        nodes_all = []
        for k in range(n_wp - 1):
            pred = preds[(k, chain[k])]
            nodes = [chain[k + 1]]
            while nodes[-1] != chain[k]:
                p = pred[nodes[-1]]
                if p < 0:
                    raise RuntimeError("broken predecessor chain")
                nodes.append(p)
            nodes.reverse()
            nodes_all.extend(nodes if not nodes_all else nodes[1:])
            leg_len = 0.0
            for u, v in zip(nodes[:-1], nodes[1:]):
                ei = g.pred_edge[(u, v)]
                e = edges[ei]
                used.add(ei)
                rev = e.a != u
                pts = e.xy[::-1] if rev else e.xy
                cum = np.concatenate([[0.0], np.cumsum(np.hypot(*np.diff(pts, axis=0).T))])
                xy_parts.append(pts[:-1])
                ei_parts.append(np.full(len(pts) - 1, ei))
                fr_parts.append(cum[:-1] / max(cum[-1], 1e-9))
                rev_parts.append(np.full(len(pts) - 1, rev))
                leg_len += e.length
            leg_lengths.append(leg_len)
        last = node_xy[chain[-1]]
        xy = np.vstack(xy_parts + [last[None, :]])
        ei = np.concatenate(ei_parts + [[-1]])
        fr = np.concatenate(fr_parts + [[1.0]])
        rv = np.concatenate(rev_parts + [[False]])
        total = sum(leg_lengths)
        if attempt > 0 and total > prev_total + 1000.0:
            # Avoiding the turn costs a long loop: the waypoint itself is off the race road, which
            # the validator reports (path doubling back); keep the shorter solution.
            log("route without the turn is much longer; keeping the turn")
            xy, ei, fr, rv, leg_lengths, chain, used = prev
            break
        reversals = []
        for k in range(len(nodes_all) - 2):
            u, v, w_ = nodes_all[k], nodes_all[k + 1], nodes_all[k + 2]
            if w_ == u and g.pred_edge.get((u, v)) == g.pred_edge.get((v, u)):
                reversals.append((v, u))
        if not reversals or attempt == 2:
            break
        prev, prev_total = (xy, ei, fr, rv, leg_lengths, chain, used), total
        log(f"route turns round at {len(reversals)} place(s); penalising the reversed edges")
        m = g.matrix.tolil()
        for v, u in reversals:
            m[v, u] = m[v, u] * 6.0
        g.matrix = m.tocsr()
    return RoutedPath(xy, ei, fr, rv, leg_lengths, chain, edges, used), g


def resample(rp: RoutedPath, step=5.0):
    """Samples the routed polyline every [step] metres; per sample the edge attributes."""
    xy = rp.xy
    seg = np.hypot(*np.diff(xy, axis=0).T)
    keep = np.concatenate([[True], seg > 1e-3])
    xy, ei, fr, rv = xy[keep], rp.edge_idx[keep], rp.edge_frac[keep], rp.edge_rev[keep]
    seg = np.hypot(*np.diff(xy, axis=0).T)
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    total = cum[-1]
    n = int(math.floor(total / step)) + 1
    s = np.arange(n) * step
    if total - s[-1] > 0.5:
        s = np.append(s, total)
    x = np.interp(s, cum, xy[:, 0])
    y = np.interp(s, cum, xy[:, 1])
    vi = np.clip(np.searchsorted(cum, s, side="right") - 1, 0, len(cum) - 2)
    e_idx = ei[vi]
    # Fraction along the edge as ridden at each sample (linear between the vertex fractions).
    t = (s - cum[vi]) / np.maximum(seg[vi], 1e-9)
    nxt = np.minimum(vi + 1, len(fr) - 1)
    f_next = np.where(ei[nxt] == ei[vi], fr[nxt], 1.0)
    f = fr[vi] + (f_next - fr[vi]) * t
    flags = np.zeros(len(s), np.uint8)
    width = np.zeros(len(s), np.float32)
    cls = np.zeros(len(s), np.uint8)
    for i in range(len(s)):
        e = rp.edges[e_idx[i]] if e_idx[i] >= 0 else rp.edges[e_idx[i - 1]]
        ff = 1 - f[i] if rv[vi[i]] else f[i]
        b = 0
        for lo, hi, bits in e.flags_at:
            if lo - 1e-6 <= ff <= hi + 1e-6:
                b |= bits
        flags[i] = b
        width[i] = e.width
        cls[i] = CLASS_CODE.get(e.cls, 0)
    return s, x, y, flags, width, cls, e_idx
