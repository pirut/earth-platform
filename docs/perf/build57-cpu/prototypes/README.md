# Exact-output prototypes and cross-vendor gates

Both experiments are **off by default**, alongside the now opt-in command cache. No graphics quality, distance, simulation radius, fade or texture setting is reduced. These are bounded prototypes, not accepted production optimizations. The main [report](../README.md) retains the earlier build-57 measurements; those FPS numbers do not describe these prototypes.

## Shadow blocker hints

`-Dearthplatform.far.shadowHints=true` enables two ping-pong R32F hint textures. Each stores a successful original `earth_sun` sample index plus one, with zero meaning no hint. A hint is never a cached shadow answer. The candidate uses the current reconstructed position, camera projection, sun, noise and depth sampler. It repeats the original float multiplication sequence, current positive-W and inclusive [0,1] UV reachability checks, and the unchanged original thickness predicate. Nonfinite/invalid values, clipping or a failed blocker test fall back to the original loop. AO, RG8 shadow output and subsequent history/filtering remain unchanged.

Why skipping prior depth fetches preserves the binary result: if the proposed current blocker passes and no preceding original clipping check terminates the ray, the original loop either finds an earlier blocker (also returning 1.0) or reaches this blocker and returns 1.0. Otherwise the original loop runs. The stored index is a successful original-loop sample; it need not remain the *first* blocker after edits. The CPU oracle exercises every hint/termination index and varied blocker patterns. This proof does not replace driver-specific floating-point/output tests.

Sampling and output attachments are distinct. The write texture is detached before roles swap. Resize invalidates the read history. Unsupported shader/FBO setup uses the original pass. Extra hint VRAM is capped at **32 MiB** across both textures; 1708x960 needs 12.51 MiB. Larger unsupported sizes, including 3840x2160 under this cap, retain the original full-quality pass. No vendor, warp size, unified-memory or discrete-memory assumption selects the feature.

### Actual M4 Pro shader evidence

The standalone `ShadowGpuOracle.java` compiles the exact production GLSL, renders disposable synthetic depth fields, and compares original/hinted shadow **and AO raw float bits**, before normalized RG8 storage. It passed **96 cases / 2,672,688 channels / zero mismatches**, with **308,456 validated fast-hint hits**. Cases vary depth/camera-like motion, edit-like ridges, sun direction/night, sky/fade-like gaps, jittered projection, stale/malformed hints and resize. A diagnostic extra output confirms the fast branch was exercised. This is a bounded GPU fixture, not exhaustive world, mod or vendor coverage.

At 1708x960 with production RG8 shadow and R32F hint targets, two same-context ABBA blocks used 24 asynchronous elapsed-query samples per window. No result was read before availability; no glFinish or readback occurred inside measured scopes. The mean GPU pass duration was **0.6500 ms original versus 0.7751 ms hinted (+19.25%, worse)**; 96 samples per path, maximum result age eight submitted frames, zero dropped samples. Window means were 0.629–0.669 ms original and 0.758–0.783 ms hinted. These are GPU component durations, not FPS or independent multi-launch confidence intervals. This result **fails the benefit gate**, so the experiment remains off. Live world movement/edit/sun/fade/resize benefit is not established.

The Mac exposes OpenGL 4.1 Metal 91.7 / ARB_timer_query, but reports **timestamp counter bits=0, elapsed counter bits=32**. The instrumentation therefore prefers timestamp pairs when supported and otherwise uses non-nested TIME_ELAPSED, skipping occupied targets. Availability, age, wrap ambiguity, ownership loss and bounded storage are checked. No blocking query result retrieval is used. Native timestamp mode still needs verification on another implementation.

## Exact far surface packets

`-Dearthplatform.far.surfacePackets=true` prototypes generation of exact vertical material runs for levels 3–8 without vertical strata or rasterized giants. Fine far levels, giant rasterization and strata retain the dense generator. Level-3 canopy and wider ground foliage, discrete water/ice, bedrock cover, fractional drops and biome palette defaults are retained. Near generation and simulation are unchanged.

The immutable base holds flat run words plus per-column offsets, exact registry state IDs, original biome quart samples/default and drops. Sparse block exceptions produce a new revisioned immutable overlay; stale/non-increasing revisions fail. An overlay above 16,384 entries requires dense fallback. Bounds limit rows, runs and experimental encoded bytes. The compact experimental codec uses explicit bounded integers and preserves state properties and unsigned drops. It is **not an existing network or save format**; no saved IDs, network IDs or personal world data changed.

`SurfaceGenerator.surfacePacket()` is the direct bounded API and returns null for unsupported cases. The opt-in `farColumn()` integration deliberately **materializes the existing sections before the original edit/network/meshing boundary**. Saved-cube downsampling, water/air edit rules, build summaries, halo/normal calculations, packed faces and their ordering still use that boundary. The packet field is the immutable generated base; the sections remain authoritative after legacy edits. The sparse-overlay API is tested, but is not a replacement for that existing edit fold.

**Direct packet transport, direct run meshing, production edit-overlay plumbing, and end-to-end streaming/refinement/memory gains remain unfinished.** This adapter can cost more than dense generation. Do not treat a faster direct generator or a smaller experimental encoding as an enabled FPS, VRAM, upload or network improvement.

