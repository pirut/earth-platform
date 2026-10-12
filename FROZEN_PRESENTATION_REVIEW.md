# Rolling 004 frozen-edit presentation: independent review

Status: no remaining blocker found in the reviewed paired lifecycle. Two callback-lifecycle findings were fixed and regression-tested before this review was closed. This is a source, controller-bytecode, and headless ownership/protocol review; it makes no new GUI or hardware-GPU performance claim.

## Scope and preserved authority

- `RollingGateClient.Session.beginEdit` captures the committed near/far pair, revision, publication, resource/coverage generations, and canonical manifest digest before `replica.beginEdit` or authoritative replacement admission. It requires a committed visible pair and ready collision first.
- Historical submission uses explicit `PresentationLease`/`PresentationTicket` capabilities. It returns before the ordinary current-mesh draw path and cannot publish collision or send readiness. Movement, mouse interaction, and authoritative ray/edit processing remain fenced by the existing edit protocol.
- The normal native current-revision path still calls `CubeStreamAdmission.isCurrentLoaded`; the added same-loaded-lease query is used only for explicitly retained presentation metadata. A replaced revision remains rejected by normal native preparation/draw checks.
- Native provenance retains exact immutable snapshot identities, bounded mesh/dependency membership, world/epoch/lease/resource identity, physical source identity, and appearance validity. Revocation, lease re-entry, reload, source closure, or observed appearance mismatch cannot revive a ticket.
- Far provenance binds one exact committed coverage list, view, source, epoch, resource generation, and coverage generation. Private preparation and cancellation preserve it; successful replacement revokes it before cache publication and retired-buffer callbacks. No additional native/far GPU generation is created merely by retaining presentation.
- Successful installation invalidates paired presentation before the far commit, retires tickets before old native resources, then reports paired readiness. Authoritative collision publication additionally rejects a retained presentation owner.
- Cleanup first revokes presentation, attempts each owner, and preserves failed owners for retries before disposing replica authority. Save failure, malformed/rejected acknowledgements, and protocol errors still flow through the existing fail-closed session boundary.
- Bootstrap HUD explicitly says there is no active/published pair and collision/interactions are fenced. Historical HUD labels the frozen revision and fenced motion/interactions separately from current authority.
- `RollingCubePlan` and `RollingGateReplica` are byte-identical to 003. The 213 source-owner and 55 native-generation caps, one-builder limit, fixed far extent, and original-cube immutable-shell/edit-interior restrictions are unchanged.

## Findings resolved

1. Delayed reload completion formerly invalidated the captured session but closed whichever session happened to be current when its asynchronous callback ran. `closeReloadOwner(capturedSession)` now requires exact current-owner identity before teardown. The new bytecode regression verifies both identity comparison and capture of the original session.
2. Cancelling/retiring during a ticket-acquisition callback cleared the cancellable `acquiring` flag. A nested capture could therefore install a second pair before the first call returned and overwrite a retained owner. A separate `captureStackActive` flag now prevents nested acquisition until the original `finally` runs. Both native-side and far-side repro cases failed before the fix and pass afterward.

The helper also rechecks exact ticket/stamp identity between liveness predicates, so reentrant retirement cannot call the next predicate with a discarded owner or claim a live pair. A separate `retiring` guard prevents recursive cleanup from repeating destruction or admitting a nested capture; failed owners remain available for a later outer cleanup attempt.

## Independent regression additions

20 new tests:

- `RollingFrozenClientContractTest`: 8 production-bytecode checks for capture ordering and edit bounds, explicit historical rendering, retirement before native destruction/readiness, collision-authority separation, cleanup/reload ordering, late reload ownership, input fences, and honest bootstrap/frozen HUD labels.
- `RollingFrozenPairReviewTest`: 6 tests using the actual native admission/guard, actual asynchronous DH prepared view/cache, and actual paired helper. Covers immutable prior revision during replacement/private preparation, candidate cancellation, far replacement revocation before retirement, appearance/lease ABA, far-source ABA, and retryable physical cleanup. Only GPU resource operations are fake.
- `RollingPresentationReentrancyReviewTest`: 6 tests for cancelled native/far acquisition reentry, retirement within native/far liveness predicates, recursive cleanup, and retry ownership after failed recursive cleanup.

Two assertions in `RollingSlicedClientContractTest` were updated to reflect intentional changes: the stricter commit error message and the extracted screenshot helper. The screenshot guard and separation from JSON report generation remain checked.

## Verified results

- Fresh isolated `javac` compilation of changed production classes and review tests succeeded with only existing deprecation warnings.
- Direct JUnit: **47/47 passed**, comprising 8 new controller checks, 6 new reentrancy checks, 9 existing sliced-controller checks, and 24 paired-helper tests.
- Normal NeoForge test XML: **6/6 actual paired-lifecycle tests passed**, with zero skipped, failures, or errors. The normal bootstrap is necessary because these tests create real native registry-backed snapshots.
- The nested-acquisition regression reproduced **2 failures before the fix**, then passed after recompiling the fix.

Evidence is retained in `review-evidence/frozen-presentation/`: direct-test output, paired-test XML, compilation output, pre-fix repro output, and SHA-256 hashes of the reviewed production sources. Aggregate final verification and real-client screenshots remain the owner's separate integration evidence.
