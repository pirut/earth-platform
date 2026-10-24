Earth Platform build 64: no dark slabs after switching shaders, and a clean log when leaving a world.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 63:
- Turning a shader pack on or off right after a teleport no longer leaves huge dark slabs across the nearby ground with sky showing through it. Ground that was still being built when the pack changed came back in the old format and was never rebuilt; it is now thrown away and built again. Builds 62 and 63 had this too.
- Quitting or leaving a world no longer logs "Could not make far column" with a stack trace for every far column still being made when the world closed.
- Gliding in the jet stream (above Y 9000) no longer passes through blocks players built up there. A glider now gets the builds within about a kilometer around it and hits them like any wall.
- Flying into a wall in the jet stream is now deadly, as hitting one at about 1,500 blocks a second should be: no armor or potion saves you, though a totem still can. Players in the jet stream could not be hurt at all before, and their hunger and potion timers stood still; both now work as anywhere else.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
