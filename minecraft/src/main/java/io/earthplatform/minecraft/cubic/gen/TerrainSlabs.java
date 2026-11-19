package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import io.earthplatform.minecraft.platform.Loader;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Places Countered's optional half-block steps using the Earth generator's bordered height grid. */
public final class TerrainSlabs {
    public static final String ID = "countered-soil-steps-v1";
    private static final String MOD_ID = "terrain_slabs";

    private final Map<Block, BlockState> steps;

    TerrainSlabs(Map<Block, BlockState> steps) {
        this.steps = Map.copyOf(steps);
    }

    /** The upstream switch applies when a world is created, then the world keeps its choice. */
    public static boolean enabledForNewWorld() {
        if (!Loader.INSTANCE.isModLoaded(MOD_ID)) return false;
        try {
            // The hook is Countered's own class and has no mapped Minecraft parameters.
            Class<?> config = Class.forName("net.countered.terrainslabs.platform.PlatformConfigHooks");
            if (!Boolean.TRUE.equals(config.getMethod("isSlabGenerationEnabled").invoke(null))) return false;
            load();
            return true;
        } catch (IllegalStateException unsupported) {
            EarthPlatformMod.LOGGER.warn("Terrain Slabs does not have the blocks Earth needs for half-block steps");
            return false;
        } catch (ReflectiveOperationException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("Could not read Terrain Slabs generation settings; Earth will use full blocks", failure);
            return false;
        }
    }

    static TerrainSlabs load() {
        Map<Block, BlockState> steps = new HashMap<>();
        register(steps, Blocks.GRASS_BLOCK, "grass_slab");
        register(steps, Blocks.DIRT, "dirt_slab");
        register(steps, Blocks.COARSE_DIRT, "coarse_slab");
        register(steps, Blocks.PODZOL, "podzol_slab");
        register(steps, Blocks.MYCELIUM, "mycelium_slab");
        return new TerrainSlabs(steps);
    }

    private static void register(Map<Block, BlockState> steps, Block ground, String name) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, name));
        if (!(block instanceof SlabBlock slab) || !(slab.getStateDefinition().getProperty("generated") instanceof BooleanProperty generated)) {
            throw new IllegalStateException("This Earth world uses Countered's Terrain Slabs. Install Terrain Slabs 3.1.2 for Minecraft 1.21.1 to open it.");
        }
        steps.put(ground, slab.defaultBlockState().setValue(generated, true));
    }

    void decorate(LevelChunkSection section, int y0, int[] height, int[] water, Surface[] surface) {
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = (z + 1) * 18 + x + 1;
            int ground = height[i], stepY = ground + 1 - y0;
            if (stepY < 0 || stepY >= 16 || !section.getBlockState(x, stepY, z).isAir()) continue;
            Surface cover = surface[z * 16 + x];
            BlockState step = steps.get(cover.top().getBlock());
            if (step != null && cover.biome().value().getBaseTemperature() >= 0.15f && gentleEdge(height, water, i, ground)) {
                section.setBlockState(x, stepY, z, step, false);
            }
        }
    }

    private static boolean gentleEdge(int[] height, int[] water, int i, int ground) {
        int west = height[i - 1], east = height[i + 1], north = height[i - 18], south = height[i + 18];
        if (water[i] != SurfaceGenerator.DRY || water[i - 1] != SurfaceGenerator.DRY || water[i + 1] != SurfaceGenerator.DRY
                || water[i - 18] != SurfaceGenerator.DRY || water[i + 18] != SurfaceGenerator.DRY) return false;
        int low = Math.min(Math.min(west, east), Math.min(north, south));
        int high = Math.max(Math.max(west, east), Math.max(north, south));
        // Only a one-block rise with an approach from level ground. Leave cliffs and holes alone.
        return low == ground && high == ground + 1 && (west != east || north != south);
    }
}
