# Earth Cubic Platform

**Roadmap:** see [docs/ROADMAP.md](docs/ROADMAP.md) for the plan to a playable, go-anywhere Earth (vanilla-chunk host first, cubic engine for true 1:1 height) and the status of each milestone.

Private development of a general-purpose cubic-world platform for Minecraft Java 1.21.1 / NeoForge 21.1.256. The priority is the cubic core and engine integration. Earth geography is an optional test provider and a later plugin.

## Current checkpoint: bounded rolling native player 002

This experimental integrated-player checkpoint makes native candidate rebuilds resumable on the render owner. The server-owned moving 5×5×5 cube window, generic XYZ providers, durable edits, network protocol, collision handshake and official Distant Horizons renderer remain the verified rolling001 implementation. Earth geography remains a later optional provider.

The frozen native suite passed **626 tests** with zero failures, errors or skips. The scoped actual native-client run verified:

- Ordinary mouse editing, forward/reverse movement across cube seams, return to the preserved block and durable host re-entry at revision 1.
- 17 coherent native/DH pair publications and summit rolling at native ground Y+8849.
- Real F3+T cancellation of an active partial native build after **2791 of 4096 successful block callbacks**, with 8 targets still queued and publication not yet prepared.
- Three clean actor/native/far/server-collision closures and three final post-fence client collision receipts.

See [the gate and commands](docs/ROLLING_PLAYER_002.md) and [machine-readable verification](docs/evidence/rolling-player-002.json). READY screenshot capture no longer overwrites earlier readiness JSON; all 17 readiness records are coherent. Main CLOSED reports for epochs 2 and 3 precede queued collision fencing and still show 125 collision owners; their paired post-fence receipts show zero. Same-world restart, abyss and death recovery were not repeated in this short run and remain separately proven by earlier immutable gates, not new rolling002 acceptance claims.

## Scheduling, bounds and remaining work

One owner-thread pump performs exactly one phase: begin a retained native build, execute up to 4096 callbacks under a soft 2 ms budget, or finish/upload a previously completed build. Finish/upload cannot share the pump with its final callback. At most one active build and one retained completion slot exist. A single callback, begin, finish/upload, publication or cleanup may exceed the soft budget; arbitrary model callbacks do not move off-thread.

Current collision covers 125 cubes. Current/candidate/pinned source coordinates retain a tested cap of 213, with 150 observed in this run. Physical native mesh generations are capped at 55, with 45 observed. The original editable cube retains 27 pinned dependencies and an actual native mesh; only local X/Y/Z 1..14 can be edited while in ray/collision range. Its one-block shell is immutable, and edited geometry cannot retire into a distant summary. A prepared candidate may conservatively wait for player-body containment; automatic timeout/replanning of that hold is not implemented.

The far field remains a fixed 512 m radius; safety is bounded to X/Z ±512 m and gameplay Y[−11008,8864), with provider-backed render padding Y[−11040,8896). The carrier retains vanilla height 384 and 24 sections. This is not an unbounded survival world, moving far ring or huge-view-distance implementation.

The first epoch reached a 9.995 ms native slice, 273.450 ms far initialization and 330.857 ms update. The warmed summit reached a 2.330 ms slice, but synchronous DH replacement still reached **44.843 ms** and update **49.936 ms**. These are owner-thread elapsed maxima, not CPU time, GPU duration, controlled comparative benchmarks or complete frame timings; inclusive nested spans must not be summed. Native finish/upload and full DH replacement remain synchronous. Smooth pacing and hardware FPS are not accepted.

Lighting is fixed diagnostic illumination. Vanilla material/fluid/entity/tick/survival integration, hostile public-server packet handling and general mod compatibility remain incomplete. The upstream VertexBuffer constructor exposes no rollback handles for its own internal partial construction failure. Mesa llvmpipe is software OpenGL; ownership accounting is not total JVM/native/GPU memory usage.

## Other verified experiments

Terrain-view003 separately passed native movement, moving far-field recentering, near/far coverage and resource lifecycle checks, with 306 headless tests. It still has significant startup/upload stalls and no gameplay or hardware-performance acceptance. [Its verification report](docs/evidence/terrain-view-003.json) records the limits. Its [separate frozen source branch](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-003/README.md) preserves that experiment without mixing it into this server-authority checkpoint. The older terrain-view001 seam failure is historical and is not the current default gate.

