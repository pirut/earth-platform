# Earth Platform explainer: implementation claim audit

Audit target: `pirut/earth-cubic-platform`, commit `5b2b62b745c9580f64ca3d3c95bc631f3570c683`. Source files were fetched through the GitHub connector at that exact commit and materialized in this workspace. No code was changed or pushed. This is static source review, not a successful build, benchmark, gameplay test, or compatibility certification.

Citation prefixes below are exact repository directories:
- `C/` = `minecraft/src/main/java/io/earthplatform/minecraft/cubic/`
- `G/` = `geography/src/main/java/io/earthplatform/geography/globe/`
- `T/` = `minecraft/src/test/java/io/earthplatform/minecraft/cubic/`

For a source link, expand the prefix and append the path to `https://github.com/pirut/earth-cubic-platform/blob/5b2b62b745c9580f64ca3d3c95bc631f3570c683/`, with `#Lnn-Lnn` for the cited lines.

## Corrections required before narration

1. The implemented range is **Y −16,384 through +16,383**, inclusive: **32,768 blocks total height**. Do not say ±32,768, Y 32,768, or unlimited vertical height. `C/CubeWorld.java:35,62`; matches `docs/FAQ.md:5–6`.
2. **94–201 km is a game-block reach interpretation**, not a promise of that geodesic distance everywhere on Earth. Default far reach is 94,208 blocks; maximum startup detail gives 200,704 blocks. Web Mercator changes local horizontal scale away from the home latitude. Prefer “about 94,000 blocks by default, configurable to about 201,000.” `C/far/Far.java:25–34,64–68`; scale caveat `docs/FAQ.md:5–6`.
3. Do not label this implementation an **explicit SSE-driven renderer**. The audited path uses nested square rings, configurable detail/sharpness, and altitude/speed-dependent finest-level selection. No projected screen-space-error calculation drives that selection. “Coarser voxels with distance” is accurate; “screen-space-inspired detail” needs explanation. `C/far/Far.java:105–130`; `C/client/FarRenderer.java:467–482,923–938`.
4. “Exact heights” means aligning far surfaces to the generated ground/water surface rather than snapping them to coarse voxel tops. It does not imply survey-perfect truth. Drops are stored in 1/256 block units; mesher face storage is 1/16 block. `C/gen/SurfaceGenerator.java:344–353`; `C/client/FarMesher.java:52`; `docs/FAQ.md:90`.
5. Do not say clients **never download geographic data**. Terrain authority is on the server, but the client globe picker builds its own detail tiles using elevation and land cover. Its cache is local to that game installation; it is not a cache shared over the network with a dedicated server. `C/play/client/GlobeImagery.java:151–175`; `C/CubicSettings.java:208–238,348`.

## Approved claims and evidence

### A. True-height cubes, block authority, and near loading

Approved narration: “Nearby terrain lives in independent 16-by-16-by-16 cubes at its actual height. Block reads and edits use that cube store, and the server loads a bounded neighborhood around each player.”

- A cube has independent x/y/z coordinates, vanilla `LevelChunkSection` storage, block entities, and true block origin (`x << 4`, `y << 4`, `z << 4`): `C/Cube.java:10–32`.
- Block lookup resolves the real block coordinate to a cube; missing cubes return the unloaded state: `C/CubeWorld.java:50–69`.
- Level mixins route reads into the cube store and reject writes outside range or to unloaded positions; vanilla-compatible snapshot and notification handling is retained: `C/mixin/CubicLevelMixin.java:49–88`.
- Default vertical radius is 6 cubes; horizontal cap is 12, bounded by server view distance with a minimum of 2: `C/Cubic.java:13–16`; `C/ServerCubeWorld.java:383–384`.
- Server streaming uses player coordinates, generation budgets and sent-state tracking: `C/ServerCubeWorld.java:408–430,438–443,571–592`.
- Qualification: this is not a promise that every cube in the entire height range is loaded, simulated, or editable simultaneously. Buried terrain and fast flight have special scheduling paths: `C/ServerCubeWorld.java:95–111,457–478`; jet-stream collision limitation is disclosed in `docs/FAQ.md:86`.

