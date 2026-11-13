Earth Platform build 73: worlds load again on computers with 20 or more processor threads.

Needs Minecraft 1.21.1 and either NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 72:

### Fixes
- Builds 71 and 72 crashed while loading any world on a computer with 20 or more processor threads, such as a Ryzen 9 3900X, with `IllegalArgumentException: 11 > 8` in the log. Those worlds load again, and big machines keep their full far terrain threads: one stand-in thread per far worker, 11 on 24 threads. Computers with fewer threads are unchanged.
- If you set `-Dearthplatform.far.threads=8` to get around the crash, you can remove it.
