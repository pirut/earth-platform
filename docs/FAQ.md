# FAQ and troubleshooting

## Playing

**Is it really full size?**
Yes. One block is one meter in every direction around the place your world starts, sea level is Y 63, and heights are real: Everest's summit is near Y 8,900 and the Dead Sea shore is below sea level. Worlds run from Y -16384 to 16383. Across very long distances the map uses Web Mercator, so far from your starting latitude a block is slightly more or less than a meter east to west.

**Where is everything?**
`/earth where` gives your latitude, longitude and real height. `/earth visit <place>` and `/earth goto <latitude> <longitude>` take you anywhere; `/earth places` lists famous spots. On servers only operators can travel unless the server turns on `anyoneCanTravel`. See [Playing on the Earth](PLAYING.md).

**Why are there no cities, roads or buildings?**
On purpose. Built-up land becomes the wild land around it, so the world is yours to build. Vanilla villages, temples, strongholds and other structures still generate.

**How accurate is it?**
Mountains, valleys, coasts and lakes come from measured elevation (about 30 m apart in most places, finer in some countries). Forests, deserts, ice and wetlands come from 10 m satellite land cover. Smaller bumps, caves, ores, individual trees and plants are procedural. Use it for fun, not for navigation or route planning.

**Can I play survival?**
Yes. Mining, crafting, mobs, beds, death and respawn, the Nether and the End all work as usual. Nether portals built on the Earth link to the Nether and back.

