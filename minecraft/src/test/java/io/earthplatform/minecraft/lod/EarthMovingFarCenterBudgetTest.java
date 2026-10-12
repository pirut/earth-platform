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
    static LodSummaryKey key(WorldIdentity world,Center c,String variant){
        return new LodSummaryKey(world,world.datasetVersion(),new LodSummaryKey.Volume(c.x-512,-11008,c.z-512,c.x+512,8864,c.z+512),4,0,variant);
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

    @Test void runtimeReachableTwentyFiveCentersUseNaturalFull27MasksAndReturnWithinUnchangedCaps(){
        var earth=EarthRollingFarBudgetTest.provider();var source=earth.surfaceSampler();
        var pin=EarthRollingFarBudgetTest.pin(source);var initialIdentity=source.identity();
        assertTrue(earth.naturalRelief());assertTrue(source.supportsConcurrentReads());assertTrue(source.supportsFootprintCoverage());
        assertEquals(AX,16L*Math.floorDiv(X,16));assertEquals(AZ,16L*Math.floorDiv(Z,16));
        var centers=new HashSet<Center>();var ys=new TreeSet<Integer>();var keys=new HashSet<LodSummaryKey>();
        var sampled=new Counting(source);DhAdaptiveTerrainData.Batch first=null,previous=null;
        int maxCells=0,maxFaces=0,maxTiles=0,maxPage=0,maxOwners=0;long maxDeclared=0,maxResident=0;int index=0;
        for(var delta:route()){
            Visit v=visit(source,pin,delta[0],delta[1]);centers.add(v.center);ys.add(v.candidate.y());
            assertEquals(new Center(AX+delta[0],AZ+delta[1]),v.center);
            keys.add(key(earth.identity(),v.center,"same-mask-to-isolate-center-volume"));
            // Prove an actual sequence of permitted adjacent XYZ candidate transitions reaches this representative.
            CubePos cursor=previous==null?pin:visit(source,pin,route().get(index-1)[0],route().get(index-1)[1]).candidate;
            int steps=0;while(!cursor.equals(v.candidate)){
                var next=RollingCubePlan.nextStep(cursor,v.candidate);var step=RollingCubePlan.create(pin,cursor,next);
                assertTrue(step.stagedOwners().size()<=213);assertTrue(step.stagedTargets().size()<=47);
                assertTrue(Math.abs(next.minBlockX()+8-X)<=128&&Math.abs(next.minBlockZ()+8-Z)<=128);
                cursor=next;assertTrue(++steps<=32);
            }
            var data=new DhAdaptiveTerrainData(sampled,v.center.x,v.center.z);
            assertEquals(v.center.x-512,data.originX());assertEquals(v.center.z-512,data.originZ());
            sampled.reset();var masked=data.clip(v.mask);sampled.assertReads(masked);
            sampled.reset();var fallback=data.clip(List.of(),v.mask);sampled.assertReads(fallback);
            for(var batch:List.of(masked,fallback)){
                budgets(batch);assertEquals(v.center.x==AX-128?64:0,batch.unknownCells(),"Omit 64 unsupported western leaves before center sampling");
                assertTrue(batch.refinedCells()>0);assertTrue(batch.transitionFaces()>0);
                assertTrue(batch.tiles().keySet().stream().allMatch(k->k.pageY()==3),"Real route is page 3; signed-page tests are separate");
                maxCells=Math.max(maxCells,batch.sampledCells());maxFaces=Math.max(maxFaces,batch.faces());
                maxTiles=Math.max(maxTiles,batch.tiles().size());maxDeclared=Math.max(maxDeclared,4096L+128L*batch.faces());
                for(var list:batch.tiles().values())maxPage=Math.max(maxPage,list.size());
            }
            assertEquals(masked.sampledCells(),fallback.sampledCells());assertTrue(masked.clippedSourceFaces()>0);
            assertExactNoReadyOverlap(masked,v.mask);
            long changedFaces=0;int changedTiles=0;
            for(var e:masked.tiles().entrySet())if(previous==null||!e.getValue().equals(previous.tiles().get(e.getKey()))){changedFaces+=e.getValue().size();changedTiles++;}
            long resident=88L*((previous==null?0:previous.faces())+changedFaces);
            int owners=(previous==null?0:previous.tiles().size())+changedTiles;
            assertTrue(resident<=GPU_CAP);assertTrue(owners<=128);maxResident=Math.max(maxResident,resident);maxOwners=Math.max(maxOwners,owners);
            if(first==null)first=masked;if(index==route().size()-1)assertEquals(first,masked,"Return restores exact deterministic faces and absolute page owners");
            assertEquals(initialIdentity,source.identity());assertEquals(initialIdentity,earth.identity().datasetVersion());
            System.out.println("EARTH_MOVING_FAR visit="+index+" delta="+Arrays.toString(delta)+" ground="+v.ground+" candidate="+v.candidate+" center="+v.center
                    +" targets="+v.mask.size()+" cells="+masked.sampledCells()+" unknown="+masked.unknownCells()+" faces="+masked.faces()+" fallbackFaces="+fallback.faces()
                    +" tiles="+masked.tiles().size()+" transitions="+masked.transitionFaces()+" currentAndChangedGpuUpper="+resident+" physicalOwnerUpper="+owners);
            previous=masked;index++;
        }
        assertEquals(25,centers.size());assertEquals(25,keys.size());assertTrue(ys.size()>1,"Use changing native Y from real slopes");
        assertEquals(key(earth.identity(),center(pin),"return"),key(earth.identity(),visit(source,pin,0,0).center,"return"));
        System.out.println("EARTH_MOVING_FAR_MAX centers="+centers.size()+" candidateY="+ys+" cells="+maxCells+" faces="+maxFaces+" tiles="+maxTiles+" pageFaces="+maxPage
                +" resultUpper="+maxDeclared+" fourResultCacheUpper="+(4*maxDeclared)+" currentAndChangedGpuUpper="+maxResident+" physicalOwnerUpper="+maxOwners+" source="+initialIdentity);
    }

    @Test void offCenterExtremeSnapBandsAcceptDistinctNaturalXyzMasksWithoutExpandingTheBodyDomain() throws Exception {
        var earth=EarthRollingFarBudgetTest.provider();var source=earth.surfaceSampler();var pin=EarthRollingFarBudgetTest.pin(source);
        var offsets=List.of(new int[]{-112,-112},new int[]{-112,96},new int[]{-112,112},new int[]{-128,112},
                new int[]{96,-112},new int[]{112,-112},new int[]{112,-128},new int[]{96,96},new int[]{96,112},
                new int[]{112,96},new int[]{112,112},new int[]{112,128},new int[]{128,112},
                new int[]{-112,0},new int[]{112,0},new int[]{0,-112},new int[]{0,112});
        var masksByCenter=new HashMap<Center,Set<List<CubePos>>>();var keys=new HashSet<LodSummaryKey>();var nativeY=new TreeSet<Integer>();
        int maxCells=0,maxFaces=0,maxTiles=0,maxPage=0,maxOwners=0;long maxResident=0;DhAdaptiveTerrainData.Batch previous=null;
        for(var offset:offsets){
            var v=visit(source,pin,offset[0],offset[1]);assertEquals(28,v.mask.size());nativeY.add(v.candidate.y());
            assertTrue(Math.abs(v.center.x-AX)==128||Math.abs(v.center.z-AZ)==128,"Explicit extreme-center snap band");
            masksByCenter.computeIfAbsent(v.center,k->new HashSet<>()).add(v.mask);
            String maskDigest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(v.mask.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(keys.add(key(earth.identity(),v.center,maskDigest)),"Distinct XYZ masks cannot alias a same-center content key");
            var data=new DhAdaptiveTerrainData(source,v.center.x,v.center.z);var masked=data.clip(v.mask);var fallback=data.clip(List.of(),v.mask);
            for(var batch:List.of(masked,fallback)){
                budgets(batch);assertEquals(v.center.x==AX-128?64:0,batch.unknownCells());maxCells=Math.max(maxCells,batch.sampledCells());maxFaces=Math.max(maxFaces,batch.faces());
                maxTiles=Math.max(maxTiles,batch.tiles().size());for(var list:batch.tiles().values())maxPage=Math.max(maxPage,list.size());
            }
            assertExactNoReadyOverlap(masked,v.mask);assertTrue(masked.clippedSourceFaces()>0);
            long changed=0;int changedOwners=0;
            for(var e:masked.tiles().entrySet())if(previous==null||!e.getValue().equals(previous.tiles().get(e.getKey()))){changed+=e.getValue().size();changedOwners++;}
            long resident=88L*((previous==null?0:previous.faces())+changed);int owners=(previous==null?0:previous.tiles().size())+changedOwners;
            assertTrue(resident<=GPU_CAP);assertTrue(owners<=128);maxResident=Math.max(maxResident,resident);maxOwners=Math.max(maxOwners,owners);
            System.out.println("EARTH_MOVING_SNAP_BAND delta="+Arrays.toString(offset)+" candidate="+v.candidate+" ground="+v.ground+" center="+v.center
                    +" targets="+v.mask.size()+" cells="+masked.sampledCells()+" faces="+masked.faces()+" fallbackFaces="+fallback.faces()+" tiles="+masked.tiles().size()
                    +" currentAndChangedGpuUpper="+resident+" physicalOwnerUpper="+owners);previous=masked;
        }
        assertTrue(masksByCenter.values().stream().anyMatch(masks->masks.size()>1));assertTrue(nativeY.size()>1);assertEquals(offsets.size(),keys.size());
        assertEquals(new Center(AX+128,AZ),visit(source,pin,96,0).center);assertEquals(new Center(AX+128,AZ),visit(source,pin,112,0).center);
        assertEquals(new Center(AX+128,AZ),visit(source,pin,128,0).center);assertEquals(new Center(AX-128,AZ),visit(source,pin,-128,0).center);
        assertEquals(new Center(AX-128,AZ),visit(source,pin,-112,0).center);assertEquals(new Center(AX-64,AZ),visit(source,pin,-96,0).center);
        System.out.println("EARTH_MOVING_SNAP_BAND_MAX masks="+keys.size()+" centers="+masksByCenter.size()+" nativeY="+nativeY+" cells="+maxCells+" faces="+maxFaces
                +" tiles="+maxTiles+" pageFaces="+maxPage+" resultUpper="+(4096L+128L*maxFaces)+" fourResultCacheUpper="+(4L*(4096L+128L*maxFaces))
                +" currentAndChangedGpuUpper="+maxResident+" physicalOwnerUpper="+maxOwners+" allBodiesWithin128=true");
    }

    @Test void shiftedCentersPreserveAbsoluteOverlapKeysAndExactFaceLists(){
        var earth=EarthRollingFarBudgetTest.provider();var source=earth.surfaceSampler();var pin=EarthRollingFarBudgetTest.pin(source);
        var mask=RollingCubePlan.stationary(pin,pin).committedTargets();
        Center a=new Center(AX,AZ),b=new Center(AX+64,AZ-64);
        assertNotEquals(key(earth.identity(),a,"identical-mask"),key(earth.identity(),b,"identical-mask"));
        var first=new DhAdaptiveTerrainData(source,a.x,a.z).clip(mask);var second=new DhAdaptiveTerrainData(source,b.x,b.z).clip(mask);
        assertNotEquals(first.tiles(),second.tiles());int equal=0,interior=0;
        for(var e:first.tiles().entrySet()){
            var key=e.getKey();if(e.getValue().equals(second.tiles().get(key)))equal++;
            // The outer 256m ownership box must be wholly inside both requested footprints.
            if(key.originX()>=Math.max(a.x,b.x)-512&&key.originX()+256<=Math.min(a.x,b.x)+512
                    &&key.originZ()>=Math.max(a.z,b.z)-512&&key.originZ()+256<=Math.min(a.z,b.z)+512){
                assertEquals(e.getValue(),second.tiles().get(key),"Overlapping absolute owner cannot depend on center-relative coordinates");interior++;
            }
        }
        assertTrue(interior>0);assertTrue(equal>=interior);
        assertEquals(first,new DhAdaptiveTerrainData(source,a.x,a.z).clip(mask));
        System.out.println("EARTH_MOVING_OVERLAP sameMask=true distinctVolumeKeys=true exactInteriorOwners="+interior+" allEqualOwners="+equal);
    }

    @Test void westRequestedOverhangIsConservativelyOmittedBeforeSamplingAndUnknownSourceRemainsExplicit(){
        var source=EarthRollingFarBudgetTest.provider().surfaceSampler();var pin=EarthRollingFarBudgetTest.pin(source);
        long firstKnown=9_674_290;var v=visit(source,pin,-128,0);var counting=new Counting(source);
        var data=new DhAdaptiveTerrainData(counting,v.center.x,v.center.z);long requestedMin=data.originX();
        assertEquals(9_674_288,requestedMin);assertEquals(2,firstKnown-requestedMin);
        assertThrows(MissingTerrainException.class,()->source.surfaceY(requestedMin,Z));
        assertThrows(MissingTerrainException.class,()->source.surfaceY(firstKnown-1,Z));
        assertDoesNotThrow(()->source.surfaceY(firstKnown,Z));
        var batch=data.clip(v.mask);counting.assertReads(batch);assertEquals(64,batch.unknownCells());assertEquals(64,batch.footprintRejectedCells());
        assertEquals(requestedMin+24,counting.minX);assertTrue(counting.minX>=firstKnown);
        long emittedMin=batch.tiles().entrySet().stream().flatMap(e->e.getValue().stream().filter(f->f.direction()==DhTerrainGeometry.Direction.UP).map(f->e.getKey().originX()+f.x())).mapToLong(Long::longValue).min().orElseThrow();
        assertEquals(9_674_304,emittedMin);assertFalse(source.knownFootprint(requestedMin,Z,requestedMin+16,Z+16));
        assertTrue(source.knownFootprint(emittedMin,Z,emittedMin+16,Z+16));
        // A separate boundary probe deliberately goes beyond the runtime center policy. Missing samples produce no top or skirt.
        var edge=new DhAdaptiveTerrainData(source,firstKnown,Z).clip(List.of());budgets(edge);
        assertTrue(edge.knownCells()>0);assertTrue(edge.unknownCells()>0);int tops=0;
        for(var entry:edge.tiles().entrySet())for(var face:entry.getValue()){
            long x=entry.getKey().originX()+face.x(),z=entry.getKey().originZ()+face.z();
            if(face.direction()==DhTerrainGeometry.Direction.UP){
                tops++;assertTrue(source.knownFootprint(x,z,x+face.width(),z+face.height()));assertDoesNotThrow(()->source.surfaceY(x+face.width()/2,z+face.height()/2));
                assertEquals(source.surfaceY(x+face.width()/2,z+face.height()/2)+1,(long)face.pageY2048()*2048+face.y());
            }
        }
        assertEquals(edge.knownCells(),tops,"No invented top for an unknown attempted sample");
        System.out.println("EARTH_MOVING_SOURCE_EDGE requestedMinX="+requestedMin+" firstKnownColumnX="+firstKnown+" requestedOverhangMetres=2 firstAttemptedSampleX="+counting.minX
                +" emittedMinX="+emittedMin+" footprintRejectedLeaves="+batch.footprintRejectedCells()+" boundaryProbeKnown="+edge.knownCells()+" boundaryProbeUnknown="+edge.unknownCells()
                +" coverageClaim=full-rendered-footprints; unsupported whole leaves omitted without skirts");
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
