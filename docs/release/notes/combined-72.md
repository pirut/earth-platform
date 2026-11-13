Earth Platform build 72: sharper far terrain through a zoom mod or spyglass, up to 128x, and smoother ground colour on the finest far terrain.

Needs Minecraft 1.21.1 and either NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 71:

### Looking far away
- A zoom mod's whole zoom now counts, up to 128x where it stopped at 32x before. A zoomed view also asks only for the ground that is on screen, so it sharpens about twice as fast. On an M4 Pro, zoomed to a 1 degree field of view at a ridge 56 km off with an empty data cache, the view was fully sharp in 8 to 11 seconds where build 71 took 21, asking for 4,012 far columns where it asked for 7,428. Around 10x there is a little more detail than before. `-Dearthplatform.far.zoomSteps` now defaults to 14 (128x).
- Walking or jumping while zoomed keeps the whole screen sharp. The sway of view bobbing used to pull in ground the zoomed view did not ask for. With `-Dearthplatform.far.curvature=true`, columns now count where the curve draws them.
- The finest far terrain, in 2-block voxels, now has its ground cover evened out like the coarser levels. Through a zoom it fills the screen out to tens of kilometres, and rock and snow now read as bands and fields where they were a mosaic of small squares. Unzoomed, ground just past the real blocks looks a little smoother than the blocks beside it, most visibly from the air.
- Blocks that are not natural ground keep their own material on that finest terrain, so a wool marker or a plank path still shows. A pattern made of natural blocks, such as a gravel path on grass, is evened with the ground around it. Coarser levels are evened as before. `-Dearthplatform.far.coverFrom=2` gives the old look back.
- Not tested: the zoom changes have only been run in the plain renderer on macOS. They have not been tried with shader packs, on Fabric or on a multiplayer server.
