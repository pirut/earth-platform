Earth Platform build 64: no dark slabs after switching shaders, and a clean log when leaving a world.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 63:
- Turning a shader pack on or off right after a teleport no longer leaves huge dark slabs across the nearby ground with sky showing through it. Ground that was still being built when the pack changed came back in the old format and was never rebuilt; it is now thrown away and built again. Builds 62 and 63 had this too.
- Distant marsh no longer looks patchy, as across the Everglades: its pools and sloughs broke up into single blocks of water scattered at random over the far terrain, which flickered in shader reflections. A far block is now water only where the pools cover most of the ground it stands for. Nearby terrain is unchanged. New worlds only.
- Quitting or leaving a world no longer logs "Could not make far column" with a stack trace for every far column still being made when the world closed.

Known: dark far slabs can show in the Amazon for the first ~20 seconds.
