package io.earthplatform.minecraft.visual;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarthDistanceSurfacePlanTest {
    static final EarthDistanceSurfacePlan.Limits LIMITS=new EarthDistanceSurfacePlan.Limits(4_000_000,160L<<20,2_000_000,100_000,100_000,10_000_000,3_000_000_000L,128L<<20);
    static EarthAlpineDefinition.Envelope envelope(int cx,int cz,int centers){return new EarthAlpineDefinition.Envelope(cx,cx+centers,cz,cz+centers,-16,16,-4096,4096,-4096,4096);}
    static EarthDistanceSurfacePlan.Source source(int max,int guard,EarthDistanceSurfacePlan.ColumnSampler sampler){
        return EarthDistanceSurfacePlan.Source.prepareSampled(envelope(0,0,1),-256,256,"measured-fixture","detail-fixture","material-fixture",max,guard,LIMITS,sampler);
    }
    static EarthDistanceSurfacePlan.Sample flat(long h){return new EarthDistanceSurfacePlan.Sample(h,5,3,7,0,h,0);}
    static EarthDistanceSurfacePlan.Source flatSource(int max,int guard,long h){return source(max,guard,(x,z)->flat(h));}
    static EarthReplacementCapture capture(EarthDistanceSurfacePlan.Source s,CubePos p){
        try(var b=new EarthReplacementCapture.Builder(p)){
            for(long y=p.minBlockY()-2;y<p.minBlockY()+18;y++)for(long z=p.minBlockZ()-2;z<p.minBlockZ()+18;z++)for(long x=p.minBlockX()-2;x<p.minBlockX()+18;x++)b.setNext(s.materialAt(x,y,z));
            return b.finish();
        }
    }
    static EarthDistanceSurfacePlan plan(EarthDistanceSurfacePlan.Source s,EarthReplacementCapture c,int r,int guard){return EarthDistanceSurfacePlan.build(s,c,new CubePos(0,0,0),r,guard,LIMITS);}
    record Face(long x,long y,long z,EarthDistanceSurfacePlan.Direction direction,int material){}

    @Test void arbitrarySelectedDistancesAreNotClampedAndTwelveAndTwentyFourAreOrdinaryCases(){
        var s=flatSource(32,1,-1);
        for(int r:new int[]{2,3,5,7,12,17,24,31,32}){
            var p=plan(s,null,r,1);long min=Math.floorDiv(-16L*r,64)*64-64,max=-Math.floorDiv(-16L*(r+1),64)*64+64;
            assertEquals(r,p.requestedChunks());assertEquals(new EarthDistanceSurfacePlan.Bounds(min,min,max,max),p.bounds());
            assertEquals(p.bounds().columns(),p.quads());assertEquals(p.quads()*208,p.vertexBytes());assertEquals(p.quads()*24,p.conservativeIndexBytes());
            assertEquals(p.quads()*232,p.geometryBytes());assertEquals(p.pageCount(),p.pieces());
            for(var page:p.pages()){assertEquals(-1,page.key().y());assertEquals(4096,page.quads());assertEquals(1,page.pieces());assertEquals(0,Math.floorMod(page.key().minX(),64));}
        }
        for(int r:new int[]{Integer.MIN_VALUE,0,1,33,Integer.MAX_VALUE})assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->plan(s,null,r,0));
    }
    @Test void sharedSlabIncludesEveryAllowedCenterOnceAndOneColumnHalo(){
        var calls=new AtomicInteger();var env=envelope(-16,-16,32);
        var s=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,"s","d","m",2,1,LIMITS,(x,z)->{calls.incrementAndGet();return flat(-1);});
        assertEquals(s.sampledColumns(),calls.get());assertEquals(s.ownedBounds().minX()-1,s.bounds().minX());assertEquals(s.ownedBounds().maxXExclusive()+1,s.bounds().maxXExclusive());
        for(int z:new int[]{-16,15})for(int x:new int[]{-16,15}){
            var p=EarthDistanceSurfacePlan.build(s,null,new CubePos(x,0,z),2,1,LIMITS);assertTrue(s.ownedBounds().contains(p.bounds()));
        }
        assertEquals(s.sampledColumns(),calls.get());assertEquals(7,s.sourceClass(-1,-1));assertEquals(0,s.proceduralDeltaMetres(-1,-1));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(s,null,new CubePos(16,0,0),2,1,LIMITS));
    }
    @Test void negativeCoordinatesUseFloorPagesAndPreserveSourceMaterials(){
        var s=EarthDistanceSurfacePlan.Source.prepareSampled(envelope(-5,-7,1),-256,256,"s","d","m",12,0,LIMITS,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,2,-1.125,.125));
        var p=EarthDistanceSurfacePlan.build(s,null,new CubePos(-5,0,-7),12,0,LIMITS);
        assertEquals(-320,p.bounds().minX());assertEquals(-320,p.bounds().minZ());assertEquals(128,p.bounds().maxXExclusive());assertEquals(128,p.bounds().maxZExclusive());
        assertEquals(5,p.materialAt(-1,-1,-1));assertEquals(3,p.materialAt(-1,-4,-1));assertEquals(1,p.materialAt(-1,-5,-1));assertEquals(0,p.materialAt(-1,0,-1));
        assertEquals(-1.125,s.sourceMetres(-1,-1));assertEquals(.125,s.proceduralDeltaMetres(-1,-1));assertEquals(2,s.materialReceipt(-1,-1));
    }
    @Test void tallCliffCountsAllSignedPagesAndBoundedPiecesWithoutDenseVoxels(){
        var s=source(2,0,(x,z)->flat(x<0?-75:200));var p=plan(s,null,2,0);
        assertEquals(128L*128+128L*275,p.quads());assertEquals(14,p.pageCount());
        var ys=new TreeSet<Long>();for(var page:p.pages()){ys.add(page.key().y());assertEquals((page.quads()+5040)/5041,page.pieces());}
        assertEquals(Set.of(-2L,-1L,0L,1L,2L,3L),ys);
        long[] emitted={0};for(var page:p.pages()){
            long[] count={0};p.forEachFace(page.key(),(x,y,z,d,m)->{assertTrue(page.key().contains(x,y,z));assertEquals(s.materialAt(x,y,z),m);count[0]++;});
            assertEquals(page.quads(),count[0]);emitted[0]+=count[0];
        }assertEquals(p.quads(),emitted[0]);
    }
    @Test void buriedCaveExposesAllSixDirectionsAndAgreesWithIndependentVoxelOracle(){
        var s=flatSource(2,0,15);var c=capture(s,new CubePos(0,0,0)).withMaterial(5,5,5,0);var p=plan(s,c,2,0);
        assertEquals(p.bounds().columns()+6,p.quads());assertEquals(0,p.materialAt(5,5,5));assertEquals(s.materialAt(-1,5,5),p.materialAt(-1,5,5));
        Set<Face> actual=new HashSet<>();EnumSet<EarthDistanceSurfacePlan.Direction> cave=EnumSet.noneOf(EarthDistanceSurfacePlan.Direction.class);
        for(var page:p.pages())p.forEachFace(page.key(),(x,y,z,d,m)->{assertTrue(actual.add(new Face(x,y,z,d,m)),"Duplicate face");if(y<15)cave.add(d);});
        Set<Face> expected=new HashSet<>();var b=p.bounds();
        for(long z=b.minZ();z<b.maxZExclusive();z++)for(long x=b.minX();x<b.maxXExclusive();x++)for(long y=0;y<=16;y++){
            int material=oracle(s,c,x,y,z);if(material==0)continue;
            for(var d:EarthDistanceSurfacePlan.Direction.values())if(oracle(s,c,x+d.dx(),y+d.dy(),z+d.dz())==0)expected.add(new Face(x,y,z,d,material));
        }
        assertEquals(EnumSet.allOf(EarthDistanceSurfacePlan.Direction.class),cave);assertEquals(expected,actual);
    }
    private static int oracle(EarthDistanceSurfacePlan.Source s,EarthReplacementCapture c,long x,long y,long z){
        var e=c.editedCube();return x>=e.minBlockX()&&x<e.minBlockX()+16&&y>=e.minBlockY()&&y<e.minBlockY()+16&&z>=e.minBlockZ()&&z<e.minBlockZ()+16?c.materialAt(x,y,z):s.materialAt(x,y,z);
    }
    @Test void surfaceRemovalAndFloatingPlacementUseExactOcclusionAndDioritePalette(){
        var s=flatSource(2,0,15);var hole=plan(s,capture(s,new CubePos(0,0,0)).withMaterial(5,15,5,0),2,0);
        assertEquals(hole.bounds().columns()+4,hole.quads());
        var floating=plan(s,capture(s,new CubePos(0,1,0)).withMaterial(5,18,5,EarthReplacementCapture.DIORITE),2,0);
        assertEquals(floating.bounds().columns()+6,floating.quads());long[] diorite={0};
        for(var page:floating.pages())floating.forEachFace(page.key(),(x,y,z,d,m)->{if(m==2){assertEquals(18,y);diorite[0]++;}});assertEquals(6,diorite[0]);
    }
    @Test void materialOnlyEditInvalidatesOnlyAffectedPhysicalPagesAndMovementReusesTheRest(){
        var s=EarthDistanceSurfacePlan.Source.prepareSampled(envelope(0,0,2),-256,256,"s","d","m",5,0,LIMITS,(x,z)->flat(15));
        var original=capture(s,new CubePos(0,0,0));var edited=original.withMaterial(5,15,5,2);
        var a=plan(s,original,5,0);var b=plan(s,edited,5,0);assertEquals(a.quads(),b.quads());
        int changed=0,unchanged=0;for(var page:a.pages()){
            var other=b.pageIndex().get(page.key());if(page.identity().equals(other.identity()))unchanged++;else changed++;
        }assertTrue(changed>0);assertTrue(unchanged>0);
        var moved=EarthDistanceSurfacePlan.build(s,edited,new CubePos(1,0,1),5,0,LIMITS);
        for(var page:b.pages())if(moved.pageIndex().containsKey(page.key()))assertEquals(page.identity(),moved.pageIndex().get(page.key()).identity());
        assertNotEquals(a.identity(),b.identity());assertNotEquals(b.identity(),moved.identity());
    }
    @Test void sourceContentHashProtectsPhysicalPagesEvenWhenFixtureProviderLabelsMatch(){
        var a=plan(flatSource(2,0,15),null,2,0);var b=plan(flatSource(2,0,14),null,2,0);
        var key=a.pages().getFirst().key();assertNotEquals(a.pageIndex().get(key).identity(),b.pageIndex().get(key).identity());assertNotEquals(a.identity(),b.identity());
    }
    @Test void sourceAndBudgetRefusalsOccurWithoutSamplingOrSilentlyReducingDistance(){
        var calls=new AtomicInteger();var tiny=new EarthDistanceSurfacePlan.Limits(10,1024,10,1,1,1,232,1024);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(envelope(0,0,1),-256,256,"s","d","m",2,0,tiny,(x,z)->{calls.incrementAndGet();return flat(0);}));assertEquals(0,calls.get());
        var unsupported=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-32,32,-32,32);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(unsupported,-256,256,"s","d","m",2,0,LIMITS,(x,z)->{calls.incrementAndGet();return flat(0);}));assertEquals(0,calls.get());
        var s=flatSource(2,0,15);assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->plan(s,null,3,0));assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->plan(s,null,2,1));
        var geometryCap=new EarthDistanceSurfacePlan.Limits(4_000_000,160L<<20,2_000_000,100_000,100_000,100,23200,128L<<20);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(s,null,new CubePos(0,0,0),2,0,geometryCap));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->source(2,0,(x,z)->flat(255)));
    }
    @Test void faceBoundsAndMemberMasksExactlyMatchEmittedFacesIncludingCaveCeilings(){
        var flatPlan=plan(flatSource(2,0,-1),null,2,0);
        for(var page:flatPlan.pages()){
            assertEquals(16,page.memberCount());assertEquals(0xffff000000000000L,page.memberMask());
            assertEquals(0,page.faceBounds().minY());assertEquals(0,page.faceBounds().maxY());
        }
        var s=source(2,0,(x,z)->flat(x<0?-75:200));var c=capture(s,new CubePos(0,-5,0)).withMaterial(5,-70,5,0);var p=plan(s,c,2,0);
        for(var page:p.pages()){
            long[] bounds={Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE};long[] mask={0};
            p.forEachFace(page.key(),(x,y,z,d,m)->{
                long x0=x+(d==EarthDistanceSurfacePlan.Direction.EAST?1:0),x1=x+(d==EarthDistanceSurfacePlan.Direction.WEST?0:1);
                long y0=y+(d==EarthDistanceSurfacePlan.Direction.UP?1:0),y1=y+(d==EarthDistanceSurfacePlan.Direction.DOWN?0:1);
                long z0=z+(d==EarthDistanceSurfacePlan.Direction.SOUTH?1:0),z1=z+(d==EarthDistanceSurfacePlan.Direction.NORTH?0:1);
                bounds[0]=Math.min(bounds[0],x0);bounds[1]=Math.min(bounds[1],y0);bounds[2]=Math.min(bounds[2],z0);
                bounds[3]=Math.max(bounds[3],x1);bounds[4]=Math.max(bounds[4],y1);bounds[5]=Math.max(bounds[5],z1);
                int bit=(page.key().localY(y)/16*4+page.key().localZ(z)/16)*4+page.key().localX(x)/16;mask[0]|=1L<<bit;
                assertTrue(page.ownsMember(CubePos.fromBlock(x,y,z)));assertTrue(page.faceBounds().containsVertex(x0,y0,z0));assertTrue(page.faceBounds().containsVertex(x1,y1,z1));
            });
            assertEquals(new EarthDistanceSurfacePlan.FaceBounds(bounds[0],bounds[1],bounds[2],bounds[3],bounds[4],bounds[5]),page.faceBounds());assertEquals(mask[0],page.memberMask());
        }
    }
    @Test void steepCheckerboardSplitsPagesIntoAtMost5041QuadPieces(){
        var p=plan(source(2,0,(x,z)->flat(((x+z)&1)==0?0:63)),null,2,0);
        assertTrue(p.pages().stream().anyMatch(page->page.pieces()>1));
        assertEquals(p.bounds().columns()+p.bounds().columns()/2*4*63,p.quads());
        for(var page:p.pages()){
            long remaining=page.quads();for(int i=0;i<page.pieces();i++){long piece=Math.min(5041,remaining);assertTrue(piece>0&&piece<=5041);remaining-=piece;}
            assertEquals(0,remaining);
        }
        var capped=new EarthDistanceSurfacePlan.Limits(4_000_000,160L<<20,2_000_000,100_000,1,10_000_000,3_000_000_000L,128L<<20);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(p.source(),null,p.center(),2,0,capped));
    }
    @Test void pageAndCoverageBudgetRefusalsNeverIssuePartialPlans(){
        var s=flatSource(2,0,15);
        var pages=new EarthDistanceSurfacePlan.Limits(4_000_000,160L<<20,2_000_000,1,100_000,10_000_000,3_000_000_000L,128L<<20);
        var coverage=new EarthDistanceSurfacePlan.Limits(4_000_000,160L<<20,2_000_000,100_000,100_000,10_000_000,3_000_000_000L,1024);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(s,null,new CubePos(0,0,0),2,0,pages));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.build(s,null,new CubePos(0,0,0),2,0,coverage));
    }
    @Test void overflowAndForeignCaptureHaloAreExplicitRefusals(){
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->new EarthDistanceSurfacePlan.Bounds(Long.MIN_VALUE,0,Long.MAX_VALUE,1));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(envelope(0,0,1),-256,256,"s","d","m",32,Integer.MAX_VALUE,LIMITS,(x,z)->flat(0)));
        var s=flatSource(2,0,15);var pos=new CubePos(0,0,0);byte[] values=new byte[8000];int i=0;
        for(long y=-2;y<18;y++)for(long z=-2;z<18;z++)for(long x=-2;x<18;x++)values[i++]=(byte)s.materialAt(x,y,z);
        values[0]=0;var foreign=new EarthReplacementCapture(pos,values);assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->plan(s,foreign,2,0));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->plan(s,capture(s,new CubePos(0,15,0)),2,0));
    }
}
