# NOAA ETOPO 2022: bounded real-terrain proof

These are **actual NOAA elevation and bathymetry samples**, imported on 2026-10-09. They are a small end-to-end proof for Earth Platform, not worldwide installed coverage and not measured one-meter terrain. The three source rasters total **130,457 bytes**. No credentials or paid services were used.

## Sources and reuse

- Product: [NOAA ETOPO 2022 Global Relief Model](https://www.ncei.noaa.gov/products/etopo-global-relief-model)
- Citation: NOAA National Centers for Environmental Information (2022), *ETOPO 2022 15 Arc-Second Global Relief Model*, [DOI 10.25921/fd45-gt74](https://doi.org/10.25921/fd45-gt74). Accessed 2026-10-09.
- Export entry point: [NOAA Grid Extract](https://www.ncei.noaa.gov/maps/grid-extract/)
- Machine-readable source selection: [official datasets.json](https://www.ncei.noaa.gov/maps/grid-extract/js/app/datasets.json), entry `ETOPO_2022 (Ice Surface; 15 arcseconds)`.
- Release/variant: `ETOPO_2022_v1_15s_surface_elev`, ice-surface elevation, not bedrock under ice and not a colored hillshade.
- [NOAA metadata and reuse constraints](https://data.noaa.gov/metaview/page?header=none&view=getDataView&xml=NOAA/NESDIS/NGDC/MGG/DEM//iso/xml/etopo_2022.xml) identify **CC0-1.0** worldwide, geographic WGS84/EPSG:4326, and EGM2008 vertical reference EPSG:3855. The data are **not for navigation**. NOAA does not warrant fitness for other uses.
- [CC0 1.0 Universal Public Domain Dedication](https://creativecommons.org/publicdomain/zero/1.0/).

`provenance/noaa-datasets.json` and `provenance/noaa-metadata.html` preserve the official evidence as retrieved. They are stored as inert reference data; no downloaded JavaScript or HTML is executed. `source-lock.json` pins their SHA-256 values and each exact regional export URL, raw TIFF size/hash, native grid shape, bounding edges, source identity and datum.

Source selection is independently verified, rather than inferred from the endpoint name: `provenance/service-catalog.json` contains exactly one matching raster, OBJECTID 2527; `service-raster-info.json` identifies its single F32 band, native spacing, WGS84 and **latestVcsWkid 3855**. Nine `getSamples` checks across the three windows name that exact raster and match the exported TIFF values. The catalog's human-readable datum field only says “Sea Level”; the raster-info vertical CRS and dataset metadata provide the specific EGM2008 evidence. Exported TIFFs themselves encode horizontal georeferencing but do not carry a vertical CRS. All source-selection evidence and expected requests are pinned and revalidated offline. Changed source IDs, empty selections, fallback raster IDs, incorrect datum, or mismatching sampled values fail closed.

## Imported regions and actual measurements

| Region | Bounds: west, south, east, north | Grid | Minimum m | Maximum m | Raw bytes |
| --- | --- | --- | ---: | ---: | ---: |
| San Francisco coast | -122.6666667, 37.5, -122.1666667, 38.0 | 120 × 120 | -102.502892 | 744.948608 | 67,379 |
| Everest vicinity | 86.8, 27.85, 87.05, 28.1 | 60 × 60 | 4035.320557 | 8585.715820 | 16,437 |
| Challenger Deep vicinity | 142.3, 11.2, 142.8, 11.6 | 120 × 96 | -10581.133789 | -6219.423340 | 46,641 |

All 29,520 source samples are finite in these three extracts. The San Francisco raster contains 6,959 negative and 7,441 positive values, exercising coastal terrain. `import-report.json` holds the complete numeric statistics and landmark interpolation values.

Important: the Everest extract peaks at 8,585.7 m and the Challenger Deep extract bottoms at -10,581.1 m. They do **not** reproduce the surveyed +8,849 m summit or approximately -11,000 m trench extrema. Coarse cells and mixed source data lose local extrema. Extreme-height engine tests need separate explicit fixtures; they must not be presented as measured NOAA results.

## Resolution, ocean interpretation and coverage

The import preserves the source's **15 arcsecond (1/240 degree)** sample spacing. Grid edges are aligned to that spacing; the extractor is requested at native spacing with nearest-neighbor selection and no rendering function. North-south spacing is roughly 460 m; east-west spacing varies with latitude. Bilinear interpolation into one-meter game blocks does not create new one-meter measurements, improve the source's accuracy, or ensure exact shorelines.

The source is a surface/seafloor DEM, not a measured subsurface voxel dataset. Filling below the surface with stone, applying soil/material layers, adding caves or ores, and selecting vegetation are game-generation decisions. They are not observed geology.

A negative elevation means below the vertical datum, **not necessarily connected ocean**. The current sign-only water-fill rule cannot distinguish inland depressions, disconnected basins, tidal wetlands, lakes, or a proper ocean/coast mask. No water classification mask is included here. Treat water placement and coastal fidelity as provisional until a licensed mask and explicit inland-water rules are added.

These three regional windows leave almost the entire planet uncovered. Out-of-coverage or no-data queries must report missing coverage; never silently substitute zero elevation or invented Earth terrain. There is no live per-block API dependency. Stored bytes permit offline use.

## Files and Java contract

- `world.properties`: default San Francisco projection origin 37.7749°, -122.4194°, standard parallel 37.7749°.
- `world-global.properties`: origin 0°, 0°, standard parallel 0°, with the same regional tiles. Its origin itself is outside imported coverage; it is useful for explicitly addressing the imported landmarks.
- `regions/<region>/raw.tif`: bounded NOAA float32 GeoTIFF, preserved unchanged.
- `regions/<region>/*.egp`: deterministic, uncompressed EGP1 tiles for Java.
- `source-lock.json`: immutable import input/provenance lock.
- `import-report.json`: import statistics, tile hashes, landmark samples and overlap facts.
- `SHA256SUMS`: checksums for all source, provenance and generated payloads listed above.

An EGP1 file is 44 header bytes followed by row-major, north-to-south samples, longitude varying fastest. All numbers are big-endian:

1. 32-bit integer magic `0x45475031` (`EGP1`).
2. 32-bit integer width and height, each at least 2.
3. Float64 west sample-center longitude, north sample-center latitude, positive longitude step and positive latitude step.
4. `width × height` IEEE float32 elevations in meters. NaN denotes missing data.

GeoTIFF bounds are pixel **edges**; EGP1 coordinates are sample **centers**, offset half a cell inward. The importer verifies float32, dimensions, tiepoint, pixel scale, PixelIsArea and EPSG:4326 before conversion. GeoTIFF no-data/sentinel values become NaN; missing samples must not be interpreted as ocean.

San Francisco is deliberately split into west/east tiles with **two identical shared sample columns** (source columns 60 and 61). Tests compare these bytes and interpolated seam values. This is a halo/overlap proof for one east-west seam. It does not imply global tiling, antimeridian handling or polar coverage have been validated. Tile source coordinates, widths and heights are independently included in the properties manifest.

World generation is pinned to `surface-fill-v1`; the default palette identity is `geography-palette-v1`, with 0 air, 1 stone, 2 dirt, 3 grass, 4 water and 5 gravel. An engine adapter with different block-state IDs must bind/pin its own palette identity. Elevation tiles do not themselves contain block IDs.

## Rebuild and verify, entirely offline

From the `earth-platform` repository root, with Python, NumPy and Pillow installed:

```sh
python geography/tools/import_noaa_etopo2022.py
python geography/tools/test_noaa_import.py
(cd geography/data/noaa-etopo2022-proof && sha256sum -c SHA256SUMS)
```

The default operation does not use the network. It checks pinned raw/evidence hashes, regenerates EGP1 tiles/manifests/reports byte-for-byte, and rejects missing or corrupted inputs. The tests stub all network access during an offline rebuild and compare every generated byte. They also exercise TIFF metadata/type validation, hash corruption, missing sources, overlap continuity, little/big-endian interpretation and the download cap.

If a raw TIFF alone is missing, `--download` may retrieve its already-pinned URL. The replacement must match the existing hash. Service changes or a different response fail closed rather than silently change world terrain. Retrieve/download mode never overwrites the lock.

To create a distinct fresh bounded proof directory with new retrieval evidence:

```sh
python geography/tools/import_noaa_etopo2022.py --initialize --output /tmp/fresh-noaa-proof
```

Initialization refuses an existing lock. Each of the three fixed region requests has a hard **4 MiB** response cap and uses a maximum of three concurrent downloads. This is not a downloader for full-resolution global tiles or public map imagery. New data releases, larger coverage, changed datum, or an expanded download budget require a separate reviewed import and new pinning.
