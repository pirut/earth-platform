# Earth Platform

A Minecraft Java cubic-world platform prototype. The development priority is the general-purpose cubic core and engine integration; the Earth geography provider is an optional stress fixture and future plugin.

Status: active prototype, October 9, 2026. A NeoForge mod builds, creates disposable Creative worlds, and renders three real-data terrain windows; this is not yet a complete playable Earth platform. A passing standalone core test does not prove that Minecraft supports its coordinates, lighting, entities, networking or rendering.

## Modules

- `core`: signed 3D cube coordinates, bounded residency, asynchronous generation and durable player-edit overlays.
- `geography`: documented projection and elevation/bathymetry providers, source provenance and reproducible data packs.
- `minecraft`: Minecraft engine adapter, native-class tests and experimental integration gates.
- `streaming`: bounded world/epoch/lease/revision admission for cube snapshots; engine transport integration is pending.

## Required proofs

1. Blocks at approximately Y=+8849 and Y=-11000, without vertical compression.
2. Bounded cube count, in-flight requests, cache bytes and network traffic while travelling and teleporting.
3. Place/break, fluids, entities and block lighting across vertical cube boundaries.
4. Correct skylight under an unloaded mountain and after opening a shaft.
5. Save, restart and restore player edits without rewriting them when data sources change.
6. Cold/warm caches, offline operation, provider failures and cancellation.
7. Multiple clients at widely separated heights without loading entire columns between them.
8. Actual measured client frame time/server tick time on stated hardware. No performance claims based only on asymptotic reasoning.
9. Distant Horizons or Voxy integration with genuinely long-distance views that communicate Earth scale. Required by the user October 9; loader compatibility alone is insufficient. Verify native wide-Y, bounded far-field generation, edits and cache identity before claiming support.

## Data accuracy

One block represents one meter in the chosen coordinate system. This does not imply measured one-meter terrain data everywhere. Worldwide public land DEMs and ocean grids have substantially coarser spacing. Vertical datums, source dates and uncertainty must be recorded. Terrain is a surface/seafloor representation; global underground geology is not available as a measured three-dimensional voxel dataset. Any procedural caves/ores will be identified as procedural.

## Safety and scope

Development runs in dot's own isolated cloud workspace. This is a private development repository for the project owner. No credentials, game binaries, runtime worlds or unrelated personal files are included. Third-party source/data licenses must be checked before reuse and distribution. Public map-tile services are not a bulk download backend. This is an independent project, not endorsed by Mojang or Microsoft.

See `docs/ARCHITECTURE.md` and `docs/SOURCES.md`. Check each module's test report for current implementation status.

## Reproduce independent validation

With Java21 available through JAVA_HOME, run `./scripts/test-independent.sh`. This runs the core, geography, source-composition, terrain-variety, streaming and land-cover suites without Minecraft or network access once the checked data packs are present. Python3, NumPy and Pillow are required for the higher-resolution importer checks; tested package versions are in `requirements-dev.txt`. See the module READMEs for their exact dependencies. Native engine tests and launch are separate under `minecraft/`.

See `docs/DECISIONS.md` for unresolved geometry, lighting, compatibility and data-fidelity choices.

## Verified native preview (October 9)

The checkpoint004 client loaded NOAA San Francisco, Everest and Mariana windows in a disposable Creative world. Screenshots are real in-game captures, not concept art. This preview uses separate 256-block vertical windows with explicit canonical Earth-Y offsets, not continuous full-height travel. The first preview has placeholder grass/trees at Everest; its surface palette is not a land-cover reconstruction. The Mariana screenshot uses Creative night vision. All dimensions saved successfully on exit.

Checkpoint005 separately passed 44 native-class tests, including constructor-safe sparse LevelChunk/EmptyLevelChunk facades and direct reads/writes at Y=-11000/+8849. That isolated fixture is not yet the live world shown in the screenshots. Native ServerLevel/ClientLevel, render, collision, lighting and save integration remain subsequent gates.

## Native sparse-level verification (October 9, 21:52 UTC)

