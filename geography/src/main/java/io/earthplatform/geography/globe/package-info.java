/**
 * Real elevation for any place on Earth, streamed on demand, for the cubic world's Earth
 * generator. It is separate from the pinned offline packs elsewhere in this module.
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
 *   <li>{@link io.earthplatform.geography.globe.UsgsElevation} ({@code 3dep-13-v1}, read lighter far out as {@code 3dep-13-v2}): the United
 *       States from the USGS 3DEP 1/3 arc-second DEM, each 1° cell pinned to a dated release.</li>
 *   <li>{@link io.earthplatform.geography.globe.FloatCogs}: range reads of float cloud-optimised
 *       GeoTIFFs (LZW, classic or BigTIFF) with a disk cache, shared by the layers below.</li>
 *   <li>{@link io.earthplatform.geography.globe.RemaElevation} ({@code rema-v2.0}): Antarctica from
 *       the Reference Elevation Model of Antarctica, read block by block from
 *       {@link io.earthplatform.geography.globe.RemaSource} and brought to the EGM96 geoid.</li>
 *   <li>{@link io.earthplatform.geography.globe.GlobalElevation}: Catmull-Rom bicubic in true
 *       metres across tile seams and the antimeridian. Above zoom 10 open sea is stored as 0 m, so
 *       those pixels take zoom-10 bathymetry.</li>
 *   <li>{@link io.earthplatform.geography.globe.GlobeProbe}: a shaded top-down preview from live
 *       tiles.</li>
 * </ul>
 *
 * <p>Attribution for the tiles is in {@code THIRD_PARTY_NOTICES.md} and
 * {@link io.earthplatform.geography.globe.TerrariumTileSource#ATTRIBUTION}.
 */
package io.earthplatform.geography.globe;
