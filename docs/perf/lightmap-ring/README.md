# Disabled cubic lightmap texture-ring experiment

This separate prototype follows the reviewed build-59 baseline `6ff363560ff5173740ec641c465c72caf59a1633` (PR96). It is disabled by default. It does not change terrain, distance, geometry, shader formulas, the lightmap pixel computation, or the upload cadence. The ordinary constructor remains the default. Opt-in local evaluation uses `-Dearthplatform.cubic.lightmapRing=true`; this is not a player requirement or an accepted production speedup.

## Measured lead

On the M4 Pro's native OpenGL 4.1 Metal 91.7 driver, a converged shader-free scene spent about 16.65–16.94 ms in the host call that uploads the 16×16 lightmap. Moving a `glFinish` immediately before it moved the wait to the finish call (17.13 ms) and left upload at 0.018 ms without increasing FPS. This points to synchronization with earlier GPU work; it is not a measurement of lightmap GPU execution time. Reallocating storage on the same texture did not help and was rejected. Photon did not show this upload stall in the control scene.

A local-only reflected texture experiment on unchanged earlier source `f0aa3f9` produced 47.16 / 50.17 / 50.15 / 46.81 FPS in original/ring/ring/original order, with p99 39.33 / 24.02 / 24.32 / 39.48 ms. Those numbers belong to that diagnostic, not to the implementation below. Its screenshots were captured after restoring the original texture and do not establish active-ring scene quality.

## Implementation and boundaries

Only the 16×16 `LightTexture` allocation is wrapped. When explicitly enabled, a `DynamicTexture` subclass retains the original vanilla `NativeImage`, pixel computation and full upload, and rotates the destination among the original texture plus two owned names. The additional texel storage is 2,048 bytes, excluding driver object overhead. No image copy, reflection, fences or `glFinish` is added to product code.

Eligibility requires the cubic world, no active shader pack, RGBA 16×16 pixels and OpenGL 3.3 support. Setup checks the original level-zero storage and allocates matching storage, copies filtering, wrap, LOD, compare, swizzle, border and supported anisotropy state, and verifies allocation dimensions/format. Unsupported storage or a caught setup failure restores the original path and records a bounded failure type. Texture-name validity respects Java's signed representation of GLuint values; zero and the vanilla -1 sentinel are rejected.

Near terrain's RenderSystem lightmap slot follows rotation; far terrain obtains the same current texture. A shader/world change restores the original and synchronizes its pixels even on a frame without a vanilla lightmap update. The original name remains owned by `DynamicTexture`; the pool owns only the two extras. Initialization rolls back transactionally. Release restores ownership, disposes both extras exactly once, and retains vanilla render-thread scheduling and original-name cleanup. Pure tests cover rotation phases, failed/duplicate allocations, cleanup exceptions, unexpected external names and unsigned names.

## Native equivalence evidence

The first implementation matrix on exact `945fd02fe17eb167ace89a7d6567cd1e40dd53ef` passed 19 GPU readback checks, comparing 13,568 RGBA pixels. It checked day, dusk, night, gamma, night vision and fade, darkness and fade, weather flash, water/conduit vision, a water edit, resize/restore, Photon fallback, shader-free reactivation, menu/ordinary-world fallback, reopening and explicit release/reallocation. Live near-slot checks reached 431,642 and actual far sampler-binding checks reached 9,770 with no mismatch. Every recorded product failure state was false. All three old texture names ceased to exist on explicit release. Screenshots were captured with the ring active and inspected; native readbacks establish the lightmap pixels, not identical whole-frame screenshots under animated effects.

The follow-up source `ea99f960f66306362e5a9a59e6be1ab3f30d4200` adds storage, sampler and unsigned-name guards. Full validation with the checked stock Photon ZIP passed: 170 tests, 166 passed, four optional skips, zero failures/errors, and `verifyShimArtifact` passed. The exact hardened JAR (SHA-256 `e98b6d20d6a7b380c72f98096d774210bf11bde7dd887045967573ff1400ae76`) passed a second complete matrix: 24 readback checks, 17,408 RGBA pixels, 531,232 near binding checks and 11,693 actual far bindings. This includes gamma 0/.5/1, placed/removed light, a 1,200-block route, shader/world changes and release/reallocation. Every recorded product failure state was false; seven explicit failure-counter checks returned zero. Normal final save-and-exit took 353.12 ms.

## Implementation controls

The first exact-implementation block used one unmodified JAR in one JVM. A local-only driver selected the original upload path by forcing the prototype's fallback flag, then re-enabled the ring. This is a same-JAR path comparison, not a separate released-build baseline. No product reflection is added. After five seconds of settling per switch, each window recorded 45 seconds with timing-only probes; all readback and live-binding assertions were off. Full distributions, the two-second edge-trim diagnostic and every large frame are retained.

| Path, in time order | FPS | p95 ms | p99 ms | Frames >40 ms | Frames >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Original | 47.18 | 37.48 | 38.77 | 0% | 0 |
| Ring | 50.12 | 23.29 | 24.02 | 0% | 0 |
| Ring | 50.13 | 23.17 | 23.90 | 0% | 0 |
| Original restored | 47.10 | 37.22 | 38.47 | 0.094% | 0 |

