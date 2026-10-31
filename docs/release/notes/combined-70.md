Earth Platform build 70: far terrain does less work on your processor while you fly.

Needs Minecraft 1.21.1 and either NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. For shaders, add Iris 1.8 and Sodium 0.8 and a Voxy-capable pack such as Photon v1.3b. Give the game about 6 GB of memory with shaders.

New since build 69:
- Flying no longer makes Earth recheck every far column each frame. Before, each time the camera moved or turned even slightly, Earth worked out again which of tens of thousands of far columns were in view, and queued them all to draw. Now it picks the columns that could show from anywhere within 16 blocks of the camera, in a slightly wider view, and keeps that pick while you stay inside it. Columns that can't be seen draw nothing, so the picture is the same. On our test PC (RTX 2080 Ti, Photon, Sharpest settings, flying at the Yosemite vista) far terrain's work on the render thread dropped from about 4.1 ms to 1.6 ms a frame.
- Far lakes, rivers and sea are drawn in large batches instead of one far column at a time, in each of the water passes. Far water's work on the render thread dropped from about 1.2 ms to 0.9 ms a frame on the same test.
- On that PC the frame rate stayed the same, about 78 fps standing and flying, because the graphics card was the limit there. Computers with a slower processor, or playing without shaders, should see more of the saving.
- Both can be switched off with `-Dearthplatform.far.viewMargin=false` and `-Dearthplatform.far.batchWater=false` (see docs/FLAGS.md).
