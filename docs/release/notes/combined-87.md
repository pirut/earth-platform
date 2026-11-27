Earth Platform build 87 clears up terrain and biome oddities in cold, mild and coastal regions, adds a setting to leave coarse dirt out of new worlds, explains why far terrain may be disabled, and updates the FAQ.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Fixes

- **Caves:** Mountain caves no longer generate lava above sea level.
- **Palms and cold coasts:** Cold coastlines (such as Iceland) no longer spawn palm trees, including modded palms, while dune beaches stay intact.
- **Mild-climate freezing:** Mild and coastal climates no longer freeze water or snow at modest elevations, while polar ice is preserved.
- **Polar oceans:** Freezing polar oceans (such as Antarctica) no longer generate sand ocean floors or kelp, and Antarctic waters stay free of polar bears.
- **Biome classification:** Coastal barrier islands (such as the Outer Banks) are no longer misclassified as swamps, Dark Forest placement is tightened, and standalone vanilla huge mushrooms are no longer treated as regular trees.

### Improvements

- **Far terrain notice:** A one-time in-game chat message now explains why far terrain is disabled, and failing data host names are logged when hosts become unreachable.
- **Coarse dirt setting:** The new `earth.coarseDirt` world setting is enabled by default and written only when off. When off, new worlds replace coarse dirt with grass, plain dirt or podzol, leaving vegetation and underlayers unchanged.
- **FAQ:** `docs/FAQ.md` now answers common player questions on biome mods, Mercator and Dymaxion poles, Far View and Sharpness settings, Fabric support and `.jar` installation.

### Saved worlds

- Existing worlds keep coarse dirt on by default unless it is disabled in `generator.properties`. Terrain and biome fixes apply to newly generated chunks.
