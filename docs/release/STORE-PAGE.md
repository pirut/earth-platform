# Modrinth and CurseForge listings

Everything to paste into the two project pages. Nothing has been published. JR creates the projects and uploads the files; the clicks are in [GO-PUBLIC.md](GO-PUBLIC.md).

## Project fields

| Field | Modrinth | CurseForge |
| --- | --- | --- |
| Name | Earth Platform | Earth Platform |
| URL / slug | `earth-platform` (modrinth.com/mod/earth-platform) | `earth-platform` |
| Summary | The real Earth in Minecraft at true scale. Every mountain at its real height, real climate and biomes, terrain to the horizon, and ordinary survival anywhere on the planet. (Under 256 characters.) | The real Earth in Minecraft at true scale. (Short summary field.) |
| Project type | Mod | Minecraft → Mods |
| Loader | NeoForge | NeoForge |
| Game version | 1.21.1 | 1.21.1 |
| Environment | Client: required. Server: required. | Client and server |
| Categories / tags | Primary **Worldgen**; also **Adventure** and **Transportation** | **World Gen** (main), **Biomes**, **Adventure and RPG** |
| Licence | LGPL-3.0-only ("GNU Lesser General Public License v3.0 only") | "GNU Lesser General Public License version 3 (LGPLv3)" |
| Icon | [docs/images/icon.png](../images/icon.png) (256 × 256) | Same file as the logo |
| Source | https://github.com/pirut/earth-cubic-platform | Same, under "Source" |
| Issues | https://github.com/pirut/earth-cubic-platform/issues | Same, under "Issues" |
| Wiki | https://github.com/pirut/earth-cubic-platform/blob/main/docs/PLAYING.md | Same |
| Discord | None yet; leave blank | Leave blank |
| Monetisation | Creator rewards **off** for the first release. Turning them on later is fine with satellite imagery off by default, but ask EOX first if imagery ever becomes default ([LICENSING-AUDIT.md](LICENSING-AUDIT.md)) | Rewards programme **off** for the first release, for the same reason |

## Dependencies

Set these on every version you upload.

