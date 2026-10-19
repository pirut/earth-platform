Earth Platform build 59: automatic Earth world thumbnails and mesher memory cleanup.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack. Give the game about 6 GB of memory with Photon.

New since build 58:
- Far terrain meshing creates fewer temporary objects while keeping the same textures and biome colors. No general FPS increase is claimed.
- Shore-height queries create fewer temporary objects while keeping the same terrain and water sampling. No general FPS increase is claimed.
- New Earth worlds automatically get terrain thumbnails in the world-selection menu once visible cubic terrain is ready. Existing icons are preserved; ordinary Minecraft worlds keep their normal capture behavior.
- Leaving a world releases mesher results that finish after the renderer closes, including their native water/near-mesh buffers. Queued jobs also skip unnecessary work after closure. A controlled native race reproduced retained buffers before the fix and zero retained results afterward; no general FPS gain is claimed.

Visual quality, render distance, detail and the active rendering paths are unchanged. The draw-command cache and shadow-hint experiments remain disabled. Cloud terrain-grid reuse was tested and removed after it failed average-FPS and frame-tail acceptance.

Native checks used this Mac with stock Photon and shaders off, including automatic 64×64 icon files and exact world-list texture matches. NVIDIA/AMD native hardware and a real VR headset remain untested.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
