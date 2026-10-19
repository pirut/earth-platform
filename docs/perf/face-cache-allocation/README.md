# Far-face cache hit allocation

The proposed change starts on reviewed PR #99 (`9ef41d3`) and changes only `FarFaces.face` / `tint`. A hit reads the existing ConcurrentHashMap value before constructing a mapping closure; a miss still uses the original atomic computeIfAbsent and the same callback, key, tint, sprite/color calculation and published value. No new cache, retained storage, feature switch, quality or distance change. The lightmap ring, PBO and tree-scratch experiments are absent.

Profiles identified recurring allocated bound bake callbacks and capturing tint callbacks on these hit paths. Native counters validate that lead rather than treating sampled allocation weights as phase totals. Source `b0414bc` passed actual unmodified-Photon verifyShimArtifact: 163 tests, 159 passed, four optional benchmark skips, zero failures/errors.

## Exact native output and hit allocation

Four fresh JVMs ran original/candidate/candidate/original. In every launch, the corpus made 6,480 look calls across 18 block states (including leaves, logs, fluids, waterlogged slab, snow and emissive blocks), six directions, five biome cases including null, both texture flags and three shader-ID maps. Cold and warm passes matched each other and the original's complete ID/tint/packed-look hash, covering 948 look records. The fixture is included in FaceCacheProbe.java. This is a bounded real-asset corpus, not exhaustive model/mod coverage; the unchanged cache keys/callbacks and atomic miss path provide the general source equivalence argument.

Every launch also passed 24 native mesher jobs across levels 3–8, 16,889 quads and 59 water parts, comparing full declared packed attributes, geometry and look tables against the dense oracle. Uninitialized allocator padding differences are reported separately, not labeled raw-byte mesh equality. These are per-artifact dense/run comparisons with identical inputs, not a claim of deterministic live scheduling or global look-ID assignment between independent JVMs.

After 300,000 warmup calls, direct current-render-thread counters around one million cached calls measured:

| Path | Face bytes/call | Cached tinted bytes/call |
| --- | ---: | ---: |
| Original, both launches | 16 | 48 |
| Candidate, both launches | 0 | 24 |

Tint measurements use 185 actual tinted cases; untinted early returns are excluded. Identity/value assertions run in the loops. These are component allocation counters, not FPS gains. Timing samples and checksums are retained in measurements.json; face identity hash checksums are JVM-specific and are not cross-JVM output hashes.

## Whole-worker counters and frame distributions

M4 Pro / verified built-in Retina / native OpenGL 4.1 Metal 91.7, 1708×960, distances 12/12, gamma .5, shader-free, same far detail/reach. Warm Yosemite geodata, disposable world, fixed day/weather/random ticks, unchanged automatic logger and one-in-120 asynchronous GPU observer. Each launch waited 25 seconds plus settling, ran the oracle/component checks outside FPS windows, then 45 seconds stationary and a flight over 1,200 blocks and 600 ticks. No separate build/profile/export overlapped FPS windows; other user apps remained open. The component warmup changes transient heap state before later windows; full GC/counter/context records are retained and no portable latency conclusion is drawn.

| Time order | Far-mesher allocation MiB/s | Stationary FPS | Stationary p99 ms | Moving FPS | Moving p95 ms | Moving p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline-0 | 308.997 | 47.056 | 39.242 | 32.573 | 42.485 | 54.981 |
| candidate-0 | 260.258 | 46.961 | 38.166 | 31.438 | 44.433 | 56.638 |
| candidate-1 | 253.448 | 46.944 | 38.057 | 32.497 | 41.878 | 55.706 |
| baseline-1 | 284.867 | 46.939 | 38.070 | 32.172 | 41.902 | 54.042 |

The mean measured allocation rate on far-mesher workers is 13.50% lower across these two launches per build. This is a whole-worker counter, including their other work and asynchronous workload differences, not bytes normalized by an identical completed-job set. It supports the direct hit-allocation reduction. It is not a confidence interval or portable rate.

There is **no established average-FPS gain**. Moving means are mixed, and both candidate p99 values are higher than their paired references. GC patterns vary. One candidate stationary frame was 148.12 ms; the returning original had 147.15 ms. The first original flight had 120.57 ms; both candidate flights and the returning original had zero moving frames over 100 ms. Every primary frame is retained; optional edge trims are diagnostic only. No cause is assigned from those correlations.

A same-JVM original/candidate/candidate/original diagnostic also completed, comparing moving distributions without restarting the game. Its local-only hook returns a cache miss from the added get-first operation to reach the unchanged original computeIfAbsent path, with a volatile control shared by both paths. It changes no values or GL work, and is not included in the product. Direct post-flight counters recovered original 16/48 → candidate 0/24 → original 16/48 bytes per cached face/tinted call, with matching corpus hashes throughout. The driver is included for review; it is never compiled by the product build.

| Same-JVM path order | FPS | p95 ms | p99 ms | Max ms | Frames >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| causal-original-0 | 32.091 | 41.521 | 54.614 | 75.702 | 0 |
| causal-candidate-1 | 33.750 | 39.359 | 47.911 | 76.749 | 0 |
| causal-candidate-2 | 33.469 | 39.983 | 53.377 | 165.333 | 1 |
| causal-original-3 | 33.502 | 41.041 | 52.750 | 149.379 | 2 |

The first colder route was slower; the later candidate/reference means were nearly equal. One late candidate frame reached 165.28 ms and two returning-original frames exceeded 100 ms (maximum 149.36 ms); none are excluded. These instrumented controls do not establish an FPS or latency improvement. Exact distributions, allocation counters, scene/refinement context and cleanup are in same-jvm.json. Independent review, final-head native/CI identity and acceptance of this as an allocation cleanup remain separate delivery gates. PR #99 is still the ordinary accepted artifact.

All four worlds exited normally (350.72/380.42/389.12/418.08 ms), and the original installation/config/Photon/options were restored between launches. Full source/JAR/driver identities, allocation/GC counters, untrimmed distributions, sampled GPU scopes, convergence and cleanup are in measurements.json; raw private scripts/logs/frames/screenshots remain under results/face-cache-controls. No NVIDIA/AMD native GPU or physical VR hardware was available. This delta changes no GL operation or shader input. No merge or release.
