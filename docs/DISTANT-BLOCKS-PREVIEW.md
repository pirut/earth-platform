# Distant material and canopy preview

This preview includes the mountain-weather preview and changes the distant renderer itself.
It applies with and without shader packs.

* Voxel textures keep the actual one-block Minecraft scale instead of expanding with each
  coarser LOD cell. The existing derivative filter blends subpixel patterns into their mean
  colour. Spyglass magnification can resolve the real-scale pattern again.
* Ground lighting uses the projected size of a real block, rather than the size of a coarse
  proxy voxel, to soften the alternating bright treads and dark risers on distant slopes.
* Smooth levels include the forest canopy in the surface's height, colour and material.
  They no longer draw separate 32–256 block wide leaf/log boxes above that surface.
* Levels 1–4 and interactable blocks keep their voxel geometry. This does not turn nearby
  Minecraft terrain into a smooth heightfield, increase the spyglass streaming budget,
  alter world generation or discard player edits.

Test artifact: `minecraft/build/flight-preview/earthplatform-distant-blocks-preview.jar`.
Replace the prior Earth Platform JAR and restart the client. No shader settings change is
required. Compare the same view with the spyglass held still after Earth data finishes loading.

Validation includes a real-mesher canopy regression at levels 4, 5 and 8, the full automated
suite, plain shader variants compiled on the GPU, and the pinned Photon shader compatibility
checks. The canopy regression checks that detailed scenery remains block geometry, smooth
scenery contains no separate canopy boxes, and canopy heights/material IDs remain present.
These checks do not identify the block material in the supplied screenshots or prove that
every isolated box shown there has the same cause. No full-game visual or FPS test was run.
