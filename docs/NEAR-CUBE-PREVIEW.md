# Nearby-cube streaming preview

This includes the spyglass performance preview and targets the stalls when real,
interactable 16-cubed sections replace distant terrain.

The previous renderer accepted at least 48 completed meshes on every upload
call, regardless of whether its frame-time deadline had expired. A slow frame
also increased its pending-work limit up to 512. Starting each mesh copies up
to 27 neighboring block containers on the render thread, so that queue limit
also allowed large bursts of copying and allocations during descent/loading.

Changes:

- Upload at most eight completed results per frame and stop after approximately
  1.5 ms, always allowing one operation to make progress. Shadow and main passes
  share this allowance. One indivisible mesh upload can exceed the budget.
- Start at most eight snapshots per scheduling call, stopping after about 1 ms.
  Existing distance/view priority remains. A saturated pipeline avoids rescanning
  and sorting dirty cubes just to discover that no worker slots are available.
- Keep only two to eight jobs per mesher worker in flight (six to 24 with three
  workers), rather than 48 to 512. Clamp the frame-time estimate used by the
  policy at 50 ms so a stall/pause cannot expand the backlog further.
- Discard results superseded by a newer cube/neighbor version before GPU upload
  and far-terrain masking. Dirty tracking retains the request for a current mesh.

Generation, collision, interaction range and server streaming are unchanged.
Far terrain remains the placeholder until a current near mesh compiles. Busy
areas can take longer to finish loading in exchange for less render-thread
burst work. Explicit flawless-frame capture settling can still drain its queue.

Tests cover deadline/count limits, progress after a delayed call, frame-time
spikes/pauses and monotonic-clock wrap. The complete existing suite checks world
generation, storage, mesh oracles and shader regressions. No full game is launched.

These are cooperative work bounds, not measured whole-game FPS improvements.
Dirty-cube scanning/sorting, individual uploads, world generation, networking,
draw calls, and other mods can still contribute to stalls. A running-world
comparison is needed to establish which costs remain after this change.

November 9 verification: 248 tests passed, five optional benchmarks skipped,
no failures. The five new budget regressions and existing seven real-driver
tests passed. No full-game FPS result is claimed.

Artifact: `minecraft/build/flight-preview/earthplatform-near-cube-performance-preview.jar`.
SHA-256: `2dc134677cea0fb6d50d198eccedf54aa819e19c7455529ee0870ee47f23417f`.
