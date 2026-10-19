# Build 58 performance continuation

Baseline is released alpha.58, main `4cd96bf`. The performance source change retained here is mesher result ownership cleanup from `f5462ef`. The build-59 review bundle also includes the independently validated [automatic world-thumbnail fix](../world-thumbnails/README.md). The exact cloud-cache experiment in that native candidate failed its end-to-end acceptance gate and was reverted in `093a9d2`. No FPS gain is claimed. Command caching and shadow hints remain disabled. No player switches, quality or distance changes are introduced.

## Evidence and changes

The fresh Mac baseline used the published JAR, the existing 1708 × 960 framebuffer, render/simulation distances 12/12, the same far detail/reach, Sodium 0.8.13, Iris 1.8.14-beta.1 and Photon v1.3b. Only disposable worlds were used. Player/profile identity matched before disconnect testing. Native OpenGL 4.1 Metal 91.7 display operation was verified. Separate unprofiled windows bracketed JFR windows; builds and profiler exports ran after the game exited.

- Shader-free unprofiled stationary windows: 46.16 / 46.88 FPS, p95 37.92 / 37.72 ms, p99 39.09 / 38.84 ms. The first window had one 555.74 ms frame; it is retained, not excluded.
- Photon stationary: 29.58 / 29.57 FPS, p95 34.97 / 35.05 ms, p99 36.43 / 36.76 ms.
- Photon movement under JFR: 22.48 FPS, p95 62.81 ms, p99 71.86 ms. This is a profiled workload, not a measured optimization gain. It recorded 45 GC pause events totaling 298.40 ms, maximum 19.18 ms.
- The settled scene held 58,909 far columns, 17,005 visible, 27,535 draw ranges, with no generation, meshing or uploads pending. Cloud-related stacks appeared in 376 of 812 render-thread Java execution samples without shaders, and zero with Photon. Sample share is not a whole-frame time percentage.
- Cumulative per-thread allocation counter deltas in the movement profile show about 287 MiB/s in near meshing, 280 MiB/s in far meshing, 507 MiB/s in far generation and 140 MiB/s in near generation, plus render/server work. Raw ObjectAllocationSample weights carried older per-thread history and are not used as phase allocation totals.

### Rejected exact cloud terrain-grid experiment (`3f7ecd8`, reverted `093a9d2`)

Cache only integer corner maxima for one FarRenderer, keyed by the existing column revision and exact bits of grid inputs. Receipts/replacements/edits and eviction advance that revision; movement or grid changes rebuild the data. Nonfinite inputs always rebuild. The original integer addition, floating-point cell mapping, inclusive boundaries and corner maxima are preserved. Deck/opacity, wind, colour, vertices, textures and projection still update on the original path. Photon keeps its own clouds and does not use this cache. Retained storage is about 8.3 KiB per renderer. Arrays are reused on misses as well as hits.

The headless oracle compared the original clearance algorithm bit-for-bit across boundaries, overlapping columns, negative/extreme coordinates, movement, grid/deck changes, arrival/edit/removal revisions, revision wrap and world isolation. Three native oracles checked 96 cases / 104,544 float-opacity channels each: during arrivals with 2,220 held columns, then twice with 58,909 held columns. All had zero bit mismatches.

The matched 45-second same-process controls rejected reuse:

| In time order | FPS | p95 ms | p99 ms | Frames >40 ms |
| --- | ---: | ---: | ---: | ---: |
| Force rebuild | 46.96 | 36.74 | 38.13 | 0.000% |
| Reuse | 46.83 | 39.67 | 40.25 | 1.706% |
| Reuse | 46.68 | 39.67 | 40.02 | 1.046% |
| Force rebuild | 46.92 | 37.13 | 38.38 | 0.047% |

Both reverse controls recovered the earlier distribution. The second reuse window contained one 283.54 ms frame; full and two-second-trimmed distributions are retained in the JSON. Reuse saved Java work but produced no average-FPS improvement and consistently worsened tails, so it is absent from the proposed code. Forcing misses was a local test-driver action, not a player option. The control did not alter the draw-command buffer path, making it independent evidence that removing CPU work can change this Mac's frame cadence. It does not establish the underlying driver mechanism or behavior on other GPUs.

### Mesher result ownership (`f5462ef`)

A renderer previously drained its completed-result queue once on close; workers could publish results afterward, including explicitly owned native buffers. A controlled native interleaving held the three real far-mesher workers, queued 32 real water remeshes, closed the renderer with 160 jobs pending, then released the workers. The baseline retained 160 late results, 65 native water parts and 328,704 bytes of native capacity. The probe freed them after measurement. An ordinary unload retained none, so this is a proven race, not an observed natural leak rate.

The ownership queue now closes publication atomically, disposes late completions exactly once, and lets tasks skip work after closure. The same ownership pattern in near meshing uses the same fix. Polling/upload ownership remains unchanged while a world is open. Concurrent headless controls verify both close/publication orders, consumed-result ownership and 2,000 racing offers. The identical native far-mesher interleaving now reports zero late results and zero native capacity, with normal save-and-exit completed.

## Validation and remaining gates

The final ownership-only source `9ab64cd90f08055170be53471b89ce1971965d1f` passed verifyShimArtifact with the actual Photon ZIP: 159 tests total, 155 passed, four optional benchmark skips, zero failures/errors. Its native Photon smoke used JAR SHA-256 `88bcebb4f23f7a7618d1377f66f0bf70f63c8e884774b246bae9570fcb125655`, repeated the 32-water-job / 160-pending-job barrier, and found zero late results and zero native capacity twice. Normal save-and-exit completed in 291.55 ms; the screenshot was inspected and five automatic log records matched the exact source. Independent Astra/high review cleared that frozen head. Photon and profiled-streaming controls of the earlier experimental artifact completed without an established gain. The native opacity and cloud ABBA controls completed; cloud reuse was rejected. The combined build-59 artifact is validated separately after adding the thumbnail fix and matching release metadata. Performance acceptance requires useful end-to-end gains without worse p95/p99 or stutters; a component sample reduction alone is insufficient. No additional third-party performance mods were installed. No NVIDIA/AMD native GPU or real VR hardware was tested; portable Java behavior and unchanged shader inputs do not substitute for those hardware checks. Nothing was merged or released by the agent.

Sanitized values are in [measurements.json](measurements.json). Raw JFR, worlds, account identifiers, screenshots and logs remain local. Further command-cache work must distinguish native argument-buffer reuse from CPU/driver cadence; no mechanism is established yet. Server far generation already suppresses byte-identical edit payloads and skips out-of-range tasks, so those existing checks are not claimed as new work.

The next measured lead is shader-free lightmap upload cadence: all sampled glTexSubImage2D native stacks in the settled controls came from LightTexture.updateLightTexture through DynamicTexture.upload. Cloud terrain-scan execution samples fell to zero in the rejected candidate, while upload-related native stacks remained. This establishes a call-site lead, not GPU utilization, synchronization causality or a speed gain. Bounded per-upload timing and causal controls should precede any source change.
