# Extension API readiness and compatibility proposal

**Status:** design proposal `EARTH-EXT-001`, draft 0.1, 2026-10-09. This document changes no runtime behavior. The existing API inventory below is implemented; every item explicitly labeled **Proposed** is not implemented. No stable third-party ABI or Minecraft-wide compatibility is currently promised.

## Recommendation

Keep `CubeProvider` as the small cube-native terrain boundary. Add a versioned host facade before inviting gameplay modules to depend on `CubicWorld` directly. The minimum additional surface is: addon metadata/compatibility negotiation, an owned terrain session, bounded read/event access, and an explicit edit capability. Keep projection and raster-source extensions in geography, rather than making heightmaps or Minecraft columns the common addon abstraction.

Do not freeze every currently public class as a supported addon API. Public constructors currently make useful development seams, but do not establish lifecycle, authority, migration or long-term binary compatibility contracts.

## 1. Existing interfaces, verified against source

| Existing surface | What it supports now | Important limit |
| --- | --- | --- |
| [`CubePos`](../src/main/java/io/earthplatform/core/CubePos.java), [`ImmutableCube`](../src/main/java/io/earthplatform/core/ImmutableCube.java) | Signed independent 16³ cubes, checked long-block conversion, immutable nonnegative integer state payloads | Integer states have no namespaced meaning by themselves; 16³ and local index order are format-relevant contracts |
| [`CubeProvider`](../src/main/java/io/earthplatform/core/CubeProvider.java) | Thread-safe deterministic asynchronous cube generation with cooperative cancellation | No descriptor, factory context, manifest proof, lifecycle close or implementation-owned work-drain contract |
| [`BoundedCubeLoader`](../src/main/java/io/earthplatform/core/BoundedCubeLoader.java) | Bounded running/queued requests and subscribers, deduplication, independently cancellable leases | Caller owns executor; a cancelled uncooperative provider retains its slot until its future settles; `close()` does not await provider quiescence |
| [`CubicWorld`](../src/main/java/io/earthplatform/core/CubicWorld.java) | Bounded tickets, memory-only reads, readiness snapshots, explicit retries, asynchronous durable single-block edits, asynchronous store closure | A caller holding the object can edit any ready resident position; no actor/permission checks, event subscription, conditional compare-and-set edit, transaction or gameplay scheduling contract |
| [`WorldIdentity`](../src/main/java/io/earthplatform/core/WorldIdentity.java), [`FileEditStore`](../src/main/java/io/earthplatform/core/FileEditStore.java) | Exact seed/generator/version/dataset/palette matching, checksummed revisioned overlays, explicit recovery | Version mismatch fails closed; there is no migration runner, persistent stable-state dictionary or addon-owned data namespace |
| [`ElevationProvider`](../../geography/src/main/java/io/earthplatform/geography/ElevationProvider.java) | Ordered asynchronous batches of signed metre elevations with per-sample provenance | This is a scalar surface/seafloor service, not a general 3D terrain or gameplay abstraction |
| [`TileStore`](../../geography/src/main/java/io/earthplatform/geography/TileStore.java), [`CachedTileStore`](../../geography/src/main/java/io/earthplatform/geography/CachedTileStore.java) | Async pinned raster loads, content validation, deduplication and bounded cache admission | Interface has no cancellation/session lifecycle; constructor and cache setup perform blocking filesystem work; shared tile transfers may outlive a departing cube |
| [`GeographyCubeProvider`](../../geography/src/main/java/io/earthplatform/geography/GeographyCubeProvider.java) | Implements `CubeProvider`; bounded shared horizontal height fields produce independent cubes | Constructor takes the concrete `EquirectangularProjection`; height fields intentionally have a shared lifetime; column in-flight limit is currently fixed at 128 |
| [`WorldManifest`](../../geography/src/main/java/io/earthplatform/geography/WorldManifest.java) | File loading pins the canonical properties, projection, source hashes, datum, generator and palette into `WorldIdentity` | One source-provenance record and one concrete projection per manifest; direct public record construction accepts a supplied fingerprint instead of recomputing/validating the loaded-file proof |
| [`OfflineTerrain`](../../geography/src/main/java/io/earthplatform/geography/OfflineTerrain.java) | Verified bounded preload, then no-I/O queries | Setup blocks; maximum preload is 64 MiB; scalar surface queries do not imply full-height Minecraft integration |