### B. Elevation cleaning, ordered sources, and world pins

Approved narration: “The elevation pipeline starts with Terrarium terrain tiles, repairs known artifacts, then layers in regional sources in a saved order. Each world records which source combination it uses.”

- Real cleanup call order: raw samples → stray-zero repair → seam treatment → isolated-pixel repair (`mend`) → reference-level repair for fine zooms: `G/TerrariumTileSource.java:298–304`.
- After that, layers refine in list order, then high-zoom despiking runs: `G/TerrariumTileSource.java:91–94,122–127`. Do not describe every cleanup operation as occurring before overlays: the final despike happens after them.
- New-world default order: Terrarium → Copernicus fill → Canada HRDEM → US 3DEP → Antarctica REMA → France IGN → Japan GSI. National layers are optional via startup properties; later overlays win where they overlap. `C/CubicSettings.java:185–202,216–238`.
- Dataset IDs, source combination, projection, home latitude/longitude and metres-per-block are written to world properties: `C/CubicSettings.java:148–170`.
- Reload uses the saved elevation pin and explicitly handles older worlds without newer layers: `C/CubicSettings.java:92–120`.
- Qualification: dataset-ID pins are not cryptographic snapshots of every external tile, nor proof that every layer covers every location. Missing source data fails a tile for retry; other runtime layer errors restore its prior values and warn: `G/TerrariumTileSource.java:1038–1051`.
- Avoid “perfectly cleans all artifacts” or “guaranteed bare-earth accuracy.” Implemented heuristics cannot establish those universal claims.

### C. Climate, ecoregions, rivers, and procedural trees

Approved narration: “Land cover, long-term climate and ecoregions guide the biomes and vegetation. River traces combine satellite water observations with terrain-derived streams. Individual trees are generated, rather than copied one-for-one from Earth.”

- Climate uses CHELSA V2.1 **1981–2010 climatologies**, not live weather: dataset and variables `G/ClimateSource.java:21–38,53–58`; bilinear temperature/rain sampling and elevation lapse-rate adjustment `G/ClimateSource.java:95–125`.
- Earth generation consumes climate at geographic coordinates and altitude, with deliberate boundary warping and a latitude/height fallback: `C/earth/EarthGenerator.java:2084–2104`.
- RESOLVE 2017 ecoregions ship as a 0.05-degree map. Resource loading and absence fallback are explicit: `C/earth/Ecoregions.java:15–27,36–59`.
- Ecoregions influence forest selection, including avoiding tropical forest in non-tropical ecoregions: `C/earth/EarthGenerator.java:2936–2956,3112–3154`.
- River tracing closes short gaps and joins facing ends in water-occurrence observations; optional drainage-derived streams supplement the map: `G/RiverMap.java:12–35,196–199`; Earth generation uses river strength to recover narrow channels `C/earth/EarthGenerator.java:748–755`.
- Actual tree placement is deterministic procedural selection: per-position hash, surface density, biome/tree feature selection, pre-grown shape variant, orientation and seed: `C/gen/Trees.java:157–179`. Vanilla feature shapes and special sequoias are described at `C/gen/Trees.java:47–59`.
- Qualification: data resolution, warped biome edges, inferred streams and procedural detail mean this is an interpretation for Minecraft, not a tree census, hydrological guarantee or navigation product. `docs/FAQ.md:14–15,90`.

### D. Far voxel pyramid, edits, meshing, and reach

Approved narration: “Beyond the playable cubes, the same world is represented with progressively larger voxels. Matching faces are merged, finer levels replace the middle of coarser ones, and saved builds are folded into the closer far-detail levels.”

