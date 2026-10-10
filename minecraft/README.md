# Earth Platform: Minecraft engine adapter

This is a real NeoForge mod project, **not yet the requested seamless full-height cubic engine**.

## Pinned development target

- Minecraft Java 1.21.1
- NeoForge 21.1.256 (exact runtime dependency)
- Java 21
- ModDevGradle 2.0.148
- Gradle 9.2.1, wrapper SHA-256 verified
- Parchment 1.21.1 / 2024.11.17

This older Minecraft target was chosen as a stable integration baseline with an official maintained MDK, not as a claim of compatibility with every mod. The current CubicChunks3 rewrite targets 1.21.6 and explicitly calls itself unusable. It is not installed or executed here. Neither BetterCubicChunks nor RealisticEarth binaries are used.

Official MDK source: https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/tree/ad911709f06d3bdd5b1a500023a9156999216351 . The template archive contained no root LICENSE file; existing Gradle wrapper license notices are preserved. Our source does not redistribute Minecraft or NeoForge game source/binaries. Mod metadata is provisionally All Rights Reserved; no release or external publication has occurred.

## Build and tests

From the parent `earth-platform` directory:

    minecraft/scripts/gradle.sh clean test build

The helper uses the task-local `../.toolchain/jdk21` by default; a supplied JAVA_HOME overrides it. It forwards the managed environment's proxy and existing Java CA trust, and uses a project-local Gradle cache. On an ordinary developer computer with Java 21, `./gradlew test build` also works. First setup downloads official development dependencies and Minecraft assets, and can take several minutes.

Tests are run through the official ModDevGradle NeoForge JUnit launch mode, so coordinate and dimension tests exercise the actual pinned Minecraft classes. They do not start a dedicated server or accept its EULA.

Build output: `build/libs/earthplatform-0.1.0-engine-prototype.jar`.

## Launch the development client

    minecraft/scripts/gradle.sh runClient

This is the official offline-profile development client. It is not an authenticated Minecraft session. No external game server is started. A dedicated-server run task is deliberately absent until its separate legal/setup requirements are approved.

Create a fresh Creative world with commands enabled. The ordinary Overworld remains standard. Use:

- `/earth visit coast`: synthetic plane at canonical Earth Y 0, window base -64
- `/earth visit everest`: synthetic plane at canonical Earth Y +8849, window base +8704
- `/earth visit trench`: synthetic plane at canonical Earth Y -11000, window base -11136
- `/earth visit real_coast`: NOAA terrain at San Francisco, window base -64
- `/earth visit real_everest`: NOAA coarse terrain near the Everest coordinate, window base +8352
- `/earth visit real_trench`: NOAA coarse terrain near Challenger Deep, window base -10656
- `/earth status`: disclose active mapping and limitations

The generator fills actual Minecraft sections with blocks; ordinary physics, block interactions, rendering, saving and packets operate in the bounded window. The HUD clearly labels canonical Earth Y separately from the temporary engine coordinates. Engine Y is 0..255 in each proof dimension. Different windows are separate dimensions, not seamless travel. Do not alter a saved world's window offset or dataset without a migration: vanilla-saved edits are in engine coordinates.

The real-data pack is a checksum-verified, bounded three-region NOAA ETOPO 2022 proof. Its native samples are 15 arcseconds (hundreds of metres), interpolated onto metre-sized blocks. **Neither exact +8849 nor exact -11000 is claimed as measured terrain in this coarse pack.** Those values belong to the synthetic engine calibration. Full global coverage, higher-resolution terrestrial data, a real water mask and correct globally varying ground scale remain open. Out-of-coverage requests fail explicitly; they never generate fictional Earth terrain.

Terrain data is bundled as an independently versioned resource, extracted into the development world's `earthplatform-proof-data` cache, and verified before sampling. To supply a verified alternative manifest in development, set the Java system property `earthplatform.terrainManifest` to its absolute path. The manifest must match the geography module's supported schema/generator version.

## What this first integration proves and does not prove

Implemented: NeoForge entrypoint, actual chunk-generator codec/registration, playable window dimensions, core cube-to-Minecraft-state materialization, real offline terrain integration, dimension transition commands, client coordinate HUD/payload and lossless wide address codec. Tests expose stock engine limits instead of hiding them.

Not implemented: native Minecraft Y -11000..+8849, independent cube residency inside Minecraft's server/client lifecycle, seamless vertical travel, replacement cube lighting, cube-first full chunk networking, sparse 3D rendering, mod compatibility guarantees, global terrain coverage, block-entity/tick/entity migration across windows, authoritative synchronization of vanilla edits into the independent cube store, or production performance targets.

The core module's cubic cache and persistence tests do not establish those Minecraft features. The current generator materializes 16 sections per column; it is not an independent vertical streaming implementation.

See `docs/ENGINE_AUDIT.md` for checked source-level constraints, exact source provenance and the next native-engine integration gates. Do not include `.gradle-home`, `.gradle`, `build`, `run`, `mdk.zip`, `MDK-1.21.1-ModDevGradle-main`, or generated/decompiled game sources in a source distribution.

## Native engine research additions

A guarded signed-section-Y serializer migration and bounded actual-LevelChunkSection snapshot codec now accompany the playable window prototype. They do not enable full-height gameplay. Wide section writes stay disabled unless a developer explicitly sets `earthplatform.experimental.wideSectionY=true`; enabling it creates an incompatible save format that must never be opened without the matching mod. See `docs/ENGINE_CHECKPOINTS.md` for test scope, format safety, allocation/wire-size measurements and remaining integration gates.

The opt-in sparse-facade Gate 0 now tests actual `LevelChunk` and `EmptyLevelChunk` construction and direct block access without full-height section arrays. No live level uses it yet. See `docs/SPARSE_FACADE_GATE0.md` for ownership restrictions and measured allocation, and `docs/CUBIC_INTEGRATION_PLAN.md` for the remaining native integration gates.

## Consolidated checkpoint status

This source tree includes native006, native-render001 and Copernicus003, plus the completed opt-in DH first diagnostic. It passed 97 native-class tests and an offline build. Frozen runtime results and their precise limits are recorded in `../docs/CHECKPOINT_003.md`. This combined source tree was not separately relaunched during packaging. The native diagnostic remains isolated from ordinary playable worlds; the Copernicus preview remains a 256-block window.
