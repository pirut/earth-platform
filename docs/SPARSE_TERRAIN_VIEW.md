# Sparse terrain-view engine gate

## Scope

A first flyable sparse rendering gate in Minecraft 1.21.1 / NeoForge 21.1.256. The actual Minecraft spectator camera moves at native Earth coordinates. An empty stock-height carrier dimension (min Y −64, height384) hosts the camera. No full-height level is installed into stock `ViewArea` / chunk render arrays. This gate is visual-only: cubic collision, native edit interaction, and integrated cubic world saving are the next engine gates.

Near terrain is generated into immutable admitted 16³ engine cube snapshots, then compiled with actual Minecraft baked block models and uploaded through Minecraft `MeshData` / `VertexBuffer`. Far geometry uses unchanged official Distant Horizons terrain shaders and actual DH builder bytes in an isolated runtime. It does not claim a stock DH mod compatibility release. Diagnostic illumination is fixed; no completed native lighting or biome engine is claimed.

The accepted Earth terrain is an engine stress fixture. The core's existing `CubeProvider` accepts full 3D immutable cubes and supports caves/overhangs; this fixture's `SceneSurfaceSampler` is only a heightfield summary adapter. The next integration should bridge the existing `BoundedCubeLoader`, `CubicWorld` and `FileEditStore`, paired with analytic flat/slope/void/overhang/extreme-Y fixtures. Do not turn the heightfield fixture into the generic generation contract.

## Bounds and identity

- Native envelope Y −11,000..+8,849, cube-aligned allocated envelope −11,008..+8,863.
- At most256 near owners including explicit 26-neighbor halos. A 3×3-column surface interest footprint spans48m, with a128m distance gate. No unknown neighbor is treated as air.
- At most2 synchronous source cubes generated and1 native mesh compiled per frame. The4ms source budget is a soft check between jobs, not a hard real-time guarantee. Timings are reported.
- Native meshes are privately prepared. A whole-batch far clipping transaction must succeed before near publication; retiring/out-of-range near coverage is unmasked first. Published empty meshes carry real readiness.
- A complete paired near/far frame is fenced against off-thread resource invalidation. Native draw dependencies additionally fence all halo revisions against admission/retirement.
- Far summaries use4096 source samples at16m spacing within a fixed512m half-width. At most64 published signed-page/tile owners and32768 faces; atomic replacement may hold a further64 private owners (128 hard peak). All session resources must retire at close.
- World/source/appearance, connection epoch, cube XYZ, lease generation, revision, and resource generation remain explicit. Double subtraction precedes float camera-relative conversion.
- The512m far square remains centered on scene entry; `/earth_scene resume` creates a fresh epoch centered on the current camera. This gate does not yet provide a moving far ring or huge-distance support.

## Source fixture

Pinned Copernicus GLO-30 DSM, native1arcsecond source resolution, EGM2008 heights; pinned measured WorldCover classifications; accepted presentation005 regional relief/material policy. The source sample remains separate from bounded cosmetic microrelief. Unknown cover is explicitly magenta; unresolved water cover is cyan without invented hydrology. Missing elevation/derivative coverage is deferred, never clamped or filled.

Global equirectangular coordinates use one projected metre per block, not a uniform global ground-metre guarantee. At the initial X9,674,928 / Z−3,112,479, source elevation is7500.738714689942m. All4096 far centers plus four ±8m derivative reads are inside the checksum-verified source coverage (20,480 successful reads). Far-source heights range approximately6617–7601m.

## Manual native QA

1. Launch the frozen `minecraft/native-terrain-view-001/launch-client.sh` from the cloud Terminal. Create a fresh disposable creative world with cheats, accepting the already-authorized experimental world warning.
2. Run `/earth_scene enter`. Expect actual camera Y around7536m, fixed-source far terrain promptly, then native textured terrain after bounded halo/mesh preparation. The HUD explicitly labels the visual-only carrier and budgets.
3. Move using ordinary spectator flight (WASD/mouse, Space up, Shift down; mouse wheel adjusts spectator speed). Stop briefly after crossing16m sections and inspect completed near batches. F3 should show actual native position. F1 hides the diagnostic HUD for a clean actual screenshot.
4. `/earth_scene resume` must close all old owners, create a fresh epoch and rebuild the view around the current native camera.
5. F3+T invokes real resource reload in this normal world view, closes all scene resources, and prompts `/earth_scene resume`. Rebuild and inspect again.
6. `/earth_scene leave` returns to the recorded original dimension/position/mode and closes the scene. Re-enter and leave again to test disposal/reopen. Save and close the client before modifying its frozen launch.

Actual screenshots/JSON are written to the frozen client's `minecraft/run/earthplatform-evidence/sparse-terrain-view`. Check `FAR_VISIBLE`, `NEAR_FAR_READY`, `CLOSED`, known/unknown far cells, exact coverage readiness, owner peaks, resource cleanup and CPU timings. Mesa llvmpipe results are software-OpenGL evidence, not hardware-performance proof.
