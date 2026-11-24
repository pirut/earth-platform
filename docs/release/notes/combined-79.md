Earth Platform build 79 makes `/forceload`, portal loops and chunk-loader mods work in the Earth.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Chunk loading

- `/forceload` now works in the Earth. It keeps the area round you loaded and running, about 96 blocks up and down from the height you ran it at, and it survives restarts (#276).
- `/forceload query` shows the height band. For an exact height, use `/execute positioned <x> <y> <z> run forceload add <x> <z>` (#276).
- Nether portal item loops keep their Earth exit loaded (#276).
- NeoForge chunk-loader mods keep the area round their block loaded (#276).
- Crops and saplings in forced areas grow with no player online, away from the edges of the area (#276).
- New config option `chunkLoaderCubeLimit` (default 8,192 cubes) caps how much is held. Loaders are admitted first come, first served (#276).

### Releases

- This build is published on CurseForge and Modrinth as Beta, for both the NeoForge and Fabric jars. The version in the Mods screen is `0.1.0-beta.79`.
