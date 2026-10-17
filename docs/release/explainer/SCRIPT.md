# Earth Platform explainer — locked editorial script

Two text-and-music edits. No narration, voice generation, spoken inserts, or simulated dialogue. Exact durations: short **00:54**, full **05:00**. Timeline is machine-readable in `timeline.json`; this document is the source ledger and publish copy.

## Evidence and render boundary

Implementation evidence: `CLAIM_AUDIT.md`, pinned to Earth Platform commit `5b2b62b745c9580f64ca3d3c95bc631f3570c683`. Comparison research was checked October 17, 2026. Source review does not verify builds, performance, mod compatibility, real-world accuracy, actual multiplayer behavior or final render quality. File selection and timing here are edit decisions; final image inspection, dimensions, duration, readability, music rights and audio QC must be recorded separately by production.

Source prefix expansion: C/ = minecraft/src/main/java/io/earthplatform/minecraft/cubic/; G/ = geography/src/main/java/io/earthplatform/geography/globe/. Repository link root: https://github.com/pirut/earth-cubic-platform/blob/5b2b62b745c9580f64ca3d3c95bc631f3570c683/ . Append the expanded file and #L anchors for public citations.

## Edit contract

- Short: portrait. Cold-open hook is visible on the first frame, with gameplay already moving. Three numbered points, one respectful comparison line, then “Where should I go next?” Use the same Everest source at beginning/end; choose contiguous wrap-compatible frames or an 8-frame matched dissolve wholly inside the existing 54 seconds. Do not add black or end padding. No guarantee that the source is already a seamless loop.
- Full: landscape 16:9 canvas. **162 seconds gameplay / 138 seconds diagram = 54% / 46%.** Every chapter starts and ends with gameplay. Diagram segments keep gameplay visible behind or adjacent to the plate; they do not imply the invented vectors are captured terrain. This 54% calculation counts foreground gameplay only, not the backing beneath diagrams.
- The full cut now mixes verified 1920×1080 landscape trailer windows with 1008×1792 portrait ambient footage. Diagrams use purposeful left-side gameplay / right-side diagram layouts. Portrait-only gameplay beats use a designed gameplay panel; never stretch them. The four ambient files are 40 seconds each. Landscape windows were visually cleared by production; this script verifies their time bounds, not independent image content.
- The clip field always names gameplay, including diagram backing. source_in is the exact seek offset; source_duration is the exact source window length. playback_rate = source_duration / duration; apply setpts = PTS / playback_rate to the trimmed source. Trim before retiming, map that window to duration, and never stream-loop. scene identifies the Manim class. Adjacent subshots sharing caption_group retain one uninterrupted caption; do not reanimate/reflash text at each scenery cut. Omit original trailer audio entirely. Short landscape excerpts use mild 0.67× slow motion when necessary; original 3-second windows play native speed.
- Diagram animation runs near native speed (minimum 0.85×). Longer segments extend with gentle diagram camera movement/highlight motion, never a static frame hold longer than 3 seconds. Do not restart diagram headings every few seconds. Avoid overlay text colliding with intrinsic labels; use side gutters or alternate headline cards, while preserving the claim caveats.
- Short caption safe area: minimum 65 px left inset, maximum 835 px text width. The opening question uses two deliberate headline lines.
- Mobile captions: one headline plus normally two short lines. Never shrink a long sentence to fit. Render practical minimum 40 px body at 1080 px output width, high contrast, safe from right-side controls and lower caption UI. Final disclaimer is held 6 seconds in the short (31 px minimum body) and 12 seconds in the full, with its three body lines fully readable.
- No real city/town labels or footage. Natural landscape names are allowed. Natural valley footage must exclude visible settlements; never caption the town name. No other project's screenshots or fake comparative FPS/terrain captures.
- Server's two-client animation is expressly conceptual. No actual two-player capture is available. Nearby gameplay is single-session B-roll and must not be edited to imply two simultaneous clients.
- Use original or properly licensed instrumental music only. Instrumental beat changes may mark chapters; no spoken vocal samples. Keep continuity through the short loop. Rights and rendered loudness are production checks, not certified by this script.

## Short titles (choose one)

1. Earth in Minecraft: Data, Cubes, and Huge Views
2. How Earth Becomes a Minecraft World
3. Real Geography, Minecraft Blocks

## Short description

Earth data becomes a playable Minecraft landscape: independent cubes nearby, coarser voxels farther away. Nominal meter-scale terrain near the world origin; projected horizontal scale varies. A short look at Earth Platform, with respect to Terra++, Tellus, Distant Horizons and Voxy.

