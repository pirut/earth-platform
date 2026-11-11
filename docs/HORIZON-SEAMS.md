# Horizon seam preview

This follows the cloud-volume preview and targets the thin ocean grid and
polygon-shaped cloud fractures reported while zooming toward the curved horizon.
The screenshot suggests these artifacts; the isolated regressions reproduce the
underlying rendering faults, not the user's exact world/location.

## Corrections

1. Plain far water now uses a dedicated block-format shader. Every vertex first
   adds its camera-relative column offset, then uses the same radial curvature
   equation as far terrain. Adjacent columns no longer use different tangent
   planes.
2. Water's depth-fighting adjustment changes clip-space depth only. The old
   per-column distance scale shrank each column about the camera by a different
   amount, separating the edges of a continuous ocean. Positions, silhouettes,
   and shared edges are now independent of that adjustment.
3. Merged flat water faces have edges at most 512 blocks long. This keeps the
   diagonal chord error near one centimetre at full Earth curvature and scale
   one, while retaining large cells rather than building individual blocks.
   The tradeoff is more water vertices than a single merged column-wide quad.
4. Cloud sample locations no longer depend on the depth of the terrain behind
   them. Previously scene depth changed both the ray interval and the sample
   spacing, imprinting triangle/depth discontinuities into foreground clouds.
   Scene depth now clips the fixed march, including its last partial segment,
   without moving earlier samples.

Shader packs retain their existing water path. The custom plain-water shader
reloads alongside the base translucent shader and falls back with a warning if
its resources cannot load. No world-generation or save-format changes are added.

## Verification

The hidden-window OpenGL test uses the production water vertex shader to render
the old and new transforms over an ocean patch roughly 70 km away, with a
10-degree zoomed field of view. Orange background makes gaps visible. It checks
that the old path produces internal gaps and the new path closes them. The
diagnostic fragment shader uses flat blue to isolate geometry; these images are
not full in-game screenshots:

- `minecraft/build/reports/horizon/ocean-before.png`
- `minecraft/build/reports/horizon/ocean-after.png`

Additional checks load the actual water shader JSON/fragment resources through
Minecraft's ShaderInstance, bound the cell chord error, and verify that moving
opaque background geometry from 30 km to 40 km leaves foreground cloud rendering
unchanged. Existing tests retain cloud opacity bounds and near-geometry clipping.

Build verification runs in `minecraft/build-horizon-check` with two Gradle
workers. It does not start or control a Minecraft game. Final comparisons in the
user's running world and frame-time checks remain necessary. Missing downloads,
LOD shape/colour transitions, and shader-pack-specific artifacts are separate
from the reproduced seam faults.

November 9 verification: 242 tests passed, five optional benchmarks skipped,
no failures. Seven real-driver tests include the ocean comparison (666 internal
gap pixels before, zero after) and twelve cloud renders. The 30 km and 40 km
background-depth cloud PNGs have identical SHA-256 hashes.

Artifact: `minecraft/build/flight-preview/earthplatform-horizon-seam-preview.jar`.
SHA-256: `4057f9a56e21505dda7ffc29ebb198ffff0a8d89369cd86895e29f1f4e348841`.
