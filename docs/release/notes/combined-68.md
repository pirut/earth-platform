Earth Platform build 68: block-breaking cracks show again on high ground.

Needs Minecraft 1.21.1 and either NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 67:
- The cracks on a block you're breaking show again above Y 2,047, on both the NeoForge and Fabric builds. Minecraft keeps cracks under a packed position that wraps above Y 2,047, so Earth remembers each crack's real place. It forgot that place after the first crack stage, so every later stage was drawn thousands of blocks away and you saw no cracks at all on most mountains. It now keeps the place through every stage.
