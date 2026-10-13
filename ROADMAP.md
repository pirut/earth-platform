# Earth Cubic Platform roadmap

Status: resumed on October 12, 2026 on the "one cubic core" route (see below). This is the continuation order; a milestone counts as done only when its acceptance was met in the installed client.

## One cubic core (current route)

The project owner chose to rebuild survival on a single working cubic engine instead of continuing the locked 039 survival layer. The engine lives in `minecraft/src/main/java/io/earthplatform/minecraft/cubic`:

- Cubes are vanilla `LevelChunkSection`s plus block entities, keyed by true cube coordinates from Y -16384 to 16383. A small carrier dimension (`earthplatform:cubic`) supplies chunk tickets, entity sections and ticking; Level mixins route every block, block-entity, light, biome and height query to the cube store, so vanilla gameplay code runs unchanged at any Y.
- The server generates or loads cubes on worker threads, streams them nearest-first to each player, random-ticks them, lights them for gameplay, spawns mobs in the loaded band around players, and saves edited cubes in region files under `dimensions/earthplatform/cubic/cubes`.
- The client meshes and lights cubes off-thread with the vanilla block and fluid renderers and draws them at the vanilla terrain stages. Block positions above Y 2047 keep their true Y on the wire.
- Far terrain works the way Voxy draws distance: a pyramid of 3D voxel sections (16^3 voxels, voxel = 2^level blocks, levels 1 to 6) around the player, each level's middle replaced by the next finer one. Detail is chosen by on-screen size as Voxy does: by default each level keeps about 180 to 370 of its voxels before the next coarser one takes over, so a voxel covers a few pixels at 1080p, and the coarsest level reaches about 23 km (`-Dearthplatform.far.detail`, 4 to 24, default 11). Heights are exact as in Distant Horizons: each voxel column carries a drop that lowers its stack so the top of its ground or water sits at the real height instead of a voxel boundary, and side faces split where they meet a neighbour with a different drop. The server builds columns from the same generator with saved cubes folded in, so builds show from afar. The client meshes them into compact faces (16 bytes each: position, size, light and an id into one shared table of block looks) kept in large GPU buffers and drawn by its own shader with one multi-draw per buffer, as Voxy does; it draws per-side walls where a neighbouring column is not drawn so level boundaries have no holes, and draws water as a separate see-through layer filled to its real height.
- Shader packs (Iris) light cube meshes and far terrain: meshes carry the pack's block ids, Iris's shadow pass draws only near opaque cubes, and the pack's `far` distance is raised to the far terrain's reach so its border fog does not hide it. Far terrain is shaded by the pack's own Voxy code (`voxy_opaque.glsl`, which Photon and other packs ship): the client reads it through Iris's preprocessor and calls it for every far fragment, so the pack lights far terrain as it lights Voxy's. Packs without Voxy code get the lit colour in their first terrain target, which looks right only for forward-shaded packs.
- New cubic worlds are the real Earth (`EarthGenerator`): one block is one metre up, and one metre across at the world's home latitude on a Web Mercator grid; sea level is Y 63. Elevation streams from AWS Terrain Tiles with a disk cache under the game folder, zoom 15 for cubes and nearby far levels and coarser zooms for distant ones; open sea comes from the tiles' sea marking with bathymetry below it. Noise adds the small relief the data is too coarse for (more on steep ground, none on beaches). Climate comes from CHELSA V2.1 (1981-2010, 1 km, CC0, streamed by HTTP range with a disk cache): each place's Koppen-Geiger zone, mean and warmest-quarter temperature carried to each column's own height with CHELSA's DEM, and rainfall; worlds pin `earth.climate=chelsa-v2.1-1981-2010`, and older worlds keep a temperature from latitude and height. Zone and land cover together pick the biome, trees and plants. Every block vanilla 1.21.1 grows on the surface is placed where it would grow on Earth (`gen/Vegetation`: flowers, crops, bamboo, cacti in the Americas, cherry groves in East Asia, coral reefs, kelp, ice and snow, banded terracotta). Trees are Minecraft's own: `gen/Trees` grows each biome's vanilla tree features (jungle trees, bushes and giant jungle trees in a jungle, oak, fancy oak and birch in a forest, and so on) on flat ground into shapes it places per column, at a density from the land cover, with bee nests at vanilla's odds; `gen/Sequoia` adds giant sequoias at real size (70 to 94 m) in the Sierra Nevada's real groves. Land cover comes from ESA WorldCover (10 m classes, streamed from its public cloud-optimised GeoTIFFs with a disk cache, coarser overviews for far levels): forests, shrubs, grassland, bare rock and sand, snow and ice, wetlands, mangroves and moss, plus lakes and rivers whose water sits at the surveyed surface with a bed that deepens from the shore. The world is wild land only: built-up ground and bare lots among buildings take the class most common on the nearest ring of wild land around them, and farm fields are grassland. Worlds pin `earth.cover=esa-worldcover-v200`; worlds made before land cover leave it empty and keep noise wetness with no inland water. The home point (default El Capitan Meadow, Yosemite, or `-Dearthplatform.earth.home=LAT,LON`) and scale are pinned in `dimensions/earthplatform/cubic/generator.properties` when the world is made. `/cubes goto <lat> <lon>` travels anywhere (terrain is fetched off the server thread first) and `/cubes where` gives latitude, longitude and height.
- `HighlandsGenerator`, the earlier procedural test world around Y 2400, stays for cubic worlds that saved cubes before the Earth existed. Both share `SurfaceGenerator`, which turns a height, water level and cover per column into cubes with caves, ores, trees and plants and into far columns.

