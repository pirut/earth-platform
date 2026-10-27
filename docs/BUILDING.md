# Building and testing

The mod is one Gradle project in `minecraft/`, which builds the NeoForge jar. It also compiles the pure Java code in `geography/` as an extra source folder, so there is a single jar. A second project in `fabric/` builds the Fabric jar from the same sources plus a little Fabric glue; see [Fabric](#fabric).

## What you need

- JDK 21. Set `JAVA_HOME` to it.
- Gradle 9.2.1, through the wrapper in `minecraft/` or your own install. A fresh clone has no `gradle-wrapper.jar` (the `*.jar` ignore rule catches it), so run `gradle wrapper` once in `minecraft/` or call an installed Gradle 9.2.1 directly.
- Network access to Maven Central, `maven.neoforged.net` and Mojang's download hosts. Gradle fetches Minecraft 1.21.1 and NeoForge 21.1.259.
- Three files you download yourself, because the mod builds against them but never bundles them. Every build task checks their SHA-256 first:

| Environment variable | File | SHA-256 |
| --- | --- | --- |
| `EARTH_INSTALLED_IRIS_JAR` | [iris-neoforge-1.8.14-beta.1+mc1.21.1.jar](https://cdn.modrinth.com/data/YL57xq9U/versions/KduFYu4t/iris-neoforge-1.8.14-beta.1%2Bmc1.21.1.jar) | `60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314` |
| `EARTH_INSTALLED_SODIUM_JAR` | [sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar](https://cdn.modrinth.com/data/AANobbMI/versions/iw5APcWy/sodium-neoforge-0.8.12-beta.1%2Bmc1.21.1.jar) | `dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d` |
| `EARTH_INSTALLED_SHADER_PACK` | [photon_v1.3b.zip](https://cdn.modrinth.com/data/lLqFfGNs/versions/gUv7fBPN/photon_v1.3b.zip), unmodified | `120897768eaa8cb1f14a3e98f88dff009ae3204ee3b96904a4fe8586edfc1ead` |

Set each variable to the file's absolute path, or pass the same paths as `-PinstalledIrisJar=`, `-PinstalledSodiumJar=` and `-PinstalledShaderPack=`.

## Build

From `minecraft/`:

```sh
./gradlew --no-daemon jar
```

The jar is `minecraft/build/libs/earthplatform-<version>.jar` (for example `earthplatform-0.1.0-alpha.38.jar`; the version is `mod_version` in `minecraft/gradle.properties`). That one file is the whole mod. `scripts/gradle.sh` runs the same wrapper and passes an `HTTPS_PROXY` on to Java when one is set.

## Test

```sh
./gradlew --no-daemon test --continue
```

The unit tests run in a couple of minutes, and all should pass. They include `GlobeTest`, a harness for the Earth data code that serves synthetic tiles from a local HTTP server. GitHub runs the same build and tests on every pull request (`.github/workflows/ci.yml`), and every merge into `main` publishes the newest build as a GitHub release and, when the repository has a `MODRINTH_TOKEN` secret, uploads the same jar to Modrinth (`.github/workflows/release.yml`).

## Try it in game

Install the jar into a NeoForge 21.1.256 or newer instance for Minecraft 1.21.1 and create a new Earth world. Use a fresh world after any change to terrain generation, because a world keeps the data choices it was made with. Downloaded Earth data lands in `earthplatform/cache` inside the game folder.

## Fabric

`fabric/` builds `fabric/build/libs/earthplatform-fabric-<version>.jar` with Fabric Loom. It compiles `minecraft/src/main` and `geography/` as they are, with the Fabric glue in `fabric/src/main/java`, and takes its version from `minecraft/gradle.properties`. It needs Gradle 9.5 or newer (CI uses 9.8.2) and the Fabric builds of Iris and Sodium, checked by SHA-256 like the NeoForge ones:

| Environment variable | File | SHA-256 |
| --- | --- | --- |
| `EARTH_INSTALLED_FABRIC_IRIS_JAR` | [iris-fabric-1.8.14-beta.1+mc1.21.1.jar](https://cdn.modrinth.com/data/YL57xq9U/versions/bAo1Qhte/iris-fabric-1.8.14-beta.1%2Bmc1.21.1.jar) | `0ceb694040b4628bf3fa02286cfe5f0db833195669ab07f8caa4001359763eff` |
| `EARTH_INSTALLED_FABRIC_SODIUM_JAR` | [sodium-fabric-0.8.13+mc1.21.1.jar](https://cdn.modrinth.com/data/AANobbMI/versions/SMxNOGZ6/sodium-fabric-0.8.13%2Bmc1.21.1.jar) | `3d43c14985a4deb19c654aad3393b454cf57e1ebcbba86c4ae6a98dfc60eca2d` |

```sh
cd fabric && gradle --no-daemon build
```

Shared code reaches the loader only through `io.earthplatform.minecraft.platform` (`Loader`, `Platform`, `ClientPlatform`, `Payloads`), which each loader fills in through `META-INF/services`. The NeoForge side lives in `minecraft/src/neoforge` (event subscribers, payload registration, NeoForge-only mixins in `earthplatform.neoforge.mixins.json`); the Fabric side in `fabric/src/main` (Fabric API callbacks and the mixins Fabric needs for hooks NeoForge provides as events, in `earthplatform.fabric.mixins.json`). Config files use NeoForge's config classes on both, through [Forge Config API Port](https://github.com/Fuzss/forgeconfigapiport) bundled in the Fabric jar. Compatibility mixins for other mods (`mixin/compat`) are NeoForge-only for now. Shared mixins that target a lambda list both its NeoForge name and its Fabric intermediary name, and build against the oldest Fabric Loader the jar accepts (newer Mixin versions write annotations older loaders can't read).

## Where things are

Start with the `package-info.java` in each package: it says what the package does and which classes to read first.

| Path | What it is |
| --- | --- |
| `minecraft/src/neoforge/java/io/earthplatform/minecraft/neoforge` | The NeoForge entry point (`EarthPlatformNeoForge`) and every NeoForge event the mod listens to (`NeoForgeEvents`, `NeoForgeClientEvents`) |
| `fabric/src/main/java/io/earthplatform/minecraft/fabric` | The Fabric entry points (`EarthPlatformFabric`, `EarthPlatformFabricClient`) and their mixins |
| `minecraft/src/main/java/io/earthplatform/minecraft/platform` | What shared code asks of the loader |
| `.../minecraft/cubic` | The cubic world engine: cubes from Y -16384 to 16383, how they are made, saved, lit and sent (`ServerCubeWorld`, `CubeWorld`, `CubeStorage`, `CubicEvents`) |
| `.../minecraft/cubic/gen` | Terrain shared by every cubic world: `SurfaceGenerator` turns column heights into cubes; caves, rock layers, plants, trees and vanilla structures |
| `.../minecraft/cubic/earth` | The real Earth: `EarthGenerator` decides every column's height, water and surface from the Earth data |
| `.../minecraft/cubic/far` | Far terrain on the server: the voxel pyramid (`Far`) and the workers that make its columns (`FarServer`) |
| `.../minecraft/cubic/client` | Drawing: near cubes (`CubeRenderer`), far terrain (`FarRenderer`, `FarMesher` and `FarColumnMesh`, `FarGpu`, `FarShaders`) and shader pack support (`FarLod`, `PhotonCompat`) |
| `.../minecraft/cubic/net` | Cube and far column traffic, and the pacing for remote players |
| `.../minecraft/cubic/play` | What players and operators use: the Earth world type, settings, `/earth` commands, spawns, the travel map, games. Screens are in `play/client` |
| `.../minecraft/cubic/mixin` | Hooks into vanilla, NeoForge, Iris and Sodium, listed in `src/main/resources/earthplatform.cubic.mixins.json`; `mixin/compat` hooks into optional mods |
| `.../minecraft/cubic/compat`, `portal`, `jet` | Other mods, Nether and End travel, the jet stream |
| `geography/src/main/java/io/earthplatform/geography/globe` | The Earth data, with no Minecraft code: elevation tiles and national layers, land cover, climate, rivers, imagery, and their download and cache code |
| `minecraft/src/test` | Unit tests, and `GlobeTest`, which runs the Earth data code against a local tile server. `CubicBench` (opt-in, set `EARTH_BENCH`) prints a world fingerprint for checking that a change leaves generation alone |
| [docs/FLAGS.md](FLAGS.md) | Every `-Dearthplatform.*` developer flag |
| [ROADMAP.md](../ROADMAP.md) | How the engine works and what comes next |

### Changing things safely

- A world pins its generator and data sources when it is made. Anything that changes generation needs a fresh world to see, and changes every world made after it.
- Seed salts, hash functions, saved ids, network payload ids, cache folder names, mixin class names and flag names are read by saved worlds, other clients or the mixin system. Keep them as they are unless you mean to break compatibility.
- Before and after a generation change, compare `CubicBench` fingerprints: `EARTH_BENCH=<cache folder> ./gradlew test --tests '*CubicBench' --rerun -i`.
