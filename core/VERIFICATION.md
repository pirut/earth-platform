# Core verification record

2026-10-09, final recorded verification at 23:21:22 UTC.

## Result

`./core/test.sh --benchmark` passed strict Java 21 compilation (`--release 21 -Xlint:all -Werror`) and all 27 test groups / 351,809 assertions. The test phase took 0.576 seconds. No third-party compile or test dependency is required. All original 25 groups remain; their pre-fix baseline was independently rerun successfully at 23:19 UTC (351,725 assertions, 0.743 seconds).

The executable suite covers signed coordinate extremes, true-height storage at Y=8,849 and Y=−11,000, all local indexes, negative and vertical section boundaries, immutable bases, explicit air edits, overlapping multi-player tickets, hard budgets, deterministic randomized reference models, asynchronous deduplication and cancellation, 10,000 rejected queued operations, parallel subscriptions and ticket replacements, persistent overlays, restart/eviction, strict version/identity/palette checks, corruption recovery, fsync failure propagation, uncertain-write publication barriers across eviction and reopening, queue reclamation, callback reentrancy, and shutdown races.

## Independent adversarial checks

A separate read-only review reran the then-current 22-group suite / 351,707 assertions successfully, exercised 8,000 concurrent ticket updates across eight callers with the final three cubes ready and the loader fully drained, and tested 2,000 unused-world shutdowns with zero store closures on the calling thread. No must-fix residual core issue was found in that review. Three subsequent test groups added explicit negative/boundary edits, failed-write visibility and parallel ticket replacement coverage without changing production code.

The review found defects that were corrected and now have deterministic regressions:

1. Evicted read tasks could occupy the disk queue and permanently fail the final teleport destination. Eviction now reclaims queued reads, while admitted writes remain intact.
2. A shutdown rejection cascade could recursively exhaust the stack and strand requests. Dispatch now uses a bounded-work trampoline.
3. Synchronous future callbacks could reenter partial residency transitions or restore cubes before old subscriptions retired. State transitions and old private reservations now settle before public callbacks.
4. An uncertain atomic rename followed by failed directory fsync could be incorrectly acknowledged by an equality shortcut on retry. The retry now reestablishes durability; directory-creation retries also force ancestors.
5. Interrupted shutdown could retain a writer lock, and an executor termination hook could run blocking store closure on its caller. Shutdown now enqueues a reserved FIFO I/O closure barrier, remains observable, and releases the actual file-store lock after admitted writes drain.
6. After a primary rename succeeded but its directory fsync failed, eviction/reentry or a reopened store could publish that uncertain revision as ready without repairing durability. `CubicWorld` now saves the identical loaded primary snapshot on its I/O worker before publishing readiness. The existing equality path forces the file and cube directory without advancing the revision or replacing primary/backup bytes. Continued failure explicitly fails readiness; `retry` after recovery publishes that exact preserved revision. Raw `FileEditStore.load` remains inspection, not a durability acknowledgement, and recovered backups still require explicit restoration.

## Uncertain-write regression verification

Two new deterministic groups exercise both first-write revision 1 and replacement revision 3 with a valid revision-2 backup. The injected failure occurs after the primary rename; replacement tests allow the preceding backup-directory sync to succeed. With the fault still active, repeated equal-save attempts, eviction/reentry, explicit readiness retries, and reopened-store readiness all fail without publishing an unconfirmed view. Source bytes and backup bytes remain unchanged. Clearing the fault permits exact-revision recovery, preserving explicit air at negative coordinates including Y=−11,000; the next actual edit advances by exactly one revision. Readiness repair fsync is verified on `earth-overlay-io`.

A separate standalone before/after probe reproduces the original defect against the saved pre-fix `CubicWorld`: it fails because unconfirmed reentry readiness returns successfully. The same probe passes against the corrected core for 100 rounds / 1,800 checks in 0.588 seconds, covering ongoing-failure eviction/reentry, store reopening, explicit retries, exact-revision recovery, unchanged primary bytes, and negative-coordinate air edits.

Ten additional consecutive runs of the complete 27-group suite all passed (3,518,090 total assertions), including its concurrency and shutdown regressions.

An independent reviewer additionally compiled the current core with strict Java 21 and passed a separate fault probe for both eviction/reentry and reopened stores with valid backup revision 1 and uncertain primary revision 2. No view was published while the directory sync continued failing; recovery retained and published primary revision 2 without silently accepting backup revision 1.

## Latest synthetic measurements

The 23:21:22 UTC verification run used Linux amd64, Temurin Java 21.0.12.1, and 9 JVM-visible processors. Historical results remain in `BENCHMARKS.md`; these additional shared-machine measurements are not capacity claims:

- 2,000,000 coordinate round trips: 0.009224 s, approximately 216,821,300/s
- 4,000 two-player ticket updates: 0.211092 s, approximately 18,949/s; peak residency 150/150
- 20,000 synthetic async requests: 0.194212 s, approximately 102,981/s; all completed, zero failed or residual work
- 200 durable overlay saves with backup: 0.251544 s, approximately 795/s; final primary 1,289 bytes and 200 edits

These workloads do not measure the added primary-read readiness barrier or physical-storage latency. Raw final suite/benchmark output is at `core/build/verification.txt`.

## Scope and remaining gates

This is an independent cubic engine core, not a full Minecraft mod. The core's geography plugin contract compiles separately from Minecraft engine internals. Actual Minecraft chunk/ticket replacement, networking, lighting/skylight, entities, liquids, rendering, client behavior and compatibility remain integration work.

Persistence was exercised on the current Linux cloud filesystem. Atomic replacement and strict file/directory fsync are required; unsupported filesystems fail explicitly. macOS/Windows and physical power-loss recovery are unverified. Tests inject selected durability failures; they do not simulate every operating-system/hardware crash.

Benchmarks are synthetic, single-machine development measurements with visible run-to-run variation. They do not measure real geography requests, Minecraft ticks, FPS or production capacity. See `BENCHMARKS.md` for exact workloads, results and limitations.
