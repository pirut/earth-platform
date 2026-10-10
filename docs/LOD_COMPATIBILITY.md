# Distant Horizons / Voxy compatibility gate

Audit and headless proof: **2026-10-09**. Earth target: **Minecraft Java 1.21.1, NeoForge 21.1.256, Java 21**, independent 16³ cubes, native Y **−11000 through +8849**. Aligned engine bounds are **[−11008, 8864)**, a 19,872-block span.

## Decision

**Target an explicitly adapted Distant Horizons integration. Neither stock DH nor stock Voxy is verified compatible with this wide-Y sparse engine.** DH has an official build for the existing loader/version, a direct terrain-data API, and an LGPL source route for required changes. Matching the loader does not resolve its height encoding or column-oriented integration.

The first adaptation primitive now passes against the **actual official DH 3.3.3 binary**: divide vertical intervals into independently keyed 2,048-block pages, keep DH's packed heights local to each page, and retain a separate absolute page origin. The primitive passed headless data/API/CPU-vertex checks and, subsequently, an isolated actual-shader OpenGL gate inside Minecraft. The latter used Mesa llvmpipe software rendering. Neither is a finished DH mod integration, native terrain world view, hardware GPU performance result, or drop-in compatibility result. A page-aware DH adapter/fork is still required. The existing 256-block Earth-offset game windows do not demonstrate this integration or the full native height target.

## Exact upstream baseline and rights

### Distant Horizons

