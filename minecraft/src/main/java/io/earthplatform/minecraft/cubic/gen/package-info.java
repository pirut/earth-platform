/**
 * Terrain generation shared by every cubic world: {@link io.earthplatform.minecraft.cubic.gen.SurfaceGenerator}
 * turns a ground and water height per column into cubes and far columns, and its helpers fill in what lies
 * under and on the ground: {@link io.earthplatform.minecraft.cubic.gen.Underground} (rock, caves, ores),
 * {@link io.earthplatform.minecraft.cubic.gen.Strata} and {@link io.earthplatform.minecraft.cubic.gen.Towers}
 * (rock layers and sheer hills), {@link io.earthplatform.minecraft.cubic.gen.Vegetation} and
 * {@link io.earthplatform.minecraft.cubic.gen.Trees} (plants and vanilla trees), and
 * {@link io.earthplatform.minecraft.cubic.gen.VanillaStructures} (vanilla's own structures, placed in cubes).
 *
 * <p>Generators run on many worker threads at once and must give the same blocks for the same seed and
 * place every time. Seed salts and hash functions here decide the terrain: changing one changes every
 * world. {@link io.earthplatform.minecraft.cubic.gen.HighlandsGenerator} is a small made-up world used
 * for engine tests; the Earth itself is in {@code cubic.earth}.
 */
package io.earthplatform.minecraft.cubic.gen;
