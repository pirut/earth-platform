package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.NativeAlpineCubeProvider;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.lod.DhLodLayout;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Structural descriptor proof only. Actual source raster and renderer census are separate tests. */
class RollingAlpineDescriptorTest {
    static EnginePaletteContext palette;static WorldIdentity world;
    @BeforeAll static void registry(){
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(biomes.freeze());
        world=new WorldIdentity(EarthAlpineDefinition.SEED,NativeAlpineCubeProvider.GENERATOR_ID,NativeAlpineCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,EarthAlpineDefinition.DEFINITION_IDENTITY,NativeCubeCodec.paletteIdentity(palette));
    }
    static RollingSessionDescriptor descriptor(){return RollingSessionDescriptor.alpine(world,palette,EarthAlpineDefinition.ANCHOR_X,7314,EarthAlpineDefinition.ANCHOR_Z);}
    @Test void boundedSeparateEarthPolicyPreservesCaps(){
        var d=descriptor();assertTrue(d.alpineMode());assertTrue(d.playerFacing());assertTrue(d.naturalRelief());assertFalse(d.arenaMode());
        assertSame(DhLodLayout.EARTH_ALPINE_2048,d.lodLayout());assertEquals(512,d.bodyEnvelope().maxX()-d.bodyEnvelope().minX());
        assertEquals(512,d.bodyEnvelope().maxZ()-d.bodyEnvelope().minZ());assertEquals(d.pinCenter(),d.initialCenter());assertTrue(d.containsBody(d.walkEntryBody()));
        assertTrue(d.saveDirectory().startsWith("earthplatform-everest-v1/"));assertEquals(213,RollingCubePlan.MAX_STAGED_OWNERS);assertEquals(55,RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
    }
    @Test void exactSourceWindowEnvelopePreventsUncensusedThirtyThirdRow(){
        var d=descriptor();var p=EarthAlpineDefinition.ENVELOPE;var w=d.windowEnvelope();
        assertEquals(32,w.maxX()-w.minX()+1);assertEquals(32,w.maxZ()-w.minZ()+1);
        assertEquals(p.minCenterX(),w.minX());assertEquals(p.maxCenterXExclusive()-1,w.maxX());
        assertEquals(p.minCenterZ(),w.minZ());assertEquals(p.maxCenterZExclusive()-1,w.maxZ());
        for(int x:new int[]{w.minX(),w.maxX()})for(int z:new int[]{w.minZ(),w.maxZ()}){
            var c=new io.earthplatform.core.CubePos(x,d.pinCenter().y(),z);assertTrue(p.permitsCenter(c));assertDoesNotThrow(()->d.requireAllowedWindow(c));
            assertEquals(EarthAlpineDefinition.snapCubeCenter(x),RollingFarPublication.center(d,c).x());
            assertEquals(EarthAlpineDefinition.snapCubeCenter(z),RollingFarPublication.center(d,c).z());
        }
        assertThrows(IllegalArgumentException.class,()->d.requireAllowedWindow(new io.earthplatform.core.CubePos(w.maxX()+1,d.pinCenter().y(),w.minZ())));
        assertThrows(IllegalArgumentException.class,()->d.requireAllowedWindow(new io.earthplatform.core.CubePos(w.minX(),d.pinCenter().y(),w.minZ()-1)));
    }
    @Test void newPolicyRoundTripsWithoutRasterIO(){
        var d=descriptor();var b=new FriendlyByteBuf(Unpooled.buffer());try{d.write(b);b.writeInt(537);assertEquals(d,RollingSessionDescriptor.read(b,world,palette));assertEquals(537,b.readInt());assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void onlyOriginalInteriorIsEditable(){
        var d=descriptor();var p=d.pinCenter();
        assertTrue(d.permitsEdit(p.minBlockX()+1,p.minBlockY()+1,p.minBlockZ()+1));assertTrue(d.permitsEdit(p.minBlockX()+14,p.minBlockY()+14,p.minBlockZ()+14));
        for(int local:new int[]{0,15}){assertFalse(d.permitsEdit(p.minBlockX()+local,p.minBlockY()+2,p.minBlockZ()+2));assertFalse(d.permitsEdit(p.minBlockX()+2,p.minBlockY()+local,p.minBlockZ()+2));assertFalse(d.permitsEdit(p.minBlockX()+2,p.minBlockY()+2,p.minBlockZ()+local));}
    }
    @Test void wrongAnchorHeightAndSourceContractRefuse(){
        assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.alpine(world,palette,EarthAlpineDefinition.ANCHOR_X+1,7314,EarthAlpineDefinition.ANCHOR_Z));
        for(int ground:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,7312,7326,7327})assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.alpine(world,palette,EarthAlpineDefinition.ANCHOR_X,ground,EarthAlpineDefinition.ANCHOR_Z));
        var foreign=new WorldIdentity(world.seed(),"foreign",world.generatorVersion(),world.datasetVersion(),world.blockPaletteVersion());
        assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.alpine(foreign,palette,EarthAlpineDefinition.ANCHOR_X,7314,EarthAlpineDefinition.ANCHOR_Z));
    }
    @Test void entryOrientationUsesExactAlpinePolicyAndLeavesArenaUnchanged(){
        assertEquals((float)EarthAlpineDefinition.ENTRY_YAW,RollingGateCommands.entryYaw(descriptor()));assertEquals((float)EarthAlpineDefinition.ENTRY_PITCH,RollingGateCommands.entryPitch(descriptor()));
        assertEquals(-150,RollingGateCommands.entryYaw(RollingSessionDescriptor.arena(palette)));assertEquals(-8,RollingGateCommands.entryPitch(RollingSessionDescriptor.arena(palette)));
    }
}
