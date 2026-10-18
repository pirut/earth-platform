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
| `earthplatform.earth.offline` | `false` | Never download; only places already in the cache generate. Same as the `offline` setting | `CubicSettings`, `EarthStatus` |
| `earthplatform.earth.imagery` | `false` | New worlds take ground colors from EOX satellite imagery. Same as the `satelliteImagery` setting | `CubicSettings`, `EarthConfig` |
| `earthplatform.earth.home` | Yosemite Valley | Default home of new worlds, as `LAT,LON` | `EarthSettings` |
| `earthplatform.earth.copernicus` | on | `false` leaves the Copernicus 30 m fill out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.3dep` | on | `false` leaves the USGS 3DEP layer (United States) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.hrdem` | on | `false` leaves NRCan HRDEM (Canada) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.rema` | on | `false` leaves REMA (Antarctica) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.ign` | on | `false` leaves IGN RGE ALTI (France) out of new worlds | `CubicSettings.elevation()` |
| `earthplatform.earth.gsi` | on | `false` leaves GSI (Japan) out of new worlds | `CubicSettings.elevation()` |

A world pins its elevation layers when it is made, so the layer flags only change new worlds.

## Far terrain

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.far.detail` | 11 | How far fine far terrain reaches, 4 to 48 (past 24 is the extreme range). Same as the `detail` setting | `Far.DETAIL` |
| `earthplatform.far.textured` | from settings | Far levels drawn with block textures; coarser ones use flat colors. Same as the `texturedLevels` setting | `FarRenderer` |
| `earthplatform.far.threads` | (cores - 4) / 2, 2 to 8 | Server workers that make far columns | `FarServer.WORKERS` |
| `earthplatform.far.mostThreads` | up to 4x workers | Most far columns made at once while they wait on downloads | `FarServer.MOST_WORKERS` |
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
| `earthplatform.far.cacheCommands` | on | Reuses assembled opaque multi-draw arguments for the exact camera pick; `false` rebuilds each frame | `FarRenderer`, `FarGpu` |
| `earthplatform.far.indexedAllocator` | on | Size-indexed best-fit GPU face allocation; `false` scans free ranges by address | `FarGpu` |
| `earthplatform.far.cacheCoverage` | on | Precomputes exact near-cube coverage per far mesh job; `false` repeats the original cube lookups | `FarMesher`, `FarColumnMesh` |
| `earthplatform.far.batchWaterDepth` | `false` | Experimental ordered water depth batches with original GPU position/offset operands; color draws keep their shader attributes and order | `FarWaterBatch`, `FarLod` |
| `earthplatform.far.strictUpload` | `false` | Experimental fixed 3 ms upload admission cap, without the legacy minimum-eight/adaptive policy; may delay terrain convergence | `FarRenderer` |
| `earthplatform.far.uploadBytes` | 4194304 | Byte allowance per frame with `strictUpload`; first valid mesh always progresses, even if oversized | `FarRenderer` |

## Cubes, loading and networking

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.view.order` | on | `false` loads near cubes by distance alone instead of what the player looks at first | `ViewOrder` |
| `earthplatform.view.behind` | 4 | How many times as far ground straight behind the player counts as | `ViewOrder` |
| `earthplatform.cubic.buried` | `false` | Make every buried cube instead of skipping rock out of sight | `ServerCubeWorld` |
| `earthplatform.cubic.bytesPerTick` | 262144 | Terrain bytes a remote player is sent per tick | `Throttle` |

## Shaders, debugging and measuring

| Flag | Default | What it does | Read in |
| --- | --- | --- | --- |
| `earthplatform.photon.compat` | `auto` | `on` or `off` overrides the check for running Photon on drivers it doesn't fit | `PhotonCompat` |
| `earthplatform.cubic.perf` | `false` | Logs every timing every few seconds | `Timings` |
| `earthplatform.cubic.perfTicks` | 100 | Ticks between perf log lines | `CubicClient` |
| `earthplatform.cubic.trace` | `false` | Logs cube traffic | `Cubic` |
| `earthplatform.map.pace` | 1 | Slows the travel map's flight by this factor, for checking it frame by frame | `EarthFlight` |

When you add a flag, add it here, and keep reading it where it is used, so it is read at the same moment as before.
