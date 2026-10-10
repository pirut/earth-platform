package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual transformed Level + native BlockCollisions, with no vanilla chunk allocation. */
class CoreCollisionLevelHookTest {
    private static EnginePaletteContext palette;private static RegistryAccess registries;private static Holder<DimensionType> dimension;
    @BeforeAll static void registry(){var lookup=VanillaRegistries.createLookup();var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));var damage=new MappedRegistry<net.minecraft.world.damagesource.DamageType>(Registries.DAMAGE_TYPE,Lifecycle.stable());lookup.lookupOrThrow(Registries.DAMAGE_TYPE).listElements().forEach(h->damage.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));registries=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze(),damage.freeze())).freeze();palette=EnginePaletteContext.create(biomes);dimension=lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);}
    private static NativeCollisionView floor(int y){
        var provider=new AnalyticNativeCubeProvider(0,AnalyticNativeCubeProvider.Config.flat(y),palette);var codec=new NativeCubeCodec(provider.identity(),palette);var snapshots=new HashMap<CubePos,EngineCubeSnapshot>();long lease=1;
        for(var pos:CubeTickets.box(CubePos.fromBlock(4,y,4),1,1,27))snapshots.put(pos,codec.materialize(pos,provider.load(pos,new CancellationToken()).join(),1,lease++,0));
        return new NativeCollisionView(provider.identity(),1,palette,snapshots,()->true);
    }
    @Test void actualLevelCollisionPathSupportsBothNativeExtremesWithoutTallSections(){
        var level=new FacadeTestLevel(registries,dimension,-64,384);var access=(NativeCollisionLevelAccess)level;
        for(int y:new int[]{-11000,8849})try(var view=floor(y)){
            assertTrue(access.earthplatform$replaceCoreCollisionView(null,view));assertSame(view,level.getChunkForCollisions(0,0));
            var body=new AABB(4.2,y+1.2,4.2,4.8,y+3.0,4.8);var shapes=new ArrayList<VoxelShape>();level.getBlockCollisions(null,body.expandTowards(0,-.5,0)).forEach(shapes::add);
            assertEquals(-.2,Shapes.collide(Direction.Axis.Y,body,shapes,-.5),1e-9);
            assertEquals(384,level.getHeight());assertEquals(24,level.getSectionsCount());
            assertTrue(access.earthplatform$replaceCoreCollisionView(view,null));
        }
    }
    @Test void replacementIsExactOwnerScopedAndOrdinaryLevelStillUsesOriginalPath(){
        var one=new FacadeTestLevel(registries,dimension,-64,384);var two=new FacadeTestLevel(registries,dimension,-64,384);var a=(NativeCollisionLevelAccess)one;var b=(NativeCollisionLevelAccess)two;
        try(var first=floor(128);var second=floor(128)){
            assertTrue(a.earthplatform$replaceCoreCollisionView(null,first));assertFalse(a.earthplatform$replaceCoreCollisionView(second,null));assertSame(first,a.earthplatform$coreCollisionView());assertNull(b.earthplatform$coreCollisionView());
            assertThrows(UnsupportedOperationException.class,()->two.getChunkForCollisions(0,0));
            assertTrue(a.earthplatform$replaceCoreCollisionView(first,null));assertThrows(UnsupportedOperationException.class,()->one.getChunkForCollisions(0,0));
        }
    }
}
