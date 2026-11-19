Earth Platform build 74 adds optional mod compatibility, surveyed terrain, and fixes for long-distance travel.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Compatibility

- Countered's Terrain Slabs 3.1.2 can add half-block steps to exposed grass and soil on gentle slopes. Enable slab generation before creating a new Earth world and keep the mod installed when reopening it. Suggested by lucrais on CurseForge.
- Create Sky 0.1.0 on NeoForge can draw clouds and their shadows in Earth worlds and run dimension-local storms. Install it on the server and clients for storms. Thanks to LockBoss for Create Sky.
- Fixed a NeoForge dedicated-server crash in the jet-stream hook for Sable ships. The separate Windows Create Aeronautics report remains open (#183).

### Terrain

- New Earth worlds fill gaps in Estonia's satellite water map with surveyed river channels, including along the Pirita (#182).
- New Earth worlds use Schleswig-Holstein's surveyed elevations around Helgoland (#184).
- Cacti respect the native range in newly generated terrain, including on red sand. This prevents desert scrub cacti from appearing in Greece (#186).

### Travel and memory

- Bounded the cave-visibility search after long teleports to avoid a negative-capacity crash and excessive searches (#185).
- Large modpacks that run low on memory release map data and slow far-terrain work until memory frees up. The game reports the pressure once. The initial memory spike after a long teleport can still occur (#179).
- Windows cache saves retry when another process temporarily has the file open.
- The in-game Earth Platform settings expose engine switches. With Sodium, the Earth page is under Video Settings.
