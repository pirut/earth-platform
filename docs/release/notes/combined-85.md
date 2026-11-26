Earth Platform build 85 closes far-terrain downhill seams, sharpens northern cliffs and canyons, removes bogus mountain-shadow water curtains, makes structure water and lava flow on first load, and hardens generation stability.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Fixes

- **Far-terrain seams:** Closed the horizontal gap where near chunks meet distant terrain when looking downhill, both with and without shader packs.
- **Northern cliffs and canyons:** Preserved high-resolution ArcticDEM elevation detail above 60°N and eliminated elevation spikes cleanly across tile borders in new worlds.
- **Rivers, waterfalls, and mountain shadows:** Stripped false cliff-shadow water and carved shadow walls on sheer mountain faces (such as Trollveggen) while keeping real waterfalls (such as Skógafoss) intact, joined rivers smoothly across dataset tile seams, and fixed border waterfalls in new worlds.
- **Biomes, treelines, and coastlines:** Kept treeless subpolar heaths and canyons clear of stray trees, prevented unfrozen peat bogs in freezing Arctic tundra, and stopped trees from spawning on offshore ocean reefs.
- **Structures and underground:** Water and lava inside newly generated structures (village fountains, wells, farms, portals) now flow naturally on first load; fixed Deep Dark sculk block entities, passenger entity spawning, amethyst geode placement, and modded ore filtering.
- **Stability and shutdown:** Bounded hydrology caches under memory pressure, isolated single-tile download errors without stalling healthy tiles, and fixed a shutdown race during world save and pregeneration.

### Saved worlds

- All terrain and hydrology dataset changes are version-pinned to new worlds. Existing saved worlds keep their original terrain and river pins so new chunks continue generating seamlessly alongside old ones.
