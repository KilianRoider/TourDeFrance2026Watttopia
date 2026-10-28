"""World layers around the route, tiled in the route's local metric projection.

LODs (all square tiles in local metres, tile (tx, ty) covers [tx*T, (tx+1)*T) x [ty*T, (ty+1)*T)):
  0: 1280 m tiles near the road: heights every 10 m (carved road bed), land cover 10 m, imagery 10 m,
     buildings, other roads, water.
  1: 5120 m tiles out to ~7 km: heights 40 m, land cover 40 m, imagery 20 m, large water.
  2: 20480 m tiles out to ~30 km (horizon): heights 160 m, imagery 80 m, large water.
Every raster has row 0 at the north edge (like an image). Height grids have n+1 samples per side
(edges shared with the neighbour); land cover and imagery have n cells/pixels per side.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
import shapely
from scipy.ndimage import binary_dilation, gaussian_filter, grey_opening, map_coordinates
from scipy.spatial import cKDTree

from geo_common import DEM, S2RGB, WORLDCOVER, Overture, cells_for_lonlat_polygon, log, primary_name, sample_grid
from route import CLASS_CODE, CLASS_WIDTH, F_BRIDGE, F_TUNNEL, xy_poly_to_lonlat


@dataclass
class Lod:
    lod: int
    tile: float  # metres
    grid: int  # height cells per side (grid + 1 samples)
    image: int  # imagery pixels per side
    landcover: int  # land cover cells per side (0 = none)
    reach: float  # tiles within this distance of the path
    dem_ovr: int  # overview level of the DEM to sample
    s2_ovr: int
    wc_ovr: int
    jpeg_q: int


LODS = [
    Lod(0, 1280.0, 128, 128, 128, 1200.0, -1, -1, -1, 80),
    Lod(1, 5120.0, 128, 256, 128, 7000.0, -1, 0, 1, 78),
    Lod(2, 20480.0, 128, 256, 0, 30000.0, 1, 2, 3, 76),
]

# Land cover codes in the pack.
LC_NAMES = ["none", "tree", "shrub", "grass", "crop", "built", "bare", "snow", "water", "wetland", "mangrove",
            "moss", "vineyard", "orchard", "road"]
WC_TO_LC = np.zeros(256, np.uint8)
for wc, lc in {10: 1, 20: 2, 30: 3, 40: 4, 50: 5, 60: 6, 70: 7, 80: 8, 90: 9, 95: 10, 100: 11}.items():
    WC_TO_LC[wc] = lc
LC_VINEYARD, LC_ORCHARD, LC_ROAD = 12, 13, 14

ROOF_SHAPES = {"flat": 1, "gabled": 2, "hipped": 3, "pyramidal": 4, "skillion": 5, "dome": 6, "onion": 7, "round": 8,
               "gambrel": 9, "mansard": 10, "saltbox": 11, "half_hipped": 12, "butterfly": 13, "spherical": 14}
BUILDING_CLASSES = ["unknown", "house", "residential", "apartments", "detached", "terrace", "farm", "farm_auxiliary",
                    "barn", "shed", "garage", "industrial", "commercial", "retail", "office", "church", "chapel",
                    "school", "civic", "public", "hotel", "hospital", "service", "hangar", "warehouse", "stadium",
                    "roof", "castle", "cathedral", "train_station", "sports_hall", "bungalow", "greenhouse"]
BUILDING_CODE = {c: i for i, c in enumerate(BUILDING_CLASSES)}
WATER_CLASSES = ["unknown", "lake", "pond", "reservoir", "river", "stream", "canal", "ditch", "drain", "ocean", "sea",
                 "basin", "swimming_pool", "water", "bay", "lagoon", "wetland", "spring"]
WATER_CODE = {c: i for i, c in enumerate(WATER_CLASSES)}


def tile_box(lod: Lod, tx, ty):
    t = lod.tile
    return shapely.box(tx * t, ty * t, (tx + 1) * t, (ty + 1) * t)


def tiles_near(lod: Lod, path_line, reach=None):
    reach = lod.reach if reach is None else reach
    xmin, ymin, xmax, ymax = path_line.bounds
    t = lod.tile
    area = path_line.buffer(reach, quad_segs=4)
    shapely.prepare(area)
    out = []
    for tx in range(math.floor((xmin - reach) / t), math.floor((xmax + reach) / t) + 1):
        for ty in range(math.floor((ymin - reach) / t), math.floor((ymax + reach) / t) + 1):
            if area.intersects(tile_box(lod, tx, ty)):
                out.append((tx, ty))
    return out


def s2_to_rgb(a):
    """Sentinel-2 L2A reflectance x 10000 to display sRGB: gain, a soft shoulder and gamma."""
    v = a.astype(np.float32) / 10000.0
    v = v * 3.2
    v = v / (1 + 0.25 * v)  # keeps bright roofs and clouds from clipping hard
    v = np.clip(v, 0, 1) ** (1 / 1.9)
    # A touch more saturation: composites look washed out next to rendered vegetation.
    m = v.mean(0, keepdims=True)
    v = np.clip(m + (v - m) * 1.15, 0, 1)
    return (v * 255 + 0.5).astype(np.uint8)


class Sampler:
    """Raster sampling in local metres via the projection, with a ground (de-building) filter for the DEM."""

    def __init__(self, proj):
        self.proj = proj

    def _box(self, lon, lat, margin):
        return lon.min() - margin, lat.min() - margin, lon.max() + margin, lat.max() + margin

    def ground(self, X, Y, ovr=-1, order=3):
        """Terrain height at local points: the DSM with buildings flattened (grey opening in built-up cells)."""
        lon, lat = self.proj.to_lonlat(X, Y)
        dem, l0, t0, dl, dt = DEM.read(*self._box(lon, lat, 0.004 * (1 if ovr < 0 else 4)), ovr=ovr)
        d = dem[0].astype(np.float32)
        if ovr < 0:
            wc, wl0, wt0, wdl, wdt = WORLDCOVER.read(l0, t0 - dt * d.shape[0], l0 + dl * d.shape[1], t0, ovr=-1)
            # Built-up fraction of every DEM cell from the 10 m land cover (3x3 samples per 30 m cell).
            rr, cc = np.mgrid[0:d.shape[0], 0:d.shape[1]]
            frac = np.zeros_like(d)
            for oy in (-1 / 3, 0, 1 / 3):
                for ox in (-1 / 3, 0, 1 / 3):
                    lo = l0 + (cc + 0.5 + ox) * dl
                    la = t0 - (rr + 0.5 + oy) * dt
                    frac += sample_grid(wc[0], wl0, wt0, wdl, wdt, lo, la, order=0) == 50
            frac = gaussian_filter(frac / 9.0, 1.0)
            # Opening (erosion then dilation) removes blobs narrower than ~150 m, i.e. buildings; smoothing
            # it avoids the flat terraces an opening leaves behind.
            opened = gaussian_filter(grey_opening(d, size=(5, 5)), 1.2)
            d = d - np.clip(frac * 1.6, 0, 1) * np.maximum(d - opened, 0)
        return sample_grid(d, l0, t0, dl, dt, lon, lat, order=order)

    def dsm(self, X, Y):
        lon, lat = self.proj.to_lonlat(X, Y)
        dem, l0, t0, dl, dt = DEM.read(*self._box(lon, lat, 0.002))
        return sample_grid(dem[0].astype(np.float32), l0, t0, dl, dt, lon, lat, order=1)

    def landcover(self, X, Y, ovr=-1):
        lon, lat = self.proj.to_lonlat(X, Y)
        wc, l0, t0, dl, dt = WORLDCOVER.read(*self._box(lon, lat, 0.002), ovr=ovr)
        return WC_TO_LC[sample_grid(wc[0], l0, t0, dl, dt, lon, lat, order=0)]

    def imagery(self, X, Y, ovr=-1):
        lon, lat = self.proj.to_lonlat(X, Y)
        s2, l0, t0, dl, dt = S2RGB.read(*self._box(lon, lat, 0.002), ovr=ovr)
        a = sample_grid(s2.astype(np.float32), l0, t0, dl, dt, lon, lat, order=1)
        return s2_to_rgb(a)


def cell_centres(lod: Lod, tx, ty, n):
    t = lod.tile
    c = (np.arange(n) + 0.5) * t / n
    X = tx * t + c[None, :].repeat(n, 0)
    Y = (ty + 1) * t - c[:, None].repeat(n, 1)
    return X, Y


def grid_points(lod: Lod, tx, ty):
    t = lod.tile
    n = lod.grid
    c = np.arange(n + 1) * t / n
    X = tx * t + c[None, :].repeat(n + 1, 0)
    Y = (ty + 1) * t - c[:, None].repeat(n + 1, 1)
    return X, Y


class RoadBed:
    """Carves the terrain to the road: flat bed under the road, cut/fill slopes beside it."""

    def __init__(self, s, x, y, z, width, flags, slope=0.75, shoulder=1.5, reach=45.0, below=0.15, min_half=0.0):
        """[min_half] widens the flat bed to at least that distance from the centre line. The 10 m height
        grid uses 7.5 m: then all four grid points around a point of the road lie on the bed, and the
        grid interpolates the road's own height there (on a 15 % ramp with a narrow bed, corners on
        the cut slope put the grid 1 m off the road). The corridor strips keep the narrow bed."""
        keep = (flags & (F_BRIDGE | F_TUNNEL)) == 0
        # 2.5 m samples: the nearest sample is then within 1.25 m along the road.
        sd = np.arange(0, s[-1], 2.5)
        xd, yd, zd = np.interp(sd, s, x), np.interp(sd, s, y), np.interp(sd, s, z)
        wd = np.interp(sd, s, width)
        kd = np.interp(sd, s, keep.astype(float)) > 0.99
        self.xy = np.column_stack([xd, yd])[kd]
        self.z = zd[kd] - below
        self.half = np.maximum(wd[kd] / 2 + shoulder, min_half)
        self.tree = cKDTree(self.xy)
        self.slope = slope
        self.reach = reach

    def apply(self, X, Y, H):
        pts = np.column_stack([X.ravel(), Y.ravel()])
        h = H.ravel().astype(np.float64).copy()
        near = self.tree.query_ball_point(pts, self.reach + 10.0, return_length=True) > 0
        if not near.any():
            return H
        idx = np.nonzero(near)[0]
        d, j = self.tree.query(pts[idx], k=64, distance_upper_bound=self.reach + 10.0)
        valid = np.isfinite(d)
        jj = np.where(valid, j, 0)
        e = np.maximum(d - self.half[jj], 0.0)
        zz = self.z[jj]
        # Constraints from the nearest road first; one that contradicts a nearer one is dropped (between
        # two hairpin legs that is where the retaining wall stands), so a point always fits its own road.
        lo = np.full(len(idx), -np.inf)
        hi = np.full(len(idx), np.inf)
        for k in range(d.shape[1]):
            lk = np.where(valid[:, k], zz[:, k] - self.slope * e[:, k], -np.inf)
            hk = np.where(valid[:, k], zz[:, k] + self.slope * e[:, k], np.inf)
            nlo = np.maximum(lo, lk)
            nhi = np.minimum(hi, hk)
            ok = nlo <= nhi + 1e-9
            lo = np.where(ok, nlo, lo)
            hi = np.where(ok, nhi, hi)
        orig = h[idx]
        carved = np.clip(orig, lo, hi)
        # Fade the constraint out over the last 10 m so there is no step at the reach.
        dmin = d[:, 0]
        fade = np.clip((self.reach + 10.0 - dmin) / 10.0, 0, 1)
        h[idx] = orig + (carved - orig) * fade
        return h.reshape(H.shape)


CORRIDOR_OFFSETS = np.array([0.0, 1.5, 3.0, 6.0, 10.0, 15.0, 22.0, 30.0, 40.0])  # metres beyond the road edge
CORRIDOR_SECTION = 2048  # path samples per corridor chunk (10.24 km)


def corridor_strips(sampler, bed, s, x, y, z, width, min_gap_s=60.0, chunk=1024):
    """Carved terrain on cross-sections every path sample, left and right of the road edge, relative
    to the road elevation; plus how many offsets per side are free of other parts of the route (at a
    hairpin the next leg starts there). The renderer builds the road verge and embankment from these
    at 5 m resolution, which the 10 m terrain grid cannot show."""
    tx, ty = np.gradient(x), np.gradient(y)
    nrm = np.hypot(tx, ty) + 1e-9
    nx, ny = -ty / nrm, tx / nrm  # left normal
    half = width / 2.0
    tree = cKDTree(np.column_stack([x, y]))
    k = len(CORRIDOR_OFFSETS)
    out = {"left": np.zeros((len(s), k)), "right": np.zeros((len(s), k))}
    free = {"left": np.zeros(len(s), np.uint8), "right": np.zeros(len(s), np.uint8)}
    for a in range(0, len(s), chunk):
        sl = slice(a, min(a + chunk, len(s)))
        for side, sign in (("left", 1.0), ("right", -1.0)):
            lat = half[sl, None] + CORRIDOR_OFFSETS[None, :]
            PX = x[sl, None] + sign * nx[sl, None] * lat
            PY = y[sl, None] + sign * ny[sl, None] * lat
            H = bed.apply(PX, PY, sampler.ground(PX, PY))
            out[side][sl] = H - z[sl, None]
            # An offset is taken when another part of the route (more than min_gap_s away along the
            # road) is closer to it than this road is.
            dist, j = tree.query(np.column_stack([PX.ravel(), PY.ravel()]))
            other = (np.abs(s[j] - np.repeat(s[sl], k)) > min_gap_s).reshape(PX.shape)
            taken = other & (dist.reshape(PX.shape) < lat)
            free[side][sl] = np.where(taken.any(1), taken.argmax(1), k)
    return out, free


def rasterize_local(shapes, lod: Lod, tx, ty, n, fill=0, all_touched=False):
    from affine import Affine
    from rasterio.features import rasterize
    t = lod.tile
    tr = Affine(t / n, 0, tx * t, 0, -t / n, (ty + 1) * t)
    if not shapes:
        return np.full((n, n), fill, np.uint8)
    return rasterize(shapes, out_shape=(n, n), transform=tr, fill=fill, dtype="uint8", all_touched=all_touched)


# ---------------------------------------------------------------------------------------------
# Vector layers
# ---------------------------------------------------------------------------------------------
def project_geoms(proj, geoms):
    """Projects an array of shapely lon/lat geometries in one transform call."""
    coords, idx = shapely.get_coordinates(geoms, return_index=True)
    if len(coords) == 0:
        return geoms
    x, y = proj.to_xy(coords[:, 0], coords[:, 1])
    return shapely.set_coordinates(np.asarray(geoms, dtype=object), np.column_stack([x, y]))


def parse_colour(c):
    if not c:
        return 0
    c = c.strip().lower()
    named = {"white": 0xF0F0F0, "black": 0x202020, "grey": 0x808080, "gray": 0x808080, "red": 0xA03020,
             "brown": 0x7A4A2A, "beige": 0xD8C8A0, "yellow": 0xD8C060, "orange": 0xD08040, "green": 0x507040,
             "blue": 0x4060A0, "darkgray": 0x505050, "lightgrey": 0xC0C0C0, "silver": 0xB0B0B0}
    if c.startswith("#") and len(c) in (4, 7):
        h = c[1:]
        if len(h) == 3:
            h = "".join(ch * 2 for ch in h)
        try:
            return 0xFF000000 | int(h, 16)
        except ValueError:
            return 0
    return (0xFF000000 | named[c]) if c in named else 0


def default_height(area, cls):
    if cls in ("church", "cathedral", "chapel"):
        return 14.0 if cls != "chapel" else 7.0
    if cls in ("apartments",):
        return 15.0
    if cls in ("garage", "shed", "roof", "greenhouse"):
        return 3.0
    if area < 25:
        return 3.0
    if area < 60:
        return 4.5
    if area < 250:
        return 7.0
    if area < 1000:
        return 9.0
    return 10.0


def corridor_cells(proj, path_line, reach):
    return cells_for_lonlat_polygon(xy_poly_to_lonlat(proj, path_line.buffer(reach, quad_segs=4)))


def corridor_query(ov: Overture, key, proj, path_line, reach):
    """Overture rows of [key] within [reach] metres of the path (by geometry, not just by cell)."""
    area = xy_poly_to_lonlat(proj, path_line.buffer(reach, quad_segs=4))
    return ov.query_cells(key, cells_for_lonlat_polygon(area), area=area)


def collect_buildings(proj, ov: Overture, path_line, reach=350.0):
    rows = corridor_query(ov, "buildings/building", proj, path_line, reach)
    rows = [r for r in rows if not r.get("is_underground")]
    if not rows:
        return [], []
    geoms = project_geoms(proj, shapely.from_wkb([r["geometry"] for r in rows]))
    shapely.prepare(path_line)
    d = shapely.distance(geoms, path_line)
    keep = np.nonzero(d <= reach)[0]
    return [rows[i] for i in keep], [geoms[i] for i in keep]


def collect_roads(proj, ov: Overture, path_line, reach):
    rows = corridor_query(ov, "transportation/segment", proj, path_line, reach)
    rows = [r for r in rows if r["subtype"] in ("road", "rail")]
    geoms = project_geoms(proj, shapely.from_wkb([r["geometry"] for r in rows]))
    d = shapely.distance(geoms, path_line)
    keep = np.nonzero(d <= reach)[0]
    return [rows[i] for i in keep], [geoms[i] for i in keep]


def collect_water(proj, ov: Overture, path_line, reach):
    rows = corridor_query(ov, "base/water", proj, path_line, reach)
    geoms = project_geoms(proj, shapely.from_wkb([r["geometry"] for r in rows]))
    geoms = shapely.make_valid(geoms)
    d = shapely.distance(geoms, path_line)
    keep = np.nonzero(d <= reach)[0]
    return [rows[i] for i in keep], [geoms[i] for i in keep]


def collect_landuse(proj, ov: Overture, path_line, reach):
    rows = corridor_query(ov, "base/land_use", proj, path_line, reach)
    rows = [r for r in rows if r["class"] in ("vineyard", "orchard")]
    if not rows:
        return []
    geoms = shapely.make_valid(project_geoms(proj, shapely.from_wkb([r["geometry"] for r in rows])))
    return [(g, LC_VINEYARD if r["class"] == "vineyard" else LC_ORCHARD) for r, g in zip(rows, geoms)]


def collect_places(proj, ov: Overture, path_line, s, x, y, reach=3000.0):
    rows = corridor_query(ov, "divisions/division", proj, path_line, reach)
    rows = [r for r in rows if r["subtype"] in ("locality", "localadmin", "neighborhood", "macrohood", "borough")]
    if not rows:
        return []
    geoms = project_geoms(proj, shapely.from_wkb([r["geometry"] for r in rows]))
    tree = cKDTree(np.column_stack([x, y]))
    out = []
    for r, g in zip(rows, geoms):
        if g.geom_type != "Point":
            g = g.representative_point()
        d, i = tree.query([g.x, g.y])
        if d > reach:
            continue
        # Signed offset: positive = left of the direction of travel.
        j = min(i + 1, len(x) - 1)
        i0 = max(j - 1, 0)
        tx, ty = x[j] - x[i0], y[j] - y[i0]
        side = np.sign(tx * (g.y - y[i]) - ty * (g.x - x[i]))
        out.append({"name": primary_name(r) or "", "subtype": r["subtype"], "class": r.get("class") or "",
                    "population": r.get("population"), "x": round(g.x, 1), "y": round(g.y, 1),
                    "s": round(float(s[i]), 1), "offset": round(float(side * d), 1)})
    out.sort(key=lambda p: p["s"])
    return out


def town_stretches(s, x, y, building_geoms, places, radius=120.0, min_count=6, min_len=250.0):
    """Built-up stretches of the route (where the town signs stand), named after the nearest locality."""
    if not building_geoms:
        return []
    c = shapely.centroid(np.array(building_geoms, dtype=object))
    bxy = shapely.get_coordinates(c)
    bt = cKDTree(bxy)
    step = 10
    idx = np.arange(0, len(s), step)
    counts = np.array(bt.query_ball_point(np.column_stack([x[idx], y[idx]]), radius, return_length=True))
    built = counts >= min_count
    # Close gaps shorter than 150 m (a park, a bridge) inside a town.
    from scipy.ndimage import binary_closing
    gap = max(1, int(150 / (5 * step) / 2))
    built = binary_closing(built, iterations=gap) | built
    towns = []
    locs = [p for p in places if p["subtype"] in ("locality", "localadmin") and p["class"] != ""
            or p["subtype"] == "locality"]
    i = 0
    while i < len(idx):
        if built[i]:
            j = i
            while j < len(idx) and built[j]:
                j += 1
            a, b = s[idx[i]], s[idx[min(j, len(idx) - 1)]]
            if b - a >= min_len:
                mid = idx[(i + j) // 2]
                best = None
                for p in locs:
                    d = math.hypot(p["x"] - x[mid], p["y"] - y[mid])
                    if d < 2500 and (best is None or d < best[0]):
                        best = (d, p)
                towns.append({"name": best[1]["name"] if best else "", "sIn": round(float(a), 1),
                              "sOut": round(float(b), 1), "population": best[1]["population"] if best else None})
            i = j
        else:
            i += 1
    return towns