**How do I travel long distances in survival?**
Ride the jet stream: fly an elytra up above about Y 9,200 and the wind carries you at over 1,000 blocks a second. At sea, ride an ocean current: sprint while you paddle a boat forward on the open sea and it carries you at about 900 blocks a second. See [The jet stream](PLAYING.md#the-jet-stream) and [Ocean currents](PLAYING.md#ocean-currents).

**What does the satellite imagery option do?**
When turned on with the **Satellite imagery** toggle on the Customize screen or `satelliteImagery = true` in `config/earthplatform-common.toml` (see [the install guide](INSTALL.md#satellite-ground-colours-optional)), Sentinel-2 cloudless imagery nudges the choice of ground blocks, such as sand, gravel, stone and dry soil, and how dense trees are, to match each place's real colors. Biomes and tree kinds don't change. It's off by default.

**Can I use an old world?**
New Earth worlds need the Earth world type when they are created. Worlds made with the Default type stay ordinary Minecraft worlds.

## Internet and disk space

**Do I need the internet?**
Only the first time you visit a place. The mod downloads that area's elevation, land cover and climate and keeps them on your computer, so later visits are instant and work offline. A note at the top of the screen tells you when the data servers can't be reached; new ground fills in once the connection is back.

**How much disk space does it use?**
It depends how much of the planet you explore. Downloaded data lives in `earthplatform/cache` inside your game folder. It is limited to 8 GB by default: the files you used least recently are removed when the game starts and as the cache grows. Change the limit in Mods → Earth Platform → Config (or `cacheLimitGb` in `config/earthplatform-common.toml`). Deleting the folder is safe: it downloads again when needed.

**Stuck on "Preparing your Earth"?**
The starting place has not finished downloading. Check your connection; the screen keeps retrying and enters the world as soon as the ground has arrived. Behind a firewall, allow `s3.amazonaws.com`, `esa-worldcover.s3.eu-central-1.amazonaws.com` and `os.zhdk.cloud.switch.ch` (plus `tiles.maps.eox.at` if you turned satellite imagery on).

## Performance

**It's slow or stutters.**
- Give the game at least 4 GB of memory, and at least 6 GB with shaders such as Photon or higher far terrain detail. With Photon, 4 GB drops the frame rate sharply.
- Lower **far terrain detail** in Mods → Earth Platform → Config → Graphics (restart to apply). The default is 11; 6 to 8 helps weaker GPUs.
- Flying fast over new ground downloads and builds terrain as you go; it settles once the area is cached.
- Sodium helps frame rate. Use 0.8.12 or a later 0.8 release, as in the [install guide](INSTALL.md#shaders-optional).

**Far terrain pops in or looks blocky.**
Distant terrain is built in levels that get coarser with distance and refine as you approach. Higher far terrain detail keeps finer levels further out at the cost of memory.

## Shaders

**Which shader packs work?**
Photon v1.3b is tested. Packs that include Voxy support light the far terrain like nearby blocks. Other packs work for nearby blocks, but far terrain may look flat.

**The game won't start after adding Iris or Sodium.**
Earth Platform needs Iris 1.8.14 or a later 1.8 release and Sodium 0.8.12 or a later 0.8 release (see the [install guide](INSTALL.md#shaders-optional)). Older builds, and Iris 1.9 or Sodium 0.9, are refused at start-up so they can't break the far terrain.

**Do I need special Photon settings on a Mac?**
No. Use the official Photon zip at its defaults. Earth Platform adapts it for Apple's OpenGL on your own computer (in `earthplatform/photon-compat/`), leaving your zip untouched. Colored Lights, Colored Shadows and Cloud Shadows are unavailable on a Mac. See [On a Mac](INSTALL.md#on-a-mac).

**My log says "Failed to parse an ResourceLocation in item.properties".**
Harmless; it comes from Photon itself.

## Other mods

**Which other mods work?**
Most do. Tested in the Earth world: Xaero's Minimap and World Map, JourneyMap, Create (contraptions, mechanical arms, pulleys, scaffolding and elevators), Waystones, Mekanism (including the Digital Miner and multiblocks), Immersive Engineering, the Twilight Forest, JEI, EMI, Jade, AppleSkin, Ars Nouveau, Applied Energistics 2 networks, Refined Storage 2, Iron's Spells, FTB Quests and Teams, Farmer's Delight, Sophisticated Backpacks, Supplementaries, YUNG's structures, and Open Parties and Claims. Tinkers' Construct and Botania have no NeoForge 1.21.1 release yet. Terralith and other biome mods have no effect on the Earth, since the real climate picks the biomes. Cobblemon, the Aether, Quark, Handcrafted, Nature's Compass and Lithium work too, and so do Create Aeronautics ships and AE2 spatial storage. CC: Tweaked (ComputerCraft) works on both the NeoForge and Fabric builds: computers, turtles, monitors, wired and wireless modems, rednet, GPS, disk drives, printers, speakers and pocket computers, at any height. Sodium and Iris need the versions in the [install guide](INSTALL.md#shaders-optional).

**Does WorldEdit work? What about Axiom or FAWE?**
On the NeoForge build, WorldEdit 7.3.8 or later 7.3 works at any height and anywhere on the Earth: selections, `//set`, `//copy` and `//paste`, `//undo`, brushes and `//setbiome`. Edits far from any player load the ground there first, and the far terrain shows them once that ground is saved, usually within a minute. `//regen` is refused in the Earth world for now. A 2 million block `//set` takes about 10 seconds. Axiom works on the Fabric build (tested with Axiom 6.1.3): its tools see the real ground at any height, and brush strokes and undo reach every player on a server. Axiom has no NeoForge release. FastAsyncWorldEdit runs only on Paper-style servers, so it can't run alongside Earth Platform.

**Can I pre-generate the Earth with Chunky?**
Yes. Pick the Earth world with `/chunky world earthplatform:cubic`, then set a center and radius as usual (`/chunky center` uses where you stand) and run `/chunky start`. Chunky generates and saves the ground in that area, from just under the surface to the treetops, so anyone arriving there later loads it from the save. A 1 km square takes about 25 seconds and 4 MB of save. It also fills the map data cache with the far terrain around the area.

**How big do Earth saves get?**
Like vanilla, the Earth saves the ground it generates, but only the surface: from one cube under the ground up to the treetops, plus anything you change at any depth. Untouched rock below and empty sky above are rebuilt when needed. That comes to about 2 to 4 MB per square kilometer explored, where a vanilla world uses about 20. Ground saved before an update keeps the terrain it was made with, as in vanilla.

**A mod acts as if blocks aren't there, or as if I'm outside the world.**
Some mods check the world's height limits themselves and don't know the Earth world is 32,768 blocks tall. Please report it with the mod's name and version; Create, Waystones, Mekanism and CC: Tweaked's turtles and wireless modems had this and are fixed.

**Mobs stand still or villagers won't trade.**
That was a bug in early test builds and is fixed. Update to the latest version.

## Known limitations

- **Ships in the jet stream:** a Create Aeronautics ship with players aboard rides the jet stream at 40 blocks a second; only an empty ship reaches elytra speed.
- **AE2's meteorite compass** finds meteorites in the Earth world by map column only, and doesn't notice blocks changed after the meteorite generated. Everything else in AE2, spatial storage included, works normally.
- **Gliding into builds above Y 9,000:** the jet stream layer streams no blocks around a gliding player, so you fly through anything built up there. Land before you reach it.
- **Boats in an ocean current** see only far terrain around them, so they pass over islands less than about 60 blocks across and through anything built out on the open sea. Slow down before you reach a build. Other players see a boat in the current about 450 blocks behind where it really is.
- **Iris and Sodium** must be Iris 1.8.14 or later 1.8, and Sodium 0.8.12 or later 0.8 (see the [install guide](INSTALL.md#shaders-optional)).
- **First visits are slow:** a place nobody has visited loads slowly the first time while its elevation is cleaned up. Later visits are quick, and work offline.
- **On a Mac**, Photon's Colored Lights, Colored Shadows and Cloud Shadows are unavailable.
- **Accuracy:** heights come from a surface model, so dense forests sit a little high, and some waterfalls may not fall where they should. Tell us about any place that looks wrong, with `/earth where`.

## Servers

**How do I run a server?**
Set `level-type=earthplatform\:earth` in `server.properties` before the world is created. See [Dedicated servers](INSTALL.md#dedicated-servers). The server needs internet access; players need the mod but not the data.

## License

**Can I put it in a modpack, or change the code?**
Yes. Earth Platform is free software under the GNU LGPL 3.0: include it in any modpack, and fork or port it as long as your changes to its code stay open under the same license. The Earth data it downloads has its own terms and credits, listed in [CREDITS.md](../CREDITS.md).

## Reporting a problem

Include your `logs/latest.log`, the place (`/earth where`), your mods list, and whether shaders were on.
