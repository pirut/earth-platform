# Independent cubic-world core

Java 21, no third-party libraries. This is an engine-independent implementation and executable test suite, **not a completed Minecraft cubic-height mod**. It does not implement Minecraft lighting, entities, networking, mesh rendering, fluids, ticking, or vanilla chunk replacement.

## Verify

From the project directory:

```sh
./core/test.sh
./core/test.sh --benchmark
```

The runner uses `JAVA_HOME`, then the project's verified local JDK if present, otherwise `java`/`javac` on `PATH`. Compilation targets Java 21 and treats every lint warning as an error. Tests throw on failure without relying on `-ea`. Build output is under `core/build/`.

## Contracts

- `CubePos(int x, int y, int z)` addresses independent 16³ cubes. All three axes are signed; no surface-relative Y remapping occurs. Block coordinates are `long`, with checked conversion into the signed-int cube domain. Supported blocks are −34,359,738,368 through +34,359,738,367 on each axis. `floorDiv`/`floorMod` correctly handle negative boundaries.
- Y=8,849 is cube Y=553, local Y=1. Y=−11,000 is cube Y=−688, local Y=8. Local indexing is X-fastest, then Z, then Y: `(y*16+z)*16+x`.
- `ImmutableCube` stores one nonnegative integer state for a homogeneous cube, otherwise 4,096 integer states. Input/output arrays are defensive copies. Reported payload sizes, 4 or 16,384 bytes, exclude JVM object/array/map overhead and are not heap measurements. Block state 0 conventionally denotes air.
- `CubeProvider.load(CubePos, CancellationToken)` returns a `CompletableFuture<ImmutableCube>`. Implementations are thread-safe and deterministic for pinned inputs. They own tile fetching/caching, source provenance, retries and projection. A request is for one cube, not one external request per block. See the separate geography module.
- `WorldIdentity` pins seed, generator ID, generator version, dataset version and block-palette version. Dataset or palette changes require explicit migration. Never persist Minecraft's transient numeric registry IDs without a stable palette or validated mapping fingerprint.

## Bounded streaming

`CubeTickets` tracks atomic unions of named player-centered boxes. A box contains `(2h+1)²*(2v+1)` cubes, where `h` is horizontal radius and `v` is vertical radius. Overlap consumes one resident slot, even when multiple players refer to it. Ticket count and total resident count are independently bounded. Invalid, overflowing or over-budget updates leave the original tickets unchanged.

`BoundedCubeLoader` bounds active work, queued work and subscribers per cube. Requests for the same cube share generation but receive separate leases. Cancelling one lease does not cancel another. Cancelling the last lease signals the provider. Running work retains its slot until the provider actually settles, even if it ignores cancellation; this avoids unbounded orphan work. An uncooperative or stalled provider can still occupy the bounded slots indefinitely. The caller owns the supplied execution pool; its jobs must not run on Minecraft's tick thread. A dispatch trampoline prevents recursion when thousands of queued jobs are rejected during executor shutdown.

`CubicWorld` composes tickets, loader and persistent overlays:

1. `updateTicket(...)` stages the complete residency change atomically before publishing callbacks. Reentrant completion callbacks cannot add stale, unticketed residents.
2. `ready(cube)` resolves once generation and overlay reading finish. `view(cube)` returns the latest immutable snapshot; an earlier ready snapshot does not change after edits.
3. `getBlock(...)` is a memory-only lookup and rejects missing/not-ready cubes.
4. `setBlock(...)` admits a bounded asynchronous, write-through edit. Success means the save completed under the store's durability policy. Accepted writes survive residency eviction and close; explicit air edits are retained. Cancelling the returned completion future does not revoke an already admitted edit.
5. Queued disk reads for evicted cubes are removed, so rapid teleports cannot permanently starve the current desired set. A failed provider/read remains visible; `retry(cube)` explicitly retries it.
6. `closeAsync()` stops admission and closes the store after a reserved FIFO I/O barrier drains accepted disk work. Even an unused world closes its store on the worker. `close()` waits up to 60 seconds. If waiting is interrupted or times out, closure still proceeds and can be observed or awaited again. Blocking close from the world's own I/O completion thread is rejected; use `closeAsync()` there.

