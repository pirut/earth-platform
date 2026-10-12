package io.earthplatform.minecraft.survival.host;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.world.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalBlockViewTest {
    private static SurvivalPalette palette;
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
    }
    private static NativeSurvivalWorld owner() {
        return new NativeSurvivalWorld(new WorldIdentity(39,"survival-test","v1","test",palette.identity()),palette,
                new NativeSurvivalWorld.Bounds(new CubePos(-1000000,-688,-1000000),new CubePos(1000000,553,1000000)),
                new NativeSurvivalWorld.Limits(8,8,2,4,4096,32,16,65536,8,8));
    }
    @Test void actualBlockGetterKeepsPackedYAliasesDistinct() {
        var owner=owner();var a=new BlockPos(-17,7314,-17);var b=new BlockPos(-17,3218,-17);
        owner.admitGenerated(CubePos.fromBlock(a.getX(),a.getY(),a.getZ()),ImmutableCube.uniform(palette.id(Blocks.OAK_LOG.defaultBlockState())));
        owner.admitGenerated(CubePos.fromBlock(b.getX(),b.getY(),b.getZ()),ImmutableCube.uniform(palette.id(Blocks.CRAFTING_TABLE.defaultBlockState())));
        var view=new SurvivalBlockView(owner);assertEquals(a.asLong(),b.asLong());
        assertSame(Blocks.OAK_LOG.defaultBlockState(),view.getBlockState(a));assertSame(Blocks.CRAFTING_TABLE.defaultBlockState(),view.getBlockState(b));
        assertEquals(-11008,view.getMinBuildHeight());assertEquals(19872,view.getHeight());
    }
    @Test void nativeCollisionAndFluidQueriesUseTheSameRegisteredState() {
        var owner=owner();var pos=new BlockPos(-136,135,-421);var cube=CubePos.fromBlock(pos.getX(),pos.getY(),pos.getZ());
        owner.admitGenerated(cube,ImmutableCube.uniform(palette.id(Blocks.OAK_LEAVES.defaultBlockState())));var view=new SurvivalBlockView(owner);
        var state=view.getBlockState(pos);assertFalse(state.getCollisionShape(view,pos).isEmpty());assertTrue(view.getFluidState(pos).isEmpty());assertNull(view.getBlockEntity(pos));assertTrue(view.isResident(pos));
    }
    @Test void missingOrRetiredCubesNeverBecomeAirOrSuccessfulBlockEntityAbsence() {
        var owner=owner();var view=new SurvivalBlockView(owner);var pos=new BlockPos(-1,134,-1);var cube=CubePos.fromBlock(-1,134,-1);
        assertFalse(view.isResident(pos));assertThrows(SurvivalRefusal.class,()->view.getBlockState(pos));assertThrows(SurvivalRefusal.class,()->view.getBlockEntity(pos));
        owner.admitGenerated(cube,ImmutableCube.uniform(palette.id(Blocks.STONE.defaultBlockState())));owner.retire(cube);
        assertFalse(view.isResident(pos));assertThrows(SurvivalRefusal.class,()->view.getFluidState(pos));
    }
    @Test void activeNativeNeighborReadSeesStagedWriteAndAbortRestoresView() {
        var owner=owner();var pos=new BlockPos(-1,134,-1);var cube=CubePos.fromBlock(-1,134,-1);
        owner.admitGenerated(cube,ImmutableCube.uniform(palette.id(Blocks.STONE.defaultBlockState())));var view=new SurvivalBlockView(owner);
        try(var tx=owner.begin(owner.actionIdentity(new UUID(1,1),1),Map.of(cube,owner.stamp(cube)))) {
            tx.set(CubeCell.of(-1,134,-1),Blocks.CRAFTING_TABLE.defaultBlockState());assertSame(Blocks.CRAFTING_TABLE.defaultBlockState(),view.getBlockState(pos));
        }
        assertSame(Blocks.STONE.defaultBlockState(),view.getBlockState(pos));
    }
}
