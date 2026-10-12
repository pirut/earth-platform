# Private sparse engine fixture: source and data notices

Original engine source is a development prototype, with no new public redistribution or stable addon compatibility claim.

## Distant Horizons

Players obtain and install official Distant Horizons separately. The distributable contains our adapter/engine source and resources only, with no upstream DH JAR, implementation source or extracted shader. A separately installed official JAR in the disposable minecraft/run/mods directory is runtime-only; it is excluded from source/distribution inputs and must never be included in a release directory snapshot. Its headless tests use an externally supplied, unchanged official Distant Horizons 3.3.3 artifact. DH is copyright James Seibel and contributors, LGPL-3.0-only. Official artifact/source pins and provenance are recorded in `runtime-inputs/source-manifest.json`; upstream license texts remain with the separately obtained official artifact. Corresponding sources:

- https://gitlab.com/distant-horizons-team/distant-horizons/-/tree/a54dd3eb1df0078e63a71266d042c10d9cda4493
- https://gitlab.com/distant-horizons-team/distant-horizons-core/-/tree/b02c66d778beea11a931291815d652cd8abaa7ad

The original engine's provisional licensing does not cover upstream DH implementation, shaders, binaries or license texts. No Voxy source/binary is included or executed. Inherited isolated-loader proofs run only in the legacy headless test JVM. The installed client backend must use the existing game/mod classloader and must not create or close a duplicate upstream runtime/pool. All shim-owned view GL objects must be deleted at session close; installed DH resources remain owned by DH.

## Earth test fixture data

Copernicus GLO-30: produced using Copernicus WorldDEM-30 © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA; all rights reserved. The bounded native-resolution crop is repacked without changing source elevations. Exact source object/ranges, original license, datum and metadata: `geography/data/copernicus-everest-proof/source-lock.json`, `world.properties`, and `provenance/`.

ESA WorldCover: © ESA WorldCover project2021 / Contains modified Copernicus Sentinel data(2021) processed by ESA WorldCover consortium. Zanaga, D. et al.(2022), ESA WorldCover10m2021v200: https://doi.org/10.5281/zenodo.7254221. CC BY4.0: https://creativecommons.org/licenses/by/4.0/. Changes are extraction/repacking into ELC1, without changing class codes. Source manifest, evidence and attribution are under `geography/data/worldcover-2021-proof/`.

NOAA ETOPO proof data remains as an existing independent fixture; it is not silently substituted into this Copernicus scene. Its source and dataset notices remain in `geography/data/noaa-etopo2022-proof/`.

Regional relief and surface appearance are explicitly procedural heuristics layered on pinned measurements. They are not additional measured elevation, climate, biome or geology data.

## Separately installed shader inputs

The shader candidate additionally requires player-supplied Iris1.8.14-beta.1, Sodium0.8.12-beta.1 and Photonv1.3b. Their unchanged external artifacts, shaders, nested payloads and licenses are not part of our shim distribution. Exact input hashes and the three-classpath/no-copied-payload verification are recorded in review-evidence/shader-packaging015 and shader-aggregate. Users obtain each project from its official distribution and keep the terms accompanying that separate artifact. This candidate’s original code only adapts the pinned installed lifecycle, native format/material context and pass-owned renderer resources. No broader version or pack compatibility is implied.
