/**
 * Other mods in the cubic world. Most mods read vanilla chunks, which here are empty carriers, so these
 * classes answer their reads from the cubes: map mods ({@link io.earthplatform.minecraft.cubic.compat.CubicMaps},
 * JourneyMap), Chunky pre-generation, Distant Horizons (switched off over the Earth's own far terrain),
 * Sable and Create Aeronautics, AE2 spatial storage and other mods' dimension portals. Optional mods are
 * reached by reflection or through mixins in {@code cubic.mixin.compat}, so none of them is needed to run.
 */
package io.earthplatform.minecraft.cubic.compat;
