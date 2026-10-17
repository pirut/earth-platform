# Earth Platform explainer: animated diagrams

Six original Manim Community Edition scenes, built from vector geometry. No LaTeX, external artwork, data downloads, or third-party animation assets required. Scenes use Inter and the existing trailer palette: yellow `#FFDD00`, white, ink `#08080C`, navy `#0C1830`, blue `#2C68BE`.

## Render

Install Manim Community Edition in the production environment, plus Cairo/Pango/FFmpeg and Inter. Set `MANIM_PYTHON` when using a virtual environment.

```bash
./render.sh landscape panel   # 1920 × 1080, 30 fps
./render.sh portrait panel    # 1080 × 1920, 30 fps
./render.sh landscape overlay # alpha MOV, standalone elements over gameplay
./render.sh portrait overlay
EARTH_PREVIEW=1 ./render.sh portrait panel # half resolution, still 30 fps
```

Individual scene:

```bash
EARTH_LAYOUT=portrait EARTH_PREVIEW=1 python -m manim --renderer=cairo earth_scenes.py DataToBlocks
```

`EARTH_LAYOUT` controls geometry as well as resolution. Do not crop the landscape scene to make the portrait version. `EARTH_OVERLAY=1` must be paired with Manim's `-t --format=mov`; overlay mode removes the large navy backing and adds a dark text stroke for gameplay legibility. Set `EARTH_BACKING=panel` to retain a navy-backed overlay. The renderer uses no audio; the lead edit supplies narration/music. Do not use `-ql`/`-qh` CLI resolution presets: `EARTH_PREVIEW=1` handles previews without changing layout. Default renders are full resolution, 30 fps.

Portrait panel extends approximately x=88…979; actual content is narrower (to x≈922). Important text stays left of the rightmost 140 px and above the bottom 380 px. The navy panel itself may extend into the right UI zone; no important information does. These are diagram plates for editing over location-specific gameplay, not substitutes for gameplay evidence.

## Scenes and claims

| Scene | Content | Evidence / scope |
| --- | --- | --- |
| `DataToBlocks` | Animated input stack, height samples, block terrain | README data credits; FAQ accuracy. No assertion that source resolution equals block resolution. |
| `TrueHeight` | Cube terrain grows; player and meter unit; world Y extent | FAQ: one block is one meter around origin; current Y −16,384…16,383. Scale varies over long distances. |
| `FarTerrainLOD` | Constant-width voxel strips coarsen by level; conceptual view cone | FAQ distance-dependent detail; technical audit should verify doubling. Labels are relative voxel sizes, not physical range bounds. No assertion that all far levels propagate edits. |
| `SharedServer` | One authoritative server feeds two players; matched block | README/FAQ dedicated server; technical audit verifies authority. Illustrative data flow, not actual network trace. |
| `FairComparison` | Animated architecture comparison of Terra++, Tellus, Distant Horizons, Voxy, and Earth Platform | Architecture labels require external source verification by the production comparison audit. This is not a ranking or benchmark. |
| `HonestLimits` | Measured geography, procedural detail, no recreated real cities, data limitations | README and FAQ. Vanilla villages/structures remain possible. |

All scenes are marked conceptual. Terrain patterns are invented illustrations, not named locations or satellite samples. Caves/ores/trees/structures are procedural; source resolution varies and this is not navigation material. Text avoids describing the whole world as simultaneously loaded or promising performance.

## Editing notes

The scenes animate sequentially, retain a final two-second hold, and have no fade to black. Use transparent MOVs over gameplay or cut to navy plates. A single scene typically lasts 6–9 seconds. Extend the final hold in the edit if narration needs longer; avoid speeding up the text reveals. The comparison scene contains no invented terrain footage or FPS figures, and distinguishes terrain generation from distant rendering.

`EARTH_PREVIEW=1` produces half-size inspection assets only. Deliverable edits should use the full-resolution renders. Manim's cache/media directory is generated build output; retain source and production render manifest separately.

## Production render QA (2026-10-17)

Rendered with Manim CE 0.22.0 in `/tmp/earth-manim-venv`. The module registers Inter from `EARTH_FONT_DIR`, defaulting to `/tmp/manim-sysroot/usr/share/fonts/opentype/inter`; change that environment variable on another machine or install Inter system-wide.

All twelve production files are QuickTime Animation (`qtrle`, `argb`) alpha MOVs at 30 fps:
- Landscape: `media/landscape/overlay/videos/earth_scenes/1080p30/*.mov`
- Portrait: `media/portrait/overlay/videos/earth_scenes/1920p30/*.mov`

Final-frame contact sheets: `qa/landscape-contact.jpg`, `qa/portrait-full-contact.jpg`. `qa/render-verification.json` contains per-file FFprobe output. Background corner alpha is zero. Sheets composite transparency over navy for inspection. Initial portrait preview revealed a footer overlap and an off-frame player; both were fixed before full-resolution rendering. Landscape server block/caption spacing was also corrected and re-rendered.

Actual frame-quantized scene durations: DataToBlocks 8.033 s, TrueHeight 6.933 s, FarTerrainLOD 7.533 s, SharedServer 7.733 s, FairComparison 6.567 s, HonestLimits 6.433 s. Use production files rather than the half-resolution `960p30` preview folder.

Public architecture comparison labels were checked by the comparison research worker: Terra++ Earth generation + CubicChunks; Tellus Earth generation + experimental tall worlds; Distant Horizons column-based LOD with stacked vertical spans; Voxy 3D voxel LOD. Production must retain the source bibliography and repo audit alongside the final video.
