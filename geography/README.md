# Earth geography module

Dependency-free Java 21 geography pipeline, separate from Minecraft integration. This is a bounded real-data proof and an extensible raster provider, not a complete global 1 m survey or a production Earth server.

## Implemented

- Pin world identity to the complete canonical manifest: projection/origin, generator version, interpolation, dataset release, datum, native angular spacing, provenance, license, tile SHA-256 values and palette version.
- EGP1 compact immutable float32 rasters, deterministic bilinear sampling, overlap halos and antimeridian support.
- Batched asynchronous elevation/seafloor sampling. A cube requests a 16×16 horizontal height field, never an HTTP request per block. Vertical cubes reuse bounded cached height fields.
- `CachedTileStore`: single-flight tile reads, bounded in-flight admission, content-hash verification with corrupt-cache reload from the same pinned source, bounded in-memory LRU, atomically persisted content-addressed disk LRU, explicit offline mode and transfer limits. Sources are relative pack files or explicitly allowed HTTPS EGP1 tiles. No redirects, credentials, keys, sign-ups or paid APIs are used.
- `GeographyCubeProvider`: independent 16³ cubes, compact uniform air/water/rock, no Minecraft-height clamp. Cancellation of one cube does not poison shared tile/column work; cooperative cancellation is checked before generation and between Y layers.
- `OfflineTerrain`: preload and verify a small local pack (maximum 64 MiB) at initialization, then synchronously query/generate with no disk or network I/O. This supports game world-generation APIs that need immediate heights.
- Four actual NOAA ETOPO2022 rasters covering three bounded regions: San Francisco coast, Everest and Challenger Deep. NOAA importer, pinned original TIFFs, reconstruction scripts and attribution are in `tools/` and `data/noaa-etopo2022-proof/`.

## Whole-planet surface layer (`globe` package)

Streams real elevation for any place on Earth, for the playable world type described in `../docs/ROADMAP.md` (milestone A1). It is separate from the pinned EGP1 packs above.

- `MercatorGrid` (`web-mercator-v1`): X east, Z south, origin at 0°/0°, `metresPerBlock` projected metres per block (1 m at the equator by default). Conformal; block coordinates are linear in slippy-map pixels. Beyond ±85.05° fails explicitly.
- `TerrariumTileSource` (`terrarium-v1`): AWS Terrain Tiles PNGs, single-flight per tile, bounded concurrent transfers with retry, bounded decoded-tile LRU, atomic disk cache under `z/x/y.png`, offline mode. 403/404 and undecodable tiles throw `MissingTerrainException`; corrupt cache files are deleted and refetched. Callers block, so use it from world-generation workers, never a tick thread.
- `GlobalElevation`: Catmull-Rom bicubic through pixel centres across tile seams and the antimeridian. Above zoom 10 open sea is stored as 0 m, so those pixels take zoom-10 bathymetry.
- `VerticalScale` (`vertical-log-v1`): near 1:1 at sea level, logarithmic compression for high land (after the capped Mercator scale factor) and hard compression for ocean depth. Defaults fit Y −64..2031 with sea level 63.
- `EarthSurface`: combines the above and exposes `surfaceY(x, z)` plus an `identity()` string that a world must pin.
- `GlobeProbe LAT LON SIZE PIXELS OUT.png [ZOOM] [CACHE]`: shaded top-down preview of the mapped surface from live tiles.

`GlobeTest` serves synthetic tiles from a local HTTP server (single flight, retries, 404, offline disk cache, corrupt-cache repair, seamless bicubic, antimeridian wrap, bathymetry fill). `EARTH_LIVE_TILES=1` adds checks against the real dataset at Everest, the Challenger Deep, the Dead Sea and Denver.

## Coordinate contract: equirectangular-v1

1. Input is WGS84 geographic longitude/latitude in degrees. Longitude canonicalizes to [-180,180); +180 and -180 identify the same meridian.
2. Radius is exactly 6,378,137 m. X points east; Z points south; Y points up. Origin and standard parallel are fixed in the world manifest.
3. X = R × radians(wrapped(lon − originLon)) × cos(standardParallel).
4. Z = R × radians(originLat − lat).
5. One horizontal block is one **projected** metre. One vertical block is one metre in the declared height datum. This does not preserve global geodesic distances. On the spherical model, east-west ground metres per block are cos(latitude)/cos(standardParallel). The projection is locally scaled east-west at the standard parallel and increasingly distorted elsewhere.
6. Horizontal sampling uses the block centre (X+0.5,Z+0.5), including negative coordinates. Surface block Y is floor(source height). Its top face can differ from the continuous surface by up to one metre. Sea surface is at the Y=0 plane; the highest water cell is Y=-1.
7. Longitude sampling repeats after one projected circumference. Inverse projection chooses the nearest longitude copy to the origin. This is dataset wrapping, **not** a player-teleport/world-edge implementation. North/south requests beyond the poles fail explicitly.
8. A later atlas, local tangent-plane or other lower-distortion projection must receive a distinct projection ID and migration; never reinterpret an existing world's coordinates silently.

