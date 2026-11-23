Earth Platform build 77 adds the BuildTheEarth map projection, fixes structures from YUNG's and Aquamirae, stops big structures from freezing servers, and adds more terrain fixes and mod compatibility.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### World

- New optional **BTE Dymaxion (1:1)** projection matches BuildTheEarth block coordinates and keeps a true 1:1 scale on every continent. Pick it from the **Projection** button in Create World, Earth, Customize. On a dedicated server, set `defaultProjection = "bte-dymaxion-v1"` in `config/earthplatform-common.toml` before the world is first created. Web Mercator stays the default, and existing worlds are untouched (#260).
- Coastline voids, coastal pits, and shoreline sea levels are fixed. Inland lakes are capped by local relief, canyon rivers and falls come down to their banks, and glacier lakes, spires, alpine rock, canyon strata, and bushveld soils look right (#245, #247, #233, #248, #249).
- Earth forests can use optional regional trees, and giant mushrooms are gone from them (#259).
- Ores that other mods add to biomes now generate (#232).
- Surface structures stay off steep walls, and the Generate Structures option is honored (#241).
- Optional day and night by longitude, latitude, and season (#253).

### Structures and servers

- YUNG's Better Strongholds and Better Desert Temples now generate on Earth. Better Dungeons and Better Ocean Monuments no longer lose parts. Aquamirae camps and snowy wisteria no longer throw errors. Depleted Create Ore Excavation veins stay depleted after a restart. Start a new world to see structures that failed before (#262).
- Big structures such as ancient cities, trial chambers, and mineshafts no longer freeze the server until the watchdog kills it. Structure lookups no longer run on the server thread, and Dungeons and Taverns shrines no longer wait on faraway elevation downloads (#263).

### Compatibility

- Biomes O' Plenty, Regions Unexplored, and Oh The Biomes We've Gone biomes and surface blocks (#258).
- Homeostatic, Cold Sweat, and Tough as Nails (#255).
- ProtoManly's Weather follows Earth's local climate (#257).
- Ragdollified, Bloody Bits, Wakes, and Immersive Vehicles (#252).
- Axiom on NeoForge through Sinytra Connector (#251).

### Rendering

- Far terrain no longer shows holes at cave entrances, and draws through the pack's own terrain program when neither Voxy nor Distant Horizons is supported. Dynamic lighting updates on cubic terrain (#243, #244, #242).

### Releases

- This build is published on CurseForge and Modrinth as Beta, for both the NeoForge and Fabric jars. The version in the Mods screen is `0.1.0-beta.77`.
