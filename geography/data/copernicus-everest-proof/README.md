# Copernicus GLO-30: bounded Everest proof

This separate optional pack contains **180 × 180 real, native one-arcsecond samples** around Mount Everest. It is not merged into the NOAA baseline. The origin is 27.9881° N, 86.925° E; sample bounds are 86.9055555556°–86.9552777778° E and 27.9502777778°–28° N.

The actual imported range is **5927.337891 to 8737.788086 meters**. Bilinear elevation at the chosen Everest landmark is **8720.444141 m**. No elevation was forced to a surveyed summit height. One-meter game blocks interpolate these approximately 30 m posts, not measured one-meter terrain.

## Source, identity and datum

- [AWS Open Data registry](https://registry.opendata.aws/copernicus-dem/), mirrored Copernicus DEM 2021 release.
- [AWS source and COG processing documentation](https://copernicus-dem-30m.s3.amazonaws.com/readme.html).
- [Original one-degree COG](https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N27_00_E086_00_DEM/Copernicus_DSM_COG_10_N27_00_E086_00_DEM.tif), object ETag `37c621f4f1b33740ea8f5997922a3f03`.
- [Original tile metadata XML](https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N27_00_E086_00_DEM/Copernicus_DSM_10_N27_00_E086_00.xml), tile version 02. The tile's citation date is 2020-11-11; do not interpret “2021 release” as a 2021 observation date for every pixel.
- [Official Copernicus product specifications](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).

The original TIFF metadata explicitly verifies 3600 × 3600 scalar float32, WGS84/EPSG:4326, one-arcsecond spacing, and **PixelIsPoint**. Its tiepoint at original index (0, 0) is exactly 86° E, 28° N. That coordinate is a sample center, so no half-cell shift is applied. This differs from the pixel-edge GeoTIFF exports in the NOAA proof.

The original per-tile XML explicitly supplies **EGM2008 geoid / EPSG:3855 and meter units**. The mirrored TIFF itself omits a vertical CRS tag. The importer verifies both evidence sources and performs no vertical datum transformation.

Copernicus is a **digital surface model (DSM)**, which can contain buildings, infrastructure and vegetation. It is not universally bare-earth elevation. The AWS 2021 mirror has limited land coverage and omits ocean-only tiles; this pack does not contain bathymetry or a water mask. Do not infer one-meter accuracy or complete worldwide coverage from this proof.

## Why this is below the surveyed Everest summit

The supplied tile XML independently states `maxValue=8737.78808594` meters, matching the downloaded block's decoded maximum to float32 precision. That peak is at original column 3332, row 39, or 86.9255555556° E, 27.9891666667° N. It lies inside the imported window.

The complete valid part of the downloaded source block, not just the crop, was checked. Two independent decoders agree for every stored sample: Pillow/libtiff, and a separate DEFLATE + floating-point predictor-3 byte reconstruction implemented with Python/NumPy. The XML maximum, source georeferencing, crop bounds, and decoder cross-check all support the lower value being part of this source product. It is not corrected upward to 8849 m. A source product cannot be assumed to preserve exact mountain summits.

## Bounded range import and reproducibility

The remote COG is 43,039,634 bytes, but it was **not downloaded in full**. Only the first 16,384 bytes of header and compressed native block 3 (1,260,299 bytes) were retrieved. Total raster range payload: **1,276,683 bytes**. The separate provenance/license documents are also small and pinned.

HTTP 206, exact Content-Range, object size, ETag, source hashes, encoding, CRS, tiepoint, pixel spacing, and block length are checked. There is a 2 MiB cap per range and no whole-file fallback. Block 3 starts at original column 3072, row 0. It stores 1024 × 1024 samples, but only the first 528 columns are inside the original raster; padding is excluded from statistics and from the crop.

The pack selects original columns **3260 through 3439** and rows **0 through 179**, inclusive. `import-report.json` preserves the exact indices, whole-valid-block statistics, crop statistics, peak coordinates, metadata checks, and landmark interpolation result. `source-header.bin` and `source-block-3.bin` are unchanged source byte ranges, not standalone TIFFs. `everest.egp` follows the same big-endian EGP1 contract as the NOAA proof.

From the repository root:

```sh
python geography/tools/import_copernicus_everest.py
python geography/tools/test_copernicus_import.py
(cd geography/data/copernicus-everest-proof && sha256sum -c SHA256SUMS)
```

Default rebuilding is fully offline and byte-identical. Tests cover two-decoder agreement, source-index mapping, PixelIsPoint handling, corruption, bounded requests, refused whole-file responses, source identity, and license evidence. The world manifest pins `surface-fill-v1`, `geography-palette-v1`, `bilinear-v1` and a local equirectangular origin at Everest.

A new directory may be initialized with `--initialize --output /tmp/new-copernicus-proof`. Existing source locks are never replaced. The current importer is intentionally tied to the reviewed source object and byte hashes; it fails if the upstream object changes rather than silently updating a world's terrain.

## Required license and distribution notices

This is a local proof, not a public release. The data are supplied under the **Copernicus WorldDEM-30 free licence**, not CC0. The original tile's full `provenance/source-eula.pdf` and the [official licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf), cached as `provenance/License-COPDEM-30.pdf`, are included and pinned. Read and retain these when reusing the data.

Source attribution:

© DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved.

This cropped/reformatted pack is modified data. Required modified-data notice:

produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved.

Required liability notice:

The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30.

This project is not endorsed by the data provider, licensor, European Union, ESA, or other Copernicus organizations. Any later redistribution or public display must retain the applicable attribution, modification and liability notices and ensure subsequent users are bound by the licence's obligations. The full licence governs; this summary is not a replacement or a new licence.
