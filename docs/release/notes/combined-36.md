Earth Platform build 36: shader fixes, falls from afar and a faster first arrival.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 35:
- Terrain no longer shreds with shaders on newer NeoForge (block faces missing half their triangles, slanted sheets across cubes). Near cubes and far water now draw with their own index buffers.
- Far water no longer flashes at jet-stream height.
- Far slabs no longer appear stretched or in the wrong place after flying around.
- Waterfalls such as Iguazu now show in far terrain, not only up close.
- A new world's first arrival waits only for nearby terrain: a fresh arrival at Mount Vinson went from about 116 s to 40 s.
- Far Antarctica is read from coarser REMA overviews, so it loads faster and looks slightly smoother.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