### Readiness findings

1. A custom terrain supplier can implement `CubeProvider` today without inheriting a Minecraft column or a global height limit. The example in section 9 compiles and runs against that interface.
2. A supplier instance must be scoped to one pinned world. Loader deduplication keys are `CubePos`, not `(world, position, generation manifest)`. Sharing one loader across worlds would be incorrect.
3. The current world shutdown drains admitted overlay I/O and closes its edit store. It does **not** close the provider, await its owned tile/column work, or shut down the caller-owned generation executor. Addon unload needs a separate host-owned lifecycle.
4. Provider completion callbacks have no Minecraft-thread guarantee. Immediate futures can execute continuations synchronously; asynchronous futures may finish on generation or tile workers. Gameplay modules cannot call engine methods directly from those callbacks.
5. `setBlock` persists to the independent core overlay, not an asserted Minecraft engine transaction. An adapter must not advertise engine-wide block/tick/light/entity support merely because the core method exists.
6. Geometry is immutable after generation and player edits overlay it, which is a sound extension boundary. The missing piece is a narrow authority-bearing facade; handing an addon `CubicWorld` or `EditStore` gives it excessive control.
7. Future geography composition needs per-source/per-sample provenance and explicit datum/water policies. A new provider must not put several datasets into today's single-provenance manifest and imply that one record truthfully describes them all.
8. Existing public records are convenient, not trust boundaries. A future host must validate the canonical manifest, palette mapping and addon descriptor rather than trusting a provider's claimed fingerprint.

## 2. Proposed versioning boundary

**Proposed:** publish an engine-independent `earth-extension-api` artifact on Java 21. Target API version 1.0 only after the acceptance gates below pass. Draft 0.x releases require an exact documented source revision; they offer no binary compatibility guarantee.

- Reserve `io.earthplatform.api.v1` for the host facade. Existing implementations remain under `io.earthplatform.core` and `io.earthplatform.geography`.
- Initially reuse `CubePos`, `ImmutableCube` and `CancellationToken` as a small, explicitly supported value/terrain seam. Because proposed signatures reference them, their referenced methods, index order, immutability and signed-coordinate behavior become part of the v1 contract too. Packaging alone does not isolate those dependencies.
- Do not expose `FileEditStore`, loader internals, world ticket bookkeeping or concrete Minecraft classes through the facade. `CubicWorld` stays host implementation, not the default gameplay-module dependency.
- An addon declares a namespaced ID, addon semantic version, API major and minimum minor, required capabilities, supplied services, generation/configuration fingerprint when applicable, and owned-state schema version when applicable.
- Host and addon must agree on API major and satisfy the minimum minor. A newer minor is additive only. Removing/changing methods, record components, exception meaning, completion semantics or coordinate interpretation requires a new major. Do not add components to a published Java record and call that a binary-compatible minor change.
- API version, addon release, generation algorithm version, projection version, dataset/content digest, state-palette digest, persistent save-format version and engine-adapter version are separate axes. Successful API negotiation never authorizes a save conversion.
- Registration is explicit during host startup. A service-loader manifest may discover descriptors, but discovery must not instantiate work or grant capabilities. No runtime hot reload in the first stable release.

**Proposed capability examples:** `cube-read-v1`, `cube-lease-v1`, `durable-single-cube-edit-v1`, `terrain-generation-v1`, `geographic-projection-v1`. Actual Minecraft-facing features such as cube-aware lighting, entities, fluids and authoritative client/server cubic storage require separate verified capabilities. Absence means unavailable. This review did not inspect or certify Minecraft adapter behavior.