Met in the installed client (NeoForge 21.1.256 with Sodium, Iris and Distant Horizons installed, shaders off): milestone 1's save, act, save, quit, restart and continue loop, and milestone 2's loop of hand-mining wood, crafting at a real table, a wooden pickaxe, mining stone and placing blocks at Y 2408 to 2414. From milestone 4: night-time monsters and first-visit animals spawn, torches and shade count for spawning and crops, and death respawns at a bed or at the cubic world's arrival point.

Far terrain and shaders were checked in the installed client with Sodium 0.8.12, Iris 1.8.14 and Photon v1.3b, and with shaders off: no holes between levels, lakes seamless from near to far water with visible floors, and Photon reflections, shadows and fog over both. Distant Horizons was removed from the test instance because it overrides the fog and draws its own terrain over the cubic world.

Checked in the installed client: entering a new world lands in Yosemite Valley at true height, `/cubes goto` reaches Glacier Point with Half Dome in view, and the far terrain shows the real valley.

Checked in the installed client with the Voxy-style far renderer, with Photon and with shaders off: Glacier Point and Yosemite Valley at true height, 12,800 far columns in about 150 MB of GPU memory, and Photon shading far terrain through its Voxy code. Shaded cliffs look deep blue under Photon in the low settings the test machine runs (no sky light spherical harmonics); near cliffs in shade look the same.

Not yet done on this route: satellite imagery guiding surface blocks; Distant Horizons coexistence; faint seams where two far levels meet on water; temporal anti-aliasing jitter for far terrain under packs that use it; and the Nether and End.

## Product requirements

- A genuine cubic-coordinate Minecraft base, with the one-metre Earth generator as a plugin rather than an ordinary-height visual illusion.
- A continuous player-traversable Earth, with moving terrain windows and durable edits when unloading/revisiting.
- Normal survival, inventory, crafting, health and progression, including ordinary Nether and End portal travel.
- User-selected full-detail native distance, with 12 and 24 chunks as focused acceptance cases, before distant LOD begins. Refuse explicit resource limits rather than silently reducing distance.
- Distant Horizons or Voxy as optional separately installed compatibility paths. Users download vendor artifacts themselves. The existing039 build is pinned to a DH/Iris/Sodium/Photon research stack; optionality and a Voxy path are future work.
- Shader-compatible presentation and a coherent visible/collision/edit state.
- Real-map macro geography with explicitly procedural microdetail driven by climate, biome and slope. Never label invented detail as measured terrain.

