# Builds 58 and 59: speed work

Builds 58 and 59 cut repeated CPU work in far terrain and terrain generation. Each change produces the same output as the code it replaced: the same blocks, faces, face order, textures, colors and fades.

- **Run meshing** (`earthplatform.far.runMesher`, on). Plain far columns at level 3 and coarser, with one biome per column and no near cubes over them, are meshed from vertical runs of blocks instead of block by block. The faces and their order are the same. `FarSurfaceMeshOracleTest` compares both meshers. Anything else uses the block-by-block mesher.
- **Coverage cache** (`far.cacheCoverage`), **GPU space allocator** (`far.indexedAllocator`) and **river sampling** (`rivers.fastSample`): the same results from fewer lookups and allocations.
- **Build 59**: fewer temporary objects in far face and tint lookups and in shore-height queries. Mesher results that finish after you leave a world are now freed instead of leaked. New Earth worlds get a world-list thumbnail once terrain is ready.
- **Performance log**: see [PERFORMANCE-LOG.md](../PERFORMANCE-LOG.md).

On the M4 Pro Mac, an early version of build 58 (with draw-list reuse, since removed) measured about 6% higher frame rate standing still without shaders, and within 1% with Photon or while moving. Build 58 as shipped wasn't measured again. These changes don't promise a general frame rate gain.

Four experiments from this work were removed after review: reusing draw lists, far shadow hints, batched far water depth, and a strict upload budget. Two of them measured slower on the Mac and none was switched on. They are kept on the `archive/codex-experiments-build59` branch. The server-side run generator (`far.surfacePackets`, off) stays, because the run mesher's exactness tests use it.

The full measurement logs are kept outside the repository.