Alpha. Coming soon. Where should I go next?

No recreated real cities, roads or buildings. Data can be incomplete; individual trees and other fine details are generated. This is not a benchmark or a navigation product.

Source: https://github.com/pirut/earth-cubic-platform

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

#Minecraft #EarthPlatform #WorldGeneration #VoxelTerrain

## Full titles (choose one)

1. How Earth Platform Turns Earth Data into Minecraft
2. Earth in Minecraft: Cubes, Terrain Data, and Distant Voxels
3. Building a Planet-Sized Minecraft World: How It Works

## Full description

A five-minute, text-and-music look at Earth Platform: elevation and ecological inputs, true-height cubes, distant voxel levels, server-owned terrain, and the tradeoffs behind an Earth-scale Minecraft world.

Terra++ already brought nominal 1:1 Earth terrain to Minecraft. Tellus supports configurable scale and experimental tall worlds with an adapted Distant Horizons build. Voxy is an important example of 3D voxel LOD. This is a respectful architecture comparison, not a speed ranking.

Alpha. Coming soon. No recreated real cities, roads or buildings. Source resolution varies, first visits can require downloads, and generated detail is an interpretation of measured geography. The server diagrams are conceptual, not recorded two-player sessions. Horizontal ground scale varies with map projection. No benchmark is claimed.

Chapters:
00:00 Earth in Minecraft
00:12 Earth data
01:18 Room for height
02:06 Distant terrain
03:06 A shared world
03:42 Other approaches
04:30 Honest limits

Source and project: https://github.com/pirut/earth-cubic-platform
Terra++: https://github.com/BuildTheEarth/terraplusplus
Tellus: https://github.com/Yucareux/Tellus
Distant Horizons: https://gitlab.com/distant-horizons-team/distant-horizons-core
Voxy: https://github.com/MCRcortex/voxy

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

#Minecraft #EarthPlatform #WorldGeneration #VoxelTerrain #MinecraftMods

## Exact on-screen timeline

Text after the headline is shown as separate short lines, not a dense paragraph. Evidence IDs apply to every factual phrase in that segment; neutral chapter labels and calls to action are editorial. Diagram-internal claims are covered by the scene ledger below.

### Short cut

- **00:00–00:04** [gameplay; Hook]: **HOW DOES EARTH FIT / IN MINECRAFT?** / Real elevation. Generated detail.. Evidence: SCALE, DATA.
  - Source: `docs/release/trailer/hooks/clips/b47-q-everest.mp4`, exact source 0–4s → 4s output.
- **00:04–00:08** [gameplay; Data]: **1. START WITH EARTH** / Elevation becomes blocks.. Evidence: DATA.
  - Source: `docs/release/trailer/hooks/clips/b47-c-half_dome.mp4`, exact source 0–4s → 4s output.
- **00:08–00:16** [diagram; Data]: **MEASURED + GENERATED** / Earth data guides the landscape. / Individual trees are procedural.. Evidence: DATA, LIFE.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 0–8s → 8s output; plate: `DataToBlocks`.
- **00:16–00:22** [gameplay; Cubes]: **2. KEEP THE HEIGHT** / 32,768 block layers.. Evidence: CUBES.
  - Source: `docs/release/trailer/hooks/clips/b48-scale.mp4`, exact source 0–6s → 6s output.
- **00:22–00:30** [diagram; Cubes]: **INDEPENDENT 16³ CUBES** / Y −16,384 to +16,383 / Near origin: 1 m / block (3.28 ft). Evidence: CUBES, SCALE.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 10–18s → 8s output; plate: `TrueHeight`.
- **00:30–00:35** [gameplay; Far terrain]: **3. LOOK FARTHER** / Coarser voxels with distance.. Evidence: FAR.
  - Source: `docs/release/trailer/hooks/clips/b47-c-grand_canyon.mp4`, exact source 0–5s → 5s output.
- **00:35–00:43** [diagram; Far terrain]: **A WORLD IN LEVELS** / About 94,000 blocks by default. / Configurable to about 201,000.. Evidence: FAR.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 20–28s → 8s output; plate: `FarTerrainLOD`.
- **00:43–00:48** [gameplay; Comparison]: **OUR CUBES + EARTH DATA / + OUR FAR RENDERER** / Terra++, Tellus, DH, Voxy: different layers.. Evidence: TERRA, TELLUS, DH, VOXY, CUBES, DATA, FAR.
  - Source: `docs/release/trailer/hooks/clips/b48-g-waimea.mp4`, exact source 0–5s → 5s output.
