Earth Platform build 44: far terrain looks like Minecraft all the way out.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 43:
- Far terrain is drawn as Minecraft blocks at every distance again, and every far level is now textured so each block reads as a block. The smooth far ground from build 43 is off by default (`-Dearthplatform.far.smooth=true` brings it back).
- Finer far levels still fade in as they arrive, and cliffs above the camera stay drawn.

Not yet tested on a Mac. If far terrain looks wrong on macOS, please report it.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