## 0. Establish an honest resumable baseline

Preserve integrated69, separate037, all later drafts and the existing pass/fail evidence. Verify the remote publication commit and review the unfinished manifest warnings. Keep the source-only status prominent. No new implementation is needed to claim this documentation milestone.

## 1. Close the minimum save and ownership blockers

Continue from actual read4 rather than repeating earlier read1–3 failures as new discoveries. Resolve ACTIVE-save admission and exact physical close/producer retirement using the original invariants. Compose production epoch/output debits with ordinary-save phase, server MAIN, finite splitter and installed identity/callback approval. Attach the host save-output owner.

Acceptance: a real admitted action can save, perform another action, save again, return/leave cleanly, and reload in a new process with coherent terrain, inventory, player state and ownership. Preserve any unresolved close failure instead of converting a partial successful read test into whole-lifecycle success.

## 2. Deliver one playable client loop

Join immutable true-coordinate client terrain reads, collision/prediction, block updates and ACK reconciliation to server authority and scene completion. Introduce the minimum source-qualified installed MOVE/mining/use-item/placement paths required by the first loop.

Acceptance: ordinary horizontal walking/jumping; hand-mine generated wood; drops move and can be picked up; use real inventory/table screens to craft a wooden pick; place blocks; save/quit/restart and continue. No supplied resources, scripted action calls or GameTest-only scheduling substitute for the ordinary client route. Document unsupported actions visibly.

## 3. Make the selected-distance scene real

Connect the production scheduler, native GPU page ownership and matching distant geometry to the same completed client epoch, current edit revision, camera/depth and shader resources. Existing per-page censuses and modeled-DH GPU work are inputs, not acceptance.

Acceptance: actual client views show complete current terrain/collision at selected12 and24, with continuous movement, edits, distance changes and native-to-LOD transitions. Record genuine screenshots and measured resource/latency behavior. Preserve the user's setting rather than passing by reducing detail.

## 4. Complete the bounded survival progression

Continue through soil/stone excavation, stone tools and a small shelter. Qualify continuous player ticks, health/hunger/damage/death/respawn, item cohorts, menu interruption, scheduled effects, fluids/light/weather, food and mobs. Add caves/ores/resources with disclosed procedural policy.

Acceptance: a fresh normal-inventory survival playthrough reaches a durable shelter and can recover across quit/restart. Tests and ordinary play must agree; component coverage alone does not establish balance or playability.

## 5. Expand continuous Earth travel and source coverage

Replace the bounded Marin milestone with player-centered source windows, streaming, source availability/refusal and durable edits across unload/revisit. Address genuine high-elevation/full-XYZ networking and coordinate safety without rebasing. Expand real macro source coverage; introduce climate/biome/slope microgeneration with attribution and deterministic seeds.

Acceptance: travel across multiple streamed regions, revisit edits, change distance, save/restart and resume at the same true coordinates. Missing data is explicit and never silently fabricated.

## 6. Integrate Nether and End

Use ordinary native generation/height semantics initially for these dimensions; the Earth dimension requires cubic expansion. Define logical-Overworld portal return routing explicitly because native portals normally target `minecraft:overworld`, not the prototype carrier. Keep one survival inventory and progression across portals; ordinary-world entry escrow must not become a per-dimension inventory swap.

Acceptance: normal portal construction, scaling/placement and round trips; inventory and advancement continuity; death/respawn; saving/restarting in each dimension. Dimension creation/save messages and command teleports do not qualify gameplay.

## 7. Broaden renderer options and release qualification

Make DH truly optional, research/implement a separate Voxy shim only if requested, and broaden supported shaders/settings after the main loop works. Keep exact supported versions and failure modes clear; do not distribute vendors. Profile actual hardware, complete reliable installation/package instructions and test supported consumer launch flows.

Release acceptance: reproducible source build, qualified ordinary play, clean repeated saves/restarts, explicit supported configurations, actual hardware performance and honest known limitations. No elapsed-time deadline replaces these gates.