- **00:48–00:54** [gameplay; Outro]: **COMING SOON / Where should I go next?** / NOT AN OFFICIAL MINECRAFT PRODUCT. / NOT APPROVED BY OR ASSOCIATED WITH / MOJANG OR MICROSOFT.. Evidence: EDITORIAL.
  - Source: `docs/release/trailer/hooks/clips/b47-q-everest.mp4`, exact source 0–5s → 6s output.

### Full cut

- **00:00–00:03** [gameplay; Intro]: **EARTH, IN MINECRAFT** / Measured terrain. A block world.. Evidence: DATA.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 10–13s → 3s output; shot: Everest.
- **00:03–00:06** [gameplay; Intro]: **EARTH, IN MINECRAFT** / Measured terrain. A block world.. Evidence: DATA.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 46–49s → 3s output; shot: Yosemite.
- **00:06–00:09** [gameplay; Intro]: **HOW DOES IT WORK?** / Earth data → cubes → distant voxels. Evidence: DATA, CUBES, FAR.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 14–16s → 3s output; shot: Matterhorn.
- **00:09–00:12** [gameplay; Intro]: **HOW DOES IT WORK?** / Earth data → cubes → distant voxels. Evidence: DATA, CUBES, FAR.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 17.5–19.5s → 3s output; shot: Grand Canyon.
- **00:12–00:15** [gameplay; Data]: **01 / EARTH DATA** / Start with elevation.. Evidence: DATA.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 6.7–8.7s → 3s output; shot: Fuji.
- **00:15–00:18** [gameplay; Data]: **01 / EARTH DATA** / Start with elevation.. Evidence: DATA.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 24–26s → 3s output; shot: Geiranger.
- **00:18–00:28** [diagram; Data]: **TERRARIUM: CLEAN ELEVATION** / Repair zeros, seams, isolated pixels. / Regional layers come next.. Evidence: DATA, CLEAN.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 0–10s → 10s output; plate: `DataToBlocks`.
- **00:28–00:38** [gameplay; Data]: **REGIONAL ELEVATION SOURCES** / Copernicus GLO-30 fill / USGS 3DEP: U.S. · REMA: Antarctica. Evidence: DATA, REGIONAL.
  - Source: `docs/release/trailer/ambient/clips/b47-yosemite.mp4`, exact source 26–36s → 10s output.
- **00:38–00:48** [diagram; Data]: **ORDERED LAYERS, SAVED PER WORLD** / Later regional overlays can win. / Final despike follows the overlays.. Evidence: PIN, CLEAN.
  - Source: `docs/release/trailer/ambient/clips/b47-yosemite.mp4`, exact source 0–10s → 10s output; plate: `DataToBlocks`.
- **00:48–00:58** [gameplay; Data]: **MORE THAN HEIGHT** / CHELSA climate + ecoregions / Land cover helps guide vegetation.. Evidence: LIFE.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 28–38s → 10s output.
- **00:58–01:08** [diagram; Data]: **RIVERS FROM OBSERVATION + TERRAIN** / JRC Global Surface Water / plus terrain-derived streams. Evidence: LIFE, JRC.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 0–10s → 10s output; plate: `DataToBlocks`.
- **01:08–01:18** [gameplay; Data]: **DATA GUIDES PROCEDURAL DETAIL** / Trees are generated individually. / Climate is long-term, not live weather.. Evidence: LIFE.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 28–38s → 10s output.
- **01:18–01:21** [gameplay; Cubes]: **02 / ROOM FOR HEIGHT** / Everest: 8,849 m / 29,032 ft / above sea level.. Evidence: EVEREST.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 10–13s → 3s output; shot: Everest.
- **01:21–01:24** [gameplay; Cubes]: **02 / ROOM FOR HEIGHT** / Everest: 8,849 m / 29,032 ft / above sea level.. Evidence: EVEREST.
  - Source: `docs/release/trailer/hooks/clips/b47-q-everest.mp4`, exact source 1–4s → 3s output; shot: everest.
