#!/usr/bin/env python3
"""
Turns a CC0 HDRI (Poly Haven, equirectangular .hdr) into the sky texture of the 3D world:
app/src/main/assets/sky/<name>.webp, the upper hemisphere only (the landscape below comes from the
geodata), as sRGB 8 bit of linear radiance / MAX, plus <name>.json with the sun's u and the scale.

The radiance is scaled so the sky between 10 and 60 degrees up has the mean brightness of the
renderer's analytic sky; values above MAX (the sun) are clipped: the renderer draws its own sun where
the route's real sun is, and turns the panorama so its bright side lies there.

Usage: sky.py IN.hdr OUT_DIR NAME
"""
import json
import sys

import cv2
import numpy as np

MAX = 3.0
TARGET = 0.32  # mean linear luminance of the analytic sky 10..60 degrees up

hdr = cv2.imread(sys.argv[1], cv2.IMREAD_UNCHANGED)[:, :, ::-1].astype(np.float32)  # BGR -> RGB
h, w, _ = hdr.shape
up = hdr[: h // 2]
lum = up @ np.array([0.2126, 0.7152, 0.0722], np.float32)
# Rows: 0 = zenith, h/2 = horizon; elevation = 90 * (1 - row / (h/2)).
elev = 90.0 * (1.0 - (np.arange(h // 2) + 0.5) / (h // 2))
band = (elev > 10) & (elev < 60)
scale = TARGET / float(np.mean(lum[band]))
sun_u = (float(np.argmax(lum.max(axis=0))) + 0.5) / w
out = np.clip(up * scale / MAX, 0.0, 1.0)
srgb = np.where(out <= 0.0031308, out * 12.92, 1.055 * np.power(out, 1 / 2.4) - 0.055)
img = (srgb * 255 + 0.5).astype(np.uint8)[:, :, ::-1]
cv2.imwrite(f"{sys.argv[2]}/{sys.argv[3]}.webp", img, [cv2.IMWRITE_WEBP_QUALITY, 90])
json.dump({"sunU": round(sun_u, 4), "max": MAX, "source": sys.argv[1].split("/")[-1]}, open(f"{sys.argv[2]}/{sys.argv[3]}.json", "w"))
print(sys.argv[3], img.shape, "scale", scale, "sunU", sun_u)