## 3. Proposed minimal terrain extension

These signatures are a design sketch, not classes currently shipped:

```java
interface TerrainAddon {
    AddonDescriptor descriptor();
    CompletionStage<TerrainSession> open(TerrainContext context);
}
interface TerrainSession {
    CubeProvider cubes();
    CompletionStage<Void> closeAsync();
}
```

`TerrainContext` is host-created, world-scoped and read-only. It supplies the validated generation identity, a deterministic stable-state palette, bounded CPU/I/O scheduling services, a read-only source resolver, cancellation/lifecycle signals and an observability sink. It does not expose a mutable world, player edits, arbitrary server objects, secrets or implicit network permission. A source resolver's permitted roots/hosts and byte limits come from host configuration, not a plugin-provided URL alone.

`open` validates dependencies and returns only when the session can accept requests. Any disk/network initialization runs on an approved worker. Source content, interpolation, datum transforms, water rules, feature ordering and generation-affecting configuration must all be pinned. For the same identity and position, output and failure classification must not depend on request order, wall-clock time or which player arrived first.

`cubes().load` retains the existing immutable-result contract. Its future settles after that cube's work is finished. Shared work may continue only while accounted to a bounded session-owned cache/request budget; cancellation of one subscriber cannot poison others. A session cannot spawn an unbounded private executor behind the bounded cube loader.

`closeAsync` stops new admissions, cancels obsolete work, releases subscriptions, and completes only when all owned work has settled and resources have been released. It must not block its calling thread. A hung provider remains visibly incomplete and consumes bounded capacity; a host must not falsely declare it stopped or release accounting just because a future was force-cancelled.

### Geography extensions belong beside this interface

**Proposed**, for a later compatible geography facade rather than extra mandatory core methods:

- A versioned `GeographicProjection` interface with forward/inverse transforms, declared units, extent/seam behavior, axis orientation and local-scale/distortion information. `GeographyCubeProvider` can then accept an interface instead of the concrete equirectangular record. A projection's horizontal branch/atlas face must be explicit if its inverse is not unique.
- Distinct elevation/bathymetry, water mask/level, land-cover and vector-feature providers. They return bounded immutable batches with source ID, release, native resolution, datum, quality/uncertainty and attribution. Resolution in the source does not become measured 1 m detail after interpolation.
- A canonical generation plan with ordered source precedence and deterministic transforms. Mixed datums must be normalized or rejected before blending. Missing water data cannot silently trigger a different physical model.
- Keep horizontal height-field caching as a geography optimization. It must never require all Y cubes in a Minecraft-style column to be resident, generated, saved or ticked.
- Cave/ore/structure layers may be fully 3D deterministic stages. Any neighbor dependency is expressed as an explicit bounded 3D region or radius, with deterministic boundary ownership and a cycle-free plan. No implicit "load the entire column" or recursive unbounded neighbor generation.

The first facade should ship terrain sessions without trying to standardize all those data types at once. Native vector/building/water support and a new projection require their own tested provider contracts.

## 4. Proposed gameplay access and edit authority

**Proposed:** separate observation from mutation. A `GameplayAddon` opens a per-world session and receives only granted host services. A read-only addon cannot obtain an edit service by casting the read interface to the implementation.

The minimum service families are:

| Proposed service | Required contract |
| --- | --- |
| `CubeReadAccess` | Immutable effective-block snapshots with cube position, world/session ID, generation epoch and edit revision; unavailable/evicted is explicit |
| `CubeLeaseAccess` | Bounded, module-namespaced, cancellable 3D residency interests; owner limits are subordinate to the global budget and do not evict another player's required cubes silently |
| `CubeEvents` | Bounded subscription to ready/evicted/edited/failed events on a declared executor; subscription close and overflow behavior are explicit |
| `EditAuthority` | Host-minted scope for actor/module, allowed regions/materials, edit rates and expiry; admission verifies authority and readiness before any mutation |

