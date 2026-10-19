# Experimental quiet-view lightmap policy

This branch starts on reviewed PR #99 (`9ef41d3`) and imports the reviewed, disabled PR #98 ring code (`35c302a`) unchanged before a separate policy commit. The existing experimental path remains disabled by default. No release, default activation, player setting, shader, geometry, distance or NativeImage color formula change is included.

The policy rotates the existing original-plus-two-extra texture names only after 500 ms of unchanged camera position, yaw, pitch and viewport in a settled scene. During movement, near/far streaming, resize or other invalidation, existing vanilla updates select the original texture and retain the two extras. No upload is skipped, moved or added by the activity transition. Environment changes and close keep the reviewed original-name restoration/release behavior. A private prior-frame terrain flag is used for this performance decision because frame-start events clear the current-pass draw counter before the lightmap update; screenshot readiness still uses its current-pass counter unchanged.

Activity history does not own the world: the later `43475b0` cleanup uses a weak reference. It preserves world-identity invalidation while allowing closed worlds to be collected. The measured behavioral source was `86ff8bc`; GPU/selection logic is unchanged by this cleanup.

## Verification and exact output

Actual-Photon `verifyShimArtifact` passed 177 tests (173 passed, four optional skips, zero failures/errors) both before and after the history cleanup. New tests exercise quiet intervals, camera changes, streaming, world/viewport changes, invalid inputs, clock rollback, pool retention and allocation rollback.

Native smoke on the verified M4 Pro Retina/OpenGL 4.1 Metal 91.7 display compared 5,632 RGBA pixels and 23 sampler/storage fields, with 48,361 near and 7,867 far binding checks. A real 1,200-block / 600-tick flight produced exactly 600 vanilla and 600 module uploads; 597 post-transition checks held the original name, with zero extra allocations/releases/restores/uploads. The same extras survived the flight and resize, quiet rotation resumed, stock Photon fallback/resumption and all-name release/reallocation passed, and the failure counter stayed zero. The test installation was restored afterward. This is local Mac evidence; other vendors and physical VR were not available.

## Initial matched controls

Two fresh JVMs used reference/policy/policy/reference and the reversed order, with four 45-second stationary windows and four 30.7-second / 1,200-block flights each. Both paths used the same experimental JAR, normal automatic logger and one-in-120 asynchronous GPU observer, fixed render/simulation distances 12/12, 1708×960 framebuffer and gamma .5. A local diagnostic driver forced the reference path only in the disposable test installation. No player is asked to enable an experiment. Oracles were off during FPS windows; all primary frames and outliers are retained.

Stationary policy windows measured 50.12–50.21 FPS versus 46.98–47.14 for the reference. Policy p99 was 23.90–24.20 ms versus 37.92–39.26 ms. Host upload means were about .02 ms in quiet rotation versus 17–19 ms on the original path. This does not reduce far GPU work or an entire GPU frame.

Moving results are mixed and do not establish a throughput gain. First routes are slower regardless of order, so warm-cache comparisons cannot be promoted to gains. Two moving policy frames in the reversed JVM reached 107.68 and 152.26 ms; both remain in the primary results. They occurred 2.75 and 6.18 seconds into flight, with no texture allocations/releases/restores during either phase. Frame alignment shows .0185 ms of upload work in the first event and 117.75 ms in the second. Estimated cross-JVM clock alignment places a 36.73 ms GC pause within the first frame; it does not explain the full frame. No nearby GC pause was found around the second with the stated margin, and historical wall-clock drift is not bounded by that diagnostic. No complete cause or exclusion is claimed. Rare tails remain an acceptance concern.

Full distributions, resource snapshots, sampled GPU scopes, convergence and limitations are in `initial-controls.json`; raw frames/logs/scripts/JARs are retained in the private task under `results/lightmap-policy/`. 

## Streaming and repeated stop/start

The `43475b0` history-cleanup artifact passed four GPU oracle checks (3,072 RGBA pixels and the same 23 sampler/storage fields). With 32 water-remesh jobs queued behind held workers at a fixed camera, 45 vanilla updates produced exactly 45 module uploads. All 41 post-transition checks held the original texture, with zero extra uploads, allocations, releases or restores. Quiet rotation resumed after the workers were released.

Four matched 61.5–61.6 second windows used reference/policy/policy/reference order. Each repeatedly alternated two seconds stationary and a five-block, 20-tick movement in each direction, ten times. All primary frames are retained.

| Path | FPS | p95 ms | p99 ms | Max ms | Frames >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Reference 0 | 44.394 | 34.850 | 38.158 | 62.753 | 0 |
| Policy 0 | 46.394 | 30.725 | 36.386 | 55.024 | 0 |
| Policy 1 | 46.353 | 30.757 | 36.029 | 46.572 | 0 |
| Reference 1 | 44.674 | 34.881 | 38.384 | 52.998 | 0 |

The pool stayed bounded with no allocation/release/restoration churn during those windows, the failure counter remained zero and menu exit released the two extras. Normal world exit took 408.89 ms. Exact source/JAR identities, full distributions, convergence, sampled GPU scopes and cleanup receipts are in `transition-controls.json`.

These local controls support continued review, not default activation. The two earlier long-flight outliers remain unresolved; this shorter stop/start workload cannot exclude rare moving regressions. Independent review and final-head artifact checks are still required. No automatic supported-hardware activation is included. The ordinary integrated artifact remains reviewed PR #99.
