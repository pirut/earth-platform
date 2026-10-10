# Core verification record

2026-10-09, final recorded verification at 20:30:36 UTC.

## Result

`./core/test.sh --benchmark` passed strict Java 21 compilation (`--release 21 -Xlint:all -Werror`) and all 25 test groups / 351,725 assertions. The test phase took 0.844 seconds. No third-party compile or test dependency is required.

The executable suite covers signed coordinate extremes, true-height storage at Y=8,849 and Y=−11,000, all local indexes, negative and vertical section boundaries, immutable bases, explicit air edits, overlapping multi-player tickets, hard budgets, deterministic randomized reference models, asynchronous deduplication and cancellation, 10,000 rejected queued operations, parallel subscriptions and ticket replacements, persistent overlays, restart/eviction, strict version/identity/palette checks, corruption recovery, fsync failure propagation, queue reclamation, callback reentrancy, and shutdown races.

## Independent adversarial checks

A separate read-only review reran the then-current 22-group suite / 351,707 assertions successfully, exercised 8,000 concurrent ticket updates across eight callers with the final three cubes ready and the loader fully drained, and tested 2,000 unused-world shutdowns with zero store closures on the calling thread. No must-fix residual core issue was found in that review. Three subsequent test groups added explicit negative/boundary edits, failed-write visibility and parallel ticket replacement coverage without changing production code.

The review found defects that were corrected and now have deterministic regressions:

1. Evicted read tasks could occupy the disk queue and permanently fail the final teleport destination. Eviction now reclaims queued reads, while admitted writes remain intact.
2. A shutdown rejection cascade could recursively exhaust the stack and strand requests. Dispatch now uses a bounded-work trampoline.
3. Synchronous future callbacks could reenter partial residency transitions or restore cubes before old subscriptions retired. State transitions and old private reservations now settle before public callbacks.
4. An uncertain atomic rename followed by failed directory fsync could be incorrectly acknowledged by an equality shortcut on retry. The retry now reestablishes durability; directory-creation retries also force ancestors.
5. Interrupted shutdown could retain a writer lock, and an executor termination hook could run blocking store closure on its caller. Shutdown now enqueues a reserved FIFO I/O closure barrier, remains observable, and releases the actual file-store lock after admitted writes drain.

## Scope and remaining gates

This is an independent cubic engine core, not a full Minecraft mod. The core's geography plugin contract compiles separately from Minecraft engine internals. Actual Minecraft chunk/ticket replacement, networking, lighting/skylight, entities, liquids, rendering, client behavior and compatibility remain integration work.

Persistence was exercised on the current Linux cloud filesystem. Atomic replacement and strict file/directory fsync are required; unsupported filesystems fail explicitly. macOS/Windows and physical power-loss recovery are unverified. Tests inject selected durability failures; they do not simulate every operating-system/hardware crash.

Benchmarks are synthetic, single-machine development measurements with visible run-to-run variation. They do not measure real geography requests, Minecraft ticks, FPS or production capacity. See `BENCHMARKS.md` for exact workloads, results and limitations.
