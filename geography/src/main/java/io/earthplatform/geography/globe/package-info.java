/**
 * Real elevation, land cover, climate and water for any place on Earth, streamed on demand, for the
 * cubic world's Earth generator. Nothing here depends on Minecraft.
 *
 * <ul>
 *   <li>{@link io.earthplatform.geography.globe.MercatorGrid} ({@code web-mercator-v1}): X east,
 *       Z south, origin at 0°/0°, {@code metresPerBlock} projected metres per block. Conformal, so
 *       shapes keep their proportions; beyond ±85.05° fails explicitly.</li>
 *   <li>{@link io.earthplatform.geography.globe.TerrariumTileSource} ({@code terrarium-v1}): AWS
 *       Terrain Tiles PNGs, single-flight per tile, bounded concurrent transfers with retry, a
 *       decoded-tile LRU, an atomic disk cache under {@code z/x/y.png} and an offline mode. Callers
 *       block, so use it from generation workers, never a tick thread.</li>
 *   <li>{@link io.earthplatform.geography.globe.ElevationLayer}: a better source laid over each
 *       cleaned tile, in order, pinned per world in {@code earth.elevation}.</li>
 *   <li>{@link io.earthplatform.geography.globe.UsgsElevation} ({@code 3dep-13-v1}, read lighter far out as {@code 3dep-13-v2} and {@code -v3}): the United
 *       States from the USGS 3DEP 1/3 arc-second DEM, each 1° cell pinned to a dated release.</li>
 *   <li>{@link io.earthplatform.geography.globe.FloatCogs}: range reads of float cloud-optimized
 *       GeoTIFFs (LZW, classic or BigTIFF) with a disk cache, shared by the layers below.</li>
 *   <li>{@link io.earthplatform.geography.globe.RemaElevation} ({@code rema-v2.0}): Antarctica from
 *       the Reference Elevation Model of Antarctica, read block by block from
 *       {@link io.earthplatform.geography.globe.RemaSource} and brought to the EGM96 geoid.</li>
 *   <li>{@link io.earthplatform.geography.globe.HrdemElevation} ({@code hrdem-2m-v1}): Canada's lidar
 *       from NRCan's HRDEM 2 m mosaic on Canada Atlas Lambert, read at 8 m and coarser near the player.</li>
 *   <li>{@link io.earthplatform.geography.globe.MercatorTileLayer}: national sources served on the
 *       composite's own Web Mercator grid, asked only inside their
 *       {@link io.earthplatform.geography.globe.TileCoverage} lists:
 *       {@link io.earthplatform.geography.globe.IgnElevation} ({@code ign-rgealti-v1}, France) and
 *       {@link io.earthplatform.geography.globe.GsiElevation} ({@code gsi-dem-v1}, Japan).</li>
 *   <li>{@link io.earthplatform.geography.globe.GlobalElevation}: Catmull-Rom bicubic in true
 *       metres across tile seams and the antimeridian. Above zoom 10 open sea is stored as 0 m, so
 *       those pixels take zoom-10 bathymetry.</li>
 * </ul>
 *
 * <p>{@code GlobeProbe} and {@code ImageryProbe} in the test sources draw top-down previews of a
 * place from live data, for checking changes by eye.
 *
 * <p>Attribution for the tiles is in {@code THIRD_PARTY_NOTICES.md} and
 * {@link io.earthplatform.geography.globe.TerrariumTileSource#ATTRIBUTION}.
 */
package io.earthplatform.geography.globe;
