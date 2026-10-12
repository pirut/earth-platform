# Explicit prepared-DH rolling adapter, overlay 001

This narrowly scoped source overlay is for native-rolling-player-003. It is not a runtime freeze, does not modify terrain009, and does not alter legacy `replaceCoverage` semantics. Only `DhPreparedLodView` is new production behavior. Production support and all but one support test are copied byte-for-byte from immutable `native-terrain-view-005`; see `support-allowlist.json` and `support-files.sha256`. The single overlay-only support-test timing fix has original/new hashes and rationale in `support-test-deviations.json`.

## Public controller contract

1. Construct `DhPreparedLodView(sampler, world, epoch, resourceGeneration, centerX, centerZ)` on the render owner and retain the object before `initialize()`.
2. `initialize()` allocates driver resources only. It does not sample, upload, mask, draw, or publish terrain.
3. Finish candidate native meshes and create the exact immutable `List<SceneReadyCube>` attestation. Call `prepareCoverage(generation, exactReady)` and retain the opaque `CoverageTicket`.
4. Call `pump()` on the owner. This admits bounded CPU work and private GPU uploads, at the fixed aligned center. It never commits. `isCurrent(ticket)` distinguishes a live request, including executor backpressure, from a cancelled/stale/discarded request. A false value is not ordinary waiting. `isPrepared(ticket)` is true only for the complete current private far stage.
5. Send PREPARED only when candidate native meshes and this far stage are both complete. Keep the old native/far/collision pair visible while awaiting server ALLOW.
6. After ALLOW, revalidate every actual candidate native mesh and its full lease/revision/resource dependencies, then call `commitPrepared(ticket)` under the controller's shared native/far/collision frame fence. A successful result publishes the far stage; the controller publishes the matching near/collision state in that same fence. A false result or exception fails the candidate pair closed. These methods fence CPU submission, not GPU completion.
7. `coverageReady(generation)` reports committed coverage. `draw(matrix, cameraX, cameraY, cameraZ, resourceGeneration)` submits only committed current resources. `cancelPrepared(ticket)` and `requestInvalidation()` are CPU-only, thread-safe requests serialized against publication. Private GL owners are retained until owner `pump()` or `close()` cleans them. `close()` must be retried after an exception.

`CoverageTicket` has a private constructor and fields. It binds exact view object, request serial, epoch, resource generation, immutable-source identity, coverage generation, and full ready-list identity. Duplicate positions, wrong worlds/epochs/resources, nonpositive leases, negative revisions, out-of-envelope cube bounds, and more than 256 cubes are rejected. Lists are canonicalized by XYZ. Coverage generations are nonnegative and strictly increase for accepted new requests. Repeating the same live request returns its same ticket; a cancelled/committed generation cannot be reused. Old, cross-view, consumed, cancelled and closed tickets cannot commit. Source identity changes or operational identity-read failures throw and permanently fence active ownership, even if the old identity later returns. A sampler that changes data or identity without any observable identity change violates the required immutable-source contract.

## Source and geometry scope

The sole003 consumer is the immutable flat base sampler. Its source identity pins base generation and appearance. `LodSummaryKey.editRevision` is always zero for this immutable base; the full near readiness list, including independent cube lease and revision stamps, is in the transaction variant. Maximum near revision is never represented as an authoritative world/source revision. This adapter does not summarize edited volume. The actual edited central cube must remain pinned in the native coverage supplied by the controller.

The summary remains fixed at the constructor center aligned to16, radius512; there is no camera recenter or implicit bootstrap publication. The005 adaptive geometry is unchanged: exact XYZ near suppression, fine transition sampling, signed2048-block pages, native endpoints -11000 and +8849, and aligned cube envelope [-11008,8864). Endpoint18-cube eligibility is supplied by the actual controller attestation; the renderer neither invents readiness nor broadens exact XYZ masks into columns.

## Bounds and ownership

- One process-wide worker, queue capacity2, AbortPolicy; no official builder work runs on the render owner. The005 official runtime serializes actual upstream CPU builder access.
- Per view: one physical running source, one queued job, one subscriber per key, at most4 cached payloads, 20MiB declared cache and5MiB per result. Cancelled sources keep their physical slot until actual settlement, even when they ignore cancellation.
- At most2 upload owners and1MiB admitted allocations per pump, with a2ms soft admission budget. A synchronous upload cannot be preempted. The GPU cache counts current, private and failed-retirement owners against8MiB and128 owners; at most64 tiles.
- Faces and packed vertex bytes are both charged before packing and in payload accounting. JVM headers, source scratch, keys/futures, and caller-retained payloads are not represented as measured process heap.
- Allocation failures retain every partially allocated driver owner. Stage cancellation retains current owners. Failed deletion is retried through close. Retirement failure after internal cache publication invalidates the view and never reports a new coverage generation; the controller must hide the whole pair on that exception.
- Capture, upload, draw, restore and cleanup errors fence publication. Failed state-restoration snapshots remain retained and are retried by close, even if physical GL deletion already succeeded. `ownedGlObjectsDeleted` is deliberately false until both cleanup and restoration obligations complete.
- Diagnostics retain the first invalidation/close fence before futures are cancelled: pending generation, incomplete CPU future, physical running/queued counts, private GPU stage, and published/resident owners. Off-owner invalidation reads the last owner-fenced GPU counts; it never touches GL off-thread. Separate first-close counters retain pending generation, CPU/staging state, resident owners and uploaded owners. Physical running-job counts remain observable after close; a late result cannot upload or cache after cancellation.

## Provenance boundaries

`support-allowlist.json` explicitly identifies additions and005 replacements relative to rolling002. It does not admit unrelated `SceneSession`, rolling controllers, moving `DhIncrementalLodView`, terrain009 profiler/helpers, or any mutable shared source. `DhGeographicLodView` is copied unchanged solely for the supported005 driver and worker-facing interfaces, not changed by the adapter. `SceneLodRenderer` is an unchanged005 dependency of that class; the new adapter intentionally does not implement its implicit `replaceCoverage` API. `SceneSurfaceSampler` is the005 interface whose `supportsConcurrentReads()` default is false.

`SceneReadyCube` is taken from the rolling002 baseline and is byte-identical to005. Native core/world coordinates and other platform dependencies come from the frozen002 baseline. No other source is copied into this overlay. The official artifact is the unchanged pinned005 DH3.3.3 jar with SHA256 `864e70def5b0d54d619b940b66bb2784e91d3f3fc5786cbfab`.

## Verification

`test-results.txt`, `test-summary.json`, and `run-tests.sh` record cached Java21/JUnit execution without Gradle, GUI, or cache mutation. The suite contains18 new fake-driver adapter tests and137005-derived support tests, including actual pinned-library worker proofs. Fake drivers test protocol and ownership; they do not establish GPU/rendering performance or actual GL correctness. Independent003 controller and identity-fault reviews are owned by the integration task and live with003 evidence rather than this overlay.

The sole reviewed support-test deviation replaces an immediate post-completion assertion with a bounded3s wait for physical scheduler settlement. The source-entry latch can fire before its completion callback is registered. All pre-settlement running-slot, cancellation and queue checks remain unchanged. Frozen005 production and test files remain untouched.
