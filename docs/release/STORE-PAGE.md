# Modrinth and CurseForge page draft

Not published. Fill the gaps marked TODO once the licence blockers in [LICENSING-AUDIT.md](LICENSING-AUDIT.md) are settled.

## Basics

- **Name:** Earth Platform (TODO: final name; no "Minecraft" in it)
- **Slug:** `earth-platform`
- **Summary (Modrinth, under 256 characters):** The real Earth in Minecraft at true scale. Every mountain at its real height, real climate and biomes, terrain to the horizon, and ordinary survival anywhere on the planet.
- **Type:** Mod. **Loader:** NeoForge. **Game version:** 1.21.1. **Environment:** client and server (required on both).
- **Categories:** Worldgen, Adventure, Technology. CurseForge: World Gen, Biomes, Adventure and RPG.
- **Licence:** TODO (currently All Rights Reserved).
- **Links:** Source, Issues, Wiki → TODO once the repository is public. Discord: none yet.
- **Icon:** [docs/images/icon.png](../images/icon.png)
- **Dependencies:** none required. Optional: Sodium 0.8.12-beta.1, Iris 1.8.14-beta.1 (exact versions). Incompatible in practice: Distant Horizons.
- **Monetisation:** creator rewards are fine with imagery off by default; if they are turned on, players who opt into imagery use EOX data in a mod that earns money, so get EOX's OK first (see the audit).

## Gallery

Most test screenshots are 854×480. Re-take the ones marked ★ at 1920×1080 on a real GPU before publishing.

| File | Title | Caption |
| --- | --- | --- |
| glacier-point-photon.jpg (featured) | Glacier Point, Yosemite | Half Dome and Yosemite Valley at their real heights, with Photon shaders. |
| lake-reflections-photon.jpg ★ | Mountain lake | Far terrain reaches kilometres, and lakes reflect the peaks around them. |
| lake-tahoe-emerald-bay.jpg ★ | Emerald Bay, Lake Tahoe | Lakes sit at their surveyed level, islands and all. |
| sierra-nevada-from-above.jpg ★ | The Sierra Nevada | Real ridges and valleys all the way to the horizon. |
| half-dome.jpg ★ | Half Dome | One block is one metre. |
| giant-sequoias.jpg ★ | Giant sequoias | Real groves, real size: up to 94 blocks tall. |
| amazon-rainforest.jpg ★ | The Amazon | Real climate picks the biome: vanilla jungle as far as you can see. |
| yoshino-cherry-and-bamboo.jpg ★ | Yoshino, Japan | Cherry groves and bamboo where they grow on Earth. |
| serengeti-savanna.jpg ★ | The Serengeti | Acacia savanna on the real plains. |
| siberian-taiga.jpg ★ | Yakutia, Siberia | Snowy taiga in the far north. |
| village.jpg ★ | A village | Vanilla villages and structures still generate. |
| nether-portal-on-earth.jpg ★ | Portals | The Nether and the End work as usual. |
| spawn-picker.jpg | Choose where to start | Pick a famous place or any latitude and longitude. |
| multiplayer.jpg ★ | Multiplayer | Share the planet on a dedicated server. |

## Description

> Paste below this line. Image links need the uploaded gallery URLs.

---

# Earth Platform

**The real Earth in Minecraft, at true scale.**

One block is one metre. Every mountain stands at its real height, every lake and valley is where it really is, and the land runs all the way to the horizon. Start anywhere on the planet and play ordinary survival there.

## Features

**Real height, no limits.** Worlds run from Y -16384 to 16383. Everest's summit is 8,848 blocks above the sea, and you can climb it, mine it and build on it.

**The real ground.** Elevation, land cover and climate come from open satellite and survey data. Forests, deserts, glaciers, wetlands, mangroves, lakes and rivers are where they are on Earth.

**Minecraft's own world, placed by the real climate.** The Amazon grows vanilla jungle, the Serengeti acacia savanna, Siberia snowy taiga, Japan cherry groves and bamboo. Giant sequoias stand at their real height in the Sierra Nevada.

**See for kilometres.** Distant terrain is drawn as textured voxels out to about 23 km, so you see the far side of the valley instead of fog.

**Ordinary survival.** Mining, crafting, mobs, caves, ores, villages, strongholds, the Nether and the End all work as usual.

**Go anywhere.** Pick a famous place or any latitude and longitude when you create the world. Travel later with `/earth visit everest` or `/earth goto 46.59 7.91`.

**Shaders.** Works with Iris and Photon, which light near blocks and far terrain alike.

**Servers.** Run it on a dedicated server and share the planet.

No cities: built-up land becomes the wild land around it, so the world is yours to build. Vanilla villages and structures still appear.

## Getting started

1. Install NeoForge 21.1.256 for Minecraft 1.21.1 and put this mod in `mods`.
2. Give the game at least 4 GB of memory.
3. Create New World: the world type is already **Earth**. Press **Customize** to choose where you start.

The first visit to a place downloads its terrain, so you need an internet connection. Places you've been work offline afterwards.

**Shaders:** Sodium 0.8.12-beta.1 and Iris 1.8.14-beta.1 (these exact versions) with Photon v1.3b. On a Mac, turn off Cloud Shadows and Shadow Color in Photon's Lighting settings.

**Don't** install Distant Horizons alongside it: Earth Platform has its own far terrain.

Install guide, FAQ and troubleshooting: TODO link to docs/INSTALL.md and docs/FAQ.md.

## Credits

Elevation from AWS Terrain Tiles (USGS 3DEP, SRTM and GMTED2010, NOAA ETOPO1, Copernicus EU-DEM, ArcticDEM, and the national mapping agencies of Australia, Austria, Canada, Mexico, New Zealand, Norway and the United Kingdom). Land cover © ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium (CC BY 4.0). Climate from CHELSA V2.1 (Karger et al. 2017, CC0). Optional ground colours (off by default) from EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016). Full credits in the mod (Mods → Earth Platform) and in CREDITS.md.

The world is built from these data but changed to make Minecraft terrain, with procedural detail. Not for navigation.

*Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.*

---

## CurseForge differences

- CurseForge's description editor takes the same Markdown in its "Markdown" mode.
- Main file: the jar, release type Alpha, game version 1.21.1, NeoForge.
- Relations: Sodium and Iris as "Optional dependency"; Distant Horizons as "Incompatible".