- **01:24–01:36** [diagram; Cubes]: **WORLD HEIGHT, COMPARED** / Vanilla: 384 layers (Y −64…319) / Upper boundary: Y 320 / Earth Platform: 32,768 layers. Evidence: VANILLA, CUBES.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 0–12s → 12s output; plate: `TrueHeight`.
- **01:36–01:48** [gameplay; Cubes]: **BLOCKS AT THEIR TRUE HEIGHT** / Independent 16 × 16 × 16 cubes / load around each player.. Evidence: CUBES.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 26–38s → 12s output.
- **01:48–02:00** [diagram; Cubes]: **SCALE NEEDS A CAVEAT** / Nominal 1 m / block near origin / 1 m ≈ 3.28 ft; map scale varies.. Evidence: SCALE.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 10–22s → 12s output; plate: `TrueHeight`.
- **02:00–02:06** [gameplay; Cubes]: **A BOUNDED NEIGHBORHOOD** / The whole height range / is not loaded all at once.. Evidence: CUBES.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 2–8s → 6s output.
- **02:06–02:09** [gameplay; Far terrain]: **03 / DISTANT TERRAIN** / Near blocks. Farther voxels.. Evidence: FAR.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 17.5–19.5s → 3s output; shot: Grand Canyon.
- **02:09–02:12** [gameplay; Far terrain]: **03 / DISTANT TERRAIN** / Near blocks. Farther voxels.. Evidence: FAR.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 24–26s → 3s output; shot: Geiranger.
- **02:12–02:22** [diagram; Far terrain]: **DOUBLE THE VOXEL SIZE** / 2, 4, 8 … 256 blocks per voxel / Eight far-detail levels.. Evidence: FAR.
  - Source: `docs/release/trailer/ambient/clips/b47-yosemite.mp4`, exact source 10–20s → 10s output; plate: `FarTerrainLOD`.
- **02:22–02:30** [gameplay; Far terrain]: **NESTED DISTANCE RINGS** / Finer levels replace the center. / Matching faces merge.. Evidence: FAR, MESH.
  - Source: `docs/release/trailer/ambient/clips/b47-yosemite.mp4`, exact source 2–10s → 8s output.
- **02:30–02:40** [diagram; Far terrain]: **HOW FAR?** / 94,208 blocks by default / Up to 200,704 blocks: configured maximum. Evidence: REACH.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 10–20s → 10s output; plate: `FarTerrainLOD`.
- **02:40–02:48** [gameplay; Far terrain]: **BLOCK DISTANCE ≠ MAP DISTANCE** / Nominally 94.2–200.7 km / 58.5–124.7 mi / Ground distance varies with projection.. Evidence: REACH, SCALE.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 2–10s → 8s output.
- **02:48–02:58** [diagram; Far terrain]: **WHAT ABOUT YOUR BUILDS?** / Saved edits enter levels 1–4. / Small details can disappear.. Evidence: EDITS.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 12–22s → 10s output; plate: `FarTerrainLOD`.
- **02:58–03:06** [gameplay; Far terrain]: **HEIGHTS, NOT COARSE STEPS** / Far surfaces align to generated ground. / That does not mean survey accuracy.. Evidence: MESH.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 2–10s → 8s output.
- **03:06–03:07.5** [gameplay; Server]: **04 / A SHARED WORLD** / The server generates shared terrain.. Evidence: SERVER.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 36–37s → 1.5s output; shot: Rainier.
- **03:07.5–03:09** [gameplay; Server]: **04 / A SHARED WORLD** / The server generates shared terrain.. Evidence: SERVER.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 38–39s → 1.5s output; shot: Lake Louise.
- **03:09–03:12** [gameplay; Server]: **04 / A SHARED WORLD** / The server generates shared terrain.. Evidence: SERVER.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 46–49s → 3s output; shot: Yosemite.
- **03:12–03:21** [diagram; Server]: **SERVER → TWO CLIENTS** / Near cubes + far terrain stream out. / Conceptual; no multiplayer capture.. Evidence: SERVER.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 22–31s → 9s output; plate: `SharedServer`.
- **03:21–03:27** [gameplay; Server]: **SHARED BLOCK CHANGES** / The server sends changes / to watching players.. Evidence: SERVER.
  - Source: `docs/release/trailer/ambient/clips/b47-yosemite.mp4`, exact source 32–38s → 6s output.
- **03:27–03:36** [diagram; Server]: **THE MAP HAS ITS OWN DATA** / Client globe tiles can download data. / Its cache is local to that installation.. Evidence: CACHE.
  - Source: `docs/release/trailer/ambient/clips/b47-yosemite.mp4`, exact source 20–29s → 9s output; plate: `SharedServer`.
