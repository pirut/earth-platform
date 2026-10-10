package io.earthplatform.minecraft;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeuristicSurfaceTest {
    @Test void rockAndSnowUseTheActualColumnSurfaceAtDocumentedBoundaries() {
        assertTrue(HeuristicSurface.map(3, 2999).is(Blocks.GRASS_BLOCK));
        assertTrue(HeuristicSurface.map(2, 2999).is(Blocks.DIRT));
        assertTrue(HeuristicSurface.map(3, 3000).is(Blocks.STONE));
        assertTrue(HeuristicSurface.map(2, 3000).is(Blocks.STONE));
        assertTrue(HeuristicSurface.map(3, 4799).is(Blocks.STONE));
        assertTrue(HeuristicSurface.map(3, 4800).is(Blocks.SNOW_BLOCK));
        assertTrue(HeuristicSurface.map(2, 4800).is(Blocks.STONE));
        assertTrue(HeuristicSurface.map(3, 8491).is(Blocks.SNOW_BLOCK));
    }

    @Test void seabedDepthSelectsOnlyAnExplicitHeuristicMaterial() {
        assertTrue(HeuristicSurface.map(5, -1).is(Blocks.SAND));
        assertTrue(HeuristicSurface.map(5, -32).is(Blocks.SAND));
        assertTrue(HeuristicSurface.map(5, -33).is(Blocks.GRAVEL));
        assertTrue(HeuristicSurface.map(5, -10512).is(Blocks.GRAVEL));
    }

    @Test void presentationNeverCreatesOrRemovesSolidOrWaterVoxels() {
        for (long ground : new long[]{-11000, -33, -32, -1, 0, 2999, 3000, 4799, 4800, 8849}) {
            assertTrue(HeuristicSurface.map(0, ground).isAir());
            assertTrue(HeuristicSurface.map(4, ground).is(Blocks.WATER));
            for (int id : new int[]{1, 2, 3, 5}) {
                var state = HeuristicSurface.map(id, ground);
                assertFalse(state.isAir());
                assertTrue(state.getFluidState().isEmpty());
                assertFalse(state.is(Blocks.SNOW), "Snow layers would alter the sampled voxel geometry");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> HeuristicSurface.map(6, 0));
        assertThrows(IllegalArgumentException.class, () -> HeuristicSurface.map(-1, 0));
    }
}
