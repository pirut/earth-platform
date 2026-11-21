Earth Platform build 76 fixes long trips, low-memory crashes, and ocean seams, draws far terrain on Fabric, and lets you file a bug from the game.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Travel

- Teleporting a long way, such as from the Serengeti to Everest, no longer crashes the game once the new terrain has meshes. For that one oversized frame, nearby cubes that would have been hidden stay drawn, and hiding them resumes when the old meshes are gone (#204, #185).
- Sable ships, physics boxes, and ropes far from the map origin stay where they should. Ships no longer shake or get flung away, ropes keep their contraptions, the physics wand turns a ship around the selected block, and the selection outline sits on that block. Dragging a ship very fast with the wand can still tunnel through terrain, and each physics scene has one origin, so two clusters millions of blocks apart cannot both sit near it. Thanks to Cheesidibb1 (#213, #197, #183).

### Terrain

- Coastlines and open sea that cross a whole degree of latitude or longitude no longer grow a straight wall of cliffs and floating needles. Just south of Durban, along 30°S, that slot was kilometres below the sea; it is sea again, and the beach beside it stays. Chunks you have already visited keep the old shape, so a world that has been to this coast meets a seam where old chunks border new ones. Start a fresh world to see the repair throughout (#207, #202).

### Memory

- A large modpack on a small heap no longer keeps filling memory after a long trip until the game exits. When the heap is nearly full, new downloads and far terrain wait, and if a garbage collection still leaves less than a 512 MB reserve, Earth drops its in-memory map caches and tells you once. On a heap with room to spare, cache sizes are unchanged (#205, #179).
- Saving Earth data on Windows retries when another process briefly has the cache file open, instead of throwing the download away.

### Far terrain

- On Fabric, distant terrain turns on when the window is an OpenGL 3.2 context, which is what Fabric and Sodium open. That context never set the OpenGL 3.3 flag the old check required, so the horizon stayed off even on a card that supports newer OpenGL. NeoForge is unchanged (#224, #220, #178).

### Reporting a bug

- With the optional Bug Courier mod installed, you can file an Earth Platform bug from the game. The pause menu's **Report a mod bug** button, `/bugreport earthplatform`, and `/earth bug` open a report you can read first. It includes the Earth Platform, Minecraft, and loader versions, your coordinates, latitude and longitude when the Earth grid is known, the biome, and a short scrubbed log line. Nothing is sent until you press Submit. Bug Courier is not bundled (#208).
- Without Bug Courier, `/earth bug` opens a prefilled GitHub issue. The game runs the same if Bug Courier is not installed.

### Releases

- This build is published on CurseForge and Modrinth as Beta, for both the NeoForge and Fabric jars. The version in the Mods screen stays `0.1.0-alpha.76`. A version with no alpha, beta, or prototype suffix is still a full release (#211).
- Builds 74 and 75 already on those sites can be marked Beta the same way, without uploading the jars again (#212).

### Internal

- A merge into main no longer publishes a release by itself. A release is published when a pull request labeled `build` is merged, when a `combined-*` tag is pushed, or when the Release workflow is run by hand (#223).
