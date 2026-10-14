package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real immutable Earth source, moving 512m summaries and full XYZ masks. CPU evidence only. */
@Timeout(60)
class EarthMovingFarCenterBudgetTest {
    static final long X=9_674_936, Z=-3_112_472;
    static final long AX=9_674_928, AZ=-3_112_480;
    static final long RESULT_CAP=5L*1024*1024, CACHE_CAP=20L*1024*1024, GPU_CAP=8L*1024*1024;
    record Center(long x,long z) { }
    record Visit(CubePos candidate,Center center,List<CubePos> mask,long ground) { }
    @BeforeAll static void registries(){EarthRollingFarBudgetTest.registries();}

    /** Independent expression of the host policy, not a call into the renderer under test. */
    static long snapped(long anchor,int candidateCube){
        long a=16L*Math.floorDiv(anchor,16),b=16L*candidateCube+8;
        return a+64L*Math.floorDiv(b-a+32,64);
    }
    static Center center(CubePos candidate){return new Center(snapped(X,candidate.x()),snapped(Z,candidate.z()));}
    static Visit visit(SceneSurfaceSampler source,CubePos pin,int dx,int dz){
        assertTrue(Math.abs(dx)<=128&&Math.abs(dz)<=128,"Body stays within the authorized anchor square");
        long x=X+dx,z=Z+dz,ground=source.surfaceY(x,z);
        var candidate=RollingCubePlan.centerFromFeet(x,ground+1,z);
        var plan=RollingCubePlan.stationary(pin,candidate);
        assertEquals(27,plan.currentTargets().size());
        var mask=plan.committedTargets();assertTrue(mask.contains(pin));
        assertEquals(plan.currentTargets().contains(pin)?27:28,mask.size());
        assertTrue(plan.committedOwners().size()<=152);
        return new Visit(candidate,center(candidate),mask,ground);
    }
    static List<int[]> route(){
        var result=new ArrayList<int[]>();result.add(new int[]{0,0});
        // Adjacent intermediate cube steps exist throughout this bounded square. These are its 25 summary-center representatives.
        for(int z=-128,row=0;z<=128;z+=64,row++)for(int i=0;i<5;i++){
            int x=(row%2==0?-128+64*i:128-64*i);if(x!=0||z!=0)result.add(new int[]{x,z});
        }
        result.add(new int[]{0,0});return List.copyOf(result);
    }
    static void budgets(DhAdaptiveTerrainData.Batch batch){
        assertEquals(batch.sampledCells(),batch.knownCells()+batch.unknownCells());
        assertEquals(batch.sampledCells(),batch.centerSampledCells()+batch.footprintRejectedCells());
        assertEquals(batch.knownCells(),batch.fullySupportedCells(),"Actual Earth leaves require full footprint support");
        assertTrue(batch.sampledCells()<=12288);assertTrue(batch.sourceFaces()<=32768);assertTrue(batch.faces()<=32768);
        assertTrue(batch.tiles().size()<=64);assertTrue(4096L+128L*batch.faces()<=RESULT_CAP);
        assertTrue(4L*(4096L+128L*batch.faces())<=CACHE_CAP);assertTrue(88L*batch.faces()<=GPU_CAP);
        int counted=0;
        for(var entry:batch.tiles().entrySet()){
            assertTrue(entry.getValue().size()<=4096);counted+=entry.getValue().size();
            for(var face:entry.getValue()){
                assertEquals(entry.getKey().pageY(),face.pageY2048());
                assertTrue(face.x()>=0&&face.x()<=256);assertTrue(face.z()>=0&&face.z()<=256);
                if(face.direction()==DhTerrainGeometry.Direction.UP)assertTrue(face.y()>0&&face.y()<=2048);
                else assertTrue(face.y()>=0&&face.y()+face.height()<=2048);
            }
        }
        assertEquals(batch.faces(),counted);
    }

