package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DhPreparedReplacementSetTest {
    static final String SOURCE=DhAnalyticFlatPolicy.expectedDataset(128);
    static final WorldIdentity WORLD=new WorldIdentity(0,"earthplatform:analytic-native-cube","analytic-native-cube-v1/rolling-interior-v1",SOURCE,"test-palette");
    static final DhAnalyticFlatPolicy POLICY=new DhAnalyticFlatPolicy(SOURCE,WORLD,128,0xff808080);
    static final ReplacementWorkspaces WORKSPACES=ReplacementWorkspaces.two(new CubePos(0,8,0));
    static final long EPOCH=4,RESOURCE=2;
    static NativeReplacementCapture capture(CubePos p,boolean checker){
        byte[] values=new byte[8000];int index=0;
        for(long y=p.minBlockY()-2;y<p.minBlockY()+18;y++)for(long z=p.minBlockZ()-2;z<p.minBlockZ()+18;z++)for(long x=p.minBlockX()-2;x<p.minBlockX()+18;x++){
            boolean inside=CubePos.fromBlock(x,y,z).equals(p);
            values[index++]=(byte)(checker&&inside?((x+y+z)&1)==0?1:0:y<=128?1:(p.x()==0&&x==4&&y==132&&z==4?2:0));
        }
        return new NativeReplacementCapture(p,values);
    }
    static List<NativeReplacementCapture> captures(){return WORKSPACES.positions().stream().map(p->capture(p,false)).toList();}
    static ReplacementSetCertificate certificate(List<NativeReplacementCapture> captures,long generation,long revA,long revB,String indexIdentity){
        var regions=new ArrayList<ReplacementSetCertificate.Region>();long lease=1;
        for(int i=0;i<2;i++){
            var c=captures.get(i);var deps=new ArrayList<RollingCubeActor.CubeVersion>();
            for(var p:WORKSPACES.dependencies(c.position()))deps.add(new RollingCubeActor.CubeVersion(p,lease++,p.equals(c.position())?(i==0?revA:revB):0));
            regions.add(new ReplacementSetCertificate.Region(c.position(),deps,c.contentIdentity()));
        }
        return new ReplacementSetCertificate(WORLD,EPOCH,RESOURCE,"00000000-0000-0000-0000-000000000001",indexIdentity,"provider","a".repeat(64),DhReplacementInput.ANALYTIC_MATERIAL_POLICY,"exact-owned18-read20-unit-faces-v1",generation,regions);
    }
    static long center(int x){return 256L*Math.floorDiv(16*x+8+128,256);}

}