### CPU exactness and component scope

The dense generator remains an independent enumeration oracle. Tests passed:

- 90 bounded columns / **860,160 voxels**, exact states, biomes, drops and every legacy encoded section byte, before and after experimental codec roundtrip.
- Original fallback comparisons for fine levels, giants and strata; canopy, snow, water, ice, negative coordinates and material boundaries in bounded fixtures.
- The **actual packed mesher**: 12 masked/unmasked jobs, **14,555 packed quads and 12 water meshes**, exact ordering/counts/normals/halo and declared vertex attributes. Synthetic stable looks avoid requiring a model manager. Uninitialized BLOCK padding byte 31 is excluded; this is not a live Iris-format proof.
- Sparse cave-air, overhang/log and water exceptions, revision rejection/revert, immutable input ownership, unsigned drops and resource/decoder bounds. Additional varied registry-state/property and compact-codec checks are tracked by CI/local final verification.

The earlier TreeSet/list construction allocated excessively and was replaced by reusable primitive scratch and flat run storage. In one single-thread synthetic in-memory run, direct generation at level 5 took 47–49 us versus 101–113 us dense, and level 8 took 44–45 us versus 98–99 us dense. At steady level 3 it took 52–53 us versus 216–223 us dense. JIT warm-up drift is visible; these are component fixtures, not full Earth/geodata results. Allocation was about 20,384 bytes/direct column versus 24,269 dense at level 3, but 16,520–17,032 dense at coarser levels: memory does **not** universally improve. Materialization alone cost roughly 91–231 us in that run. First-touch measurements are ordered observations, not independent cold JVMs; no cold-disk/download gain was measured.

The first fixed-width experimental encoding was larger at coarser levels (about 6.6 KiB versus 4.1 KiB legacy); compact codec measurements are kept separate. Final component aggregates and live checks are recorded in the companion results when complete.

## Cross-vendor validation matrix

| Target | Java/CPU exactness | GLSL compiler/output | Native GPU timings | Live quality/performance |
| --- | --- | --- | --- | --- |
| Apple M4 Pro, macOS / GL 4.1 | Local oracle suite passed; final compact-codec verification tracked separately | Actual driver: 96 raw-output cases passed | Non-nested elapsed mode measured; timestamp bits zero | Serialized disposable client checks in progress; no accepted benefit |
| NVIDIA discrete GPU, Windows/Linux | Portable Java implementation; Linux x86 CI covers CPU logic, not NVIDIA hardware | NVIDIA driver compilation/output **not verified** | Timestamp/elapsed selection and occupied-target behavior need hardware test | **Not tested; no access used** |
| AMD discrete GPU, Windows/Linux | Same CPU/CI scope | AMD driver compilation/output **not verified** | Same capability and ownership gates need hardware test | **Not tested; no access used** |
| Generic GLSL 330 reference compiler | Not a hardware test | glslang validation tracked separately | Not applicable | Not applicable |

Dedicated-GPU considerations: hints add texture reads, an MRT write, FBO work and divergent ray-validation branches; reduced depth fetches do not guarantee a gain. The VRAM cap avoids unbounded extra allocation. No coherent CPU/GPU memory, persistent mapping, subgroup width, vendor extension or Apple-only fast path is assumed. The current packet adapter emits the same packed GPU faces and uploads; it has **no demonstrated discrete-VRAM/upload saving**. A direct consumer must preserve ordering and avoid turning compression into many small PCIe uploads. Hardware acceptance requires each vendor's driver, representative dedicated VRAM sizes, stationary/moving/edit/sun/fade/resize cases, p95/p99 and stutters, GPU result ages/drops and observer-off/on controls. Do not extrapolate the Mac result in either direction. The offline, unauthorized Windows machine was not used.

## Reproduction

Use JDK 21 and the separately installed inputs from [BUILDING.md](../../../BUILDING.md). Run the full suite, including the actual Photon ZIP test where available. `EARTH_SURFACE_BENCH=1` opts into `FarSurfacePacketBench`; serialize it outside client/performance windows. Logs beginning `SURFACE_COMPONENT`, `SURFACE_PACKET_ORACLE` and `SURFACE_MESH_ORACLE` describe scope and counts.

For the standalone GPU oracle, compile this directory's `ShadowGpuOracle.java` with the repository's `FarLodShaders.java`, `GlShaders.java` and `GpuQueryRing.java`, using the installed LWJGL 3.3.3 and JOML jars plus platform native jars on the classpath. Run `io.earthplatform.minecraft.cubic.client.ShadowGpuOracle`; on macOS add `-XstartOnFirstThread`. Pass `bench` for the asynchronous GPU component test and optionally `-Dearthplatform.oracle.csv=/path/results.csv`. It requires a working primary monitor/context, changes no worlds or shader packs, and uses synchronous readback only in the **correctness** phase/warm-up. Never run it alongside Minecraft, builds or CPU benchmarks.

The [Khronos glslang project](https://github.com/KhronosGroup/glslang) supplies a separate portable GLSL reference validator; a successful reference compile does not verify an NVIDIA or AMD driver. The standalone oracle and all CPU tests are original code in this repository. External compiler binaries, game assets, shader packs, raw worlds and private logs are not committed.
