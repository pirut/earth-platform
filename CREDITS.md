# Credits

**Earth Platform would not be possible without the people and organizations below.** Every mountain, coastline, forest and river in the game comes from data that scientists, space agencies and mapping agencies spent years collecting and then chose to share openly. We are deeply grateful to each of them. If you enjoy exploring the Earth in Minecraft, they deserve the thanks.

Earth Platform builds its world from this open data. Apart from the few small files marked bundled below, none of it ships in the mod: the mod downloads each area while you play and keeps it in `earthplatform/cache` in your game folder. Earth Platform changes these data to make a Minecraft world (resampling, block and biome choices, procedural detail), so the result is not the original data, not a survey, and not for navigation.

The same credits appear in game under Mods → Earth Platform, and as a short line when you arrive on the Earth.

## Elevation: AWS Terrain Tiles

**What it gives Earth Platform:** The shape of the whole planet: every mountain, valley and sea floor starts here.

**Thank you** to Mapzen and the Tilezen community for assembling these tiles from dozens of national surveys, to Amazon Web Services for hosting them openly, and to every agency listed below.

**Attribution and license:**

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

## Fjords and the far north: Copernicus DEM GLO-30 (downloaded while you play)

**What it gives Earth Platform:** Steep fjords such as Milford Sound, and the ground in the far north that other surveys don't reach.

**Thank you** to the European Union, ESA, DLR and Airbus for the Copernicus programme and for making this global model free to use.

**Attribution and license:**

