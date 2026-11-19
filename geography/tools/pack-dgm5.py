#!/usr/bin/env python3
"""Bundle the Schleswig-Holstein DGM5 Helgoland delivery as elevation tiles.

Download the DGM5 entries for delivery 32420_6000 from the LVermGeo SH
OpenGBD index. Pass the directory of downloaded XYZ files and a target
base path. Missing source tiles must be left out of that directory.
https://geodaten.schleswig-holstein.de/gaialight-sh/_apps/dladownload/dl-dgm5.html
Requires NumPy and pyproj.
"""
import argparse
from collections import defaultdict
import gzip
from pathlib import Path
import struct

import numpy as np
from pyproj import Transformer

ZOOM = 15
SIZE = 256
NODATA = -32768
STEP = 5
EARTH = 6378137 * np.pi
METRES_PER_PIXEL = 2 * EARTH / (SIZE * (1 << ZOOM))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    xyz = np.concatenate([np.loadtxt(path, encoding="utf-8-sig") for path in sorted(args.source.glob("*.xyz"))])
    west, south = xyz.min(axis=0)[:2]
    east, north = xyz.max(axis=0)[:2]
    # Native sample centres are five metres apart on the ETRS89/UTM 32N grid.
    offsets_x = np.rint((xyz[:, 0] - west) / STEP).astype(int)
    offsets_y = np.rint((xyz[:, 1] - south) / STEP).astype(int)
    valid = np.isfinite(xyz[:, 2]) & (xyz[:, 2] > -500)
    native = np.full((offsets_y.max() + 1, offsets_x.max() + 1), np.nan)
    native[offsets_y[valid], offsets_x[valid]] = xyz[valid, 2]

    project = Transformer.from_crs(25832, 3857, always_xy=True)
    inverse = Transformer.from_crs(3857, 25832, always_xy=True)
    bounds_x, bounds_y = project.transform([west, west, east, east], [south, north, south, north])
    span = METRES_PER_PIXEL * SIZE
    first_x, last_x = int((min(bounds_x) + EARTH) // span), int((max(bounds_x) + EARTH) // span)
    first_y, last_y = int((EARTH - max(bounds_y)) // span), int((EARTH - min(bounds_y)) // span)
    offsets = (np.arange(SIZE) + 0.5) * METRES_PER_PIXEL
    tiles = {}
    for ty in range(first_y, last_y + 1):
        for tx in range(first_x, last_x + 1):
            x, y = np.meshgrid(tx * span - EARTH + offsets, EARTH - ty * span - offsets)
            utm_x, utm_y = inverse.transform(x, y)
            fx, fy = (utm_x - west) / STEP, (utm_y - south) / STEP
            col, row = np.floor(fx).astype(int), np.floor(fy).astype(int)
            total, weights = np.zeros_like(x), np.zeros_like(x)
            for j in range(2):
                for i in range(2):
                    c, r = col + i, row + j
                    sample = native[np.clip(r, 0, native.shape[0] - 1), np.clip(c, 0, native.shape[1] - 1)]
                    valid = (c >= 0) & (r >= 0) & (c < native.shape[1]) & (r < native.shape[0]) & np.isfinite(sample)
                    weight = np.where(valid, (1 - np.abs(fx - c)) * (1 - np.abs(fy - r)), 0)
                    total += np.where(valid, sample, 0) * weight
                    weights += weight
            valid = weights > 0.5
            if valid.any():
                metres = np.full(x.shape, NODATA, dtype=">i2")
                metres[valid] = np.rint(10 * total[valid] / weights[valid])
                tiles[tx, ty] = metres.tobytes()

    coverage = defaultdict(int)
    with args.output.with_suffix(".bin.gz").open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as packed:
        packed.write(struct.pack(">I", len(tiles)))
        for (x, y), data in tiles.items():
            packed.write(struct.pack(">HH", x, y))
            packed.write(data)
            cx, cy = x >> (ZOOM - 12), y >> (ZOOM - 12)
            coverage[cx >> 4, cy >> 4] |= 1 << (255 - ((cy & 15) * 16 + (cx & 15)))
    with args.output.with_suffix(".txt").open("w") as index:
        for (x, y), mask in sorted(coverage.items()):
            index.write(f"{x} {y} {mask:064x}\n")


if __name__ == "__main__":
    main()
