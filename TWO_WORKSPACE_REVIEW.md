# Integrated two-workspace authority review

Candidate010 derives from exact accepted009. Source/test overlays are enumerated in two-workspace-overlays.txt. This file describes headless review scope; actual GUI acceptance remains a separate gate.

## Authority and lifecycle

The server serially captures two disjoint27-neighborhoods using leases1..54, retires those leases, and then admits the first native window starting at55. The client gets a new admission gate with that fixed construction-only bootstrap floor and independently verifies the complete capture manifest. Existing live admission gates cannot reset or rebase.

Current focus is part of stage metadata and ready manifests. Actor and replica both require a same-center, unpinned-to-pinned focus transition whose target27 is already in the125 committed owners. Client validation precedes interest mutation; forged/outside/combined movement metadata cannot consume leases. The complete set, all captures and focus are bound into the versioned publication digest before PREPARED/ALLOW. A late review additionally found that a generic queued goal could outlive a focus/edit repin and request a disjoint28th target before retirement authorization. The actor now defers that two-workspace movement, rejects unsafe pin restoration, and checks the no-disjoint-pin invariant before allocating leases or tickets; independent tests cover initial, focus and edit paths. This enforces the near-only geometry proof at the actor boundary, rather than relying on the GUI controller ordering.

Only one prospective set and one historical set can accompany committed authority. An acknowledged newer revision raises a cancellation-surviving minimum revision: cancelling private preparation cannot restore the older presentation as current authority. Historical presentation remains display-only and is revoked by source/resource/session invalidation. Refused edits that never touched storage may recover the exact old complete set. SAVE_FAILED or CLOSING cannot generate recovered readiness.

Every native read binding and exact far generation/center/layout/set handshake is checked before exposing a pair. Private far preparation does not grant collision authority. Far commit precedes native binding activation; any partial failure closes/fences the entire session and retains cleanup owners. Set callbacks capture only a value-only mirror, the exact immutable certificate reference and cached identity, avoiding Session/Level capture and per-frame certificate hashing.

## Persistence and bounds

Two workspaces use an explicit new store/index version and distinct save directory. Legacy one-region sources and stores remain unchanged. The journal intent precedes the cube write and confirmation precedes ACK. Independent tests exercise every crash cut, failed writes, malformed/missing index, missing edited primary and backup, stale revisions, ABA leases and complete restart reconstruction.

Prospective admission is server-owned and pure, over both complete compact captures. Failure occurs before journal intent, authority mutation or cube save. The exact analytic-flat capability has a checked provider/config whitelist; both clean revision0 generated fixtures remain exact replacements. The rejected12589-leaf counterexample and accepted exhaustive11587-leaf proof are preserved. No cap was widened.

The actor's compact palette-array peak is three arrays, up to24000 bytes: oldA+otherB+prospectiveA, or retainedA+scratchB+defensiveB copy during bootstrap. Public input constructors remain defensive; prospective edits use a private trusted ownership constructor for their fresh array. Wire, replica and summary-cache ownership are separately bounded, so this is not an all-process heap bound.

One coalesced center/focus goal slot remains the limit. A queued focus consumes that slot; center requests return BUSY until focus staging starts. Worker cancellation and close drain the existing bounded provider/journal executors before store disposal. The four-slot actor result queue may still contain final immutable Snapshot/EditAck/CLOSED values until consumed or the closed actor becomes unreachable. These bounded value payloads retain no provider, Level, store or executor callbacks; they are distinct from zero live host/client/far owners and are not a garbage-collection claim.

## Evidence

- review-evidence/two-workspace-authority: independent publication, journal and actor adversaries; exact reviewed source hashes.
- review-evidence/two-workspace-budget: failed original refinement, exhaustive flat-policy census and independent geometry oracle.
- review-evidence/two-workspace-dh-lifecycle: exact complete-set staging, historical/current guards, failed GL cleanup and late worker results.
- logs/aggregate-inputs.sha256 and final-preparation.log: exact aggregate source inputs and result.
- logs/core-regression.log and logs/streaming-regression.log: standalone regressions.

No practical frame-rate or stall improvement is claimed. Broad profiling is disabled for this functional milestone.
