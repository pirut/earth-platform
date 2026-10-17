/**
 * The real Earth as a cubic world. {@link io.earthplatform.minecraft.cubic.earth.EarthGenerator} reads
 * elevation, land cover, climate, rivers and (optionally) imagery from {@code io.earthplatform.geography.globe}
 * and decides each column's height, water and surface; {@link io.earthplatform.minecraft.cubic.earth.EarthSettings}
 * is what a world is pinned to when it is made. {@link io.earthplatform.minecraft.cubic.earth.ClimateZone},
 * {@link io.earthplatform.minecraft.cubic.earth.Ecoregions} and {@link io.earthplatform.minecraft.cubic.earth.ImageryGround}
 * pick biomes and ground; {@link io.earthplatform.minecraft.cubic.earth.ImageryCalibration} is a developer tool
 * behind {@code /cubes calibrate}.
 */
package io.earthplatform.minecraft.cubic.earth;
