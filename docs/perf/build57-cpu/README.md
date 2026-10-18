# Build 57 performance implementation and measurements

This is the incremental evaluation for PR [#93](https://github.com/pirut/earth-cubic-platform/pull/93), based on released combined build 57, not the earlier build 54/55 CPU work in [far-cpu](../far-cpu/README.md). The changes reduce repeated CPU work without changing requested detail, reach, textures, geometry, fades or shader inputs. The water depth experiment and stricter upload pacing remain **disabled by default**.

The production candidate delivered about **6% higher stationary FPS without shaders**, with little change during movement or under Photon. A matched same-process command-cache comparison isolated about **7% higher no-shader stationary FPS** and **less than 1% under Photon**. This is not a general stutter improvement: no-shader stationary p95/p99 frame times worsened. Mean-FPS gains do not clear the performance acceptance gate; tail attribution remains open alongside the unfinished shader-free full-job audit. Component microbenchmarks are substantially faster in some workloads, but do not establish equivalent whole-generator or FPS gains. Do not add these percentages.

## Current policy after the tail regression

Command caching is now **off by default**. Opt in only for diagnostics with `-Dearthplatform.far.cacheCommands=true`. The remaining allocator, coverage and river changes retain their existing defaults. This restores the original command assembly path; it does not establish a driver-level cause or a new measured performance result.

Every client FPS, percentile, image and generator result below belongs to the earlier tested gameplay source `cc56ae0`, including its default-enabled command cache. Those results must not be attributed to this policy revision. The new default still needs a matched Mac retest. Both approved ideas now have **off-by-default bounded prototypes**. Their exactness evidence, negative shadow GPU result, packet integration limits and Apple/NVIDIA/AMD coverage are in [prototype report](prototypes/README.md). Neither is accepted as a production optimization.

On 2026-10-18 the continuation found no benchmark/game JVM running. A native display query identified the online built-in Color LCD after a brief wake request. A subsequent GLFW probe and Gradle build were started, but the Mac execution and desktop transports disconnected before their results could be retrieved. No new client benchmark is claimed. No game instance, installed JAR, personal world or shader pack was changed by this continuation before the disconnect. The local build/probe process state must be checked after reconnecting before another test is started.

## Diagnostic GPU timestamp implementation

An opt-in `-Dearthplatform.far.gpuTimers=true` now issues timestamp pairs around near/full opaque draws, each depth-smoothing direction, the combined sun/occlusion fullscreen pass, and water merge/deepen/colour/restore. Timestamp pairs allow nesting without sharing a TIME_ELAPSED query target. Where absolute timestamps have no counter bits, a non-nested TIME_ELAPSED backend skips occupied targets, rejects ownership loss and reads only its completed result. The ring uses at most 128 pairs and polls only earlier frames. It reads results only when both timestamps report GL_QUERY_RESULT_AVAILABLE; samples older than 120 frames are deleted and dropped, with fresh names allocated lazily. Retained samples are also bounded. No glFinish or NO_WAIT assumption is used. Counter wrap is handled conservatively, and unsupported contexts remain inactive.

The render-thread diagnostic consumer can drain pass/frame, CPU submission duration, GPU elapsed duration, age and range/face metadata from `FarGpuTimers.drain()`; unknown counts are -1. Opaque range counts are supplied by the actual draw. Readouts contain no disk IO during rendering. World renderer teardown deletes query names without waiting. Nine headless state-machine tests cover availability, saturation, overdue/aborted queries, stale tokens, nesting, wrap, bounded readouts and cleanup.

This is instrumentation, not a measured optimization. A native M4 Pro context has since been verified: timestamp counter bits are zero, but non-nested TIME_ELAPSED has 32 counter bits and the bounded fallback was measured successfully. Live observer-overhead and per-pass client measurements are tracked in the [continuation report](prototypes/README.md). NVIDIA and AMD native verification remains pending. The existing sun/AO shader is one draw, so its timer reports their combined GPU duration. Separating those components would require a separate diagnostic experiment.

## Revision, hardware and settings

- Reference: main/release build 57, `8e3943461c3ea5bed688ad7c15c802d1dfb3f7c9`.
- Tested source: `cc56ae0a23e274fe4a73e748c1bfa2de08feb74b`, branch `codex/build57-performance`. Later documentation commits do not change the tested implementation.
- Reference combined JAR SHA-256: `978abbd961c2b4f1a826da94c9e7255d22b1a06945fc34b43a1b553b2c291ed0`.
- Production candidate JAR SHA-256: `1a39cbdbfd0fd33971de163b243404c14b37465119d0b2d64dde0ed13b188cbe`.
- Apple M4 Pro: 14 CPU cores (10 performance, 4 efficiency), 20 GPU cores, 24 GiB RAM, Mac16,7; macOS 27.0 build 26A428, AC power.
- Microsoft OpenJDK 21.0.7+6 ARM64, G1, 512 MiB initial / 6144 MiB maximum client heap. OpenGL 4.1 Metal 91.7.
- Minecraft 1.21.1, NeoForge 21.1.262, Sodium 0.8.13, Iris 1.8.14-beta.1 at runtime. Compilation used the pinned NeoForge 21.1.259 / Sodium 0.8.12-beta.1 inputs.
- 1708 × 960 framebuffer (854 × 480 points), vsync off, FPS cap 260, Fancy, FOV 90, render distance 12, simulation distance 12, Sharpest, detail 11 / about 94 km far reach, eight textured levels.
- Stock Photon v1.3b ZIP SHA-256: `120897768eaa8cb1f14a3e98f88dff009ae3204ee3b96904a4fe8586edfc1ead`. Existing driver compatibility adaptation remained active; the runtime GLSL is therefore not claimed to be unmodified stock GLSL.
- Disposable spectator Earth worlds, seed 20261013, Yosemite, clear weather / fixed time 6000 / no mob spawning. A quiet one-player integrated server; this does not measure multiplayer capacity.

No quality settings were reduced. Other user applications were left open. The Mac already had roughly 7 GB of swap usage and memory compression, so absolute memory and tail-latency results have that limitation. No benchmarks overlapped a build, another benchmark or JFR export.

## Protocol and production client results

Runs used separate normal reference and candidate JAR launches, warm-up and settled queues, then fixed stationary and moving paths. Most conditions have two 30-second windows; near and high-altitude conditions have one. Frame times came from the diagnostic driver's per-frame timestamps; average FPS is 1000 divided by mean frame time, not an average of instantaneous FPS. Percentiles are frame-duration percentiles in milliseconds. Tick samples, process CPU/RSS, GC logs, JFR, settings, logs and real screenshots were retained locally.

All retained client windows had **zero data downloads**. Geodata on disk was warm. Fresh-world and cold in-memory generation is distinguished from a cold disk/download test; this implementation comparison did not clear the user's geodata cache. It cannot establish download/network acceleration.

| Condition | Reference FPS | Candidate FPS | p95 ms, reference → candidate | p99 ms, reference → candidate |
| --- | ---: | ---: | ---: | ---: |
| No shaders, stationary, run 1 | 42.5 | 44.9 | 39.6 → 43.8 | 41.5 → 44.8 |
| No shaders, stationary, run 2 | 42.1 | 45.0 | 39.5 → 43.7 | 42.2 → 44.5 |
| No shaders, moving, run 1 | 27.0 | 27.3 | 49.9 → 49.3 | 67.4 → 64.9 |
| No shaders, moving, run 2 | 28.7 | 28.3 | 46.3 → 47.8 | 63.4 → 64.9 |
| Photon, stationary, run 1 | 26.2 | 26.3 | 40.2 → 40.1 | 41.3 → 41.1 |
| Photon, stationary, run 2 | 26.1 | 26.3 | 40.2 → 39.9 | 41.0 → 41.0 |
| Photon, moving, run 1 | 18.5 | 18.7 | 70.9 → 70.3 | 82.1 → 79.3 |
| Photon, moving, run 2 | 20.6 | 20.8 | 66.2 → 65.0 | 81.9 → 80.0 |
| Near terrain, stationary | 39.8 | 42.1 | 35.2 → 37.9 | 37.6 → 40.6 |
| Near terrain, moving | 29.2 | 29.0 | 43.1 → 42.6 | 58.0 → 60.3 |
| High altitude, Y=10000 | 85.2 | 86.7 | 22.4 → 22.6 | 23.0 → 23.1 |

The steady server remained at 20 TPS; candidate mean tick times were about 0.8–2.1 ms. That quiet workload has substantial tick headroom and supplies no server-capacity improvement claim. No OOM or unbounded memory growth was observed. The largest noted candidate near-moving GC pause was 33.15 ms; GC still occupied a small fraction of the run. RSS includes compressed/shared macOS memory and is not a standalone leak test.

The initial fill window rose from 37.1 to 39.4 FPS, but this loading result is not a steady-state result. Photon reload's first 60 seconds worsened from 11.6 to 10.8 FPS; p99 rose 287.6 → 426.1 ms and maximum frame 415.0 → 528.8 ms. The subsequent settle check took 72.5 → 77.9 seconds, making the combined reload sequence 132.5 → 137.9 seconds (about 4% longer). This is one unpaired reload comparison, not a confidence interval or isolated attribution. Other transitions were broadly comparable. These results do not support a universal improvement to loading or stutter.

The no-shader `far draw` CPU-wall section fell about 7.2 → 4.1 ms (43% less section time). Photon remained around 25.5 ms. These timers include OpenGL calls and possible waits; they are **not GPU timers**. Photon stationary process CPU was about 81% → 71% of one core, approximately 12% less observed process CPU, without meaningful FPS improvement.

## Stationary tail regression: command-cache attribution

**Acceptance remains blocked.** The saved same-process off/on/on/off control reproduces the adverse no-shader tail behavior when only command caching changes:

| Command cache, in time order | FPS | p95 ms | p99 ms | Frames >40 ms | p95 after trimming first/last 2 seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| Off | 41.6 | 38.7 | 42.0 | 3.7% | 39.6 |
| On | 44.7 | 44.0 | 44.8 | 18.2% | 44.1 |
| On | 44.9 | 43.8 | 44.6 | 16.9% | 43.8 |
| Off | 41.8 | 39.9 | 42.2 | 4.9% | 40.0 |

These are four 25-second windows in one JVM, with the same geometry/allocation map and other optimization flags fixed. Both enabled windows have p95 about **3.9–5.2 ms higher** (approximately 10–14% longer frame duration) and p99 about **2.3–2.8 ms higher** (approximately 6–7%) than the corresponding disabled windows. Frames exceeding 40 ms rose from 3.7–4.9% to 16.9–18.2%. Disabling the flag at the end restored the earlier distribution, despite being later in the run.

No GC pause occurred during any of these four windows. Every recorded selection/allocation version and scene counter stayed fixed within its window: 17,005 visible columns, 27,535 ranges, 952,325,376 owned bytes, no meshing or requests, and zero measured far-upload time. Removing the first and last two seconds does not remove the effect; it also persists in each enabled window's thirds. The normal production reference/candidate stationary windows independently show the same direction.

This is enough to associate the regression with **command caching enabled in this settled no-shader scene on this Mac**. It does not establish a portable code defect or separate CPU scheduling, driver queuing, GPU execution and presentation. CPU-observed RenderFrameEvent.Post intervals are not measured GPU/display presentation intervals. Individual frames are autocorrelated, and the two adjacent enabled windows are not independent multi-launch trials; no per-frame statistical confidence claim is made. The source review found matching submitted commands/order, but matching output does not explain the changed cadence.

The original measurements did not validate a correction. Command caching has since been changed to **off by default**; the command-cache opt-in remains an **unaccepted draft behavior**. A mean-FPS gain is insufficient to approve it. If a correction cannot preserve tails, the command-cache default should be off until it can; any proposed policy/correction needs the matched verification below before acceptance.

Required validation when the display returns: three fresh-launch blocks, alternating ABBA and BAAB starting states; four 60-second windows per block after at least 60 seconds of warm-up, settled queues/fades and a cache audit outside each window. Keep source, world, exact view/allocation map, quality settings, 260-FPS limit and vsync-off state fixed; change only the command flag. Capture frame IDs with CPU render/submit and swap/present-call boundaries, plus supported asynchronous GPU timestamps. First quantify instrumentation off/on overhead against natural repeat variance. Record p95/p99, >40 ms frequency, GC, queue/version stability and output equivalence. Compare any proposed fix directly with the disabled path; require tails to recover within demonstrated repeat variability. Do not mask the issue by silently changing visual settings or the frame cap. Complete the outstanding shader-free full-job audit as a separate quality gate.

Recompute the saved-data finding with `python3 harness/analyze-command-tails.py`; detailed sanitized rows, trim checks, runtime counters and input CSV hashes are in `results/command-tail-analysis.json` locally and in the accompanying measurements' `command_isolation.tail_analysis` field. This analysis launches no client and changes no gameplay source.

## Findings, safeguards and measured scope

| Change / source at tested revision | Evidence and expected benefit in the measured workload | Effort / residual risk |
| --- | --- | --- |
| Opaque command reuse: [FarGpu.java:360](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarGpu.java#L360), [FarRenderer.java:836](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarRenderer.java#L836) | Same-process off/on/on/off 25-second windows: no-shader 41.6/41.8 → 44.7/44.9 FPS, about 7%; Photon 26.2/26.2 → 26.3/26.3, less than 1%. Moderate confidence for this stationary scene; no moving-FPS promise. | Implemented. Cache invalidation is the principal risk. Keys retain owner identity, exact selection epoch, allocation version and raw near-reach float bits. Near and full passes are separate; fades, programs and uniforms still update. |
| Size-indexed allocation: [FarArenaRanges.java:49](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarArenaRanges.java#L49) | Six alternating synthetic pairs: log-size 1–10000-face allocations improved about 69–103% in operations/s; small uniform allocations 126–265%; large fragmented traces roughly 0–2%. Simple large FIFO became 38% slower. No isolated player-visible gain was established. | Implemented. Medium allocation/fragmentation risk. Deterministic best fit, address coalescing and identity tokens reject stale/foreign releases. Randomized ownership/occupancy tests cover 160,000 operations. Comparison uses the same ownership checks in both helper paths. |
| Coverage precomputation: [FarCoverage.java:20](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarCoverage.java#L20), FarColumnMesh/FarMesher | Six alternating pairs, construction included, 48-row synthetic fixtures: predicate-workload jobs/s rose 170% at level 1, 325% at level 4 and 262% at level 8; empty fixture changed −0.2%. These are not full-mesh or FPS percentages. | Implemented. Medium boundary risk. Exact dropped voxels/fractional bounds remain; exceptional/out-of-footprint coordinates use the original predicate. Tests include all 65,536 drop values and levels 1–8. Live Photon job output equivalence is described below. |
| River lookup/allocation reduction: [RiverMap.java:98](../../../geography/src/main/java/io/earthplatform/geography/globe/RiverMap.java#L98), [near:180](../../../geography/src/main/java/io/earthplatform/geography/globe/RiverMap.java#L180) | Six alternating pairs, 8192 varied points: strength samples/s rose about 300% in one-tile fixtures and 96% in a 16-tile fixture with 10% seam points. Allocation fell 96 → 0 bytes/sample (single tile), 135.5 → 39.5 (mixed). Nearby-maxima queries/s rose 405% / 82%. No reliable full-generator gain was established. | Implemented. Medium determinism risk. Same arithmetic order; seams, poles, wraps, saturated and nonfinite coordinates retain the reference path. Tests use exact non-NaN raw bits, boundary/extreme points, two maps and eight threads. NaN payload identity is not portable across CPUs. |
| Water depth batching: [FarWaterBatch.java:13](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarWaterBatch.java#L13), FarWaterBatchPlan/FarLod | Verified startup-enabled off/on/on/off 20-second Photon windows: 25.9/26.2 → 26.5/26.6 FPS, about 2%; `far water` CPU-wall 8.11/8.01 → 5.52/5.59 ms, about 31% less section time. Expected scene benefit remains approximately 0–3% FPS with low confidence outside this view. | Experimental and off. Medium memory/convergence/shader risk; 32 MiB retained source positions plus at most 16 MiB per GPU pass, shared index growth additional. Whole-pass fallback on missing/sorted/closed/nonfinite/overbudget data. Original GPU Position + Offset operands/order retained; Photon color draws and extended attributes unchanged. |
| Strict upload admission: UploadBudget/FarRenderer | No valid evidence of equal convergence/pop-in and a net user-visible improvement. No percentage assigned. | Experimental and off. First valid mesh progresses; rejected FIFO head remains. Do not enable merely to improve frame time while delaying terrain. |

These component percentage ranges describe their own fixtures. They are not additive, and they do not justify the larger end-to-end estimates made before implementation.

## Full-generator control

Four fresh-JVM runs in ABBA order (reference, optimized, optimized, reference) used warm geodata, a 3 GiB heap, four threads, 20 far columns per level and the Yosemite fixture. Gradle task caching/up-to-date reuse was disabled for the final four runs; early reused-task results were excluded.

Each produced 6,357 cubes / 3,382 nonempty cubes and 7,549,066 network-format bytes, with zero downloads and the same disk-hit counts (elevation 129, cover 27, climate 279). The 11 printed 64-bit SHA-256 prefixes agreed; combined prefix `ae76effbc45b216f`, cube prefix `68bdfa4f6c1f1fce`, far prefix `ed6fb77176bd74c2`. These are **prefixes**, not full SHA-256 digests.

Reference cube throughput was 3,700 / 4,411 cubes/s; optimized 3,950 / 6,022. The weighted far-generation proxy was 85 / 102 CPU-seconds versus 94 / 98. Run variation prevents a reliable overall cube/far-generation percentage claim. Faster sampling is real in the controlled microbenchmark; a full-generator speedup remains unproven.

## Quality evidence and exclusions

The normal production launches passed independent live checks of byte ownership, fresh/cached selection and water ordering at stationary, moving, near, sub-block, turn, reload and editing phases. Real no-shader, Photon and underwater images were captured. The command-only same-process test retained the same geometry and allocation map:

- No-shader repeat captures and off/on comparison: **0 of 1,639,680 RGB pixels changed**, and zero main-depth raw float-bit differences.
- Photon command comparison: mean absolute RGB-channel difference 1.891/255, versus 1.644–1.646/255 for unchanged-path repeats. Main depth matched exactly. Far-depth and face buffers vary with temporal jitter even on repeated captures. This does not prove Photon pixel identity.
- Actual water batching comparison: mean RGB difference 2.170/255 versus 1.958–2.089/255 repeats; main depth matched exactly. Far-depth raw bits changed 76.16% cross-path versus 76.45–76.50% in unchanged-path repeats. This is a bounded temporal comparison, not a universal visual guarantee.

The corrected live **Photon full-job mesh audit passed 24 jobs at levels 1–8**, including 22 masked jobs: 13,297 quads, 59 water parts, 9,448 vertices, 491,296 vertex-buffer bytes. It rebuilt each identical immutable job with coverage caching off and on and compared quad/count/grid/look data, index data and every declared vertex-format attribute exactly. Declared attributes occupied 472,400 bytes; 18,896 padding bytes were separately inspected and 1,604 differed. No declared attribute or geometry differed. BLOCK stride 32 has padding byte 31; Iris terrain stride 52 has padding bytes 31 and 51. The earlier raw-buffer assertion failed on this uninitialized padding and was superseded by the declared-layout audit; it is not silently counted as a pass.

Actual water proof verified all 16,055 water sources retained at the fixed view, using 19,051,632 source-position bytes. Two valid batches replaced 2,769 source depth draws: deepen 1,375 sources / 258,580 vertices / 6,205,920 bytes; restore 1,394 / 259,116 / 6,218,784. Near the test pond, both larger passes still fit the caps and no retained source was missing. A startup-property check was essential: an earlier runtime-toggle water trial lacked retained positions because Prism cached JVM arguments; those earlier water timings are explicitly excluded. The valid enabled reload took about 152.7 seconds versus 137.9 seconds for the normal candidate in a separate comparison (about 11% longer); this further supports leaving batching off.

After the valid water windows and Photon mesh audit, a diagnostic assertion compared an inactive underwater far selection with a fresh one and terminated that diagnostic client. The production renderer intentionally skips far drawing in water/lava/powder-snow fog at [FarRenderer.java:802](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarRenderer.java#L802). The driver was corrected to report that inactive pass as skipped and to retain other results. This was a diagnostic assertion, not a production gameplay exception.

The final shader-free full-job mesh and underwater entry/exit rerun was blocked **before mod loading** when GLFW could not find a primary monitor. Shell access later recovered, but system_profiler still returned the GPU without any display entry after a temporary five-second display-wake request. No security settings were changed and no unlock was attempted. The additional no-shader full-job audit remains pending. The actual enabled-water pond, underwater and fixed-view screenshots were visually reviewed with no obvious structural defect; this is not an exact pond off/on comparison. Existing exact no-shader command/depth checks and the Photon full-job check remain valid; this does not establish exhaustive shader/mod compatibility. The PR remains draft and must not be merged on a blanket “no quality drop” claim.

## Remaining opportunities and exact next experiments

1. **Cache the cloud height grid.** [EarthClouds.java:178](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/play/client/EarthClouds.java#L178) rebuilds clearance from all held far columns; the settled view held 58,909. After command caching, EarthClouds.clearance / FarRenderer.forEachTop became prominent Java stacks. Cache only the terrain-height grid using exact camera/grid inputs plus a terrain-height revision, and keep deck/opacity animation live. Speculative benefit: **5–15% higher no-shader stationary FPS**, low confidence; no Photon gain established. Estimated effort 1–3 engineering days, medium invalidation risk. Validate off/on/on/off after warm-up, exact RGB/depth and cloud animation at stationary, sub-block movement, edits, arrivals, eviction and weather/deck transitions. No implementation is included here.

2. **Separate Photon GPU work from submission/waits.** JFR's Photon native samples still included glMultiDraw in 42/64 reference and 46/79 candidate native samples. Sample shares and CPU-wall section times cannot attribute GPU cost. In a diagnostic-only build, first verify GL 3.3/ARB_timer_query, nonzero query-counter bits and entry points. Use timestamp pairs in a bounded ring for near/full opaque, far depth/deepen/restore, sun/horizon and water passes; retrieve only older queries whose GL_QUERY_RESULT_AVAILABLE is true, and drop overdue samples instead of waiting. Record CPU-wall time, GPU elapsed time, frame ID, draw/range/face counts and result age. Use alternating instrumentation-off/on windows to quantify observer overhead; keep all quality settings and output unchanged. Timestamp pairs avoid conflicting nested TIME_ELAPSED queries. Do not use per-frame glFinish or blocking result retrieval. Large CPU-wall versus small measured GPU duration would motivate submission/synchronization experiments, but does not by itself identify the originating stall. No speed percentage is defensible before this measurement. See the [Khronos query documentation](https://wikis.khronos.org/opengl/Query_Object) and [query result specification](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glGetQueryObject.xhtml). OpenGL 4.1 alone does not promise the 4.4 NO_WAIT query mode; poll availability explicitly.

3. **Investigate exact-output depth specialization / overdraw.** [FarShaders.java:470](../../../minecraft/src/main/java/io/earthplatform/minecraft/cubic/client/FarShaders.java#L470) and PACK_MAIN statically write gl_FragDepth through earth_slopeDepth. This may restrict early-depth rejection; its cost on this Apple driver is unmeasured. Smooth ground is off by default, so a proven depth-neutral specialization primarily applies to normal=0 foliage/undersides in current voxel mode, not broadly to default smooth terrain. Do not remove stepped slope correction or force early tests that ignore the required depth result. First count eligible pixels/faces, then compare exact-output specialized programs and/or an exact corrected-depth prepass in a disposable diagnostic with asynchronous GPU timings. Preserve alpha/discard, fades, material inputs, TAA jitter, near/far depths and corrected stepped depth; compare declared attributes, main/far depth and temporal-control image differences. A prepass can cost more than it saves. No implementation or gain estimate is included. [Khronos early fragment tests](https://wikis.khronos.org/opengl/Early_Fragment_Test) explains the depth-write constraint; [gl_FragDepth](https://registry.khronos.org/OpenGL-Refpages/gl4/html/gl_FragDepth.xhtml) defines the output requirement.

## Reproduction and retained artifacts

The accompanying [measurements.json](measurements.json) contains sanitized rounded aggregates. Raw worlds, JFR recordings, screenshots, personal paths/configuration and private logs are not committed.

The task workspace retains `harness/`, `inputs/`, `results/` and `evidence/`. Production sets: `results/impl-baseline`, `impl-candidate`; command controls: `impl-quality`; actual water/full-Photon-mesh proof: `impl-water-proof`; failed final startup: `results/impl-final-audit` and `results/impl-final-audit-launch.log`. CPU/RSS CSVs and memory snapshots accompany each run. Profiles: `results/profile-implementation.json` / `.txt`, `process-implementation-summary.json`. Component inputs/results: `CoverageBench.java`, `AllocatorBench.java`, `RiverSamplingBench.java` and their harness documentation; `coverage-bench.csv`, `allocator-v2-*.csv`, `river-sampling-v2.csv`. Generator control: `harness/generator-comparison.py`, `river-bench.init.gradle`, four `generator-v2-{ref-1,opt-1,opt-2,ref-2}.log` files and `generator-v2-summary.json`.

Build with the pinned inputs in [BUILDING.md](../../BUILDING.md), JDK 21, and the local Photon input for the otherwise file-dependent compatibility test. The recorded local command was `zsh harness/build-implementation.sh test jar --continue` with EARTH_PHOTON_ZIP set to the existing authorized input. Final local suite: **124 tests total, 122 passed, zero failures/errors, two opt-in benchmark skips**. Source-head [CI run 39155485920](https://github.com/pirut/earth-cubic-platform/actions/runs/39155485920) passed. Do not use a Gradle UP-TO-DATE/FROM-CACHE generator task as a measured run: the harness disables up-to-date reuse, supplies the 3 GiB test JVM heap and passes the river property into the forked test JVM.

For client reproduction use the fixed settings above, the existing shader compatibility settings, and disposable worlds. Commands:

```text
/gamemode spectator
/gamerule doDaylightCycle false
/gamerule doWeatherCycle false
/gamerule doMobSpawning false
/weather clear
/time set 6000
/tp @s -10530225.5 1572 -3593832.5 90 8
# Near view:
/tp @s -10534004.5 1269 -3591394.5 90 8
# High altitude: fixed far x/z, y=10000
```

Wait for queues/fades to settle, warm up, record repeated fixed-duration windows, then execute the same moving route/turn/transition script. Restart for production flags; most are final at class load. Default controls:

```text
-Dearthplatform.cubic.perf=true
-Dearthplatform.far.cacheCommands=false
-Dearthplatform.far.indexedAllocator=false
-Dearthplatform.far.cacheCoverage=false
-Dearthplatform.rivers.fastSample=false
```

Use one control at a time to isolate a component, or all four for an old-path bundle control. Keep pre-existing build-57 optimizations and indexed face drawing unchanged. Water is opt-in with `-Dearthplatform.far.batchWaterDepth=true`; verify the **actual JVM property at startup** and retain source positions before testing. Strict upload pacing stays off. The same-process diagnostic JAR cleared final from only five test-control fields; methods and other archive entries were verified unchanged. It is not the shipped JAR.

Runtime scripts and audit source remain local: `EpTest.java`, `CoverageOutputAuditSemantic.java.txt`, `CoverageVertexAuditNotes.md`, `implementation-final-audit.txt`, and `quality-compare.py`. Rebuild the driver only between performance runs. `python3 harness/quality-compare.py results/impl-quality` and `python3 harness/quality-compare.py results/impl-water-proof water` reproduce the pixel/depth aggregates. Never run builds, microbenchmarks or profiler exports during client windows.

Final local cleanup is complete, recorded in `results/impl-final-audit/cleanup.json`: no benchmark JVM/monitor remained, the failed startup was archived, the ordinary wrapper and production JAR were restored, the diagnostic mod/driver and test-only water sentinel were removed, and the automatic script was disabled. All results and personal instances/worlds were preserved. Complete the final shader-free audit before declaring the quality gate complete. 
