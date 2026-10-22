Earth Platform build 62: faster far terrain under shaders, and Replay Mod videos keep the Earth.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 61 (measured on Windows with an RTX 2080 Ti and a Ryzen 9 3900X, 1920 x 1080, Sharpest far detail, stock Photon, over Yosemite Valley):
- With shaders, far terrain hidden behind nearer far terrain is no longer shaded. Standing still went from 51.4 to 62.6 fps, and far terrain's GPU time from 13.5 to 10.4 ms. The picture is the same. Macs keep the old way, which measured faster there in build 61.
- Flying is lighter on the CPU: picking which far terrain to draw no longer looks every column up by key. Flying with Photon went from 34.6 to 36.6 fps.
- Replay Mod: rendered videos keep the Earth's terrain instead of a patch of near blocks under an empty sky, the terrain-loading caption no longer shows in replays (or for spectators), and paused replays keep loading far terrain.
- Every change has a switch to turn it off; see docs/FLAGS.md.

Known: switching shaders on or off rebuilds all far terrain, and at Sharpest the game runs at a few frames a second for about a minute while it does. Dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
