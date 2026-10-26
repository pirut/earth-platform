# Developer flags

Earth Platform reads a few Java system properties, given as JVM arguments such as `-Dearthplatform.far.threads=4`.
Players never need them: the settings files and menus cover everything a player changes (see [PLAYING.md](PLAYING.md#settings)).
They are for testing, measuring and comparing against older behavior.

Most are read once when the class that uses them loads, so set them on the command line, not at runtime. Four of them
(`far.detail`, `far.textured`, `earth.offline`, `earth.imagery`) are also set by the settings files through
`EarthConfig`; a value given on the command line wins over the file.

## World data

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.rivers.fastSample` | on | `false` restores the allocating per-pixel strength sampler and per-cell nearby maxima lookups for exact-output/performance comparisons | `RiverMap` |
| `earthplatform.cogs.fastRead` | on | `false` restores per-pixel block lookups and per-pixel overview shapes when 3DEP, REMA and HRDEM files are read; the values read are identical | `FloatCogs` |
| `earthplatform.climate.rowChunks` | on | `false` keeps CHELSA climate rows in memory whole (320 of them) rather than as 2048-column chunks in the same memory; the far levels re-decoded whole rows thousands of times. Values are identical | `ClimateSource` |
| `earthplatform.copernicus.fastWeight` | on | `false` makes the Copernicus fill ask the shared caches for each tile pixel's check size and part, and wraps longitudes with remainders only; the weights are identical | `CopernicusFill`, `CopernicusSource` |
| `earthplatform.earth.offline` | `false` | Never download; only places already in the cache generate. Same as the `offline` setting | `CubicSettings`, `EarthStatus` |
| `earthplatform.earth.imagery` | `false` | New worlds take ground colors from EOX satellite imagery. Same as the `satelliteImagery` setting | `CubicSettings`, `EarthConfig` |
| `earthplatform.earth.home` | Yosemite Valley | Default home of new worlds, as `LAT,LON` | `EarthSettings` |
| `earthplatform.earth.copernicus` | on | `false` leaves the Copernicus 30 m fill out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.3dep` | on | `false` leaves the USGS 3DEP layer (United States) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.hrdem` | on | `false` leaves NRCan HRDEM (Canada) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.rema` | on | `false` leaves REMA (Antarctica) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.ign` | on | `false` leaves IGN RGE ALTI (France) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.gsi` | on | `false` leaves GSI (Japan) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.cubes.fastBlobs` | on | `false` restores ore and rock blobs' per-block sphere sums, their writes of blocks that are already the vein's, and the walk through the spheres of blobs that cannot reach the cube; the cubes are identical | `Underground` |
| `earthplatform.cubes.plainRandom` | on | `false` draws each cube's veins, cave plants and structure plans, and the random numbers block models ask for when near cubes are meshed, from vanilla's legacy random source (an atomic seed) instead of the plain one; both give the same numbers | `Underground`, `Structures`, `CubeMesher` |
| `earthplatform.cubes.fastLight` | on | Lights a cube's 48-block neighborhood filling cubes of one state at once, starting the skylight's spread from each column's lowest sky and its neighbors' instead of looking at every block for a darker neighbor, and looking for light sources only in cubes whose palette holds one; `false` goes over every block, for comparing. The same light, on the server and in near cube meshes | `CubeLight` |

A world pins its elevation layers when it is made, so the layer flags only change new worlds.

## Far terrain

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.far.fastReach` | on | `false` restores the shrinking flood queue, a fresh mark array per flood and a land cover lookup per sample in the water reach windows (`reachLevels`); output is identical | `EarthGenerator` |
| `earthplatform.far.fastDrowned` | on | Columns ask the water reach windows for reservoirs (drowned ground) only where one of the four water samples round them is mapped as water by the land cover, or is land with water mapped within six samples two of its four ways; nowhere else can one be found. `false` asks for every column of a cell near a reservoir-sized patch of water, for comparing; output is identical | `EarthGenerator` |
| `earthplatform.far.detail` | 11 | How far fine far terrain reaches, 4 to 48 (past 24 is the extreme range). Same as the `detail` setting | `Far.DETAIL` |
| `earthplatform.far.textured` | from settings | Far levels drawn with block textures; coarser ones use flat colors. Same as the `texturedLevels` setting | `FarRenderer` |
| `earthplatform.far.threads` | (cores - 4) / 2, 2 to 8 | Server workers that make far columns | `FarServer.WORKERS` |
| `earthplatform.far.mostThreads` | workers + 24, up to 64 | Most far columns made at once; only `far.threads` of them use the processor, the rest wait on downloads | `FarServer.MOST_WORKERS` |
| `earthplatform.far.inFlight` | 192 | Far columns a client asks for before earlier ones arrive | `FarRenderer` |
| `earthplatform.far.visibility` | edge of the far view | Haze distance in meters, as a real-world visibility | `FarRenderer` |
| `earthplatform.far.fade` | on | `false` makes far levels meet at a seam instead of fading into each other | `FarRenderer` |
| `earthplatform.far.smooth` | `false` | Draw far ground smooth instead of blocky | `FarMesher` |
| `earthplatform.far.smoothFrom` | 1 | With `far.smooth`, the first level drawn smooth | `FarMesher` |
| `earthplatform.far.coverFrom` | 2 | The finest far level whose ground cover is evened out | `FarMesher` |
| `earthplatform.far.buildFill` | 8 | How much of a coarse voxel a player build must fill to show far away, as 1/N | `FarBuilds` |
| `earthplatform.far.lodDepth` | on | `false` makes far terrain share the game's depth buffer under shader packs | `FarLod` |
| `earthplatform.far.indexed` | on | `false` draws six corners per face instead of indexed faces | `FarGpu` |
| `earthplatform.far.splitRanges` | `false` | Draws every group as its own range, for comparing | `FarGpu` |
| `earthplatform.far.trackBytes` | on | `false` sums every column for each memory query, for comparing | `FarRenderer` |
| `earthplatform.far.cachePick` | on | `false` rebuilds selection, sorting and face groups every frame, for comparing | `FarRenderer` |
| `earthplatform.far.fastWater` | on | `false` restores full water scans and uniform updates, for comparing | `FarRenderer`, `FarLod` |
| `earthplatform.far.sharedWater` | on | Keeps every far column's water (surfaces and walls) in one vertex buffer per vertex format, so each far water pass binds it once and draws column after column from it with a base vertex, instead of binding each column's own buffers before its draw; `false` gives each column's water buffers of its own again, for comparing. The same triangles in the same order | `FarWaterBuffers`, `FarRenderer` |
| `earthplatform.far.batchStamps` | on | Uploads the fade stamps selection writes once it ends, adjacent slots in one call; `false` uploads each stamp as it is written, for comparing | `FarGpu` |
| `earthplatform.far.nearGround` | on | While the ground near the player loads (joining a world, arriving somewhere new), far terrain inside the game's blocks' reach draws only where those blocks drew nothing, from a copy of the game's depth taken before any far terrain: coarse far voxels standing in under the player, and voxels not yet cut away where blocks have come, no longer cover the blocks at the player's feet. Once that ground has loaded (and 2 s after), no copy is taken and far terrain draws as before. `false` always draws it as before | `FarRenderer`, `FarBlockDepth`, `FarGpu` |
| `earthplatform.far.skipNear` | on | Under a shader pack with Voxy support, leaves out of the far pass the columns that lie wholly inside the game's blocks' reach, where that pass draws no pixel of them anyway (the near pass still draws them). `false` draws them, for comparing | `FarRenderer`, `FarNearSkip` |
| `earthplatform.far.depthPrepass` | `auto` | Under a shader pack with Voxy support, draws far terrain in two passes: depth first (the same dropped fragments and slope depth as the one-pass draw), then the pack's shading tested equal against the depth of the faces the first pass kept, so hidden far faces are no longer shaded. Packs whose Voxy code mentions `discard`, `gl_FragDepth` or `gl_SampleMask`, or a blended draw, stay on the one-pass draw. Draws the same pixels. `auto` turns it on except on Apple GPUs, where build 61 measured it slower; `true` or `false` forces it | `FarGpu`, `FarLod` |
| `earthplatform.far.replayPrepass` | on | With the depth pre-pass, the shading pass draws the ranges the pre-pass drew instead of queueing every column again; `false` queues them again, for comparing | `FarGpu` |
| `earthplatform.far.linkColumns` | on | Each far column keeps links to the held columns a level finer under it and beside it, so selection walks the pyramid without a hash lookup per step; `false` looks each one up, for comparing. The selection is the same | `FarRenderer` |
| `earthplatform.far.reuseDraws` | on | While the far pick stands (the view and the columns as they were), each far pass draws again the ranges it queued the frame before instead of going over every column; `false` queues them every frame, for comparing. The same draws | `FarRenderer`, `FarGpu` |
| `earthplatform.far.hiddenCull` | on | With the depth pre-pass and OpenGL 4.3, leaves out of the shading pass the ranges of far faces that the pre-pass shows lie wholly behind other far terrain, tested on the GPU against a pyramid of the pre-pass's depth with room for jitter and rounding; `false` shades every range, for comparing. The same pixels | `FarHiddenCull`, `FarGpu` |
| `earthplatform.far.reuseWalk` | on | Keeps the far pick's walk of the pyramid (which columns draw, their fades and places) while the camera stays in the same level 2 far column (64 blocks across), the finest level and sharpness stay, and no column it draws is dropped or sent again; in between only what is on screen is worked out again. Columns meshed since, and fades that ended, are taken in at the next walk, a tenth of a second later at most. `false` walks the pyramid whenever the view changes, for comparing | `FarRenderer` |
| `earthplatform.far.checkWalk` | `false` | Also walks the pyramid afresh every frame and logs a warning naming the columns whenever the kept walk would draw different ones, for testing `far.reuseWalk` | `FarRenderer` |
| `earthplatform.far.quietMeshes` | on | With `far.reuseWalk`, a column meshed that the kept walk does not draw walks the pyramid again only when that changes what the walk draws: where the walk now reaches the column, a column over it now has all its children ready, or the column it stops at is fading back in over its children. A column meshed again, or one of several children still coming under a column that stands in for them, changes nothing. `false` walks again within a tenth of a second for every such mesh, for comparing. The same picture | `FarRenderer` |
| `earthplatform.far.coarseWalks` | on | With `far.reuseWalk`, while the finest far level drawn is a coarser one (high up, or as fast as the jet stream or an ocean current), the kept walk waits for the camera to enter another column of the level above the finest instead of another level 2 column, unless the last walk came to a column at or below the finest level not yet meshed: only those ask where the camera is at a finer level. `false` walks at every level 2 column, for comparing. The same picture | `FarRenderer` |
| `earthplatform.far.sentInPlace` | on | With `far.reuseWalk`, a column the kept walk draws that the server sends again (remade after blocks changed in it, as when water settles in new ground) takes the place of the one it replaces in the walk, with that one's faces and fades, instead of walking the pyramid again; `false` walks again, for comparing. The same columns drawn | `FarRenderer`, `FarCullTree` |
| `earthplatform.far.partialWalks` | on | With `far.quietMeshes`, a walk due only for columns meshed or fades ended walks again just the branches of the pyramid they change: under the column a fade was seen at, or under the column the walk stopped at on its way down to a column meshed. The columns drawn there take the place of the ones drawn before, in the walk's order, and the columns beside them work out again what they note of them; once the columns they took the place of outnumber the ones the walk draws, the whole pyramid is walked again. `false` walks all of it, for comparing. The same columns drawn | `FarRenderer`, `FarCullTree` |
| `earthplatform.far.movePatches` | on | With `far.partialWalks`, the camera entering another column of the level the kept walk follows walks again just the branches that change with it too: at each level whose column under the camera changed, the ones the walk reached of the columns that come into or leave the middle the next finer level draws instead (or its outer ring, where a shader pack fades it), and for such a column not meshed yet the nearest meshed one over it, whose children being ready may turn on it. A move of more than 4 columns of a level, or into another top level column, walks the whole pyramid. `false` walks all of it, for comparing. The same columns drawn | `FarRenderer` |
| `earthplatform.far.maskChanges` | on | Each far mask (which near cubes each far column leaves out, as the near terrain draws them) starts from the last one: the cubes drawn, gone or sunk since change only the columns they are inside, and only columns received since have their cubes worked out whole. `false` works every column's cubes out afresh each time, for comparing. The same cubes for every column | `FarRenderer` |
| `earthplatform.far.checkMask` | `false` | Also works every far column's cubes out afresh after each mask and logs a warning naming any column whose cubes differ, for testing `far.maskChanges` | `FarRenderer` |
| `earthplatform.far.reuseOrder` | on | Each view of the far columns puts them in order (nearest first) starting from the last view's order while the walk stands, setting right only what the camera's move changed and merging in the columns that came into view; `false` sorts them afresh every frame, for comparing. The same order | `FarRenderer`, `FarOrder` |
| `earthplatform.far.indexedAllocator` | on | Finds free GPU space by size; `false` scans free ranges by address, for comparing | `FarGpu` |
| `earthplatform.far.cacheCoverage` | on | Works out once per far column which parts near cubes cover; `false` looks them up per block, for comparing | `FarMesher`, `FarColumnMesh` |
| `earthplatform.far.runMesher` | on | Meshes plain far columns (level 3 and coarser, one biome per column, no near cubes) from vertical runs of blocks, with the same faces in the same order; anything else uses the block-by-block mesher. `false` always uses the block-by-block mesher, for comparing | `FarMesher` |
| `earthplatform.far.recentLooks` | on | Each far mesher thread remembers the last few hundred looks (sprite, color and tint id) it was given for a block, side and biome, and asks the table every mesher shares, under its lock, only for ones it has not seen. A look and its id never change once given, so the faces are the same; `false` asks the table for every face, for comparing | `FarLooks`, `FarColumnMesh` |

## Cubes, loading and networking

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.view.order` | on | `false` loads near cubes by distance alone instead of what the player looks at first | `ViewOrder` |
| `earthplatform.view.behind` | 4 | How many times as far ground straight behind the player counts as | `ViewOrder` |
| `earthplatform.cubic.buried` | `false` | Make every buried cube instead of skipping rock out of sight | `ServerCubeWorld` |
| `earthplatform.cubic.bytesPerTick` | 262144 | Terrain bytes a remote player is sent per tick | `Throttle` |
| `earthplatform.cubes.skipBuried` | on | Meshing a near cube leaves out, before the model pass, blocks whose six faces vanilla's own face check hides and whose model is one of vanilla's plain kinds with no faces that are never hidden: the model pass would draw nothing for them, after asking the model for each face's quads. Below the ground that is nearly every block. Models of other kinds, such as a mod's connected textures, always go through the model pass. `false` sends every block through it, for comparing. The same vertices | `CubeMesher` |
| `earthplatform.cubes.reusePose` | on | Meshing a near cube draws each block with one pose set to its place, the same matrices as a copy of the identity pose pushed and moved there; `false` pushes a copy for every block, for comparing | `CubeMesher` |
| `earthplatform.cubes.checkMesh` | `false` | Also meshes and lights every near cube the plain way (`cubes.skipBuried`, `cubes.reusePose`, `cubes.plainRandom` and `cubes.fastLight` off) and logs a warning naming any cube whose light or vertices differ, with a count every 1,000 cubes, for testing them. Under a shader pack, vertex normals are counted apart: Iris works a quad's normal out again only while the render thread is drawing the level, so a mesh made twice can differ there either way | `CubeMesher` |
| `earthplatform.cubic.lightmapRing` | on | In cubic worlds without a shader pack, uploads the game's lightmap to three textures in turn, so an upload never waits on frames still drawing with the last one; the same pixels are drawn. `false` always uploads to the one texture, for comparing | `CubicLightmapTexture` |

## Shaders, debugging and measuring

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.photon.compat` | `auto` | `on` or `off` overrides the check for running Photon on drivers it doesn't fit | `PhotonCompat` |
| `earthplatform.far.surfacePackets` | `false` | Server prototype: generates plain far columns (levels 3 to 8) as vertical runs, then turns them back into normal sections, so nothing sent or saved changes. Not the client's `far.runMesher` | `SurfaceGenerator` |
| `earthplatform.far.gpuTimers` | `false` | Times far terrain GPU passes with OpenGL queries and adds them to the performance log. Off for players because the queries wrap other mods' and shader packs' draws | `FarGpuTimers` |
| `earthplatform.far.gpuSampleEvery` | 120 | Frames between GPU timings with `gpuTimers` | `FarGpuTimers` |
| `earthplatform.perfLog` | on | Writes a small local performance log (frame times, GPU and driver, graphics settings, terrain workload; no names, places or paths) to `earthplatform/perf/latest*.jsonl` in the game folder, at most three 4 MiB files. `false` turns it off | `BuildInfo`, `PerformanceDiagnostics` |
| `earthplatform.cubic.perf` | `false` | Logs every timing every few seconds | `Timings` |
| `earthplatform.cubic.perfTicks` | 100 | Ticks between perf log lines | `CubicClient` |
| `earthplatform.cubic.trace` | `false` | Logs cube traffic | `Cubic` |
| `earthplatform.map.pace` | 1 | Slows the travel map's flight by this factor, for checking it frame by frame | `EarthFlight` |

When you add a flag, add it here, and keep reading it where it is used, so it is read at the same moment as before.
