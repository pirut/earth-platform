package io.earthplatform.minecraft.coregate;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class CoreEndpointPipelineTest {
    static EnginePaletteContext palette;
    @BeforeAll static void registry(){var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(r.freeze());}
    @Test void exactEndpoint125CubeNetworkReplicaHasRealPaddingAndUnchangedCollisionDomain(){for(var p:new CoreGateProfile[]{CoreGateProfile.ABYSS,CoreGateProfile.SUMMIT}){
        var provider=p.provider(palette);var codec=new NativeCubeCodec(provider.identity(),palette);var owners=p.owners();var snapshots=new HashMap<CubePos,EngineCubeSnapshot>();
        try(var replica=new CoreGateReplica(provider.identity(),1,palette,owners,java.util.stream.LongStream.rangeClosed(1,125).boxed().toList(),p.meshTargets(),p)){
            for(var pos:owners){var original=codec.materialize(pos,provider.load(pos,new CancellationToken()).join(),1,owners.indexOf(pos)+1,0);var b=CoreGatePayload.create(original::write).open();try{var decoded=EngineCubeSnapshot.readFrame(b,provider.identity(),1,palette);replica.accept(decoded);snapshots.put(pos,decoded);}finally{b.release();}}
            assertEquals(125,replica.cubes().size());assertEquals(0,replica.revision());
            var neighborhood=new SceneNeighborhood(replica.cubes(),p.renderEnvelope());assertEquals(p.renderMinY(),neighborhood.getMinBuildHeight());assertEquals(p.renderMaxYExclusive(),neighborhood.getMaxBuildHeight());
            assertTrue(neighborhood.getBlockState(new BlockPos(4,p.anchorY(),4)).is(Blocks.STONE));assertTrue(neighborhood.getBlockState(p.commandEdit()).isAir());
            try(var collision=new NativeCollisionView(provider.identity(),1,palette,snapshots,()->true)){assertTrue(collision.getBlockState(new BlockPos(4,p.anchorY(),4)).is(Blocks.STONE));assertTrue(collision.getBlockState(new BlockPos(4,-11009,4)).is(Blocks.BARRIER));assertTrue(collision.getBlockState(new BlockPos(4,8864,4)).is(Blocks.BARRIER));assertEquals(125,collision.residentOwners());}
            assertThrows(IllegalArgumentException.class,()->new SceneNeighborhood(replica.cubes()));
        }
    }}
    @Test void defaultSceneEnvelopeStillRejectsEndpointPadding(){for(var p:new CoreGateProfile[]{CoreGateProfile.ABYSS,CoreGateProfile.SUMMIT}){var provider=p.provider(palette);var codec=new NativeCubeCodec(provider.identity(),palette);var outside=p.owners().stream().filter(pos->!p.distantMaskEligible(pos)).findFirst().orElseThrow();var snapshot=codec.materialize(outside,provider.load(outside,new CancellationToken()).join(),1,1,0);assertThrows(IllegalArgumentException.class,()->new SceneCube(snapshot,palette));assertDoesNotThrow(()->new SceneCube(snapshot,palette,p.renderEnvelope()));}}
    @Test void wrongEndpointMetadataCannotEnterAnotherProfile(){var p=CoreGateProfile.ABYSS;var leases=java.util.stream.LongStream.rangeClosed(1,125).boxed().toList();assertThrows(IllegalArgumentException.class,()->new CoreGateReplica(p.world(palette),1,palette,p.owners(),leases,p.meshTargets(),CoreGateProfile.SUMMIT));}
    @Test void signedFarPagesMaskExactlyTheSameGroundFootprintWithoutPaddingSuppression(){for(var p:new CoreGateProfile[]{CoreGateProfile.ABYSS,CoreGateProfile.SUMMIT}){
        var source=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return p.anchorY();}public int argb(long x,long z,long y){return 0xff808080;}public String identity(){return p.fingerprint(palette);}};
        var data=new io.earthplatform.minecraft.lod.DhAdaptiveTerrainData(source,0,0);var masked=data.clip(p.distantMaskTargets());var unmasked=data.clip(List.of(),p.distantMaskTargets());
        assertTrue(masked.tiles().size()<=64);assertTrue(masked.faces()<=32768);assertEquals(0,masked.unknownCells());assertEquals(0,masked.transitionFaces());
        long area=0;for(var faces:masked.tiles().values())for(var face:faces){assertEquals(io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction.UP,face.direction());assertEquals(p.anchorY()+1,(long)face.pageY2048()*2048+face.y());area+=(long)face.width()*face.height();}
        assertEquals(1024L*1024-48L*48,area);
        assertEquals(1024L*1024,unmasked.tiles().values().stream().flatMap(List::stream).mapToLong(f->(long)f.width()*f.height()).sum());
    }}

}
