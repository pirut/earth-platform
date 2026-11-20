#!/usr/bin/env python3
"""Bakes forests.png, the map of what kind of trees make up each place's forest, for ForestTypes.java.

Output, in src/main/resources/earthplatform/:
  forests.png   8640 x 4320 (a 24th of a degree, about 4.6 km), 8-bit grey:
                0             no forest mapped within reach: the generator keeps its climate rules
                16 + 16n + e  n = needleleaf share of the forest, 0 to 14 (ENF + DNF + half of mixed),
                              e = evergreen share of its broadleaf trees, 0 to 15 (EBF / (EBF + DBF))

Input (download it yourself; nothing is fetched while the game runs):
  --forest-type  PROBAV_LC100_global_v3.0.1_2019-nrt_Forest-Type-layer_EPSG-4326.tif, the forest type
                 layer of the Copernicus Global Land Service Land Cover 100 m, collection 3, epoch
                 2019 (Buchhorn et al. 2020, CC BY 4.0), https://doi.org/10.5281/zenodo.3939050
                 Codes: 0 not forest or unknown, 1 evergreen needleleaf, 2 evergreen broadleaf,
                 3 deciduous needleleaf, 4 deciduous broadleaf, 5 mixed.

Cells with less than 4% forest borrow the nearest forested cell within FILL cells, so a wood the
100 m map misses but WorldCover keeps still takes its region's kind of trees.

Needs numpy, pillow and rasterio.
"""
import argparse

import numpy as np
from PIL import Image

PER_DEGREE = 24
FILL = 4
MIN_FOREST = 0.04


def band_counts(job):
    """Counts each forest type code in one band of output rows: (first output row, counts[6, rows, columns])."""
    import rasterio
    from rasterio.windows import Window

    path, top, rows, step = job
    with rasterio.open(path) as src:
        cells_x = src.width // step
        data = src.read(1, window=Window(0, top, cells_x * step, rows))
    blocks = data.reshape(rows // step, step, cells_x, step)
    return top, np.stack([(blocks == code).sum(axis=(1, 3)) for code in range(6)]).astype(np.float32)


def bake(path, out, workers):
    import rasterio
    from multiprocessing import Pool

    width, height = 360 * PER_DEGREE, 180 * PER_DEGREE
    counts = np.zeros((6, height, width), np.float32)
    with rasterio.open(path) as src:
        t = src.transform
        step = round(1 / PER_DEGREE / t.a)
        if abs(step * t.a * PER_DEGREE - 1) > 1e-6:
            raise SystemExit(f"source pixel {t.a} does not divide a {PER_DEGREE}th of a degree")
        col0 = round((t.c + 180) / t.a)
        row0 = round((90 - t.f) / -t.e)
        if col0 % step or row0 % step:
            raise SystemExit("source origin is not on the output grid")
        usable = (src.height // step) * step
    band = step * 16
    jobs = [(path, top, min(band, usable - top), step) for top in range(0, usable, band)]
    with Pool(workers) as pool:
        for done, (top, c) in enumerate(pool.imap_unordered(band_counts, jobs), 1):
            y, x = (row0 + top) // step, col0 // step
            counts[:, y:y + c.shape[1], x:x + c.shape[2]] = c
            print(f"band {done} / {len(jobs)}", flush=True)
    forest = counts[1:].sum(axis=0)
    total = counts.sum(axis=0)
    needle = (counts[1] + counts[3] + 0.5 * counts[5]) / np.maximum(forest, 1)
    broad = counts[2] + counts[4]
    evergreen = np.where(broad > 0, counts[2] / np.maximum(broad, 1), 0.5)
    n = np.clip(np.rint(needle * 14), 0, 14).astype(np.int32)
    e = np.clip(np.rint(evergreen * 15), 0, 15).astype(np.int32)
    grid = np.where((total > 0) & (forest >= MIN_FOREST * total), 16 + 16 * n + e, 0).astype(np.uint8)
    for _ in range(FILL):
        spread = grid.copy()
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            moved = np.roll(grid, (dy, dx), axis=(0, 1))
            spread = np.where(spread == 0, moved, spread)
        grid = spread
    Image.fromarray(grid, "L").save(out, optimize=True)
    changes = (np.diff(grid.astype(np.int16), axis=1) != 0).sum() + height
    print(f"wrote {out}: {(grid > 0).mean() * 100:.1f}% of cells forested, {changes} runs")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--forest-type", required=True)
    parser.add_argument("--out", default="src/main/resources/earthplatform/forests.png")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    bake(args.forest_type, args.out, args.workers)


if __name__ == "__main__":
    main()
