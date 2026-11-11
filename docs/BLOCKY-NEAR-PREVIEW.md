# Block-shaped nearby terrain

This preview includes the snapshot reuse and previous performance/cloud changes.

Levels 1–4 of detailed far terrain now always use voxel faces rather than the
smoothed height surface. This holds during high flight and zoom, even if an old
configuration or mesh job requests smoothing from a lower level. Real nearby
16-cubed sections continue to render their original individual Minecraft blocks.

The earlier cruise override lowered the smoothing threshold to level 3, turning
8- and 16-block far voxels into slopes above 2,200 blocks over local terrain.
Cruise smoothing now reaches only coarse levels 5–8. At the default smoothing
threshold of 5, altitude transitions no longer schedule levels 3/4 for remeshing.
Coarse scenery retains smoothing, clouds, curvature and aerial texture filtering.

`smoothFarFrom` accepts levels 5–8, default 5; the JVM equivalent is clamped to
the same range. `cruiseSmooth` can bring a higher configured threshold down to 5
at cruise altitude, but never changes detailed block geometry. The actual
transition distance depends on far sharpness, loaded levels and zoom; this is
not a new fixed-radius high-resolution chunk-loading range.

This restores block silhouettes in the detailed terrain; distant voxels remain
larger approximations until real nearby cubes load. It does not load individual
blocks across the entire distant world or change generation/collision.

Regression checks inspect built meshes for all four detailed levels, including
legacy smoothing thresholds and dense materialization, and require voxel faces
with no smooth grid. Existing tests verify that coarse level 5 still smooths.
The full suite and driver regressions run without launching Minecraft.

November 9 verification: 255 tests passed, five optional benchmarks skipped,
no failures. Seven real-driver tests and the block-shape policy/mesh checks passed.
The exact running-world appearance has not been captured.

Artifact: `minecraft/build/flight-preview/earthplatform-blocky-near-preview.jar`.
SHA-256: `7f1c0a14c131f091e97f6b9cc6edf2c21b6c25c2cc441ce8168da080e96fa23a`.
