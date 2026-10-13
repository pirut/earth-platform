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

## Ecoregions: RESOLVE Ecoregions 2017 (bundled)

Ecoregions: RESOLVE Ecoregions 2017, Dinerstein et al. 2017, An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm, BioScience 67(6):534-545, CC BY 4.0; derived 0.05 degree raster.
Dinerstein, E., Olson, D., Joshi, A., et al. (2017). An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm. *BioScience* 67(6), 534-545. https://doi.org/10.1093/biosci/bix014
Licence: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: the ecoregion polygons are rasterised to a 0.05 degree grid (`earthplatform/ecoregions.png`, with ecoregion names in `ecoregions.tsv`) and used to tune biomes and ground cover.

## Ground colours (optional): Sentinel-2 cloudless

EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016). Off by default; used only when a player turns satellite imagery on, and then only to choose between ground blocks. No imagery is shown in game.

## Spawn picker globe (bundled)

The globe on the Customize screen shows NASA's Blue Marble: NASA Earth Observatory, Reto Stöckli, *Blue Marble Next Generation* (July 2004, topography and bathymetry), https://visibleearth.nasa.gov/collection/1484/blue-marble. Public domain in the United States; credited as NASA asks. Close up, the globe also shows the elevation and land cover data credited above.

## Built-in test areas (bundled)

- Copernicus WorldDEM-30 (Everest area): produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. Licence text included in the jar under `earthplatform/copernicus_proof_data/provenance/`. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30. Earth Platform is not endorsed by the Copernicus programme.
- NOAA National Centers for Environmental Information (2022). ETOPO 2022 15 Arc-Second Global Relief Model. https://doi.org/10.25921/fd45-gt74 (CC0 1.0).
- ESA WorldCover extracts, credited as above.

## Software

Earth Platform runs on [NeoForge](https://neoforged.net/) and contains no third-party code. Optional companions are installed separately and keep their own licences: [Sodium](https://modrinth.com/mod/sodium) (PolyForm Shield 1.0.0), [Iris](https://modrinth.com/mod/iris) (LGPL-3.0) and [Photon](https://github.com/sixthsurge/photon) by Benjamin Stott (SixthSurge), under the Photon Shaders License Agreement. Earth Platform's own code was written for this project; see [Originality](docs/release/LICENSING-AUDIT.md#originality-check) for how that was checked.

## Distant terrain: thank you, Voxy and Distant Horizons

Earth Platform's far terrain would not exist without [Voxy](https://modrinth.com/mod/voxy) by Cortex (MCRcortex) and [Distant Horizons](https://modrinth.com/mod/distanthorizons) by James Seibel and contributors. They showed how to draw Minecraft terrain to the horizon, and our renderer is built on their ideas. We would have used them directly, but neither works with a cubic-chunks world: Distant Horizons stores heights in a 4,096-block band, too short for a world where Everest stands at Y 8,900, and Voxy builds its terrain from ordinary Minecraft chunks, which a cubic world doesn't have. So we wrote our own renderer for cubes, following their lead. It contains no code from either project.

From **Voxy** we took the shape of the whole thing:
- far terrain as a pyramid of 3D voxel sections, each level twice as coarse as the one inside it, so trees, cliffs, overhangs and player builds keep their shape in the distance;
- choosing detail by how big a voxel looks on screen, so distant terrain stays sharp without wasting memory;
- packing faces into a few bytes each in large GPU buffers, drawn by the renderer's own shader with one multi-draw per buffer, and one shared table of block looks;
- the shader-pack interface Voxy created (`voxy.json`, `voxy_opaque.glsl`, `VoxyFragmentParameters` and `voxy_emitFragment`, plus the far depth and projection names packs read, `vxDepthTexOpaque`, `vxDepthTexTrans`, `vxProj`, `vxProjInv`, `vxProjPrev` and `vxRenderDistance`). Earth Platform speaks this interface so packs that support Voxy, such as Photon, light our far terrain the way they light Voxy's. Thanks to Voxy and to the shader authors who adopted it.

From **Distant Horizons** we took:
- exact heights in the distance: each far column is lowered so its ground or water sits at the real height instead of snapping to a voxel boundary;
- building distant terrain on the server from the world generator, so far land is there before anyone has visited it;
- how Iris hands a shader pack's uniforms to Distant Horizons' programs, which we follow for our own far programs;
- years of showing players what "render distance" can mean.

## Earlier Earth and cubic-chunks projects

The [Cubic Chunks](https://github.com/OpenCubicChunks) mods, [Terrarium](https://github.com/gegy/terrarium) and [Terra++](https://github.com/BuildTheEarth/terraplusplus) (Build The Earth) proved that tall worlds and a real-scale Earth in Minecraft are possible. Earth Platform contains no code from them.

## Trademarks

Minecraft is a trademark of Mojang Synergies AB. Earth Platform is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
