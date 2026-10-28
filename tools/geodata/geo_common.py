"""Shared paths, projection and remote data access for the geodata pipeline.

Everything that touches the network goes through this module so caching is in one place:
Overture GeoParquet queries are cached per 0.1 degree cell and theme, raster sources
(Copernicus DEM, ESA WorldCover map and Sentinel-2 composite) per COG block. A second run of
a route therefore needs no network at all.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

TOOLS_DIR = Path(__file__).resolve().parent
REPO_DIR = TOOLS_DIR.parent.parent
# Caches and outputs stay out of git (build/ is ignored); WWGEO_WORK overrides the location.
WORK_DIR = Path(os.environ.get("WWGEO_WORK", REPO_DIR / "build" / "geodata"))
CACHE_DIR = Path(os.environ.get("WWGEO_CACHE", WORK_DIR / "cache"))
OUT_DIR = Path(os.environ.get("WWGEO_OUT", WORK_DIR / "out"))

OVERTURE_RELEASE = os.environ.get("WWGEO_OVERTURE_RELEASE", "2026-10-21.0")
OVERTURE_BASE = f"overturemaps-us-west-2/release/{OVERTURE_RELEASE}/"


def log(*a):
    # Peak memory goes in every line: a stage shares the machine with other jobs, and the log is
    # where a run that grows too large shows which step did it.
    import resource
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1048576
    print(time.strftime("%H:%M:%S"), f"[{rss:.1f}G]", *a, flush=True)


# ---------------------------------------------------------------------------------------------
# Projection: a transverse Mercator centred on the route keeps scale error below 1e-4 for
# 100 km and gives plain metres (x east, y north) for the renderer.
# ---------------------------------------------------------------------------------------------
class LocalProjection:
    def __init__(self, lat0: float, lon0: float):
        from pyproj import Transformer
        self.lat0 = float(lat0)
        self.lon0 = float(lon0)
        self.proj4 = (f"+proj=tmerc +lat_0={self.lat0:.6f} +lon_0={self.lon0:.6f} +k=1 "
                      f"+x_0=0 +y_0=0 +ellps=WGS84 +units=m +no_defs")
        self._fwd = Transformer.from_crs("EPSG:4326", self.proj4, always_xy=True)
        self._inv = Transformer.from_crs(self.proj4, "EPSG:4326", always_xy=True)

    def to_xy(self, lon, lat):
        return self._fwd.transform(np.asarray(lon, dtype=float), np.asarray(lat, dtype=float))

    def to_lonlat(self, x, y):
        return self._inv.transform(np.asarray(x, dtype=float), np.asarray(y, dtype=float))


# ---------------------------------------------------------------------------------------------
# Overture Maps (GeoParquet on S3)
# ---------------------------------------------------------------------------------------------
_OVERTURE_COLUMNS = {
    "transportation/segment": ["id", "subtype", "class", "subclass", "connectors", "road_flags", "level_rules",
                               "width_rules", "road_surface", "names", "geometry"],
    "buildings/building": ["id", "names", "level", "height", "min_height", "is_underground", "num_floors", "subtype",
                           "class", "facade_color", "facade_material", "roof_material", "roof_shape",
                           "roof_direction", "roof_orientation", "roof_color", "roof_height", "geometry"],
    "base/water": ["id", "names", "subtype", "class", "level", "is_intermittent", "is_salt", "geometry"],
    "base/land_use": ["id", "names", "subtype", "class", "geometry"],
    "divisions/division": ["id", "names", "subtype", "class", "country", "population", "local_type", "geometry"],
    "divisions/division_area": ["id", "names", "subtype", "class", "division_id", "admin_level", "geometry"],
}

CELL_DEG = 0.1


def _s3fs(region: str):
    import pyarrow.fs as pafs
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    kw = dict(anonymous=True, region=region)
    if proxy:
        u = urllib.parse.urlparse(proxy)
        kw["proxy_options"] = {"scheme": u.scheme or "http", "host": u.hostname, "port": u.port}
    return pafs.S3FileSystem(**kw)


class Overture:
    """Bounding-box queries against one Overture release, cached per 0.1 degree cell."""

    def __init__(self):
        self._fs = None
        self._datasets = {}
        self._indexes = {}
        self._lock = threading.Lock()
        self.fetched = 0

    def _dataset(self, key):
        import pyarrow.dataset as ds
        with self._lock:
            if self._fs is None:
                self._fs = _s3fs("us-west-2")
            if key not in self._datasets:
                theme, typ = key.split("/")
                self._datasets[key] = ds.dataset(f"{OVERTURE_BASE}theme={theme}/type={typ}",
                                                 filesystem=self._fs, format="parquet")
            return self._datasets[key]

    def _cell_path(self, key, cx, cy):
        return CACHE_DIR / "overture" / OVERTURE_RELEASE / key.replace("/", "_") / f"{cx}_{cy}.parquet"

    def _rg_index(self, key):
        """Bounding boxes of every row group of [key]: (files, file index, rg index, xmin, xmax, ymin, ymax).

        Built once from the parquet footers, one file at a time, and cached. A pyarrow dataset scan
        keeps all ~500 footers of a theme in memory (4 GB for buildings), which the shared machine
        cannot afford; with this index a cell reads just the row groups that touch it."""
        with self._lock:
            if key in self._indexes:
                return self._indexes[key]
        p = CACHE_DIR / "overture" / OVERTURE_RELEASE / f"{key.replace('/', '_')}.rgindex.npz"
        if p.exists():
            z = np.load(p, allow_pickle=False)
            idx = (list(z["files"]), z["fi"], z["rg"], z["xmin"], z["xmax"], z["ymin"], z["ymax"])
        else:
            import pyarrow.parquet as pq
            files = sorted(self._dataset(key).files)
            t0 = time.time()

            def footer(i):
                for attempt in range(4):
                    try:
                        with self._fs.open_input_file(files[i]) as fh:
                            md = pq.ParquetFile(fh).metadata
                        break
                    except Exception as e:  # proxy hiccups
                        if attempt == 3:
                            raise
                        log("overture footer retry", files[i], e)
                        time.sleep(3 * (attempt + 1))
                names = [md.schema.column(c).path for c in range(md.num_columns)]
                cols = {n: names.index(f"bbox.{n}") for n in ("xmin", "xmax", "ymin", "ymax")}
                out = []
                for r in range(md.num_row_groups):
                    rg = md.row_group(r)
                    st = {n: rg.column(c).statistics for n, c in cols.items()}
                    if any(v is None or not v.has_min_max for v in st.values()):
                        b = (-180.0, 180.0, -90.0, 90.0)  # no statistics: always read
                    else:
                        b = (st["xmin"].min, st["xmax"].max, st["ymin"].min, st["ymax"].max)
                    out.append((i, r) + tuple(float(v) for v in b))
                return out

            with ThreadPoolExecutor(max_workers=8) as ex:
                rows = [r for part in ex.map(footer, range(len(files))) for r in part]
            a = np.array([r[2:] for r in rows], dtype=np.float64)
            fi = np.array([r[0] for r in rows], dtype=np.int32)
            rg = np.array([r[1] for r in rows], dtype=np.int32)
            p.parent.mkdir(parents=True, exist_ok=True)
            tmp = p.with_suffix(".tmp.npz")
            np.savez(tmp, files=np.array(files), fi=fi, rg=rg, xmin=a[:, 0], xmax=a[:, 1], ymin=a[:, 2], ymax=a[:, 3])
            tmp.rename(p)
            log(f"overture {key}: row-group index of {len(files)} files, {len(rows)} groups in {time.time() - t0:.0f}s")
            idx = (files, fi, rg, a[:, 0], a[:, 1], a[:, 2], a[:, 3])
        with self._lock:
            self._indexes[key] = idx
        return idx

    def cell(self, key, cx, cy):
        """All features of [key] whose bbox touches cell (cx, cy) (cell = CELL_DEG degrees)."""
        import pyarrow as pa
        import pyarrow.compute as pc
        import pyarrow.parquet as pq
        p = self._cell_path(key, cx, cy)
        if p.exists():
            return pq.read_table(p)
        xmin, ymin = cx * CELL_DEG, cy * CELL_DEG
        xmax, ymax = xmin + CELL_DEG, ymin + CELL_DEG
        files, fi, rg, gx0, gx1, gy0, gy1 = self._rg_index(key)
        if self._fs is None:
            self._dataset(key)
        hit = (gx0 < xmax) & (gx1 > xmin) & (gy0 < ymax) & (gy1 > ymin)
        cols = _OVERTURE_COLUMNS[key]
        parts = []
        for f_i in np.unique(fi[hit]):
            rgs = [int(r) for r in rg[hit & (fi == f_i)]]
            for attempt in range(4):
                try:
                    with self._fs.open_input_file(files[f_i]) as fh:
                        t = pq.ParquetFile(fh).read_row_groups(rgs, columns=cols + ["bbox"])
                    break
                except Exception as e:  # network hiccups through the proxy happen; retry a few times
                    if attempt == 3:
                        raise
                    log("overture retry", key, cx, cy, e)
                    time.sleep(3 * (attempt + 1))
            bb = t.column("bbox")
            m = pc.and_(pc.and_(pc.less(pc.struct_field(bb, "xmin"), xmax), pc.greater(pc.struct_field(bb, "xmax"), xmin)),
                        pc.and_(pc.less(pc.struct_field(bb, "ymin"), ymax), pc.greater(pc.struct_field(bb, "ymax"), ymin)))
            t = t.filter(m).select(cols)
            if t.num_rows:
                parts.append(t)
        if parts:
            tb = pa.concat_tables(parts, promote_options="default")
        else:
            tb = self._empty_table(key)
        pa.default_memory_pool().release_unused()
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp")
        pq.write_table(tb, tmp)
        tmp.rename(p)
        self.fetched += 1
        return tb

    def _empty_table(self, key):
        sch = self._dataset(key).schema
        return sch.empty_table().select(_OVERTURE_COLUMNS[key])

    def query_cells(self, key, cells, workers=4, area=None):
        """Rows of all cells, deduplicated by id, as a list of dicts. With [area] (a lon/lat
        geometry) only features touching it are kept: a 0.1 degree cell holds far more than a
        route corridor needs, and Python dicts of every building of a stage cost gigabytes."""
        import pyarrow as pa
        cells = sorted(set(cells))
        # The first query reads every file footer; do it alone so the threads share the result.
        missing = [c for c in cells if not self._cell_path(key, *c).exists()]
        t0 = time.time()
        if missing:
            self.cell(key, *missing[0])
        with ThreadPoolExecutor(max_workers=workers) as ex:
            tables = list(ex.map(lambda c: self.cell(key, *c), cells))
        if missing:
            log(f"overture {key}: fetched {len(missing)} of {len(cells)} cells in {time.time() - t0:.0f}s")
        tables = [t for t in tables if t.num_rows]
        if area is not None and tables:
            import shapely
            shapely.prepare(area)
            kept = []
            for t in tables:
                geoms = shapely.from_wkb(t.column("geometry").to_numpy(zero_copy_only=False))
                mask = shapely.intersects(area, geoms)
                del geoms
                if mask.any():
                    kept.append(t.filter(pa.array(mask)))
            tables = kept
        if not tables:
            return []
        tb = pa.concat_tables(tables, promote_options="default")
        seen = set()
        out = []
        for r in tb.to_pylist():
            if r["id"] in seen:
                continue
            seen.add(r["id"])
            out.append(r)
        return out


def cells_for_lonlat_polygon(poly) -> list[tuple[int, int]]:
    """Overture cells touching a shapely polygon given in lon/lat."""
    import shapely
    xmin, ymin, xmax, ymax = poly.bounds
    out = []
    for cx in range(math.floor(xmin / CELL_DEG), math.floor(xmax / CELL_DEG) + 1):
        for cy in range(math.floor(ymin / CELL_DEG), math.floor(ymax / CELL_DEG) + 1):
            box = shapely.box(cx * CELL_DEG, cy * CELL_DEG, (cx + 1) * CELL_DEG, (cy + 1) * CELL_DEG)
            if box.intersects(poly):
                out.append((cx, cy))
    return out


def primary_name(r):
    n = r.get("names") or {}
    return n.get("primary") if isinstance(n, dict) else None


# ---------------------------------------------------------------------------------------------
# Raster sources (cloud-optimised GeoTIFFs over HTTPS), cached per COG block on disk
# ---------------------------------------------------------------------------------------------
def _lat_tag(lat):
    return f"{'N' if lat >= 0 else 'S'}{abs(lat):02d}"


def _lon_tag(lon, width=3):
    return f"{'E' if lon >= 0 else 'W'}{abs(lon):0{width}d}"


class RasterSource:
    """A tiled global raster: one COG per tile_deg x tile_deg degree tile."""

    def __init__(self, name, tile_deg, url_fn, bands, nodata=None):
        self.name = name
        self.tile_deg = tile_deg
        self.url_fn = url_fn
        self.bands = bands
        self.nodata = nodata
        self._local = threading.local()
        self._meta = {}
        self._meta_lock = threading.Lock()

    def tile_of(self, lon, lat):
        d = self.tile_deg
        return int(math.floor(lat / d) * d), int(math.floor(lon / d) * d)

    def _open(self, tile, ovr):
        import rasterio
        cache = getattr(self._local, "h", None)
        if cache is None:
            cache = self._local.h = {}
        k = (tile, ovr)
        if k not in cache:
            url = "/vsicurl/" + self.url_fn(*tile)
            kw = {} if ovr < 0 else {"overview_level": ovr}
            cache[k] = rasterio.open(url, **kw)
        return cache[k]

    def meta(self, tile, ovr):
        """(transform, width, height, block size, dtype) of a tile at overview level ovr (-1 = full)."""
        key = (tile, ovr)
        with self._meta_lock:
            if key in self._meta:
                return self._meta[key]
        mp = CACHE_DIR / "raster" / self.name / f"{tile[0]}_{tile[1]}" / f"meta_ov{ovr}.json"
        if mp.exists():
            m = json.loads(mp.read_text())
        else:
            try:
                ds = self._open(tile, ovr)
                bh, bw = ds.block_shapes[0]
                m = {"transform": list(ds.transform)[:6], "width": ds.width, "height": ds.height,
                     "bw": bw, "bh": bh, "dtype": ds.dtypes[0], "count": ds.count, "missing": False}
            except Exception as e:
                if "404" not in str(e) and "does not exist" not in str(e) and "not recognized" not in str(e):
                    raise
                m = {"missing": True}  # ocean tiles do not exist for the DEM
            mp.parent.mkdir(parents=True, exist_ok=True)
            mp.write_text(json.dumps(m))
        with self._meta_lock:
            self._meta[key] = m
        return m

    def block(self, tile, ovr, bx, by):
        p = CACHE_DIR / "raster" / self.name / f"{tile[0]}_{tile[1]}" / f"ov{ovr}" / f"{bx}_{by}.npy"
        if p.exists():
            return np.load(p, mmap_mode="r")
        m = self.meta(tile, ovr)
        from rasterio.windows import Window
        ds = self._open(tile, ovr)
        w = Window(bx * m["bw"], by * m["bh"], min(m["bw"], m["width"] - bx * m["bw"]),
                   min(m["bh"], m["height"] - by * m["bh"]))
        for attempt in range(4):
            try:
                a = ds.read(indexes=self.bands, window=w)
                break
            except Exception as e:
                if attempt == 3:
                    raise
                log("raster retry", self.name, tile, bx, by, e)
                time.sleep(3 * (attempt + 1))
                self._local.h = {}
                ds = self._open(tile, ovr)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp.npy")
        np.save(tmp, a)
        tmp.rename(p)
        return np.load(p, mmap_mode="r")

    def _nearest_meta(self, ovr, lat, lon):
        """Meta of an existing tile at overview ovr, nearest in latitude first (GLO-30's column
        spacing changes with the latitude band), from memory or the disk cache; None if none."""
        cands = []
        with self._meta_lock:
            cands += [(t, m) for (t, o), m in self._meta.items() if o == ovr and not m.get("missing")]
        if not cands:
            for mp in (CACHE_DIR / "raster" / self.name).glob(f"*/meta_ov{ovr}.json"):
                m = json.loads(mp.read_text())
                if not m.get("missing"):
                    la, lo = (int(v) for v in mp.parent.name.split("_"))
                    cands.append(((la, lo), m))
        if not cands:
            return None
        d = self.tile_deg
        return min(cands, key=lambda c: (abs(c[0][0] + d / 2 - lat), abs(c[0][1] + d / 2 - lon)))[1]

    def read(self, lon0, lat0, lon1, lat1, ovr=-1, workers=8):
        """Mosaic covering the lon/lat box at overview ovr. Returns (array[bands, h, w], lon_left,
        lat_top, dlon, dlat) with pixel (r, c) centred at (lon_left + (c + .5) dlon, lat_top - (r + .5) dlat)."""
        tiles = set()
        d = self.tile_deg
        for la in range(int(math.floor(lat0 / d) * d), int(math.floor(lat1 / d) * d) + 1, d):
            for lo in range(int(math.floor(lon0 / d) * d), int(math.floor(lon1 / d) * d) + 1, d):
                tiles.add((la, lo))
        metas = {t: self.meta(t, ovr) for t in tiles}
        present = [t for t in tiles if not metas[t]["missing"]]
        if present:
            ref = metas[present[0]]
        else:
            # Open sea: the source has no tile anywhere in the box (the coarse world tiles of a
            # coastal stage reach that far out). It reads as nodata on the grid of the nearest tile
            # of the same overview, as the sea part of a partly covered coastal tile does.
            ref = self._nearest_meta(ovr, (lat0 + lat1) / 2, (lon0 + lon1) / 2)
            if ref is None:
                raise RuntimeError(f"{self.name}: no data for box {lon0},{lat0},{lon1},{lat1}")
        dlon, dlat = ref["transform"][0], -ref["transform"][4]
        # Global pixel grid of this source/overview: the tiles share it (grid origin at tile corners).
        org_lon = ref["transform"][2] - round((ref["transform"][2] - (-180.0)) / dlon) * dlon
        org_lat = ref["transform"][5] + round((90.0 - ref["transform"][5]) / dlat) * dlat
        c0 = int(math.floor((lon0 - org_lon) / dlon))
        c1 = int(math.ceil((lon1 - org_lon) / dlon))
        r0 = int(math.floor((org_lat - lat1) / dlat))
        r1 = int(math.ceil((org_lat - lat0) / dlat))
        out = np.full((len(self.bands), r1 - r0, c1 - c0),
                      self.nodata if self.nodata is not None else 0,
                      dtype=np.dtype(ref["dtype"]))
        jobs = []
        for t in present:
            m = metas[t]
            tc0 = int(round((m["transform"][2] - org_lon) / dlon))
            tr0 = int(round((org_lat - m["transform"][5]) / dlat))
            # Part of this tile inside the request, in tile pixel coordinates.
            a0, a1 = max(c0, tc0) - tc0, min(c1, tc0 + m["width"]) - tc0
            b0, b1 = max(r0, tr0) - tr0, min(r1, tr0 + m["height"]) - tr0
            if a0 >= a1 or b0 >= b1:
                continue
            for by in range(b0 // m["bh"], (b1 - 1) // m["bh"] + 1):
                for bx in range(a0 // m["bw"], (a1 - 1) // m["bw"] + 1):
                    jobs.append((t, m, tc0, tr0, bx, by))

        def run(job):
            t, m, tc0, tr0, bx, by = job
            blk = self.block(t, ovr, bx, by)
            gx, gy = tc0 + bx * m["bw"], tr0 + by * m["bh"]
            x0, y0 = max(gx, c0), max(gy, r0)
            x1, y1 = min(gx + blk.shape[2], c1), min(gy + blk.shape[1], r1)
            if x0 < x1 and y0 < y1:
                out[:, y0 - r0:y1 - r0, x0 - c0:x1 - c0] = blk[:, y0 - gy:y1 - gy, x0 - gx:x1 - gx]

        with ThreadPoolExecutor(max_workers=workers) as ex:
            list(ex.map(run, jobs))
        return out, org_lon + c0 * dlon, org_lat - r0 * dlat, dlon, dlat


DEM = RasterSource(
    "cop30", 1,
    lambda la, lo: (f"https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_{_lat_tag(la)}_00_{_lon_tag(lo)}_00_DEM/"
                    f"Copernicus_DSM_COG_10_{_lat_tag(la)}_00_{_lon_tag(lo)}_00_DEM.tif"),
    bands=[1], nodata=0.0)

WORLDCOVER = RasterSource(
    "worldcover", 3,
    lambda la, lo: (f"https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/"
                    f"ESA_WorldCover_10m_2021_v200_{_lat_tag(la)}{_lon_tag(lo)}_Map.tif"),
    bands=[1], nodata=0)

S2RGB = RasterSource(
    "s2rgb", 1,
    lambda la, lo: (f"https://esa-worldcover-s2.s3.eu-central-1.amazonaws.com/rgbnir/2021/{_lat_tag(la)}/"
                    f"ESA_WorldCover_10m_2021_v200_{_lat_tag(la)}{_lon_tag(lo)}_S2RGBNIR.tif"),
    bands=[1, 2, 3], nodata=0)


def sample_grid(arr, lon_left, lat_top, dlon, dlat, lon, lat, order=1):
    """Samples a mosaic from RasterSource.read at lon/lat arrays (bilinear by default)."""
    from scipy.ndimage import map_coordinates
    c = (np.asarray(lon) - lon_left) / dlon - 0.5
    r = (lat_top - np.asarray(lat)) / dlat - 0.5
    shape = np.shape(lon)
    coords = np.vstack([r.ravel(), c.ravel()])
    if arr.ndim == 2:
        return map_coordinates(arr, coords, order=order, mode="nearest").reshape(shape)
    return np.stack([map_coordinates(b, coords, order=order, mode="nearest").reshape(shape) for b in arr])


def stable_hash(*parts) -> str:
    return hashlib.sha1(json.dumps(parts, sort_keys=True, default=str).encode()).hexdigest()[:16]


def gdal_env():
    import rasterio
    return rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif",
                        GDAL_HTTP_MULTIRANGE="YES", GDAL_HTTP_MERGE_CONSECUTIVE_RANGES="YES",
                        GDAL_HTTP_MAX_RETRY="4", GDAL_HTTP_RETRY_DELAY="2", GDAL_CACHEMAX=512)
