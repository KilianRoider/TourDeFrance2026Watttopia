"""Writer for the .wwgeo route pack (see FORMAT.md). Little-endian throughout."""
from __future__ import annotations

import io
import json
import struct
import zlib

import numpy as np

MAGIC = b"WWGEO\x00\x00\x01"
VERSION = 1

# Chunk types
T_META, T_PATH, T_PLACES, T_CORRIDOR = 1, 2, 3, 4
T_HEIGHT, T_LANDCOVER, T_IMAGE = 10, 11, 12
T_BUILDINGS, T_ROADS, T_WATER = 20, 21, 22

# Codecs
C_RAW, C_DEFLATE, C_JPEG, C_PNG, C_WEBP = 0, 1, 2, 3, 4

HEADER_SIZE = 64
DIR_ENTRY = struct.Struct("<HBBiiQIII")  # type, lod, codec, tx, ty, offset, length, rawLength, crc32 = 32 bytes
assert DIR_ENTRY.size == 32


class PackWriter:
    def __init__(self, path):
        self.f = open(path, "wb")
        self.f.write(b"\0" * HEADER_SIZE)
        self.entries = []
        self.sizes = {}

    def add(self, typ, payload: bytes, lod=0, tx=0, ty=0, codec=C_DEFLATE):
        raw_len = len(payload)
        data = zlib.compress(payload, 9) if codec == C_DEFLATE else payload
        off = self.f.tell()
        self.f.write(data)
        self.entries.append((typ, lod, codec, tx, ty, off, len(data), raw_len, zlib.crc32(data) & 0xFFFFFFFF))
        k = (typ, lod)
        self.sizes[k] = self.sizes.get(k, 0) + len(data)

    def close(self, lat0, lon0):
        # Sorted directory: readers may binary-search by (type, lod, tx, ty).
        self.entries.sort(key=lambda e: (e[0], e[1], e[3], e[4]))
        dir_off = self.f.tell()
        for e in self.entries:
            self.f.write(DIR_ENTRY.pack(*e))
        dir_len = self.f.tell() - dir_off
        hdr = struct.pack("<8sHHIQIIdd", MAGIC, VERSION, 0, len(self.entries), dir_off, dir_len, 0, lat0, lon0)
        hdr += b"\0" * (HEADER_SIZE - len(hdr))
        self.f.seek(0)
        self.f.write(hdr)
        self.f.close()


def enc_heights(h: np.ndarray, step=0.1) -> bytes:
    """(n, n) float heights, row 0 = north. u16 quantised, Paeth-style residuals (left + up - upleft)."""
    rows, cols = h.shape
    base = float(np.floor(np.nanmin(h) / step) * step) - step
    q = np.round((h - base) / step).astype(np.int64)
    if q.max() > 65535:
        raise ValueError("height range too large for u16 at this step")
    pred = np.zeros_like(q)
    pred[0, 1:] = q[0, :-1]
    pred[1:, 0] = q[:-1, 0]
    pred[1:, 1:] = q[1:, :-1] + q[:-1, 1:] - q[:-1, :-1]
    res = ((q - pred) & 0xFFFF).astype("<u2")
    return struct.pack("<HHff", cols, rows, base, step) + res.tobytes()


def enc_u8_raster(a: np.ndarray) -> bytes:
    rows, cols = a.shape
    return struct.pack("<HH", cols, rows) + a.astype(np.uint8).tobytes()


def enc_jpeg(rgb: np.ndarray, quality=82) -> bytes:
    from PIL import Image
    b = io.BytesIO()
    Image.fromarray(rgb, "RGB").save(b, "JPEG", quality=quality, optimize=True, progressive=False, subsampling=2)
    return b.getvalue()


def zigzag_deltas(v: np.ndarray, dtype="<i2"):
    d = np.diff(np.concatenate([[0], v.astype(np.int64)]))
    if dtype == "<i2" and (d.max(initial=0) > 32767 or d.min(initial=0) < -32768):
        raise ValueError("delta out of range")
    return d.astype(dtype).tobytes()


def enc_path(x, y, z, width, flags, cls, step, length):
    """Path samples every [step] m: x, y in cm, z in mm as int32 first value + int16 deltas."""
    n = len(x)
    xi = np.round(np.asarray(x) * 100).astype(np.int64)
    yi = np.round(np.asarray(y) * 100).astype(np.int64)
    zi = np.round(np.asarray(z) * 1000).astype(np.int64)
    out = struct.pack("<Ifd", n, step, length)
    out += struct.pack("<iii", int(xi[0]), int(yi[0]), int(zi[0]))
    for v in (xi, yi, zi):
        d = np.diff(v)
        if d.max(initial=0) > 32767 or d.min(initial=0) < -32768:
            raise ValueError("path delta out of int16 range (step too large?)")
        out += d.astype("<i2").tobytes()
    out += np.clip(np.round(np.asarray(width) * 10), 0, 255).astype(np.uint8).tobytes()
    out += np.asarray(flags, dtype=np.uint8).tobytes()
    out += np.asarray(cls, dtype=np.uint8).tobytes()
    return out


def enc_polyline(pts_dm: np.ndarray) -> bytes:
    """Vertices in decimetres relative to the tile origin: count, first absolute, then deltas (int16)."""
    p = np.asarray(pts_dm, dtype=np.int64)
    d = np.diff(p, axis=0)
    if (np.abs(p[0]) > 32767).any() or (d.size and np.abs(d).max() > 32767):
        raise ValueError("polyline out of range")
    return struct.pack("<H", len(p)) + p[0].astype("<i2").tobytes() + d.astype("<i2").tobytes()


def enc_corridor(first, left, right, free_l, free_r, offsets):
    """Cross-sections of samples first .. first + n - 1: heights relative to the road in dm."""
    n, k = left.shape
    out = struct.pack("<IIB", first, n, k) + np.asarray(offsets, "<f4").tobytes()
    rec = np.zeros((n, 2 + 4 * k), np.uint8)
    rec[:, 0] = free_l
    rec[:, 1] = free_r
    rec[:, 2:2 + 2 * k] = np.clip(np.round(left * 10), -32768, 32767).astype("<i2").view(np.uint8).reshape(n, 2 * k)
    rec[:, 2 + 2 * k:] = np.clip(np.round(right * 10), -32768, 32767).astype("<i2").view(np.uint8).reshape(n, 2 * k)
    return out + rec.tobytes()


def json_bytes(obj) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
