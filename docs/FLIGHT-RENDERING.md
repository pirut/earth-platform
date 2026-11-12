# Flying high and seeing far

How the game looks and loads when you fly, climb or look a long way. Most of it is experimental and off by default. The pieces were written by VanceCStone in [#161](https://github.com/pirut/earth-platform/pull/161) and landed in five parts ([#162](https://github.com/pirut/earth-platform/pull/162), [#163](https://github.com/pirut/earth-platform/pull/163), [#164](https://github.com/pirut/earth-platform/pull/164), [#165](https://github.com/pirut/earth-platform/pull/165), [#167](https://github.com/pirut/earth-platform/pull/167)).

Flags are Java system properties, given as JVM arguments such as `-Dearthplatform.cubes.reuseSnapshots=true`. Every flag and its default is in [FLAGS.md](FLAGS.md). The world's height is unchanged by any of this.

## What each part does

| Part | On by default | What it does |
| --- | --- | --- |
| Near cube frame budgets | yes | Meshing and uploading near cubes is spread over frames, so teleporting, fast flight or a shader switch should not stall a single frame. The limits follow the frame time so nearby ground is not meant to fill in more slowly; that is not yet measured in game |
| Layered clouds | no | Three cloud banks with their own wind and regional weather, in place of the single deck |
| Surface cubes for flight | no | A player flying over the ground keeps getting the real blocks of the ground and water below, not only far terrain |
| Spyglass from high up | yes | Zooming at a nearby peak from a plane or a mountain now sharpens it |
| Distant water | yes | Flat-coloured far water is nearly opaque, so the seabed no longer shows through distant oceans (plain renderer only) |
| Snapshot reuse, sealed-stone skip | no | Two near cube meshing savings |
| Smooth far ground, block textures | no | Two far terrain looks |
| Curvature, high-altitude haze | no | The far view curves away with the Earth and hazes when you fly high. See below |
| Precise far depth | only with layered clouds | Layered clouds clip cleanly against distant mountains |

## Turn the experimental parts on

**Layered clouds** (client). Mods → Earth Platform → Config → Layered clouds, or the Sky group on Sodium's Earth page, or `layeredClouds = true` in the client settings file. Quality (`cloudQuality`: Balanced, High, Cinematic) and the most the clouds may hide (`cloudOpacity`, 0.2 to 0.98, default 0.72) are next to it. They change at once. The banks sit at 0.65, 1.5 and 3 times `cloudHeight`, about Y 1,690, 3,810 and 7,560 at the default 2,500, and are never drawn under a shader pack, which keeps its own clouds. If the shader fails to load, the old deck draws instead.

**Surface cubes for flight** (server). `-Dearthplatform.cubic.flightSurface=true`. From 48 blocks over the ground or water, a flyer keeps receiving the real ground and water out to the cube view distance (192 blocks at most), and within 384 blocks in a straight line. Past that the far terrain takes over. No load test with many flying players exists yet, which is why it is off.

**Near cube savings** (client). `-Dearthplatform.cubes.reuseSnapshots=true -Dearthplatform.cubes.skipInterior=true`. Add `-Dearthplatform.cubes.checkMesh=true` to log a warning when either changes a mesh, then dig and place blocks underground.

**Far terrain looks** (client). `-Dearthplatform.far.smooth=true` blends trees into smooth ground from level 5 out (`far.smoothFrom` moves that). `-Dearthplatform.far.blockTextures=true` keeps far textures one block in size and fades them to their average colour.

**Curvature, haze and precise far depth** (client, no shader pack). `-Dearthplatform.far.curvature=true` drops far terrain, water and layered clouds away with the Earth's curve from 1,500 blocks up; by 6,000 blocks it is the real drop, about 196 m at 50 km. Only the picture curves, never the world. `-Dearthplatform.far.atmosphere=true` hazes far terrain from 2,000 to 6,000 blocks up. `far.plainDepth` (on) draws far terrain into a 32-bit depth of its own, but only while layered clouds draw, so the clouds meet distant mountains cleanly; `false` turns it off. All four do nothing under a shader pack.

## Known issues

- **Layered clouds, whiteout.** With the camera inside the middle bank, about Y 3,400 to 4,200 at the default height, the horizon is a uniform white veil.
- **Layered clouds, ribbons.** The top bank can show long straight lines when seen at a shallow angle.
- **Layered clouds, clipping.** Where a cloud meets a distant slope the cutoff can sit tens of blocks off, because the game's depth is coarse past a few kilometres. The precise far depth (`far.plainDepth`, on while layered clouds draw) is meant to fix this; it passes GPU tests but has not been checked in game. The first cloud pass after turning them on can still use the coarse depth.
- The layered cloud pass keeps about 1 MiB of textures after it is switched off, until the next resource reload.
- **Distant water under Photon.** Photon computes its own water alpha, so the seabed can still show through far water.
- **Surface cubes.** A cube held below the player that meshed before its column's tops arrived can keep pale lighting until it is next remeshed. Survey work for players on foot can delay a flyer's, and a column whose survey fails five times stays unknown until it is pruned.

## Still to check in game

- Layered clouds: the shader compiles on NeoForge and Fabric, on Mac and on Windows and Linux drivers; toggling live from the config screen and from Sodium; flying through Y 3,400 to 4,200; the top bank from low down; clouds against the Himalayas; the pass staying off under Photon; GPU cost at Balanced, High and Cinematic.
- Surface cubes: elytra flight at 60 to 300 blocks over land, then over deep ocean. Ground and water stay full blocks, with no holes or pale squares, and buried rock does not load.
- Near cubes with Photon: teleport and fast low flight show no hitch, and a redstone clock or flowing water beside you keeps updating.
- `far.blockTextures=true` has not been compiled on a GPU yet. Check that it builds, with and without Photon.
- Precise far depth: far terrain looks the same with layered clouds on or off, and stand-ins at your feet don't clip after a teleport.
- Curvature and haze: the ramps from 1,500 and 2,000 blocks up are smooth, shores stay closed, and nothing curves under Photon.

## Recommended Photon settings

Stock Photon v1.3b, unmodified. Earth Platform does not ship or change Photon, and Photon draws its own clouds and weather. The settings give its clouds Earth-scale heights and trim the cost.

The settings below were checked against the option names and allowed values in the Photon v1.3b archive (all 45 are valid and shown in its menus). They have not been looked at or timed in game, so treat them as a starting point.

1. Choose Photon, then Shader Pack Settings. Export your current settings first if you want a backup.
2. Reset Photon's settings (in the Iris version we test, hold Shift while clicking Reset), so old choices do not linger.
3. Set each option below by hand. The profile may read Custom.

These apply to every world that uses Photon until you reset them.

```
shadowMapResolution=1536
shadowDistance=96.0
SHADOW_COLOR=false
SHADOW_SSRT=false
ENTITY_SHADOWS=false
BLOCK_ENTITY_SHADOWS=false
CLOUD_SHADOWS=false
COLORED_LIGHTS=false
SHADER_AO=SHADER_AO_SSAO
VL=true
OVERWORLD_FOG_INTENSITY=0.35
ENVIRONMENT_REFLECTIONS=false
WATER_CAUSTICS=false
WATER_PARALLAX=false
WATER_DISPLACEMENT=false
POM=false
DOF=false
MOTION_BLUR=false
TAA=true
TAAU=false
CAS=true
RANDOM_WEATHER_VARIATION=true
BIOME_WEATHER_VARIATION=true
CLOUDS_SCALE=1.0
CLOUDS_TEMPORAL_UPSCALING=4
CLOUDS_CUMULUS=true
CLOUDS_ALTOCUMULUS=true
CLOUDS_CIRRUS=true
CLOUDS_CUMULUS_CONGESTUS=false
CLOUDS_NOCTILUCENT=false
CLOUDS_CUMULUS_ALTITUDE=2500.0
CLOUDS_CUMULUS_THICKNESS=0.30
CLOUDS_CUMULUS_DENSITY=0.70
CLOUDS_CUMULUS_COVERAGE=0.85
CLOUDS_CUMULUS_PRIMARY_STEPS_H=32
CLOUDS_CUMULUS_PRIMARY_STEPS_Z=16
CLOUDS_CUMULUS_LIGHTING_STEPS=4
CLOUDS_CUMULUS_AMBIENT_STEPS=1
CLOUDS_ALTOCUMULUS_ALTITUDE=5000.0
CLOUDS_ALTOCUMULUS_THICKNESS=0.10
CLOUDS_ALTOCUMULUS_DENSITY=0.10
CLOUDS_ALTOCUMULUS_COVERAGE=0.75
CLOUDS_CIRRUS_ALTITUDE=7500.0
CLOUDS_CIRRUS_DENSITY=0.25
CLOUDS_CIRRUS_COVERAGE=0.65
```

What this does: cumulus at 2,500 m, altocumulus at 5,000 m and cirrus at 7,500 m, with a thin cumulus layer (0.30 of its altitude, about 750 m) in place of a multi-kilometre bank; Photon's own random and biome weather; shadows at 1,536 resolution out to 96 blocks, with entity, colored and cloud shadows off; and no motion blur, depth of field, water parallax or caustics. Everest-sized peaks can still stand above the cloud layers. If your frame rate is poor, drop `shadowMapResolution` to 1024, `shadowDistance` to 80, `SHADER_AO` to `SHADER_AO_NONE` and `VL` to `false`, and turn `CLOUDS_ALTOCUMULUS` off.

On a Mac, Colored Lights, Colored Shadows and Cloud Shadows stay unavailable whatever you set (see [On a Mac](INSTALL.md#on-a-mac)).