An addon submits an immutable `EditIntent` through its granted `EditAuthority`. For v1, keep one cube per request and require an expected edit revision for read-modify-write operations. A durable `EditReceipt` reports world/cube, actor, previous/new revision, operation ID and status. A conflict returns a conflict result; the host must not silently overwrite newer player work. The existing `setBlock` method does not perform this compare-and-set or actor check and must be wrapped/replaced internally before advertising that capability.

The host resolves stable block keys to the pinned palette and enforces quotas. Addons never write directly to `EditStore`, the binary save directory or a procedural base. Defaults grant reads only. A terrain provider cannot use generation as an excuse to erase player overlays. Accepted writes may complete after a player moves away; cancelling a receipt future does not undo an accepted edit.

Do not promise multi-cube atomicity, rollback, undo, exactly-once retried edit operations or side effects on entities/fluids in v1. Those need a journal/transaction design and independent engine tests. An operation ID is useful for audit/correlation; it does not create idempotency by itself. Before safe automatic retries of uncertain write outcomes, implement persistent deduplication or an explicit read/resolve protocol.

A single-cube batch would require one admitted revisioned save, not a loop of public `setBlock` calls pretending to be atomic. Gameplay-owned metadata, if needed, belongs in a host-managed namespaced store with explicit byte quotas and schema version; it is not currently implemented and is not a reason to add arbitrary addon fields to cube-save records.

**Trust boundary:** capability objects constrain use of supported host APIs. They do not sandbox arbitrary in-process JVM bytecode against filesystem/network access or reflection. Initial addons must be trusted, reviewed code. Untrusted addon execution requires a separate isolation design; neither a Java interface nor a classloader alone is a security boundary.

## 5. Lifecycle, thread ownership and events

**Existing:** generation executor is caller-owned; core overlay I/O worker is world-owned. Current readiness/edit futures may invoke user continuations on the completing thread or, if already complete, on the registering thread. `CubicWorld.closeAsync()` covers accepted overlay work and store closure; it does not prove provider quiescence. A gameplay caller must not `join`/`get` on a Minecraft tick/render thread.

**Proposed lifecycle:** `DISCOVERED -> VALIDATED -> OPENING -> ACTIVE -> QUIESCING -> CLOSED`, with a visible `FAILED` state. Every session has a unique epoch; late results from an earlier epoch are discarded. One declared terrain supplier, or one explicit deterministic composition, owns generated base blocks for each world.

Recommended host shutdown order:

1. Revoke addon edit/read-lease admission and stop gameplay scheduling.
2. Close subscriptions and remove module interests; prevent new generation admissions.
3. Begin world closure, preserving all already accepted overlay edits.
4. Close terrain/data sessions, propagating cancellation without cancelling another world's shared work. Await actual provider-owned work settlement separately from the world's store-close future.
5. Await both durable world closure and session quiescence, then shut down host-owned executors. A bounded timeout reports the still-running resource; it must not claim completion or unload code that is still executing.

**Proposed event contract:** events are observations, never the durable write log. Deliver in sequence on one bounded per-session dispatch lane, after the relevant state transition. Include epoch and sequence so a listener can reject stale events. Initial subscription provides a bounded snapshot/resync protocol. On overflow, signal `RESYNC_REQUIRED` and suspend/drop observational events according to policy; never drop an admitted save. Edit confirmation comes from its durable receipt, not an event listener.

Callbacks must be short and nonblocking. Host callback exceptions are isolated and reported; a repeatedly failing subscription is disabled. Heavy work is submitted through a bounded service. Events do not run on Minecraft's main thread unless the host explicitly advertises that guarantee; engine-specific mutations are marshalled by the adapter. No host monitor is held while invoking addon code or publishing callbacks that can reenter the host.

