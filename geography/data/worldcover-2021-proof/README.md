# ESA WorldCover 2021 v200 bounded proof

Two unchanged native 1024×1024 categorical blocks: Marin Headlands and Everest. See [the integration contract](../../LANDCOVER.md) for access, limitations, exact extents, class counts and reproduction commands.

- `source-lock.json`: reviewed release, source object identity, exact byte ranges, SHA-256, citation, license, and crop selection
- `<region>/source-header.bin` and `source-block.bin`: exact original COG range bytes
- `<region>/landcover.elc`: lossless ELC1 categorical rasters
- `landcover.properties`: bounded local Java loader manifest with source/policy/content identity
- `import-report.json`: two-decoder agreement, class histograms, grid bounds and transfer sizes
- `control-points.tsv`: 12 selected source-cell centers, one per class present per region; not independent ground observations
- `provenance/`: original official data-access page, AWS registry page and product manual; pinned snapshots for this review
- `SHA256SUMS`: all machine-consumed source, provenance and output artifacts

© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium

Zanaga, D. et al. (2022). ESA WorldCover 10 m 2021 v200. https://doi.org/10.5281/zenodo.7254221

Data license: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: extracted two native blocks and repacked into ELC1 without altering class codes. Source rights and attribution apply to the data independently of the Java/Python implementation.

Outside these windows remains unknown. Snow/glacier-shadow pixels may be mislabeled water; neither water surface heights nor a tidal shoreline are established. Land cover is not full biomes, species, structures, climate or geology. Elevation is unchanged.
