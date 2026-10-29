# Roadmap

Last updated October 29, 2026, at build 69.

## Where things stand

Earth Platform is playable and public. You can start anywhere on Earth at one block per meter, play ordinary survival, and see terrain out to about 94 km. It runs on NeoForge and Fabric for Minecraft 1.21.1.

Releases are public alphas on [Modrinth](https://modrinth.com/mod/YnuWHcwC) and the [Releases page](https://github.com/pirut/earth-platform/releases). Every merge to `main` builds one, numbered `0.1.0-alpha.N`, where N is the build number. Each release batches several fixes rather than shipping them one at a time.

## Done

- **Cubic engine.** Worlds run from Y -16384 to 16383. Blocks, lighting, mobs, saving and loading all work at any height, and vanilla gameplay code runs on top of it unchanged.
- **Survival.** Mining, crafting, building, death and respawn, caves, ores, vanilla structures and villages, and the Nether and the End through normal portals.
- **Far terrain.** Our own voxel renderer, built on ideas from Voxy and Distant Horizons. It draws to about 94 km by default (201 km at most, 397 km with the extreme settings), and player builds show in it out to about 6 km.
- **Elevation.** AWS Terrain Tiles for the whole planet, with Copernicus GLO-30 filling gaps, USGS 3DEP for the United States (80, 40, then 20 m on a first visit) and REMA for Antarctica. Bad pixels in the source data are repaired as they load.
- **Climate and biomes.** CHELSA climate and the RESOLVE ecoregion map pick the biome, and ESA WorldCover places forests, grass, rock, sand, snow, wetlands and water.
- **Water.** Lakes and rivers sit level at their surveyed height. Narrow rivers come from JRC Global Surface Water, and small streams are traced from valleys in the elevation data.
- **Getting around.** Spawn picker with a list of places and a globe, `/earth` commands, a travel map in creative (N), and a jet stream for elytra above Y 9,200.
- **Shaders.** Iris with the stock Photon download, including on Macs.
- **Servers and other mods.** Dedicated servers with several players, and a long list of popular mods tested in the Earth world (see the [FAQ](docs/FAQ.md#which-other-mods-work)).
- **Releases.** CI on pull requests, and an automatic release on every merge that goes up on GitHub and Modrinth for both loaders.
- **Fabric.** A Fabric build alongside the NeoForge one, from build 67.

## Now

We are still mostly fixing bugs, regressions and speed, plus a few features players asked for.

- **Speed.** Far terrain while flying, and the first visit to a place nobody has been, are the slowest parts. Each build tries to make them faster without losing detail.
- **Other mods.** Mods that check the world's height themselves need a small fix each. Tell us about any that misbehave in the Earth world.

## Later

Nothing here is promised. These are the known gaps, roughly in the order we expect to look at them.

- Fix the known issues in the [changelog](CHANGELOG.md#known-issues), most of all slow first loads in places nobody has visited.
- Move from alpha to beta once a few releases go out with no serious bug reports.
- Deeper lake beds (a patch exists and is parked until the freeze ends).

## What we decided not to do

- **No cities or roads.** Built-up land becomes the wild land around it. Vanilla villages and structures still generate.
- **No OpenStreetMap data.** Everything comes from elevation, land cover, climate and water surveys.
- **Looks and speed together.** A speed-up must not cost visible detail. Where detail and speed do trade off, the player chooses, with the far detail setting.
- **Our own far renderer.** Voxy and Distant Horizons don't work with cubic worlds, so we wrote our own. If Distant Horizons is installed it is paused in Earth worlds. We don't plan to add support for either.
- **Fixes should be general.** We fix terrain problems by reading the data better, not with lists of special places.

## How it works

For people reading the code. The engine lives in `minecraft/src/main/java/io/earthplatform/minecraft/cubic`.

- **Cubes.** The world is stored as 16-block cubes keyed by their real coordinates. A small carrier dimension provides chunk tickets, entities and ticking, and mixins on `Level` send every block, light, biome and height lookup to the cube store. The server generates, loads, lights and saves cubes on worker threads and sends them to each player nearest first. The client meshes them off the main thread with the vanilla renderers.
- **Far terrain.** A pyramid of voxel sections around the player, six levels, each twice as coarse as the one before. Each column carries its exact ground and water height so distant land sits at the right height instead of snapping to voxel edges. The client packs faces into large GPU buffers and draws them with its own shader. Shader packs that ship Voxy support code (Photon does) light far terrain through that code.
- **Earth.** `EarthGenerator` turns latitude and longitude into a Web Mercator grid at one block per meter, sea level at Y 63. Elevation, land cover and climate are streamed on first visit and cached on disk (8 GB by default), so places you have been work offline. The generator settings are saved with each world when it is made.

Building and running the tests is covered in [docs/BUILDING.md](docs/BUILDING.md).
