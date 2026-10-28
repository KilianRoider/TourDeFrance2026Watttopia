"""Reference reader for .wwgeo packs (mirrors the Kotlin reader; used by preview.py and checks)."""
from __future__ import annotations

import io
import json
import struct
import zlib

import numpy as np

from pack import (C_DEFLATE, DIR_ENTRY, HEADER_SIZE, MAGIC, T_BUILDINGS, T_HEIGHT, T_IMAGE, T_LANDCOVER, T_META,
                  T_PATH, T_PLACES, T_ROADS, T_WATER)


class Pack:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        magic, ver, flags, count, dir_off, dir_len, _r, lat0, lon0 = struct.unpack_from("<8sHHIQIIdd", self.data, 0)
        assert magic == MAGIC, "not a wwgeo pack"
        self.version = ver
        self.dir = {}
        for i in range(count):
            t, lod, codec, tx, ty, off, ln, raw, crc = DIR_ENTRY.unpack_from(self.data, dir_off + i * DIR_ENTRY.size)
            self.dir[(t, lod, tx, ty)] = (codec, off, ln, raw, crc)
        self.meta = json.loads(self.chunk(T_META))

    def chunk(self, t, lod=0, tx=0, ty=0):
        e = self.dir.get((t, lod, tx, ty))
        if e is None:
            return None
        codec, off, ln, raw, crc = e
        b = self.data[off:off + ln]
        assert zlib.crc32(b) & 0xFFFFFFFF == crc, "crc mismatch"
        return zlib.decompress(b) if codec == C_DEFLATE else b

    def tiles(self, t, lod):
        return sorted((k[2], k[3]) for k in self.dir if k[0] == t and k[1] == lod)

    def path(self):
        b = self.chunk(T_PATH)
        n, step, length = struct.unpack_from("<Ifd", b, 0)
        x0, y0, z0 = struct.unpack_from("<iii", b, 16)
        o = 28
        arrs = []
        for v0 in (x0, y0, z0):
            d = np.frombuffer(b, "<i2", n - 1, o).astype(np.int64)
            o += 2 * (n - 1)
            arrs.append(np.concatenate([[v0], v0 + np.cumsum(d)]))
        width = np.frombuffer(b, np.uint8, n, o) / 10.0
        flags = np.frombuffer(b, np.uint8, n, o + n)
        cls = np.frombuffer(b, np.uint8, n, o + 2 * n)
        s = np.minimum(np.arange(n) * step, length)
        return dict(s=s, x=arrs[0] / 100.0, y=arrs[1] / 100.0, z=arrs[2] / 1000.0, width=width, flags=flags, cls=cls,
                    length=length)

    def heights(self, lod, tx, ty):
        b = self.chunk(T_HEIGHT, lod, tx, ty)
        if b is None:
            return None
        cols, rows, base, step = struct.unpack_from("<HHff", b, 0)
        r = np.frombuffer(b, "<u2", cols * rows, 12).reshape(rows, cols).astype(np.int64)
        q = np.zeros_like(r)
        for i in range(rows):
            for j in range(cols):
                if i == 0 and j == 0:
                    p = 0
                elif i == 0:
                    p = q[0, j - 1]
                elif j == 0:
                    p = q[i - 1, 0]
                else:
                    p = q[i, j - 1] + q[i - 1, j] - q[i - 1, j - 1]
                q[i, j] = (p + r[i, j]) & 0xFFFF
        return base + q * step

    def heights_fast(self, lod, tx, ty):
        """Vectorised decode: residuals are a 2D difference, so the values are a 2D prefix sum (mod 2^16)."""
        b = self.chunk(T_HEIGHT, lod, tx, ty)
        if b is None:
            return None
        cols, rows, base, step = struct.unpack_from("<HHff", b, 0)
        r = np.frombuffer(b, "<u2", cols * rows, 12).reshape(rows, cols).astype(np.int64)
        # q = cumsum over rows and columns of r, except row 0 / col 0 use 1D predictors: same thing.
        q = np.cumsum(np.cumsum(r, axis=0), axis=1) & 0xFFFF
        return base + q * step

    def landcover(self, lod, tx, ty):
        b = self.chunk(T_LANDCOVER, lod, tx, ty)
        if b is None:
            return None
        cols, rows = struct.unpack_from("<HH", b, 0)
        return np.frombuffer(b, np.uint8, cols * rows, 4).reshape(rows, cols)

    def image(self, lod, tx, ty):
        from PIL import Image
        b = self.chunk(T_IMAGE, lod, tx, ty)
        return None if b is None else np.asarray(Image.open(io.BytesIO(b)).convert("RGB"))

    @staticmethod
    def _polyline(b, o):
        n = struct.unpack_from("<H", b, o)[0]
        o += 2
        p0 = np.frombuffer(b, "<i2", 2, o).astype(np.int64)
        o += 4
        d = np.frombuffer(b, "<i2", 2 * (n - 1), o).astype(np.int64).reshape(-1, 2)
        o += 4 * (n - 1)
        return np.vstack([p0, p0 + np.cumsum(d, axis=0)]), o

    def buildings(self, tx, ty):
        b = self.chunk(T_BUILDINGS, 0, tx, ty)
        if b is None:
            return []
        n, unit = struct.unpack_from("<If", b, 0)
        o = 8
        out = []
        org = np.array([tx, ty]) * self.meta["lods"][0]["tileM"]
        for _ in range(n):
            cls, fl, roof, floors, h, mh, rh, _r, base, rc, fc = struct.unpack_from("<BBBBHHHHfII", b, o)
            o += 24
            ring, o = self._polyline(b, o)
            out.append(dict(cls=cls, flags=fl, roof=roof, floors=floors, height=h / 10, min_height=mh / 10,
                            roof_height=rh / 10, base=base, roof_colour=rc, facade_colour=fc, ring=org + ring * unit))
        return out

    def roads(self, tx, ty):
        b = self.chunk(T_ROADS, 0, tx, ty)
        if b is None:
            return []
        n, unit = struct.unpack_from("<If", b, 0)
        o = 8
        out = []
        org = np.array([tx, ty]) * self.meta["lods"][0]["tileM"]
        for _ in range(n):
            cls, fl, w, _r = struct.unpack_from("<BBBB", b, o)
            o += 4
            pts, o = self._polyline(b, o)
            out.append(dict(cls=cls, flags=fl, width=w / 10, pts=org + pts * unit))
        return out

    def water(self, lod, tx, ty):
        b = self.chunk(T_WATER, lod, tx, ty)
        if b is None:
            return []
        n, unit = struct.unpack_from("<If", b, 0)
        o = 8
        out = []
        org = np.array([tx, ty]) * self.meta["lods"][lod]["tileM"]
        for _ in range(n):
            kind, cls, nr, lvl = struct.unpack_from("<BBHf", b, o)
            o += 8
            rings = []
            for _k in range(nr):
                pts, o = self._polyline(b, o)
                rings.append(org + pts * unit)
            out.append(dict(kind=kind, cls=cls, level=lvl, rings=rings))
        return out

    def places(self):
        return json.loads(self.chunk(T_PLACES))