- **03:36–03:42** [gameplay; Server]: **SEPARATE MACHINES, SEPARATE CACHES** / A server cache is not / automatically a client cache.. Evidence: CACHE.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 32–38s → 6s output.
- **03:42–03:45** [gameplay; Comparison]: **05 / OTHER GOOD APPROACHES** / Different projects. Different choices.. Evidence: TERRA, TELLUS, DH, VOXY.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 6.7–8.7s → 3s output; shot: Fuji.
- **03:45–03:48** [gameplay; Comparison]: **05 / OTHER GOOD APPROACHES** / Different projects. Different choices.. Evidence: TERRA, TELLUS, DH, VOXY.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 14–16s → 3s output; shot: Matterhorn.
- **03:48–04:00** [diagram; Comparison]: **EARTH GENERATION** / Terra++: 1:1 + CubicChunks / Tellus: configurable, tall worlds. Evidence: TERRA, TELLUS.
  - Source: `docs/release/trailer/ambient/clips/b47-rainier.mp4`, exact source 20–32s → 12s output; plate: `FairComparison`.
- **04:00–04:12** [gameplay; Comparison]: **DISTANT HORIZONS** / Stock 3.3.4 uses 12-bit Y fields. / Tellus supplies a tall-world fork.. Evidence: DH, TELLUS.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 14–26s → 12s output.
- **04:12–04:24** [diagram; Comparison]: **TWO VALID LOD APPROACHES** / DH: columns with stacked spans / Voxy: 3D voxel hierarchy. Evidence: DH, VOXY.
  - Source: `docs/release/trailer/ambient/clips/b47-matterhorn.mp4`, exact source 22–34s → 12s output; plate: `FairComparison`.
- **04:24–04:27** [gameplay; Comparison]: **OUR CHOICE: CUBES + EARTH DATA** / Voxy-inspired far renderer. / Project credits: no copied code.. Evidence: CUBES, DATA, FAR.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 46–49s → 3s output; shot: Yosemite.
- **04:27–04:30** [gameplay; Comparison]: **OUR CHOICE: CUBES + EARTH DATA** / Voxy-inspired far renderer. / Project credits: no copied code.. Evidence: CUBES, DATA, FAR.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 10–13s → 3s output; shot: Everest.
- **04:30–04:31.5** [gameplay; Limits]: **06 / STILL AN ALPHA** / No recreated real cities or roads. / Real buildings are not reproduced.. Evidence: LIMITS.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 29.5–30.5s → 1.5s output; shot: Uluru.
- **04:31.5–04:33** [gameplay; Limits]: **06 / STILL AN ALPHA** / No recreated real cities or roads. / Real buildings are not reproduced.. Evidence: LIMITS.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 27–28s → 1.5s output; shot: Dunes.
- **04:33–04:36** [gameplay; Limits]: **06 / STILL AN ALPHA** / No recreated real cities or roads. / Real buildings are not reproduced.. Evidence: LIMITS.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 17.5–19.5s → 3s output; shot: Grand Canyon.
- **04:36–04:48** [diagram; Limits]: **WHAT TO EXPECT** / Data can be missing or imperfect. / First visits may need downloads.. Evidence: LIMITS, CACHE.
  - Source: `docs/release/trailer/ambient/clips/b47-fuji.mp4`, exact source 8–20s → 12s output; plate: `HonestLimits`.
- **04:48–04:51** [gameplay; Limits]: **EARTH PLATFORM · COMING SOON** / NOT AN OFFICIAL MINECRAFT PRODUCT. / NOT APPROVED BY OR ASSOCIATED WITH / MOJANG OR MICROSOFT.. Evidence: EDITORIAL.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 10–13s → 3s output; shot: Everest.
- **04:51–04:54** [gameplay; Limits]: **EARTH PLATFORM · COMING SOON** / NOT AN OFFICIAL MINECRAFT PRODUCT. / NOT APPROVED BY OR ASSOCIATED WITH / MOJANG OR MICROSOFT.. Evidence: EDITORIAL.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 6.7–8.7s → 3s output; shot: Fuji.
- **04:54–04:57** [gameplay; Limits]: **EARTH PLATFORM · COMING SOON** / NOT AN OFFICIAL MINECRAFT PRODUCT. / NOT APPROVED BY OR ASSOCIATED WITH / MOJANG OR MICROSOFT.. Evidence: EDITORIAL.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 14–16s → 3s output; shot: Matterhorn.
- **04:57–05:00** [gameplay; Limits]: **EARTH PLATFORM · COMING SOON** / NOT AN OFFICIAL MINECRAFT PRODUCT. / NOT APPROVED BY OR ASSOCIATED WITH / MOJANG OR MICROSOFT.. Evidence: EDITORIAL.
  - Source: `docs/release/videos/earth-platform-trailer-out-now.mp4`, exact source 46–49s → 3s output; shot: Yosemite.

