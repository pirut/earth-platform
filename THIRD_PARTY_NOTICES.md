# Third-party notices

Earth Platform is licensed LGPL-3.0-only (see LICENSE and COPYING). This file lists the third-party data, documents and build files in the repository and the mod jar, and the services the mod reads at runtime. Player-facing credits are in [CREDITS.md](CREDITS.md); the licence audit is in [docs/release/LICENSING-AUDIT.md](docs/release/LICENSING-AUDIT.md). The mod jar contains no third-party code: only `io/earthplatform` classes, our own resources and the data listed below.

## Build files

- The Gradle build in `minecraft/` started from the NeoForged MDK template, © 2023 NeoForged project, MIT License; the licence text is in `minecraft/TEMPLATE_LICENSE.txt`.
- `minecraft/gradlew` and `minecraft/gradlew.bat` are the Gradle wrapper scripts, © the original authors (Gradle), Apache License 2.0, as stated in their headers.
- Neither is part of the mod jar, apart from the layout of `neoforge.mods.toml`, which follows the template.

## Distant Horizons

Earth Platform no longer contains a Distant Horizons backend, compiles against no Distant Horizons code and needs no Distant Horizons file to build or test. If a player has Distant Horizons installed, a small client hook reaches its public API by reflection to pause it in Earth worlds. DH is copyright James Seibel and contributors, LGPL-3.0-only. No Voxy source or binary is included or executed.

## Earth test fixture data

Copernicus GLO-30: produced using Copernicus WorldDEM-30 © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA; all rights reserved. The bounded native-resolution crop is repacked without changing source elevations. Exact source object/ranges, original license, datum and metadata: `geography/data/copernicus-everest-proof/source-lock.json`, `world.properties`, and `provenance/`. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30. This project is not endorsed by the European Union, ESA or the Copernicus programme.

ESA WorldCover: © ESA WorldCover project2021 / Contains modified Copernicus Sentinel data(2021) processed by ESA WorldCover consortium. Zanaga, D. et al.(2022), ESA WorldCover10m2021v200: https://doi.org/10.5281/zenodo.7254221. CC BY4.0: https://creativecommons.org/licenses/by/4.0/. Changes are extraction/repacking into ELC1, without changing class codes. Source manifest, evidence and attribution are under `geography/data/worldcover-2021-proof/`.

NOAA ETOPO proof data remains as an existing independent fixture; it is not silently substituted into this Copernicus scene. Its source and dataset notices remain in `geography/data/noaa-etopo2022-proof/`.

AWS Terrain Tiles (streamed at runtime by `geography/.../globe/TerrariumTileSource`, never bundled): https://registry.opendata.aws/terrain-tiles/ . Required attribution, from https://github.com/tilezen/joerd/blob/master/docs/attribution.md : ArcticDEM terrain data DEM(s) were created from DigitalGlobe, Inc., imagery and funded under National Science Foundation awards 1043681, 1559691, and 1542736; Australia terrain data © Commonwealth of Australia (Geoscience Australia) 2017; Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich; Canada terrain data contains information licensed under the Open Government Licence – Canada; Europe terrain data produced using Copernicus data and information funded by the European Union - EU-DEM layers; Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration; Mexico terrain data source: INEGI, Continental relief, 2016; New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information New Zealand and the New Zealand Government (All rights reserved); Norway terrain data © Kartverket; United Kingdom terrain data © Environment Agency copyright and/or database right 2015. All rights reserved; United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data courtesy of the U.S. Geological Survey. The same text is exposed as `TerrariumTileSource.ATTRIBUTION` for in-game display.

NASA Blue Marble Next Generation (bundled as `minecraft/src/main/resources/assets/earthplatform/textures/globe/blue_marble.jpg`, the July 2004 topography and bathymetry image downscaled to 4096x2048 for the spawn picker globe): NASA Earth Observatory, Reto Stöckli, Blue Marble Next Generation, https://visibleearth.nasa.gov/collection/1484/blue-marble . NASA imagery is not subject to copyright in the United States; credited as NASA asks. The globe also draws AWS Terrain Tiles and ESA WorldCover at runtime, with the attributions above.

Regional relief and surface appearance are explicitly procedural heuristics layered on pinned measurements. They are not additional measured elevation, climate, biome or geology data.

Ecoregions: RESOLVE Ecoregions 2017, Dinerstein et al. 2017, An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm, BioScience 67(6):534-545, CC BY 4.0; derived 0.05 degree raster. Bundled as `minecraft/src/main/resources/earthplatform/ecoregions.png` (derived raster) and `ecoregions.tsv` (ecoregion names). https://doi.org/10.1093/biosci/bix014 . Changes: polygons rasterised to 0.05 degree cells.

## Separately installed shader inputs

Shaders are optional. Players install Iris (1.8.14 or a later 1.8 release, LGPL-3.0), Sodium (0.8.12 or a later 0.8 release, PolyForm Shield 1.0.0) and a shader pack such as Photon (Photon Shaders License Agreement) themselves. None of their files, shaders or licences ship in Earth Platform; the mod only compiles against Iris and Sodium and mixes into them at runtime. When the player's driver needs it, the mod adapts the player's own Photon copy on their machine (see the licensing audit); the jar contains no Photon text.