[Terrain-view005](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-005/README.md) is the separate off-thread official DH vertex experiment: 344 native tests and three actual GUI lifecycle epochs passed. Render-owner builder calls were zero. That run recorded zero cancellations and zero recenter commits, so its evidence does not replace terrain003's recenter validation or prove a live build/reload race. Startup and overall-update stalls remain; performance is only partially improved. It is not merged into this collision checkpoint.

[Terrain-view006](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-006/README.md) separately validates resumable native model construction: 445 native tests, three clean GUI lifecycle epochs, seven cancelled partial builds, five retained pairs and one safe budget unmask. Its 2 ms slice target is soft; individual callbacks, planning, uploads and draw stalls can exceed it. Reload was idle and host contention prevents a controlled speedup claim. This visual-only terrain scheduling is not merged into the rolling player checkpoint. Only the narrow verified terrain006 native mesh cleanup helpers are inherited from rolling001.

[Terrain-view007](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-007/README.md) separately preserves resumable near-source planning, 476 tests and actual reload interruption at 192/2304 samples. Its constructor, callback and initialization stalls remain documented. It is not merged into rolling002.

[Terrain-view008](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-008/README.md) separately adds paired owner-CPU/elapsed diagnostics, with 513 full tests, 80 focused cases and three clean GUI epochs. Eleven CPU-greater-than-elapsed anomalies have unknown magnitudes and affected stages, so aggregate paired statistics remain provisional. It is a diagnostic, not an overall performance or hardware-FPS acceptance, and is not merged into rolling002.

## Modules

- core: generic signed XYZ cubes, bounded loading and durable edit storage
- streaming: versioned world/epoch/lease/revision snapshot admission
- minecraft: native engine adapter, server-authoritative runtime fixture and tests
- geography: optional source-attributed elevation/classified-cover fixtures and import tools

The general provider accepts full 3D cubes; heightfield Earth fixtures are not the generic world-generation contract. One projected metre per block does not imply measured one-metre global terrain accuracy or a uniform global ground-metre scale.

## Build and test

Install Java 21 and set JAVA_HOME. Gradle resolves official Minecraft and NeoForge dependencies locally; these are not included here.

From the repository root:

```sh
./scripts/test-independent.sh
cd minecraft
bash scripts/gradle.sh test build
```

For the full official-builder native tests without skips, also set EARTH_DH_PROBE_JAR as described below. The independent geography import checks additionally need Python, NumPy and Pillow; see requirements-dev.txt. No GitHub Actions workflow is enabled in this checkpoint. Reported tests were run in the isolated development workspace.

## Run the bounded core diagnostic

Separately obtain the official Distant Horizons 3.3.3 build for Minecraft 1.21.1. From minecraft, with JAVA_HOME set:

```sh
export EARTH_DH_PROBE_JAR=/absolute/path/to/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar
export JAVA_TOOL_OPTIONS="-Dearthplatform.experimental.authoritativeCoreGate=true -Dearthplatform.experimental.coreCollisionGate=true -Dearthplatform.experimental.fullHeightPlayerGate=true -Dearthplatform.experimental.rollingCubeGate=true -Dearthplatform.experimental.sparseTerrainView=false"
bash scripts/gradle.sh runClient
```

Create a disposable Creative test world with commands enabled. Use /earth_roam enter for the base fixture, /earth_roam enter abyss or /earth_roam enter summit for the native-height endpoints. Wait for READY, then /earth_roam walk. /earth_roam fly restores spectator mode; right/left mouse inputs edit the original bounded cube. /earth_roam status records diagnostics and /earth_roam leave closes the gate. Profile stores are separate. Do not run the Earth terrain viewer simultaneously or use an existing valuable world. Follow the gate document for exact edit bounds, rolling, reload and recovery procedures.

## Source and data scope

This repository contains project source, tests, build bootstrap, source-attributed redistributable fixture crops and license notices. It excludes proprietary Minecraft binaries/assets/decompiled code, the DH runtime JAR, JDKs, Gradle caches, runtime saves, credentials, unrelated private photographs and the research-only Mariana dataset. See [third-party notices](THIRD_PARTY_NOTICES.md) and each data fixture's source-lock/provenance files. This independent project is not endorsed by Mojang or Microsoft.
