# Snapshot reuse and enclosed-cube preview

Includes the near-cube, spyglass, horizon and cloud changes from prior previews.
This pass reduces duplicate work rather than lowering visible terrain detail.

## Changes

- Client-thread-owned LRU retains at most 1,024 copied block-state containers.
  Adjacent mesh jobs share these read-only copies. The cache detects replacement
  container identities and invalidates entries on received/rewritten cubes,
  block changes and unloads. Resource/shader remeshes and world exit clear it.
  A worker holding an old snapshot keeps its immutable old copy after an edit;
  new jobs get the updated data. Biomes and sky-top snapshots retain their
  previous handling.
- A tightly guarded interior fast path skips lighting/model work for stone or
  deepslate cubes surrounded on all six sides by full stone/deepslate cubes.
  It requires known sky tops at or above the centre cube, no block entities,
  and exact SimpleBakedModel models without unconditional quads. Custom/weighted
  models, cave openings, fluids, emitters and missing neighbours use the normal
  path. No world data or collision is discarded. Changes exposing faces dirty
  affected meshes through the existing system.
- Existing local performance logs now include `cube_snapshot` and `cube_light`
  timing stages, cache occupancy/hits/copies, and a cumulative interior-skip
  counter. Logs stay local, bounded and asynchronous under `earthplatform/perf`.

The snapshot cache can be disabled for comparison with
`-Dearthplatform.cubes.reuseSnapshots=false`. Existing
`-Dearthplatform.cubes.skipBuried=false` also disables the interior shortcut.
The existing optional `cubes.checkMesh` diagnostic compares optimized and plain
meshes in a running game; it is expensive and is not enabled by this preview.

## Verification

The adjacent-job test copies all blocks through the fresh and cached paths.
Its 27 jobs make 729 neighborhood requests but only 125 container copies (604
hits, about 83% fewer copies). This is a synthetic workload, not an FPS claim.
Tests also cover edits while old snapshots remain in use, replacement, LRU
eviction, unload/reset, missing neighbours, cave/fluid/emitter changes, and
exact centre-light agreement with both existing lighting implementations.

The full mod and real-driver shader regressions run without launching Minecraft.
No view distance, LOD selection, textures, exposed geometry or gameplay limits
are reduced by this pass. Broad occlusion culling, general incremental lighting,
and nearby draw-call batching are separate future changes.

November 9 verification: 254 tests passed, five optional benchmarks skipped,
no failures. Six new snapshot/interior checks and seven existing real-driver
tests passed. In-game frame rate has not been measured for this artifact.

Artifact: `minecraft/build/flight-preview/earthplatform-snapshot-reuse-preview.jar`.
SHA-256: `aecc272d74160c5ec1c76a8f8c88a3e728a9a66f9081fedf05c2669b328f8f60`.
