# Candidate 039: bounded native selected-distance content plan

## Scope and verification status

`SurvivalDistancePagePlan` is a pure immutable content planner. It does not publish a frame, admit a simulation cube, certify an overlay, upload a native mesh, clip Distant Horizons, or prove selected-distance readiness. No 037 files were changed. Its two new Java files have not been compiled or run by the planner worker; the parent owns the sole JVM/build lease. The source-backed JUnit census below is an executable test specification, not claimed runtime evidence.

The implementation uses the already checksum-verified `MarinSurvivalDefinition` read-only visual identity and source. Existing source identity/overlap tests were read before implementation. Mutable source admission remains the existing 512 m travel rectangle. Read-only selected-distance source availability does not expand mutable ownership.

## API and capture contract

- `build(definition, focus, DistanceChoice, CompleteEditSet, Limits)` returns the immutable plan.
- `DistanceChoice(selectedChunks, effectiveChunks, effectiveCapChunks)` requires 2..32 selected and effective distances. Zero cap means uncapped. A nonzero 2..32 cap must explain `effective = min(selected, cap)`. The convenience `build(..., selected, effective, ...)` accepts equal selected/effective values only. This planner records the cap; the runtime owner still must prove it actually came from the effective native setting.
- `CompleteEditSet.copyOf(contentRevision, lowercaseSha256ContentIdentity, completeModifiedCubes, limits)` defensively copies a bounded complete set and digests the set, content identity and revision. It is deliberately caller-attested **content**, not an owner capability. No constructor can establish completeness by itself.
- Production must bind that exact index to an owner-certified coherent capture of every durable and live modified cube, including unloaded modifications, explicit AIR tombstones, and index removals. Residency is not a completeness oracle. A change to the index/revision/content invalidates the prior plan binding, even if the edit is offscreen.
- `pages()` is a stable ordered list. `pageIndex()` is keyed by aligned minimum `CubePos`. Each `Page` exposes `address`, separate `groundMask`, `treeMask`, `editExceptionMask`, their union `memberMask`, and exact lazily derived `members()` / `dependencies()`.
- Bit layout is `(localCubeY * 4 + localCubeZ) * 4 + localCubeX`. Signed page alignment uses floor division. The 64th member is the sign bit and is valid.
- `surfaceY` and `lowestExposedGroundY` expose generated-source ground observations in the selected rectangle. They contain no applied edit payload and confer no opaque-ground or DH replacement authority.

## Exact horizontal and sparse vertical ownership

The footprint is the inclusive native cube square `[focus.x-distance, focus.x+distance]` by `[focus.z-distance, focus.z+distance]`, with effective distance used for geometry and selected distance separately recorded. It is a half-open block rectangle after conversion. It does not adopt server rounded chunk tracking. Camera, frustum and actual native/DH depth eligibility remain separate proofs.

A 64 m page is only a work unit. A page intersecting an edge owns only the exact selected 16 m members, even when the rest of the physical page lies outside the footprint. Its meshing dependencies may extend outside the footprint; dependencies are not draw members.

For every selected block column the planner adds:

1. The cube containing the generated ground top.
2. Every signed-Y cube intersecting an exposed cardinal cliff interval, from `min(surface, min(cardinal-neighbor surface)+1)` through `surface`, inclusive.
3. The cubes containing non-AIR procedural tree cells above that column's ground. Bounded 64 m root queries include roots beyond query edges whose crowns intersect. Each returned tree's actual state function is evaluated, so sparse canopy corners and terrain-over-feature precedence are preserved.

Ground and tree reasons are separate masks. Ground remains selected under trees. Leaves never certify ground occlusion. Ground selection does not extrude to the world's minimum Y; tree selection does not create sky columns.

Every modified cube and each of its six face neighbors is added as an explicit exception where its own XZ cube lies in the footprint. This includes an edited cube just outside the footprint whose neighbor lies inside. Arbitrary signed-Y edits remain at their own height; no interval connecting a deep excavation or high build to the terrain is introduced. Native payload application and native-state correctness remain downstream responsibilities.

