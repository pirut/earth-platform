# Installed-DH shim build and runtime contract

## External official input

The supported input is the separately obtained, unchanged official Distant Horizons 3.3.3 for Minecraft 1.21.1 Fabric/NeoForge artifact, API 7.2.0:

- Filename: `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`
- Size: 28,289,212 bytes
- SHA-256: `864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab`

Supply its path via `EARTH_INSTALLED_DH_JAR` or `-PinstalledDhJar=/absolute/path/to/official.jar`. Existing `EARTH_DH_PROBE_JAR`, `earthplatform.installedDhTestJar` and `earthplatform.dhJar` inputs are accepted for headless tooling. Priority is Gradle property, installed environment variable, legacy environment variable, installed test system property, legacy system property. `verifyOfficialDhInput` checks exact size/hash before compilation or tests. The build has no downloader and no upstream repository coordinate.

The recorded source/provenance links remain in `runtime-inputs/source-manifest.json` and `THIRD_PARTY_NOTICES.md`. This contract does not grant permission to redistribute an upstream payload as part of our shim.

## One full invocation, two test JVMs

Main and test sources compile against the external official artifact using `compileOnly` and `testCompileOnly`. All test sources are compiled together; no inherited tests are deleted or replaced.

- `test`: all historical engine/isolated-DH tests plus build contracts; excludes `io/earthplatform/compat/dh/**`. The official artifact is absent from its runtime classpath. Only this task sets `earthplatform.test.isolatedDhRuntime=true`.
- `installedDhTest`: includes only `io/earthplatform/compat/dh/**`. Its own plain JVM runtime adds the external official artifact and removes only the `net.neoforged.fancymodloader:junit-fml` automatic JUnit bootstrap listener module. Every other normal dependency remains. It does not inherit the regular NeoForge test task's launch-argument wiring and never enables the isolated runtime property. These are already-loaded helper/API contracts, not an actual NeoForge mod-discovery proof.
- `check` and therefore `build` include both tasks. `verifyShimArtifact` also requires both tasks and the resolved classpath contract, so an isolated-only pass cannot produce the integrated verification receipt.

Example full verification from the `minecraft` directory, after authorization to use the shared Gradle cache:

```bash
export JAVA_HOME="/workspace/scratch/67f3a8ccac99/earth-platform/.toolchain/jdk21"
export GRADLE_USER_HOME="/workspace/scratch/67f3a8ccac99/earth-platform/minecraft/.gradle-home"
export EARTH_INSTALLED_DH_JAR="/absolute/path/to/separately-obtained/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar"
scripts/gradle.sh --offline check
```

Retain both `build/test-results/test` and `build/test-results/installedDhTest` XML trees and the complete invocation log. Aggregate their counts explicitly; the inherited 1,672-test checkpoint alone is not the combined candidate's result. Use `--continue` to let both independent partitions finish when a test fails, preserving both failure reports in one correction batch. A full acceptance run requires zero failures, errors or skips. Do not use task exclusions or a focused test filter as a substitute for the aggregate.

`verifyInstalledDhBuildContract` verifies that both compile classpaths see the official input, global/client/legacy-test runtime classpaths do not, the installed test runtime does, and package selection remains disjoint. It also checks that the installed helper classpath differs only by the excluded automatic-bootstrap module and added official artifact, while legacy tests retain their normal FML wiring. Both tasks receive an explicit absolute `earthplatform.test.projectDirectory`; metadata/launcher fixtures never assume NeoForge's test working directory is the project root. The two runtime contract tests also check actual class visibility, API identity and the absence of diagnostic-loader permission in the installed test JVM.

## Shim-only artifact

The only distributable is the built `earthplatform` shim JAR. `verifyShimArtifact` rejects upstream namespaces/assets, nested JARs and upstream-package source. It also fingerprints official upstream entries directly in the external ZIP and rejects exact copied class/resource payloads even under renamed paths. No official ZIP entry is extracted into the build output. The report includes the shim hash, official input hash and both required test partition names.

This is a bounded artifact scan, not a claim that arbitrary modified upstream code can always be recognized. Code review must also ensure all implementation/resources are ours. No upstream source/binary/shader may be packaged, shaded, relocated or embedded. There is no Gradle cache copying step.

`minecraft/run`, `minecraft/run-data`, build outputs and project caches are ignored local state. A local official JAR in `minecraft/run/mods` is a separately installed runtime dependency, not a source or release input. Never package the entire candidate directory or that runtime folder as the shim distribution. Packaging must select the guarded shim JAR or explicit source files with runtime/build/cache exclusions.

## Root-only installed launch

Root/operator installs the official artifact separately as:

`minecraft/run/mods/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`

`launch-client.sh` requires that existing file and its pinned hash before invoking Gradle. Missing or altered files fail before any build/client launch. It does not fetch or install a mod. Its compile-only input points at the already installed file; NeoForge is responsible for discovering the mod in `run/mods`. Host code must resolve the actual `ModList` entry, identity, version, API, artifact and initialized backend. A filesystem preflight does not prove those runtime conditions.

The launcher preserves indexed workspace/scenario flags, sets `earthplatform.experimental.installedDhBackend=true`, and sets `earthplatform.experimental.longDistanceDepthProbe=false`. It does not enable `earthplatform.test.isolatedDhRuntime`. Installed-mode refusal must never invoke the diagnostic classloader as fallback.

The dependency metadata is optional, ordered after DH and client-only with version range `[3.3.3]`. The shim can exist in an ordinary installation without DH; the opt-in cubic backend requires the separately installed supported mod and must fail clearly when unavailable. Optional metadata is not installed-mode permission to use another version.

Headless compilation, API helper tests, artifact scans and shell preflight tests do not establish actual mod startup, terrain coexistence, signed-height rendering, GUI reload, literal restart or ordinary-input behavior. Root owns those runtime acceptance steps after the full aggregate passes.