## 6. Save identity and migration

**Existing:** `FileEditStore` rejects any changed `WorldIdentity` component. Save format is version 1; backup recovery is explicit and may lose changes newer than the recovered revision. The pinned identity is a safeguard, not a migration system. A custom `EditStore` implementation must supply its own equivalent identity/durability enforcement; `CubicWorld` only directly verifies the built-in file store's identity.

**Proposed stable-state rule:** persist a world-owned mapping from integer IDs to namespaced block keys plus canonical sorted properties. Hash the actual mapping, not a human label or only the six terrain materials. Old IDs are never reassigned. Missing states require an explicit refusal, documented placeholder mode or approved conversion; never silently become air. Different Minecraft/mod registry numbering therefore does not reinterpret old player edits.

**Proposed generation fingerprint:** include seed, ordered generation addons and exact versions, canonical generation configuration, complete projection/seam/datum/interpolation rules, source content hashes, override precedence, water policy and palette mapping. Exclude purely observational addon preferences from the generation fingerprint. Modules that change generated blocks are generation modules even if marketed as gameplay modules.

A migration is an explicit offline operation:

1. Stop admissions and fully quiesce the world and source sessions. Verify an intact backup and exclusive writer ownership.
2. Produce a dry-run report of affected cubes, unknown states, coordinate/projection changes and addon schema changes. Identify the exact from/to format and identity digests.
3. Write converted state into a separate destination with checksums and a resumable journal. Never reinterpret the old manifest or mutate only its identity fields to force a successful open.
4. Preserve every player overlay, including explicit air. A changed projection may move positions; a changed terrain base can cause physical conflicts with existing builds. Surface those conflicts instead of claiming a state-ID remap preserves geometry.
5. Validate counts, checksums, sentinel coordinates and replay samples; switch via a supported atomic manifest/world-root operation and retain rollback data.

No migration, hot-regeneration or automatic dataset refresh is implemented by this proposal. Until a tested migration exists, create a new world for incompatible generation changes. Upgrading a read-only observer may be API-compatible without changing the world; upgrading its own persisted schema is a separate decision.

## 7. Failure and budget contracts

**Existing:** invalid coordinates/states fail synchronously; budget exhaustion commonly uses `RejectedExecutionException`; missing terrain uses `MissingTerrainException`; source/store failures often use `IOException`; cooperative cancellation uses `CancellationException`. Asynchronous causes may be wrapped by completion exceptions. `retry(cube)` is explicit and does not authorize replacing corrupt saves. Geography tile admission is bounded, and column work has separate cache/in-flight accounting.

**Proposed:** standardize a typed error envelope with a stable code, retry category, provider/module ID, bounded diagnostic detail and optional retry-after. Preserve the original cause internally. Codes should distinguish `CANCELLED`, `BUDGET_EXHAUSTED`, `OUTSIDE_COVERAGE`, `SOURCE_UNAVAILABLE`, `CONTENT_MISMATCH`, `IDENTITY_MISMATCH`, `UNSUPPORTED_CAPABILITY`, `EDIT_CONFLICT`, `PERMISSION_DENIED`, `SAVE_CORRUPT` and `DURABILITY_UNCERTAIN`.

- Missing coverage/no-data is never fabricated as zero elevation or air. Fallback is permitted only when the fallback and its precedence are pinned, provenance is preserved and the host reports which source supplied it.
- Use bounded backoff and retry budgets for transient failures. No retry loop on corruption, identity mismatch, unsupported projection or denied capability. Re-read/resolve uncertain writes before resubmitting; a timeout is not proof that nothing happened.
- Account separately for resident cubes, active/queued generation, per-cube subscribers, provider-owned shared requests, tile/column cache bytes, network bytes, pending writes, addon leases and event queue length. Sum per-addon grants under hard host limits.
- Payload byte counts do not describe whole-JVM memory. Temporary buffers, retained snapshots, object/maps, queued closures and provider work also need measured limits and soak tests.
- Do not release a running slot until physical work settles. Define shutdown/drain diagnostics for a hung source. Per-request timeouts must not quietly create unbounded orphan tasks.
- Library data caches may be reconstructible and repairable from a pinned hash; player-edit stores require stronger durability. Do not generalize the cache's non-atomic fallback into an allowed player-save policy.