There is an explicit vertical boundary limitation: an edit, its exposure-neighbor member, and that member's meshing halo require up to two cubes of support beyond the edited cube. The current visual source and native render envelope have the same Y endpoints as mutable generation. Therefore edits in the lowest or highest two admitted generation cube rows refuse visual planning. The source remains unchanged and the planner fails closed before source-slab allocation. The parent must either enforce a separate verified 32 m gameplay margin at each endpoint or extend the proved read-only source/render halo envelope before claiming every admitted generation Y is editable/renderable. Tests cover both rejected rows and the immediately adjacent supported rows.

## Source and resource bounds

Before source sampling the planner checks focus, distance arithmetic, explicit limits, complete edit-index size, all horizontal meshing-halo extremes, and every relevant edit-exception dependency box against constructor-verified visual support. The retained source slab is the exact selected rectangle plus one block-column neighbor border, held as an `int[]` of surface heights. Ground source/dependency Y extremes are checked before ground-member enumeration. Every newly admitted member checks all eight corners of its 3-by-3-by-3 dependency box before retaining dependency bits. The source support is a verified rectangle, so these extrema checks prove its interior.

Each page has at most 64 selected members and at most 216 exact dependency owners. A full physical page has 152 exterior halo owners. A sparse page's exact dependency union may include unselected interior members as neighbors.

All these caller-supplied limits are explicit and fail closed:

- sampled block columns, including the one-column border
- selected horizontal 16 m cubes
- retained signed 64 m pages and selected 16 m members
- aggregate per-page dependency references and halo references
- complete modified-cube index size
- logical metadata allocation admission

Dependency references count shared owners once **per page**, rather than disguising total work as a globally deduplicated count. Halo references are dependency references minus selected member references. Exterior halo references are separately reported. Halo admission also applies during construction; an intermediate sparse subset may require more nonmember neighbors than the final page.

Logical metadata admission is `2048 + 4*sampledColumns + (256 + 96*completeEdits) + 65536 + 768*pages` bytes, using exact arithmetic. The page allowance includes construction/canonicalization metadata; the fixed scratch allowance covers one bounded tree-root query. This is an explicit logical allocation admission allowance, **not measured heap usage**. It excludes source object ownership, native full-cube/state/section payloads, geometry, models, queues, retained meshes, GL buffers and driver allocation. Those still need independent runtime resource admission and measurements.

No limit failure shrinks a distance, truncates a page list, substitutes AIR, drops an edit, or returns a partial plan. There is no all-world vertical loop. Source generation remains read-only.

## Finite source census and tests

Run the parent's normal reviewed Minecraft JUnit path with selector:

`io.earthplatform.minecraft.survival.render.SurvivalDistancePagePlanTest`

The finite test logs `SURVIVAL_DISTANCE_SOURCE_CENSUS` at distances 12, 24 and 32, including verified source identity, exact plan identity, sampled columns, pages, each member reason, exact dependency/halo/exterior-halo references, feature query visits and logical metadata bytes. Actual page/member counts must be taken from that run; they are not guessed here.

Mathematical footprint expectations, independent of source data:

| Effective chunks | Width in blocks | Horizontal cubes | Selected block columns | Sampled columns |
| --- | ---: | ---: | ---: | ---: |
| 12 | 400 | 625 | 160,000 | 161,604 |
| 24 | 784 | 2,401 | 614,656 | 617,796 |
| 32 | 1,040 | 4,225 | 1,081,600 | 1,085,764 |

The tests additionally cover all selected distances 2..32 at center and travel corners; legitimate versus unexplained effective caps; signed arithmetic overflow; exact partial-page boundaries; independent top/cliff and tree-cell coverage; retained ground under foliage; arbitrary deep/high edits and an outside-footprint neighbor; no full-Y extrusion; deterministic complete-index/revision identity; immutable outputs; negative pages and the sign-bit member; exact 64/216/152 cardinalities; and each explicit budget refusing rather than clamping.

## Release gates still required

This is not a release substitute for real selected-distance native rendering. The parent must still bind complete owner-certified durable/live overlays, apply exact edited native states in every member and dependency, build/upload actual model-backed meshes, enforce whole-action/revision fences, establish each frame's camera/depth eligibility, and pair that publication with the revision-correct exact DH complement. Full selected-distance and revision-correct DH proofs remain release gates. Synthetic fixtures, a source-only census, or a successful planner unit test cannot satisfy them.
