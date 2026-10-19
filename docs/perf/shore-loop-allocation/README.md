# Shore-query temporary allocation

This change replaces one two-element temporary array with the same two scalar values in `EarthGenerator.shore`. It keeps all eight samples in their original order: near then far, for each of the four original directions. The radius calculations, point arithmetic, casts, short-circuit checks, water queries and result reduction are unchanged. There is no new cache, retained workspace, quality setting, distance change or player switch. The lightmap-ring and tree-scratch prototypes are separate and are not included in this delta.

The allocation profile recorded double arrays at this exact shore-query site. The old loop constructs four two-double arrays per invocation (128 bytes on the tested JVM when materialized). The candidate removes those constructions; it does not substitute approximate terrain or reuse mutable survey results.

## Exact-output checks

Code source `5b351cd51297f14e9a132913d9e2f6a2e455c32a` passed `verifyShimArtifact` with the checked stock Photon input: 163 tests, 159 passed, four optional skips, no failures/errors. The reference is the reviewed build-59 baseline `6ff363560ff5173740ec641c465c72caf59a1633`.

Four fresh native JVM launches used reference/candidate/candidate/reference order. Every launch generated 144 real far columns and 48 complete near cubes. All four far hash sets and all four near hash sets matched exactly, including the returning reference. Far hashes include bottom, all drops and complete section serialization (block states, biomes and palette ordering). Near hashes include complete section serialization. Per launch this covers 1,101,824 counted far voxel states, 196,608 near voxel states and 32,281 fine-level tree states. This is a warm-geodata Yosemite corpus, not exhaustive geographic coverage. The source-level order/value equivalence also applies to the existing algorithm's other inputs.

## Native measurements

Apple M4 Pro, native OpenGL 4.1 Metal 91.7, working built-in Retina display, 1708×960, render/simulation distances 12/12, gamma .5, shaders off, unchanged detail/reach. Iris 1.8.14-beta.1 and Sodium 0.8.13 were installed. The normal automatic logger and one-in-120 asynchronous GPU observer stayed fixed. A local-only driver and detailed GC log were identical across controls. No build, separate benchmark or JFR export overlapped the windows. Personal worlds were not used.

Each launch waited 25 seconds plus refinement/queue settling, ran both generation oracles, waited five seconds plus settling, then recorded 45 seconds stationary and a 1,200-block flight over 600 client ticks (about 30.7 seconds). The world was returned to the same start before saving. Day/weather/mob settings and random ticks were fixed only in the disposable test world. Geodata was warm and no downloads occurred. Asynchronous arrivals during the flight were not identical per-frame workloads. Other user applications remained open.

| Path, in order | Stationary FPS | Stationary p99 ms | Moving FPS | Moving p95 ms | Moving p99 ms | Moving frames >40 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Reference | 46.99 | 38.87 | 32.02 | 42.71 | 54.71 | 8.78% |
| Candidate | 47.03 | 37.88 | 31.77 | 42.64 | 54.84 | 7.71% |
| Candidate | 46.69 | 39.12 | 32.36 | 42.36 | 50.47 | 8.38% |
| Reference restored | 46.80 | 39.16 | 32.07 | 42.95 | 57.08 | 8.66% |

There is **no established average-FPS gain**. The first candidate moving mean is 0.8% lower than its first reference; the repeat is higher. p99 is slightly worse in the first pair and better in the second. These two launches per build do not establish a portable latency benefit or confidence interval. Full distributions and an explicitly diagnostic two-second edge trim are retained. Nothing was removed from the primary results.

Both the second candidate and returning reference had two stationary frames around 147–149 ms. Those frames did not overlap recorded GC pauses; the longest logged safepoints in those windows were under 0.3 ms. Their cause remains unclassified. All moving windows had zero frames over 100 ms. No attribution of these outliers to GC or the loop change is made.

| Path | Far-generation worker allocation MiB/s | Near-generation worker allocation MiB/s | Logged moving GC pause count | Logged moving GC pause total ms |
| --- | ---: | ---: | ---: | ---: |
| Reference | 501.91 | 148.74 | 66 | 293.07 |
| Candidate | 460.91 | 144.79 | 52 | 257.35 |
| Candidate | 461.23 | 144.46 | 55 | 292.14 |
| Reference restored | 503.15 | 148.55 | 61 | 279.77 |

The tested route shows about **8% less allocation on far-generation workers** and about **3% less on near-generation workers**. These are complete worker-group counters, including their other work, not an estimate of bytes from one method. Fixed worker threads remained alive through every counter interval. Logged GC pause counts were lower in the candidates, while total pause time was mixed. This is an allocation cleanup with an end-to-end measurement, not a claimed FPS speedup.

Sampled far-opaque GPU means stayed around 19.5–19.6 ms stationary and 18.7–19.0 ms moving. There is no GPU work reduction claim, and these scopes do not measure an entire GPU frame or display presentation. Setup convergence after the initial 25-second wait was 30.99 / 29.50 / 32.39 / 30.24 seconds. Post-route settling was 2.75 / 2.90 / 3.76 / 2.10 seconds. No loading/convergence gain is established; both candidate post-route checks took longer in these samples.

The exact identities, full/tail distributions, allocation counters, logged GC overlap diagnostics, GPU scopes and convergence are in [measurements.json](measurements.json). Complete private raw frames, scripts, driver source, JVM logs, screenshots and generated hashes are retained under the task's `results/terrain-loop-controls/`. The disposable installation was restored after the controls. CI, final-tip native smoke and independent review are separate delivery gates; keep the PR draft until they are confirmed. NVIDIA/AMD and real VR hardware were not available. This delta changes no graphics API or shader code.
