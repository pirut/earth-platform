# Whole-planet elevation layer (`globe`)

Streams real elevation for any place on Earth. It is engine agnostic: the cubic engine (or any other host) can sample it as a terrain provider. It is separate from the pinned EGP1 regional packs.

- `MercatorGrid` (`web-mercator-v1`): X east, Z south, origin at 0°/0°, `metresPerBlock` projected metres per block (1 m at the equator by default). Conformal; block coordinates are linear in slippy-map pixels. Beyond ±85.05° fails explicitly.
- `TerrariumTileSource` (`terrarium-v1`): AWS Terrain Tiles PNGs, single-flight per tile, bounded concurrent transfers with retry, bounded decoded-tile LRU, atomic disk cache under `z/x/y.png`, offline mode. 403/404 and undecodable tiles throw `MissingTerrainException`; corrupt cache files are deleted and refetched. Callers block, so use it from generation workers, never a tick thread.
- `GlobalElevation`: Catmull-Rom bicubic through pixel centres across tile seams and the antimeridian. Above zoom 10 open sea is stored as 0 m, so those pixels take zoom-10 bathymetry.
- `VerticalScale` (`vertical-log-v1`): optional compression of elevation into a limited height range (near 1:1 at sea level, logarithmic above). A true 1:1 cubic host does not need it.
- `EarthSurface`: combines the above and exposes `surfaceY(x, z)` plus an `identity()` string that a world must pin.
- `GlobeProbe LAT LON SIZE PIXELS OUT.png [ZOOM] [CACHE]`: shaded top-down preview of the mapped surface from live tiles.

`GlobeTest` serves synthetic tiles from a local HTTP server (single flight, retries, 404, offline disk cache, corrupt-cache repair, seamless bicubic, antimeridian wrap, bathymetry fill). `EARTH_LIVE_TILES=1` adds checks against the real dataset at Everest, the Challenger Deep, the Dead Sea and Denver.

Run from the repository root with Java 21:

    mkdir -p /tmp/globe && javac -d /tmp/globe geography/src/main/java/io/earthplatform/geography/{GeoPoint,MissingTerrainException}.java geography/src/main/java/io/earthplatform/geography/globe/*.java geography/src/test/java/io/earthplatform/geography/globe/*.java
    java -ea -cp /tmp/globe io.earthplatform.geography.globe.GlobeTest

Terrain data attribution: see `THIRD_PARTY_NOTICES.md` (AWS Terrain Tiles).