Historical note (older engine): Exact input hashes and the three-classpath/no-copied-payload verification are recorded in review-evidence/shader-packaging015 and shader-aggregate. Users obtain each project from its official distribution and keep the terms accompanying that separate artifact. This candidate’s original code only adapts the pinned installed lifecycle, native format/material context and pass-owned renderer resources. No broader version or pack compatibility is implied.

## Provenance documents kept with the test data

The test areas keep copies of the documents their data came with, as a record of where the data came from and under what terms. They are bundled in the jar under `earthplatform/copernicus_proof_data/provenance/` and `earthplatform/worldcover_proof_data/provenance/` (sources in `geography/data/*/provenance/`). Copyright in each stays with its owner:

- `License-COPDEM-30.pdf`, `source-eula.pdf`, `source-metadata.xml`: the Copernicus WorldDEM-30 licence, end-user licence and product metadata, from the European Space Agency and the Copernicus programme.
- `aws-readme.html`, `aws-registry.html`: copies of the dataset pages on the Registry of Open Data on AWS.
- `WorldCover_PUM_V2.0.pdf`: the ESA WorldCover 2021 Product User Manual, © ESA WorldCover consortium.
- `data-access.html`: a copy of the ESA WorldCover data access page.
- `noaa-metadata.html` (repository only): NOAA ETOPO 2022 metadata, a U.S. government work.

Earth worlds don't read the test data; it is proposed for removal from the release jar.

## EGM96 geoid grid (bundled)

`geography/src/main/resources/io/earthplatform/geography/globe/egm96-south.bin`: EGM96 geoid heights every half degree south of 50° S, sampled from the U.S. National Geospatial-Intelligence Agency's EGM96 15' grid (public domain) as PROJ distributes it (`us_nga_egm96_15.tif`).

## Earth data streamed at runtime (never bundled)

CHELSA V2.1 climatologies 1981-2010 (streamed by `geography/.../globe/ClimateSource` from https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/): Karger, D.N. et al. (2017) Climatologies at high resolution for the earth's land surface areas. Scientific Data 4:170122; Karger, D.N. et al. (2021) EnviDat, https://doi.org/10.16904/envidat.228. CC0 1.0.

ESA WorldCover 10 m 2021 v200 (streamed by `WorldCoverSource` from its public cloud-optimised GeoTIFFs): attribution as for the fixture above, CC BY 4.0; classes are mapped to Minecraft biomes and blocks and built-up classes are replaced by surrounding land cover.

Copernicus DEM GLO-30 (streamed by `geography/.../globe/CopernicusSource` from https://copernicus-dem-30m.s3.amazonaws.com/ by range reads, cached on disk, to fill fjords and datum-offset ground; off with `-Dearthplatform.earth.copernicus=false`): Elevation fill: produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30. Not endorsed by the European Union, ESA or the Copernicus programme.

USGS 3DEP 1/3 arc-second DEM (streamed by `geography/.../globe/UsgsElevation` from https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/ for visited places in the 1,450 cells listed in `3dep-13-v1.txt`, cached on disk; off with `-Dearthplatform.earth.3dep=false`): United States: USGS 3D Elevation Program 1/3 arc-second DEM (public domain), courtesy of the U.S. Geological Survey.

REMA v2.0 mosaics (streamed by `geography/.../globe/RemaSource` from https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/rema/mosaics/v2.0/ only for Antarctic tiles a player visits, cached on disk; off with `-Dearthplatform.earth.rema=false`): Antarctica: Reference Elevation Model of Antarctica (REMA) v2.0, Polar Geospatial Center, University of Minnesota (CC BY 4.0); Howat, I., et al. (2022), The Reference Elevation Model of Antarctica - Mosaics, Version 2, Harvard Dataverse, https://doi.org/10.7910/DVN/EBW8UC. DEMs provided by the Byrd Polar and Climate Research Center and the Polar Geospatial Center under NSF-OPP awards 1043681, 1542736, 1543501, 1559691, 1810976, and 2129685.

JRC Global Surface Water v1.4 occurrence (streamed by `geography/.../globe/SurfaceWaterSource` from https://storage.googleapis.com/global-surface-water/; used to carve river channels): JRC Global Surface Water v1.4 (Source: EC JRC/Google; Pekel et al. 2016, Nature 540, 418-422), Copernicus Programme; Pekel, J.-F. et al. (2016), doi:10.1038/nature20584. Free of charge, without restriction of use. The European Union and the organisations in charge of the Copernicus programme do not incur any liability for any use of these data.

Sentinel-2 cloudless 2016 (streamed by `ImagerySource` from https://tiles.maps.eox.at only when a player turns imagery on; off by default; used only to choose ground blocks): EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016). See the licensing audit.

## Photon adjusted for macOS

A Photon v1.3b copy adjusted for Apple OpenGL was made for testing. It was used for testing only and will not be distributed: the mod is required to work with stock Photon. It is a derivative of Photon (© 2021-2025 Benjamin Stott "SixthSurge", Photon Shaders License Agreement), keeps that licence file, and is not part of any Earth Platform distribution. Its licence forbids redistribution on platforms that pay creators (such as Modrinth and CurseForge) without the author's written permission.
