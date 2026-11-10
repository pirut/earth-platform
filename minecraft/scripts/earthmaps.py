#!/usr/bin/env python3
"""Bakes the small world maps that ship in the jar for EarthMaps.java.

Outputs, in src/main/resources/earthplatform/:
  rock.png        7200 x 3600, GLiM rock class (1-16) of each river sub-basin, 0 at sea; islands
                  HydroATLAS leaves out (Hawaii, Reunion) take GLiM's own half-degree grid
  redground.png   3600 x 1800, how red the land is from orbit (0 pale to 255 red)
  soilcarbon.png  1800 x 900, organic carbon in the top 5 cm of soil (0 none to 255 peat)

Inputs (download them yourself; none is fetched while the game runs):
  --basins   BasinATLAS_v10.gdb from HydroATLAS v1.0 (CC BY 4.0)
             https://www.hydrosheds.org/hydroatlas  (its lit_cl_smj field is the GLiM class,
             Hartmann & Moosdorf 2012, that covers most of each level 12 sub-basin)
  --glim     glim_wgs84_0point5deg.txt.asc, GLiM gridded to half a degree (CC BY 3.0)
             https://doi.org/10.1594/PANGAEA.788537
  --marble   NASA Blue Marble: Next Generation as an equirectangular 7200 x 3600 image, e.g. from
             https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap
             &LAYERS=BlueMarble_NextGeneration&FORMAT=image/jpeg&WIDTH=7200&HEIGHT=3600
             &BBOX=-90,-180,90,180&CRS=EPSG:4326&VERSION=1.3.0&STYLES=
  --carbon   soc_0-5cm_mean_5000.tif from ISRIC SoilGrids 2.0 (CC BY 4.0)
             https://files.isric.org/soilgrids/latest/data_aggregated/5000m/soc/

Needs numpy, pillow, fiona and rasterio.
"""
import argparse
import os

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None


def fill(grid, glim):
    """Cells no sub-basin covers take GLiM's half-degree class, or that of a half-degree cell beside it."""
    coarse = np.loadtxt(glim, skiprows=6).astype(np.int32)
    if coarse.shape != (360, 720):
        raise SystemExit("GLiM grid must be 720 x 360")
    coarse = np.where((coarse < 1) | (coarse > 16), 0, coarse).astype(np.uint8)
    spread = coarse.copy()
    for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0), (1, 1), (1, -1), (-1, 1), (-1, -1)):
        moved = np.roll(coarse, (dy, dx), axis=(0, 1))
        spread = np.where(spread == 0, moved, spread)
    fine = np.repeat(np.repeat(spread, 10, axis=0), 10, axis=1)
    return np.where(grid == 0, fine, grid)


def rock(basins, glim, out):
    import fiona
    from rasterio.features import rasterize
    from rasterio.transform import from_origin

    width, height = 7200, 3600
    grid = np.zeros((height, width), np.uint8)
    transform = from_origin(-180, 90, 360 / width, 180 / height)
    with fiona.open(basins, layer="BasinATLAS_v10_lev12") as source:
        batch = []
        for feature in source:
            kind = int(feature["properties"]["lit_cl_smj"] or 0)
            if 1 <= kind <= 16:
                batch.append((feature["geometry"], kind))
            if len(batch) >= 50000:
                rasterize(batch, out=grid, transform=transform, all_touched=False)
                batch = []
        if batch:
            rasterize(batch, out=grid, transform=transform, all_touched=False)
    # Small islands and thin coasts no cell centre falls in: one more pass that fills only empty cells.
    with fiona.open(basins, layer="BasinATLAS_v10_lev12") as source:
        touched = np.zeros_like(grid)
        batch = []
        for feature in source:
            kind = int(feature["properties"]["lit_cl_smj"] or 0)
            if 1 <= kind <= 16 and feature["properties"]["SUB_AREA"] < 400:
                batch.append((feature["geometry"], kind))
            if len(batch) >= 50000:
                rasterize(batch, out=touched, transform=transform, all_touched=True)
                batch = []
        if batch:
            rasterize(batch, out=touched, transform=transform, all_touched=True)
        grid = np.where(grid == 0, touched, grid)
    if glim:
        grid = fill(grid, glim)
    Image.fromarray(grid, "L").save(out, optimize=True)
    return grid


def red(marble, out):
    image = np.asarray(Image.open(marble).convert("RGB")).astype(np.float32)
    if image.shape[:2] != (3600, 7200):
        raise SystemExit("Blue Marble image must be 7200 x 3600")
    r, g, b = image[..., 0], image[..., 1], image[..., 2]
    # Land only, so coasts do not blend with the sea: water is bluest, and nearly black pixels say nothing.
    land = ~((b > r) & (b > g)) & (r + g + b > 30)
    ratio = np.where(land, r / np.maximum(g, 1), np.nan)
    cells = ratio.reshape(1800, 2, 3600, 2).transpose(0, 2, 1, 3).reshape(1800, 3600, 4)
    with np.errstate(all="ignore"):
        mean = np.nanmean(cells, axis=2)
    # Red over green: about 1.05 for pale sand, 1.15 for tan desert, 1.65 and more for Australia's red centre.
    redness = np.clip((np.nan_to_num(mean, nan=1.0) - 1.15) / (1.65 - 1.15), 0, 1)
    # Sixteen steps keep the file small; the game blends between cells.
    steps = np.round(redness * 15).astype(np.uint8) * 17
    Image.fromarray(steps, "L").save(out, optimize=True)
    return steps


def carbon(soc, out):
    import rasterio
    from rasterio.transform import from_origin
    from rasterio.warp import Resampling, reproject

    with rasterio.open(soc) as source:
        grid = np.full((900, 1800), -32768, np.int16)
        reproject(rasterio.band(source, 1), grid, dst_transform=from_origin(-180, 90, 0.2, 0.2), dst_crs="EPSG:4326",
                  resampling=Resampling.average, src_nodata=-32768, dst_nodata=-32768)
    # Decigrams per kilogram: 400 in an ordinary forest soil, 2,400 and more in peat and boreal forest floors.
    share = np.clip(np.where(grid < 0, 0, grid) / 2400.0, 0, 1)
    steps = np.round(share * 15).astype(np.uint8) * 17
    Image.fromarray(steps, "L").save(out, optimize=True)
    return steps


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--basins")
    parser.add_argument("--glim")
    parser.add_argument("--marble")
    parser.add_argument("--carbon")
    parser.add_argument("--out", default=os.path.join(here, "..", "src", "main", "resources", "earthplatform"))
    args = parser.parse_args()
    if args.basins:
        rock(args.basins, args.glim, os.path.join(args.out, "rock.png"))
    if args.marble:
        red(args.marble, os.path.join(args.out, "redground.png"))
    if args.carbon:
        carbon(args.carbon, os.path.join(args.out, "soilcarbon.png"))


if __name__ == "__main__":
    main()
