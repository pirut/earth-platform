# Distant terrain preview

The later [weather and cruise-view preview](WEATHER-PREVIEW.md) replaces the flat
cloud deck, adds an initial curved view and cruise smoothing, and adjusts water
opacity and extra distant shadows. See that document for the current defaults.

This preview builds on the flight surface-band and depth-option work. It addresses
three distinct sources of roughness: depth precision, coarse stepped geometry,
and subpixel texture patterns. A screenshot alone cannot establish their relative
contribution, and a view still downloading Earth data must also be tested after
it settles.

## Changes

- Without a shader pack, opaque far terrain resolves visibility in a separate
  32-bit floating depth buffer using an 8-block near plane. Nearby blocks are
  compared in the original game projection. After drawing, depth is converted
  back into the game target so clouds, water and later translucent draws can
  remain occluded. Shader packs keep their existing FarLod integration.
- Depth allocation or shader failures fall back to the original rendering path.
  The extra full-resolution depth target costs approximately 4 bytes per pixel,
  in addition to a copy of the game's depth. This adds a depth copy and merge
  pass; performance must be measured on the player's GPU.
- Ground using 32-block voxels or larger is smoothed into connected slopes by
  default. The four finest far levels (including all levels showing player builds) and columns masked for nearby real cubes
  retain their block geometry. Existing water, trees, and boundary-skirt paths
  remain in use. Smoothing only changes the display, not terrain or collision.
- Repeating textures fade toward their average colour when too small on screen
  to resolve, using unwrapped texture derivatives. This works even if the player
  has reduced atlas mipmapping. No geometry or opening is discarded by this filter.
- Zoom requests share the queue after an initial 32 ordinary requests: one zoom
  request, then two ordinary requests. Both orders are preserved and duplicates
  removed. Existing in-flight/request/column caps remain. Changed zoom views plan
  immediately rather than waiting for the periodic half-second plan.
- Zoom refinement uses a held column's height bounds, so nearby mountain peaks
  can sharpen even when the camera is high above a valley. Reducing refinement
  to fit its column budget retains the original view cone.
- Between 2,000 and 6,000 blocks above local terrain, far textures gradually
  soften. The no-shader path also gains gradual haze. Nearby surfaces retain
  texture detail, and shader packs retain their own atmospheric shading. The
  existing Earth cloud layers remain visible from above and below.

This is a first atmospheric presentation, not a spherical planet/space renderer
or a chunk-free planet proxy. Terrain stays visible through cloud gaps. Existing
cube streaming and collision protections remain; a high-altitude player-built
platform is not discarded because the camera is high above generated ground.

## Settings

In `config/earthplatform-client.toml`:

```toml
smoothFarTerrain = true
smoothFarFrom = 5
highAltitudeAtmosphere = true
```

Smoothing changes require a game restart. Atmosphere can change live. JVM flags
override the corresponding config values:

- `-Dearthplatform.far.smooth=false`: restore stepped distant ground.
- `-Dearthplatform.far.smoothFrom=5`: first smoothed level (3=8 m, 4=16 m, 5=32 m).
  Lower values smooth more of the scene but also approximate builds in those far levels.
- `-Dearthplatform.far.atmosphere=false`: disable the new high-altitude appearance.
- `-Dearthplatform.far.plainDepth=false`: use the original no-shader depth path.
- `-Dearthplatform.far.zoom=false`: disable view-directed refinement for comparison.

## Test in game

1. Revisit the screenshot location without shaders. Let downloads finish, hold a
   spyglass steady for 30 seconds, then pan. Compare striping, geometry, texture
   shimmer, time to sharpen, and the debug screen's `Far zoom` column counts.
2. Repeat during a cold load and a return visit. Fine terrain must replace its
   coarser fallback as it arrives, with no sky gaps. Look away and return; panning
   must not leave the rest of the world permanently starved of requests.
3. Check steep mountains, forests, coasts, and water, including boundaries between
   smooth and block geometry. Check underwater views and far water against hills.
4. Ascend from ground level to 1,000, 2,000, 4,000 and 6,000 blocks above local
   terrain; descend again. Repeat beside high peaks and on a high player build.
   Clouds must not obscure nearby peaks incorrectly or reveal empty sky below.
5. Toggle fullscreen, resize, reload resources, and switch Photon on/off. Check
   frame time and GPU memory at the same resolution and zoom after settling.

The opt-in GPU tests use an invisible OpenGL window (`EARTH_GL_TESTS=1`) to compile
the plain shader variants and check depth merging, colour preservation and state
restoration through resize. These supplement mathematical, queue and seam tests;
they do not substitute for an in-game visual or performance comparison.
