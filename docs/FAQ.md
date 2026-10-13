# FAQ and troubleshooting

## Playing

**Is it really full size?**
Yes. One block is one metre in every direction around the place your world starts, sea level is Y 63, and heights are real: Everest's summit is near Y 8,900 and the Dead Sea shore is below sea level. Worlds run from Y -16384 to 16383. Across very long distances the map uses Web Mercator, so far from your starting latitude a block is slightly more or less than a metre east to west.

**Where is everything?**
`/earth where` gives your latitude, longitude and real height. `/earth visit <place>` and `/earth goto <latitude> <longitude>` take you anywhere; `/earth places` lists famous spots. On servers only operators can travel unless the server turns on `anyoneCanTravel`. See [Playing on the Earth](PLAYING.md).

**Why are there no cities, roads or buildings?**
On purpose. Built-up land becomes the wild land around it, so the world is yours to build. Vanilla villages, temples, strongholds and other structures still generate.

**How accurate is it?**
Mountains, valleys, coasts and lakes come from measured elevation (about 30 m apart in most places, finer in some countries). Forests, deserts, ice and wetlands come from 10 m satellite land cover. Smaller bumps, caves, ores, individual trees and plants are procedural. Use it for fun, not for navigation or route planning.

**Can I play survival?**
Yes. Mining, crafting, mobs, beds, death and respawn, the Nether and the End all work as usual. Nether portals built on the Earth link to the Nether and back.

**What does the satellite imagery option do?**
When turned on with the **Satellite imagery** toggle on the Customize screen or `satelliteImagery = true` in `config/earthplatform-common.toml` (see [the install guide](INSTALL.md#satellite-ground-colours-optional)), Sentinel-2 cloudless imagery nudges the choice of ground blocks, such as sand, gravel, stone and dry soil, and how dense trees are, to match each place's real colours. Biomes and tree kinds don't change. It's off by default.

**Can I use an old world?**
New Earth worlds need the Earth world type when they are created. Worlds made with the Default type stay ordinary Minecraft worlds.

## Internet and disk space

**Do I need the internet?**
Only the first time you visit a place. The mod downloads that area's elevation, land cover and climate and keeps them on your computer, so later visits are instant and work offline. A note at the top of the screen tells you when the data servers can't be reached; new ground fills in once the connection is back.

**How much disk space does it use?**
It depends how much of the planet you explore. Downloaded data lives in `earthplatform/cache` inside your game folder. Set a size limit in Mods → Earth Platform → Config (or `config/earthplatform-common.toml`); the oldest files are removed when the game starts. Deleting the folder is safe: it downloads again when needed.

**Stuck on "Preparing your Earth"?**
The starting place has not finished downloading. Check your connection; the screen keeps retrying and enters the world as soon as the ground has arrived. Behind a firewall, allow `s3.amazonaws.com`, `esa-worldcover.s3.eu-central-1.amazonaws.com` and `os.zhdk.cloud.switch.ch` (plus `tiles.maps.eox.at` if you turned satellite imagery on).

## Performance

**It's slow or stutters.**
- Give the game at least 4 GB of memory (6 GB if you raise far terrain detail).
- Lower **far terrain detail** in Mods → Earth Platform → Config → Graphics (restart to apply). The default is 11; 6 to 8 helps weaker GPUs.
- Flying fast over new ground downloads and builds terrain as you go; it settles once the area is cached.
- Sodium helps frame rate. Use the exact version from the [install guide](INSTALL.md#shaders-optional).

**Far terrain pops in or looks blocky.**
Distant terrain is built in levels that get coarser with distance and refine as you approach. Higher far terrain detail keeps finer levels further out at the cost of memory.

## Shaders

**Which shader packs work?**
Photon v1.3b is tested. Packs that include Voxy support light the far terrain like nearby blocks. Other packs work for nearby blocks, but far terrain may look flat.

**The game won't start after adding Iris or Sodium.**
Earth Platform needs the exact versions in the [install guide](INSTALL.md#shaders-optional). Newer or older Iris or Sodium builds are refused at start-up so they can't break the far terrain.

**Do I need special Photon settings on a Mac?**
No. Use the official Photon zip at its defaults. Earth Platform adapts it for Apple's OpenGL on your own computer (in `earthplatform/photon-compat/`), leaving your zip untouched. Colored Lights, Colored Shadows and Cloud Shadows are unavailable on a Mac. See [On a Mac](INSTALL.md#on-a-mac).

**My log says "Failed to parse an ResourceLocation in item.properties".**
Harmless; it comes from Photon itself.

**Should I use Distant Horizons?**
There's no need. Earth Platform has its own far terrain. If Distant Horizons is installed, it pauses itself while you're in an Earth world (a note in chat says so) and works as usual in other worlds.

## Other mods

**Which other mods work?**
Most do. Tested in the Earth world: Xaero's Minimap and World Map, JourneyMap, Create (contraptions assemble as usual), JEI, EMI, Jade, AppleSkin, Farmer's Delight, Waystones, Sophisticated Backpacks, Supplementaries, YUNG's structures, and Open Parties and Claims. Terralith and other biome mods have no effect on the Earth, since the real climate picks the biomes. Sodium and Iris need the exact versions in the [install guide](INSTALL.md#shaders-optional).

**A mod acts as if blocks aren't there, or as if I'm outside the world.**
Some mods check the world's height limits themselves and don't know the Earth world is 32,768 blocks tall. Please report it with the mod's name and version; Create had this and is fixed.

**Mobs stand still or villagers won't trade.**
That was a bug in early test builds and is fixed. Update to the latest version.

## Servers

**How do I run a server?**
Set `level-type=earthplatform\:earth` in `server.properties` before the world is created. See [Dedicated servers](INSTALL.md#dedicated-servers). The server needs internet access; players need the mod but not the data.

## Licence

**Can I put it in a modpack, or change the code?**
Yes. Earth Platform is free software under the GNU LGPL 3.0: include it in any modpack, and fork or port it as long as your changes to its code stay open under the same licence. The Earth data it downloads has its own terms and credits, listed in [CREDITS.md](../CREDITS.md).

## Reporting a problem

Include your `logs/latest.log`, the place (`/earth where`), your mods list, and whether shaders were on.
