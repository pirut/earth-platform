# Independent prepared-pair acknowledgement review

## Result and scope

The focused independent run passed **23 tests**: nine production client bytecode-wiring checks, eleven executable server-handshake tests (five existing and six additional adversarial tests), and three additional executable prepared-DH source-identity fault tests. The latter exercise sixteen exception cases plus reentrant cancellation/invalidation through an actual `DhPreparedLodView` with a fake resource driver.

`RollingGateClient` compiled directly against the actual prepared-DH adapter and immutable rolling002 compiled dependencies. The only compile warnings were the existing NeoForge `EventBusSubscriber.Bus` deprecations. No Gradle task or cache write, GUI operation, publication, frozen runtime mutation, or actual OpenGL claim is part of this review. Bytecode checks verify production wiring; they do not execute Minecraft gameplay or prove GPU completion.

Receipts and exact reviewed-source hashes: `../evidence/native-rolling-player-003/independent-prepared-pair-review/`.

## Verified acknowledgement sequence

1. Native candidate work remains frame-sliced and must drain completely. Every candidate mesh is current and the original editable target is retained.
2. Far coverage receives the exact mesh-derived, endpoint-filtered readiness list. Its opaque ticket is retained while CPU work and changed-tile uploads stay private. Every pump checks that the ticket is still current before testing prepared readiness, so expiration cannot be mistaken for indefinitely pending work.
3. The client emits PREPARED only after both far readiness and native revalidation. The server compares exact epoch/publication/resource/manifest identity, proves complete-body containment, and fences authoritative walking collision before ALLOW.
4. ALLOW rechecks ticket readiness. Pair installation revalidates native ownership, hides visibility before the fallible far commit, checks the exact resulting coverage generation, and retires replaced native owners before PAIR_READY.
5. Only COMMITTED may retire client replica snapshots and publish its new 125-cube collision view. Failure retains cleanup owners and fences further acknowledgement handling.

Server state tests cover terminal close from every phase, non-mutating invalid packets, resource-latch integrity, duplicate PREPARED packets, unchanged-manifest edits with fresh publication IDs, and attempts to skip grant/final-commit transitions.

## Review findings corrected during implementation

- A retained session after failed close could previously accept a late COMMITTED packet because `checkPair` did not check closed/disposed/session validity. That could reopen collision before failed cleanup settled. The isolated003 client now rejects these acknowledgements and skips closed/disposed recipients at dispatch, with STOP allowed to retry cleanup.
- Late older epochs now return before body decoding; replayed initial metadata does not tear down a newer session. Current-session metadata and other frames are validity/resource-gated before admission.
- Controller handle/render/close boundaries now catch both RuntimeException and Error, matching the owned-resource layers' fault contract. This does not promise recovery from catastrophic VM/resource exhaustion.
- Source identity lookup exceptions could previously escape selected far readiness APIs without permanent invalidation, allowing a restored source identity to revive a prepared ticket. The adapter now centralizes active source observation with a permanent fault fence and rechecks callback-induced cancellation/invalidation. Independent tests inject RuntimeException and Error through all eight active identity-reading APIs.

The DH implementation owner's parallel audit additionally corrected active source-change readiness hangs and retained failed graphics-state-restoration obligations across close retries. Its own fake-driver tests cover those paths; this review does not substitute for that suite or the eventual exact-build GUI run.

## Reviewed support-test deviation

The isolated overlay changes one assertion timing in `BoundedLodSummaryJobsTest.closeCancelsLeasesAndRejectsRequestsButDoesNotReleaseUnsettledWorkOrShutExecutor`. Its source-entry latch fires inside `Source.load`, before that method returns and before production `invoke` can attach `whenComplete`. The test thread can therefore complete the source future before the completion callback is attached. Replacing the immediate settled-running-count assertion with a bounded three-second wait fixes that race; all earlier cancelled-lease, retained-running-slot, empty-queue and post-close rejection assertions remain intact, and a stuck worker still fails. The original005 file is unchanged. Both hashes in `rolling-dh-prepared-overlay-001/support-test-deviations.json` were independently verified. That support suite is separate from the23-test focused receipt here.

## Files owned by this review

- Updated `minecraft/src/test/java/io/earthplatform/minecraft/coregate/RollingSlicedClientContractTest.java`
- Added `minecraft/src/test/java/io/earthplatform/minecraft/coregate/RollingPreparedPairAdversarialTest.java`
- Added `minecraft/src/test/java/io/earthplatform/minecraft/lod/DhPreparedIdentityFaultReviewTest.java`

No unresolved acknowledgement/ownership blocker was found in the reviewed snapshot. The assembled003 full build, its aggregate regression suite, and its gameplay/GL evidence remain separate acceptance steps.
