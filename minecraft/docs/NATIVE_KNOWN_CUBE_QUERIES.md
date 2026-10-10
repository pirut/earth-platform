# Native known-cube query primitive (next gate)

This is source for the next cubic-engine gate. It does not change the frozen native-core runtime launch, install player hooks, or claim playable full-height physics.

## Boundary and use

`NativeKnownCubeQueries` owns an immutable bounded query view decoded from already-admitted `EngineCubeSnapshot` values. Construct it with the exact `WorldIdentity`, connection/actor epoch, verified `EnginePaletteContext`, and a map of full `CubePos(x,y,z)` keys to snapshots. It accepts zero through 125 owners. A map-key, world, epoch, palette, or numeric-state mismatch is rejected during preparation. Leases and revisions remain independent per cube.

- `raycast(Vec3 start, Vec3 end, CurrentStamp current)` returns `HIT`, `CLEAR`, or `UNKNOWN`, an optional native `BlockHitResult`, and sampled dependencies.
- `overlaps(AABB body, CurrentStamp current)` returns the same three statuses, a deterministic first overlapping block if present, and sampled dependencies.
- `result.dependencies().isCurrent(current)` supports revalidation immediately before authoritative consumption.

`CurrentStamp` is caller-supplied. It must compare world, epoch, full cube address, lease generation, and exact revision against current ownership. It can adapt either server-actor ownership or client admission without either being a dependency of this primitive. A test's always-true predicate is not a production admission check. The view grants no residency and cannot establish admission itself.

Every state read is checked, including a required one-cell collision halo, and the entire sampled dependency set is checked again before returning a successful result. Missing owners, stale stamps, registry changes, unsupported states/shapes, and reads outside the bounded native domain return `UNKNOWN` with the offending cube and explicit reason. An empty known cube is genuine air. A missing cube never becomes air. An `UNKNOWN` result contains no usable hit or collision position.

Revalidation alone is not an atomic transaction. The authoritative owner must serialize the final dependency check with mutation submission and compare the expected target revision. A result must not be reused across an ownership/revision change. The immutable view remains unchanged after an edit; prepare a replacement snapshot/view at the acknowledged revision.

## Geometry and supported scope

All geometry comes from the registered `BlockState.getCollisionShape` via a private scoped `BlockGetter`. That getter has no air fallback; a native shape's additional state/fluid dependency would have to be present and current too. Block-entity queries are explicitly unsupported. Every traversed/overlapped voxel also audits all 26 adjacent voxels: an absent or unsupported halo state makes the result unknown. This prevents a fence below a known-air voxel from silently losing its protruding upper collision shape. The conservative halo may reject a query even when the unsupported neighbor would not actually touch it. The current 125-owner/27-inner-target layout already provides a cube halo. Arbitrarily oversized modded shapes extending beyond this bounded halo are outside the supported fixture policy, not a compatibility claim.

This gate intentionally audits only AIR/CAVE_AIR/VOID_AIR and the opaque full-block fixtures: STONE, DIRT, GRASS_BLOCK, GRAVEL, SNOW_BLOCK, ANDESITE, DIORITE, TUFF, CYAN_TERRACOTTA, MAGENTA_TERRACOTTA, GOLD_BLOCK, and BEDROCK. Their actual native shapes are checked to be empty for air or exactly one unit box for a supported solid. Other registered blocks are `UNKNOWN`, including slabs, fences, doors, fluids, snow layers, plants with empty collision, and block entities. Adding support requires an explicit geometry/context audit and tests. The primitive does not pretend that `CollisionContext.empty()` reproduces entity-specific physics.

Rays use bounded full-3D voxel traversal and an exact segment/slab intersection against boxes obtained from the native `VoxelShape`. Computation is block-relative double precision with no float conversion, Y remapping, heightfield lookup, or packed `BlockPos` keys. The segment has positive length and at most 8 metres; its endpoint is inclusive. A start inside solid returns a native hit at distance zero with the opposite dominant travel direction. At an exact voxel boundary, travel direction chooses the entered owner; an axis parallel to a boundary uses the floor-side voxel. Edge-only contact with other voxels is not sampled. These deliberate exact-segment conventions avoid the small displacement used by vanilla `VoxelShape.clip`; this is not a claim of byte-for-byte vanilla ray-pick epsilon behavior. It queries collision shapes, not fluid or visual-selection outlines.

