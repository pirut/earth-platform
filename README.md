# Earth Cubic Platform

Private development of a general-purpose cubic-world platform for Minecraft Java 1.21.1 / NeoForge 21.1.256. The priority is the cubic core and engine integration. Earth geography is an optional test provider and a later plugin.

## Current checkpoint: authoritative core runtime 002

This is an experimental development checkpoint, not a playable full-height world or release-ready mod. The current source is frozen from the tested core-runtime002 checkpoint.

Implemented and checked:

- Signed 3D cube coordinates, bounded asynchronous residency, immutable snapshots and durable player-edit overlays.
- A server-owned cube actor connected to the existing core, native block palettes and actual NeoForge framed snapshot packets.
- Bounded native baked-block meshes plus an isolated adapter using actual Distant Horizons 3.3.3 terrain builder/shaders.
- Server-authoritative first-person mouse edits, a near/far draw barrier, save/restart preservation, explicit-air erase and resource cleanup.

The frozen native suite passed **356 tests**. Actual native-client testing passed three ordinary mouse edits (right/left/right, revisions 1/2/3), held-button no-repeat, sky/outside-cube/third-person rejection, same-world restart and resource reload preserving revision 3, and four cleanly closed epochs. All final actor, native mesh and far-render owners closed cleanly. See [the gate and commands](docs/AUTHORITATIVE_CORE_RUNTIME.md) and [machine-readable verification](docs/evidence/core-runtime-002.json).

The current runtime gate uses an analytic flat provider, a fixed 125-cube working set, 27 inner mesh targets and a 512 m far field. It hosts a spectator camera in an empty stock-height carrier dimension. Mouse edits have an 8 m reach and are restricted to the known central 16³ cube. Player collision, survival, native lighting, fluids/entities, public-server flood protection and general edited distant terrain are not completed in this checkpoint. A measured 225.9 ms maximum CPU update is an unresolved control-rebuild hitch. Mesa software-OpenGL results do not establish hardware FPS or smooth performance.

## Other verified experiment

Terrain-view003 separately passed native movement, moving far-field recentering, near/far coverage and resource lifecycle checks, with 306 headless tests. It still has significant startup/upload stalls and no gameplay or hardware-performance acceptance. [Its verification report](docs/evidence/terrain-view-003.json) records the limits. Its [separate frozen source branch](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-003/README.md) preserves that experiment without mixing it into this server-authority checkpoint. The older terrain-view001 seam failure is historical and is not the current default gate.

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
export JAVA_TOOL_OPTIONS="-Dearthplatform.experimental.authoritativeCoreGate=true"
bash scripts/gradle.sh runClient
```

Create a disposable Creative test world with commands enabled. Use /earth_core enter, then ordinary right/left mouse input in the bounded editable area. /earth_core edit and /earth_core erase remain operator diagnostics; /earth_core leave closes the gate. The gate document describes reach, target bounds and restart/reload checks. Do not use an existing valuable world for this prototype.

## Source and data scope

This repository contains project source, tests, build bootstrap, source-attributed redistributable fixture crops and license notices. It excludes proprietary Minecraft binaries/assets/decompiled code, the DH runtime JAR, JDKs, Gradle caches, runtime saves, credentials, unrelated private photographs and the research-only Mariana dataset. See [third-party notices](THIRD_PARTY_NOTICES.md) and each data fixture's source-lock/provenance files. This independent project is not endorsed by Mojang or Microsoft.
