# Earth Cubic Platform

Private development of a general-purpose cubic-world platform for Minecraft Java 1.21.1 / NeoForge 21.1.256. The priority is the cubic core and engine integration. Earth geography is an optional test provider and a later plugin.

## Current checkpoint: bounded full-height native player 001

This experimental checkpoint validates actual player mechanics at two extreme native Y coordinates. It is still a bounded diagnostic, not a continuous full-height survival world or general mod-compatibility release. Cubic core integration remains the priority; Earth geography is a later optional provider.

Implemented and checked:

- Generic signed XYZ cube providers, bounded asynchronous residency, immutable network snapshots and durable edit stores.
- Native baked-block rendering and scoped vanilla collision against sparse native blocks, with wall/roof fixtures and unknown-boundary motion fences.
- Actual walking, support and jumping at ground Y−11000 and Y+8849, using real signed block/player coordinates rather than display offsets.
- Ordinary mouse edits: abyss placement revision 1 then removal revision 2; summit placement revision 1. Independent profile stores survive same-world reopening.
- A narrow exact-player/level safety lease prevents stock void damage within the diagnostic envelope, including loading, reload and failed return. Other players and damage rules retain their original path.
- Direct walking Save & Quit restores the original Creative position/mode. A deliberate /kill and Respawn also ends the gate and restores the original location/mode.

The frozen native suite passed **432 tests**. Actual native-client validation additionally confirmed abyss F3+T motion fencing with health 20, four closed epochs and four final post-fence client collision receipts with zero owners. See [the gate and commands](docs/FULL_HEIGHT_PLAYER_001.md) and [machine-readable verification](docs/evidence/full-height-player-001.json). Older checkpoint reports are retained as historical evidence.

## Bounds and remaining work

Each profile uses 125 authoritative cube owners, 27 native mesh targets and 18 fully in-domain ready meshes in the exact 3D far mask. The central editable cube and its dependencies stay pinned. Gameplay/raycast/collision support is bounded to Y[−11008,8864); provider-backed render halos use Y[−11040,8896). The carrier still has vanilla height 384 and 24 sections, without dense full-height allocation. The DH far field remains a fixed 512 m radius.

This is not continuous traversal between the endpoint fixtures or moving cubic interest. Ordinary Level.getBlockState still returns void air at extreme Y, so material/friction/fluid/block-entity/survival/tick compatibility remains incomplete. Lighting is fixed diagnostic illumination. Hardcore entry is rejected; death ends the gate rather than implementing native-world survival. Public-server flood protection, arbitrary edited far summaries and broad third-party mod compatibility remain unverified. The observed maximum synchronous update was 872.8 ms on a contended cloud host. Synchronous native/far rebuilds and software rendering can hitch; no hardware FPS or frame-pacing acceptance is claimed.

## Other verified experiments

Terrain-view003 separately passed native movement, moving far-field recentering, near/far coverage and resource lifecycle checks, with 306 headless tests. It still has significant startup/upload stalls and no gameplay or hardware-performance acceptance. [Its verification report](docs/evidence/terrain-view-003.json) records the limits. Its [separate frozen source branch](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-003/README.md) preserves that experiment without mixing it into this server-authority checkpoint. The older terrain-view001 seam failure is historical and is not the current default gate.

[Terrain-view005](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-005/README.md) is the separate off-thread official DH vertex experiment: 344 native tests and three actual GUI lifecycle epochs passed. Render-owner builder calls were zero. That run recorded zero cancellations and zero recenter commits, so its evidence does not replace terrain003's recenter validation or prove a live build/reload race. Startup and overall-update stalls remain; performance is only partially improved. It is not merged into this collision checkpoint.

[Terrain-view006](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-006/README.md) separately validates resumable native model construction: 445 native tests, three clean GUI lifecycle epochs, seven cancelled partial builds, five retained pairs and one safe budget unmask. Its 2 ms slice target is soft; individual callbacks, planning, uploads and draw stalls can exceed it. Reload was idle and host contention prevents a controlled speedup claim. This visual-only terrain work is not merged into the endpoint player checkpoint.

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
export JAVA_TOOL_OPTIONS="-Dearthplatform.experimental.authoritativeCoreGate=true -Dearthplatform.experimental.coreCollisionGate=true -Dearthplatform.experimental.fullHeightPlayerGate=true -Dearthplatform.experimental.sparseTerrainView=false"
bash scripts/gradle.sh runClient
```

Create a disposable Creative test world with commands enabled. Use /earth_core enter abyss or /earth_core enter summit, wait for READY, then /earth_core walk. The default /earth_core enter retains the base fixture. /earth_core fly restores spectator mode; right/left mouse inputs edit the bounded area. /earth_core status records diagnostics and /earth_core leave closes the gate. Profile stores are separate. Do not run the Earth terrain viewer simultaneously or use an existing valuable world. Follow the gate document for exact bounds, reload and recovery procedures.

## Source and data scope

This repository contains project source, tests, build bootstrap, source-attributed redistributable fixture crops and license notices. It excludes proprietary Minecraft binaries/assets/decompiled code, the DH runtime JAR, JDKs, Gradle caches, runtime saves, credentials, unrelated private photographs and the research-only Mariana dataset. See [third-party notices](THIRD_PARTY_NOTICES.md) and each data fixture's source-lock/provenance files. This independent project is not endorsed by Mojang or Microsoft.