Body bounds must have positive volume and fit within 1 x 2 x 1 metres. Collision is static positive-volume overlap: merely touching a face does not overlap. Every voxel intersected by the body is checked before returning, so a missing part of the body wins over an overlap already found. This is not swept movement, collision response, stepping, gravity, grounding, entity collision, or vanilla movement validation.

The query domain uses the native snapshot's lossless X/Z range [-33554432,33554432) and padded native Y [-11008,8864), including real signed Y -11000 and +8849. Out-of-domain reads are unknown. Nonfinite, zero-length, over-reach, oversized, or non-positive-volume requests are rejected as invalid query inputs. The ray cell budget is 32; an 8m segment cannot cross more than 8√3 total axis boundaries plus its initial/boundary cells. The body directly visits at most 12 cells (80 unique cells with its halo). Ray traversal audits at most 32 x 27 cells, with a per-query shape cache preventing duplicate native shape calls. There are no unbounded jobs, queues, tickets, stores, providers, global hooks, or scene-heightfield coupling.

## Verification

Focused `NativeKnownCubeQueriesTest` coverage includes:

- Actual analytic full-3D overhang: known air underneath, upward roof hit, body clearance/overlap
- Known air versus unknown owners, missing space before a far solid, and unknown space behind an already-occluding hit
- Independent negative X/Y/Z cube seams and sampled dependencies
- All six hit faces, exact 8m reach, just-short reach, starting inside, exact boundary departure/entry, sub-nanometre nonzero ray
- Signed native Y -11000/+8849 at Earth-scale signed X/Z, preserving fractional hit coordinates
- Strict AABB overlap, face contact, owner seam, and unknown dominating partial known collision
- Explicit rejection of water, slabs, fences, chest, and an empty-collision plant; all three known-air variants
- Protruding native-fence regression above its owning voxel and across a vertical cube seam, including a missing lower halo cube
- World/epoch/lease/revision changes, eviction, invalidation after the final shape read, immutable dependency results
- Explicit-air edit replacement and immutable captured input
- Durable DIORITE edit saved, store reopened, exact revision materialized, and ray/body queries at Y -11000
- 125-owner admission bound, key/epoch rejection, invalid geometry, and outside-domain unknown

Run through the pinned Java 21 / Minecraft 1.21.1 / NeoForge 21.1.256 JUnit launcher, for example:

    minecraft/scripts/gradle.sh --offline -x downloadAssets -x createMinecraftArtifacts test --tests io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueriesTest

This no-download rerun assumes the pinned artifacts and test assets were already prepared; excluding both artifact and asset tasks is intentional because the asset task can refresh its index even with Gradle offline. An isolated `/tmp` probe can use those existing cached dependencies without changing any frozen runtime. Headless tests do not verify player hooks or live server movement.

## Narrow next server-player gate

1. Keep the current frozen rendering launch unchanged. Add a separately opted-in disposable analytic-overhang world proof with one selected server player and a capped owner neighborhood with the required known collision halo.
2. On the existing actor owner, prepare a bounded snapshot view outside the server tick and publish it immutably. Wire `CurrentStamp` to that actor's admitted world/epoch/lease/revision metadata. No new provider, store, or global task queue is needed.
3. First use only server-authoritative short-reach hit validation and diagnostic body-overlap checks. For an edit, recheck every sampled dependency in the actor's serialized command, verify the expected target revision, and invoke the existing bounded edit command. Apply a replacement snapshot before accepting its acknowledgement as current.
4. On `UNKNOWN`, defer/reject the action and request the already-supported bounded residency path. Do not treat absent data as empty terrain, teleport the player through it, or fall back to a heightfield.
5. Test stale leases/revisions, independent upper/lower cubes, overhang selection, durable diorite/air replacement, and disconnect/close. Only after that passes should a separate gate design swept collision and actual movement response with player context. This static primitive alone cannot safely replace vanilla player physics.
