package io.earthplatform.minecraft.visual;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.lod.*;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhNativeDistanceTerrainDataTest {
    static final String HASH="a".repeat(64);
    static final EarthDistanceSurfacePlan.Limits PLAN=new EarthDistanceSurfacePlan.Limits(2_000_000,128L<<20,2_000_000,5000,10000,20_000_000,8L<<30,128L<<20);
    static final DhNativeDistanceTerrainData.Limits FAR=new DhNativeDistanceTerrainData.Limits(100_000,500_000,5000,4096,128L<<20);
    static long height(long x,long z,boolean cliff){return cliff&&x>=0?130:0;}
    static EarthDistanceSurfacePlan plan(boolean cliff){
        var e=new EarthAlpineDefinition.Envelope(0,1,0,1,-10,10,-4096,4096,-4096,4096);
        var source=EarthDistanceSurfacePlan.Source.prepareSampled(e,-512,512,HASH,HASH,HASH,2,0,PLAN,
                (x,z)->new EarthDistanceSurfacePlan.Sample(height(x,z,cliff),1,1,0,0,height(x,z,cliff),0));
        return EarthDistanceSurfacePlan.build(source,null,new CubePos(0,0,0),2,0,PLAN);
    }
    static SceneSurfaceSampler sampler(boolean cliff){return new SceneSurfaceSampler(){
        public long surfaceY(long x,long z){return height(x,z,cliff);}
        public int argb(long x,long z,long y){return 0xff8a8a8a;}
        public String identity(){return HASH;}
        public boolean supportsFootprintCoverage(){return true;}
        public boolean knownFootprint(long x,long z,long x1,long z1){return true;}
        public boolean supportsStratifiedSides(){return true;}
        public ColumnMaterials columnMaterials(long x,long z,long y){var m=new SurfaceMaterial(0xff8a8a8a,2);return new ColumnMaterials(m,m,m,3);}
    };}
    static DhNativeDistanceTerrainData data(SourceSurfaceCoverage c,boolean cliff){return new DhNativeDistanceTerrainData(sampler(cliff),0,0,DhLodLayout.EARTH_ALPINE_2048,c,FAR);}
    @Test void fullInteriorIsSkippedAndFlatPerimeterHasExactlyOneOwner(){
        var plan=plan(false);var c=SourceSurfaceCoverage.complete(plan,plan.pageIndex().keySet());
        var batch=data(c,false).compose(null,null,()->{});assertEquals(0,batch.unknownCells());
        assertTrue(batch.sampledCells()<20_000);
        int[][] owners=new int[133][133];
        for(int z=0;z<133;z++)for(int x=0;x<133;x++)owners[z][x]=c.covers(x-66,0,z-66)?1:0;
        for(var entry:batch.tiles().entrySet())for(var f:entry.getValue())if(f.direction()==DhTerrainGeometry.Direction.UP){
            long fx=entry.getKey().originX()+f.x(),fz=entry.getKey().originZ()+f.z();
            for(long z=Math.max(-66,fz);z<Math.min(67,fz+f.height());z++)for(long x=Math.max(-66,fx);x<Math.min(67,fx+f.width());x++)owners[(int)z+66][(int)x+66]++;
        }
        for(int z=0;z<133;z++)for(int x=0;x<133;x++)assertEquals(1,owners[z][x],"Ownership at "+(x-66)+","+(z-66));
    }
    @Test void partialTallCliffCutsOnlyItsExactSignedPage(){
        var plan=plan(true);var keys=new TreeSet<NativePageKey>();for(var key:plan.pageIndex().keySet())if(key.y()==1)keys.add(key);
        var c=SourceSurfaceCoverage.restricted(plan,keys);assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(0,0,1,1));
        var face=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.WEST,0,1,0,1,130,0xff8a8a8a,2);
        var pieces=data(c,true).clipCoverage(face);assertEquals(2,pieces.size());
        assertEquals(1,pieces.get(0).y());assertEquals(63,pieces.get(0).height());
        assertEquals(128,pieces.get(1).y());assertEquals(3,pieces.get(1).height());
        assertFalse(c.covers(0,130,0));assertTrue(c.covers(0,64,0));assertFalse(c.covers(0,-1,0));
    }
    @Test void noEligiblePagesMeansNoNativeSuppressionOrRefinement(){
        var c=SourceSurfaceCoverage.restricted(plan(false),Set.of());
        var batch=data(c,false).compose(null,null,()->{});
        assertEquals(0,batch.clippedSourceFaces());assertEquals(batch.sampledCells(),batch.faces());
        assertEquals(0,batch.refinedCells());
    }
    @Test void sourceFailureAndBudgetExhaustionCannotTruncate(){
        var plan=plan(false);var c=SourceSurfaceCoverage.complete(plan,plan.pageIndex().keySet());
        var tiny=new DhNativeDistanceTerrainData.Limits(1,500_000,5000,4096,128L<<20);
        assertThrows(DhNativeDistanceTerrainData.BudgetExceeded.class,()->new DhNativeDistanceTerrainData(sampler(false),0,0,DhLodLayout.EARTH_ALPINE_2048,c,tiny).compose(null,null,()->{}));
        var failure=new IllegalStateException("cancelled");assertSame(failure,assertThrows(IllegalStateException.class,()->data(c,false).compose(null,null,()->{throw failure;})));
    }
    @Test void shiftedOrUnknownNativeBoundaryCannotClipCoarseFace(){
        var plan=plan(false);var c=SourceSurfaceCoverage.complete(plan,plan.pageIndex().keySet());
        var face=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,0,1,0,2,2,0xff8a8a8a,2);
        assertThrows(IllegalStateException.class,()->data(c,false).clipCoverage(face));
    }
}
