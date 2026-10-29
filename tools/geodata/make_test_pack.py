#!/usr/bin/env python3
"""Writes the small fixture pack the Kotlin reader test reads (core/src/test/resources/geo/fixture.wwgeo).
Every value in it is synthetic and known, so the test checks the Python writer and the Kotlin reader
against each other."""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import pack as P  # noqa: E402
from geo_common import REPO_DIR  # noqa: E402

OUT = REPO_DIR / "core/src/test/resources/geo/fixture.wwgeo"


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    w = P.PackWriter(OUT)
    # Path: straight north from (100, 200), 1000 m, climbing 5 %, bridge from 300 to 400 m.
    n = 201
    s = np.arange(n) * 5.0
    x = np.full(n, 100.0)
    y = 200.0 + s
    z = 500.0 + 0.05 * s
    width = np.full(n, 6.5)
    flags = np.where((s >= 300) & (s <= 400), 1, 0).astype(np.uint8)
    cls = np.full(n, 4, np.uint8)
    w.add(P.T_PATH, P.enc_path(x, y, z, width, flags, cls, 5.0, 1000.0))
    # Corridor: one section, 2 offsets; left = -0.15 then +1.0 m, right = -0.15 then -2.0 m; free 2 / 1.
    left = np.tile([-0.15, 1.0], (n, 1))
    right = np.tile([-0.15, -2.0], (n, 1))
    w.add(P.T_CORRIDOR, P.enc_corridor(0, left, right, np.full(n, 2), np.full(n, 1), [0.0, 5.0]), 0, 0, 0)
    # Heights: h = 400 + 0.5 * col + 0.25 * row (row 0 = north) on LOD0 tiles (0,0) and (0,1).
    c, r = np.meshgrid(np.arange(129), np.arange(129))
    for ty in (0, 1):
        w.add(P.T_HEIGHT, P.enc_heights(400 + 0.5 * c + 0.25 * r + 100 * ty), 0, 0, ty)
    w.add(P.T_HEIGHT, P.enc_heights(300 + 2.0 * c, 0.25), 1, 0, 0)
    lc = np.where(np.arange(128)[None, :] < 64, 1, 4).repeat(128, 0).astype(np.uint8)
    w.add(P.T_LANDCOVER, P.enc_u8_raster(lc), 0, 0, 0)
    rgb = np.zeros((16, 16, 3), np.uint8)
    rgb[..., 0] = 200
    w.add(P.T_IMAGE, P.enc_jpeg(rgb, 90), 0, 0, 0, codec=P.C_JPEG)
    # Two buildings, coordinates in decimetres relative to the tile origin (0, 0).
    items = []
    for ring, h, cls_, roof in (([(500, 500), (600, 500), (600, 580), (500, 580)], 95, 1, 2),
                                ([(1000, 2000), (1150, 2000), (1150, 2100)], 120, 15, 0)):
        items.append(struct.pack("<BBBBHHHHfII", cls_, 1, roof, 3, h, 0, 25, 0, 512.5, 0xFFA03020, 0) +
                     P.enc_polyline(np.array(ring)))
    w.add(P.T_BUILDINGS, struct.pack("<If", len(items), 0.1) + b"".join(items), 0, 0, 0)
    road = struct.pack("<BBBB", 7, 4, 55, 0) + P.enc_polyline(np.array([(0, 0), (1000, 0), (1000, 1000)]))
    w.add(P.T_ROADS, struct.pack("<If", 1, 0.1) + road, 0, 0, 0)
    lake = (struct.pack("<BBHf", 0, 1, 2, 480.25) + P.enc_polyline(np.array([(0, 0), (2000, 0), (2000, 2000), (0, 2000)])) +
            P.enc_polyline(np.array([(500, 500), (600, 500), (600, 600)])))
    stream = struct.pack("<BBHf", 1, 5, 1, float("nan")) + P.enc_polyline(np.array([(3000, 3000), (3100, 3200)]))
    w.add(P.T_WATER, struct.pack("<If", 2, 0.1) + lake + stream, 0, 0, 0)
    meta = {
        "format": "wwgeo", "version": 1, "pipeline": 1, "id": "fixture", "lengthM": 1000.0, "officialLengthKm": 1.0,
        "projection": {"type": "tmerc", "lat0": 45.0, "lon0": 6.0},
        "pathStepM": 5.0, "pathBounds": [100, 200, 100, 1200],
        "corridor": {"offsetsM": [0.0, 5.0], "sectionSamples": 2048},
        "lods": [{"lod": 0, "tileM": 1280.0, "grid": 128, "image": 16, "landcover": 128, "reachM": 1200.0, "unitM": 0.1},
                 {"lod": 1, "tileM": 5120.0, "grid": 128, "image": 256, "landcover": 128, "reachM": 7000.0, "unitM": 0.4}],
        "hairpins": [{"s": 500.0, "turnDeg": 170, "z": 525.0}],
        "attribution": ["test"], "someFutureField": {"ignored": True},
    }
    w.add(P.T_META, P.json_bytes(meta))
    w.add(P.T_PLACES, P.json_bytes({"places": [{"name": "Testdorf", "subtype": "locality", "class": "village",
                                                "population": 120, "x": 150.0, "y": 600.0, "s": 400.0, "offset": -50.0}],
                                    "towns": [{"name": "Testdorf", "sIn": 350.0, "sOut": 480.0, "population": 120}]}))
    w.close(45.0, 6.0)
    print(OUT, OUT.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
