Earth Platform build 71: nearby blocks built and uploaded across frames, sharper spyglass views from high up, distant water that hides the seabed, and experimental flight streaming, layered clouds, curved horizons and high-altitude haze.

Needs Minecraft 1.21.1 and either NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 70:

### Nearby blocks
- Near-cube snapshots and mesh uploads now share frame budgets that follow the recent frame time. Uploads share one allowance across the shadow and main passes, and one long frame no longer queues hundreds of snapshots. These budgets are on by default, with no flag. They are intended to reduce hitches after teleporting, fast flight and shader switches; the effect on frame time and how quickly the ground fills in still needs an in-game check.
- A finished mesh that is already out of date can be dropped while an older mesh keeps drawing. The guard allows a mesh through after two consecutive drops, and allows a first mesh through so a cube does not stay a hole. It is on by default, with no flag. Redstone clocks and flowing water still need an in-game check.
- Two optional meshing savings are off by default. On the client, `-Dearthplatform.cubes.reuseSnapshots=true` reuses copies of cube blocks, and `-Dearthplatform.cubes.skipInterior=true` skips meshing sealed stone. Set either to `false` to turn it off. Neither has had a load test. `-Dearthplatform.cubes.checkMesh=true` checks mesher shortcuts against the plain mesher, but cannot catch a stale reused snapshot; check that edits appear at once too.

### Flying over the ground
- With `-Dearthplatform.cubic.flightSurface=true` on the server, flying from 48 blocks over ground or water keeps receiving the real surface blocks below, across the cube view distance (192 blocks at most) and within 384 blocks in a straight line. The cubes right around the player come first. This is off by default; set the flag to `false` to turn it off. Many-player load testing and flight over land and deep ocean are still needed. The world's height is unchanged.
- A column whose terrain survey fails no longer counts all its cubes as buried rock, so those cubes are made as intended. This fixes a Build 70 bug.

### Looking far away
- A spyglass or zoom mod now sharpens a nearby peak when viewed from a plane or mountain top, using the column's own height rather than the valley floor. Repeated zoom limits also keep the original view cone. Both fixes are on by default.
- Coarse far water is nearly opaque, hiding the seabed in distant oceans and lakes without shaders. This is on by default in the plain renderer only. Photon computes its own water alpha, so the seabed can still show through there.
- Smooth far ground and one-block far textures remain off by default. On the client, `-Dearthplatform.far.smooth=true` blends trees into the surface from level 5 out, and `-Dearthplatform.far.blockTextures=true` fades block-sized textures to their average colour with distance. Set either to `false` to turn it off. Block textures compiled and linked in GPU tests without a pack; Photon and in-game checks are still outstanding.
- A curved horizon is off by default. `-Dearthplatform.far.curvature=true` on the client curves far terrain, water and layered clouds from 1,500 blocks above the ground, reaching the full curve at 6,000. Only the picture curves. High-altitude haze is also off by default; `-Dearthplatform.far.atmosphere=true` adds it from 2,000 to 6,000 blocks up. Set either to `false` to turn it off. Both do nothing under a shader pack and still need an in-game check.

### Clouds and weather
- Layered clouds add three banks with their own wind and regional weather. They are off by default. Turn Layered clouds on or off in Mods → Earth Platform → Config, Sodium's Earth page under Sky, or with `layeredClouds = true` or `false` in client settings. Quality and opacity controls are beside it; opacity defaults to 0.72. Under a shader pack, the pack keeps its own clouds.
- Precise far depth helps layered clouds meet distant mountains. It is enabled only while layered clouds draw without a shader pack; `-Dearthplatform.far.plainDepth=false` turns it off. GPU tests passed, but the result and frame cost still need checking in game. The first cloud pass can still use coarse depth.
- Known issues: the middle bank can make a white veil around Y 3,400 to 4,200 at the default cloud height, and the top bank can show straight ribbons. Turning layered clouds off retains about 1 MiB of textures until resource reload. A flight surface cube can keep pale lighting if it meshed before its column tops arrived. See [Flying and far terrain](../../FLIGHT-RENDERING.md) for the remaining checks and stock Photon settings, which have not been tried or timed in game.

### Contributors
- This build's flight and rendering work was contributed by VanceCStone (#161).