## 8. Compatibility gates before advertising v1

1. Publish a whitelist of supported classes/methods and compile sample addons against the actual API artifact. Test old binary addons against every compatible minor release.
2. Verify deterministic results for reordered/concurrent requests and the same pinned generation identity; test negative boundaries, both vertical extremes, seams, no-data and datum disagreement.
3. Verify session close with never-started, blocked, cancelled and shared requests. No callbacks after closed completion, no released accounting while orphan work continues, and no shutdown I/O on tick/render threads.
4. Verify read-only modules cannot obtain mutation/storage/ticket-control implementations through the supported facade. Validate host-minted scopes, stale revisions and per-addon/global quotas.
5. Verify failed/uncertain saves, palette changes, unknown states, explicit air, restart, eviction and interrupted migration without silent overwrite.
6. Verify callback reentrancy, slow listeners and event overflow do not violate residency limits or stall durable edits.
7. Run engine-adapter conformance separately: advertised cube, lighting, entity, fluid, networking and client-render capabilities each require real engine tests. Core tests alone cannot grant them.
8. Keep the first release small: descriptor + managed terrain session + immutable reads/leases + conditional single-cube edit authority. Add rich gameplay events and owned state only once their lifecycle/storage guarantees are implemented and tested.

## 9. Compilable example using only the existing API

This is a synthetic 3D stripe provider, not Earth geography. It demonstrates that an addon can generate independent signed cubes without any column/heightmap/Minecraft type. Work is trivial, so its provider future is already completed; real blocking I/O belongs on a dedicated bounded worker.

<!-- BEGIN_COMPILED_EXAMPLE -->
```java
import io.earthplatform.core.BoundedCubeLoader;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.ImmutableCube;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SignedStripeAddon implements CubeProvider {
    @Override
    public CompletableFuture<ImmutableCube> load(CubePos cube, CancellationToken token) {
        try {
            token.throwIfCancelled();
            long stripe = 31L * cube.x() + 17L * cube.y() + 13L * cube.z();
            int abstractState = Math.floorMod(stripe, 3);
            return CompletableFuture.completedFuture(ImmutableCube.uniform(abstractState));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try (var loader = new BoundedCubeLoader(new SignedStripeAddon(), workers, 2, 2, 2)) {
            for (long y : new long[] {8_849L, -11_000L}) {
                CubePos position = CubePos.fromBlock(-1, y, -1);
                try (var lease = loader.request(position)) {
                    ImmutableCube cube = lease.future().get(5, TimeUnit.SECONDS);
                    if (!cube.isUniform()) throw new AssertionError("Expected uniform stripe");
                    System.out.println(position + " state=" + cube.get(0));
                }
            }
        } finally {
            workers.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        }
    }
}
```
<!-- END_COMPILED_EXAMPLE -->

Save the example as `SignedStripeAddon.java` outside the source tree, compile it with all existing core main sources using Java 21, and run `SignedStripeAddon`. The blocking `get` above is only in a command-line harness, never on a Minecraft tick thread. Abstract states 0/1/2 require a host-approved, pinned palette in a real world. This example intentionally has no write capability and does not implement the proposed registration/session interfaces.

**Validation:** the exact example was extracted from this document, compiled with `--release 21 -Xlint:all -Werror` against current core source, and run successfully. It returned independent cubes at section Y=553 and Y=−688. The proposed interfaces and compatibility gates are design work only; this compilation does not claim that they are implemented.