## Diagram-internal claim ledger

The source code for all six plates is `manim/earth_scenes.py`. Heading numbers and invented shapes are design, not measurements. “Conceptual” footer remains visible.

- DataToBlocks: measured inputs, elevation/land cover/climate/water, height samples and block terrain → DATA, LIFE. The drawn sample grid is invented, not downloaded map data.
- TrueHeight: block scale near origin and world Y extent → SCALE, CUBES. Qualify any “1 BLOCK = 1 METER” intrinsic caption as nominal near origin; do not leave that phrase globally unqualified. US units: 1 m ≈ 3.28 ft. Invented mountain/player objects are illustrative.
- FarTerrainLOD: near blocks, farther coarse voxels, voxel-size doubling → FAR. The view cone is conceptual; actual selection is nested distance rings, not explicit screen-space-error optimization.
- SharedServer: server authority, shared terrain and changes → SERVER. Player A/B are conceptual endpoints. It is not a network trace or multiplayer capture. Client map download caveat → CACHE.
- FairComparison: Terra++ Earth generation + CubicChunks → TERRA; Tellus experimental tall worlds → TELLUS; Distant Horizons columns/stacked spans → DH; Voxy voxel hierarchy → VOXY; Earth Platform cubes/data/far renderer → CUBES, DATA, FAR. No ranking, derivation or copied-code claim.
- HonestLimits: measured geography interpreted as Minecraft, procedural trees/structures, absence of recreated cities/roads/buildings and variable source resolution → LIFE, LIMITS. References to caves/ores/structures are maintainer documentation (`docs/FAQ.md:11–15,90`), not tested here. Vanilla villages may still generate; exclude them from this scenery edit to avoid confusing the no-real-cities point.

## Source ledger

### DATA

Source: C/CubicSettings.java:185–202,216–238; G/TerrariumTileSource.java:91–94,122–127

Scope: Elevation data and ordered regional overlays produce terrain. Inputs are not all equally resolved or globally available.

### CLEAN

Source: G/TerrariumTileSource.java:298–304,91–94,122–127

Scope: Stray-zero repair, seam treatment, mend and reference repair precede overlays; a final high-zoom despike follows overlays. The short caption does not assert all cleanup comes first.

### PIN

Source: C/CubicSettings.java:92–120,148–170,185–202

Scope: Ordered dataset IDs and projection/source settings are saved, not cryptographic snapshots of every external tile.

### LIFE

Source: G/ClimateSource.java:21–38,53–58,95–125; C/earth/Ecoregions.java:15–27; C/earth/EarthGenerator.java:2084–2104,2936–2956,3112–3154; G/RiverMap.java:12–35,196–199; C/gen/Trees.java:157–179

Scope: CHELSA 1981–2010 climatology; observed-water plus inferred drainage; deterministic procedural trees. No live weather or one-to-one tree census claim.

### CUBES

Source: C/Cube.java:10–32; C/CubeWorld.java:35,50–69; C/mixin/CubicLevelMixin.java:49–88; C/ServerCubeWorld.java:383–384,408–430

Scope: 16³ independent cubes, current inclusive Y −16,384…16,383, bounded player neighborhood. Inclusive arithmetic gives 32,768 layers.

### SCALE

Source: docs/FAQ.md:5–6; C/CubicSettings.java:148–170

Scope: Nominal meter/block scale applies near the home latitude/origin; projected horizontal scale changes away from it. 1 meter ≈ 3.28084 ft is unit arithmetic, not a precision claim.

### FAR

Source: C/far/Far.java:25–34,64–68,105–130; C/client/FarRenderer.java:467–482,923–938

Scope: Eight far levels, voxel edge 2…256 blocks; nested distance rings, not an explicit projected screen-space-error selector.

### REACH

Source: C/far/Far.java:25–34,64–68

Scope: Nearest-edge reach: default 23 × 4096 = 94,208 blocks; maximum startup setting 49 × 4096 = 200,704. Not a spherical radius or benchmark. At nominal 1 m/block, about 94–201 km / 59–125 mi, with projection caveat on the same screen.

### EDITS

Source: C/far/Far.java:57–58; C/far/FarServer.java:161–167,182–188,196–217,248–269

Scope: Only levels 1–4 incorporate saved cubes; downsampling loses fine detail. Air/water and hidden underground edits have limitations.

### MESH

