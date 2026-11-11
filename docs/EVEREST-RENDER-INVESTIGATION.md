# Everest cube investigation

The isolated cubes reported from Everest are not confirmed fixed by the distant-blocks
preview. The earlier texture/canopy changes were insufficient for this artifact.

An opt-in offline probe now reads a **copy** of a world's `level.dat`,
`generator.properties`, relevant cached datasets, cube overlays, and optional entity
regions from `EARTH_EVEREST_REPRO`. It never opens the live world. Elevation, land cover,
climate and water sources are created with networking disabled. The Everest reproduction
uses Copernicus/Terrarium elevation; other regional elevation layers do not cover Nepal.

`EverestRenderProbeTest` records the saved camera, samples distant columns, checks local
height maxima and saved overlays, and exports height/material CSVs. Set
`EARTH_EVEREST_SCAN=1` to compare all copied edited level-4 columns with fresh generation.
Optional copied entity regions are checked for falling-block entities.

With `EARTH_GL_TESTS=1`, `EverestProbeImage` draws the real packed meshes through the
plain terrain vertex/fragment shaders in an invisible OpenGL context. The images use
constant representative material colours. They contain only a small terrain patch, with
no game scene, resource-pack textures, dynamic streaming, near cubes, cloud pass, or
Photon. Exposed patch boundaries are expected. They are diagnostics, not game screenshots.

The investigated copy yielded no local height maxima over half a voxel in the sampled
level-3/4/5 glacier columns, no changed surface heights in its five edited level-4 columns,
and no saved falling-block entities. These checks narrow the search; they do not prove
the runtime renderer is correct. The saved camera direction did not reproduce the
isolated cubes shown in the report. Exact camera position and yaw/pitch at the artifact
are the next required input. No production rendering changes were made during this
investigation, and no new fixed JAR was produced.

Run the probe with the build's normal pinned shader input paths and:

```powershell
$env:EARTH_EVEREST_REPRO = '<workspace copy of reproduction inputs>'
$env:EARTH_GL_TESTS = '1' # optional GPU images
$env:EARTH_EVEREST_SCAN = '1' # optional saved-column comparison
./build-tools/gradle-9.2.1/bin/gradle.bat --offline --no-daemon --max-workers=2 test --tests '*EverestRenderProbeTest'
```

Reproduction inputs and diagnostic outputs belong under ignored `minecraft/build/`.
Do not package or commit the world copy, entity data, caches, or local camera records.
