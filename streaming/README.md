# Bounded cube-stream admission prototype

Java 21, no third-party libraries. This module implements a metadata-only admission state machine over the existing core's `CubePos` and `WorldIdentity`. It does **not** connect Minecraft client/server tickets, transport, native section storage, lighting, meshing, renderer, entities or ticks. Passing these tests does not establish real Minecraft cubic streaming or seamless full-height gameplay.

## Run

From `earth-platform`:

```sh
./streaming/test.sh
./streaming/test.sh --benchmark
```

The runner uses `JAVA_HOME` when supplied, otherwise `earth-platform/.toolchain/jdk21`, resolved relative to the runner rather than the invoking directory. It compiles the current core main sources read-only alongside this module using `--release 21 -Xlint:all -Werror`; all build output stays under `streaming/build/`. There are no downloads. Tests are dependency-free and do not depend on `-ea`.

## Implemented protocol

One `CubeStreamAdmission` instance binds one immutable `WorldIdentity`, nonnegative connection epoch and explicit active-lease budget (1 through 65,536). It stores at most that many entries and one pending metadata token per entry. All three `CubePos` coordinates remain signed integers; this module adds no 2D packing or vertical remapping. An eventual engine adapter may have a narrower native address domain and must enforce its own checked conversion.

1. `replaceInterest(Collection<CubePos>)` atomically replaces the full requested set. Retained positions retain generation, last committed revision and pending token. Each new position gets a gate-wide strictly increasing positive `long` lease generation. Removed then re-added positions get a different generation, even in the same connection epoch. This prevents the ABA stale-response problem without retaining per-position tombstones.
2. New entries are `REQUESTED`, with applied revision `-1`. Valid snapshot revisions are nonnegative, so revision zero is usable.
3. `admit(Header)` checks world, epoch, currently requested position, exact lease generation, and revision strictly greater than both the last committed revision and any currently pending revision. It returns either a rejection or an opaque token. A newer admitted revision supersedes the previous pending token for that lease. It does not install or retain payload data.
4. Decode/materialize bounded, validated data outside the gate as appropriate. Before any external install, use `commit(token, installer)`. Commit revalidates the exact token and runs the short installer synchronously under the same monitor as interest replacement and closure. A successful install updates the applied revision and lifecycle to `LOADED`, then releases the pending slot. The token cannot be replayed.
5. `cancel(token)` withdraws only the exact current reservation, so a superseded token cannot cancel its successor. Cancellation permits retry using a new token, with a revision strictly greater than the last committed revision. It can admit a lower revision than a cancelled speculative reservation, but never lower than an actually committed revision. An old token with identical header values is still invalid: capability identity, not header equality, controls commit.
6. Retirement removes all metadata for that lease. `close()` retires all leases, returns bounded cleanup metadata and is idempotent. Closed gates never reopen.

Wrong metadata or invalid tokens return an explicit `Rejection` and never run the installer. Invalid API arguments throw. Duplicate, null, over-budget and overflowing interest replacements leave both leases and counters unchanged. Input traversal ignores an untrusted collection's advertised size and stops before fetching a `(maxLeases + 1)`th element. It cannot guarantee timely return from arbitrarily blocking user iterator code; callers should supply ordinary materialized collections.

### Connection ownership and exhaustion

The connection owner must allocate a fresh epoch whenever replacing/reconnecting a gate, including recovery after failure. Never create another gate with the same world/epoch on the same response channel and assume old wire packets will be distinguishable: token ownership is gate-local, but wire headers are not capabilities. This class does not maintain a process-global epoch registry, create credentials, authenticate a peer or enforce a connection transport. A fresh epoch must not collide with outstanding packets; the actual transport's handshake owns this requirement.

Generation `Long.MAX_VALUE` is usable. Any replacement needing more unique generations fails **before** changing interest or counters. Retention/removal/closure still work; there is no wrap or generation reuse. Committed revision `Long.MAX_VALUE` is also usable and leaves no larger revision for that lease. Renew the lease explicitly if the upstream revision scheme legitimately starts again. Never silently reset a live lease's committed revision.

## Application, retirement and failure contract

An admission token is not permission to install later without `commit`. Network arrival, decode completion or scheduling on a main-thread queue are not commit. In particular, never call commit with an installer that merely queues future application: such application could run after retirement. The callback must perform the actual atomic publication while commit holds the monitor, on the owning engine thread. The gate does not marshal work to that thread.

