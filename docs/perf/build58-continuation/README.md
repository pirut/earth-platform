# Build 58 performance continuation

Baseline is released alpha.58, main `4cd96bf`; candidate gameplay source is `f5462ef`, based on that main. This is work in progress: no candidate FPS improvement or completed acceptance gate is claimed yet. Command caching and shadow hints remain disabled. No player switches, quality or distance changes are introduced.

## Evidence and changes

The fresh Mac baseline used the published JAR, the existing 1708 × 960 framebuffer, render/simulation distances 12/12, the same far detail/reach, Sodium 0.8.13, Iris 1.8.14-beta.1 and Photon v1.3b. Only disposable worlds were used. Player/profile identity matched before disconnect testing. Native OpenGL 4.1 Metal 91.7 display operation was verified. Separate unprofiled windows bracketed JFR windows; builds and profiler exports ran after the game exited.

- Shader-free unprofiled stationary windows: 46.16 / 46.88 FPS, p95 37.92 / 37.72 ms, p99 39.09 / 38.84 ms. The first window had one 555.74 ms frame; it is retained, not excluded.
- Photon stationary: 29.58 / 29.57 FPS, p95 34.97 / 35.05 ms, p99 36.43 / 36.76 ms.
- Photon movement under JFR: 22.48 FPS, p95 62.81 ms, p99 71.86 ms. This is a profiled workload, not a measured optimization gain. It recorded 45 GC pause events totaling 298.40 ms, maximum 19.18 ms.
- The settled scene held 58,909 far columns, 17,005 visible, 27,535 draw ranges, with no generation, meshing or uploads pending. Cloud-related stacks appeared in 376 of 812 render-thread Java execution samples without shaders, and zero with Photon. Sample share is not a whole-frame time percentage.
- Cumulative per-thread allocation counter deltas in the movement profile show about 287 MiB/s in near meshing, 280 MiB/s in far meshing, 507 MiB/s in far generation and 140 MiB/s in near generation, plus render/server work. Raw ObjectAllocationSample weights carried older per-thread history and are not used as phase allocation totals.

### Exact cloud terrain-grid reuse (`3f7ecd8`)

Cache only integer corner maxima for one FarRenderer, keyed by the existing column revision and exact bits of grid inputs. Receipts/replacements/edits and eviction advance that revision; movement or grid changes rebuild the data. Nonfinite inputs always rebuild. The original integer addition, floating-point cell mapping, inclusive boundaries and corner maxima are preserved. Deck/opacity, wind, colour, vertices, textures and projection still update on the original path. Photon keeps its own clouds and does not use this cache. Retained storage is about 8.3 KiB per renderer. Arrays are reused on misses as well as hits.

The headless oracle compares the original clearance algorithm bit-for-bit across boundaries, overlapping columns, negative/extreme coordinates, movement, grid/deck changes, arrival/edit/removal revisions, revision wrap and world isolation. Native opacity checks and whole-frame cache-miss/cache-hit ABBA controls are in progress. Forcing misses is a local test-driver action, not a player option.

### Mesher result ownership (`f5462ef`)

A renderer previously drained its completed-result queue once on close; workers could publish results afterward, including explicitly owned native buffers. A controlled native interleaving held the three real far-mesher workers, queued 32 real water remeshes, closed the renderer with 160 jobs pending, then released the workers. The baseline retained 160 late results, 65 native water parts and 328,704 bytes of native capacity. The probe freed them after measurement. An ordinary unload retained none, so this is a proven race, not an observed natural leak rate.

The ownership queue now closes publication atomically, disposes late completions exactly once, and lets tasks skip work after closure. The same ownership pattern in near meshing uses the same fix. Polling/upload ownership remains unchanged while a world is open. Concurrent headless controls verify both close/publication orders, consumed-result ownership and 2,000 racing offers. The identical native far-mesher interleaving now reports zero late results and zero native capacity, with normal save-and-exit completed.

## Validation and remaining gates

Local verifyShimArtifact and actual-Photon validation passed: 163 tests total, 159 passed, four optional benchmark skips, zero failures/errors. Candidate native frame-tail, opacity equivalence, Photon and streaming controls are still running. Performance acceptance requires useful end-to-end gains without worse p95/p99 or stutters; a component sample reduction alone is insufficient. No additional third-party performance mods were installed. No NVIDIA/AMD native GPU or real VR hardware was tested; portable Java behavior and unchanged shader inputs do not substitute for those hardware checks. Nothing was merged or released by the agent.

Sanitized values are in [measurements.json](measurements.json). Raw JFR, worlds, account identifiers, screenshots and logs remain local. Further command-cache work must distinguish native argument-buffer reuse from CPU/driver cadence; no mechanism is established yet. Server far generation already suppresses byte-identical edit payloads and skips out-of-range tasks, so those existing checks are not claimed as new work.