Generation worker capacity is bounded independently of residency. Each loaded cube has a sparse immutable player overlay. The owned I/O executor has one worker and a bounded queue. Disk-backed save throughput is deliberately conservative: correctness first, with no unbounded dirty write-behind cache.

## Persistence and recovery

`FileEditStore` holds an exclusive writer lock for one world directory. Its checksummed manifest rejects changed generation, dataset and state-palette identities. Each per-cube record includes a magic value, version, kind, pinned identity, full cube coordinates, edit revision, sorted sparse edits and CRC32C. Reads are capped at 65,536 bytes. Malformed, duplicated, misplaced, unsupported or corrupted records fail visibly.

A save writes and fsyncs a staged file, atomically installs the previously verified primary as a backup, atomically replaces the primary, and fsyncs the containing directory. There is no non-atomic fallback. A retry after an uncertain rename reestablishes file/directory durability before reporting success. Revision checks prevent stale snapshots from silently replacing newer edits.

If the primary is missing or corrupt and a valid backup exists, `load` returns `RECOVERED_BACKUP` with a warning and its older revision. The engine refuses to use that older data silently, and saving is blocked. `restoreBackup(cube)` is an explicit decision to accept that previous generation; it retains any bad primary as a uniquely named `.corrupt-*` evidence file before restoring. If both copies fail validation, neither is treated as an empty cube. Orphaned temporary writes are ignored. CRC is accidental-corruption detection, not authentication against malicious filesystem modification.

Strict file/directory fsync and atomic replacement are required. Verified here only on this Linux cloud filesystem. Some Windows, network, removable or virtual filesystems may not support this policy; errors identify the strict durability requirement rather than silently weakening it. macOS/Windows behavior, real power-loss recovery, cross-machine filesystems, manual manifest recovery and format migrations are integration gates. One checksummed manifest is currently retained; corruption of it requires external backup/recovery rather than automatic adoption of unrelated data.

## Test coverage

`CoreTests` includes deterministic fake executors/providers plus real temporary-file persistence:

- 100,000 signed coordinate round trips; integer/long extrema; all 4,096 local indexes
- Compact homogeneous cubes, defensive copies and explicit air edits
- Overlapping players, atomic budget rejection, 3,000 randomized ticket operations against an independent union/reference-count model
- Shared generation, independently cancelled subscribers, uncooperative cancellation, bounded queues, 300 concurrent subscribers
- Executor/provider failure, 10,000 queued executor rejections without recursion or stranded futures
- Revision checks, save/reload, world/dataset/palette mismatch, exclusive writer locking
- Checksum/size/truncation/version failures, visible backup fallback, explicit repair and corrupt-file retention
- Injected directory-fsync failure after rename, including idempotent retry failure propagation
- Two players near true Y=8,849 and Y=−11,000, edited air/blocks surviving eviction and restart
- 200 rapid teleports with stale generation, and 200 with a blocked disk read
- Single- and multi-cube future-callback reentrancy, 800 parallel ticket replacements, bounded edit admission, injected failed writes, interrupted close with actual writer-lock reacquisition, deferred store closure, unused-world asynchronous closure and own-thread close rejection
- Negative X/Z and vertical Y=-1/0/15/16 boundary edits surviving restart

No test result here establishes full Minecraft correctness, geology accuracy, client FPS, tick budget, skylight under unloaded terrain, or compatibility with other mods.

## Recorded synthetic benchmark

See `BENCHMARKS.md` for one reproducible development run and its limitations. These measurements are useful for regression direction only; they are not production capacity promises.
