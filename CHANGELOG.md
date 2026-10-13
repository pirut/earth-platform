# Changelog

## 0.1.0 (unreleased, first public alpha)

The first release of Earth Platform for Minecraft 1.21.1 and NeoForge 21.1.

### The Earth
- The whole planet at one block per metre, with true heights from Y -16384 to 16383.
- Elevation from AWS Terrain Tiles, with ocean floors from bathymetry. Bad pixels in the source tiles (deep pits, lone spikes) are found and mended, so places like Aconcagua, Bora Bora and the Amazon have no holes or spires.
- Rivers, lakes and estuaries lie level, at the same height near and far, and estuaries join the sea.
- Land cover from ESA WorldCover: forests, shrubland, grassland, bare rock and sand, snow and ice, wetlands, mangroves, moss, lakes and rivers at their surveyed height.
- Biomes from the real climate (CHELSA, 1981-2010), corrected for each place's height, so mountains carry snow and conifers above warm valleys, and refined by the RESOLVE ecoregion map (swamps in Florida, mangroves in the Everglades).
- Every surface block vanilla grows, placed where it would grow on Earth: cacti in the Americas, cherry groves in East Asia, bamboo in South and East Asia, coral reefs in warm seas.
- Minecraft's own trees chosen by biome, and giant sequoias at real size in the Sierra Nevada's groves.
- Optional ground colours guided by Sentinel-2 cloudless satellite imagery (off by default).
- No cities: built-up land becomes the wild land around it.

### Survival
- Ordinary survival at any height: mining, crafting, light, death and respawn.
- Mobs walk, chase and wander; villagers take jobs, sleep in beds and trade.
- Sleeping skips the night and clears the rain.
- The Bonus Chest world option places its chest beside your first arrival on the Earth.
- Chests, barrels and furnaces show up straight away, after relogging and in newly loaded villages.
- The sky stays bright in real places below sea level, such as Death Valley.
- Caves, ores and vanilla structures, including villages, strongholds, trial chambers and ancient cities; `/locate` and eyes of ender find them.
- Nether and End through normal portals, with portals on the Earth linking back where you built them.
- Pistons and sticky pistons, raids from Bad Omen, and bees in generated bee nests.

### Seeing far
- Textured voxel far terrain out to about 23 km, at real height, with see-through far water.
- Works with Iris and Photon, which light near blocks and far terrain alike.
- Macs run the stock Photon download at its defaults: the mod adapts it for Apple's OpenGL on your own computer.

### Getting around
- An Earth world type, chosen by default in Create World, with a picker for famous places or any latitude and longitude.
- `/earth visit`, `/earth goto`, `/earth places`, `/earth where` and `/earth home`.
- The jet stream: glide with an elytra above Y 9,200 and the wind carries you at over 1,000 blocks a second, fast enough to cross a continent in under an hour.
- A "Preparing your Earth" screen on first arrival, and a quiet note while data downloads or when it can't be reached.
- Offline play in places you have visited, with a size limit for downloaded data.
- Settings in Mods → Earth Platform → Config.

### Servers
- Dedicated servers with several players on the same Earth.

### Other mods
- Xaero's Minimap, Xaero's World Map and JourneyMap map the Earth world, including ground above Y 2047.
- Create contraptions (bearings, pistons and the rest) assemble in the Earth world.
- Distant Horizons pauses itself in the Earth world, with a one-time note in chat, so it doesn't hide the far terrain.
- Create Aeronautics ships assemble, fly and save in the Earth world, with air pressure from the real atmosphere. The jet stream carries ships east too, the altitude sensor reads from sea level to 10 km, and ships shade mobs at any height.
- AE2 spatial storage captures and places Earth blocks, and AE2 meteorites generate in the Earth world.
- Create's mechanical arm, pulley, scaffolding and elevators, Waystones, Mekanism (Digital Miner, multiblocks and pipes), Immersive Engineering's multiblocks and the Twilight Forest portal work in the Earth world.
- JEI, EMI, Jade, AppleSkin, Ars Nouveau, AE2 networks, Refined Storage 2, Iron's Spells, FTB Quests and Teams, Farmer's Delight, Sophisticated Backpacks, Supplementaries, YUNG's structures and Open Parties and Claims work unchanged.

### Known issues
- Iris and Sodium must be the exact tested versions.
- On a Mac, Photon's Colored Lights, Colored Shadows and Cloud Shadows are unavailable (the mod adapts stock Photon for Apple's OpenGL automatically).
- Create Aeronautics ships with players aboard ride the jet stream at 40 blocks a second; only empty ships reach elytra speed.
- In the Earth world, AE2's meteorite compass finds meteorites by map column only and doesn't notice blocks changed later.
- Other mods that check build height or read chunks themselves may misbehave in the Earth world; please report them.
- Blocks you place above Y 9,000 have no collision for a player gliding in the jet stream.
