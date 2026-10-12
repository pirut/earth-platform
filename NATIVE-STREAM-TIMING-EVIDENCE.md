# Native stream timing diagnostics

Scope: `NativeVisualStream.java` in candidate 036 only. This is observational instrumentation; it does not change the native pump caller's four-upload / six-millisecond limits, the bounded mailbox capacity, the existing 50 ms timed offers, geometry, useful-progress counters, cache/identity rules, readiness, or ownership/release requirements.

## Evidence contract

The existing `NativeVisualStream.Evidence` appends two fixed-size records. Every component is a primitive or a fixed enum. Timings use `System.nanoTime` wall elapsed, not CPU time. No JVM timing instrumentation is enabled.

`pumpTiming()` is cumulative for the stream's render owner:

- `calls`, `ownerWallNanos`, `maxOwnerWallNanos`: admitted pump slices and their measured current-owner elapsed time, including synchronous work and failure paths. Timing begins at the original budget start, after successful owner/open/argument validation. Entry lease/resource validation is excluded even for successful calls. Rejected ownership/open/argument checks are outside admission. This does not measure full outer-call latency.
- `successfulUploadWallNanos`, `maxSuccessfulUploadWallNanos`: elapsed time inside successful `gpu.upload()` calls only. Failed upload calls remain included in owner elapsed but receive no successful-upload time or piece credit.
- `noRequestStops`, `uploadLimitStops`, `timeLimitStops`, `emptyMailboxStops`, `failedStops`: mutually exclusive terminal reasons, one for each admitted call.
- `lastStop`, `lastWallNanos`, `lastUploadLimit`, `lastBudgetNanos`, `lastUploads`: the final observed call's stop reason, elapsed wall time, supplied limits and successful upload count.

Stop precedence is preserved: upload count first, then elapsed budget, then mailbox poll. If count and time are both exhausted, the reason is `UPLOAD_LIMIT`. A primary pump/worker failure takes precedence over an otherwise normal stop reason. No-request calls report `NO_REQUEST`; a polled null with an admitted request reports `EMPTY_MAILBOX`.

`workerTiming()` describes the current request, or the most recently physically exited/drained request:

- `stop`: `NOT_STARTED`, `RUNNING`, `COMPLETED`, `CANCELLED`, or `FAILED`.
- `phase`: `IDLE`, `SOURCE`, `PLAN`, `HALO`, `MESH`, or `OUTPUT_OFFER`.
- `workerWallNanos`: finalized total worker wall elapsed. It remains zero until exit.
- `sourceWallNanos`: source preparation calls; source reuse is zero.
- `planWallNanos`: the draw and cache plan builds, including partial failed/cancelled work.
- `haloWallNanos`: halo allocation, material lookup/stores, member extraction and immutable builder-input construction, including partial failed/cancelled work.
- `meshWallNanos`: `nextPiece()` calls, including partial failed/cancelled work. There are no per-face timers.
- `outputOfferWallNanos`, `maxOutputOfferWallNanos`: elapsed time in completed existing timed queue offers, including interrupted/failed offers.
- `outputOfferAttempts`: completed timed-offer attempts, including exceptional exits.
- `outputOfferTimeouts`: exact false returns from timed offers.
- `outputsOffered`: successful output offers, including the single `Plans` output as well as `Piece` outputs. This is not a produced/uploaded-piece counter. Fully reused pages can therefore produce zero pieces while offering one output.

Offer elapsed includes blocking and queue/lock overhead. It must not be presented as exact blocked-only time. Timeout counts establish that the existing timed wait expired; live `OUTPUT_OFFER` identifies an in-flight attempt whose elapsed time is not yet included. Stage totals need not sum to worker total: bookkeeping, cancellation checks and builder construction remain outside those individual stage measurements.

Live snapshots are non-atomic observations across volatile counters. The terminal stop publication makes final totals visible. Completed-duration counters saturate at `Long.MAX_VALUE` rather than overflow or throw. Retiring a request retains only its scalar counter holder; it does not allocate a timing snapshot or retain the source, capture, plans or output queue. Timing fields never contribute to the original `Progress` record or watchdog useful work.

## Focused verification

`NativeVisualStreamTimingTest` contains eight focused tests:

1. Exact count/time/empty/no-request stops, count-before-time tie, successful upload total/max, completed request retention and unchanged completed-work counts.
2. Empty polling and rejected arguments cannot invent work or admitted calls.
3. Original upload failure is preserved; physical ownership remains until drain; successful upload counters remain zero.
4. Original worker failure overrides an otherwise empty-mailbox stop.
5. The real bounded mailbox and existing timed offers produce observable timeout/backpressure counters without advancing completed-work progress; cancellation joins before drain.
6. Pre-cancellation plus failed source and halo work preserve honest partial elapsed measurements and no uncompleted useful-work credit.
7. Worker plans, page identities, piece digests, ordinals, member ranges and completion match a direct untimed CPU-builder run; reused-only work offers one `Plans` output and zero pieces.
8. Saturating counters are nonthrowing and all retained timing fields/snapshot components have bounded primitive/enum shape.

The headless pump fixture executes production stream/worker bytecode with explicit lease-check, GPU construction/upload and owner-clock substitutes. It does not authenticate capsule or GPU authority, allocate/upload real GPU buffers, or establish installed-client latency. Worker source/plan/halo/mesh and real mailbox tests use measured wall time. An independent reviewer adds separate bytecode/authority guards.

Execution status: the final fresh shared cached compile and consolidated focused run passed **412/412** tests, including all eight timing tests and five independent timing-authority guards. A separate optional-DH/Iris/Sodium-excluded default proof passed **198/198**, including the eight timing tests and five authority guards again. There were no skipped or aborted tests in either run.

- [Combined focused run](../evidence/native-earth-distance-036/adaptive-focused/attempt4/run.log)
- [Default proof without optional jars](../evidence/native-earth-distance-036/adaptive-focused/attempt4/default-without-optional.log)
- [Exact source inputs](../evidence/native-earth-distance-036/adaptive-focused/attempt4/source-inputs.sha256)
- [Cached compile receipt](../evidence/native-earth-distance-036/adaptive-focused/attempt4/compile.log)

The first combined timing run had one test-fixture runtime-package access failure: the child-loaded stream could not access package-private `SceneBuildStepper` helpers through the app loader. The final fixture child-loads that unchanged helper family alongside the stream. This was a fixture-only correction; no production visibility or cleanup rule changed. The fresh final run above includes the correction.

Locked production SHA-256: `acc40c34341597d968c783e97c08126ff5b7a66f4eafd148d6961b8f2a4681cd`.

Locked timing-test SHA-256: `b432803ee65302f2af6ced33d9ee69ac3ab72a2ed9c5787431f38c72adb03426`.

No separate JVM or Gradle process was launched for this diagnostic slice; execution used the shared sole-JVM focused test lease. The original `Progress` record, `Job.progress()`, public `progress()` and `drawReady()` were also checked byte-for-byte unchanged against the pre-diagnostic source. Installed 036 latency and GPU acceptance remain unmeasured by these tests.
