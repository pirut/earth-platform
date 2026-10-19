Earth Platform build 60: cleanup after review of builds 58 and 59.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack. Give the game about 6 GB of memory with Photon.

New since build 59:
- GPU timing queries around far terrain no longer run for every player. They wrapped other mods' and shader packs' draws; they are now off unless asked for (`-Dearthplatform.far.gpuTimers=true`).
- The local performance log in `earthplatform/perf/` can be turned off with `-Dearthplatform.perfLog=false`, and no longer loses its last line when the game closes. See docs/PERFORMANCE-LOG.md.
- Four switched-off far terrain experiments are removed. Nothing drawn changes.
- The mod builds from a source download without git.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
