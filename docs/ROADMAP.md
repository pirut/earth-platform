# Roadmap

Updated 2026-10-12. Goal: go anywhere on Earth in Minecraft, with terrain that looks right from a distance (Distant Horizons and/or Voxy LODs, shader packs) and up close, while staying survival friendly: caves, ores, trees, structures and villages.

## Two hosts, one Earth layer

The Earth layer is engine agnostic. It answers "where on Earth is this block column, how high is the real ground, and what does it look like there". Two hosts consume it:

- **Vanilla host (track A).** Minecraft's own chunks with a taller world (Y −64..2031). Real elevation drives Minecraft's own noise generator, so vanilla caves, aquifers, ore veins, carvers, surface rules, features, structures, lighting, mobs and redstone all work unchanged, and DH, Voxy and Iris shaders see an ordinary world. The cost is vertical compression: Everest cannot be 8,849 blocks tall in a 2,096-block world.
- **Cubic host (track B).** The existing cubic engine on main, which targets true 1:1 heights (Y −11008..8864). Its own audit (`LOD_COMPATIBILITY.md`) shows stock DH and Voxy cannot store that height range and that shaders do not apply to its custom meshes. Survival systems would have to be rebuilt on top of it.

**Default chosen:** build track A first because it reaches the playable goal soonest, then continue track B as the long-term 1:1 host. Both use the same `EarthSurface` so a location looks the same in either.

## Track A: playable Earth on vanilla chunks

| Milestone | Scope | Status |
|---|---|---|
| A1 Planet surface layer | Web Mercator block grid, streamed global elevation (AWS Terrain Tiles), single-flight disk/memory cache, bathymetry fill, logarithmic vertical mapping, preview renderer. `geography/.../globe`. | Done in this PR |
| A2 Earth world type | NeoForge mod with an "Earth" world preset: tall dimension, noise settings that copy the vanilla overworld router but take terrain height from an `earthworld:surface_y` density function. `/earth tp <lat> <lon>`, `/earth where`, configurable spawn, in-game data attribution. Verify with headless chunk generation and top-down renders of real places. | Next |
| A3 Geographic biomes | Biome source that picks vanilla biomes from climate (Köppen-Geiger, CC BY 4.0), elevation, slope and latitude; ocean temperature and depth; beaches and rivers. Trees, grass, structures and villages then follow real climate. | Planned |
| A4 Land cover and water | ESA WorldCover 10 m (CC BY 4.0, public COGs on AWS) for forest density, cropland, bare rock, snow and ice, wetlands and built-up areas. A real water mask so lakes sit at their surface level and dry land below sea level (Dead Sea shore, Death Valley) is not flooded. | Planned |
| A5 Photo-referenced appearance | Sample Sentinel-2 imagery (public COGs on AWS) per area: vegetation index drives plant density, colour clustering picks surface blocks and grass/foliage tint, snow cover from imagery. Versioned per world so saves do not change under players. | Planned |
| A6 LOD, shaders, performance | Confirm DH 3.x on NeoForge generates LODs through the Earth generator; Iris shaders with DH; Voxy decision (Fabric only, no 1.21.1 build). Skip noise above each chunk's highest surface, prefetch tiles ahead of the player, keep tile I/O off the server thread. | Planned |
| A7 World creation and servers | Scale and spawn options on the create-world screen, dedicated server config, structure placement on steep terrain, optional OSM roads and buildings. | Planned |

### A1 defaults and why

- **Projection: Web Mercator, 1 block = 1 m at the equator.** Conformal, so mountains and coastlines keep their shape everywhere; elevation tiles are already Web Mercator, so sampling is a linear lookup. Ground metres per block shrink with latitude (0.71 m at 45°, 0.5 m at 60°) and latitudes beyond ±85.05° are not covered. `metresPerBlock` is a world parameter.
- **Vertical: `vertical-log-v1`.** Near sea level 1 m ≈ 1 block; higher ground compresses logarithmically (knee 600) after multiplying by the capped Mercator scale factor so slopes keep their proportions. Everest ≈ Y 1790, Denver ≈ Y 960. Oceans compress hard (knee 10): a 100 m shelf ≈ Y 39, a 4 km abyss ≈ Y 3, the Challenger Deep ≈ Y −7, which keeps seafloors above vanilla's lava level (Y −54) and playable.
- **Data: AWS Terrain Tiles (Terrarium PNG), zoom 13–15.** Public, no key, global, with higher-resolution national surveys where available. Above zoom 10 open sea reads 0 m, so those pixels take zoom 10 bathymetry. Coarse regions are smoothed with a bicubic kernel and Minecraft noise adds small detail; resampling does not create survey detail.

## Track B: cubic engine for true 1:1 height

Continue the verification sequence in `LOD_COMPATIBILITY.md`: page-aware DH data owner (headless), CPU mesh adjacency across vertical pages, native client gate with pinned DH, wide-Y GPU gate, then measured scale. Then add the survival systems the vanilla host gets for free (lighting, fluids, entities, structures), and decide how shaders reach cubic meshes. Plug `EarthSurface` in as a `CubeProvider` so Earth looks the same as in track A.

## Build environment note

Compiling the NeoForge mods needs `maven.neoforged.net` and the Mojang hosts (`piston-meta.mojang.com`, `piston-data.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`). The independent Java tests and the A1 layer build with only a JDK.
