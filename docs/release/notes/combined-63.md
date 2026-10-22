Earth Platform build 63: switching shaders no longer rebuilds all far terrain, and cleaner logs.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 62:
- Switching a shader pack on or off only rebuilds far terrain with water in it, since the pack's block ids now live in one small shared table instead of in every far mesh. Over Yosemite Valley in a cloud test with Photon, about 23,000 far rebuilds over six minutes became about 4,000 over two, and the view matches starting with the pack on.
- Far terrain waiting to be built is no longer sorted in full every tick, which was costly after long trips with tens of thousands of columns waiting.
- Logs are quieter: hooks into optional mods (Create, Mekanism, Xaero's maps, JourneyMap and others) are left out when those mods aren't installed, instead of logging dozens of "Error loading class" warnings on every start.
- The guessing game is now called Where on Earth in game. The command (`/earth guess`) and its setting are unchanged.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
