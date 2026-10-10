# Earth Cubic Platform

Private development of a general-purpose cubic-world platform for Minecraft Java 1.21.1 / NeoForge 21.1.256. The priority is the cubic core and engine integration. Earth geography is an optional test provider and a later plugin.

## Current checkpoint: bounded rolling native player 001

This experimental integrated-player checkpoint adds a server-owned moving 5×5×5 cubic window to the verified full-height player gate. It uses generic XYZ cube providers and durable edits, actual network snapshots, native block models/collision and the isolated official Distant Horizons adapter. Earth geography remains a later optional provider.

The frozen native suite passed **550 tests** with zero failures, errors or skips. Actual native-client validation established:

- More than 90 m forward walking across cube boundaries, return to the original edited cube with revision 1 retained, and horizontal diagonal plus vertical residency changes.
- 37 direct paired native/DH publications, with bounded server collision throughout committed windows.
- Same-world restart preserving the edit and restoring the original Creative location/mode.
- Ordinary mouse rejection at the immutable editable-cube shell without a revision change.
- Idle resource reload motion fencing, plus interruption of a streaming bootstrap after 23 client snapshots, before DH allocation. This is not a mid-GPU-upload cancellation claim.
- Horizontal rolling at native ground Y−11000 and Y+8849 with real native support.
- Six clean closed epochs and six final post-fence client collision receipts. The bootstrap-cancelled epoch never allocated far GL resources.

See [the gate and commands](docs/ROLLING_PLAYER_001.md) and [machine-readable verification](docs/evidence/rolling-player-001.json). Five delayed auto-READY screenshot/report records contain publication-timing mismatches and are explicitly excluded from acceptance; direct paired reports and exact server commit evidence are retained. Main CLOSED reports can precede queued client collision disposal, so final collision claims use paired post-fence receipts. Earlier checkpoint reports remain historical.

## Bounds and remaining work

Current collision covers 125 cubes. Current/candidate/pinned source coordinates have a tested cap of 213; the actual run peaked at 177. Physical native mesh generations are capped at 55; 45 were observed. These are separate ownership/accounting bounds, not total JVM/native/GPU memory claims. The original editable cube retains 27 pinned dependencies and an actual native mesh. Only local X/Y/Z 1..14 inside that original cube can be edited, and it must be within the current ray/collision window. Its one-block shell is immutable; edited geometry cannot yet retire into a distant summary.

The diagnostic far field remains a fixed 512 m radius, with X/Z safety limited to ±512 m and gameplay Y[−11008,8864). The carrier retains vanilla height 384 and 24 sections; provider-backed render padding is Y[−11040,8896). The endpoint fixtures and rolling window do not establish an unbounded survival world, a moving far ring or huge view distance.

The maximum observed synchronous update was **397.030 ms**. Native builds and complete far-mask replacements can hitch. Lighting is fixed diagnostic illumination; vanilla material/fluid/entity/tick/survival integration and general mod compatibility remain incomplete. There is no hostile public-server packet-flood protection claim. The upstream VertexBuffer constructor does not expose rollback handles for its own internal partial construction failure. Mesa llvmpipe is software OpenGL; smooth frame pacing and hardware FPS are not accepted. Health briefly reached 18 after flight descent; post-reload full hearts do not establish health 20 throughout the run.

## Other verified experiments

Terrain-view003 separately passed native movement, moving far-field recentering, near/far coverage and resource lifecycle checks, with 306 headless tests. It still has significant startup/upload stalls and no gameplay or hardware-performance acceptance. [Its verification report](docs/evidence/terrain-view-003.json) records the limits. Its [separate frozen source branch](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-003/README.md) preserves that experiment without mixing it into this server-authority checkpoint. The older terrain-view001 seam failure is historical and is not the current default gate.

[Terrain-view005](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-005/README.md) is the separate off-thread official DH vertex experiment: 344 native tests and three actual GUI lifecycle epochs passed. Render-owner builder calls were zero. That run recorded zero cancellations and zero recenter commits, so its evidence does not replace terrain003's recenter validation or prove a live build/reload race. Startup and overall-update stalls remain; performance is only partially improved. It is not merged into this collision checkpoint.

[Terrain-view006](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-006/README.md) separately validates resumable native model construction: 445 native tests, three clean GUI lifecycle epochs, seven cancelled partial builds, five retained pairs and one safe budget unmask. Its 2 ms slice target is soft; individual callbacks, planning, uploads and draw stalls can exceed it. Reload was idle and host contention prevents a controlled speedup claim. This visual-only terrain scheduling is not merged into the rolling player checkpoint. Only the narrow verified terrain006 native mesh cleanup helpers are included in rolling001.

[Terrain-view007](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-007/README.md) separately preserves resumable near-source planning, 476 tests and actual reload interruption at 192/2304 samples. Its constructor, callback and initialization stalls remain documented. It is not merged into rolling001.

[Terrain-view008](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-008/README.md) separately adds paired owner-CPU/elapsed diagnostics, with 513 full tests, 80 focused cases and three clean GUI epochs. Eleven CPU-greater-than-elapsed anomalies have unknown magnitudes and affected stages, so aggregate paired statistics remain provisional. It is a diagnostic, not an overall performance or hardware-FPS acceptance, and is not merged into rolling001.

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
