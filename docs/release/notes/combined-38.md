Earth Platform build 38: no more torn, slanted far terrain on Macs.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 37:
- No more torn, slanted sheets of far terrain on Macs. Apple's OpenGL doesn't implement the buffer copy the far renderer used to grow its tables, so past about 4,096 far columns every column read the wrong place and scale. The tables now grow by re-uploading from a copy kept in memory.
- No more grey walls on the Spanish Peaks in Colorado. The elevation tiles have a patch set to exactly 0 m there; the US survey data now fills such inland holes.
- The mod now has a public version number: this is 0.1.0-alpha.38, and each build's version ends in its build number.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
