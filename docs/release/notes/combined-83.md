Earth Platform build 83 makes vanilla maps work on Earth, shallows small ponds, and works around an AMD crash.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Fixes

- Vanilla maps now work on Earth and show the terrain around you instead of grey.
- AMD crash workaround: far terrain's depth pre-pass is now off by default on AMD graphics. To force it back on, use `-Dearthplatform.far.depthPrepass=true`.
- Small ponds are no longer carved deep. Lakes under about 450 blocks across may be slightly shallower in newly generated terrain.
- The far-terrain cache log now says exactly why a cache was rebuilt, which makes bug reports easier to read.

### For datapack authors

- New advancement criterion `earthplatform:reached_place`, which triggers when a player reaches a latitude and longitude. See [docs/DATAPACKS.md](../../DATAPACKS.md).
