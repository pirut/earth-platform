Earth Platform build 46: faster arrival, underground made only when you need it.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 45:
- On the surface, deep rock is only made near you (within 48 blocks); farther out it waits until you go underground. At Yosemite every cube arrived in 19 s instead of 36 s, and drawing them costs about 45% less. Dig down or tunnel at full speed and the rock is there. Cave openings in the ground still show real rock and passages behind them. `-Dearthplatform.cubic.buried=true` turns this off.
- Mobs, items and arrows at the edge of loaded ground now wait for it to load instead of falling out of the world.

Not yet tested on a Mac.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
