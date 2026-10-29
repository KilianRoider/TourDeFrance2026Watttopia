"""Canopy height along a route, to take the forest out of the surface model.

GLO-30 is a radar surface model: where a road runs through a wood it reads part of the canopy, so
a road profile taken from it climbs 5-15 m into every wood and drops out of it again. On a rolling
stage those steps become short 8-12 % "ramps" that do not exist. The Meta/WRI canopy height map
(1 m, from Maxar imagery calibrated with GEDI lidar) says how tall the trees beside the road are;
profile.py lowers each observation by a fixed share of the mean canopy height across the road.

Source: s3://dataforgood-fb-data/forests/v1/alsgedi_global_v6_float/chm/<quadkey>.tif, zoom-9 Web
Mercator tiles of 65536 x 65536 uint8 metres, stored as one deflated strip per row (no overviews),
CC BY 4.0. A window read costs whole rows, so the map is fetched in squares of 256 x 256 cells of
8 x 8 source pixels (~9.6 m, ~2.4 km per square), cached on disk; squares are read row by row so
GDAL's strip cache serves the neighbours.
"""
from __future__ import annotations

import math
import threading
import time

import numpy as np

from geo_common import CACHE_DIR, log

URL = "https://dataforgood-fb-data.s3.amazonaws.com/forests/v1/alsgedi_global_v6_float/chm/{}.tif"
ATTRIBUTION = ("Canopy height: Meta and World Resources Institute (WRI) 2024, High Resolution Canopy Height Maps, "
               "CC BY 4.0")
_R = 6378137.0
_ORIG = math.pi * _R
_ZOOM = 9
_TILE_PX = 65536
_PIX = 2 * _ORIG / 2 ** _ZOOM / _TILE_PX
_F = 8  # source pixels per cell
CELL = _PIX * _F
_SQ = 256  # cells per cached square
_SQ_PER_TILE = _TILE_PX // _F // _SQ
_lock = threading.Lock()
_transformer = None


def to_mercator(lon, lat):
    global _transformer
    if _transformer is None:
        from pyproj import Transformer
        _transformer = Transformer.from_crs(4326, 3857, always_xy=True)
    return _transformer.transform(lon, lat)


def _quadkey(tx, ty):
    return "".join(str(((tx >> i) & 1) + 2 * ((ty >> i) & 1)) for i in range(_ZOOM - 1, -1, -1))


def _square_path(sx, sy):
    tx, ty = sx // _SQ_PER_TILE, sy // _SQ_PER_TILE
    return CACHE_DIR / "raster" / "chm" / _quadkey(tx, ty) / f"{sy % _SQ_PER_TILE}_{sx % _SQ_PER_TILE}.npy"


def _fetch(squares):
    """Reads missing squares [(sx, sy)] from the tiles and caches them (0 = no trees / no tile)."""
    import rasterio
    from rasterio.enums import Resampling
    from rasterio.windows import Window
    by_tile: dict = {}
    for sx, sy in squares:
        by_tile.setdefault((sx // _SQ_PER_TILE, sy // _SQ_PER_TILE), []).append((sx, sy))
    env = rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif",
                       GDAL_HTTP_MERGE_CONSECUTIVE_RANGES="YES", GDAL_CACHEMAX=192, GDAL_HTTP_MAX_RETRY=4,
                       GDAL_HTTP_RETRY_DELAY=3)
    with env:
        for (tx, ty), sqs in sorted(by_tile.items()):
            try:
                ds = rasterio.open("/vsicurl/" + URL.format(_quadkey(tx, ty)))
            except Exception as e:  # sea tiles do not exist
                if not any(k in str(e) for k in ("404", "not exist", "not recognized", "No such file")):
                    raise
                ds = None
            for sx, sy in sorted(sqs, key=lambda q: (q[1], q[0])):
                p = _square_path(sx, sy)
                if ds is None:
                    a = np.zeros((_SQ, _SQ), np.uint8)
                else:
                    c0 = (sx % _SQ_PER_TILE) * _SQ * _F
                    r0 = (sy % _SQ_PER_TILE) * _SQ * _F
                    for attempt in range(5):
                        try:
                            a = ds.read(1, window=Window(c0, r0, _SQ * _F, _SQ * _F), out_shape=(_SQ, _SQ),
                                        resampling=Resampling.average)
                            break
                        except Exception as e:  # a range response cut short ("got 7617 bytes, expected 14202")
                            if attempt == 4:
                                raise
                            log(f"canopy: retry {_quadkey(tx, ty)} {sx},{sy}: {str(e)[:80]}")
                            time.sleep(3 * (attempt + 1))
                            ds.close()
                            ds = rasterio.open("/vsicurl/" + URL.format(_quadkey(tx, ty)))
                    a = np.clip(np.round(a), 0, 255).astype(np.uint8)
                p.parent.mkdir(parents=True, exist_ok=True)
                tmp = p.with_suffix(".tmp.npy")
                np.save(tmp, a)
                tmp.rename(p)
            if ds is not None:
                ds.close()


def _cells(X, Y):
    return (np.floor((np.asarray(X) + _ORIG) / CELL).astype(np.int64),
            np.floor((_ORIG - np.asarray(Y)) / CELL).astype(np.int64))


def sample(lon, lat, box=3, chunk=1500):
    """Canopy height (m) at lon/lat arrays of shape (n, k), the mean over box x box cells (~29 m, the
    footprint of a DEM pixel) interpolated bilinearly. Only the squares under the points are fetched
    (a diagonal stage has a large but mostly empty bounding box)."""
    from scipy.ndimage import map_coordinates, uniform_filter
    lon = np.asarray(lon, float)
    lat = np.asarray(lat, float)
    out = np.zeros(lon.shape, np.float32)
    for i in range(0, lon.shape[0], chunk):
        X, Y = to_mercator(lon[i:i + chunk], lat[i:i + chunk])
        cx, cy = _cells(X, Y)
        sq = set()
        for dx in (-2, 2):
            for dy in (-2, 2):
                sq.update(zip(((cx + dx) // _SQ).ravel().tolist(), ((cy + dy) // _SQ).ravel().tolist()))
        with _lock:
            missing = [q for q in sq if not _square_path(*q).exists()]
            if missing:
                log(f"canopy: fetching {len(missing)} squares")
                _fetch(missing)
        sx0 = min(q[0] for q in sq)
        sy0 = min(q[1] for q in sq)
        sx1 = max(q[0] for q in sq)
        sy1 = max(q[1] for q in sq)
        g = np.zeros(((sy1 - sy0 + 1) * _SQ, (sx1 - sx0 + 1) * _SQ), np.float32)
        for sx, sy in sq:
            g[(sy - sy0) * _SQ:(sy - sy0 + 1) * _SQ, (sx - sx0) * _SQ:(sx - sx0 + 1) * _SQ] = np.load(_square_path(sx, sy))
        if box > 1:
            g = uniform_filter(g, box)
        left = sx0 * _SQ * CELL - _ORIG
        top = _ORIG - sy0 * _SQ * CELL
        out[i:i + chunk] = map_coordinates(g, [(top - Y) / CELL - 0.5, (X - left) / CELL - 0.5], order=1, mode="nearest")
    return out
