package io.earthplatform.minecraft;
import com.mojang.serialization.Lifecycle;
import com.mojang.authlib.GameProfile;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class CorePlayerVoidHookTest {
    private static EnginePaletteContext palette;private static RegistryAccess registries;private static Holder<DimensionType> dimension;
    @BeforeAll static void registry(){var lookup=VanillaRegistries.createLookup();var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));var damage=new MappedRegistry<net.minecraft.world.damagesource.DamageType>(Registries.DAMAGE_TYPE,Lifecycle.stable());lookup.lookupOrThrow(Registries.DAMAGE_TYPE).listElements().forEach(h->damage.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));registries=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze(),damage.freeze())).freeze();palette=EnginePaletteContext.create(biomes);dimension=lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);}

    static class ProbePlayer extends Player {
        int voidCalls;
        ProbePlayer(Level level,UUID id){super(level,BlockPos.ZERO,0,new GameProfile(id,"void-probe"));}
        @Override public boolean isSpectator(){return false;}
        @Override public boolean isCreative(){return false;}
        @Override protected void onBelowWorld(){voidCalls++;}
    }
    record View(UUID exact) implements BlockGetter,NativePlayerVoidSafety {
        public boolean preventsStockVoidCheck(UUID id,AABB box){return exact.equals(id)&&box.minY>=-11008&&box.maxY<=8864;}
        public BlockState getBlockState(BlockPos p){return Blocks.AIR.defaultBlockState();}
        public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public BlockEntity getBlockEntity(BlockPos p){return null;}
        public int getHeight(){return 19872;}
        public int getMinBuildHeight(){return -11008;}
    }
    @Test void actualTransformedVoidCheckHonorsExactPlayerAndLevelOnly(){
        var level=new FacadeTestLevel(registries,dimension,-64,384);var id=UUID.randomUUID();var one=new ProbePlayer(level,id);var other=new ProbePlayer(level,UUID.randomUUID());one.setPos(4,-10999,4);other.setPos(4,-10999,4);
        one.checkBelowWorld();assertEquals(1,one.voidCalls);var access=(NativeCollisionLevelAccess)level;var view=new View(id);assertTrue(access.earthplatform$replaceCoreCollisionView(null,view));one.checkBelowWorld();assertEquals(1,one.voidCalls);other.checkBelowWorld();assertEquals(1,other.voidCalls);
        one.setPos(4,-11009,4);one.checkBelowWorld();assertEquals(2,one.voidCalls);assertTrue(access.earthplatform$replaceCoreCollisionView(view,null));one.setPos(4,-10999,4);one.checkBelowWorld();assertEquals(3,one.voidCalls);assertEquals(384,level.getHeight());assertEquals(24,level.getSectionsCount());
    }
    @Test void positiveEndpointNeverNeededTheStockBelowWorldException(){var level=new FacadeTestLevel(registries,dimension,-64,384);var p=new ProbePlayer(level,UUID.randomUUID());p.setPos(4,8850,4);p.checkBelowWorld();assertEquals(0,p.voidCalls);}
    @Test void actualBindingKeepsZeroOwnerSafetyThroughFenceAndReleasesOnlyOnDetach()throws Exception{
        String key="earthplatform.experimental.coreCollisionGate",old=System.getProperty(key);System.setProperty(key,"true");
        try{
            var level=new FacadeTestLevel(registries,dimension,-64,384){@Override public net.minecraft.resources.ResourceKey<Level> dimension(){return io.earthplatform.minecraft.scene.SceneCommands.CARRIER;}};
            var type=Class.forName("io.earthplatform.minecraft.coregate.CoreCollisionBinding");var ctor=type.getDeclaredConstructor(Level.class,UUID.class,long.class);ctor.setAccessible(true);var id=UUID.randomUUID();var binding=ctor.newInstance(level,id,1L);var safety=(NativePlayerVoidSafety)binding;var body=new AABB(4.2,-10999,-1.8,4.8,-10997.2,-1.2);
            var fence=type.getDeclaredMethod("fence");fence.setAccessible(true);var detach=type.getDeclaredMethod("detach");detach.setAccessible(true);var resident=type.getDeclaredMethod("residentOwners");resident.setAccessible(true);
            assertTrue(safety.preventsStockVoidCheck(id,body));fence.invoke(binding);assertEquals(0,resident.invoke(binding));assertTrue(safety.preventsStockVoidCheck(id,body),"Reload/edit/failed return cannot reinstate stock void damage");
            assertTrue(((BlockGetter)binding).getBlockState(new BlockPos(4,-10999,4)).is(Blocks.BARRIER));
            detach.invoke(binding);detach.invoke(binding);assertFalse(safety.preventsStockVoidCheck(id,body));assertNull(((NativeCollisionLevelAccess)level).earthplatform$coreCollisionView());
        }finally{if(old==null)System.clearProperty(key);else System.setProperty(key,old);}
    }

}