All gate mutations are synchronized. An installer can read `view()` reentrantly and will see the pre-commit metadata. Reentrant mutation from an installer or a hostile collection iterator throws before making changes. Other threads block until the active mutation finishes. The installer must be short, must not wait for another thread that needs this gate, and must not use async continuations for actual publication. There is no fairness or real-time latency guarantee for Java monitors.

If the installer throws **any** exception or error:

- The entire gate becomes `FAILED` and every pending token is invalidated.
- No applied revision or successful-commit count is advanced for the failed install.
- Only the bounded active lease metadata remains so the caller can clean up.
- Further admissions/commits are rejected; interest replacement is prohibited.
- The original runtime exception/error propagates. An illicit checked exception thrown through a generic cast is wrapped with its original cause.
- External side effects may already have happened. There is **no rollback claim**. `REQUESTED` means no install successfully returned, not proof that no external value exists after failure.

The caller must `close()`, clean up **all** returned lease generations, including `REQUESTED` ones, and recover with a fresh epoch and a resynchronized external store. A callback that catches an internal failure and returns successfully violates the install contract; the gate cannot detect hidden partial application. Catastrophic VM termination or resource exhaustion cannot carry an application-level recovery guarantee.

Interest replacement returns immutable `InterestChange` metadata: newly requested leases and retired `LeaseView`s. The gate neither invokes unload code nor retains these results. An asynchronous external unload must compare the full session identity and lease generation before removing anything. An old unload for position A must not remove a newer lease for A. The tests demonstrate generation-qualified compare-and-remove within one session. Across sessions, include world and epoch as well. A real engine adapter must provide atomic install/unload operations on its own owner thread.

Callers must bound their own payloads, packet queues, decoders, worker jobs, retained tokens, views and change results. Generating bounded results does not make an unbounded caller history bounded. This gate has no payload cache, work queue, executor, future, old-token archive or tombstone map. Live storage is O(budget); interest replacement uses O(budget) temporary metadata in addition to the old and new tables. Returned results are each O(budget). Counters have fixed count and saturate at `Long.MAX_VALUE`; gauges remain bounded by the configured budget.

## Codec handshake agreed with `minecraft/`

The native adapter's `EngineCubeSnapshot` protocol version 2 has:

```text
WorldIdentity world
CubePos position
long epoch              // nonnegative connection epoch
long leaseGeneration    // strictly positive, echoed from request
long revision           // nonnegative, comparable within active lease
```

It also validates its Minecraft source version, exact registry fingerprint, bounded native section body and CRC32C. The adapter worker added the lease field and bumped the wire/source version; no Minecraft files were changed by this module.

Proposed integration, not implemented here:

```java
// Request generation must travel to the server and be echoed unchanged.
var header = new CubeStreamAdmission.Header(
    snapshot.world(), snapshot.epoch(), snapshot.position(),
    snapshot.leaseGeneration(), snapshot.revision());
var admission = gate.admit(header);
if (admission.accepted()) {
    // Materialize on the native object's required owner thread, with bounded work.
    // On decode/materialization failure before publication, cancel this reservation.
    var materialized = snapshot.materialize(paletteContext);
    gate.commit(admission.token(), () -> {
        // Actual synchronous atomic install, tagged by world/epoch/lease generation.
        // No deferred installation or throwing partial mutations.
        nativeStore.installAtomically(header, materialized);
    });
}
```

This is an illustrative contract, not a compiled bridge; `nativeStore` does not exist here. Requests, unload routing, owner-thread scheduling, real source revision authority and native transactional publication still need implementation. Publication must ultimately target the authoritative native client cube owner, not merely an unrelated side map. Later asynchronous mesh/light work requires its own generation/revision check immediately before publication; a committed admission token is consumed and cannot authorize those later jobs. Retirement must also remove render/tick ownership on the proper engine thread. Verify packet identity and integrity before retaining an admission reservation. CRC32C detects accidental corruption and supplies no sender authentication or protection from a hostile authorized server. This gate prevents stale/misrouted application, not network-level denial of service.

## Verification

See `VERIFICATION.md` for exact test scope and `BENCHMARKS.md` for the synthetic metadata measurements and limitations.
