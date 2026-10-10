# Architecture and acceptance contract

## Separation of concerns

The engine core owns cube identity, residency and persistence. Geography supplies a regenerable immutable base. Player edits form a separate authoritative layer. Minecraft-specific integration translates those contracts into actual engine state. Gameplay extensions use explicit cube-aware interfaces rather than modifying data-provider internals.

A cube is 16 by 16 by 16 blocks. Cube coordinates are signed and conversion uses mathematical floor division, including negative block positions. Homogeneous air/rock/water should be implicit or compact. Nearby space is materialized; distant terrain should use geographic LOD data rather than full playable chunks.

## Concurrency and budgets

Every queue, memory cache, disk cache and number of in-flight requests needs an explicit cap and observable counters. Fetching never blocks the game tick thread. Duplicate requests share work while maintaining correct subscriber cancellation. Unloading, teleporting and disconnecting must invalidate stale completions. No data may be lost merely because residency changes.

## Persistence

Pin the generation algorithm and dataset manifest per world. Record source versions, resolution, datum, attribution and quality. Base data can be cached or regenerated, but player edits cannot be silently replaced by newer imagery or maps. Writes are atomic and checksummed; corrupt data is reported and recovery is explicit. Save-format migration must preserve backups.

## Coordinate and vertical contract

World Y represents meters relative to a declared sea-level datum; negative Y represents depth. Minecraft's stock dimension limits and packed coordinates cannot be assumed to support the required span. The adapter must audit ticketing, save codecs, lighting, heightmaps, network masks, rendering and other mods' coordinate assumptions.

A flat map cannot preserve all globe distances and areas everywhere. Projection is an explicit, versioned world choice. One-meter voxel spacing and source survey accuracy are separate properties. Antimeridian, polar handling and tile seams require deterministic tests.

## First integration gate

A benchmarked standalone core is a milestone, not the final product. The first actual Minecraft gate must show place/break and persistent edits at both mountain and trench elevations, then continuous movement across cube boundaries and independent multiplayer residency. A visual renderer or merely enlarged dimension setting does not establish cubic simulation.

## Decisions still under test

- Exact Minecraft/loader version and feasibility of a complete open engine adapter.
- Coordinate projection and compatibility policy for existing mods.
- Lighting summaries that work when distant upper cubes are absent.
- Production-safe free-data access, regional data packs and optional self-hosting.

No external hosting service or arbitrary third-party mod compatibility is promised.

## October 9 implementation checkpoint

The engine target is Minecraft 1.21.1 / NeoForge 21.1.256 / Java 21. The current actual-client prototype uses separately labeled 256-block vertical windows. Canonical Earth Y is displayed independently of engine Y. Those windows are useful terrain and interaction tests, but are not continuous full-height cubic Minecraft.

The independent core has passed signed-coordinate, bounded-residency, asynchronous cancellation, durable-overlay and concurrency tests. The engine audit has reproduced 12-bit packed-Y wrapping, signed-byte section-save limits, eager allocation of all 1242 sections, and a 2.56 MB section payload above the stock 2 MiB column limit. The next work must repair those reached engine paths rather than merely raise the dimension height.

The development client reached its native title screen on the cloud computer. World creation is currently awaiting approval of Minecraft's experimental-world warning. No native gameplay or native save/reload success is claimed at this checkpoint.

Global projection selection remains open. The versioned equirectangular test projection uses one projected meter per block, not uniform ground scale. An isolated, pinned Terra++ modified-Airocean evaluation also found measurable local scale distortion and cut/seam behavior. Do not silently replace the projection of an existing save; regional atlas alternatives and explicit distortion/traversal criteria need evaluation.