| Mod | Modrinth relation | CurseForge relation | Note |
| --- | --- | --- | --- |
| NeoForge 21.1.256 or newer for 1.21.1 | (loader; not a project dependency) | (loader) | Required |
| [Sodium](https://modrinth.com/mod/sodium) 0.8.12 or later 0.8 | Optional | Optional Dependency | Only for shaders |
| [Iris](https://modrinth.com/mod/iris) 1.8.14 or later 1.8 | Optional | Optional Dependency | Only for shaders |
| [Photon](https://modrinth.com/project/lLqFfGNs) v1.3b | Not a mod; mention in the description only | Not listed | Stock, unmodified pack |
| Distant Horizons | Don't list | Don't list | Not needed: it pauses itself in Earth worlds |

## Gallery

Upload in this order. The first one is the featured image. All are 1280 × 720 from `docs/images/`; retake at 1920 × 1080 on a real GPU if time allows.

| File | Title | Caption |
| --- | --- | --- |
| grand-canyon-photon.jpg (featured) | The Grand Canyon | Mather Point at its real depth, with Photon shaders. |
| yosemite-half-dome-photon.jpg | Half Dome, Yosemite | Half Dome and Yosemite Valley from Glacier Point, at their real heights. |
| lauterbrunnen-photon.jpg | Lauterbrunnen, Switzerland | The valley and the Alps beyond it, drawn out to the horizon. |
| lake-tahoe-emerald-bay.jpg | Emerald Bay, Lake Tahoe | Lakes sit at their surveyed level, islands and all. |
| grand-canyon.jpg | The Grand Canyon without shaders | Real rock layers in vanilla lighting. |
| travel-map.jpg | The travel map | In creative, press N and click anywhere on the globe. |
| travel-map-flight.jpg | Flying there | The camera climbs out over the planet and comes back down where you clicked. |
| spawn-globe.jpg | Choose where to start | Pick a spot on the globe when you create the world. |
| spawn-picker.jpg | Or pick a famous place | A list of famous places, or any latitude and longitude. |

## Description

Paste everything between the lines into the description editor (Markdown mode on CurseForge). Swap the image paths for the gallery's uploaded URLs, or leave the images out.

---

# Earth Platform

**The real Earth in Minecraft, at true scale.** One block is one metre. Every mountain stands at its real height, every lake and valley is where it really is, and the land reaches all the way to the horizon. Start anywhere on the planet and play ordinary survival there.

## Features

- **The whole planet, at real height.** Worlds run from Y -16384 to 16383, so Everest's summit is 8,848 blocks above the sea and you can climb it, mine it and build on it.
- **Real ground.** Elevation, land cover and climate come from open satellite and survey data, with finer surveys where they exist: about 10 m across the United States and REMA in Antarctica. Forests, deserts, glaciers, wetlands, lakes and rivers sit where they are on Earth, and the Grand Canyon shows its real rock layers.
- **Minecraft's own world, placed by the real climate.** The Amazon grows vanilla jungle, the Serengeti acacia savanna, Siberia snowy taiga. Giant sequoias stand at their real 70 to 90 m in the Sierra Nevada groves.
- **See for kilometres.** Distant terrain is drawn as textured voxels out to about 94 km, so you see the far side of the valley and the ranges beyond it, not fog.
- **Fly anywhere.** In creative, press **N** for the travel map: click a spot on the globe and the camera climbs out over the planet and comes back down there. In survival, use `/earth visit` and `/earth goto`, or glide an elytra above Y 9,200 and let the jet stream carry you across a continent in under an hour.
- **Ordinary survival.** Mine, craft, build and die as usual. Caves, ores, villages and other vanilla structures generate, and the Nether and the End work through normal portals.
- **Shaders.** Works with Iris and the stock Photon download, which light near blocks and far terrain alike, Macs included.
- **Servers and other mods.** Runs on a dedicated server where everyone shares the same Earth. Create, Mekanism, Waystones, the Twilight Forest and the popular map mods work in the Earth world.

There are no cities: built-up land becomes the wild land around it. Vanilla villages and structures still appear.

## Get started

1. Install NeoForge 21.1.256 or newer for Minecraft 1.21.1 and put this mod in `mods`. Install it on the server too if you play on one.
2. Give the game at least 4 GB of memory, or at least 6 GB with shaders such as Photon.
3. Singleplayer → Create New World. The world type is already **Earth**; press **Customize** to choose where you start.

The first visit to a place downloads its terrain, so you need an internet connection. Places you have been stay on your computer and work offline afterwards.

**Shaders (optional):** Sodium 0.8.12 and Iris 1.8.14 or later in the same series, with the stock Photon v1.3b pack. Macs use the same stock Photon; the mod adapts it for Apple's OpenGL on your computer.

You don't need Distant Horizons: Earth Platform has its own far terrain, and Distant Horizons pauses itself in Earth worlds.

[Install guide](https://github.com/pirut/earth-cubic-platform/blob/main/docs/INSTALL.md) · [Playing on the Earth](https://github.com/pirut/earth-cubic-platform/blob/main/docs/PLAYING.md) · [FAQ](https://github.com/pirut/earth-cubic-platform/blob/main/docs/FAQ.md) · [Report a problem](https://github.com/pirut/earth-cubic-platform/issues)

## Credits

**Earth Platform would not be possible without open data.** Elevation from AWS Terrain Tiles (USGS 3DEP, SRTM, GMTED2010, NOAA ETOPO1, Copernicus EU-DEM, ArcticDEM and national mapping agencies), Copernicus DEM GLO-30 (produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved), USGS 3DEP (courtesy of the U.S. Geological Survey) and REMA (Polar Geospatial Center, University of Minnesota; DEMs provided by the Byrd Polar and Climate Research Center and the Polar Geospatial Center under NSF-OPP awards 1043681, 1542736, 1543501, 1559691, 1810976, and 2129685). Land cover © ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium (CC BY 4.0). Climate from CHELSA V2.1 (Karger et al. 2017, CC0). Rivers from JRC Global Surface Water (Source: EC JRC/Google). Ecoregions from RESOLVE Ecoregions 2017 (Dinerstein et al. 2017, CC BY 4.0). Globe from NASA Blue Marble (NASA Earth Observatory, Reto Stöckli). Optional ground colours (off by default) from EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016). Full credits are in the mod (Mods → Earth Platform) and in [CREDITS.md](https://github.com/pirut/earth-cubic-platform/blob/main/CREDITS.md).

**Distant terrain: thank you, Voxy and Distant Horizons.** Our far terrain is built on ideas from Voxy by Cortex and Distant Horizons by James Seibel and contributors. We'd have recommended using them directly, but neither works with a cubic-chunks world, so we wrote our own renderer following their lead; it contains no code from either.

The world is built from these data but changed to make Minecraft terrain, with procedural detail. Not for navigation.

Earth Platform is free software under the GNU LGPL 3.0; the Earth data keeps its own terms.

*Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.*

---

## Each version you upload

| Field | Value |
| --- | --- |
| File | `earthplatform-<version>.jar` from the GitHub release (the same file on both sites) |
| Version number | The mod version, for example `0.1.0-alpha.38` (see [Versioning](GO-PUBLIC.md#versioning)) |
| Version name / display name | `Earth Platform 0.1.0-alpha.38` |
| Channel / release type | **Alpha** |
| Game versions | 1.21.1 |
| Loaders | NeoForge |
| Java version (CurseForge) | Java 21 |
| Environment (CurseForge) | Client and Server |
| Changelog | That build's notes from `docs/release/notes/combined-NN.md`, which the GitHub release also shows |
| Dependencies | Sodium and Iris as optional (table above) |