That is about 6.3% higher mean FPS in this block with improved tails. Terrain/refinement, visible/range counts, owned bytes, selection/allocation versions and empty meshing/request queues stayed fixed in all four windows. No download counter changed during the windows. The preceding functional movement test fetched nine elevation files (0.8 MB); the entire launch is not a zero-download run.

Upload host time averaged 16.75 / 0.020 / 0.020 / 16.58 ms. Sampled far-opaque GPU means were 19.50 / 19.60 / 19.66 / 19.49 ms (15/17/15/16 samples respectively), with query age at most three frames and no dropped queries. These scopes do not measure a full GPU frame or lightmap GPU time, and do not establish less GPU execution work. The result supports reduced host synchronization and better observed cadence. FPS/percentiles use client render-event intervals, not measured display presentation or input latency. Adjacent frames are correlated; no per-frame confidence interval is claimed.

A fresh JVM reversed the order. All six storage/sampler capture/audits passed across 23 fields, and the final GPU readback matched all 768 ring pixels. No download occurred in this second launch. All four fixed-view scene/version snapshots remained stable within each window.

| Path, reversed block | FPS | p95 ms | p99 ms | Maximum ms | Frames >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ring | 50.15 | 23.34 | 24.32 | 34.52 | 0 |
| Original | 46.77 | 36.33 | 37.89 | 149.65 | 1 |
| Original | 47.03 | 37.08 | 38.16 | 62.07 | 0 |
| Ring restored | 50.13 | 23.32 | 24.42 | 38.76 | 0 |

The 149.65 ms baseline frame is retained and unclassified. The first block's mean gain is about 6.3%; the reverse block's is about 6.9%. They are two launch blocks on one Mac, not independent per-frame samples or a portable confidence interval. GPU far-opaque means stayed 19.57–19.59 ms in the reversed block, with no query drops.

### Movement and convergence

Each moving window reopened the same disposable world, returned to the same start/heading, waited 25 seconds plus queue/refinement settling and five seconds after selecting the upload path, then flew 1,200 blocks in 600 client ticks. The script advances only after the flight; ten additional ticks end the window at about 30.6–30.7 seconds. No file download occurred. Geodata was warm; renderer state was rebuilt after each reopen. This does not measure cold disk/download or standalone generator speed.

| Path, route order | FPS | p95 ms | p99 ms | Maximum ms | Frames >40 ms | Frames >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Original | 31.85 | 42.67 | 52.04 | 255.62 | 7.79% | 3 |
| Ring | 34.79 | 40.82 | 52.72 | 72.48 | 5.82% | 0 |
| Ring | 34.89 | 40.75 | 50.77 | 74.71 | 5.89% | 0 |
| Original restored | 32.80 | 42.36 | 53.58 | 73.10 | 6.87% | 0 |

Mean FPS is about 7.8% higher when averaging the two path means, and p95 improves in both ring windows. p99 is mixed: the first ring window is 0.68 ms slower than the first baseline, while the second is faster than either baseline. The ring's second maximum is also 1.61 ms longer than the last baseline maximum. Every large frame is retained; this is not a claim that all movement tails improve. Asynchronous arrivals/refinement are part of the route and were not identical per-frame workloads. The route/settings/target reach were fixed and all workload counters are retained.

After the initial 25-second waits, setup settle times were 29.64 / 28.64 / 28.55 / 28.24 seconds. Post-route settle times were 2.85 / 3.11 / 3.00 / 3.04 seconds. These small differences establish no loading/convergence speed gain. Moving upload host means were 4.87 / 0.016 / 0.015 / 4.58 ms; the sampled far-opaque GPU means were 18.60 / 18.91 / 18.83 / 18.70 ms (6–7 samples each). No whole-frame GPU reduction is claimed. All explicit product failure-counter checks returned zero and every world exit completed normally.

Sanitized identities, full and edge-trimmed distributions, host timings, workload snapshots, sampled GPU scopes and convergence data are in [measurements.json](measurements.json). Raw frames, scripts, complete local fixture sources, JARs and logs are retained under the task's `results/lightmap-final-matrix/` and `results/lightmap-reverse-controls/`.

Tests use disposable worlds in an isolated Prism instance, Sodium 0.8.13 and Iris 1.8.14-beta.1, a working built-in Retina display and native framebuffer screenshots. No personal world is used. Raw scripts, fixtures, private logs and screenshots stay in the local task workspace. Assertions/readbacks run outside FPS windows. The normal bounded asynchronous GPU observer and automatic logger remain configured identically in controls. GPU pass durations describe sampled render scopes; upload-call host timing is separate.

## Acceptance status

The prototype stays disabled. The Mac tests establish a promising local synchronization improvement and exact sampled lightmap/binding equivalence. They do not establish uniformly better moving tails, portable driver behavior, or exhaustive mod compatibility. Independent review and broader hardware/tail evidence remain requirements before a default change. NVIDIA/AMD native behavior and a real VR headset remain untested. No portable gain or exhaustive mod compatibility is claimed.
