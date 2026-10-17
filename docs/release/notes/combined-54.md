Earth Platform build 54: no more walls or holes around spawn with shaders, faster far terrain, and fixed Extreme/Ultra horizons.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 53:
- With a shader pack, right after arriving somewhere new (while "Downloading Earth data" still shows), far terrain fills in close to the player before the game's blocks load. Those close far blocks could draw as a huge dark striped wall, with stretched grass and leaves and white holes around spawn, on Mac and Windows. Build 52 made this rarer but did not fix it. Far terrain inside the game's own block range now draws like the game's blocks do, and the shader pack's distant-terrain path is used only past the edge of the game's blocks. Far terrain stays blocky and stripe-free.
- Far terrain draws faster: at Sharpest it takes about half as many draw calls as before (5,317 down to 2,798 in a test view), each far face is drawn with 4 corners instead of 6, and the check for overlapping far columns is skipped on frames where nothing changed.
- Extreme and Ultra far settings no longer show empty wedges at the corners of the view or a missing horizon. The tooltips for those settings now warn more clearly that they can drop to single-digit frame rates.

Not yet tested in a real VR headset.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
