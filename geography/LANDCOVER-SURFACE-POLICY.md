# Source-preserving snow/rock appearance policy

`io.earthplatform.geography.landcover.LandCoverSurfacePolicy` is an isolated, dependency-free material decision helper for the bounded Earth preview. It addresses uniform white mountain terraces by introducing slope-aware exposed rock and smoothly clustered cosmetic palette variation. **It does not change a height, add geometry, create a lake, or place a tree.** Relief and renderer behavior are separate integrations.

## Interface

```java
var source = landCover.sample(geographicPoint);
var selection = LandCoverSurfacePolicy.DEFAULT.evaluate(source, slopeRiseOverRun, blockX, blockZ);
// Optional explicitly estimated shelter in [0,1]; omitted means no shelter assumption.
var sheltered = LandCoverSurfacePolicy.DEFAULT.evaluate(source, slopeRiseOverRun, blockX, blockZ, shelter);
```

The two-argument `evaluate(source, slope)` overload uses coordinates (0,0) only for non-spatial inspection. A renderer must use coordinates to obtain coherent patches. `Selection` contains:

- `source`: the exact input `LandCoverSample`, retaining original classification, coverage status and source-region ID
- `slopeRiseOverRun`, `shelter`: the supplied evidence/heuristic inputs
- `appearance`: `UNKNOWN`, `UNRESOLVED_WATER`, `VEGETATED_GROUND`, `BARE_MINERAL`, `SNOW_ICE`, `STEEP_ROCK` or `BUILT_UP_HINT`
- `basis`: whether the decision was unavailable, unresolved water, cosmetic class mapping, cosmetic slope mapping or a procedural snow/rock transition
- `policyIdentity`: settings/seed fingerprint
- `materialVariant`: integer 0, 1 or 2; a clustered palette hint, not a measured rock type or ice subclass

## Rules and their status

These thresholds are **heuristic appearance rules**, not measured snow stability, climate, geology or soil properties:

1. No-data/outside-coverage returns `UNKNOWN` at every slope. The UI should keep unknown visible. A neutral visual fallback is procedural and must not be described as measured grass or rock.
2. Permanent-water class returns `UNRESOLVED_WATER`. There is no native-water block, water height, depth, lake generation or tide inference in this API. Built-up returns a hint without creating structures.
3. Only source class 70 can return `SNOW_ICE`. It stays snowy at rise/run ≤0.65; at ≥1.35 with no shelter it becomes `STEEP_ROCK` appearance, while the original source classification remains class 70.
4. The intermediate snow/rock band uses a smooth deterministic material field. Optional shelter lowers the effective slope by at most 0.35, increasing snow retention monotonically. Supply shelter only as an explicitly labeled local exposure/curvature heuristic; omit it when unavailable.
5. Bare/sparse cover is `BARE_MINERAL`, or a steep-rock appearance above rise/run 0.90. The class does not distinguish soil/sand/rock: gray stone in an alpine renderer is a cosmetic choice. Other terrestrial vegetation classes map to generic ground, with steep-rock appearance at rise/run ≥1.10. These rules do not establish individual trees or species.
6. Smooth value noise blended at 24 and 8 **projected-metre** lattice spacing chooses transition patches and subtle variants. It changes no source value or elevation. It is spatially coherent across negative coordinates and deterministic for the seed, including extreme long-valued coordinates. The native ~10 m land-cover grid does not measure these smaller material patches.

Compute slope from the unmodified DEM before any cosmetic relief, using physical horizontal distance when possible. For equirectangular coordinates, the east-west gradient correction is cos(standardParallel)/cos(latitude); a global standard-parallel-zero grid therefore divides its projected X gradient by cos(latitude). Latitude distortion, derivative spacing and interpolation method belong in the caller's source/estimator identity. Sampling a ~30 m DSM at ±8 projected metres remains an interpolated estimate, not an 8 m survey.

The renderer owns native block IDs/colors. A bounded plausible palette could use snow for `SNOW_ICE`, and clustered stone/andesite/tuff for mineral variants, but those exact choices are procedural. Do not silently turn `UNRESOLVED_WATER` into Minecraft water or render absent land cover as measured grass.

## Version and integration identity

`VERSION = worldcover-slope-appearance-v1`. `DEFAULT` uses seed 20221009. `new LandCoverSurfacePolicy(seed)` changes the fingerprint. `identity()` includes the semantic version, seed, all thresholds, noise scales/blend and key behavior rules.

A world/scene/cache identity must additionally pin the land-cover pack identity, DEM/projection identity, slope/shelter estimator, actual native palette mapping and any separate relief profile. Policy identity alone is not a complete world identity. Native files and APIs are untouched by this addition.

## Verification

Compile with Java 21 and the existing `GeoPoint` and `landcover` sources, then run:

```sh
java -ea -cp CLASSES io.earthplatform.geography.landcover.LandCoverSurfacePolicyTest
```

Tests cover a 65,536-point negative/positive-coordinate grid; repeated determinism; clustered variation rather than checkerboard noise; all source classes; snow-only-where-supported; gentle/steep inclusive thresholds; shelter monotonicity; invalid finite/range inputs; extreme long coordinates; signed zero; unknown/no-data and unresolved water; seed/version identity; and retained actual Everest source evidence. This establishes the helper behavior, not the appearance of a Minecraft scene. The renderer must still produce and review a new in-game screenshot from the same view.
