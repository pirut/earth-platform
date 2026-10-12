package io.earthplatform.minecraft.survival.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDryMutationRouterTest {
    @Test void currentNativeDryPaletteHasOnlyAdmittedNonShapeCallbacks() {
        for (Block block : new Block[]{Blocks.AIR,Blocks.GRASS_BLOCK,Blocks.DIRT,Blocks.STONE,
                Blocks.COBBLESTONE,Blocks.OAK_LOG,Blocks.OAK_LEAVES,Blocks.OAK_PLANKS,Blocks.CRAFTING_TABLE})
            for (BlockState state : block.getStateDefinition().getPossibleStates())
                if (state.getFluidState().isEmpty()) assertDoesNotThrow(() -> NativeDryMutationRouter.auditState(state));
    }
    @Test void nativeBlockEntityAndWetStatesRefuseBeforeCallbacks() {
        assertThrows(SurvivalRefusal.class, () -> NativeDryMutationRouter.auditState(Blocks.CHEST.defaultBlockState()));
        assertThrows(SurvivalRefusal.class, () -> NativeDryMutationRouter.auditState(Blocks.WATER.defaultBlockState()));
    }
    private static final class CustomPlace extends Block {
        CustomPlace() { super(BlockBehaviour.Properties.of()); }
        @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            throw new AssertionError("Must never invoke the unadmitted callback");
        }
    }
    private static final class CustomNeighbor extends Block {
        CustomNeighbor() { super(BlockBehaviour.Properties.of()); }
        @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos other, boolean moving) {
            throw new AssertionError("Must never invoke the unadmitted callback");
        }
    }
    @Test void onPlaceOverrideIsNotMistakenForInheritedNoOp() {
        assertThrows(SurvivalRefusal.class, () -> NativeDryMutationRouter.auditCallbackType(CustomPlace.class));
    }
    @Test void neighborOverrideIsNotMistakenForDebugOnlyBase() {
        assertThrows(SurvivalRefusal.class, () -> NativeDryMutationRouter.auditCallbackType(CustomNeighbor.class));
    }
    @Test void neighborCoordinatesNeverWrapAtNativeIntEdges() {
        assertThrows(ArithmeticException.class, () -> NativeDryMutationRouter.neighbor(new BlockPos(Integer.MAX_VALUE, 0, 0), Direction.EAST));
        assertThrows(ArithmeticException.class, () -> NativeDryMutationRouter.neighbor(new BlockPos(0, Integer.MIN_VALUE, 0), Direction.DOWN));
        assertThrows(ArithmeticException.class, () -> NativeDryMutationRouter.neighbor(new BlockPos(0, 0, Integer.MAX_VALUE), Direction.SOUTH));
        assertEquals(-13640056, NativeDryMutationRouter.neighbor(new BlockPos(-13640057, 134, -4213188), Direction.EAST).x());
    }
}
