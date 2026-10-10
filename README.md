# Earth Cubic Platform

Private development of a general-purpose cubic-world platform for Minecraft Java 1.21.1 / NeoForge 21.1.256. The priority is the cubic core and engine integration. Earth geography is an optional test provider and a later plugin.

## Current checkpoint: authoritative core collision 001

This is an experimental development checkpoint. The current source is frozen from the tested collision001 checkpoint, extending the previous authoritative mouse-editing runtime.

Implemented and checked:

- Signed 3D cube coordinates, bounded asynchronous residency, immutable snapshots and durable player-edit overlays.
- A server-owned cube actor, native block palettes and actual NeoForge framed snapshot packets.
- Bounded native baked-block meshes plus an isolated adapter using actual Distant Horizons 3.3.3 terrain builder/shaders.
- Server-authoritative first-person mouse edits, near/far revision readiness, save/restart preservation and explicit-air erase.
- Native standing, falling, jumping, wall and roof contact through scoped sparse collision snapshots on the exact active server/client level. Ordinary dimensions keep their original path.
- Fail-closed unknown boundaries, bounded movement requests, edit/reload motion fences and safe recovery when saving directly from the walking carrier.

The frozen native suite passed **397 tests**. Actual native-client validation passed support and jumping, low-roof and wall contact, the unknown boundary, mouse placement blocking movement and removal reopening it, F3+T motion fencing, direct walking Save & Quit recovery to the original Overworld, and restart retaining revision 2. See [the gate and commands](docs/AUTHORITATIVE_CORE_COLLISION.md) and [machine-readable verification](docs/evidence/core-collision-001.json).

The current gate uses an analytic flat provider with a finite wall/roof fixture, a fixed 125-cube working set, 27 inner mesh targets and a 512 m far field. Walking is tested at Y128 in a stock-height carrier, with Adventure mode enabled only when both collision sides are ready. Full native Y query tests do not establish full-height player mechanics. Mouse edits have an 8 m reach and are restricted to the central 16³ cube. Moving interest, survival/material effects, native lighting, fluids/entities, public-server flood protection and general edited distant terrain remain incomplete. The measured maximum synchronous update was 546.3 ms. Death/respawn recovery remains unverified and the diagnostic HUD clips at GUI scale 3. Synchronous edit rebuilds can hitch; Mesa software-OpenGL results do not establish hardware FPS or smooth performance.

Cleanup evidence has an explicit timing limit: client CLOSED reports precede a queued collision fence, so epoch 3 still reports 125 collision owners at that sampling point. Epochs 1 and 2 report zero. All three server collision releases, actor drains, native/far owner releases and DH GL cleanup passed; the final post-fence client collision receipt is absent, so measured final client collision zero is not claimed.

## Other verified experiments

Terrain-view003 separately passed native movement, moving far-field recentering, near/far coverage and resource lifecycle checks, with 306 headless tests. It still has significant startup/upload stalls and no gameplay or hardware-performance acceptance. [Its verification report](docs/evidence/terrain-view-003.json) records the limits. Its [separate frozen source branch](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-003/README.md) preserves that experiment without mixing it into this server-authority checkpoint. The older terrain-view001 seam failure is historical and is not the current default gate.

[Terrain-view005](https://github.com/pirut/earth-cubic-platform/blob/experiments/terrain-view-005/README.md) is the newer separate off-thread official DH vertex experiment: 344 native tests and three actual GUI lifecycle epochs passed. Render-owner builder calls were zero. That run recorded zero cancellations and zero recenter commits, so its evidence does not replace terrain003's recenter validation or prove a live build/reload race. Startup and overall-update stalls remain; performance is only partially improved. It is not merged into this collision checkpoint.

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
export JAVA_TOOL_OPTIONS="-Dearthplatform.experimental.authoritativeCoreGate=true -Dearthplatform.experimental.coreCollisionGate=true"
bash scripts/gradle.sh runClient
```

Create a disposable Creative test world with commands enabled. Use /earth_core enter and wait for READY, then /earth_core walk for the bounded walking fixture. /earth_core fly restores spectator mode; ordinary right/left mouse inputs edit the bounded area. /earth_core status records the diagnostic trace and /earth_core leave closes the gate. The gate document describes bounds, collision checks and restart/reload procedures. Do not use an existing valuable world for this prototype.

## Source and data scope

This repository contains project source, tests, build bootstrap, source-attributed redistributable fixture crops and license notices. It excludes proprietary Minecraft binaries/assets/decompiled code, the DH runtime JAR, JDKs, Gradle caches, runtime saves, credentials, unrelated private photographs and the research-only Mariana dataset. See [third-party notices](THIRD_PARTY_NOTICES.md) and each data fixture's source-lock/provenance files. This independent project is not endorsed by Mojang or Microsoft.
