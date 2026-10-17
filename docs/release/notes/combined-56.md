Earth Platform build 56: far terrain costs less to draw.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 55:
- Far terrain takes less of the computer's time each frame. When nothing has moved, the far terrain picked for drawing is reused instead of picked again, far water skips work where there is none, and unchanged shader settings aren't sent again. On a Mac with Photon this went from 16.6 to 22.0 fps. Without shaders the picture is pixel for pixel the same.

Not yet tested in a real VR headset.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