Source: C/client/FarMesher.java:507–557,621–675,52; C/gen/SurfaceGenerator.java:344–353

Scope: Matching flat pieces merge. Surface drops align generated ground/water, not survey truth; stored precision is finite.

### SERVER

Source: C/ServerCubeWorld.java:571–592,651–652; C/far/FarServer.java:178–191; C/client/FarRenderer.java:331

Scope: Near and far server delivery plus block-change broadcasts. Two-client plate is conceptual, never evidence of a recorded multiplayer session.

### CACHE

Source: C/play/client/GlobeImagery.java:151–175; C/CubicSettings.java:208–238,348,357–365,416–426

Scope: Client map can fetch elevation/cover. Caches belong to each installation; offline success depends on cached data.

### LIMITS

Source: docs/FAQ.md:11–15,35,88,90; G/TerrariumTileSource.java:1038–1051

Scope: No recreated real cities/roads/buildings; vanilla structures can appear. Data resolution and source failures remain limitations. Alpha status is release editorial context supplied for this task, not a tested runtime property.

### TERRA

Source: https://github.com/BuildTheEarth/terraplusplus/blob/b6b49d2737216b0c8dd787516f1fc82fb7cd1c20/README.md ; https://github.com/BuildTheEarth/terraplusplus/blob/b6b49d2737216b0c8dd787516f1fc82fb7cd1c20/build.gradle#L21-L58

Scope: Terra++ already delivers nominal 1:1 terrain with CubicChunks. Its official checked branch targets 1.12.2; no height-compression or first-ever accusation.

### TELLUS

Source: https://github.com/Yucareux/Tellus/blob/8c19ed8e8a06f7c0d34bf0dbb661cd22ed5b8339/README.md#L44-L77 ; https://github.com/Yucareux/Tellus/releases/tag/v0.8.0 ; https://github.com/Yucareux/Tellus/releases/tag/v0.8.3

Scope: Configurable scale, experimental tall worlds, adapted Distant Horizons. Do not say current Tellus is flatter or limited to vanilla height.

### DH

Source: https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/64410621767ee697e8bc175c2a3ebe4c5e2ce520/core/src/main/java/com/seibel/distanthorizons/core/util/FullDataPointUtil.java#L21-94 ; https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/d7a62ce4a416c2fd8b25cbc60573850333017815/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/transformers/LodDataBuilder.java#L128-263

Scope: Stock 3.3.4 has 12-bit relative vertical fields: 4,096 possible values, not an absolute Y=4096 ceiling. Columns contain stacked spans and can represent multiple surfaces. Forks differ.

### VOXY

Source: https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/java/me/cortex/voxy/common/world/WorldSection.java#L12-L76 ; https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/java/me/cortex/voxy/common/voxelization/VoxelizedSection.java#L17-L49

Scope: Verified 3D voxel hierarchy. Architectural similarity does not establish copied code, derivation, dependence, or superior speed.

### EVEREST

Source: https://www.tourismdepartment.gov.np/files/statistics/46.pdf (printed p. 2)

Scope: 8,848.86 meters above sea level rounds to 8,849 m / 29,032 ft. Not base-to-summit rise, nor a measurement of the captured summit.

### EDITORIAL

Source: User production direction; non-technical release copy

Scope: Coming soon is an editorial call to action, not a promised date. Branding, chapter numbering, questions and affiliation disclaimer are not implementation-test claims.

### REGIONAL

Source: C/CubicSettings.java:185–202,216–238,248–274; README.md:42–44; CREDITS.md:31–63.

Scope: Copernicus DEM GLO-30 provides fill/refinement where the base elevation needs it; USGS 3DEP covers the United States; REMA covers Antarctica. These are examples, not the entire layer list. Default saved order is Terrarium → Copernicus fill → Canada HRDEM → US 3DEP → Antarctica REMA → France IGN → Japan GSI. National sources are optional; later overlapping layers win. Resolution and coverage vary. Regional source names are on screen at 00:28–00:38.

### JRC

Source: README.md:50; CREDITS.md:124–132; G/RiverMap.java:12–35,196–199.

Scope: The documented observation input is JRC Global Surface Water v1.4 (EC JRC/Google), supplemented with terrain-derived streams. Gap closing/inference and source resolution mean these are not guaranteed hydrological ground truth. JRC is named on screen at 00:58–01:08. CHELSA is named at 00:48–00:58; its climate inputs are 1981–2010 climatologies, not live weather (G/ClimateSource.java:21–38,53–58).

### VANILLA

