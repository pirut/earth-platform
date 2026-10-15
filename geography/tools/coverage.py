"""Builds the coverage lists that keep the national elevation layers from asking their servers
about places they don't cover.

Each list has one line per zoom-8 Web Mercator tile with any data: its x and y, then 64 hex digits,
a bit per zoom-12 tile inside it (16 by 16, row by row from the north-west, most significant bit
first), set where the source has data in that tile or the tiles around it. Each zoom-8 tile is read
once from the source at 256 pixels, so a zoom-12 tile is 16 by 16 of its pixels.

    python3 -I coverage.py gsi  > ../src/main/resources/io/earthplatform/geography/globe/gsi-dem-v1.txt
    python3 -I coverage.py ign  > ../src/main/resources/io/earthplatform/geography/globe/ign-rgealti-v1.txt
"""
import io, math, sys, time, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

R = 20037508.342789244


def tiles(boxes, zoom=8):
    n = 2 ** zoom
    out = set()
    for west, south, east, north in boxes:
        x0, x1 = int((west + 180) / 360 * n), int((east + 180) / 360 * n)
        y0 = int((1 - math.asinh(math.tan(math.radians(north))) / math.pi) / 2 * n)
        y1 = int((1 - math.asinh(math.tan(math.radians(south))) / math.pi) / 2 * n)
        out |= {(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)}
    return sorted(out)


def get(url, tries=5):
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "earth-platform/coverage"}), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == tries - 1:
                raise
        except OSError:
            if attempt == tries - 1:
                raise
        time.sleep(2 ** attempt)


def gsi(x, y):
    data = get(f"https://cyberjapandata.gsi.go.jp/xyz/dem_png/8/{x}/{y}.png")
    if data is None:
        return None
    rgb = np.asarray(Image.open(io.BytesIO(data)).convert("RGB")).astype(np.int64)
    code = rgb[..., 0] << 16 | rgb[..., 1] << 8 | rgb[..., 2]
    return code != 1 << 23


def ign(x, y):
    s = 2 * R / 256
    box = (-R + x * s, R - (y + 1) * s, -R + (x + 1) * s, R - y * s)
    data = get("https://data.geopf.fr/wms-r/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES"
               "&STYLES=&CRS=EPSG:3857&BBOX=%.3f,%.3f,%.3f,%.3f&WIDTH=256&HEIGHT=256&FORMAT=image/x-bil;bits=32" % box)
    values = np.frombuffer(data, "<f4").reshape(256, 256)
    return values > -1000


SOURCES = {
    # Japan and its outlying islands.
    "gsi": (gsi, [(122, 20, 154, 46)]),
    # Metropolitan France and Corsica, Guadeloupe, Martinique, French Guiana, Reunion, Mayotte, Saint-Pierre-et-Miquelon.
    "ign": (ign, [(-5.8, 41.2, 10.0, 51.3), (-61.9, 15.8, -60.9, 16.6), (-61.3, 14.3, -60.7, 14.95), (-54.7, 2.0, -51.4, 6.0),
                  (55.15, -21.45, 55.9, -20.8), (44.9, -13.1, 45.4, -12.5), (-56.5, 46.7, -56.1, 47.2)]),
}


def main():
    read, boxes = SOURCES[sys.argv[1]]
    candidates = tiles(boxes)
    with ThreadPoolExecutor(3) as pool:
        masks = list(pool.map(lambda t: read(*t), candidates))
    cells = set()
    for (x, y), valid in zip(candidates, masks):
        if valid is None or not valid.any():
            continue
        blocks = valid.reshape(16, 16, 16, 16).any(axis=(1, 3))
        for j, i in zip(*np.nonzero(blocks)):
            cells.add((x * 16 + i, y * 16 + j))
    # Grow by a tile all round: a zoom-8 pixel is 600 m, and small islands and edges can fall between them.
    grown = {(cx + dx, cy + dy) for cx, cy in cells for dx in (-1, 0, 1) for dy in (-1, 0, 1)}
    parents = sorted({(cx >> 4, cy >> 4) for cx, cy in grown})
    for px, py in parents:
        bits = 0
        for j in range(16):
            for i in range(16):
                if (px * 16 + i, py * 16 + j) in grown:
                    bits |= 1 << (255 - (j * 16 + i))
        print(f"{px} {py} {bits:064x}")


if __name__ == "__main__":
    main()