Elevation fill: produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30.
License: the [Copernicus WorldDEM-30 license](geography/data/copernicus-everest-proof/provenance/License-COPDEM-30.pdf), free of charge for any purpose. Changes: each 1° square is compared once with the elevation tiles, and where those stand well above GLO-30 over an area (fjords such as Milford Sound and Ísafjörður, and ground north of SRTM's reach), the tiles are moved toward GLO-30. Tiles come from the Copernicus DEM bucket on the Registry of Open Data on AWS and are cached on disk. Turn it off with `-Dearthplatform.earth.copernicus=false`. This project is not endorsed by the European Union, ESA or the Copernicus programme.

## United States: USGS 3DEP (downloaded while you play)

**What it gives Earth Platform:** Detailed heights, about 10 m apart, across the United States: Yosemite's walls and the Grand Canyon's cliffs.

**Thank you** to the U.S. Geological Survey and its partners for surveying the country in such detail and putting it in the public domain.

**Attribution and license:**

United States: USGS 3D Elevation Program 1/3 arc-second DEM (public domain), courtesy of the U.S. Geological Survey. U.S. Geological Survey, 3D Elevation Program 1/3 arc-second Digital Elevation Model, https://www.usgs.gov/3d-elevation-program.
Changes: tiles are fetched from the USGS National Map staged products (prd-tnm on AWS) only for visited places inside 1,450 pinned 1° cells, cached on disk and blended into the elevation above. Turn it off with `-Dearthplatform.earth.3dep=false`. The USGS does not endorse Earth Platform.

## Antarctica: REMA (downloaded while you play)

**What it gives Earth Platform:** Antarctica's ice sheet and mountains, at real height.

**Thank you** to the Polar Geospatial Center, the Byrd Polar and Climate Research Center and the U.S. National Science Foundation for mapping a whole continent and sharing it.

**Attribution and license:**

Antarctica: Reference Elevation Model of Antarctica (REMA) v2.0, Polar Geospatial Center, University of Minnesota (CC BY 4.0); Howat, I., et al. (2022), The Reference Elevation Model of Antarctica - Mosaics, Version 2, Harvard Dataverse, https://doi.org/10.7910/DVN/EBW8UC. DEMs provided by the Byrd Polar and Climate Research Center and the Polar Geospatial Center under NSF-OPP awards 1043681, 1542736, 1543501, 1559691, 1810976, and 2129685.
Howat, I. M., Porter, C., Smith, B. E., Noh, M.-J. & Morin, P. (2019). The Reference Elevation Model of Antarctica. *The Cryosphere* 13, 665-674. https://doi.org/10.5194/tc-13-665-2019
License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: REMA mosaic tiles are fetched from the Polar Geospatial Center's bucket on the Registry of Open Data on AWS only where you play, moved from the WGS 84 ellipsoid to the EGM96 geoid, and blended into the elevation above. Turn it off with `-Dearthplatform.earth.rema=false`.
Geoid: EGM96 (NGA, public domain), shipped as a half-degree grid south of 50 S in geography/src/main/resources (`egm96-south.bin`, sampled from NGA's EGM96 15' grid as PROJ distributes it).

## Canada: HRDEM (downloaded while you play)

**What it gives Earth Platform:** Bare ground from airborne lidar wherever Canada has flown it: most of the populated south, much of British Columbia, Quebec and the Maritimes.

**Thank you** to Natural Resources Canada and the provinces and territories whose lidar surveys it gathers.

**Attribution and license:**

Canada: High Resolution Digital Elevation Model (HRDEM), Natural Resources Canada. Contains information licensed under the Open Government Licence - Canada.
License: [Open Government Licence - Canada 2.0](https://open.canada.ca/en/open-government-licence-canada). Changes: blocks of the 2 m mosaic's digital terrain model are read from NRCan's bucket on AWS (canelevation-dem) only where you play, at 8 m and coarser, cached on disk and blended into the elevation above. Turn it off with `-Dearthplatform.earth.hrdem=false`. Natural Resources Canada does not endorse Earth Platform.

## France: RGE ALTI (downloaded while you play)

**What it gives Earth Platform:** France's 1 to 5 m national ground model, from lidar in the mountains, forests and along the coasts and rivers: the Alps, the Pyrenees, the Verdon Gorge and the overseas departments.

**Thank you** to IGN, the French national mapping agency, for opening its data to everyone.

**Attribution and license:**

France: IGN, RGE ALTI.
License: [Licence Ouverte / Open Licence 2.0 (Etalab)](https://www.etalab.gouv.fr/licence-ouverte-open-licence/). Changes: tiles are requested from IGN's Geoplateforme map service (data.geopf.fr) on the game's own map grid only where you play, cached on disk and blended into the elevation above. Turn it off with `-Dearthplatform.earth.ign=false`. IGN does not endorse Earth Platform.

## Japan: GSI elevation tiles (downloaded while you play)

**What it gives Earth Platform:** Japan's national ground model, 10 m everywhere and 5 m from lidar over most lowlands and many mountains: Mount Fuji and the Japanese Alps at their real height.

**Thank you** to the Geospatial Information Authority of Japan for publishing its maps as open tiles.

**Attribution and license:**

Source: Geospatial Information Authority of Japan website (https://maps.gsi.go.jp/development/ichiran.html), elevation tiles (dem_png, dem5a_png), processed by Earth Platform. 出典：国土地理院ウェブサイト（地理院タイル 標高タイルを加工して作成）.
License: [Public Data License 1.0](https://www.digital.go.jp/resources/open_data/public_data_license_v1.0) under GSI's content terms. Changes: tiles are fetched from cyberjapandata.gsi.go.jp only where you play, cached on disk and blended into the elevation above. Turn it off with `-Dearthplatform.earth.gsi=false`.

## Land cover: ESA WorldCover

**What it gives Earth Platform:** Where forests, grassland, deserts, snow, wetlands, mangroves and water lie, at 10 m detail around the world.

**Thank you** to the European Space Agency and the WorldCover consortium for this open map of the whole planet.

**Attribution and license:**

© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium.
Zanaga, D., Van De Kerchove, R., Daems, D., et al. (2022). ESA WorldCover 10 m 2021 v200. https://doi.org/10.5281/zenodo.7254221
License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: land cover classes are mapped to Minecraft biomes and blocks; built-up classes are replaced by the surrounding wild land.

## Climate: CHELSA

**What it gives Earth Platform:** The real climate of every place, which picks its biomes: jungle, savanna, taiga, tundra and everything between.

**Thank you** to Dirk Karger and the CHELSA team at WSL for these climatologies, released free for anyone to use.

**Attribution and license:**

CHELSA V2.1 climatologies, 1981-2010 (Köppen-Geiger zones, mean and warmest-quarter temperature, precipitation, and the CHELSA elevation model).
Karger, D.N., Conrad, O., Böhner, J., Kawohl, T., Kreft, H., Soria-Auza, R.W., Zimmermann, N.E., Linder, H.P. & Kessler, M. (2017). Climatologies at high resolution for the earth's land surface areas. *Scientific Data* 4, 170122. https://doi.org/10.1038/sdata.2017.122
Karger, D.N. et al. (2021). Climatologies at high resolution for the earth's land surface areas. EnviDat. https://doi.org/10.16904/envidat.228
License: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

## Rivers: JRC Global Surface Water

**What it gives Earth Platform:** Rivers, streams and lakes carved into the land where water really flows.

**Thank you** to the European Commission's Joint Research Centre, Google and the Copernicus programme for mapping decades of the world's surface water and sharing it without restriction.

**Attribution and license:**

JRC Global Surface Water v1.4 (Source: EC JRC/Google; Pekel et al. 2016, Nature 540, 418-422), Copernicus Programme.
Pekel, J.-F., Cottam, A., Gorelick, N. & Belward, A.S. (2016). High-resolution mapping of global surface water and its long-term changes. *Nature* 540, 418-422. https://doi.org/10.1038/nature20584
Terms: produced under the Copernicus Programme and provided free of charge, without restriction of use ([data access page](https://global-surface-water.appspot.com/download)). The European Union and the organisations in charge of the Copernicus programme do not incur any liability for any use of these data. Changes: the water occurrence layer is read where you play and turned into river channels in the terrain.

## Ecoregions: RESOLVE Ecoregions 2017 (bundled)

**What it gives Earth Platform:** The finer character of each region, like Florida's swamps and the Everglades' mangroves.

**Thank you** to RESOLVE, Eric Dinerstein and the many co-authors for this map of the world's 846 ecoregions.

**Attribution and license:**

Ecoregions: RESOLVE Ecoregions 2017, Dinerstein et al. 2017, An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm, BioScience 67(6):534-545, CC BY 4.0; derived 0.05 degree raster.
Dinerstein, E., Olson, D., Joshi, A., et al. (2017). An Ecoregion-Based Approach to Protecting Half the Terrestrial Realm. *BioScience* 67(6), 534-545. https://doi.org/10.1093/biosci/bix014
License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: the ecoregion polygons are rasterized to a 0.05 degree grid (`earthplatform/ecoregions.png`, with ecoregion names in `ecoregions.tsv`) and used to tune biomes and ground cover.

## Ground colors (optional): Sentinel-2 cloudless

**What it gives Earth Platform:** When you turn it on, ground blocks that match what each place looks like from space.

**Thank you** to EOX IT Services for their cloudless view of the Earth.

**Attribution and license:**

EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016). Off by default; used only when a player turns satellite imagery on, and then only to choose between ground blocks. No imagery is shown in game.

## Spawn picker globe (bundled)

**What it gives Earth Platform:** The globe you spin to choose where you start, the travel map, and the server's web map.

**Thank you** to NASA Earth Observatory and Reto Stöckli for the Blue Marble.

**Attribution and license:**

The globe on the Customize screen shows NASA's Blue Marble: NASA Earth Observatory, Reto Stöckli, *Blue Marble Next Generation* (July 2004, topography and bathymetry), https://visibleearth.nasa.gov/collection/1484/blue-marble. Public domain in the United States; credited as NASA asks. Close up, the globe also shows the elevation and land cover data credited above.

## Country borders (bundled)

**What it gives Earth Platform:** The country names shown as you cross a border, `/earth border country`, and the country in `/earth where`, GeoGuessr reveals and the web map.

**Thank you** to Natural Earth and its volunteers for free, public domain map data.

**Attribution and license:**

Made with Natural Earth. Free vector and raster map data @ naturalearthdata.com. Admin 0 countries at 1:50m, packed into `minecraft/src/main/resources/earthplatform/countries.bin` by `minecraft/scripts/countries.py`. Public domain.

## Test areas (source repository only)

The older engine's proof areas are test fixtures. They stay in the source repository for its tests and are not in the release jar.

- Copernicus WorldDEM-30 (Everest area): produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. License text kept with the data under `geography/data/copernicus-everest-proof/provenance/`. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30. Earth Platform is not endorsed by the Copernicus programme.
- NOAA National Centers for Environmental Information (2022). ETOPO 2022 15 Arc-Second Global Relief Model. https://doi.org/10.25921/fd45-gt74 (CC0 1.0).
- ESA WorldCover extracts, credited as above.

## Software

Earth Platform runs on [NeoForge](https://neoforged.net/) and contains no third-party code. Optional companions are installed separately and keep their own licenses: [Sodium](https://modrinth.com/mod/sodium) (PolyForm Shield 1.0.0), [Iris](https://modrinth.com/mod/iris) (LGPL-3.0) and [Photon](https://github.com/sixthsurge/photon) by Benjamin Stott (SixthSurge), under the Photon Shaders License Agreement. Earth Platform's own code was written for this project; see [Originality](docs/release/LICENSING-AUDIT.md#originality-check) for how that was checked.

## Distant terrain: thank you, Voxy and Distant Horizons

Earth Platform's far terrain would not exist without [Voxy](https://modrinth.com/mod/voxy) by Cortex (MCRcortex) and [Distant Horizons](https://modrinth.com/mod/distanthorizons) by James Seibel and contributors. They showed how to draw Minecraft terrain to the horizon, and our renderer is built on their ideas. We'd have recommended using them directly, but neither works with a cubic-chunks world: Distant Horizons stores heights in a 4,096-block band, too short for a world where Everest stands at Y 8,900, and Voxy builds its terrain from ordinary Minecraft chunks, which a cubic world doesn't have. So we wrote our own renderer for cubes, following their lead. It contains no code from either project.

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
