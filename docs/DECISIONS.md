# Engineering decisions and unresolved gates

This document distinguishes working code from proposed architecture. Updated 2026-10-09.

## Decisions implemented

- 16³ immutable base cubes, separate durable authoritative edits.
- Every generation/subscription/residency queue has a tested cap in the independent core.
- Dataset and palette identities are pinned per world; changes are not silently applied.
- Java21, Minecraft1.21.1 and NeoForge21.1.256 provide the initial integration target.
- NOAA ETOPO2022 is the small baseline data proof. Copernicus GLO30 Everest is an optional separate higher-resolution proof, never silently substituted.
- Missing/corrupt/out-of-coverage data fails explicitly rather than inventing real-world terrain.
- Minecraft native vertical windows are diagnostic only and visibly labeled. They do not satisfy the requested cubic engine.

## Decisions intentionally unresolved

### Horizontal geometry

A flat continuous Minecraft grid cannot preserve all globe distances at one block per physical meter. A versioned projection policy must define tolerated local distortion and seams. The initial equirectangular proof is not a final choice. A measured legacy modified-Airocean candidate has much smaller extreme distortion but still nonzero errors and discontinuities. A regional atlas could improve local scale while requiring explicit transitions at region boundaries. Do not migrate an existing world by changing its coordinate function.

### Height and depth fidelity

Do not force raster peaks/trenches to famous quoted values to make a demonstration appear accurate. Source resolution and vertical datum are separately recorded. Higher-resolution regional surveys can override a coarse base only with deterministic blending, quality precedence and saved generation identity. Raw source measurements and interpolated block heights remain distinguishable.

### Minecraft engine replacement

Dimension-height changes alone are insufficient. Lossless block addresses, signed section storage, cube-based packets, bounded rendering, tick/entity ownership and independent cube tickets are separate gates. A full-height column diagnostic may help identify bugs but is not the shipping storage design.

### Lighting and fluids

Unloaded terrain above a cube cannot imply open sky. Generation must provide conservative vertical occlusion summaries or defer skylight safely. Fluid simulation must not trigger unbounded world generation; boundary state and delayed work require explicit bounded ownership. These are designs to implement and test, not current guarantees.

### Addon compatibility

A cube-native public API is planned; arbitrary existing Minecraft mods cannot be promised compatible. API versions, world storage versions and data manifest versions are independent. Addons must declare required features and obtain controlled edit access, rather than mutate immutable terrain or bypass residency budgets.

### Operational availability

Open data licensing does not guarantee unlimited API capacity or free hosting. The initial packs work offline. Production access needs bounded import/cache policies, attribution, retry budgets and optional user-provided regional packs; no paid backend or credential creation has been authorized.
