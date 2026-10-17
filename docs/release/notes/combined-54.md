Earth Platform build 54: no more walls or holes around spawn with shaders.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 53:
- With a shader pack, right after arriving somewhere new (while "Downloading Earth data" still shows), far terrain fills in close to the player before the game's blocks load. Those close far blocks could draw as a huge dark striped wall, with stretched grass and leaves and white holes around spawn, on Mac and Windows. Build 52 made this rarer but did not fix it. Far terrain inside the game's own block range now draws like the game's blocks do, and the shader pack's distant-terrain path is used only past the edge of the game's blocks. Far terrain stays blocky and stripe-free.

Not yet tested in a real VR headset.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
