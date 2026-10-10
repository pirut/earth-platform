package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.lod.DhAdaptiveTerrainData;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingGateBoundaryTest {
    @Test void controlFramesRequireExactPositivePublicationResourceAndDigest(){
        String hash="a".repeat(64);
        assertDoesNotThrow(()->new RollingGateControlPayload(1,1,1,RollingGateControlPayload.PREPARED,hash,false));
        assertThrows(IllegalArgumentException.class,()->new RollingGateControlPayload(0,1,1,1,hash,false));
        assertThrows(IllegalArgumentException.class,()->new RollingGateControlPayload(1,0,1,1,hash,false));
        assertThrows(IllegalArgumentException.class,()->new RollingGateControlPayload(1,1,0,1,hash,false));
        assertThrows(IllegalArgumentException.class,()->new RollingGateControlPayload(1,1,1,1,"",false));
        assertThrows(IllegalArgumentException.class,()->new RollingGateControlPayload(1,1,1,1,"A".repeat(64),false));
        assertThrows(IllegalArgumentException.class,()->new RollingGateControlPayload(1,1,1,1,hash,true));
        assertDoesNotThrow(()->new RollingGateControlPayload(1,0,1,RollingGateControlPayload.FENCED,"",false));
        assertDoesNotThrow(()->new RollingGateControlPayload(1,1,1,RollingGateControlPayload.MOUSE,"",true));
    }
    @Test void controlCodecRoundTripsWithoutAliases(){
        var registry=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try{for(int kind=1;kind<=4;kind++){
            var value=new RollingGateControlPayload(8,4,2,kind,kind<=2?"f".repeat(64):"",kind==4);
            RollingGateControlPayload.CODEC.encode(registry,value);assertEquals(value,RollingGateControlPayload.CODEC.decode(registry));assertFalse(registry.isReadable());
        }}finally{registry.release();}
    }
    @Test void movingFarMaskPlusDisjointPinFitsExactSameHeightSurfaceBudget(){
        for(var profile:CoreGateProfile.values()){
            var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return profile.anchorY();}public int argb(long x,long z,long y){return 0xff808080;}public String identity(){return "rolling-mask-budget-v1";}};
            var data=new DhAdaptiveTerrainData(sampler,0,0);
            for(int[] offset:new int[][]{{0,0},{1,1},{3,-3},{8,8},{16,-16},{30,30},{-30,-30}}){
                var center=profile.center().offset(offset[0],0,offset[1]);var plan=RollingCubePlan.stationary(profile.center(),center);
                var ready=plan.committedTargets().stream().filter(profile::distantMaskEligible).toList();
                var batch=data.clip(ready);assertEquals(0,batch.unknownCells());assertTrue(batch.tiles().size()<=64);assertTrue(batch.faces()<=32768);
                var coveredColumns=new HashSet<String>();for(var p:ready)if(p.minBlockY()<profile.anchorY()+1&&profile.anchorY()+1<=p.minBlockY()+16)
                    for(long z=p.minBlockZ();z<p.minBlockZ()+16;z++)for(long x=p.minBlockX();x<p.minBlockX()+16;x++)if(x>=-512&&x<512&&z>=-512&&z<512)coveredColumns.add(x+":"+z);
                long area=0;for(var faces:batch.tiles().values())for(var face:faces){assertEquals(DhTerrainGeometry.Direction.UP,face.direction());assertEquals(profile.anchorY()+1,(long)face.pageY2048()*2048+face.y());area+=(long)face.width()*face.height();}
                assertEquals(1024L*1024-coveredColumns.size(),area);
                var fallback=data.clip(List.of(),ready);assertTrue(fallback.tiles().size()<=64);assertTrue(fallback.faces()<=32768);
                assertEquals(1024L*1024,fallback.tiles().values().stream().flatMap(List::stream).mapToLong(f->(long)f.width()*f.height()).sum());
            }
        }
    }
    @Test void pinInteriorExcludesChangesToNeighborOwnedSurfaceFaces(){
        for(var profile:CoreGateProfile.values()){
            var pin=profile.target();assertTrue(RollingCubePlan.isEditableInterior(pin,profile.commandEdit().getX(),profile.commandEdit().getY(),profile.commandEdit().getZ()));
            for(int axis=0;axis<3;axis++)for(int boundary:new int[]{0,15}){long[] pos={pin.minBlockX()+4,pin.minBlockY()+4,pin.minBlockZ()+4};pos[axis]=(axis==0?pin.minBlockX():axis==1?pin.minBlockY():pin.minBlockZ())+boundary;assertFalse(RollingCubePlan.isEditableInterior(pin,pos[0],pos[1],pos[2]));}
        }
    }
}
