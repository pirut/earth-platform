package io.earthplatform.minecraft.survival.render;

import java.util.HashSet;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact registry fixture contents only, not loaded-model or render-layer evidence. */
class SurvivalNativePageParityFixtureTest {
    @Test void samplesIncludeEveryLogAxisBothLeafPersistenceValuesAndAllLeafDistances() {
        var samples=SurvivalNativePageParityFixture.samples();assertEquals(25,samples.size());assertEquals(25,new HashSet<>(samples).size());
        for(var axis:Direction.Axis.values())assertTrue(samples.contains(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS,axis)));
        for(boolean persistent:new boolean[]{false,true})for(int distance=1;distance<=7;distance++)
            assertTrue(samples.contains(Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,persistent)
                    .setValue(BlockStateProperties.DISTANCE,distance)));
        for(var state:samples) {
            assertSame(state,Block.BLOCK_STATE_REGISTRY.byId(Block.BLOCK_STATE_REGISTRY.getId(state)));
            assertTrue(state.getFluidState().isEmpty());assertFalse(state.hasBlockEntity());
        }
        assertThrows(UnsupportedOperationException.class,()->samples.clear());
    }
    @Test void realCrossModelsGrassAndTableRemainExplicitRatherThanRelabeledAsOpaqueCapsules() {
        var samples=SurvivalNativePageParityFixture.samples();
        for(var block:java.util.List.of(Blocks.OAK_SAPLING,Blocks.SHORT_GRASS,Blocks.GRASS_BLOCK,Blocks.CRAFTING_TABLE))
            assertTrue(samples.stream().anyMatch(state->state.is(block)));
    }
}