Checkpoint006 passed 68 headless tests, then two root-operated native client runs passed with actual isolated ServerLevel/ClientLevel objects and their caches. At native Y=-11000/+8849 each endpoint retained two cube owners and one column facade, with zero tall legacy section/postprocessing arrays and zero vanilla cached columns/holders. The client constructor's EmptyLevelChunk also allocated zero legacy slots. Generation-qualified retirement/reload and stale-lease rejection passed in two successive epochs. These are data-only diagnostic levels: no player movement, lighting, GPU terrain rendering or playable full-height world is implied.

Distant Horizons integration is an active requirement. Its stock 12-bit terrain height representation cannot cover this Earth's full vertical envelope unchanged. The adaptation uses explicit page origins and is being verified against actual pinned DH classes and shaders. CPU packing/data/quad proofs are not GPU or gameplay compatibility.

## Consolidated source checkpoint 003

This frozen checkpoint combines native006, Copernicus003 and native-render001, the completed opt-in DH first diagnostic, and verified core, geography, streaming and WorldCover land-cover inputs. The exact combined tree passed 97 Minecraft tests and an offline build, plus all independent suites. See `evidence/checkpoint-test-summary.json` and `docs/CHECKPOINT_003.md` for source identities, fresh test counts, recorded native GUI evidence and reproduction steps.

The native renderer now has recorded real graphics-path evidence at Y = -11,000 and +8,849, with retirement, VBO rebuilding and cleanup. It remains an isolated diagnostic with fixed lighting and labeled cameras. The Copernicus ridge remains a separate 256-block preview. Graphics results use software OpenGL. No playable full-height world, actual resource-pack reload, completed near/far coexistence or full DH mod integration is claimed.

WorldCover 2021 adds two bounded, source-backed classification samples with CC BY 4.0 notices. It is not integrated into Minecraft materials or biomes. The later natural-mountain-surface work is intentionally outside this checkpoint.

Run `python3 scripts/verify-checkpoint.py` after extraction. The archive is explicitly allowlisted and contains no game binaries, dependency caches, runtime worlds, credentials or excluded research data.

## Latest published source: sparse terrain-view gate 001

The code in this repository includes the frozen sparse terrain-view gate and its complete engine adapter source. 224 headless native tests passed on the frozen source. Actual GUI testing showed native camera movement, near cube meshes and DH draw working, but revealed coarse shelves and sky cracks at the near/far transition. The visual seam gate FAILED and a correction is pending; this snapshot is not visually accepted. This is a visual-only spectator carrier: no completed cubic collision, player edits, native lighting, full-world persistence, moving far ring or giant view-distance claim is implied. The diagnostic DH adapter uses pinned actual DH classes/shaders; it is not a stock DH compatibility release.

See [the current gate](docs/SPARSE_TERRAIN_VIEW.md), [geographic engine gate](GEOGRAPHIC_ENGINE_GATE.md), and [third-party notices](THIRD_PARTY_NOTICES.md). Historical checkpoint evidence described above applies only to its named snapshot.

### Portable build and optional visual diagnostic

Install a Java 21 JDK and set JAVA_HOME. Run `cd minecraft && bash scripts/gradle.sh test build`. Gradle downloads official Minecraft/NeoForge dependencies locally; they are not included in this repository. The official Gradle wrapper JAR is included only as the build bootstrap.

For the optional terrain-view diagnostic, separately obtain the official Distant Horizons 3.3.3 build for Minecraft 1.21.1, set EARTH_DH_PROBE_JAR to its absolute local path, set JAVA_TOOL_OPTIONS to `-Dearthplatform.experimental.sparseTerrainView=true -Dearthplatform.sceneNaturalRelief=true`, and run `bash scripts/gradle.sh runClient` from minecraft. The checked terrain samples are redistributable fixture data with their original source/license notices. See the gate documentation for commands and limits.

No GitHub Actions workflow is enabled in this initial source checkpoint. Validation reported here was performed in the development workspace, not by GitHub CI.