- Official release: **3.3.3-1.21.1**, Modrinth version **9w34y8ai**, published September 29, 2026, lists **Fabric and NeoForge**. The release's 1.21.1 build properties specify **Java 21** and both loaders. Our exact NeoForge 21.1.256 combination has not yet been launched with DH. [Official version metadata](https://api.modrinth.com/v2/version/9w34y8ai), [pinned 1.21.1 build matrix](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/versionProperties/1.21.1.properties).
- Wrapper release commit: **a54dd3eb1df0078e63a71266d042c10d9cda4493**. Its core submodule is **b02c66d778beea11a931291815d652cd8abaa7ad**. API **7.2.0**, not whatever a current development Javadoc happens to expose. [Release tag](https://gitlab.com/distant-horizons-team/distant-horizons/-/tags/3.3.3), [pinned properties](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/gradle.properties).
- Executed artifact: `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`, **28,289,212 bytes**. SHA-256 **864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab**. The official SHA-512 is also verified before every probe run; see `../evidence/lod-compatibility/source-manifest.json`.
- Source license: **LGPL-3.0-only**. Preserve notices, supply applicable corresponding modified source and build information when distributing an adaptation, and keep upstream identity/license separate from this project's own mod metadata. No upstream source has been relabeled as original Earth code. The proof does not bundle the upstream JAR. [Official project license metadata](https://api.modrinth.com/v2/project/distanthorizons), [pinned LGPL text](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/LICENSE.LESSER.txt).

### Voxy

- Official published versions retrieved on the audit date list **Fabric only**, with no **1.21.1** entry. Latest listed release is **0.2.19-beta for 26.2**. Source commit **534d58ec8b4aa412ef314b884295552c69d480a6** is a later development baseline: 0.2.20-beta, MC 26.2, Fabric, Sodium, Java target 25. This source audit is not a claim that every historical release has identical internals. [Official release catalog](https://api.modrinth.com/v2/project/voxy/version), [pinned properties](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/gradle.properties), [pinned build](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/build.gradle).
- Its official project description specifies OpenGL 4.6, terrain ingestion as the player explores, and import of existing worlds. This does not establish a built-in bounded geographic far-field generator for our custom engine. [Official project](https://modrinth.com/mod/voxy).
- Source is all-rights-reserved. The official Modrinth description separately permits modpack inclusion of official published versions with attribution/link-back, expressly excluding unofficial backports. That narrow permission does not establish permission to redistribute our modified/backported Voxy. Do not use community ports as a licensing shortcut. No Voxy code or binary was executed, copied into this repository, or redistributed by this work. [Pinned source license](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/LICENSE.md), [official distribution conditions](https://api.modrinth.com/v2/project/voxy).

## Source-confirmed blockers and reusable pieces

### DH height packing fails before rendering

`FullDataPointUtil` stores interval height and bottom Y relative to the level minimum in **12 bits each**. `RenderDataPointUtil` also masks its minimum/maximum Y to **0xFFF**. Java API fields being `int` do not expand these backing formats. The release's full-data validation uses a 6,095 limit even though those fields encode only 0–4,095; validation alone is not a sufficient guard. The release runtime disables developer validation. [Pinned full-data packing](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/util/FullDataPointUtil.java), [pinned render packing](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/util/RenderDataPointUtil.java).

The actual binary reproducer gives **+8849 − (−11008) = 19857**, which packs as **3473** and reconstructs as **−7535**. The isolated probe also shows the stock validator accepts relative Y 4096, which subsequently packs as zero. These are executed observations, not just deductions from bit widths.

DH positions are X/Z/detail-level keys, with no page-Y component. The full-data SQL DTO serializes packed longs and carries an explicit format version. Consequently, changing packing, adding pages, or sharing caches without a schema/identity boundary cannot be treated as a transparent configuration tweak. [Section key](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/pos/DhSectionPos.java), [versioned storage/network DTO](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/sql/dto/FullDataSourceV2DTO.java).

### DH can accept interval summaries without vanilla chunk generation

The pinned generator API supports **API_DATA_SOURCES** via `generateLod`, with requested horizontal/detail coordinates and a pooled `IDhApiFullDataSource`. Columns can be populated with terrain intervals. Reuse the supplied executor and respect pooled-object lifetime; do not retain a data source after ownership returns to DH. Include explicitly known air and appropriate light between terrain layers rather than leaving accidental gaps. [Generator API](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/interfaces/override/worldGenerator/IDhApiWorldGenerator.java), [full-data API](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/objects/data/IDhApiFullDataSource.java).

The public terrain repository's mutation method takes vanilla chunk objects; it is not an arbitrary page-aware interval invalidation API. Loaded chunk updates may replace supplied data. The integration must intercept or replace those paths in cubic worlds rather than fabricate 1,242-section vanilla columns. [Terrain repository API](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/interfaces/data/IDhApiTerrainDataRepo.java).

### CPU/GPU format and precision

The actual `LodQuadBuilder` writes 16-byte vertices including 16-bit coordinates. The OpenGL vertex attribute interprets positions as unsigned shorts. The shader combines local coordinates with a model offset; the Java draw path subtracts the double-precision camera position before converting that offset to float. These are useful foundations, but do not remove the earlier 12-bit data limits. [Vertex builder](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder.java), [draw path and attribute binding](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/common/src/main/java/com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram.java), [terrain vertex shader](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/resources/assets/distanthorizons/shaders/terrain/gl/vert.vert).

The page origin must affect mesh position, bounding boxes, world-height shader inputs, water/fog, shadow/occlusion assumptions, and any optional curvature consistently. Do not enable cosmetic Earth curvature to conceal projection/height errors. Keep authoritative X/Y/Z integral or double, with camera-relative float conversion late. The arithmetic probe shows one-meter neighbors at ±20,037,508 lose spacing if absolute positions are cast to float first; double subtraction preserves local spacing. This is not yet a GPU visual test or a global projection guarantee.

### Voxy is also not an automatic cubic match

At its audited source pin, Voxy's `WorldSection` represents 32³ cells. `WorldEngine` stores section Y in **8 signed bits**, and the shader position decoder repeats that layout. Level-zero absolute Y therefore spans −4096 through 4095. Our range needs level-zero section Y −344 through 277; it cannot be represented by that key. Coarser LOD coverage does not repair finest-level key collisions. [World engine key](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/java/me/cortex/voxy/common/world/WorldEngine.java), [section size](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/java/me/cortex/voxy/common/world/WorldSection.java), [shader decoder](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/resources/assets/voxy/shaders/lod/pos_util.glsl).

Its ingestion service accepts `LevelChunk`, loops the chunk's section array, and obtains level lighting. Internally cubic LOD storage is not equivalent to a supported sparse-native-cube ingestion API. Its world identifier incorporates dimension keys and biome seed; our dataset/projection/generator identity would require additional separation. No stable supported public API for our complete integration was established. [Ingestion](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/java/me/cortex/voxy/common/world/service/VoxelIngestService.java), [world identifier](https://github.com/MCRcortex/voxy/blob/534d58ec8b4aa412ef314b884295552c69d480a6/src/main/java/me/cortex/voxy/commonImpl/WorldIdentifier.java).

## Implemented isolated proof

Run from the repository root:

```sh
bash evidence/lod-compatibility/run-proof.sh
```

The runner verifies the official artifact, uses the existing Java 21 toolchain and already-resolved Log4j/Fastutil/Guava dependencies, compiles only the original probe/adapter sources, and runs headlessly. It does not install a mod or touch a world. The third-party binary remains outside the repository; license copies and source/artifact provenance are separate.

**Passing recorded result:** 100,000 seeded random intervals; 345,772 packed pieces; 3,312,663 assertions; 96 vertices emitted by the real DH CPU vertex builder. See `../evidence/lod-compatibility/proof-output.txt`.

Coverage:

- Stock full/render packing failure reproduced at the target maximum elevation.
- Exact interval reconstruction through real DH packing, with negative-Y floor division, both target extremes, page edges, sea level, full-span intervals, lighting, maximum nonnegative state ID, and a provisional external page+payload byte envelope.
- Unknown, empty, invalid-light and out-of-envelope inputs rejected; no implicit truncation or invented air below/above known bounds.
- Actual `FullDataSourceV2` direct setters/getters round-trip a column containing trench rock, water up to sea level, and sky-lit air across all 11 pages. Only Minecraft block/biome wrappers and the minimal wrapper factory are fixtures. The factory throws if a vanilla chunk generation/loading path is requested. This checks API/data conversion, not real biome colors, fluids, lighting propagation, or database persistence.
- Actual `LodQuadBuilder` generates top/side vertex buffers at native extreme and page-boundary elevations, including local top Y 2048. Buffers reconstruct the original absolute Y after adding their page origin. No GL context, VBO upload, shader execution, screenshot, or frame-time measurement was performed.
- Original bridge cache-key/revision guards distinguish page/world/dataset/projection and reject late results after edits or epoch changes. These are tested guards, not a claim of installed DH invalidation or multiplayer behavior.

## Adaptation contract still to implement

### 1. Independent vertical pages

Use `pageY = floorDiv(nativeY, 2048)` and `localY = nativeY − pageY*2048`; never rebase the player's authoritative world. Pages −6 through +4 cover the aligned envelope. Split half-open intervals at page boundaries; local top 2048 is representable. The first/last pages are only partially within the Earth envelope; preserve explicit valid bounds rather than fabricating the unused part.

Page ownership must exist through full data, render data, quadtree/cache entries, draw buffers, save paths and network messages. A synthetic level wrapper alone does not prove concurrent multi-page rendering works. DH's ordinary single-level X/Z cache would alias these pages. A page container around DH data can retain its per-page packed format, but requires integration changes and explicit protocol/schema versioning. A wider global format is an alternative fork design, not an API-only shortcut; its existing 64 bits already hold height, lighting and appearance.

### 2. Geographic far-field generation with hard budgets

Request geographic summaries at the requested horizontal resolution, then emit known material/air/water intervals directly. Preserve the same world generation manifest, vertical datum and overlays used by native near cubes. Use land cover and terrain materials consistently; a coarse source does not become surveyed one-meter data because blocks are one meter apart.

Never create full-height `LevelChunk` arrays, load all vertical cubes, call distant gameplay simulation, or perform one-meter global pre-generation. Cap in-flight requests, queued work, source bytes, interval complexity, page count, CPU time slices, upload bytes and disk cache. Deduplicate identical requests and reject stale generation after teleport/disconnect. Bound water and homogeneous rock by intervals; do not trust a native section's nonempty count as a voxel-volume measure. The engine worker found native fluid count behavior requiring canonical state-based snapshot counts.

### 3. World identity and edits

Namespace caches by save UUID, dimension, projection/version, vertical datum, dataset manifest, generator version, LOD schema and page size. A page key additionally includes signed page Y, horizontal tile and detail. A player-moving vertical window must never change the identity of existing terrain. Do not silently reuse DH's current stock database for this schema.

Native committed edit revisions are authoritative over regenerated base data. Invalidate the affected leaf page, dependent coarser tiles, relevant horizontal/vertical neighbors, mesh boundaries, and water/lighting summaries. Clear removed geometry too. Carry epoch/revision through every asynchronous job and check again at apply time. A base-data refresh must not erase player changes. Prove restart and two-client coherence before claiming edit support. Stock chunk notifications must not overwrite a page with an incomplete sparse-column view.

### 4. Near/far seams and native residency

Use the native engine's actual **3D cube mesh presence**, not an X/Z-only chunk-loaded flag, to suppress overlapping LOD. Otherwise a near cube at the trench can hide a mountain LOD at the same X/Z. Keep replacement geometry until the corresponding native mesh is ready. Handle cross-page faces without internal caps, cracks, duplicated water surfaces or double alpha accumulation. Add edge skirts/fade only as a deliberate geometric strategy; do not use fog to hide misalignment.

Keep unknown cubes distinct from known air and unresident known solid. Audit cave culling and vertical bounds before displaying trenches or overhangs. Far-field terrain does not provide collisions or authorize gameplay beyond actual native residency.

## Next verification sequence

1. **Page-bearing source and cache integration, headless.** Extend the successful primitive into a page-aware DH data owner under the isolated adapter module. Exercise actual API generation, parent aggregation and persisted page identity with source manifests and edit revisions. Verify bounded geographic queries using fixtures that fail any vanilla chunk-generation call. Reproduce removal/replacement and restart, not only first insertion.
2. **CPU mesh adjacency gate.** Exercise real DH column-to-render conversion and neighbor handling for two adjacent vertical pages, including matching solid interiors, water, overhangs and an edited boundary. Verify absence of internal caps and correct page-relative bounds. Current tests bypass that full transformer and deliberately do not claim it works.
3. **Small native client gate.** Use MC 1.21.1 / NeoForge 21.1.256 / Java 21 and checksum-pinned DH 3.3.3 in a separate disposable development profile. Begin with the unmodified baseline in an ordinary test world, no Iris/shaders/Sodium extras, then the page adapter. Do not replace the user's worlds or the existing diagnostic window saves. Record hardware, actual loader versions and logs. This installation/launch has not been done here.
4. **Native wide-Y plus GPU gate.** After the engine exposes the real native dimension and sparse 3D client meshes, draw page-aware DH terrain simultaneously at trench and mountain elevations. Verify world Y, shader origins, old 4,096 boundaries, all page boundaries, negative X/Z, projection-extreme precision, water transparency, fog and view changes. Render both extremes without a per-player coordinate rebase.
5. **Measured scale gate.** Start with a bounded 4,096-block LOD radius and grow to 16,384/32,768 only after instrumentation. Report actual CPU/GPU frame-time percentiles, heap/direct/GPU memory, resident native cubes, generation latency, cache-hit rate, queue sizes, source I/O and upload volume. Include stationary, flight, teleport and two-player workloads. These radii are proposed experiments, not achieved distances or performance promises.

**Acceptance wording:** only after these gates may the project claim “works with an adapted Distant Horizons build for native full-height Earth.” “Works with stock DH,” “Voxy compatible,” stable multiplayer LODs, seamless rendering and performance claims remain unsupported.

## Prepared actual-shader GPU gate

The bounded offscreen gate **passed inside actual Minecraft OpenGL** on October 9 at 22:11:49 UTC: 184 actual-DH quad draws, 736 vertices, 13 exact color/depth cases, 6,815,744 pixel comparisons and 52 stale-job rejections. All ten internal page boundaries and both native elevation extremes passed. GL objects were deleted and touched state restored. The renderer was **Mesa llvmpipe software OpenGL 4.5**, not discrete GPU hardware. Source-built preparation passed 68 tests. See `../evidence/lod-compatibility/GPU_GATE.md` and `../evidence/lod-compatibility/gpu-run-001/verification.json` for exact hashes and untouched report/image evidence. This remains an isolated authored-geometry test, not production terrain/LOD coexistence.