    @Test void signedPageSplitsRetainExactNativeCoordinatesWithoutWrapping(){
        // Explicit synthetic codec boundary coverage, not a claim that the Everest route visits negative elevations.
        for(long y:List.of(-4097L,-2049L,-1L,2047L,6143L)){
            var face=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.EAST,AX+16,y,AZ,16,3,0xff102030);
            var pages=DhTerrainGeometry.splitPages(face,AX,AZ);assertEquals(2,pages.size());int total=0;long next=y;
            for(var page:pages){
                assertEquals(Math.floorDiv(next,2048),page.pageY2048());assertEquals(next,(long)page.pageY2048()*2048+page.y());
                assertTrue(page.y()>=0&&page.y()+page.height()<=2048);assertEquals(16,page.x());assertEquals(0,page.z());
                total+=page.height();next+=page.height();
            }
            assertEquals(face.height(),total);
        }
        var top=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,AX,-2048,AZ,16,16,0xff102030);
        var page=DhTerrainGeometry.splitPages(top,AX,AZ).getFirst();assertEquals(-2,page.pageY2048());assertEquals(2048,page.y());
    }

    /** Independent rectangle/cube intersection test with solid-side ownership, without production clip helpers. */
    static void assertExactNoReadyOverlap(DhAdaptiveTerrainData.Batch batch,List<CubePos> ready){
        batch.tiles().forEach((key,faces)->{for(var face:faces){
            long x=key.originX()+face.x(),y=(long)face.pageY2048()*2048+face.y(),z=key.originZ()+face.z();
            for(var cube:ready){long cx=cube.minBlockX(),cy=cube.minBlockY(),cz=cube.minBlockZ();
                boolean overlap=switch(face.direction()){
                    case UP -> cy<y&&y<=cy+16&&x<cx+16&&x+face.width()>cx&&z<cz+16&&z+face.height()>cz;
                    case EAST -> cx<x&&x<=cx+16&&y<cy+16&&y+face.height()>cy&&z<cz+16&&z+face.width()>cz;
                    case WEST -> cx<=x&&x<cx+16&&y<cy+16&&y+face.height()>cy&&z<cz+16&&z+face.width()>cz;
                    case SOUTH -> cz<z&&z<=cz+16&&y<cy+16&&y+face.height()>cy&&x<cx+16&&x+face.width()>cx;
                    case NORTH -> cz<=z&&z<cz+16&&y<cy+16&&y+face.height()>cy&&x<cx+16&&x+face.width()>cx;
                    case DOWN -> throw new AssertionError("Surface-only source cannot invent bottoms");
                };assertFalse(overlap,()->"Far face overlaps an exact ready XYZ cube: "+face+" / "+cube);
            }
        }});
    }
    static final class Counting implements SceneSurfaceSampler{
        final SceneSurfaceSampler source;int heights,colors,footprints,rejectedFootprints;long minX=Long.MAX_VALUE;
        Counting(SceneSurfaceSampler source){this.source=source;}
        void reset(){heights=colors=footprints=rejectedFootprints=0;minX=Long.MAX_VALUE;}
        void assertReads(DhAdaptiveTerrainData.Batch batch){
            assertEquals(batch.sampledCells(),footprints);assertEquals(batch.centerSampledCells(),heights);assertEquals(batch.footprintRejectedCells(),rejectedFootprints);
            assertEquals(batch.sampledCells()-rejectedFootprints,heights);assertEquals(batch.knownCells(),colors);assertTrue(heights<=12288);
        }
        public boolean supportsFootprintCoverage(){return source.supportsFootprintCoverage();}
        public boolean knownFootprint(long x,long z,long x1,long z1){
            footprints++;try{boolean known=source.knownFootprint(x,z,x1,z1);if(!known)rejectedFootprints++;return known;}
            catch(MissingTerrainException missing){rejectedFootprints++;throw missing;}
        }
        public long surfaceY(long x,long z){heights++;minX=Math.min(minX,x);return source.surfaceY(x,z);}
        public int argb(long x,long z,long y){colors++;return source.argb(x,z,y);}
        public String identity(){return source.identity();}public boolean supportsConcurrentReads(){return source.supportsConcurrentReads();}
    }
}
