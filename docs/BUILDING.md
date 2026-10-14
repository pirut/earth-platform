# Building and testing

The mod is one Gradle project in `minecraft/`. It also compiles the pure Java code in `core/`, `streaming/` and `geography/` as extra source folders, so there is a single jar.

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

The jar is `minecraft/build/libs/earthplatform-<version>.jar` (for example `earthplatform-0.1.0-alpha.37.jar`; the version is `mod_version` in `minecraft/gradle.properties`). That one file is the whole mod. `scripts/gradle.sh` runs the same wrapper and passes an `HTTPS_PROXY` on to Java when one is set.

## Test

```sh
./gradlew --no-daemon test --continue
```

About 1,300 unit tests run in a few minutes, and all should pass. GitHub runs the same build and tests on every pull request (`.github/workflows/ci.yml`), and every merge into `main` publishes the newest build as a GitHub release (`.github/workflows/release.yml`). `core/test.sh` and `streaming/test.sh` run the stand-alone test suites of those two folders with plain `javac`.

## Try it in game

Install the jar into a NeoForge 21.1.256 or newer instance for Minecraft 1.21.1 and create a new Earth world. Use a fresh world after any change to terrain generation, because a world keeps the data choices it was made with. Downloaded Earth data lands in `earthplatform/cache` inside the game folder.

## Where things are

- `minecraft/src/main/java/io/earthplatform/minecraft/cubic`: the cubic world engine (cubes from Y -16384 to 16383, streaming, lighting, the far terrain renderer, the travel map and the Earth commands).
- `geography/src/main/java`: the Earth itself: elevation, land cover, climate, rivers and the data sources they stream from.
- `core/` and `streaming/`: storage and cube streaming that don't depend on Minecraft.
- [ROADMAP.md](../ROADMAP.md): how the engine works and what comes next.
