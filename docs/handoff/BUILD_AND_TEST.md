# Existing build and test instructions

These commands are provided for the next owner-authorized continuation. They were not executed during handoff. Candidate039 remains unplayable; build success is not a launch or survival qualification.

## Toolchain and upstream inputs

Use Java21 and the previously used Gradle9.2.1. Build configuration pins Minecraft1.21.1, NeoForge21.1.256 and ModDev2.0.148. Consult `minecraft/gradle.properties`, `minecraft/build.gradle` and `minecraft/gradle/wrapper/gradle-wrapper.properties` as the executable source of truth.

The Gradle wrapper scripts/properties are present, but the binary wrapper JAR is omitted along with other executable artifacts. Use an independently installed official Gradle9.2.1, or regenerate a wrapper only when authorized. No vendor toolchain/mod/shader files are bundled. Obtain the exact official external files listed in `historical/exploration-037/README.md` and preserve their license requirements:

- DH: DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar
- Iris: iris-neoforge-1.8.14-beta.1+mc1.21.1.jar
- Sodium: sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar
- Photon: photon_v1.3b.zip

Set EARTH_INSTALLED_DH_JAR, EARTH_INSTALLED_IRIS_JAR, EARTH_INSTALLED_SODIUM_JAR and EARTH_INSTALLED_SHADER_PACK to their separately downloaded absolute paths. The build checks exact bytes; do not replace with latest versions to make resolution easier. The four pinned Fabric API modules are nested in Sodium; do not download extra modules independently.

## Source build and components

From `minecraft/`, a normal source build uses `gradle --no-daemon --console=plain jar`; compilation requires the verified external inputs above. For pure lower layers, read `core/test.sh` and `streaming/test.sh` before using those scripts. These commands can need network dependency resolution and are not claimed to run offline from this cleaned source alone.

The exact historically executed focused69 test command is preserved in `review-evidence/survival-foundation-batch-69/run.sh`. It invokes the selected survival/scene/DH component classes, installedDhTest, shaderCompatibilityTest and jar with fresh output. Replace its original ROOT/toolchain/output paths for your own workspace; do not copy its old proxy/trust-store assumptions blindly. Keep per-run outputs isolated and bind results to the actual source revision. A focused subset is not an aggregate or gameplay pass.

`minecraft/build.gradle` contains the complete source-set/task registrations. The native run configuration is `survivalGameTest`; prior supervised native fixtures use dedicated init scripts, source overlays, game directories and exact launch receipts. Read the relevant evidence run.sh/init script before reproducing a specific fixture. Do not assume a plain general GameTest launch is equivalent to read4 or codec3.

## Launch status

There is no qualified039 end-user survival launch recipe. The Gradle client task and existing `launch-client.sh` are development tools; they retain historical profile assumptions. The old037 install/play instructions are in `historical/exploration-037/README.md` and `QUICK-START.md` and require a separately obtained matching037 artifact, dependencies and disposable world. Do not install an arbitrary039 build and label it037.

Installed006 observation tooling lives in `unfinished/probes/installed-session-probe-039-006`; its ROOT-HANDOFF.md describes a controlled probe, not normal gameplay. Do not infer real collision, interactive controls, Local adoption or a full scene from observation endpoints.

Use an empty disposable game directory, not a user's ordinary saves. Source/probe scripts can write configuration, worlds and test artifacts; review the intended paths first. Never bypass failed admission, lower selected native distance or disable an invariant simply to produce a green result.

## Reproducibility limits

Raw game worlds, player files, complete build TARs, vendor JARs and cache files are excluded from Git. Their original hashes/inventories remain. Some historical evidence replay scripts therefore require recovery artifacts or a new correctly authorized run. Current source and authored tests are preserved, but this repository does not claim a bit-for-bit offline environment or a newly reproduced pass.
