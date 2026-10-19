Earth Platform build 61: speed pass, same picture.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 60 (measured on an M4 Pro Mac, Sharpest far detail, Yosemite):
- Without shaders, the steady stutter while standing still is gone: the lightmap no longer stalls the GPU each tick and far fade updates are sent in batches. Standing still 40.4 to 42.9 fps, slowest 5% of frames 44 ms to 26 ms; flying 26.2 to 29.9 fps.
- With Photon, flying is smoother: 14.8 to 17.0 fps, worst 1% of frames 182 ms to 131 ms.
- Far terrain hidden behind loaded near terrain is no longer drawn.
- Distant terrain generates faster: the farthest rings take about 45% less CPU, and climate data is kept in smaller pieces so less is read back from disk. The world generated is identical.
- Shader packs other than Photon that support Voxy (for example Complementary Reimagined and TAA-Voxy) get the uniforms and textures their far-terrain code asks for, instead of failing and falling back to plain colors. Photon is still the pack we test in game.
- Every change has a switch to turn it off; see docs/FLAGS.md.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