## Data and water contract

- EGP1's `west` and `north` are the first **sample centre**, not a cell corner. Steps are positive degrees east/south. A pack contains floats in north-to-south rows, X-fastest. Bilinear-safe coverage ends at outer sample centres; import a halo when tiling. Two duplicated overlap columns in the NOAA coastal split are tested numerically.
- Binary format: big-endian magic `0x45475031`, width int32, height int32, four float64 values (west, north, stepLon, stepLat), then width×height float32 metres. Exactly 44+4×width×height bytes. A decode must match its pinned manifest and hash.
- NaN/infinity, absent tiles, corrupt source hashes and unsupported projection/interpolation versions fail; they never become fabricated Earth terrain. At exact sample centres, a zero-weight no-data neighbour does not invalidate a valid sample.
- Manifest ordering is explicit precedence when raster footprints overlap. Separate datasets with different datums must be normalized before combination. This version has one dataset provenance record per pack; do not mix different sources in one manifest and pretend they share provenance.
- `surface-fill-v1` fills ordinary soil/rock below the scalar surface and water between negative terrain and sea level. This is a **sign-only water approximation**: below-sea-level inland depressions can be incorrectly flooded; rivers, lakes, dams, ice thickness and tidal variation are not modeled. A measured water mask is a future provider, not an implemented capability.
- Rock, soil, gravel and all subsurface contents are procedural. No measured global 3D geology, caves, ore distribution, buildings or roads are supplied.
- ETOPO2022 native 15-arc-second spacing is roughly 460 m north-south. Resampling to 1 m voxels does not create 1 m measured detail, nor preserve sharp peak/trench extrema. High-resolution overrides need their own versioned evidence, provenance, datum and license.

## APIs

`OfflineTerrain.load(Path manifest)` validates and preloads a bounded local pack. `elevationMetres(GeoPoint)`, `elevationMetres(long x,long z)`, `surfaceBlockY(long x,long z)`, and `cube(CubePos,MaterialPalette)` do no I/O afterward.

For streaming, construct `WorldManifest`, `CachedTileStore`, `RasterElevationProvider` and `GeographyCubeProvider` using a dedicated bounded I/O executor. Do not block a Minecraft tick thread waiting on futures. Admission exhaustion is an explicit recoverable failure; the caller may retry when demand decreases. Default column cache holds 256×256×8 = 524,288 payload bytes and up to 128 in-flight columns. Raster memory/disk limits are constructor settings; they account for raster payload/files, not all JVM/object/temporary request overhead. A cache directory is owned by a single store/process; multi-process cache locking is not implemented.

The default palette is air=0, stone=1, dirt=2, grass=3, water=4, gravel=5. These are abstract IDs. A Minecraft adapter must use registry IDs and `manifest.worldIdentity(actualPaletteVersion)` with a stable registry/palette fingerprint to avoid reinterpreting saved state IDs.

## Verification

From the project root with Java 21:

```
mkdir -p geography/build/test-classes
javac -d geography/build/test-classes $(find core/src/main/java geography/src/main/java geography/src/test/java -name '*.java')
java -ea -cp geography/build/test-classes io.earthplatform.geography.GeographyTest
java -ea -cp geography/build/test-classes io.earthplatform.geography.GeographyBenchmark
java -cp geography/build/test-classes io.earthplatform.geography.TerrainProbe geography/data/noaa-etopo2022-proof/world.properties 11.373 142.591
python geography/tools/test_noaa_import.py
```

Use the root build script when available. Tests clearly distinguish synthetic +8849/−11000 m lifecycle fixtures from the actual coarse real-data import. The benchmark separately reports a cold source-tile read, persistent-cache read, warm cube generation with no source rereads/network, and preloaded sampling. The cold measurement means an empty application tile cache reading a local source pack; the operating-system page cache is not flushed or controlled, and no Internet download is included. These are module measurements, not Minecraft FPS/TPS.

## Remaining platform work

Global tile discovery/import policies; stable spatial indexing for very large manifests; progressive COG readers and resolution selection; explicit bathymetry/water masks and blending; per-cell survey type/uncertainty; land cover, raw OSM vectors and regional building providers; vertical datum transforms; retry/backoff policy; multi-process caches; game lighting, physics, streaming/LOD/network compatibility; and an actual full-height Minecraft client/server runtime. A working Java geography test does not establish those features.