- Levels 1 through 8 use `1 << level` blocks per voxel: 2, 4, 8, 16, 32, 64, 128, 256. Each far section is 16 voxels wide. `C/far/Far.java:25,67–68`.
- The nested pyramid is explicit in `around` and `refined`: `C/far/Far.java:105–130`. Renderer subdivision waits for children/uses available parent geometry: `C/client/FarRenderer.java:923–938`.
- Startup `DETAIL` defaults to 11, clamped 4–24; outer half-width is `2 * DETAIL + 1`; reach is outer half-width times level-8 column width (4096). Default: 23 × 4096 = **94,208 blocks**. Highest: 49 × 4096 = **200,704 blocks**. This is square-nearest-edge reach, not a spherical visibility radius or measured frame-rate guarantee. `C/far/Far.java:25–34,64–68`.
- Runtime sharpness changes the inner refinement hole, not the fixed startup reach: `C/far/Far.java:35–51`. An existing source test asserts this, but was not executed in this audit: `T/far/FarSharpnessTest.java:9–27`; nested coverage test `:30–42`.
- Saved edits invalidate only levels 1–4, and only those levels fold saved cube data into generated far columns: `C/far/Far.java:57–58`; `C/far/FarServer.java:161–167,182–188,196–217`.
- Qualification: edits are downsampled; some fine details disappear. Water/air replacements deliberately retain generated far shape, and underground edits outside the visible column extent are omitted. Do not promise every edit is visible at all distances. `C/far/FarServer.java:205–217,248–269`.
- Face merging exists in executable mesher loops: flat surface pieces merge into rectangles `C/client/FarMesher.java:507–557`; horizontal faces merge `:621–675`. Avoid claiming this necessarily minimizes every mesh globally.
- Height-offset formula aligns generated ground or water to its intended surface: `C/gen/SurfaceGenerator.java:344–353`. Precision caveat above applies.

### E. Server authority, client map tiles, and shared caches

Approved narration: “The server generates and streams the terrain players share. The globe picker can also fetch elevation and land-cover data locally, reusing that installation’s cache.”

- Server cubes are sent as payloads to watching players: `C/ServerCubeWorld.java:571–592`; block changes are broadcast `:651–652`.
- Server far generation makes columns from the generator, incorporates saved edits and serializes sections plus drops: `C/far/FarServer.java:178–191`; the client receives/constructs far columns `C/client/FarRenderer.java:331`.
- Local picker builds visual map tiles using `CubicSettings.tiles()` and `cover()`: `C/play/client/GlobeImagery.java:151–175`. Low-zoom slope shading exaggerates mountains; this is map presentation, not a terrain-height change: same file `:160–161`.
- Same-source elevation objects are reused across worlds in one game installation, with on-disk files under the game folder; offline mode suppresses network: `C/CubicSettings.java:208–238,348`. Land cover and climate use sibling caches: `:357–365,416–426`.
- Qualification: a dedicated server’s files are distinct from the player’s files. “Players need the mod but not the data” (`docs/FAQ.md:95`) describes ordinary server terrain delivery; it does not mean every client feature is network-free.

## Honest limits to retain in the explainer

- Measured macro terrain plus procedural Minecraft details, not a literal photograph converted block by block.
- Real cities, roads and buildings are intentionally absent; wild land replaces built-up cover. Vanilla structures may still appear. Documentation statement: `docs/FAQ.md:11–15`; implementation classifies climate/cover into generated surfaces (`C/earth/EarthGenerator.java:2400–2425`).
- First visits depend on data fetching and cleanup. Cached revisits can work offline only for data still present. No promise of instant loading or a complete offline planet. `C/CubicSettings.java:208–238`; `docs/FAQ.md:35,88`.
- Data can be missing, sources can fail, forests can bias surface elevation, waterfalls can be imperfect. `G/TerrariumTileSource.java:1038–1051`; `docs/FAQ.md:90`.
- Height range, rendering distance and source resolution are separate facts; a 32,768-block world is not a claim of 32,768 metres of surveyed detail.
- Compatibility statements in the README/FAQ are maintainer reports. This audit did not launch Minecraft, exercise mods/shaders, test macOS, benchmark, or certify those statements. If narrated, label them as documented support and retain version caveats (`docs/FAQ.md:53–57,68,82–90`).

## Evidence status

Implementation evidence supports the approved wording above. The far-sharpness test was read, not run. README screenshots and prose are presentation evidence, not proof of runtime correctness or performance. No benchmark or universal fidelity claim is approved by this review.
