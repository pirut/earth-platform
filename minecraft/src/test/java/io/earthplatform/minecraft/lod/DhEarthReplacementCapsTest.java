package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static io.earthplatform.minecraft.lod.DhEarthReplacementTestFixtures.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(300)
class DhEarthReplacementCapsTest {
    @Test void completeRead20Owned18CensusMatchesEveryStaticReservation()throws Exception {
        var c=DhEarthReplacementCensus.run(SOURCE);
        assertEquals(1024,c.profiles());assertEquals(25,c.farCenters());
        assertEquals(DhEarthReplacementAdmission.MAX_BASE_CELLS,c.cells());
        assertEquals(DhEarthReplacementAdmission.MAX_BASE_SOURCE_FACES,c.sourceFaces());
        assertEquals(DhEarthReplacementAdmission.MAX_BASE_FACES,c.faces());
        assertEquals(DhEarthReplacementAdmission.MAX_BASE_OWNERS,c.owners());
        assertEquals(DhEarthReplacementAdmission.MAX_BASE_PAGE_FACES,c.pageFaces());
        assertEquals(DhEarthReplacementAdmission.SOURCE_IDENTITY,SOURCE.identity());
        assertEquals(DhEarthReplacementAdmission.LAYOUT_IDENTITY,DhLodLayout.EARTH_ALPINE_2048.identity());
        assertTrue(c.faces()+8192<=32768);assertTrue(c.owners()+2<=64);
        assertTrue(4096L+8000+128L*(c.faces()+8192)<=5L*1024*1024);
        assertTrue(88L*(32768+c.faces()+8192)<=8L*1024*1024);
        assertTrue(64+c.owners()+2<=128);
        System.out.println("EARTH_READ20_FINAL "+c+" pooledFull8192="+(c.faces()+8192)+" owners="+(c.owners()+2));
    }
    @Test void revisionZeroAndDistantPinProduceExactSixMaterialFacesWithoutNativeFallback(){
        var capture=baseline();var input=input(capture,0);var sampler=sampler();var admission=new DhEarthReplacementAdmission();
        var c=admission.inspect(capture,()->{});
        assertEquals(435,c.replacementFaces());assertEquals(345,c.nativePinFaces());assertEquals(71760,c.nativePinBytes());assertTrue(c.admitted());
        assertSame(capture,input.capture().earthCapture());assertEquals(8000,input.declaredBytes());
        var pin=DhEarthReplacementAdmission.PIN;
        assertEquals(DhEarthReplacementCensus.read(pin).minX(),input.capture().readBounds().minX());
        assertEquals(DhEarthReplacementCensus.owned(pin),input.capture().ownedVolume());
        var e=SOURCE.envelope();
        for(int[] center:new int[][]{{e.minCenterX(),-686,e.minCenterZ()},{e.maxCenterXExclusive()-1,551,e.maxCenterZExclusive()-1},{pin.x(),pin.y(),pin.z()}}){
            var ready=mask(new CubePos(center[0],center[1],center[2]));
            long fx=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center[0]);
            long fz=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center[2]);
            var result=DhEarthReplacementComposition.build(sampler,fx,fz,DhLodLayout.EARTH_ALPINE_2048,ready,input,5L*1024*1024,()->{});
            var expected=DhVoxelReplacementGeometry.build(input.capture(),8192,8192L*64,()->{}).faces().stream().filter(f->!ready.contains(solidCube(f))).toList();
            assertEquals(expected.size(),result.replacementVisibleFaces());assertEquals(435,result.replacementSourceFaces());
            var actual=replacementFaces(result.batch());assertEquals(new HashSet<>(expected),new HashSet<>(actual));assertEquals(expected.size(),actual.size());
            if(center[1]==pin.y())assertEquals(0,result.replacementVisibleFaces());else assertEquals(435,result.replacementVisibleFaces());
            for(var face:actual){assertTrue(input.capture().ownedVolume().contains(solidX(face),solidY(face),solidZ(face)));assertEquals(stratifiedFaceArgb(face.direction(),capture.argbAt(solidX(face),solidY(face),solidZ(face))),face.argb());}
        }
    }
    @Test void exactFrozenCollarAndHaloHashesRejectTamperingButPermitPinChanges(){
        var original=baseline();String hash=DhEarthReplacementAdmission.outsidePinIdentity(original);
        assertEquals(DhEarthReplacementAdmission.OUTSIDE_PIN_IDENTITY,hash);
        var own=original.withMaterial(original.position().minBlockX()+3,original.position().minBlockY()+5,original.position().minBlockZ()+3,EarthReplacementCapture.DIORITE);
        assertEquals(hash,DhEarthReplacementAdmission.outsidePinIdentity(own));assertTrue(new DhEarthReplacementAdmission().assess(own).admitted());
        for(int i:new int[]{0,(1*20+1)*20+1,7999}){
            byte[] raw=bytes(original);raw[i]=(byte)(raw[i]==0?1:0);var corrupt=new EarthReplacementCapture(original.position(),raw);
            assertFalse(new DhEarthReplacementAdmission().assess(corrupt).admitted());
            assertThrows(DhReplacementComposition.Declined.class,()->new DhEarthReplacementAdmission().inspect(corrupt,()->{}));
        }
        var shifted=new EarthReplacementCapture(original.position().offset(1,0,0),bytes(original));
        assertFalse(new DhEarthReplacementAdmission().assess(shifted).admitted());
        var receipt=new DhEarthReplacementAdmission().assess(original);
        assertThrows(IllegalArgumentException.class,()->receipt.verify(own,SOURCE.identity(),DhLodLayout.EARTH_ALPINE_2048.identity()));
    }
    @Test void native5041BudgetRejectsCumulativeInteriorEditsEvenWhenFullyHiddenAndAcceptsTwoPieces(){
        var original=baseline();var candidates=isolatedAir(original);assertTrue(candidates.size()>783);
        var accepted=place(original,candidates,782);var rejected=place(original,candidates,783);
        var admission=new DhEarthReplacementAdmission();var before=admission.inspect(accepted,()->{});var after=admission.inspect(rejected,()->{});
        assertEquals(5037,before.nativePinFaces());assertEquals(5043,after.nativePinFaces());
        assertEquals(5127,before.replacementFaces());assertEquals(5133,after.replacementFaces());
        assertTrue(before.admitted());assertFalse(after.admitted());assertTrue(after.replacementFaces()<8192);
        assertTrue(before.nativePinBytes()<=1048576);assertTrue(after.nativePinBytes()>1048576);
        var distant=new CubePos(SOURCE.envelope().minCenterX(),-686,SOURCE.envelope().minCenterZ());
        var result=build(accepted,mask(distant));assertEquals(2,result.replacementOwners());assertEquals(5127,result.replacementVisibleFaces());
        var replacement=result.batch().tiles().entrySet().stream().filter(e->e.getKey().replacementIdentity()!=null&&!e.getKey().replacementIdentity().isEmpty()).toList();
        assertEquals(2,replacement.size());assertEquals(4096,replacement.getFirst().getValue().size());assertEquals(1031,replacement.getLast().getValue().size());
        assertTrue(replacementFaces(result.batch()).stream().anyMatch(f->f.material()==8));assertTrue(replacementFaces(result.batch()).stream().anyMatch(f->f.material()==2));
        var hidden=mask(DhEarthReplacementAdmission.PIN);
        assertThrows(DhReplacementComposition.Declined.class,()->build(rejected,hidden));
        assertTrue(admission.assess(accepted).admitted());assertEquals(435,EarthReplacementAdmission.faces(original));
        System.out.println("EARTH_NATIVE_CUMULATIVE accepted=782 native5037 replacement5127 rejected=783 native5043 replacement5133 twoPieces=true hiddenStillRejects=true");
    }
    @Test void exactSourcePolicyCenterAndPooledBytesFailClosed(){
        var capture=baseline();var input=input(capture,0);var ready=mask(new CubePos(SOURCE.envelope().minCenterX(),-686,SOURCE.envelope().minCenterZ()));
        long fx=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(ready.get(13).x());long fz=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(ready.get(13).z());
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.build(sampler(),fx,fz,DhLodLayout.EARTH_ALPINE_2048,ready,null,5L*1024*1024,()->{}));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.build(sampler(),fx+1,fz,DhLodLayout.EARTH_ALPINE_2048,ready,input,5L*1024*1024,()->{}));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.build(sampler(),fx,fz,DhLodLayout.LONG_4096,ready,input,5L*1024*1024,()->{}));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.build(sampler(),fx,fz,DhLodLayout.EARTH_ALPINE_2048,ready,input,0,()->{}));
        var wrong=new SceneSurfaceSampler(){public long surfaceY(long x,long z){throw new AssertionError("Reject before sampling");}public int argb(long x,long z,long y){throw new AssertionError();}public String identity(){return "wrong";}};
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.build(wrong,fx,fz,DhLodLayout.EARTH_ALPINE_2048,ready,input,5L*1024*1024,()->{}));
        class Cancelled extends RuntimeException{};
        assertThrows(Cancelled.class,()->DhEarthReplacementComposition.build(sampler(),fx,fz,DhLodLayout.EARTH_ALPINE_2048,ready,input,5L*1024*1024,()->{throw new Cancelled();}));
    }
    @Test void onlyTheCompleteFiniteMoving27ProfileCanUseTheStaticReservation(){
        var center=new CubePos(SOURCE.envelope().minCenterX(),-686,SOURCE.envelope().minCenterZ());var ready=mask(center);
        long fx=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center.x()),fz=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center.z());
        assertEquals(center,DhEarthReplacementComposition.requireReadyProfile(ready,fx,fz));
        var reordered=new ArrayList<>(ready);Collections.reverse(reordered);assertEquals(center,DhEarthReplacementComposition.requireReadyProfile(reordered,fx,fz));
        var duplicate=new ArrayList<>(ready);duplicate.set(26,duplicate.getFirst());
        var extra=new ArrayList<>(ready);extra.add(DhEarthReplacementAdmission.PIN);
        var malformed=new ArrayList<>(ready);malformed.set(0,ready.getFirst().offset(-1,0,0));
        for(var invalid:List.of(List.<CubePos>of(),ready.subList(0,26),duplicate,extra,malformed))
            assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.requireReadyProfile(invalid,fx,fz));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.requireReadyProfile(ready,fx+128,fz));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.requireReadyProfile(mask(center.offset(-1,0,0)),fx,fz));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.requireReadyProfile(mask(center.offset(0,-1,0)),fx,fz));
        assertThrows(DhReplacementComposition.Declined.class,()->DhEarthReplacementComposition.build(sampler(),fx,fz,DhLodLayout.EARTH_ALPINE_2048,duplicate,input(baseline(),0),5L*1024*1024,()->{}));
    }
    @Test void fixedOwned18AndAnyReadyYSubsetCannotFragmentRefinedThreeBlockWalls(){
        int cases=0;
        for(Direction d:List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST))
            for(int phase=0;phase<16;phase++)for(int height=1;height<=3;height++)for(int start=-20;start<=20;start++){
                int y=6144+phase;var face=new Face(d,d==Direction.EAST?1:0,y,d==Direction.SOUTH?1:0,1,height,0xff888888,2);
                var first=CubePos.fromBlock(0,y,0);var last=CubePos.fromBlock(0,y+height-1,0);var relevant=first.equals(last)?List.of(first):List.of(first,last);
                var volume=new Volume(-1,6144+start,-1,17,6144+start+18,17);
                for(int mask=0;mask<(1<<relevant.size());mask++){
                    var ready=new ArrayList<CubePos>();for(int i=0;i<relevant.size();i++)if((mask&(1<<i))!=0)ready.add(relevant.get(i));
                    var actual=clip(face,ready,List.of(volume),16);assertTrue(actual.size()<=1);
                    int expected=0;for(int dy=0;dy<height;dy++)if(!ready.contains(CubePos.fromBlock(0,y+dy,0))&&!volume.contains(0,y+dy,0))expected++;
                    assertEquals(expected,actual.stream().mapToInt(Face::height).sum());
                    for(var f:actual)assertTrue(splitPages(f,-32,-32).size()<=splitPages(face,-32,-32).size());cases++;
                }
            }
        assertEquals(16728,cases);System.out.println("EARTH_OWNED18_ALL_Y cases="+cases+" allReadySubsets=true maxFragments=1");
    }
    @Test void baseFacesHaveNoOwnedVolumeOverlapAndSourceMaterialInterfacesNeverBecomeNativeFaces(){
        var pin=DhEarthReplacementAdmission.PIN;var read=DhEarthReplacementCensus.read(pin);var owned=DhEarthReplacementCensus.owned(pin);
        var center=new CubePos(SOURCE.envelope().minCenterX(),-686,SOURCE.envelope().minCenterZ());
        long fx=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center.x()),fz=io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center.z());
        var base=new DhAdaptiveTerrainData(sampler(),fx,fz,DhLodLayout.EARTH_ALPINE_2048).clipWithRefinementVolumes(List.of(),mask(center),List.of(owned),List.of(read),()->{});
        for(var entry:base.tiles().entrySet())for(var p:entry.getValue()){
            var f=global(entry.getKey(),p);assertEquals(List.of(f),clip(f,List.of(),List.of(owned),16));
        }
        var original=baseline();byte[] raw=bytes(original);var p=original.position();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int i=((y+2)*20+z+2)*20+x+2;if(raw[i]!=0)raw[i]=(byte)(1+Math.floorMod(x+y+z,5));
        }
        var recolored=new EarthReplacementCapture(p,raw);
        assertEquals(EarthReplacementAdmission.faces(original),EarthReplacementAdmission.faces(recolored));
        assertEquals(DhEarthReplacementAdmission.nativePinFaces(original,()->{}),DhEarthReplacementAdmission.nativePinFaces(recolored,()->{}));
        assertEquals(DhEarthReplacementAdmission.OUTSIDE_PIN_IDENTITY,DhEarthReplacementAdmission.outsidePinIdentity(recolored));
    }
    private static DhEarthAlpineGeometryCensus.CachedSampler sampler(){return new DhEarthAlpineGeometryCensus.CachedSampler(SOURCE);}
    private static List<CubePos> mask(CubePos center){var cubes=new ArrayList<CubePos>();for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)cubes.add(center.offset(x,y,z));return List.copyOf(cubes);}
    private static DhReplacementComposition.Result build(EarthReplacementCapture c,List<CubePos> ready){var center=ready.get(13);return DhEarthReplacementComposition.build(sampler(),io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center.x()),io.earthplatform.geography.earth.EarthAlpineDefinition.snapCubeCenter(center.z()),DhLodLayout.EARTH_ALPINE_2048,ready,input(c,1),5L*1024*1024,()->{});}
    private static byte[] bytes(EarthReplacementCapture c){byte[] out=new byte[8000];int i=0;for(long y=c.minY();y<c.maxYExclusive();y++)for(long z=c.minZ();z<c.maxZExclusive();z++)for(long x=c.minX();x<c.maxXExclusive();x++)out[i++]=(byte)c.materialAt(x,y,z);return out;}
    private static List<long[]> isolatedAir(EarthReplacementCapture c){var out=new ArrayList<long[]>();var p=c.position();for(long y=p.minBlockY()+1;y<p.minBlockY()+15;y++)for(long z=p.minBlockZ()+1;z<p.minBlockZ()+15;z++)for(long x=p.minBlockX()+1;x<p.minBlockX()+15;x++)if(((x+y+z)&1)==0&&c.materialAt(x,y,z)==0&&c.materialAt(x-1,y,z)==0&&c.materialAt(x+1,y,z)==0&&c.materialAt(x,y-1,z)==0&&c.materialAt(x,y+1,z)==0&&c.materialAt(x,y,z-1)==0&&c.materialAt(x,y,z+1)==0)out.add(new long[]{x,y,z});return out;}
    private static EarthReplacementCapture place(EarthReplacementCapture c,List<long[]> points,int n){byte[] raw=bytes(c);for(int i=0;i<n;i++){var p=points.get(i);raw[Math.toIntExact(((p[1]-c.minY())*20+p[2]-c.minZ())*20+p[0]-c.minX())]=(byte)(i%2==0?5:2);}return new EarthReplacementCapture(c.position(),raw);}
    private static long solidX(Face f){return f.x()-(f.direction()==Direction.EAST?1:0);}private static long solidY(Face f){return f.y()-(f.direction()==Direction.UP?1:0);}private static long solidZ(Face f){return f.z()-(f.direction()==Direction.SOUTH?1:0);}private static CubePos solidCube(Face f){return CubePos.fromBlock(solidX(f),solidY(f),solidZ(f));}
    private static Face global(DhGeographicLodData.TileKey key,PageFace f){return new Face(f.direction(),key.originX()+f.x(),(long)key.pageY()*2048+f.y(),key.originZ()+f.z(),f.width(),f.height(),f.argb(),f.material());}
    private static List<Face> replacementFaces(DhAdaptiveTerrainData.Batch batch){var result=new ArrayList<Face>();batch.tiles().forEach((key,faces)->{if(key.replacementIdentity()!=null&&!key.replacementIdentity().isEmpty())for(var f:faces)result.add(global(key,f));});return result;}
}
