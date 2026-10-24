Earth Platform build 65: far terrain stays clean when you zoom in.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 64:
- Zooming in on far terrain, with a spyglass or a zoom mod, no longer makes its blocks smear into streaks, poke through each other and flicker. Far faces are drawn a hair past their edges so no sky shows through the seams, and that hair was a fixed share of the distance: half a pixel normally, but several pixels when zoomed. It is now half a pixel on screen at any zoom. The normal view looks the same, and it costs nothing in speed.
- Shallow seas no longer glow in pale patches. Sea pickles, which give off light, were scattered over every warm and lukewarm sea bed, so the bed under shallow water showed bright blotches around them. As in vanilla, they now grow only in warm seas, in a few clusters. This applies to sea beds generated from now on.
- The sea bed no longer shows pale squares after you arrive somewhere. When the height data of a 16-block column reached the game after the blocks beside it were already drawn, those blocks kept light worked out as if that column were open sky, so under water they stayed lit as if no water were above them. They are now drawn again once it arrives.
