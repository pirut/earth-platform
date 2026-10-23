# Building and testing

The mod is one Gradle project in `minecraft/`. It also compiles the pure Java code in `geography/` as an extra source folder. The cubic engine is its own library mod, [Chunks3](https://github.com/pirut/chunks3), checked out as the `chunks3/` submodule and built with the mod; its jar is bundled inside Earth Platform's (jar-in-jar), so there is still a single jar to install.

## What you need

- JDK 21. Set `JAVA_HOME` to it.
- The `chunks3/` submodule: clone with `git clone --recurse-submodules`, or run `git submodule update --init` in an existing clone.
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

## Where things are

Start with the `package-info.java` in each package: it says what the package does and which classes to read first.

| Path | What it is |
| --- | --- |
| `minecraft/src/main/java/io/earthplatform/minecraft/EarthPlatformMod.java` | The NeoForge entry point. Everything else registers itself with `@EventBusSubscriber` |
| `chunks3/src/main/java/io/github/pirut/chunks3` | The cubic world engine (the Chunks3 library): cubes from Y -16384 to 16383, how they are made, saved, lit and sent (`ServerCubeWorld`, `CubeWorld`, `CubeStorage`, `CubicEvents`); `Cubic.register` makes a dimension cubic |
| `.../chunks3/gen` | Terrain shared by every cubic world: `SurfaceGenerator` turns column heights into cubes; caves, rock layers, plants, trees and vanilla structures |
| `.../chunks3/far`, `client`, `net` | Far terrain on the server (`Far`, `FarServer`), drawing near cubes and far terrain with shader pack support (`CubeRenderer`, `FarRenderer`, `FarGpu`, `FarShaders`, `FarLod`, `PhotonCompat`), and cube traffic |
| `.../chunks3/mixin`, `compat`, `portal` | Hooks into vanilla, NeoForge, Iris and Sodium (`chunks3.mixins.json`), other mods, and Nether and End travel |
| `.../minecraft/cubic/earth` | The real Earth: `EarthWorld` registers the Earth's cubic dimension, `EarthGenerator` decides every column's height, water and surface from the Earth data; the Earth's own mixins are in `earth/mixin` (`earthplatform.earth.mixins.json`) |
| `.../minecraft/cubic/play` | What players and operators use: the Earth world type, settings, `/earth` commands, spawns, travel (`EarthTravel`), the travel map, games. Screens are in `play/client` |
| `.../minecraft/cubic/jet` | The jet stream |
| `geography/src/main/java/io/earthplatform/geography/globe` | The Earth data, with no Minecraft code: elevation tiles and national layers, land cover, climate, rivers, imagery, and their download and cache code |
| `minecraft/src/test` | Unit tests, and `GlobeTest`, which runs the Earth data code against a local tile server. `CubicBench` (opt-in, set `EARTH_BENCH`) prints a world fingerprint for checking that a change leaves generation alone |
| [docs/FLAGS.md](FLAGS.md) | Every `-Dearthplatform.*` developer flag (the engine reads them as `-Dchunks3.*`; the Earth spelling still works) |
| [ROADMAP.md](../ROADMAP.md) | How the engine works and what comes next |

### Changing things safely

- A world pins its generator and data sources when it is made. Anything that changes generation needs a fresh world to see, and changes every world made after it.
- Seed salts, hash functions, saved ids, network payload ids, cache folder names, mixin class names and flag names are read by saved worlds, other clients or the mixin system. Keep them as they are unless you mean to break compatibility.
- Before and after a generation change, compare `CubicBench` fingerprints: `EARTH_BENCH=<cache folder> ./gradlew test --tests '*CubicBench' --rerun -i`.
