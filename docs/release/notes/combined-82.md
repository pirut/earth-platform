Earth Platform build 82 fixes far terrain glitches when zoomed in.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Fixes

- Far terrain no longer z-fights or clips when you zoom in with a spyglass or Ok Zoomer. It now draws in its own precise depth when no shader pack is active. To turn this off, use `-Dearthplatform.far.plainDepth=false`.
- Glass and water near you now sort correctly far from the world origin.