Source: https://feedback.minecraft.net/hc/en-us/articles/4415128577293-Minecraft-Java-Edition-1-18 ; https://feedback.minecraft.net/hc/en-us/articles/4414284658701-Minecraft-Caves-Cliffs-Part-II-1-18-0-Bedrock . Primary official release sources verified in the comparison research.

Scope: The default modern Overworld has 384 block layers, Y −64 through 319 inclusive; Y 320 is the upper boundary, not the number of layers or the highest occupied block coordinate. Other dimensions/custom worlds can differ. The comparison at 01:24–01:36 is against Earth Platform's 32,768 total layers, not its maximum Y. The diagram also retains Earth Platform's actual inclusive Y range. This establishes coordinate capacity, not a performance or accuracy ranking.

## Footage coverage and exact window restrictions

- Actual source dimensions/durations were probed: full trailer is 1920×1080, 66.433 seconds; the four selected ambient sources are 1008×1792, 40.000 seconds each.
- Full-cut landscape gameplay accounts for **63 seconds** of 162 foreground-gameplay seconds. The rest is portrait-source gameplay intentionally laid out on the landscape canvas. The deliverable is landscape, but this is not a claim of five minutes of native landscape capture.
- All thirteen full diagram backings use continuous ambient source windows, not repeated five-second snippets. Short diagram backings now also use continuous ambient windows.
- Landscape sources are explicitly limited to production-cleared ranges: Fuji 6.7–8.7; Everest 10–13; Matterhorn 14–16; Grand Canyon 17.5–19.5; Geiranger 24–26; dunes 27–28; Uluru 29.5–30.5; Rainier 36–37; Lake Louise 38–39; Yosemite 46–49 seconds. Iguazu 20.5–22.2 was cleared but is not selected in this edit.
- Never ingest original trailer frames 0–6 or 50 seconds onward. Its old release call to action is forbidden. Source excerpts are trimmed before slowdown, and no old audio is retained. Reusing scenic windows across distant chapters is deliberate, not evidence of separate flights.
- Weak coverage: no recorded multiplayer session, no new continuous landscape footage beyond the cleared short windows, and no controlled renderer performance comparison. Long portrait scenes and conceptual diagrams carry the explanatory material; the script must not suggest otherwise.

## Required release checks (not claimed complete)

1. ffprobe confirms 54.000 and 300.000 seconds, final delivery dimensions/frame rate, audio stream and valid decode.
2. Full-frame inspection at every cut, caption reveal and final disclaimer; mobile-size inspection of every longest line.
3. Confirm no real settlement or city/town caption in selected frames. Confirm shot names against footage rather than inferring geography from filenames.
4. Review final diagram holds for reading time; no overlay may obscure the scene's qualifying labels. Gameplay continues behind or beside diagrams.
5. Confirm first-frame hook, no black gaps, clean short-loop boundary and music continuity. No voice/narration track.
6. Confirm audio licenses, no clipping, consistent loudness and clear file labeling. Source evidence and render evidence stay separate.
7. Confirm release wording remains “coming soon”; do not add a date or compatibility claim without verified approval.

## Server performance caveat

The server scenes explain implementation, not a recorded multiplayer stress test. At the pinned commit, `minecraft/src/main/java/io/earthplatform/minecraft/cubic/far/FarServer.java:59–71` explicitly budgets CPU workers and notes that higher concurrency on four cores caused late answers. `docs/FAQ.md:88` warns that first visits are slow. Capacity and stability must be tested on the intended hardware; no player-count guarantee, four-core minimum or reproduced two-traveler crash is claimed.

### INSPIRATION
Source: `CREDITS.md:180–188`, `THIRD_PARTY_NOTICES.md:13`. Earth Platform credits Voxy and Distant Horizons as inspiration, and its maintainers state no code from either project is included. The video attributes the originality statement to the project credits; this edit is not an independent whole-repository plagiarism audit. On-screen at 04:24–04:30.

## Delivery compression limitation

The prepared canonical videos are size-bounded 1080p delivery copies; repository upload is incomplete and unverified. These prepared files are delivery copies, not the higher-bitrate production masters. Two-pass H.264 preserves frame rate and dimensions, but some fine terrain, foliage and moving texture may look softer or show compression. The owner retains separate higher-bitrate masters. This compression does not change the implementation or elevation claims and is not a renderer-quality benchmark.

## Publication status

The source production was committed. Local renders passed QA and review copies were delivered to the owner. The canonical video upload did not reach a verified repository commit; it remains a separate open delivery step. No publication to a public video platform occurred.
