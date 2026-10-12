package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class NativeDryPlacementPlanTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { net.minecraft.data.registries.VanillaRegistries.createLookup(); }
    @Test void pinnedCraftingTablePlacementDescriptorsAreNative() { assertDoesNotThrow(NativeDryPlacementPlan::requireCallbacks); }
    private static BlockHitResult hit(BlockPos pos,Direction direction) { return new BlockHitResult(Vec3.atCenterOf(pos),direction,pos,false); }
    @Test void checkedNativeNeighborPreservesHighAndNegativeCoordinates() {
        assertEquals(new BlockPos(-13000000,2048,-4100000),NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(-13000000,2047,-4100000),Direction.UP)));
    }
    @Test void everySignedOverflowEndpointRefusesBeforeNativeContext() {
        assertThrows(SurvivalRefusal.class,()->NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(Integer.MAX_VALUE,0,0),Direction.EAST)));
        assertThrows(SurvivalRefusal.class,()->NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(Integer.MIN_VALUE,0,0),Direction.WEST)));
        assertThrows(SurvivalRefusal.class,()->NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(0,Integer.MAX_VALUE,0),Direction.UP)));
        assertThrows(SurvivalRefusal.class,()->NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(0,Integer.MIN_VALUE,0),Direction.DOWN)));
        assertThrows(SurvivalRefusal.class,()->NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(0,0,Integer.MAX_VALUE),Direction.SOUTH)));
        assertThrows(SurvivalRefusal.class,()->NativeDryPlacementPlan.checkedAdjacent(hit(new BlockPos(0,0,Integer.MIN_VALUE),Direction.NORTH)));
    }
}
