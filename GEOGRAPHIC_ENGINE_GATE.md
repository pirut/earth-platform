# Native-camera near/far engine fixture

Status: source and headless verification in progress; actual client gate is not yet recorded here. This gate uses the measured terrain provider as an engine stress fixture. Cubic residency, generation and distant rendering are the foundation; the Earth generator remains a provider layered above it.

## Implemented boundary

`SceneSurfaceSampler` supplies a stable source/appearance identity, native-coordinate surface elevation and opaque material color. Both native cubes and far summaries use the same provider policy. `DhGeographicLodData` samples exactly 4,096 cells on a 16-block grid in the initial 512-block-radius square. It does not generate distant one-metre cubes or vanilla chunk columns. Missing source cells remain unknown, with no invented exterior side caps.

The far representation remains a bounded opaque surface summary, not a general cave/overhang/fluid summary. Signed 2,048-block vertical pages and 256-block horizontal tile origins accompany actual official DH quad bytes. Camera subtraction is performed in doubles before relative floats are submitted. The isolated official CPU builder and unchanged terrain shaders use the same pinned artifact as the completed GPU and coexistence gates; the DH mod entrypoint is not loaded.

## Actual near coverage and batch ownership

`SceneReadyCube` carries full 3D position, world, epoch, lease generation, revision and resource generation. The controller attests only native meshes whose authoritative guard and sampled halo dependencies remain current; successfully compiled known-empty meshes count. Merely admitted cube data does not suppress the far geometry.

An entire proposed near batch is clipped privately. Uploads and GL state restoration must succeed before publication. The controller exposes the near batch only after the exact coverage-generation handshake succeeds. Before retiring or dropping visible near meshes it first publishes an empty mask in the same owner-thread operation. The shared scene frame fence covers both distant and native command submissions against off-thread reload. These fences establish CPU command ordering, not GPU completion.

The geographic view restores GL state once around a far batch rather than once per tile. Published far ownership is capped at 64 tile/page meshes; a private replacement may temporarily raise it to 128. The emitted face cap is 32,768, or at most 2 MiB of DH vertex bytes and 0.75 MiB of triangle indices per batch. The fixed source data and upstream process-lifetime CPU buffer pool are separate from these GPU budgets. There is no disk cache or infinite recentering claim.

## Failure semantics

Only pure geometry-budget/source planning refusal is an ordinary deferred result. Upload and GL failures propagate. State restoration completes before the coverage commit. All failed candidate or retired mesh owners remain reachable for retry; cleanup attempts every owner even when one fails. Failure after a coverage commit fences the renderer and throws, rather than returning a misleading normal refusal. Partial upload owners also fence reuse. Successfully deleted GL names are cleared, and the owned current program is unbound before deletion.

The controller retains the session before initialization, and the session retains its far view before `initialize()` allocates GL objects. Initialization and cleanup failures therefore retain a retry path. Native/far/session cleanup must finish before a new scene starts. Temporary shader ownership is tracked through compile/link and attempted independently on teardown.

## Gate to run

The native worker freezes a separate `native-terrain-view-001` profile after the full suite passes. Root launches it in the cloud desktop and records:

1. Actual spectator camera above the terrain, near native Y around 5,000 or higher, in an empty stock-height carrier.
2. The initial far terrain and the completed native near batch, with matching source identities and actual ready coverage.
3. Camera motion across cube boundaries; bounded residency, new complete coverage generations and retirement of old owners.
4. Resource reload fencing, close, and fresh-epoch resume.
5. Real screenshot(s), software/hardware renderer string, CPU timing counters and final zero-owner/all-GL-deleted report.

This is a visual-only carrier. Cube collision, block edits, durable saves, multiplayer, stock DH compatibility, large measured view distances and hardware performance are separate unfinished gates. The initial far center is fixed; explicit scene resume constructs a new center.
