# Private sparse engine fixture: source and data notices

Original engine source is a development prototype, with no new public redistribution or stable addon compatibility claim.

## Distant Horizons

The source adapter calls an unchanged, separately obtained official Distant Horizons 3.3.3 JAR. The private runtime copy is kept under `runtime-inputs/`, outside the Minecraft `mods` folder and outside the original source archive. DH is copyright James Seibel and contributors, LGPL-3.0-only. Exact license texts, official artifact/source pins and provenance are in `third_party/distant-horizons/`. Corresponding sources:

- https://gitlab.com/distant-horizons-team/distant-horizons/-/tree/a54dd3eb1df0078e63a71266d042c10d9cda4493
- https://gitlab.com/distant-horizons-team/distant-horizons-core/-/tree/b02c66d778beea11a931291815d652cd8abaa7ad

The original engine's provisional licensing does not cover upstream DH implementation, shaders, binaries or license texts. No Voxy source/binary is included or executed. One isolated official DH runtime/classloader and its upstream daemon buffer recycler are retained for the Minecraft process; all view-owned GL objects must be deleted at session close.

## Earth test fixture data

Copernicus GLO-30: produced using Copernicus WorldDEM-30 © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA; all rights reserved. The bounded native-resolution crop is repacked without changing source elevations. Exact source object/ranges, original license, datum and metadata: `geography/data/copernicus-everest-proof/source-lock.json`, `world.properties`, and `provenance/`.

ESA WorldCover: © ESA WorldCover project2021 / Contains modified Copernicus Sentinel data(2021) processed by ESA WorldCover consortium. Zanaga, D. et al.(2022), ESA WorldCover10m2021v200: https://doi.org/10.5281/zenodo.7254221. CC BY4.0: https://creativecommons.org/licenses/by/4.0/. Changes are extraction/repacking into ELC1, without changing class codes. Source manifest, evidence and attribution are under `geography/data/worldcover-2021-proof/`.

NOAA ETOPO proof data remains as an existing independent fixture; it is not silently substituted into this Copernicus scene. Its source and dataset notices remain in `geography/data/noaa-etopo2022-proof/`.

AWS Terrain Tiles (streamed at runtime by `geography/.../globe/TerrariumTileSource`, never bundled): https://registry.opendata.aws/terrain-tiles/ . Required attribution, from https://github.com/tilezen/joerd/blob/master/docs/attribution.md : ArcticDEM terrain data DEM(s) were created from DigitalGlobe, Inc., imagery and funded under National Science Foundation awards 1043681, 1559691, and 1542736; Australia terrain data © Commonwealth of Australia (Geoscience Australia) 2017; Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich; Canada terrain data contains information licensed under the Open Government Licence – Canada; Europe terrain data produced using Copernicus data and information funded by the European Union - EU-DEM layers; Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration; Mexico terrain data source: INEGI, Continental relief, 2016; New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information New Zealand and the New Zealand Government (All rights reserved); Norway terrain data © Kartverket; United Kingdom terrain data © Environment Agency copyright and/or database right 2015. All rights reserved; United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data courtesy of the U.S. Geological Survey. The same text is exposed as `TerrariumTileSource.ATTRIBUTION` for in-game display.

Regional relief and surface appearance are explicitly procedural heuristics layered on pinned measurements. They are not additional measured elevation, climate, biome or geology data.
