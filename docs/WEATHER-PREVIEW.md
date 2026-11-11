# Weather and cruise-view preview

The cloud rendering described below is superseded by the
[cloud volume preview](CLOUD-VOLUME-PREVIEW.md), which removes whole-scene
immersion fog and replaces horizontal sheets with depth-clipped 3D volumes.

This follows the distant-rendering preview and addresses the reported rectangular
mountain patch, distant-water transparency, extra shadow bands and flat clouds.
The screenshots are evidence of the visual problems, not proof of a single cause.

## Changes

- **Clouds without shaders:** replace the old two-texture flat deck and coarse
  terrain-clearance mask with three world-fixed weather bands. Default band
  centres are approximately 1,625, 3,750 and 7,500 metres above sea level. They
  have different thicknesses, coverage, winds and texture scales. Low/middle
  banks use five translucent slices each; high wisps use two. A dedicated shader
  preserves soft low-opacity edges instead of the basic shader's hard alpha cut.
  Procedural textures are tileable and mipmapped. Visibility reduces smoothly
  inside dense cloud areas, and mountains can physically intersect the layers.
  These are layered approximations, not a full 3D ray-marched weather simulation.
- **Mountain patch:** ignore near-cube masks outside the far column's vertical
  extent when deciding whether to smooth it. Empty sky cubes can no longer turn
  a whole surface column back into an isolated blocky rectangle. Near cubes that
  overlap the terrain still keep their protection against overlap and holes.
- **Cruise smoothing:** above 2,200 blocks over local terrain, allow levels 3 and
  4 to use the existing smoothed surface too. Restore ordinary detail below 1,600
  blocks or when zooming. Hysteresis prevents repeated switching at one height.
  Meshing still runs through the existing worker/upload budgets; no save data is
  modified. Changes may take time to remesh after entering/leaving cruise mode.
- **Distant water:** merged water on levels 3 and above has at least 250/255
  vertex opacity. The far seabed should not dominate its colour as if kilometres
  of sea were a transparent glass sheet. Near textured water retains its normal
  rendering. A shader pack may override water opacity and absorption itself.
- **Shader shadows:** disable the mod's extra screen-space far sun-shadow/AO and
  artificial nook shading by default. These can amplify LOD boundaries into
  bands. The shader pack's own lighting remains. This does not establish that all
  visible bands were caused by that extra pass; the comparison in game matters.
- **Curvature without shaders:** gradually bend far terrain, clouds and water
  down during high flight. Keep the closest 2,000 blocks flat to protect builds
  and near/far joins. Use a radius of 6,371 km, accounting for horizontal map
  scale. Clouds and terrain use the same radial drop; water columns use local
  tangent matrices. Expand frustum bounds conservatively so bent terrain is not
  culled using its original flat position. Collision and world coordinates stay
  unchanged. Shader packs retain flat projection until their reconstructed water
  and depth passes can share the full warp consistently.

The curvature is an Earth-scale visual approximation. The geometric horizon at
10 km altitude is about 357 km away, beyond the default roughly 94 km terrain
reach. This preview does not add a spherical planet proxy beyond that reach or
claim a complete airliner-distance horizon. The radius and horizon geometry are
based on [NASA's horizon explanation](https://cdaweb.gsfc.nasa.gov/pub/documents/archived_websites/pwg.gsfc.nasa.gov/stargaze/Shorizon.htm).

## Client settings

In `config/earthplatform-client.toml`:

```toml
layeredClouds = true
curvedHorizon = true
cruiseSmooth = true
extraFarShadows = false
cloudHeight = 2500
```

The first four can change live. Setting `layeredClouds` false uses vanilla clouds;
shader packs always keep their own cloud renderer. `cloudHeight` now scales the
three bands rather than making one deck follow the ground. Setting it to zero
uses vanilla clouds. The in-game Clouds=Off setting also disables cloud immersion.

JVM equivalents are `earthplatform.clouds.layered`, `earthplatform.far.curvature`,
`earthplatform.far.cruiseSmooth`, and `earthplatform.far.extraShadows`.

## Verification and remaining visual work

Automated checks cover periodic soft cloud density, separated bands, curvature
scale, water/terrain tangent agreement, sky-mask exclusions, cruise hysteresis,
far-water alpha, ordinary rendering regressions, and actual shader/resource
loading on an invisible OpenGL context. This does not reproduce the user's
entire running game, shader settings or exact screenshots.

Compare the same locations after downloads settle, with and without Photon.
Check the marked mountain patch, LOD rings, coastlines and deep ocean. Ascend
through all three cloud bands and into the jet stream, turn/zoom, then descend.
Check frame time, memory, resource reloads and resizing. Remaining LOD shape and
colour seams, water controlled by a pack, and the layered appearance of clouds
need that in-game assessment. All changes are visual and can use existing worlds.
