# Changelog

## 0.1.0 (unreleased, first public alpha)

The first release of Earth Platform for Minecraft 1.21.1 and NeoForge 21.1.

### The Earth
- The whole planet at one block per metre, with true heights from Y -16384 to 16383.
- Elevation from AWS Terrain Tiles, with ocean floors from bathymetry.
- Land cover from ESA WorldCover: forests, shrubland, grassland, bare rock and sand, snow and ice, wetlands, mangroves, moss, lakes and rivers at their surveyed height.
- Biomes from the real climate (CHELSA, 1981-2010), corrected for each place's height, so mountains carry snow and conifers above warm valleys.
- Every surface block vanilla grows, placed where it would grow on Earth: cacti in the Americas, cherry groves in East Asia, bamboo in South and East Asia, coral reefs in warm seas.
- Minecraft's own trees chosen by biome, and giant sequoias at real size in the Sierra Nevada's groves.
- Optional ground colours guided by Sentinel-2 cloudless satellite imagery (off by default).
- No cities: built-up land becomes the wild land around it.

### Survival
- Ordinary survival at any height: mining, crafting, mobs, light, beds, death and respawn.
- Caves, ores and vanilla structures, including villages, strongholds, trial chambers and ancient cities; `/locate` and eyes of ender find them.
- Nether and End through normal portals, with portals on the Earth linking back where you built them.

### Seeing far
- Textured voxel far terrain out to about 23 km, at real height, with see-through far water.
- Works with Iris and Photon, which light near blocks and far terrain alike.

### Getting around
- An Earth world type, chosen by default in Create World, with a picker for famous places or any latitude and longitude.
- `/earth visit`, `/earth goto`, `/earth places`, `/earth where` and `/earth home`.
- A "Preparing your Earth" screen on first arrival, and a quiet note while data downloads or when it can't be reached.
- Offline play in places you have visited, with a size limit for downloaded data.
- Settings in Mods → Earth Platform → Config.

### Servers
- Dedicated servers with several players on the same Earth.

### Known issues
- Iris and Sodium must be the exact tested versions.
- On a Mac, Photon's Colored Lights, Colored Shadows and Cloud Shadows are unavailable (the mod adapts stock Photon for Apple's OpenGL automatically).
- Distant Horizons is not supported alongside Earth Platform.
- Bee nests generate without bees.
