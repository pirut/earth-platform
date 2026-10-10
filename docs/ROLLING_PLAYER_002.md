# Bounded rolling player gate 002: sliced native rebuilds

This isolated gate starts from the immutable, GUI-verified rolling001 source. It changes only the native candidate work scheduler, its RollingGateClient adapter, diagnostics, and focused tests. The server actor/store/protocol, collision handshake, native SceneMesh implementation, full-height padding and official DH renderer remain byte-for-byte rolling001. Earth remains an optional full-3D provider. No terrain007/008 controller merge is included.

## Scheduling contract

One render-owner pump performs exactly one phase: begin a retained native build; execute at most4096 block callbacks with a soft2ms budget; or finish/upload a previously completed build. Finish/upload cannot run in the same pump as the last callback. A returned mesh occupies one retained completion slot until the session safely adopts it. There is at most one active build, one transferred completion slot, and28 target metadata entries. The active build and transferred slot can briefly reference the same ownership transfer; reference counts must not be added as distinct physical resources.

A model callback cannot be interrupted. Begin, a single callback, finish/upload, publication and cleanup can exceed2ms. Finish/upload is explicitly one combined metric, including validation, CPU mesh finalization, native VBO upload and CPU cleanup. DH initialization and full mask replacement are still synchronous and separately timed. This gate therefore measures an incremental scheduling improvement, not a hard frame deadline or overall performance acceptance.

Every batch is bound to its exact world/session publication, resource generation and manifest digest. Native SceneMesh still checks cube leases/revisions, frozen halo dependencies and appearance at callback boundaries. Stale or failed work can only be closed; failed cleanup remains reachable for retry. Old committed rendering and collision remain active until the candidate queue is fully drained and the exact native/DH/server acknowledgement transaction completes. If the player's complete body is outside the prepared adjacent window, the old pair waits conservatively for reentry; autonomous cancellation/replanning of that hold is not implemented.

## Unchanged authority and resource bounds

Current collision contains125 cubes. One adjacent3D candidate plus the original27 pinned edit dependencies is bounded by213 distinct source coordinates; a committed window plus pin is at most152. Client native physical mesh generations are conservatively capped55 before allocation. The single active CPU build has a1MiB declared geometry cap and at most3MiB lazy layer capacity. Native sequential indices are reported separately. DH has at most64 published plus64 staging page/tile owners,32768 faces per batch, and a fixed512m far field. Callback metadata and timing counters are fixed-size.

Only the original cube's interior local X/Y/Z1..14 can be edited; its one-block shell is immutable. Its mesh and27 dependencies stay pinned, so arbitrary edits are never retired into a regenerated flat summary. Unrestricted editing and full-3D edit-aware far summaries remain later work. Unknown cubes provide collision-only safety boundaries rather than generated barrier terrain.

Gameplay bounds remain X/Z±512 and Y[−11008,8864); native dependency padding is[−11040,8896). Profiles are base ground128, abyss−11000 and summit8849. Native model rendering uses fixed diagnostic illumination. Vanilla materials/fluids/entities/ticking, general survival and arbitrary mod compatibility remain incomplete. The upstream VertexBuffer constructor exposes no rollback handles if its own internal multi-handle construction throws before returning.

## Launch and actual GUI protocol

Use the portable repository README instructions. The development-layout launcher is omitted. Flags remain authoritativeCoreGate, coreCollisionGate, fullHeightPlayerGate and rollingCubeGate true; sparseTerrainView false. Use a disposable Creative world with commands enabled and the approved experimental-world warning. The scoped GUI validation passed; see evidence/rolling-player-002.json. The full protocol below includes additional cases not repeated in this short run, especially same-world restart, abyss and death recovery.

1. Record original Overworld position/mode. `/earth_roam enter`, wait for READY, then `/earth_roam walk`. Feet settle at129. Initial spectator feet Z−1.5 can naturally trigger an adjacent negative-Z publication after bootstrap.
2. For a repeatable mouse edit, use `/tp @s 4.5 129 -1.5 0 15`, then ordinary right-click and release. Preserve the resulting diorite edit, move around it, and walk80–100m across multiple16m seams. Stop for READY, return and verify the same edit remains. Capture `/earth_roam status` before/after and an F2 screenshot.
3. Exercise a diagonal horizontal transition and vertical flight through cube-Y boundaries, then return to known clear space and walk. Candidate geometry must remain private and collision must stay125 until exact pair commit.
4. Trigger actual F3+T while the HUD shows a queued candidate and active build1. The final CLOSED receipt records pre-cleanup builder/queue/slot counts and final zero owners. If the receipt instead shows only streaming or an idle state, label that exact case honestly and retry a separate epoch if needed. Held movement after reload must stay fenced; use leave, wait for drain, then enter.
5. Reenter and verify durable revision; save and quit directly while walking, reopen the same world, verify original Overworld mode/location, reenter and revisit the edit. At least one abyss and summit horizontal transition should retain the endpoint padding/mask behavior, with feet−10999 and8850 respectively. Finish with leave, save and close.

Evidence is under `minecraft/run/earthplatform-evidence/rolling-core-gate`. Main CLOSED and later collision-fenced receipts remain distinct because collision fencing may be queued. The READY screenshot path no longer rewrites earlier JSON readiness reports: it cancels obsolete captures when a new publication/edit starts.

## Timing evidence

Timings use System.nanoTime elapsed time on the owner thread, not CPU-time or GPU-duration measurements. `nativeWork` reports fixed-size begin/slice/finishUpload/publication/cleanup timing counters, successful callback counts, maximum callback duration and soft overshoot. Failed callbacks can consume time without increasing completed callbacks. `farInitializationTiming`, `farCoverageReplacementTiming`, `pairInstallationTiming` and `updateWorkTiming` remain separate. Actual flat-fixture surfaceY/argb callback counts include initialization and replacement, with max deltas per update; they are not distinct cell counts or DEM I/O. No source or native model callbacks were moved to a worker.

Mesa llvmpipe is software OpenGL. Compare actual phases and lifecycle correctness rather than claiming hardware FPS or universal hitch elimination. The prior rolling001 actual run reached397ms synchronous update; this gate must report its measured remainder, including unchanged DH work. Source archives exclude saves, caches, credentials and upstream binaries; reproduction requires the pinned official toolchain and DH JAR via EARTH_DH_PROBE_JAR.
