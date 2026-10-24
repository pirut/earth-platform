Earth Platform build 64: no dark slabs after switching shaders, and a clean log when leaving a world.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 63:
- Turning a shader pack on or off right after a teleport no longer leaves huge dark slabs across the nearby ground with sky showing through it. Ground that was still being built when the pack changed came back in the old format and was never rebuilt; it is now thrown away and built again. Builds 62 and 63 had this too.
- Quitting or leaving a world no longer logs "Could not make far column" with a stack trace for every far column still being made when the world closed.
- First visits to places nobody has been fill in far terrain about four times faster. Far terrain waiting on a download now lets other far terrain be made in the meantime, and the climate data the far levels need comes in a few hundred larger reads instead of over a thousand small ones. On a four-core test machine, all the far terrain at Mount Shasta was done in 58 seconds instead of about 5 minutes (what you face in about 40 seconds instead of almost 3 minutes), and at the Matterhorn in 1 minute 44 instead of 7 minutes. The terrain is exactly the same, and places you have been before load as fast as they did.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
