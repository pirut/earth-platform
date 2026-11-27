Earth Platform build 86 improves shader compatibility on Earth.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Shader fixes

- Iris uses a pack's explicit Earth mapping when present and otherwise uses its Overworld programs. This lets MakeUp use its Overworld shaders on Earth. The previous lookup is available with `-Dearthplatform.iris.overworld=false`.
- Far terrain handles legacy Voxy texture calls and supplies the game lightmap when a pack samples it without declaring it.
- Far terrain sends additional Voxy outputs to the targets named by the pack. For Complementary, this carries distant material data into later lighting passes.

Photon v1.3b is tested in game. Complementary Reimagined r5.10, Complementary Unbound r5.10 and MakeUp Ultra Fast 9.6a passed offline far-shader checks and still need in-game confirmation.
