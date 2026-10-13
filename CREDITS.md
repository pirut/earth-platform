# Credits

Earth Platform builds its world from open data. None of it is bundled in the mod except the small test areas listed at the end: the mod downloads each area while you play and keeps it in `earthplatform/cache` in your game folder. Earth Platform changes these data to make a Minecraft world (resampling, block and biome choices, procedural detail), so the result is not the original data, not a survey, and not for navigation.

The same credits appear in game under Mods → Earth Platform, and as a short line when you arrive on the Earth.

## Elevation: AWS Terrain Tiles

Mapzen/Tilezen terrain tiles on the [Registry of Open Data on AWS](https://registry.opendata.aws/terrain-tiles/). Attribution as required by [Tilezen](https://github.com/tilezen/joerd/blob/master/docs/attribution.md):

- ArcticDEM terrain data DEM(s) were created from DigitalGlobe, Inc., imagery and funded under National Science Foundation awards 1043681, 1559691, and 1542736.
- Australia terrain data © Commonwealth of Australia (Geoscience Australia) 2017.
- Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich.
- Canada terrain data contains information licensed under the Open Government Licence – Canada.
- Europe terrain data produced using Copernicus data and information funded by the European Union - EU-DEM layers.
- Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration.
- Mexico terrain data source: INEGI, Continental relief, 2016.
- New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information New Zealand and the New Zealand Government (All rights reserved).
- Norway terrain data © Kartverket.
- United Kingdom terrain data © Environment Agency copyright and/or database right 2015. All rights reserved.
- United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data courtesy of the U.S. Geological Survey.

## Land cover: ESA WorldCover

© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium.
Zanaga, D., Van De Kerchove, R., Daems, D., et al. (2022). ESA WorldCover 10 m 2021 v200. https://doi.org/10.5281/zenodo.7254221
Licence: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: land cover classes are mapped to Minecraft biomes and blocks; built-up classes are replaced by the surrounding wild land.

## Climate: CHELSA

CHELSA V2.1 climatologies, 1981-2010 (Köppen-Geiger zones, mean and warmest-quarter temperature, precipitation, and the CHELSA elevation model).
Karger, D.N., Conrad, O., Böhner, J., Kawohl, T., Kreft, H., Soria-Auza, R.W., Zimmermann, N.E., Linder, H.P. & Kessler, M. (2017). Climatologies at high resolution for the earth's land surface areas. *Scientific Data* 4, 170122. https://doi.org/10.1038/sdata.2017.122
Karger, D.N. et al. (2021). Climatologies at high resolution for the earth's land surface areas. EnviDat. https://doi.org/10.16904/envidat.228
Licence: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

## Ground colours: Sentinel-2 cloudless

Sentinel-2 cloudless 2016 - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016). Used only to choose between ground blocks; no imagery is shown in game.

## Built-in test areas (bundled)

- Copernicus WorldDEM-30 (Everest area): produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. Licence text included in the jar under `earthplatform/copernicus_proof_data/provenance/`.
- NOAA National Centers for Environmental Information (2022). ETOPO 2022 15 Arc-Second Global Relief Model. https://doi.org/10.25921/fd45-gt74 (CC0 1.0).
- ESA WorldCover extracts, credited as above.

## Software

Earth Platform runs on [NeoForge](https://neoforged.net/) and contains no third-party code. Optional companions are installed separately and keep their own licences: [Sodium](https://modrinth.com/mod/sodium) (PolyForm Shield 1.0.0), [Iris](https://modrinth.com/mod/iris) (LGPL-3.0) and [Photon](https://github.com/sixthsurge/photon) by Benjamin Stott (SixthSurge), under the Photon Shaders License Agreement. The far terrain renderer is original code that follows the ideas of [Voxy](https://modrinth.com/mod/voxy) and [Distant Horizons](https://modrinth.com/mod/distanthorizons); no code from either is included.

## Trademarks

Minecraft is a trademark of Mojang Synergies AB. Earth Platform is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
